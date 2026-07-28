package dev.oss.ime.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackspaceRoutingTest {

    @Test
    fun finalComposingCharacterDoesNotFallThroughToEditorDeletion() {
        assertFalse(
            BackspaceRouting.shouldDeleteFromEditor(
                hadComposition = true,
                receivedMozcState = true,
            )
        )
    }

    @Test
    fun idleBackspaceDeletesFromEditor() {
        assertTrue(
            BackspaceRouting.shouldDeleteFromEditor(
                hadComposition = false,
                receivedMozcState = true,
            )
        )
    }

    @Test
    fun missingMozcResponseFallsBackToEditor() {
        assertTrue(
            BackspaceRouting.shouldDeleteFromEditor(
                hadComposition = true,
                receivedMozcState = false,
            )
        )
    }

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
}
