package dev.oss.ime.ime

import android.content.Context
import dev.oss.ime.keyboard.InputStyle
import dev.oss.ime.mozc.MozcEngine
import org.mozc.android.inputmethod.japanese.protobuf.ProtoConfig.Config
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.CompositionMode
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

    private var currentStyle: InputStyle? = null
    private var configApplied = false

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
        return engine?.sendKey(key.build())?.toState()
    }

    fun sendSpecialKey(specialKey: KeyEvent.SpecialKey): State? =
        engine?.sendKey(KeyEvent.newBuilder().setSpecialKey(specialKey).build())?.toState()

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
     * Whether candidate [id] is for a reading other than the one being typed.
     *
     * See [exactReadingFirst] for what is done with the answer.
     *
     * mozc's mobile prediction mixes two kinds of candidate into one list: conversions of exactly
     * what was typed, and predictions that assume more typing to come. It ranks them together by
     * cost, so a prediction routinely outranks the plain conversion — typing でんわ offers 電話番号
     * above 電話, and あり offers ありだと above あり. That is useful when it guesses right and a
     * nuisance when the word was already finished, which is the common case.
     *
     * `CandidateWord.key` is set exactly when the candidate's reading differs from the composition
     * (it covers both longer readings like でんわばんごう and shorter partial ones like こんにち),
     * so it is the signal for pushing those below the exact matches.
     */
    private fun Output.idsExtendingReading(reading: String): Set<Int> {
        if (!hasAllCandidateWords()) return emptySet()
        var ids: MutableSet<Int>? = null
        for (word in allCandidateWords.candidatesList) {
            if (word.hasKey() && word.key != reading) {
                (ids ?: HashSet<Int>().also { ids = it }).add(word.id)
            }
        }
        // Usually every candidate is an exact match and there is nothing to move; allocating
        // nothing in that case keeps the common keystroke free.
        return ids ?: emptySet()
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

        val extending = idsExtendingReading(reading)
        val (candidates, focused) = exactReadingFirst(raw, focusedId, extending::contains)

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
 * Moves the candidates that convert exactly what was typed ahead of the predictions.
 *
 * A stable partition, not a re-ranking: mozc's order within each group is left alone, because it
 * is the product of a language model this has no business second-guessing. All this decides is
 * that a finished word beats a guess about an unfinished one.
 *
 * Split out from the proto handling so the focus arithmetic can be tested — the focused index
 * points at a slot, and once the list is reordered that slot holds something else.
 *
 * @param focusedId the id of the focused candidate, or null when nothing is focused
 * @return the reordered list and the index the focus moved to, or -1 for none
 */
internal fun exactReadingFirst(
    candidates: List<MozcSession.Candidate>,
    focusedId: Int?,
    extendsReading: (Int) -> Boolean,
): Pair<List<MozcSession.Candidate>, Int> {
    // Nothing to move is the usual case, and sorting would still copy the whole list.
    val ordered =
        if (candidates.none { extendsReading(it.id) }) candidates
        else candidates.sortedBy { if (extendsReading(it.id)) 1 else 0 }
    val focused = focusedId?.let { id -> ordered.indexOfFirst { it.id == id } } ?: -1
    return ordered to focused
}
