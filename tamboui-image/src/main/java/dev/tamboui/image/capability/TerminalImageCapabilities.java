/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.image.capability;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

import dev.tamboui.terminal.Backend;
import dev.tamboui.image.protocol.BrailleProtocol;
import dev.tamboui.image.protocol.HalfBlockProtocol;
import dev.tamboui.image.protocol.ITermProtocol;
import dev.tamboui.image.protocol.ImageProtocol;
import dev.tamboui.image.protocol.KittyProtocol;
import dev.tamboui.image.protocol.SixelProtocol;

/**
 * Detects and caches terminal image capabilities.
 * <p>
 * Detection is performed using environment variables to identify the terminal emulator.
 * This approach is fast (no I/O) and works in most cases.
 *
 * <pre>{@code
 * TerminalImageCapabilities caps = TerminalImageCapabilities.detect();
 * ImageProtocol protocol = caps.bestProtocol();
 * }</pre>
 *
 * <h2>Terminal Detection</h2>
 * <table>
 *   <caption>Environment variable to protocol mapping</caption>
 *   <tr><th>Environment</th><th>Protocol</th></tr>
 *   <tr><td>KITTY_WINDOW_ID</td><td>Kitty</td></tr>
 *   <tr><td>TERM=xterm-kitty</td><td>Kitty</td></tr>
 *   <tr><td>TERM=xterm-ghostty</td><td>Kitty</td></tr>
 *   <tr><td>WEZTERM_PANE</td><td>Kitty</td></tr>
 *   <tr><td>ITERM_SESSION_ID</td><td>iTerm2</td></tr>
 *   <tr><td>TERM=rio</td><td>Kitty</td></tr>
 *   <tr><td>KONSOLE_VERSION</td><td>Sixel</td></tr>
 *   <tr><td>TERM contains mlterm</td><td>Sixel</td></tr>
 * </table>
 */
public final class TerminalImageCapabilities {

    private static volatile TerminalImageCapabilities instance;

    private final Set<TerminalImageProtocol> supportedProtocols;
    private final TerminalImageProtocol bestSupport;

    /** Number of Sixel colour registers the terminal reported, or 0 if unknown (assume 256). */
    private int sixelColorRegisters;

    private TerminalImageCapabilities(Set<TerminalImageProtocol> supportedProtocols) {
        this.supportedProtocols = EnumSet.copyOf(supportedProtocols);
        this.bestSupport = determineBestSupport(supportedProtocols);
    }

    /**
     * Detects terminal capabilities from the current environment.
     * <p>
     * The result is cached for subsequent calls.
     *
     * @return the detected capabilities
     */
    public static TerminalImageCapabilities detect() {
        if (instance == null) {
            synchronized (TerminalImageCapabilities.class) {
                if (instance == null) {
                    instance = detectFromEnvironment();
                }
            }
        }
        return instance;
    }

    /**
     * Detects capabilities from the environment and, when Sixel is supported, additionally queries
     * the terminal for its number of Sixel colour registers (XTSMGRAPHICS).
     * <p>
     * Unlike {@link #detect()} this performs terminal I/O (a query and a short blocking read), so it
     * must be called on a backend already in raw mode and before the input loop starts consuming
     * input. The returned instance is fresh (not the cached singleton).
     *
     * @param backend the backend to query through
     * @return capabilities including the queried Sixel colour-register count
     */
    public static TerminalImageCapabilities detect(Backend backend) {
        TerminalImageCapabilities caps = detectFromEnvironment();
        if (caps.supports(TerminalImageProtocol.SIXEL)) {
            caps.sixelColorRegisters = queryColorRegisters(backend);
        }
        return caps;
    }

    /**
     * Returns the number of Sixel colour registers the terminal reported, or {@code 0} if unknown
     * (in which case the Sixel default of 256 is used).
     *
     * @return the colour-register count, or 0 if unknown
     */
    public int sixelColorRegisters() {
        return sixelColorRegisters;
    }

