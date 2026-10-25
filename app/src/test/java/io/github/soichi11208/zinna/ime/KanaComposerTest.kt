package io.github.soichi11208.zinna.ime

import org.junit.Assert.*
import org.junit.Test

class KanaComposerTest {
    private fun composer() = KanaComposer(KanaComposer.rules(listOf(
        "a\tあ", "ka\tか", "ki\tき", "kya\tきゃ", "na\tな", "n\tん", "nn\tん", "kk\tっ\tk",
    )))
    @Test fun flickKanaRepeatedKeysAndVoicingNeedNoConversionEngine() {
        val c = composer()
        c.insert("は"); c.modify(); assertEquals("ば", c.text)
        c.modify(); assertEquals("ぱ", c.text)
        c.insert("か"); c.insert("か"); assertEquals("ぱかか", c.text)
        c.modify(); assertEquals("ぱかが", c.text)
    }
    @Test fun romanPendingAndSmallTsuAreComposedBeforeModelInput() {
        val c = composer()
        c.roman("k"); assertEquals("k", c.text)
        c.roman("k"); assertEquals("っk", c.text)
        c.roman("a"); assertEquals("っか", c.text)
        c.roman("n"); assertEquals("っかん", c.state().preeditReading)
        assertEquals("っかん", c.finish())
    }
    @Test fun cursorDeletionAndModifiersPreserveSurrogatePairs() {
        val c = composer()
        c.insert("あ😀か")
        c.move(-1); c.backspace(); assertEquals("あか", c.text)
        c.insert("い"); assertEquals("あいか", c.text)
        assertEquals(2, c.state().preeditCursor)
        c.clear(); assertTrue(c.state().preedit.isEmpty())
    }
}
