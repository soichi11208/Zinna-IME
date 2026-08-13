package io.github.soichi11208.zinna.ime

import android.content.Context
import io.github.soichi11208.zinna.keyboard.InputStyle
import io.github.soichi11208.zinna.mozc.MozcEngine
import org.mozc.android.inputmethod.japanese.protobuf.ProtoConfig.Config
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCandidateWindow.CandidateAttribute
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.CompositionMode
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Context as MozcContext
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Request
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand

/**
 * Task-level view of a mozc session: sends key events and session commands, and flattens the
 * [Output] proto into the three things the IME actually renders.
 *
 * The [Request] configured here is what tells mozc it is driving a mobile 12-key surface. Getting
 * it wrong is the classic reason a from-scratch mozc client produces desktop-style behaviour on a
 * phone (no mobile prediction, wrong segment sizing), so it is set once at session creation and
 * refreshed whenever the layout's [InputStyle] changes.
 */
class MozcSession(context: Context) {

    private val engine: MozcEngine? = MozcEngine.get(context)

    val isAvailable: Boolean get() = engine != null

    data class State(
        /** Text mozc has finalised; append it to the editor. */
        val committedText: String = "",
        /** Current composition, to be shown underlined. */
        val preedit: String = "",
        /** Caret position within [preedit], in Java chars. */
        val preeditCursor: Int = 0,
        val candidates: List<Candidate> = emptyList(),
        val focusedCandidateIndex: Int = -1,
        /**
         * Whether mozc handled the key at all.
         *
         * False means it declined and expects the client to act — which it does for any key it has
         * no use for in the current state, notably space on an empty composition. Treating that as
         * "nothing to do" silently swallows the keystroke.
         */
        val consumed: Boolean = false,
    ) {
        val hasComposition: Boolean get() = preedit.isNotEmpty()
    }

    data class Candidate(val id: Int, val text: String)

    private companion object {
        /** How many candidates to ask mozc for at once. */
        const val CANDIDATE_PAGE_SIZE = 36
    }

    private var currentStyle: InputStyle? = null
    private var configApplied = false

    /**
     * What the editor looks like around the cursor, handed to mozc with every key.
     *
     * mozc reconstructs history segments from `preceding_text` when a composition starts, and ranks
     * with them — which is how it knows that に after "main" is the particle rather than 二. Without
     * this it converts every phrase from a standing start; see ZinnaImeService.refreshClientContext.
     *
     * It also carries the field type, so mozc can behave itself in a password box.
     */
    var clientContext: MozcContext? = null

    fun applyInputStyle(style: InputStyle) {
        if (style == currentStyle) return
        val engine = engine ?: return
        val request = Request.newBuilder()
            .setSpecialRomanjiTable(
                Request.SpecialRomanjiTable.forNumber(style.mozcTableNumber)
                    ?: Request.SpecialRomanjiTable.DEFAULT_TABLE
            )
            .setZeroQuerySuggestion(true)
            .setMixedConversion(true)
            .setUpdateInputModeFromSurroundingText(false)
            .setAutoPartialSuggestion(true)
            // Upstream's default is 9, which is a page for a desktop candidate window and too few
            // for a strip the user can scroll and expand. The cost is a slightly longer
            // candidate_window in each response; the field that actually dominated the payload,
            // all_candidate_words, is trimmed at the JNI boundary instead.
            .setCandidatePageSize(CANDIDATE_PAGE_SIZE)
            .setSpaceOnAlphanumeric(Request.SpaceOnAlphanumeric.SPACE_OR_CONVERT_COMMITTING_COMPOSITION)
            .setCrossingEdgeBehavior(Request.CrossingEdgeBehavior.COMMIT_WITHOUT_CONSUMING)
            // Forgives a missing or stray dakuten/small kana: "かつこう" still finds 学校. Costs
            // nothing extra — it widens an existing dictionary lookup rather than adding one.
            .setKanaModifierInsensitiveConversion(true)
            .build()
        engine.eval(
            Input.newBuilder()
                .setType(Input.CommandType.SET_REQUEST)
                .setRequest(request)
        )
        applyConfig(engine)

        // The table alone is not enough. It controls transliteration; the composition mode controls
        // whether mozc runs conversion over the result, so the latin and numeric planes have to be
        // put into HALF_ASCII or they come back with kanji candidates for "abc".
        engine.sendCommand(
            SessionCommand.newBuilder()
                .setType(SessionCommand.CommandType.SWITCH_COMPOSITION_MODE)
                .setCompositionMode(
                    CompositionMode.forNumber(style.mozcCompositionMode) ?: CompositionMode.HIRAGANA
                )
                .build()
        )
        currentStyle = style
    }

