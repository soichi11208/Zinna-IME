package dev.oss.ime.ime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updatePadding
import dev.oss.ime.R
import dev.oss.ime.keyboard.CandidateStripView
import dev.oss.ime.keyboard.FlickDirection
import dev.oss.ime.keyboard.FlickKeyboardView
import dev.oss.ime.keyboard.KeyAction
import dev.oss.ime.keyboard.KeyOutput
import dev.oss.ime.keyboard.KeySpec
import dev.oss.ime.keyboard.KeyboardLayout
import dev.oss.ime.keyboard.KeyboardPanelView
import dev.oss.ime.keyboard.LayoutRepository
import dev.oss.ime.settings.ImeSettings
import dev.oss.ime.theme.KeyboardTheme
import dev.oss.ime.theme.MaterialYouTheme
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Context as MozcContext
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent

/**
 * The input method itself.
 *
 * Responsibilities are kept narrow on purpose: translate [KeyOutput]s into mozc calls, and push
 * mozc's resulting [MozcSession.State] into the editor and the candidate strip. Flick geometry
 * lives in [FlickKeyboardView], conversion lives in mozc, and layout/theme data lives in
 * [LayoutRepository].
 */
class ZinnaImeService : InputMethodService() {

    private lateinit var repository: LayoutRepository
    private lateinit var settings: ImeSettings
    private lateinit var session: MozcSession

    private var keyboardView: FlickKeyboardView? = null
    private var candidateView: CandidateStripView? = null
    private var layout: KeyboardLayout? = null
    private var theme: KeyboardTheme = KeyboardTheme.Default

    /**
     * Whether mozc currently holds a composition. Mirrors the last rendered state so Enter can
     * decide between 確定 and the editor's action *before* it submits and destroys the evidence.
     */
    private var isComposing = false

    /** [ImeSettings.revision] the current input view was built from. */
    private var builtFromRevision = Int.MIN_VALUE

    private val clipboard = ClipboardHistory()

    /** The table key the previous keystroke sent, for [endTogglingIfRepeat]. */
    private var lastTableKey: String? = null

    /**
     * Whether [MozcSession.clientContext] still describes where the cursor actually is.
     *
     * Reading the text around the cursor is a round trip to the edited app, so it is not something
     * to do per keystroke. It only has to be right when a composition *starts* — that is the moment
     * mozc rebuilds its history from it — and the cursor cannot move under us while one is in
     * progress without [onUpdateSelection] saying so.
     */
    private var clientContextValid = false

    /** What the strip is worth when it is not covering the keyboard. */
    private var collapsedStripHeight = 0

    /** Experimental neural conversion, null unless the setting is on and a model was bundled. */
    private var neural: NeuralCandidates? = null

    /** The last state mozc rendered, so a late neural reply can be merged into it. */
    private var lastState: MozcSession.State? = null

    /**
     * The neural model's conversions of the composition currently on screen.
     *
     * Kept so the confirm key can commit what the strip is actually showing. Cleared whenever the
     * reading moves on, so a stale answer is never committed.
     */
    private var neuralFor: String = ""
    private var neuralTexts: List<String> = emptyList()

    /**
     * The system-bar insets last dispatched to us.
     *
     * Kept because a view built mid-session may never be handed them again: the window's insets
     * have not changed, so nothing re-dispatches, and a fresh panel would sit at zero padding with
     * its bottom row under the navigation bar. Seeding from these makes the rebuilt view correct on
     * its first frame instead of waiting for a dispatch that may not come.
     */
    private var systemInsetLeft = 0
    private var systemInsetRight = 0
    private var systemInsetBottom = 0

    override fun onCreate() {
        super.onCreate()
        repository = LayoutRepository(this)
        settings = ImeSettings(this)
        session = MozcSession(this)
        val candidates = NeuralCandidates(this)
        if (candidates.isBundled) {
            candidates.listener = NeuralCandidates.Listener(::onNeuralCandidates)
            neural = candidates
        }
        if (!session.isAvailable) {
            // Without the native engine there is nothing useful to do; the keyboard still renders
            // so the user can switch away rather than being stuck with a dead input field.
            Log.e(TAG, "mozc engine unavailable — conversion disabled")
        }
    }

