package dev.oss.ime.ime

/**
 * Keeps one Backspace owned by the state that existed before Mozc handled it.
 *
 * Deleting the final composing character produces an empty response, which is otherwise
 * indistinguishable from pressing Backspace while the session was already idle.
 */
internal object BackspaceRouting {

    fun shouldDeleteFromEditor(
        hadComposition: Boolean,
        receivedMozcState: Boolean,
    ): Boolean = !hadComposition || !receivedMozcState

    /**
     * Finishing an old composing span confirms it. When Mozc has just deleted that composition,
     * the editor-side span must instead be replaced with empty text.
     */
    fun shouldRemoveOldEditorComposition(
        hadComposition: Boolean,
        nextHasComposition: Boolean,
        committedText: String,
    ): Boolean =
        hadComposition && !nextHasComposition && committedText.isEmpty()
}