    /**
     * Sends one key.
     *
     * [text] is a *romanji-table key*, not the character the user sees. mozc's FLICK_TO_HIRAGANA
     * table is keyed on ASCII — `1` produces あ, `_` produces い, `*` cycles dakuten/small forms —
     * and the table is what implements dakuten cycling and small-kana rules. Sending the kana
     * directly would bypass all of it, so a single ASCII char goes out as `key_code` and lets mozc
     * do the composing.
     *
     * Anything outside ASCII is text we resolved ourselves and mozc has no table entry for (emoji,
     * a symbol run), so it goes out as `key_string` for direct insertion.
     */
    fun sendText(text: String): State? {
        val key = KeyEvent.newBuilder()
        val singleChar = text.length == 1 && text[0].code in 0x20..0x7E
        if (singleChar) {
            key.keyCode = text[0].code
        } else {
            key.keyString = text
        }
        return engine?.sendKey(key.build(), clientContext)?.toState()
    }

    fun sendSpecialKey(specialKey: KeyEvent.SpecialKey): State? =
        engine?.sendKey(KeyEvent.newBuilder().setSpecialKey(specialKey).build(), clientContext)
            ?.toState()

    /**
     * Turns on the two correction features. Both are AND-ed with the Request flags above and both
     * default to off.
     *
     * `use_typing_correction` is what reaches our TypingCorrectionModel: upstream leaves the
     * predictor's typing-correction path in place but ships a stub supplemental model, so on a
     * stock OSS build this flag changes nothing. See
     * patches/0001-rule-based-typing-correction.patch.
     */
    private fun applyConfig(engine: MozcEngine) {
        if (configApplied) return
        val output = engine.eval(Input.newBuilder().setType(Input.CommandType.GET_CONFIG))
        val builder =
            if (output != null && output.hasConfig()) output.config.toBuilder()
            else Config.newBuilder()
        engine.eval(
            Input.newBuilder()
                .setType(Input.CommandType.SET_CONFIG)
                .setConfig(
                    builder
                        .setUseTypingCorrection(true)
                        .setUseKanaModifierInsensitiveConversion(true)
                )
        )
        configApplied = true
    }

    fun submit(): State? = sendSessionCommand(SessionCommand.CommandType.SUBMIT)

    fun revert(): State? = sendSessionCommand(SessionCommand.CommandType.REVERT)

    fun resetContext(): State? = sendSessionCommand(SessionCommand.CommandType.RESET_CONTEXT)

    /** Pulls the last commit back into a composition so it can be re-converted. */
    fun undo(): State? = sendSessionCommand(SessionCommand.CommandType.UNDO)

    /** Backspace on an empty composition is the editor's problem, not mozc's. */
    fun undoOrRewind(): State? = sendSessionCommand(SessionCommand.CommandType.UNDO_OR_REWIND)

    fun selectCandidate(candidateId: Int): State? = engine?.sendCommand(
        SessionCommand.newBuilder()
            .setType(SessionCommand.CommandType.SUBMIT_CANDIDATE)
            .setId(candidateId)
            .build()
    )?.toState()

    fun highlightCandidate(candidateId: Int): State? = engine?.sendCommand(
        SessionCommand.newBuilder()
            .setType(SessionCommand.CommandType.HIGHLIGHT_CANDIDATE)
            .setId(candidateId)
            .build()
    )?.toState()

    /**
     * Ends any toggle-in-progress. Flick input still needs this: a same-key tap sequence is what
     * mozc uses to cycle characters, and moving the caret must not be absorbed by that cycle.
     */
    fun stopKeyToggling(): State? = sendSessionCommand(SessionCommand.CommandType.STOP_KEY_TOGGLING)

    fun close() {
        engine?.deleteSession()
        currentStyle = null
    }

    private fun sendSessionCommand(type: SessionCommand.CommandType): State? =
        engine?.sendCommand(SessionCommand.newBuilder().setType(type).build())?.toState()