    override fun onCreateInputView(): View {
        val loaded = repository.loadLayout(settings.keyboardStyle.defaultLayoutId)
            ?: repository.loadLayout(LayoutRepository.DEFAULT_LAYOUT_ID)
        if (loaded == null) Log.e(TAG, "default layout missing from assets")
        layout = loaded
        theme = resolveTheme()

        val root = KeyboardPanelView(this).apply {
            // The IME window runs edge-to-edge from targetSdk 35, so it extends underneath the
            // navigation bar and the bottom key row ends up beneath the gesture pill. Padding the
            // panel lifts the keys clear while its own background keeps covering the strip behind
            // the bar, so the keyboard still reaches the bottom of the screen.
            setBackgroundColor(this@ZinnaImeService.theme.backgroundColor)
            setBackgroundImage(settings.backgroundImage, settings.backgroundOpacity)
            // Start from what we already know, so a rebuild triggered by a settings change is
            // padded before it draws rather than after the next dispatch — which, since the
            // window's insets did not change, may never arrive.
            updatePadding(
                left = systemInsetLeft,
                right = systemInsetRight,
                bottom = systemInsetBottom,
            )
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() or
                        WindowInsetsCompat.Type.displayCutout()
                )
                systemInsetLeft = bars.left
                systemInsetRight = bars.right
                systemInsetBottom = bars.bottom
                view.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
            // And ask for a fresh dispatch once attached, so a stale seed (after a rotation, say)
            // is corrected rather than persisting for the rest of the session.
            doOnAttach { ViewCompat.requestApplyInsets(it) }
        }

        val candidates = CandidateStripView(this).apply {
            theme = this@ZinnaImeService.theme
            listener = CandidateStripView.OnCandidateSelectedListener(::onCandidateSelected)
            // Expanding covers the keyboard rather than squeezing it: a two-row keyboard is not
            // usable, and there is nothing to type while reading the list anyway.
            expandedListener = CandidateStripView.OnExpandedChangeListener { open ->
                keyboardView?.visibility = if (open) View.GONE else View.VISIBLE
                (layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.height =
                        if (open) LinearLayout.LayoutParams.MATCH_PARENT else collapsedStripHeight
                    layoutParams = it
                }
            }
        }
        val stripHeight = (theme.keyHeightDp * 0.8f * resources.displayMetrics.density).toInt()
        collapsedStripHeight = stripHeight

        val keyboard = FlickKeyboardView(this).apply {
            theme = this@ZinnaImeService.theme
            layout = loaded
            guideOverflowTop = stripHeight.toFloat()
            guideStyle = settings.flickGuideStyle
            listener = FlickKeyboardView.OnKeyOutputListener(::onKeyOutput)
        }

