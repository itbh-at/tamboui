/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.image.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.error.RuntimeIOException;
import dev.tamboui.image.ImageData;
import dev.tamboui.image.capability.TerminalImageProtocol;
import dev.tamboui.layout.Rect;

/**
 * Renders images using the Sixel graphics protocol.
 * <p>
 * Sixel is a DEC standard from the 1980s that encodes images as 6-pixel-high
 * horizontal strips. Each strip is encoded using ASCII characters where each
 * character represents a vertical column of 6 pixels.
 * <p>
 * Sixel is supported by: xterm (with configuration), mlterm, mintty, WezTerm,
 * Rio, Konsole (22+), and other terminals.
 *
 * <h2>Protocol Format</h2>
 * <pre>
 * ESC P [params] q [data] ESC \
 * </pre>
 *
 * @see <a href="https://en.wikipedia.org/wiki/Sixel">Sixel on Wikipedia</a>
 */
public final class SixelProtocol implements ImageProtocol {

    private static final String DCS = "\033P";  // Device Control String
    private static final String ST = "\033\\";   // String Terminator
    private static final int MAX_COLORS = 256;
    private static final int SIXEL_HEIGHT = 6;

    // Sixel character offset - character '?' (63) represents all-zero, '~' (126) represents all-ones
    private static final int SIXEL_OFFSET = 63;

    private final int maxColors;
    private final NativeImageCache cache = new NativeImageCache();

    /**
     * Creates a Sixel protocol with default settings (256 colors).
     */
    public SixelProtocol() {
        this(MAX_COLORS);
    }

    /**
     * Creates a Sixel protocol with a custom color limit.
     *
     * @param maxColors maximum number of colors in the palette (1-256)
     */
    public SixelProtocol(int maxColors) {
        this.maxColors = Math.max(1, Math.min(MAX_COLORS, maxColors));
    }

    @Override
    public void render(ImageData image, Rect area, Buffer buffer, OutputStream rawOutput) throws IOException {
        if (rawOutput == null) {
            throw new RuntimeIOException("Sixel protocol requires raw output stream");
        }

        if (area.isEmpty()) {
            return;
        }

        // Skip re-transmission when the same image is already shown at the same position.
        // Besides saving the (expensive) re-encoding and re-write each frame, this avoids
        // repainting Sixel pixels the terminal already shows. The image stays on screen
        // because the diff-based renderer leaves the image cells untouched between frames.
        // A change in screen generation (clear/resize) forces a redraw.
        List<Rect> stale = cache.staleAreasToClear(image, area, NativeImageCache.generationOf(rawOutput));
        if (stale == null) {
            return;
        }
        // Clear the slot before drawing. Unlike Kitty/iTerm2 (which receive the exact display
        // footprint), Sixel receives the whole cell slot but the pre-scaled image only fills a
        // sub-region of it (FIT letterboxes; FILL/STRETCH differ), and the slot does not change
        // when only the scaling changes. Without clearing the slot, a smaller new image leaves the
        // previous, larger one visible around it (stacking). Also clear any old slots a moved
        // image left behind (stale).
        List<Rect> toClear = new ArrayList<>(stale);
        toClear.add(area);
        NativeImageCache.clearAreas(toClear, rawOutput);

        // Center the image within the slot, like Kitty/iTerm2 do via their display area. The
        // pre-scaled image usually covers only part of the slot (FIT letterboxes); drawing it at
        // the slot corner would put it top-left instead of centered. Centering is cell-granular
        // (the cursor addresses whole cells), matching the cell-level centering of the others.
        Resolution res = resolution();
        int imageCellsW = (image.width() + res.widthMultiplier() - 1) / res.widthMultiplier();
        int imageCellsH = (image.height() + res.heightMultiplier() - 1) / res.heightMultiplier();
        int offsetX = Math.max(0, (area.width() - imageCellsW) / 2);
        int offsetY = Math.max(0, (area.height() - imageCellsH) / 2);

        // Move cursor to the centered position
        String cursorMove = String.format("\033[%d;%dH", area.y() + offsetY + 1, area.x() + offsetX + 1);
        rawOutput.write(cursorMove.getBytes(StandardCharsets.US_ASCII));

        // Generate and write Sixel data.
        // The image should already be scaled by Image.scaleImage() based on the scaling mode.
        // Sixel encoding is expensive (it scans every pixel against every palette entry), so
        // the encoded bytes are cached per image to avoid recomputing them on every frame of
        // the render loop.
        byte[] sixelData = cache.payload(image, () -> {
            try {
                return encodeSixel(image);
            } catch (IOException e) {
                throw new RuntimeIOException("Failed to encode Sixel data", e);
            }
        });
        rawOutput.write(sixelData);
        rawOutput.flush();
    }