    /**
     * Sorts every displayed candidate into one of [CandidateTier]'s buckets.
     *
     * Three facts come out of `all_candidate_words`, which lists every candidate the engine built:
     * `key` is set only when the candidate's reading differs from what was typed, the
     * USER_DICTIONARY attribute marks the ones that came from words the user supplied rather than
     * from the system dictionary, and TYPING_CORRECTION marks a reading the engine believes was
     * mistyped. None of the three is available from the displayed list on its own.
     *
     * A correction carries a different reading too, but it is not a prediction about text still to
     * come — it is a reading of what was already typed, under the belief that a key was missed. It
     * gets a band of its own between the two. Sorting it with the predictions put 漢字 for かゆじ
     * below every literal reading of the typo, 粥じ and 痒じ included, far enough down to be
     * invisible; exempting it from the bands altogether went too far the other way, and 死後の rose
     * to second place for a correctly typed しごと.
     */
    private fun Output.tiersById(reading: String): Map<Int, CandidateTier> {
        if (!hasAllCandidateWords()) return emptyMap()
        val tiers = HashMap<Int, CandidateTier>()
        for (word in allCandidateWords.candidatesList) {
            val corrected =
                word.attributesList.contains(CandidateAttribute.TYPING_CORRECTION)
            val extends = word.hasKey() && word.key != reading
            val fromDictionary =
                word.attributesList.contains(CandidateAttribute.USER_DICTIONARY)
            tiers[word.id] = CandidateTier.of(
                exact = !extends,
                fromDictionary = fromDictionary,
                corrected = corrected,
            )
        }
        return tiers
    }

    private fun Output.toState(): State {
        val preeditText = StringBuilder()
        var cursor = 0
        if (hasPreedit()) {
            preedit.segmentList.forEach { preeditText.append(it.value) }
            cursor = preedit.cursor
        }

        val reading = preeditText.toString()
        val raw = if (hasCandidateWindow()) {
            candidateWindow.candidateList.map { Candidate(it.id, it.value) }
        } else {
            emptyList()
        }
        val focusedId = if (hasCandidateWindow() && candidateWindow.hasFocusedIndex()) {
            raw.getOrNull(candidateWindow.focusedIndex)?.id
        } else {
            null
        }

        val tiers = tiersById(reading)
        val (candidates, focused) = byTier(raw, focusedId) { id ->
            tiers[id] ?: CandidateTier.MOZC_EXACT
        }

        return State(
            committedText = if (hasResult()) result.value else "",
            preedit = preeditText.toString(),
            preeditCursor = cursor.coerceIn(0, preeditText.length),
            candidates = candidates,
            focusedCandidateIndex = focused,
            consumed = consumed,
        )
    }
}

/**
 * Where a candidate belongs in the strip, best band first.
 *
 * Three questions decide it, in this order. Does the candidate convert exactly what was typed, or is
 * it a prediction about text still to come? Failing that, is it a correction — a reading of what was
 * typed on the belief that a key was missed? And did it come from the system dictionary or from a
 * word the user supplied? Exactness dominates: a guess about an unfinished word is never what
 * someone reaching for a finished one wants, however good the source. A correction sits between the
 * two, because whoever typed cleanly wants none of it and whoever mistyped wants nothing else.
 */
internal enum class CandidateTier {
    MOZC_EXACT,
    DICTIONARY_EXACT,
    TYPING_CORRECTION,
    MOZC_PREDICTED,
    DICTIONARY_PREDICTED;

    companion object {
        fun of(
            exact: Boolean,
            fromDictionary: Boolean,
            corrected: Boolean = false,
        ): CandidateTier = when {
            exact && !fromDictionary -> MOZC_EXACT
            exact -> DICTIONARY_EXACT
            corrected -> TYPING_CORRECTION
            !fromDictionary -> MOZC_PREDICTED
            else -> DICTIONARY_PREDICTED
        }
    }
}



/**
 * Groups the candidates into [CandidateTier] bands, keeping the engine's order inside each.
 *
 * A stable partition, not a re-ranking: the order within a band is the product of a language model
 * and this user's own learning, which this has no business second-guessing. All it decides is which
 * band comes first.
 *
 * Split out from the proto handling so the focus arithmetic can be tested — the focused index
 * points at a slot, and once the list is reordered that slot holds something else.
 *
 * @param focusedId the id of the focused candidate, or null when nothing is focused
 * @return the reordered list and the index the focus moved to, or -1 for none
 */
internal fun byTier(
    candidates: List<MozcSession.Candidate>,
    focusedId: Int?,
    tierOf: (Int) -> CandidateTier,
): Pair<List<MozcSession.Candidate>, Int> {
    val ordered =
        if (candidates.size < 2) candidates
        else candidates.sortedBy { tierOf(it.id).ordinal }
    val focused = focusedId?.let { id -> ordered.indexOfFirst { it.id == id } } ?: -1
    return ordered to focused
}
