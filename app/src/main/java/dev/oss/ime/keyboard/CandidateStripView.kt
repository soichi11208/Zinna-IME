package dev.oss.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.oss.ime.R
import dev.oss.ime.ime.MozcSession
import dev.oss.ime.theme.KeyboardTheme

/**
 * Horizontal strip of conversion candidates.
 *
 * Built out of plain TextViews rather than a RecyclerView: a candidate list is short and fully
 * replaced on every keystroke, so view recycling buys nothing and costs an adapter's worth of
 * latency on the hottest path in the app.
 */
@SuppressLint("ViewConstructor")
class CandidateStripView(context: Context) : HorizontalScrollView(context) {

    fun interface OnCandidateSelectedListener {
        fun onCandidateSelected(candidate: MozcSession.Candidate)
    }

    /**
     * Something to offer when there is nothing to convert — the clipboard, undo, a clipping to
     * paste. The strip only draws these; what they mean is the input method's business.
     */
    class Action(
        val label: String,
        /** Drawable to sit before the label, or 0 for none. */
        val iconRes: Int = 0,
        val onSelected: () -> Unit,
    )

    var listener: OnCandidateSelectedListener? = null

    var theme: KeyboardTheme = KeyboardTheme.Default
        set(value) {
            field = value
            for (i in 0 until container.childCount) {
                applyTheme(container.getChildAt(i) as TextView)
            }
            invalidate()
        }

    /** Which chip currently carries the focus highlight, so only the two that change are touched. */
    private var focusedChild = -1

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(
            container,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
        )
        // Transparent so the panel behind — colour or the user's background image — shows through.
    }

    /**
     * Replaces the visible candidates.
     *
     * Reuses the chips already on screen instead of rebuilding them. This runs on every keystroke,
     * and constructing a TextView is not cheap — it resolves attributes, allocates layout state and
     * forces a full measure pass. Tearing down twenty of them and building twenty more per key was
     * enough to make fast typing feel like the keyboard was falling behind.
     *
     * Each property is written only when it actually differs: TextView.setText and setBackground
     * invalidate unconditionally, so assigning the same value still costs a layout pass.
     */
    fun setCandidates(candidates: List<MozcSession.Candidate>, focusedIndex: Int) {
        while (container.childCount > candidates.size) {
            container.removeViewAt(container.childCount - 1)
        }
        while (container.childCount < candidates.size) {
            container.addView(
                newChip(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        candidates.forEachIndexed { index, candidate ->
            val view = container.getChildAt(index) as TextView
            view.tag = candidate
            if (view.text != candidate.text) view.text = candidate.text
            // A chip reused from the idle actions still has their icon attached.
            if (view.getTag(R.id.chip_icon) != 0) {
                view.setTag(R.id.chip_icon, 0)
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            }
        }

        if (focusedChild != focusedIndex) {
            highlight(focusedChild, false)
            highlight(focusedIndex, true)
            focusedChild = focusedIndex
        }
        scrollTo(0, 0)
    }

    /**
     * Fills the strip with [actions] instead of candidates.
     *
     * The strip is the widest piece of idle real estate on the keyboard, and while nothing is being
     * converted it is showing nothing at all. Same chips, same recycling — only what the tag holds
     * differs, so the click path can tell an action from a candidate.
     */
    fun setActions(actions: List<Action>) {
        while (container.childCount > actions.size) {
            container.removeViewAt(container.childCount - 1)
        }
        while (container.childCount < actions.size) {
            container.addView(
                newChip(),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        actions.forEachIndexed { index, action ->
            val view = container.getChildAt(index) as TextView
            view.tag = action
            if (view.text != action.label) view.text = action.label
            if (view.getTag(R.id.chip_icon) != action.iconRes) {
                view.setTag(R.id.chip_icon, action.iconRes)
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(action.iconRes, 0, 0, 0)
            }
        }

        if (focusedChild != -1) {
            highlight(focusedChild, false)
            focusedChild = -1
        }
        scrollTo(0, 0)
    }

    private fun highlight(index: Int, on: Boolean) {
        val view = container.getChildAt(index) ?: return
        if (on) view.setBackgroundColor(theme.keyPressedColor) else view.background = null
    }

    private fun newChip(): TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        isSingleLine = true
        applyTheme(this)
        setOnClickListener { clicked ->
            // Picking a candidate commits text just as a keypress does, so it gets the same
            // confirmation. Without it the strip is the one part of the keyboard that feels
            // dead under the thumb.
            if (theme.hapticFeedback) {
                clicked.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            // Read through the tag rather than capturing: the chip outlives the candidate it was
            // first built for, so a captured one would commit whatever was showing keystrokes ago.
            when (val tag = clicked.tag) {
                is MozcSession.Candidate -> listener?.onCandidateSelected(tag)
                is Action -> tag.onSelected()
            }
        }
    }

    private fun applyTheme(view: TextView) {
        val padding = (12 * resources.displayMetrics.density).toInt()
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, theme.labelSizeSp)
        view.setTextColor(theme.candidateTextColor)
        view.setPadding(padding, padding / 2, padding, padding / 2)
        view.compoundDrawablePadding = padding / 2
        // The icons ship white so they can take the text colour, whatever the theme sets it to.
        view.compoundDrawableTintList = ColorStateList.valueOf(theme.candidateTextColor)
    }

    fun clear() = setCandidates(emptyList(), -1)
}