    @Override
    public boolean requiresRawOutput() {
        return true;
    }

    @Override
    public Resolution resolution() {
        // Sixel can render at pixel level, but we report typical cell pixel ratio
        return new Resolution(8, 16);
    }

    @Override
    public String name() {
        return "Sixel";
    }

    @Override
    public TerminalImageProtocol protocolType() {
        return TerminalImageProtocol.SIXEL;
    }

    /**
     * Encodes an image as Sixel data.
     */
    private byte[] encodeSixel(ImageData image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Build an adaptive palette from the image's actual colours (median cut) and map every
        // pixel to a palette index.
        Quantized quantized = quantize(image);
        int[] palette = quantized.palette;
        int[] pixelIndex = quantized.pixelIndex;

        // Start Sixel sequence
        // Format: DCS P1 ; P2 ; P3 q  (P1 aspect from device, P2=1 transparent bg, P3 device grid)
        out.write((DCS + "0;1;0q").getBytes(StandardCharsets.US_ASCII));

        // Define the palette. Format: #Pc;2;Px;Py;Pz with Px,Py,Pz as 0-100 RGB percentages.
        for (int index = 0; index < palette.length; index++) {
            int rgb = palette[index];
            int r = (((rgb >> 16) & 0xFF) * 100) / 255;
            int g = (((rgb >> 8) & 0xFF) * 100) / 255;
            int b = ((rgb & 0xFF) * 100) / 255;
            out.write(String.format("#%d;2;%d;%d;%d", index, r, g, b).getBytes(StandardCharsets.US_ASCII));
        }

        // Encode image data in 6-row strips
        int width = image.width();
        int height = image.height();

        for (int stripY = 0; stripY < height; stripY += SIXEL_HEIGHT) {
            // For each palette colour, output the sixels for that colour in this strip
            for (int colorIndex = 0; colorIndex < palette.length; colorIndex++) {
                StringBuilder stripData = new StringBuilder();
                stripData.append('#').append(colorIndex);

                boolean hasPixels = false;
                int repeatCount = 0;
                int lastSixel = -1;

                for (int x = 0; x < width; x++) {
                    // Build sixel value for this column
                    int sixelValue = 0;
                    for (int dy = 0; dy < SIXEL_HEIGHT; dy++) {
                        int y = stripY + dy;
                        if (y < height && pixelIndex[y * width + x] == colorIndex) {
                            sixelValue |= (1 << dy);
                            hasPixels = true;
                        }
                    }

                    // Run-length encoding
                    if (sixelValue == lastSixel) {
                        repeatCount++;
                    } else {
                        if (lastSixel >= 0) {
                            appendSixel(stripData, lastSixel, repeatCount);
                        }
                        lastSixel = sixelValue;
                        repeatCount = 1;
                    }
                }

                // Flush last run
                if (lastSixel >= 0) {
                    appendSixel(stripData, lastSixel, repeatCount);
                }

                // Only output if this color has pixels in this strip
                if (hasPixels) {
                    out.write(stripData.toString().getBytes(StandardCharsets.US_ASCII));
                    out.write('$'); // Carriage return (back to start of line for next color)
                }
            }

            // Move to next strip
            if (stripY + SIXEL_HEIGHT < height) {
                out.write('-'); // Graphics new line
            }
        }

        // End Sixel sequence
        out.write(ST.getBytes(StandardCharsets.US_ASCII));

        return out.toByteArray();
    }

