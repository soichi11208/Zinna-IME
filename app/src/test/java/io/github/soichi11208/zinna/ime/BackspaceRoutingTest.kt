package io.github.soichi11208.zinna.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both routing rules are covered exhaustively. They take four and three values, so the whole truth
 * table is small enough to state outright — and a routing rule with an untested row is a rule
 * nobody notices breaking.
 */
class BackspaceRoutingTest {

    // --- editorDeletion --------------------------------------------------------------------

    @Test
    fun finalComposingCharacterDoesNotFallThroughToEditorDeletion() {
        assertEquals(
            EditorDeletion.NONE,
            BackspaceRouting.editorDeletion(hadComposition = true, hasSelection = false),
        )
    }

    /** Still mozc's key. Whatever the editor thinks is selected, the composition goes first. */
    @Test
    fun compositionOutranksASelection() {
        assertEquals(
            EditorDeletion.NONE,
            BackspaceRouting.editorDeletion(hadComposition = true, hasSelection = true),
        )
    }

    @Test
    fun idleBackspaceDeletesTheCharacterBeforeTheCursor() {
        assertEquals(
            EditorDeletion.PRECEDING_CHARACTER,
            BackspaceRouting.editorDeletion(hadComposition = false, hasSelection = false),
        )
    }

    @Test
    fun highlightedTextGoesWholeRatherThanOneCharacter() {
        assertEquals(
            EditorDeletion.SELECTION,
            BackspaceRouting.editorDeletion(hadComposition = false, hasSelection = true),
        )
    }

    // --- shouldRemoveOldEditorComposition --------------------------------------------------

    @Test
    fun emptiedCompositionRemovesOldEditorSpan() {
        assertTrue(
            BackspaceRouting.shouldRemoveOldEditorComposition(
                hadComposition = true,
                nextHasComposition = false,
                committedText = "",
            )
        )
    }

    @Test
    fun committedTextIsNotRemoved() {
        assertFalse(
            BackspaceRouting.shouldRemoveOldEditorComposition(
                hadComposition = true,
                nextHasComposition = false,
                committedText = "あ",
            )
        )
    }

    /** The everyday backspace: two or more characters were composing and one of them goes. */
    @Test
    fun survivingCompositionKeepsItsSpan() {
        assertFalse(
            BackspaceRouting.shouldRemoveOldEditorComposition(
                hadComposition = true,
                nextHasComposition = true,
                committedText = "",
            )
        )
        assertFalse(
            BackspaceRouting.shouldRemoveOldEditorComposition(
                hadComposition = true,
                nextHasComposition = true,
                committedText = "あ",
            )
        )
    }

    /** A composition appearing out of nothing — the first keystroke of a word. */
    @Test
    fun newCompositionKeepsItsSpan() {
        assertFalse(
            BackspaceRouting.shouldRemoveOldEditorComposition(
                hadComposition = false,
                nextHasComposition = true,
                committedText = "",
            )
        )
        assertFalse(
            BackspaceRouting.shouldRemoveOldEditorComposition(
                hadComposition = false,
                nextHasComposition = true,
                committedText = "あ",
            )
        )
    }

    @Test
    fun idleEmptyStateDoesNotRemoveEditorText() {
        assertFalse(
            BackspaceRouting.shouldRemoveOldEditorComposition(
                hadComposition = false,
                nextHasComposition = false,
                committedText = "",
            )
        )
    }

    /** Committing with nothing composing beforehand, as a symbol key does. */
    @Test
    fun idleCommitDoesNotRemoveEditorText() {
        assertFalse(
            BackspaceRouting.shouldRemoveOldEditorComposition(
                hadComposition = false,
                nextHasComposition = false,
                committedText = "あ",
            )
        )
    }
}