        root.addView(
            candidates,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, stripHeight),
        )
        root.addView(
            keyboard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        candidateView = candidates
        keyboardView = keyboard
        builtFromRevision = settings.revision
        loaded?.let { session.applyInputStyle(it.inputStyle) }
        return root
    }

    /**
     * Material You unless the user has dropped a theme file in.
     *
     * A user override wins because they asked for it explicitly; otherwise we follow the system
     * palette, which also means the keyboard tracks light/dark without a setting.
     */
    private fun resolveTheme(): KeyboardTheme {
        val base = repository.loadTheme(MaterialYouTheme.ID)
            ?: MaterialYouTheme.create(this, forceDark = settings.pureBlack)
        val sized = base.copy(keyHeightDp = base.keyHeightDp * settings.keyHeightScale)
        return if (settings.pureBlack) sized.asPureBlack() else sized
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Settings live in another process's Activity, so this is the first moment we can notice
        // they changed. Rebuilding only on a revision bump keeps the common case free.
        if (settings.revision != builtFromRevision) {
            setInputView(onCreateInputView())
        }
        if (!restarting) session.resetContext()
        lastTableKey = null
        clientContextValid = false
        isComposing = false
        // The only moment an input method is allowed to read the clipboard is while it is the
        // active one, which is exactly now.
        clipboard.record(getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
        showIdleActions()
    }

    /**
     * Rebuilds the keyboard so a light/dark switch or a wallpaper recolour is picked up. The
     * dynamic palette is read at view-creation time, so without this the keyboard keeps the colours
     * it was born with until the IME process restarts.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        setInputView(onCreateInputView())
    }

    /**
     * The cursor moved, or the app changed the text under us.
     *
     * Two things go stale at once: what mozc was told is to the left of the cursor, and our own
     * belief that a composition is in progress. Neither survives the editor being edited by anyone
     * but us, and a composition that the app has already dropped must not keep Backspace routed
     * into mozc.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        clientContextValid = false
        // candidatesStart < 0 means the editor is no longer showing a composing region.
        if (candidatesStart < 0 && isComposing) {
            session.resetContext()
            isComposing = false
            lastTableKey = null
            neuralTexts = emptyList()
            showIdleActions()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // Anything half-composed at this point can no longer be committed anywhere sensible.
        session.resetContext()
        currentInputConnection?.finishComposingText()
        lastTableKey = null
        candidateView?.clear()
    }

    override fun onDestroy() {
        neural?.close()
        session.close()
        super.onDestroy()
    }

    private fun onKeyOutput(output: KeyOutput, key: KeySpec, direction: FlickDirection) {
        when (val action = output.action) {
            is KeyAction.Input -> {
                refreshClientContext()
                endTogglingIfRepeat(action.text)
                render(session.sendText(action.text))
                lastTableKey = action.text
            }

            // The dakuten/small-kana cycle is a table entry, not a session command: mozc's flick
            // table maps '*' onto "advance the preceding kana one step" (あ→ぁ→あ, は→ば→ぱ→は).
            // Deliberately outside endTogglingIfRepeat: the cycle *is* mozc's toggling, so ending
            // it between two presses of this key resets は→ば→ぱ back to は.
            is KeyAction.ModifyChar -> render(session.sendText(CYCLE_MODIFIER_KEY))

            is KeyAction.InsertSymbol -> handleSymbol(action.text)

            is KeyAction.Undo -> render(session.undo())

            is KeyAction.Backspace -> handleBackspace()

            is KeyAction.Space -> handleSpace()

            // With something composing, this converts; with nothing to convert it behaves as
            // Enter. Inserting a space there would be the desktop behaviour and is not what a
            // 確定 key on a phone should do.
            is KeyAction.Convert ->
                if (isComposing) render(session.sendSpecialKey(KeyEvent.SpecialKey.SPACE))
                else handleEnter()

            is KeyAction.Enter -> handleEnter()

            is KeyAction.MoveCursor -> handleCursorMove(action.delta)

            is KeyAction.SwitchLayout -> switchLayout(action.layoutId)

            // Consumed by the keyboard view, which owns the shift state because it has to draw it.
            // Reaching here would mean the view stopped intercepting it.
            is KeyAction.Shift -> Log.w(TAG, "shift reached the service; view did not consume it")

            is KeyAction.ShowImePicker -> {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        }
        // Anything that is not a character ends the run, so the key after it starts a new one.
        if (output.action !is KeyAction.Input) lastTableKey = null
    }

    /**
     * Tells mozc what is to the left of the cursor, so it can convert in context.
     *
     * mozc turns `preceding_text` into history segments the moment a composition begins, and ranks
     * against them. That is the difference between "main" + にまーじしました coming out as
     * mainにマージしました and as main二マージしました — without it every phrase is converted from a
     * standing start, and a short particle loses to whatever homophone has the better unigram cost.
     *
     * Only refreshed when no composition is in progress: that is the only moment mozc reads it, and
     * `getTextBeforeCursor` is an IPC round trip to the app being typed into.
     *
     * Password fields get the type but no text. mozc adjusts its own behaviour for them, and the
     * contents of a password box are not something to feed a converter that learns.
     */
    private fun refreshClientContext() {
        if (clientContextValid && isComposing) return

        val info = currentInputEditorInfo
        val password = isPasswordField(info?.inputType ?: 0)
        val builder = MozcContext.newBuilder().setInputFieldType(
            if (password) MozcContext.InputFieldType.PASSWORD else MozcContext.InputFieldType.NORMAL
        )
        if (!password) {
            val before = currentInputConnection?.getTextBeforeCursor(PRECEDING_TEXT_CHARS, 0)
            if (!before.isNullOrEmpty()) builder.precedingText = before.toString()
        }
        session.clientContext = builder.build()
        clientContextValid = true
    }

    /**
     * mozc must not see, learn from, or convert with what is typed into a password box. The four
     * variations below are every way Android says "this is a password".
     */
    private fun isPasswordField(inputType: Int): Boolean {
        val variation = inputType and EditorInfo.TYPE_MASK_VARIATION
        return when (inputType and EditorInfo.TYPE_MASK_CLASS) {
            EditorInfo.TYPE_CLASS_TEXT -> variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
            EditorInfo.TYPE_CLASS_NUMBER -> variation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /**
     * Stops mozc's toggling when the same table key arrives twice in a row.
     *
     * A 12-key plane sends mozc a table key, and the same one twice advances that key's cycle
     * instead of repeating it: "22" gives b rather than aa, and "11" on the symbol plane gives ☆
     * rather than 11. That is how a phone without flick input reaches the other characters — but
     * every one of them is on a flick direction here, so the cycle only ever gets in the way.
     *
     * STOP_KEY_TOGGLING is mozc's own command for it: it settles the pending character so the next
     * press begins a new one. Sent only on a repeat, so an ordinary keystroke still costs one round
     * trip.
     *
     * The dakuten key is deliberately not routed through here. Its cycle は→ば→ぱ *is* mozc's
     * toggling, and ending it between presses drops the character back to は.
     */
    private fun endTogglingIfRepeat(tableKey: String) {
        if (lastTableKey == tableKey) session.stopKeyToggling()
    }

    /**
     * Symbols finalise whatever is composing and then go straight into the editor.
     *
     * They are not conversion input — nobody wants "あ(" offered as a candidate — and committing
     * first means the symbol lands after the kana rather than in the middle of it.
     */
    private fun handleSymbol(text: String) {
        if (isComposing) render(session.submit())
        currentInputConnection?.commitText(text, 1)
    }

    /**
     * Space, which mozc only handles while something is composing.
     *
     * With an empty composition it returns the key unconsumed on every plane — it has nowhere to
     * put a space and leaves the character to the client. Rendering that empty response was doing
     * nothing at all, so space appeared to work only as the second keystroke of a word.
     */
    private fun handleSpace() {
        val state = session.sendSpecialKey(KeyEvent.SpecialKey.SPACE)
        if (state == null || !state.consumed) {
            val fullWidth = layout?.inputStyle?.fullWidthSpace ?: false
            currentInputConnection?.commitText(if (fullWidth) FULL_WIDTH_SPACE else " ", 1)
        }
        render(state)
    }

    private fun handleBackspace() {
        // Route from the state before sending Backspace. Deleting the final composing character
        // returns an empty Mozc state, but that must not turn the same key into an editor delete.
        val hadComposition = isComposing
        val state = session.sendSpecialKey(KeyEvent.SpecialKey.BACKSPACE)
        if (BackspaceRouting.shouldDeleteFromEditor(hadComposition)) {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
        render(state)
    }

    /**
     * 確定 when something is composing, otherwise the editor's own Enter.
     *
     * The composing check has to happen *before* submitting — after a successful SUBMIT the
     * composition is by definition gone, so inspecting the resulting state would make every Enter
     * also fire the editor action and send the message you were only trying to finalise.
     */
    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        if (isComposing) {
            render(session.submit())
            return
        }
        val info = currentInputEditorInfo
        val imeOptions = info?.imeOptions ?: 0
        val inputType = info?.inputType ?: 0
        val target = EnterRouting.targetFor(imeOptions, inputType)

        // Logged because which branch a field lands in is not knowable from here — the descriptor
        // is whatever the app chose to declare. When Enter misbehaves somewhere, this says why.
        Log.d(TAG, "enter: imeOptions=0x${imeOptions.toString(16)} " +
            "inputType=0x${inputType.toString(16)} -> $target")

        when (target) {
            EnterRouting.Target.ACTION ->
                ic.performEditorAction(imeOptions and EditorInfo.IME_MASK_ACTION)

            EnterRouting.Target.NEWLINE -> ic.commitText("\n", 1)

            EnterRouting.Target.KEY_EVENT -> {
                val now = android.os.SystemClock.uptimeMillis()
                val enter = android.view.KeyEvent.KEYCODE_ENTER
                ic.sendKeyEvent(
                    android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, enter, 0)
                )
                ic.sendKeyEvent(
                    android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, enter, 0)
                )
            }
        }
    }

    private fun handleCursorMove(delta: Int) {
        session.stopKeyToggling()
        val special = if (delta < 0) KeyEvent.SpecialKey.LEFT else KeyEvent.SpecialKey.RIGHT
        val state = session.sendSpecialKey(special)
        if (state != null && state.hasComposition) {
            render(state)
            return
        }
        // Outside a composition, move the editor caret instead.
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return
        val target = (extracted.selectionStart + delta).coerceIn(0, extracted.text?.length ?: 0)
        ic.setSelection(target, target)
    }

    private fun switchLayout(layoutId: String) {
        // The layouts point inside their own family; the user's style decides which family the
        // kana and alphabet planes actually come from, so every switch goes through it.
        val resolved = settings.keyboardStyle.resolve(layoutId)
        val next = repository.loadLayout(resolved)
        if (next == null) {
            Log.w(TAG, "layout $resolved not found; staying on ${layout?.id}")
            return
        }
        // Finalise before swapping planes so a half-typed kana is not silently discarded.
        render(session.submit())
        lastTableKey = null
        layout = next
        keyboardView?.layout = next
        session.applyInputStyle(next.inputStyle)
    }

    private fun render(state: MozcSession.State?) {
        val ic = currentInputConnection ?: return
        if (state == null) {
            recoverFromLostSession(ic)
            return
        }

        val hadComposition = isComposing
        val removeOldEditorComposition =
            BackspaceRouting.shouldRemoveOldEditorComposition(
                hadComposition = hadComposition,
                nextHasComposition = state.hasComposition,
                committedText = state.committedText,
            )
        ic.beginBatchEdit()
        if (state.committedText.isNotEmpty()) {
            ic.commitText(state.committedText, 1)
        }
        if (state.preedit.isEmpty()) {
            if (removeOldEditorComposition) {
                // finishComposingText would confirm the stale one-character span instead.
                ic.commitText("", 1)
            } else {
                ic.finishComposingText()
            }
        } else {
            val styled = SpannableString(state.preedit).apply {
                setSpan(UnderlineSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            ic.setComposingText(styled, 1)
        }
        ic.endBatchEdit()

        isComposing = state.hasComposition
        lastState = state
        if (neuralFor != state.preedit) {
            neuralFor = state.preedit
            neuralTexts = emptyList()
        }

        // mozc's candidates are what the strip shows while the model is still thinking. Holding
        // them back left it blank for the first word of every sentence, which is worse than the
        // reordering it was meant to avoid.
        if (state.hasComposition && settings.neuralConversion) {
            neural?.request(
                reading = state.preedit,
                context = "",
                already = state.candidates.map { it.text },
            )
        }

        if (state.candidates.isEmpty()) {
            candidateView?.expanded = false
            showIdleActions()
        } else {
            candidateView?.setCandidates(state.candidates, state.focusedCandidateIndex)
        }
    }

    /** The neural conversions as strip candidates. Negative ids: these are not mozc's to select. */
    private fun neuralCandidateList(): List<MozcSession.Candidate> =
        neuralTexts.mapIndexed { i, text -> MozcSession.Candidate(-(i + 1), text) }

    /**
     * Puts the keyboard back into a usable state after mozc failed to answer.
     *
     * A null response means the call across JNI did not complete, so what mozc believes is now
     * unknown — but the editor is still showing the composing span from the last successful render,
     * and [isComposing] still says a composition exists. Leaving both is what wedges the keyboard:
     * Backspace routes to mozc because a composition is believed to exist, mozc has nothing to
     * delete, and the key stops doing anything at all.
     *
     * The composition is finished rather than dropped. The characters were typed on purpose, so
     * they stay in the editor as ordinary text; only the ability to keep converting them is lost.
     */
    private fun recoverFromLostSession(ic: android.view.inputmethod.InputConnection) {
        Log.w(TAG, "no response from mozc; finishing the composition and resetting state")
        if (isComposing) ic.finishComposingText()
        isComposing = false
        lastTableKey = null
        showIdleActions()
    }

    /**
     * Commits a candidate the user tapped.
     *
     * mozc's candidates are selected by id so it can learn from the choice. The neural ones are not
     * mozc's — their ids are ours and negative — so they are committed as plain text, after telling
     * mozc to finish what it was composing.
     */
    private fun onCandidateSelected(candidate: MozcSession.Candidate) {
        candidateView?.expanded = false
        if (candidate.id >= 0) {
            render(session.selectCandidate(candidate.id))
            return
        }
        session.revert()
        isComposing = false
        currentInputConnection?.commitText(candidate.text, 1)
        lastTableKey = null
        showIdleActions()
    }

    /**
     * Puts the neural model's conversions in front of mozc's.
     *
     * Turning the feature on is a statement that the model is the one to believe, so it takes the
     * front of the strip outright rather than being offered as an afterthought. mozc's candidates
     * keep their order behind it, and remain what the strip shows on its own until the model
     * answers.
     *
     * The reordering is visible: mozc's list is drawn first and the neural ones arrive a moment
     * later, so the strip shifts under the thumb. That is inherent to running a language model off
     * the keystroke path, and the alternative — holding the strip blank until the model replies —
     * is worse.
     *
     * A reply for a reading that is no longer being composed is dropped; the user has typed on.
     */
    private fun onNeuralCandidates(reading: String, candidates: List<String>) {
        val state = lastState ?: return
        if (state.preedit != reading || !isComposing) return
        neuralFor = reading
        neuralTexts = candidates
        val leading = neuralCandidateList()
        // The focus points at a slot in mozc's list, which has just moved along by that many.
        val focused =
            if (state.focusedCandidateIndex < 0) -1
            else state.focusedCandidateIndex + leading.size
        candidateView?.setCandidates(leading + state.candidates, focused)
    }

    /**
     * What the strip shows when there is nothing to convert.
     *
     * Undo, and a way into the clipboard — the two things Gboard puts there, and the two that are
     * otherwise unreachable without leaving the keyboard.
     */
    private fun showIdleActions() {
        val view = candidateView ?: return
        val actions = mutableListOf<CandidateStripView.Action>()
        if (clipboard.items().isNotEmpty()) {
            actions += CandidateStripView.Action(CLIPBOARD_LABEL, R.drawable.ic_clipboard) { showClipboard() }
        }
        actions += CandidateStripView.Action(UNDO_LABEL, R.drawable.ic_undo) { render(session.undo()) }
        view.setActions(actions)
    }

    /**
     * Swaps the strip over to the stored clippings, each one pasting itself.
     *
     * Labels are cut down to a chip's worth of text; the whole clipping is still what gets pasted.
     */
    private fun showClipboard() {
        val view = candidateView ?: return
        val actions = mutableListOf<CandidateStripView.Action>()
        actions += CandidateStripView.Action(BACK_LABEL) { showIdleActions() }
        for (text in clipboard.items()) {
            actions += CandidateStripView.Action(chipLabel(text)) {
                if (isComposing) render(session.submit())
                currentInputConnection?.commitText(text, 1)
                showIdleActions()
            }
        }
        view.setActions(actions)
    }

    private fun chipLabel(text: String): String {
        val flattened = text.replace(Regex("\\s+"), " ").trim()
        return if (flattened.length <= CLIP_LABEL_LIMIT) flattened
        else flattened.take(CLIP_LABEL_LIMIT) + "…"
    }

    companion object {
        private const val TAG = "ZinnaImeService"

        /** '*' in mozc's flick/12-key tables. See data/preedit/flick-hiragana.tsv upstream. */
        private const val CYCLE_MODIFIER_KEY = "*"

        /** U+3000 IDEOGRAPHIC SPACE, what a Japanese input mode types for the space key. */
        private const val FULL_WIDTH_SPACE = "　"

        private const val CLIPBOARD_LABEL = "クリップボード"
        private const val UNDO_LABEL = "元に戻す"
        private const val BACK_LABEL = "✕"

        /** How much of a clipping fits on a chip before it stops being readable. */
        private const val CLIP_LABEL_LIMIT = 24

        /**
         * How much text to the left mozc is given. It only takes the last connective token out of
         * this, so a short window is enough and keeps the round trip small.
         */
        private const val PRECEDING_TEXT_CHARS = 64
    }
}