    /**
     * Queries the terminal for its number of Sixel colour registers using XTSMGRAPHICS
     * ({@code CSI ? 1 ; 1 ; 0 S}); the reply is {@code CSI ? 1 ; 0 ; Pn S}.
     *
     * @param backend the backend to query through
     * @return the reported register count, or 0 if the terminal does not answer
     */
    public static int queryColorRegisters(Backend backend) {
        try {
            backend.writeRaw("\033[?1;1;0S");
            backend.flush();
            StringBuilder response = new StringBuilder();
            int c = backend.read(250); // give the terminal a moment for the first byte
            int budget = 64;
            while (c >= 0 && budget-- > 0) {
                response.append((char) c);
                if (c == 'S') {
                    break;
                }
                c = backend.read(50);
            }
            return parseColorRegisters(response.toString());
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Parses an XTSMGRAPHICS reply ({@code CSI ? 1 ; 0 ; Pn S}) and returns {@code Pn}, the number
     * of colour registers, or 0 if the response is missing or malformed.
     *
     * @param response the raw terminal response
     * @return the register count, or 0
     */
    static int parseColorRegisters(String response) {
        if (response == null || !response.contains("\033[?1;")) {
            return 0;
        }
        int end = response.indexOf('S', response.indexOf("\033[?1;"));
        if (end < 0) {
            return 0;
        }
        int lastSemicolon = response.lastIndexOf(';', end);
        if (lastSemicolon < 0) {
            return 0;
        }
        try {
            int value = Integer.parseInt(response.substring(lastSemicolon + 1, end).trim());
            return value > 0 ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private SixelProtocol newSixelProtocol() {
        int colors = sixelColorRegisters > 0 ? Math.min(256, sixelColorRegisters) : 256;
        return new SixelProtocol(colors);
    }

    /**
     * Forces re-detection of terminal capabilities.
     * <p>
     * Useful when the terminal environment may have changed.
     *
     * @return the newly detected capabilities
     */
    public static TerminalImageCapabilities refresh() {
        synchronized (TerminalImageCapabilities.class) {
            instance = detectFromEnvironment();
            return instance;
        }
    }

    /**
     * Creates capabilities with explicitly specified support.
     * <p>
     * Useful for testing or when environment detection is unreliable.
     *
     * @param supportedProtocols the set of supported protocols
     * @return capabilities with the specified support
     */
    public static TerminalImageCapabilities withSupport(Set<TerminalImageProtocol> supportedProtocols) {
        return new TerminalImageCapabilities(supportedProtocols);
    }

    /**
     * Returns the best available image support level.
     *
     * @return the best image support
     */
    public TerminalImageProtocol bestSupport() {
        return bestSupport;
    }

    /**
     * Returns true if the specified protocol is supported.
     *
     * @param support the support level to check
     * @return true if supported
     */
    public boolean supports(TerminalImageProtocol support) {
        return supportedProtocols.contains(support);
    }

    /**
     * Returns all supported protocols.
     * <p>
     * The returned set is immutable.
     *
     * @return the set of supported protocols
     */
    public Set<TerminalImageProtocol> supportedProtocols() {
        return supportedProtocols;
    }

    /**
     * Returns true if any native image protocol is supported.
     *
     * @return true if Kitty, iTerm2, or Sixel is available
     */
    public boolean supportsNativeImages() {
        return supports(TerminalImageProtocol.KITTY)
            || supports(TerminalImageProtocol.ITERM2)
            || supports(TerminalImageProtocol.SIXEL);
    }

    /**
     * Returns the best available image protocol implementation.
     *
     * @return the best available protocol
     */
    public ImageProtocol bestProtocol() {
        switch (bestSupport) {
            case KITTY:
                return new KittyProtocol();
            case ITERM2:
                return new ITermProtocol();
            case SIXEL:
                return newSixelProtocol();
            case HALF_BLOCK:
                return new HalfBlockProtocol();
            case BRAILLE:
                return new BrailleProtocol();
            default:
                return new HalfBlockProtocol();
        }
    }

    /**
     * Returns a protocol implementation for the specified support level.
     *
     * @param support the support level
     * @return the protocol implementation, or null if not available
     */
    public ImageProtocol protocolFor(TerminalImageProtocol support) {
        switch (support) {
            case KITTY:
                return new KittyProtocol();
            case ITERM2:
                return new ITermProtocol();
            case SIXEL:
                return newSixelProtocol();
            case HALF_BLOCK:
                return new HalfBlockProtocol();
            case BRAILLE:
                return new BrailleProtocol();
            default:
                return null;
        }
    }

    private static TerminalImageCapabilities detectFromEnvironment() {
        Set<TerminalImageProtocol> supported = EnumSet.noneOf(TerminalImageProtocol.class);

        // Always support character-based fallbacks
        supported.add(TerminalImageProtocol.HALF_BLOCK);
        supported.add(TerminalImageProtocol.BRAILLE);

        // Check for Kitty terminal
        if (getEnv("KITTY_WINDOW_ID") != null) {
            supported.add(TerminalImageProtocol.KITTY);
        }

        String term = getEnv("TERM");
        if (term != null) {
            if (term.equals("xterm-kitty") || term.equals("xterm-ghostty")) {
                supported.add(TerminalImageProtocol.KITTY);
            }
            if (term.equals("rio")) {
                // Rio renders the Kitty graphics protocol reliably; its iTerm2 inline-image and
                // Sixel support do not display in current versions, so don't advertise them.
                supported.add(TerminalImageProtocol.KITTY);
            }
            if (term.contains("mlterm")) {
                supported.add(TerminalImageProtocol.SIXEL);
            }
        }

        // Check for WezTerm (supports Kitty protocol)
        if (getEnv("WEZTERM_PANE") != null) {
            supported.add(TerminalImageProtocol.KITTY);
        }

        // Check for Ghostty (supports Kitty protocol). TERM is only "xterm-ghostty" when the
        // bundled terminfo is installed; the env var is always present, so it is the reliable hint.
        if (getEnv("GHOSTTY_RESOURCES_DIR") != null) {
            supported.add(TerminalImageProtocol.KITTY);
        }

        // Check for iTerm2
        if (getEnv("ITERM_SESSION_ID") != null) {
            supported.add(TerminalImageProtocol.ITERM2);
        }

        // Check for Konsole (supports Sixel in recent versions)
        String konsoleVersion = getEnv("KONSOLE_VERSION");
        if (konsoleVersion != null) {
            try {
                int version = Integer.parseInt(konsoleVersion.split("\\.")[0]);
                if (version >= 22) {
                    supported.add(TerminalImageProtocol.SIXEL);
                }
            } catch (NumberFormatException ignored) {
                // Unknown version format, assume Sixel support
                supported.add(TerminalImageProtocol.SIXEL);
            }
        }

        // Check TERM_PROGRAM for additional hints
        String termProgram = getEnv("TERM_PROGRAM");
        if (termProgram != null) {
            if (termProgram.equalsIgnoreCase("iTerm.app")) {
                supported.add(TerminalImageProtocol.ITERM2);
            }
            if (termProgram.equalsIgnoreCase("WezTerm")) {
                supported.add(TerminalImageProtocol.KITTY);
            }
            if (termProgram.equalsIgnoreCase("ghostty")) {
                supported.add(TerminalImageProtocol.KITTY);
            }
        }

        return new TerminalImageCapabilities(supported);
    }

    private static TerminalImageProtocol determineBestSupport(Set<TerminalImageProtocol> supported) {
        // EnumSet iterates in enum declaration order (which is our priority order)
        return supported.stream()
            .filter(p -> p != TerminalImageProtocol.NONE)
            .findFirst()
            .orElse(TerminalImageProtocol.NONE);
    }

    private static String getEnv(String name) {
        try {
            return System.getenv(name);
        } catch (SecurityException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return String.format("TerminalImageCapabilities[best=%s, supported=%s]", bestSupport, supportedProtocols);
    }
}
