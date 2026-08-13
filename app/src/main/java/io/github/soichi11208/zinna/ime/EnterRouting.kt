package io.github.soichi11208.zinna.ime

import android.view.inputmethod.EditorInfo

/**
 * Decides what the Enter key means for the field currently being edited.
 *
 * Split out and stated as a truth table because the ordering here has been wrong twice: once
 * treating an unset action as permission to type a newline, which put line breaks into single-line
 * search boxes, and once checking the multi-line flag ahead of the action, which did the same to
 * search boxes that happen to accept pasted line breaks.
 */
internal object EnterRouting {

    enum class Target {
        /** Run the editor's declared action — search, go, send. */
        ACTION,

        /** Type a line break. */
        NEWLINE,

        /**
         * Send a real Enter key and let the editor decide.
         *
         * What a hardware keyboard does, and what makes a field that declared nothing at all still
         * submit: a single-line TextView turns Enter into its editor action by itself.
         */
        KEY_EVENT,
    }

    fun targetFor(imeOptions: Int, inputType: Int): Target {
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val declaresAction =
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        val refusesEnterAction = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0

        // The action wins unless the field opted out. IME_FLAG_NO_ENTER_ACTION is the platform's
        // own way for a field to say "do not repurpose Enter", and it is what a genuine multi-line
        // field is expected to set — so the multi-line flag alone is not enough to refuse.
        if (declaresAction && !refusesEnterAction) return Target.ACTION
        if (inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE != 0) return Target.NEWLINE
        return Target.KEY_EVENT
    }
}