    /**
     * Appends a sixel character with optional repeat count.
     */
    private void appendSixel(StringBuilder sb, int sixelValue, int count) {
        char sixelChar = (char) (SIXEL_OFFSET + sixelValue);
        if (count > 3) {
            // Use repeat introducer for efficiency
            sb.append('!').append(count).append(sixelChar);
        } else {
            for (int i = 0; i < count; i++) {
                sb.append(sixelChar);
            }
        }
    }

    /** An adaptive palette plus a per-pixel index into it. */
    private static final class Quantized {
        final int[] palette;     // 0xRRGGBB per palette index
        final int[] pixelIndex;  // palette index per pixel, or -1 for transparent

        Quantized(int[] palette, int[] pixelIndex) {
            this.palette = palette;
            this.pixelIndex = pixelIndex;
        }
    }

    private static final int HIST_BITS = 5;                  // 5 bits/channel -> 32768 buckets
    private static final int HIST_SIZE = 1 << (3 * HIST_BITS);

    /**
     * Builds an adaptive palette with median cut over the image's actual colours and maps each
     * pixel to a palette index.
     * <p>
     * Unlike a fixed RGB cube, this places palette entries where the image needs them (up to
     * {@code maxColors}, and Sixel allows 256), so smooth gradients stay smooth instead of
     * collapsing into a few coarse bands.
     */
    private Quantized quantize(ImageData image) {
        int width = image.width();
        int height = image.height();
        int pixels = width * height;

        // Reduce to a 5-5-5 histogram (smooth, and it bounds the work), accumulating channel sums
        // for each box's average colour. key[] holds each pixel's bucket (-1 if transparent).
        int[] key = new int[pixels];
        int[] count = new int[HIST_SIZE];
        long[] sumR = new long[HIST_SIZE];
        long[] sumG = new long[HIST_SIZE];
        long[] sumB = new long[HIST_SIZE];
        for (int y = 0, p = 0; y < height; y++) {
            for (int x = 0; x < width; x++, p++) {
                int argb = image.pixelAt(x, y);
                if (!ImageData.isVisible(argb)) {
                    key[p] = -1;
                    continue;
                }
                int r = ImageData.red(argb);
                int g = ImageData.green(argb);
                int b = ImageData.blue(argb);
                int k = ((r >> 3) << (2 * HIST_BITS)) | ((g >> 3) << HIST_BITS) | (b >> 3);
                key[p] = k;
                count[k]++;
                sumR[k] += r;
                sumG[k] += g;
                sumB[k] += b;
            }
        }

        // Collect the populated buckets.
        int distinct = 0;
        for (int k = 0; k < HIST_SIZE; k++) {
            if (count[k] > 0) {
                distinct++;
            }
        }
        int[] keys = new int[Math.max(1, distinct)];
        for (int k = 0, i = 0; k < HIST_SIZE; k++) {
            if (count[k] > 0) {
                keys[i++] = k;
            }
        }

        // Median cut: repeatedly split the box with the largest colour spread until there are
        // maxColors boxes (or every box holds a single colour). Each box is a [from, to) slice.
        List<int[]> boxes = new ArrayList<>();
        boxes.add(new int[] {0, distinct});
        while (boxes.size() < maxColors) {
            int target = -1;
            int targetSpread = 0;
            for (int i = 0; i < boxes.size(); i++) {
                int[] box = boxes.get(i);
                if (box[1] - box[0] < 2) {
                    continue;
                }
                int spread = channelRange(keys, box[0], box[1], -1);
                if (spread > targetSpread) {
                    targetSpread = spread;
                    target = i;
                }
            }
            if (target < 0) {
                break;
            }
            int[] box = boxes.get(target);
            sortByChannel(keys, box[0], box[1], longestAxis(keys, box[0], box[1]));
            int mid = medianSplit(keys, box[0], box[1], count);
            boxes.set(target, new int[] {box[0], mid});
            boxes.add(new int[] {mid, box[1]});
        }

        // Average each box to a palette colour; remember which palette index each bucket maps to.
        int[] palette = new int[boxes.size()];
        int[] bucketToIndex = new int[HIST_SIZE];
        Arrays.fill(bucketToIndex, -1);
        for (int i = 0; i < boxes.size(); i++) {
            int[] box = boxes.get(i);
            long tr = 0;
            long tg = 0;
            long tb = 0;
            long tc = 0;
            for (int j = box[0]; j < box[1]; j++) {
                int k = keys[j];
                tr += sumR[k];
                tg += sumG[k];
                tb += sumB[k];
                tc += count[k];
                bucketToIndex[k] = i;
            }
            int r = tc > 0 ? (int) (tr / tc) : 0;
            int g = tc > 0 ? (int) (tg / tc) : 0;
            int b = tc > 0 ? (int) (tb / tc) : 0;
            palette[i] = (r << 16) | (g << 8) | b;
        }

        int[] pixelIndex = new int[pixels];
        for (int p = 0; p < pixels; p++) {
            pixelIndex[p] = key[p] < 0 ? -1 : bucketToIndex[key[p]];
        }
        return new Quantized(palette, pixelIndex);
    }

