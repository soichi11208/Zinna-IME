package dev.oss.ime.ime

/**
 * Keeps one Backspace owned by the state that existed before Mozc handled it.
 *
 * Deleting the final composing character produces an empty response, which is otherwise
 * indistinguishable from pressing Backspace while the session was already idle.
 */
internal enum class EditorDeletion {
    /** Mozc owns the key; the editor must not touch its own text. */
    NONE,

    /** Replace the highlighted run with nothing. */
    SELECTION,

    /** The plain case: take the one character in front of the cursor. */
    PRECEDING_CHARACTER,
}

internal object BackspaceRouting {

    /**
     * What the editor itself has to delete once mozc has had the key.
     *
     * Nothing at all while a composition existed: that key is mozc's — and that holds even when
     * mozc fails to answer, because the composing span is still sitting in the editor and deleting
     * around it would take the character *before* it, which is not the one the user aimed at. A
     * lost response is spent on recovery instead; see [ZinnaImeService.recoverFromLostSession].
     *
     * A selection outranks the character in front of it. Someone who has highlighted a run of text
     * and reaches for Backspace means that run, and `deleteSurroundingText` would quietly ignore it
     * and eat the character before it instead, leaving the highlight untouched.
     */
    fun editorDeletion(hadComposition: Boolean, hasSelection: Boolean): EditorDeletion = when {
        hadComposition -> EditorDeletion.NONE
        hasSelection -> EditorDeletion.SELECTION
        else -> EditorDeletion.PRECEDING_CHARACTER
    }

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
