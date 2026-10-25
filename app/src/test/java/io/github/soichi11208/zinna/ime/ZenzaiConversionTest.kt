package io.github.soichi11208.zinna.ime

import org.junit.Assert.*
import org.junit.Test

class ZenzaiConversionTest {
    @Test fun mozcCanBeDisabledOnlyWhenZenzaiIsEnabled() {
        assertTrue(ZenzaiConversion.usesMozc(false, false))
        assertTrue(ZenzaiConversion.usesMozc(false, true))
        assertFalse(ZenzaiConversion.usesMozc(true, false))
        assertTrue(ZenzaiConversion.usesMozc(true, true))
    }

    @Test fun neuralCandidateAlwaysPrecedesDictionaryAndMozcIncludingDuplicates() {
        val dictionary = MozcSession.Candidate(-100, "辞書の候補")
        val mozc = MozcSession.Candidate(5, "Mozcの候補")
        for (text in listOf("新しい候補", dictionary.text, mozc.text)) {
            val neural = ZenzaiConversion.candidate(text)!!
            val result = ZenzaiConversion.candidates(listOf(dictionary, mozc), neural)
            assertEquals(neural, result.first())
            assertEquals(1, result.count { it.text == text })
        }
    }

    @Test fun onlyEnabledJapaneseCompositionCanReachModelAndPasswordsNeverDo() {
        for (flags in 0..15) {
            val enabled = flags and 1 != 0
            val composing = flags and 2 != 0
            val japanese = flags and 4 != 0
            val password = flags and 8 != 0
            assertEquals("flags=$flags", flags == 7,
                ZenzaiConversion.canConvert(enabled, composing, japanese, password))
        }
    }

    @Test fun usesV2TagsAndKatakanaWithoutChatTemplate() {
        assertEquals("\uEE00キョウハイイテンキデス\uEE01", ZenzaiConversion.prompt("きょうはいいてんきです", ""))
        assertEquals("\uEE00ガッコウ\uEE02今日は\uEE01", ZenzaiConversion.prompt("ｶﾞｯｺｳ", "今日は"))
    }

    @Test fun contextIsLimitedAndCannotInjectModelTags() {
        assertEquals("\uEE00ア\uEE02" + "前".repeat(40) + "\uEE01",
            ZenzaiConversion.prompt("あ", "古".repeat(100) + "前".repeat(40)))
        assertEquals("\uEE00ア\uEE02前　文\uEE01", ZenzaiConversion.prompt("あ", "\uEE00前 \n文\uEE01"))
        assertFalse(ZenzaiConversion.prompt("あ", "😀".repeat(45))!!.contains('\uFFFD'))
    }

    @Test fun ineligibleReadingsDoNotInvokeTheModel() {
        for (text in listOf("", "123", "abc", "あk", "変換", "あ".repeat(129))) {
            assertNull(text, ZenzaiConversion.prompt(text, ""))
        }
    }

    @Test fun rejectsInvalidOrIncompleteOutput() {
        for (text in listOf("", " ", "\uEE01答え", "答え\n", "\uFFFD", "あ".repeat(193))) {
            assertNull(text, ZenzaiConversion.candidate(text))
        }
        assertEquals("今日はいい天気です", ZenzaiConversion.candidate("今日はいい天気です")!!.text)
    }

    @Test fun neuralCandidateLeadsWithoutDuplicatingMozcResults() {
        val base = listOf(MozcSession.Candidate(3, "今日は"), MozcSession.Candidate(4, "きょうは"))
        val neural = ZenzaiConversion.candidate("今日は")!!
        assertEquals(listOf(neural, base[1]), ZenzaiConversion.candidates(base, neural))
    }
}
