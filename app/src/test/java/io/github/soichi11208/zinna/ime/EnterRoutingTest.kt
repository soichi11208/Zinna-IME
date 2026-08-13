package io.github.soichi11208.zinna.ime

import android.view.inputmethod.EditorInfo
import io.github.soichi11208.zinna.ime.EnterRouting.Target
import org.junit.Assert.assertEquals
import org.junit.Test

class EnterRoutingTest {

    private fun target(imeOptions: Int, inputType: Int = EditorInfo.TYPE_CLASS_TEXT) =
        EnterRouting.targetFor(imeOptions, inputType)

    private val multiLine = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE

    /** The reported bug: a search box that also accepts pasted line breaks must still search. */
    @Test
    fun searchWinsOverTheMultiLineFlag() {
        assertEquals(Target.ACTION, target(EditorInfo.IME_ACTION_SEARCH, multiLine))
    }

    @Test
    fun everyDeclaredActionRunsTheAction() {
        for (action in listOf(
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_PREVIOUS,
        )) {
            assertEquals("action $action", Target.ACTION, target(action))
        }
    }

    /** How a field says "leave my Enter alone" — the opt-out a real multi-line field sets. */
    @Test
    fun noEnterActionFlagHandsEnterBack() {
        val options = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertEquals(Target.NEWLINE, target(options, multiLine))
        assertEquals(Target.KEY_EVENT, target(options))
    }

    @Test
    fun actionNoneNeverRunsAnAction() {
        assertEquals(Target.NEWLINE, target(EditorInfo.IME_ACTION_NONE, multiLine))
        assertEquals(Target.KEY_EVENT, target(EditorInfo.IME_ACTION_NONE))
    }

    /**
     * A field that declared nothing. The single-line case is the other half of the original bug:
     * it must not become a newline.
     */
    @Test
    fun unspecifiedActionFallsBackByShape() {
        assertEquals(Target.NEWLINE, target(EditorInfo.IME_ACTION_UNSPECIFIED, multiLine))
        assertEquals(Target.KEY_EVENT, target(EditorInfo.IME_ACTION_UNSPECIFIED))
    }

    /** Nothing declared at all — no imeOptions, no inputType. */
    @Test
    fun emptyDescriptorSendsAKeyEvent() {
        assertEquals(Target.KEY_EVENT, EnterRouting.targetFor(0, 0))
    }

    /** Flags outside the action mask must not be mistaken for an action. */
    @Test
    fun unrelatedFlagsDoNotLookLikeAnAction() {
        val options = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        assertEquals(Target.KEY_EVENT, target(options))
        assertEquals(Target.NEWLINE, target(options, multiLine))
    }
}
