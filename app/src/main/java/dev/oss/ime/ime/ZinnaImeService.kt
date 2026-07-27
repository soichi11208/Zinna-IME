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
            listener = CandidateStripView.OnCandidateSelectedListener { candidate ->
                render(session.selectCandidate(candidate.id))
            }
        }
        val stripHeight = (theme.keyHeightDp * 0.8f * resources.displayMetrics.density).toInt()

        val keyboard = FlickKeyboardView(this).apply {
            theme = this@ZinnaImeService.theme
            layout = loaded
            guideOverflowTop = stripHeight.toFloat()
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
        isComposing = false
        candidateView?.clear()
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

    override fun onFinishInput() {
        super.onFinishInput()
        // Anything half-composed at this point can no longer be committed anywhere sensible.
        session.resetContext()
        currentInputConnection?.finishComposingText()
        candidateView?.clear()
    }

    override fun onDestroy() {
        session.close()
        super.onDestroy()
    }

    private fun onKeyOutput(output: KeyOutput, key: KeySpec, direction: FlickDirection) {
        when (val action = output.action) {
            is KeyAction.Input -> render(session.sendText(action.text))

            // The dakuten/small-kana cycle is a table entry, not a session command: mozc's flick
            // table maps '*' onto "advance the preceding kana one step" (あ→ぁ→あ, は→ば→ぱ→は).
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
        val state = session.sendSpecialKey(KeyEvent.SpecialKey.BACKSPACE)
        // mozc reports an empty composition both when it consumed the delete and when there was
        // nothing to delete, so fall through to the editor whenever nothing was composing.
        if (state == null || (!state.hasComposition && state.committedText.isEmpty())) {
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
        val action = imeOptions and EditorInfo.IME_MASK_ACTION

        // Only a field that can hold a newline gets one. Deciding this first is the whole fix: a
        // search bar that never set imeOptions reports IME_ACTION_UNSPECIFIED, and treating that
        // as "no action, so type a newline" stuffed a line break into a single-line box instead of
        // searching.
        if ((info?.inputType ?: 0) and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE != 0) {
            ic.commitText("\n", 1)
            return
        }

        val wantsAction = action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED &&
            imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0

        if (wantsAction) {
            ic.performEditorAction(action)
            return
        }

        // No action declared, but the field cannot take a newline either. Send a real Enter and let
        // the editor do whatever it does with one — that is how a hardware keyboard reaches these
        // fields, and it is what makes an unlabelled search box submit.
        val now = android.os.SystemClock.uptimeMillis()
        val enter = android.view.KeyEvent.KEYCODE_ENTER
        ic.sendKeyEvent(
            android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, enter, 0)
        )
        ic.sendKeyEvent(
            android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, enter, 0)
        )
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
        layout = next
        keyboardView?.layout = next
        session.applyInputStyle(next.inputStyle)
    }

    private fun render(state: MozcSession.State?) {
        val ic = currentInputConnection ?: return
        if (state == null) return

        ic.beginBatchEdit()
        if (state.committedText.isNotEmpty()) {
            ic.commitText(state.committedText, 1)
        }
        if (state.preedit.isEmpty()) {
            ic.finishComposingText()
        } else {
            val styled = SpannableString(state.preedit).apply {
                setSpan(UnderlineSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            ic.setComposingText(styled, 1)
        }
        ic.endBatchEdit()

        isComposing = state.hasComposition
        candidateView?.setCandidates(state.candidates, state.focusedCandidateIndex)
    }

    companion object {
        private const val TAG = "ZinnaImeService"

        /** '*' in mozc's flick/12-key tables. See data/preedit/flick-hiragana.tsv upstream. */
        private const val CYCLE_MODIFIER_KEY = "*"

        /** U+3000 IDEOGRAPHIC SPACE, what a Japanese input mode types for the space key. */
        private const val FULL_WIDTH_SPACE = "　"
    }
}
