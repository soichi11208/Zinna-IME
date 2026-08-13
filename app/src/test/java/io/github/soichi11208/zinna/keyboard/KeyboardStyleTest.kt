package io.github.soichi11208.zinna.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardStyleTest {

    @Test
    fun flickKeepsEverythingOnTheFlickPad() {
        with(KeyboardStyle.FLICK) {
            assertEquals("flick_kana", defaultLayoutId)
            assertEquals("flick_kana", resolve("qwerty_kana"))
            assertEquals("flick_ascii", resolve("qwerty_ascii"))
        }
    }

    @Test
    fun qwertyKeepsEverythingOnQwerty() {
        with(KeyboardStyle.QWERTY) {
            assertEquals("qwerty_kana", defaultLayoutId)
            assertEquals("qwerty_kana", resolve("flick_kana"))
            assertEquals("qwerty_ascii", resolve("flick_ascii"))
        }
    }

    /** The point of the mode: kana on the flick pad, latin on qwerty, both directions. */
    @Test
    fun mixedSplitsKanaFromLatin() {
        with(KeyboardStyle.MIXED) {
            assertEquals("flick_kana", defaultLayoutId)
            // 英数 on the flick pad must land on the qwerty alphabet…
            assertEquals("qwerty_ascii", resolve("flick_ascii"))
            // …and かな on that qwerty plane must come back to the flick pad.
            assertEquals("flick_kana", resolve("qwerty_kana"))
        }
    }

    /**
     * Symbol pages are reached from inside a family and must not be redirected: ?123 on the qwerty
     * alphabet has to open the qwerty symbol page, not the flick number pad.
     */
    @Test
    fun symbolPagesAreNeverRedirected() {
        for (style in KeyboardStyle.entries) {
            for (id in listOf("flick_symbol", "qwerty_symbol", "qwerty_symbol2")) {
                assertEquals("$style redirected $id", id, style.resolve(id))
            }
        }
    }

    /** A layout the user dropped in themselves is none of our business. */
    @Test
    fun unknownLayoutsPassThrough() {
        for (style in KeyboardStyle.entries) {
            assertEquals("my_layout", style.resolve("my_layout"))
        }
    }

    @Test
    fun unreadableSettingFallsBackToFlick() {
        assertEquals(KeyboardStyle.FLICK, KeyboardStyle.of(null))
        assertEquals(KeyboardStyle.FLICK, KeyboardStyle.of("NONSENSE"))
        assertEquals(KeyboardStyle.MIXED, KeyboardStyle.of("MIXED"))
    }
}
