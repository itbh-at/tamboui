/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.image.capability;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;

import dev.tamboui.image.protocol.BrailleProtocol;
import dev.tamboui.image.protocol.HalfBlockProtocol;
import dev.tamboui.image.protocol.ITermProtocol;
import dev.tamboui.image.protocol.ImageProtocol;
import dev.tamboui.image.protocol.KittyProtocol;
import dev.tamboui.image.protocol.SixelProtocol;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalImageCapabilitiesTest {

    @Test
    void detect_returns_capabilities() {
        TerminalImageCapabilities caps = TerminalImageCapabilities.detect();
        assertThat(caps).isNotNull();
        assertThat(caps.bestSupport()).isNotNull();
    }

    @Test
    void parseColorRegisters_reads_the_xtsmgraphics_reply() {
        // CSI ? 1 ; 0 ; Pn S  -> Pn
        assertThat(TerminalImageCapabilities.parseColorRegisters("\033[?1;0;256S")).isEqualTo(256);
        assertThat(TerminalImageCapabilities.parseColorRegisters("\033[?1;0;1024S")).isEqualTo(1024);
        assertThat(TerminalImageCapabilities.parseColorRegisters("\033[?1;0;16S")).isEqualTo(16);
    }

    @Test
    void parseColorRegisters_returns_zero_on_missing_or_malformed_reply() {
        assertThat(TerminalImageCapabilities.parseColorRegisters(null)).isZero();
        assertThat(TerminalImageCapabilities.parseColorRegisters("")).isZero();
        assertThat(TerminalImageCapabilities.parseColorRegisters("garbage")).isZero();
        assertThat(TerminalImageCapabilities.parseColorRegisters("\033[?1;0;S")).isZero();
        // An XTSMGRAPHICS failure reply (Ps != 0) carries no count -> treated as unknown.
        assertThat(TerminalImageCapabilities.parseColorRegisters("\033[?1;3;0S")).isZero();
    }

    @Test
    void parseCellPixelSize_reads_the_cell_size_reply() {
        // CSI 6 ; height ; width t  -> [width, height]
        assertThat(TerminalImageCapabilities.parseCellPixelSize("\033[6;32;15t")).containsExactly(15, 32);
        assertThat(TerminalImageCapabilities.parseCellPixelSize("\033[6;40;20t")).containsExactly(20, 40);
    }

    @Test
    void parseCellPixelSize_returns_null_on_missing_or_malformed_reply() {
        assertThat(TerminalImageCapabilities.parseCellPixelSize(null)).isNull();
        assertThat(TerminalImageCapabilities.parseCellPixelSize("")).isNull();
        assertThat(TerminalImageCapabilities.parseCellPixelSize("garbage")).isNull();
        assertThat(TerminalImageCapabilities.parseCellPixelSize("\033[6;32t")).isNull();
    }

    @Test
    void withSupport_creates_capabilities_with_specified_support() {
        TerminalImageCapabilities caps = TerminalImageCapabilities.withSupport(
            EnumSet.of(TerminalImageProtocol.KITTY, TerminalImageProtocol.HALF_BLOCK)
        );

        assertThat(caps.supports(TerminalImageProtocol.KITTY)).isTrue();
        assertThat(caps.supports(TerminalImageProtocol.HALF_BLOCK)).isTrue();
        assertThat(caps.supports(TerminalImageProtocol.SIXEL)).isFalse();
    }

    @Test
    void bestSupport_returns_highest_priority() {
        TerminalImageCapabilities caps = TerminalImageCapabilities.withSupport(
            EnumSet.of(TerminalImageProtocol.SIXEL, TerminalImageProtocol.HALF_BLOCK, TerminalImageProtocol.BRAILLE)
        );

        // SIXEL should be preferred over HALF_BLOCK and BRAILLE
        assertThat(caps.bestSupport()).isEqualTo(TerminalImageProtocol.SIXEL);
    }

    @Test
    void bestSupport_prefers_kitty_over_iterm2() {
        TerminalImageCapabilities caps = TerminalImageCapabilities.withSupport(
            EnumSet.of(TerminalImageProtocol.KITTY, TerminalImageProtocol.ITERM2)
        );

        assertThat(caps.bestSupport()).isEqualTo(TerminalImageProtocol.KITTY);
    }

    @Test
    void supportsNativeImages_true_for_kitty() {
        TerminalImageCapabilities caps = TerminalImageCapabilities.withSupport(
            EnumSet.of(TerminalImageProtocol.KITTY, TerminalImageProtocol.HALF_BLOCK)
        );

        assertThat(caps.supportsNativeImages()).isTrue();
    }

    @Test
    void supportsNativeImages_true_for_iterm2() {
        TerminalImageCapabilities caps = TerminalImageCapabilities.withSupport(
            EnumSet.of(TerminalImageProtocol.ITERM2)
        );

        assertThat(caps.supportsNativeImages()).isTrue();
    }

    @Test
    void supportsNativeImages_true_for_sixel() {
        TerminalImageCapabilities caps = TerminalImageCapabilities.withSupport(
            EnumSet.of(TerminalImageProtocol.SIXEL)
        );

        assertThat(caps.supportsNativeImages()).isTrue();
    }

    @Test
    void supportsNativeImages_false_for_character_only() {
        TerminalImageCapabilities caps = TerminalImageCapabilities.withSupport(
            EnumSet.of(TerminalImageProtocol.HALF_BLOCK, TerminalImageProtocol.BRAILLE)
        );

        assertThat(caps.supportsNativeImages()).isFalse();
    }

    @Test
    void bestProtocol_returns_half_block_for_half_block_support() {
        TerminalImageCapabilities caps = TerminalImageCapabilities.withSupport(
            EnumSet.of(TerminalImageProtocol.HALF_BLOCK)
        );

        ImageProtocol protocol = caps.bestProtocol();
        assertThat(protocol).isInstanceOf(HalfBlockProtocol.class);
    }

    @Test
    void bestProtocol_returns_braille_for_braille_support() {
        TerminalImageCapabilities caps = TerminalImageCapabilities.withSupport(
            EnumSet.of(TerminalImageProtocol.BRAILLE)
        );

        ImageProtocol protocol = caps.bestProtocol();
        assertThat(protocol).isInstanceOf(BrailleProtocol.class);
    }

    @Test
    void protocolFor_returns_correct_protocol() {
        TerminalImageCapabilities caps = TerminalImageCapabilities.detect();

        assertThat(caps.protocolFor(TerminalImageProtocol.HALF_BLOCK)).isInstanceOf(HalfBlockProtocol.class);
        assertThat(caps.protocolFor(TerminalImageProtocol.BRAILLE)).isInstanceOf(BrailleProtocol.class);
        assertThat(caps.protocolFor(TerminalImageProtocol.NONE)).isNull();
    }

    @Test
    void protocolFor_returns_native_protocols() {
        TerminalImageCapabilities caps = TerminalImageCapabilities.detect();

        assertThat(caps.protocolFor(TerminalImageProtocol.KITTY)).isInstanceOf(KittyProtocol.class);
        assertThat(caps.protocolFor(TerminalImageProtocol.ITERM2)).isInstanceOf(ITermProtocol.class);
        assertThat(caps.protocolFor(TerminalImageProtocol.SIXEL)).isInstanceOf(SixelProtocol.class);
    }

    @Test
    void toString_includes_support_info() {
        TerminalImageCapabilities caps = TerminalImageCapabilities.withSupport(
            EnumSet.of(TerminalImageProtocol.HALF_BLOCK, TerminalImageProtocol.BRAILLE)
        );

        String str = caps.toString();
        assertThat(str).contains("TerminalImageCapabilities");
        assertThat(str).contains("HALF_BLOCK");
    }

    @Test
    void refresh_returns_new_capabilities() {
        TerminalImageCapabilities caps1 = TerminalImageCapabilities.detect();
        TerminalImageCapabilities caps2 = TerminalImageCapabilities.refresh();

        // Both should be valid
        assertThat(caps1).isNotNull();
        assertThat(caps2).isNotNull();
    }
}
