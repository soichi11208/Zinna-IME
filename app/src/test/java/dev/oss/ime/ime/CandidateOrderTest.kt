package dev.oss.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wanted order is: exact conversions from mozc, then exact ones from the user's dictionaries,
 * then mozc's predictions, then the dictionaries' predictions.
 */
class CandidateOrderTest {

    private fun candidates(vararg text: String) =
        text.mapIndexed { i, t -> MozcSession.Candidate(i, t) }

    private fun order(
        raw: List<MozcSession.Candidate>,
        tiers: Map<Int, CandidateTier>,
        focusedId: Int? = null,
    ) = byTier(raw, focusedId) { tiers[it] ?: CandidateTier.MOZC_EXACT }

    @Test
    fun tierIsDecidedByExactnessThenSource() {
        assertEquals(CandidateTier.MOZC_EXACT, CandidateTier.of(exact = true, fromDictionary = false))
        assertEquals(CandidateTier.DICTIONARY_EXACT, CandidateTier.of(exact = true, fromDictionary = true))
        assertEquals(CandidateTier.MOZC_PREDICTED, CandidateTier.of(exact = false, fromDictionary = false))
        assertEquals(
            CandidateTier.DICTIONARY_PREDICTED,
            CandidateTier.of(exact = false, fromDictionary = true),
        )
    }

    /** All four bands present and arriving in the worst possible order. */
    @Test
    fun bandsComeOutInOrder() {
        val raw = candidates("辞書予測", "mozc予測", "辞書一致", "mozc一致")
        val tiers = mapOf(
            0 to CandidateTier.DICTIONARY_PREDICTED,
            1 to CandidateTier.MOZC_PREDICTED,
            2 to CandidateTier.DICTIONARY_EXACT,
            3 to CandidateTier.MOZC_EXACT,
        )
        val (ordered, _) = order(raw, tiers)
        assertEquals(listOf("mozc一致", "辞書一致", "mozc予測", "辞書予測"), ordered.map { it.text })
    }

    /** The complaint that prompted this: a prediction sitting at the top. */
    @Test
    fun predictionsNeverOutrankAnExactConversion() {
        val raw = candidates("電話番号", "電話", "でんわ")
        val tiers = mapOf(0 to CandidateTier.MOZC_PREDICTED)
        val (ordered, _) = order(raw, tiers)
        assertEquals(listOf("電話", "でんわ", "電話番号"), ordered.map { it.text })
    }

    /** A dictionary word that converts exactly still beats anything merely predicted. */
    @Test
    fun exactDictionaryWordBeatsMozcPrediction() {
        val raw = candidates("予測", "ユーザー語")
        val tiers = mapOf(
            0 to CandidateTier.MOZC_PREDICTED,
            1 to CandidateTier.DICTIONARY_EXACT,
        )
        val (ordered, _) = order(raw, tiers)
        assertEquals(listOf("ユーザー語", "予測"), ordered.map { it.text })
    }

    @Test
    fun orderInsideEachBandIsPreserved() {
        val raw = candidates("アリ", "あり", "有り", "蟻")
        val (ordered, _) = order(raw, emptyMap())
        assertEquals(raw.map { it.text }, ordered.map { it.text })
    }

    @Test
    fun focusFollowsItsCandidateToTheNewIndex() {
        val raw = candidates("電話番号", "電話", "でんわ")
        val (ordered, focused) = order(raw, mapOf(0 to CandidateTier.MOZC_PREDICTED), focusedId = 0)
        assertEquals(2, focused)
        assertEquals("電話番号", ordered[focused].text)
    }

    @Test
    fun noFocusStaysNoFocus() {
        val (_, focused) = order(candidates("あ", "い"), emptyMap())
        assertEquals(-1, focused)
    }

    /** The reported bug: にほん was offering ニホン before 日本. */
    @Test
    fun plainTransliterationsSinkToTheBottom() {
        assertEquals(CandidateTier.TRANSLITERATION.ordinal, CandidateTier.entries.size - 1)
        assertTrue(isTransliterationOf("にほん", "にほん"))
        assertTrue(isTransliterationOf("ニホン", "にほん"))
        assertFalse(isTransliterationOf("日本", "にほん"))
        // Same length but not the same word.
        assertFalse(isTransliterationOf("にっぽん", "にほんご"))
    }

    @Test
    fun conversionsOutrankTheReadingWrittenOut() {
        val raw = candidates("ニホン", "にほん", "日本")
        val tiers = mapOf(
            0 to CandidateTier.TRANSLITERATION,
            1 to CandidateTier.TRANSLITERATION,
            2 to CandidateTier.MOZC_PREDICTED,
        )
        val (ordered, _) = order(raw, tiers)
        assertEquals(listOf("日本", "ニホン", "にほん"), ordered.map { it.text })
    }

    @Test
    fun emptyAndSingleListsAreHandled() {
        assertEquals(emptyList<MozcSession.Candidate>(), order(emptyList(), emptyMap()).first)
        assertEquals(1, order(candidates("あ"), emptyMap()).first.size)
    }
}
