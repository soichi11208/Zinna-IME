package dev.oss.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cases here are real candidate lists from mozc, captured against the bundled dictionary, so
 * the expectations are what the user actually sees rather than invented examples.
 */
class CandidateOrderTest {

    private fun candidates(vararg text: String) =
        text.mapIndexed { i, t -> MozcSession.Candidate(i, t) }

    /** でんわ offered 電話番号 above 電話 — the complaint that prompted this. */
    @Test
    fun predictionsSinkBelowExactConversions() {
        val raw = candidates("電話番号", "電話", "でんわ", "℡")
        val extends = setOf(0)
        val (ordered, _) = exactReadingFirst(raw, null) { it in extends }
        assertEquals(listOf("電話", "でんわ", "℡", "電話番号"), ordered.map { it.text })
    }

    /** Within each group mozc's own ranking has to survive untouched. */
    @Test
    fun orderInsideEachGroupIsPreserved() {
        val raw = candidates("ありだと", "アリ", "ありそうで", "あり", "有り", "蟻")
        val extends = setOf(0, 2)
        val (ordered, _) = exactReadingFirst(raw, null) { it in extends }
        assertEquals(
            listOf("アリ", "あり", "有り", "蟻", "ありだと", "ありそうで"),
            ordered.map { it.text },
        )
    }

    @Test
    fun focusFollowsItsCandidateToTheNewIndex() {
        val raw = candidates("電話番号", "電話", "でんわ")
        val extends = setOf(0)
        // Focus is on 電話番号, which moves from slot 0 to the end.
        val (ordered, focused) = exactReadingFirst(raw, focusedId = 0) { it in extends }
        assertEquals(2, focused)
        assertEquals("電話番号", ordered[focused].text)
    }

    @Test
    fun noFocusStaysNoFocus() {
        val (_, focused) = exactReadingFirst(candidates("あ", "い"), null) { false }
        assertEquals(-1, focused)
    }

    /** Zero-query suggestions have no reading at all, so nothing is exact and nothing moves. */
    @Test
    fun allPredictionsKeepMozcOrder() {
        val raw = candidates("おはよう", "お疲れ様", "ありがとう")
        val (ordered, _) = exactReadingFirst(raw, null) { true }
        assertEquals(raw.map { it.text }, ordered.map { it.text })
    }

    @Test
    fun emptyListIsHandled() {
        val (ordered, focused) = exactReadingFirst(emptyList(), null) { true }
        assertEquals(emptyList<MozcSession.Candidate>(), ordered)
        assertEquals(-1, focused)
    }
}