    /** Range of the given channel (0=R,1=G,2=B) over the slice, or the max of all three if axis &lt; 0. */
    private static int channelRange(int[] keys, int from, int to, int axis) {
        int rMin = 31, rMax = 0, gMin = 31, gMax = 0, bMin = 31, bMax = 0;
        for (int j = from; j < to; j++) {
            int k = keys[j];
            int r = (k >> (2 * HIST_BITS)) & 31;
            int g = (k >> HIST_BITS) & 31;
            int b = k & 31;
            if (r < rMin) { rMin = r; }
            if (r > rMax) { rMax = r; }
            if (g < gMin) { gMin = g; }
            if (g > gMax) { gMax = g; }
            if (b < bMin) { bMin = b; }
            if (b > bMax) { bMax = b; }
        }
        int rr = rMax - rMin;
        int gr = gMax - gMin;
        int br = bMax - bMin;
        if (axis == 0) { return rr; }
        if (axis == 1) { return gr; }
        if (axis == 2) { return br; }
        return Math.max(rr, Math.max(gr, br));
    }

    /** Channel (0=R,1=G,2=B) with the largest range in the slice. */
    private static int longestAxis(int[] keys, int from, int to) {
        int rr = channelRange(keys, from, to, 0);
        int gr = channelRange(keys, from, to, 1);
        int br = channelRange(keys, from, to, 2);
        if (rr >= gr && rr >= br) {
            return 0;
        }
        return gr >= br ? 1 : 2;
    }

    /** Sorts the slice by the given channel (0=R,1=G,2=B). */
    private static void sortByChannel(int[] keys, int from, int to, int axis) {
        int shift = axis == 0 ? (2 * HIST_BITS) : axis == 1 ? HIST_BITS : 0;
        Integer[] slice = new Integer[to - from];
        for (int i = 0; i < slice.length; i++) {
            slice[i] = keys[from + i];
        }
        Arrays.sort(slice, (a, b) -> Integer.compare((a >> shift) & 31, (b >> shift) & 31));
        for (int i = 0; i < slice.length; i++) {
            keys[from + i] = slice[i];
        }
    }

    /** Splits a sorted slice at the population-weighted median. */
    private static int medianSplit(int[] keys, int from, int to, int[] count) {
        long total = 0;
        for (int j = from; j < to; j++) {
            total += count[keys[j]];
        }
        long half = total / 2;
        long acc = 0;
        for (int j = from; j < to; j++) {
            acc += count[keys[j]];
            if (acc >= half && j + 1 < to) {
                return j + 1;
            }
        }
        return from + (to - from) / 2;
    }
}
