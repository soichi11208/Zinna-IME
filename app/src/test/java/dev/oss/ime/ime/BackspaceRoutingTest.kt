package dev.oss.ime.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both predicates are covered exhaustively. They take two and three values, so the whole truth
 * table is small enough to state outright — and a routing rule with an untested row is a rule
 * nobody notices breaking.
 */
class BackspaceRoutingTest {

    // --- shouldDeleteFromEditor ------------------------------------------------------------

    @Test
    fun finalComposingCharacterDoesNotFallThroughToEditorDeletion() {
        assertFalse(BackspaceRouting.shouldDeleteFromEditor(hadComposition = true))
    }

    @Test
    fun idleBackspaceDeletesFromEditor() {
        assertTrue(BackspaceRouting.shouldDeleteFromEditor(hadComposition = false))
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
