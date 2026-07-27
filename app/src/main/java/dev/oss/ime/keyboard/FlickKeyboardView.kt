package dev.oss.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.oss.ime.theme.KeyboardTheme
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a [KeyboardLayout] and turns touches into [KeyOutput]s.
 *
 * Flick resolution is deliberately handled here rather than delegated to mozc's toggle table: we
 * know the pointer geometry, so we can commit the moment the finger crosses the threshold and give
 * the user a live guide of where each direction leads. mozc then receives an already-resolved kana.
 *
 * Multi-touch is supported to the extent that matters for typing speed — a second finger landing
 * while the first is still down commits the first key immediately, which is what fast thumb typists
 * expect from Simeji and Gboard.
 */
class FlickKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    fun interface OnKeyOutputListener {
        fun onKeyOutput(output: KeyOutput, key: KeySpec, direction: FlickDirection)
    }

    var listener: OnKeyOutputListener? = null

    var theme: KeyboardTheme = KeyboardTheme.Default
        set(value) {
            field = value
            applyTheme()
            invalidate()
        }

    /** Whether the next letter, or every letter, is capitalised. See [KeyAction.Shift]. */
    private enum class ShiftState { OFF, ONCE, LOCKED }

    private var shift = ShiftState.OFF

    var layout: KeyboardLayout? = null
        set(value) {
            field = value
            // A plane switch starts fresh: a shift armed on the symbol page means nothing on the
            // one being switched to, and leaving it latched is the kind of state users cannot see
            // the cause of.
            shift = ShiftState.OFF
            // Recompute here rather than waiting for onSizeChanged: every plane has the same row
            // count, so swapping kana → ascii leaves the view exactly the same size and
            // onSizeChanged never fires. Relying on it left the new layout with no placed keys at
            // all — a blank keyboard that ignores touches.
            activeTouches.clear()
            cancelRepeat()
            refreshPlacement()
            requestLayout()
            invalidate()
        }

    /**
     * Distance in dp the finger must travel before a flick registers. Exposed because thumb size
     * and screen density vary enough that a single value annoys somebody; the settings screen maps
     * a slider onto it.
     */
    var flickThresholdDp: Float = DEFAULT_FLICK_THRESHOLD_DP

    /**
     * Pixels above this view that the flick guide may draw into — in practice the height of the
     * candidate strip sitting directly above. Requires the parent to have `clipChildren = false`,
     * otherwise the guide is still cut at this view's top edge.
     */
    var guideOverflowTop: Float = 0f

    private data class PlacedKey(val spec: KeySpec, val bounds: RectF)

    /** State for one finger currently on the keyboard. */
    private class Touch(
        val key: PlacedKey,
        val downX: Float,
        val downY: Float,
        var direction: FlickDirection = FlickDirection.CENTER,
        /** Set for repeatable keys, which emit on press instead of on release. */
        var firedOnPress: Boolean = false,
    )

    private var placedKeys: List<PlacedKey> = emptyList()
    private val activeTouches = mutableMapOf<Int, Touch>()

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val modifierPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val guideBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guideLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val handler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    private val flickThresholdPx: Float
        get() = flickThresholdDp * resources.displayMetrics.density

    init {
        isFocusable = false
        isClickable = true
        applyTheme()
    }

    private fun applyTheme() {
        keyPaint.color = theme.keyColor
        keyPressedPaint.color = theme.keyPressedColor
        modifierPaint.color = theme.modifierKeyColor
        labelPaint.color = theme.labelColor
        guideBackgroundPaint.color = theme.flickGuideColor
        guideLabelPaint.color = theme.flickGuideLabelColor
        // The parent paints the panel — a colour, or the user's background image. Painting it here
        // too would cover that image with an opaque rectangle.
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rowCount = layout?.rows?.size ?: 4
        val height = (theme.keyHeightDp * resources.displayMetrics.density * rowCount).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        labelPaint.textSize = theme.labelSizeSp * resources.displayMetrics.scaledDensity
        guideLabelPaint.textSize = theme.labelSizeSp * resources.displayMetrics.scaledDensity
        refreshPlacement()
    }

    private fun refreshPlacement() {
        placedKeys = if (width > 0 && height > 0) {
            computePlacement(width.toFloat(), height.toFloat())
        } else {
            emptyList()
        }
    }

    private fun computePlacement(width: Float, height: Float): List<PlacedKey> {
        val current = layout ?: return emptyList()
        val totalRowWeight = current.rows.sumOf { it.weight.toDouble() }.toFloat()
        val gap = theme.keyGapDp * resources.displayMetrics.density
        val placed = mutableListOf<PlacedKey>()
        var y = 0f
        for (row in current.rows) {
            val rowHeight = height * (row.weight / totalRowWeight)
            val rowWeight = row.totalWeight
            var x = width * (row.padStart / rowWeight)
            for (key in row.keys) {
                val keyWidth = width * (key.weight / rowWeight)
                placed += PlacedKey(
                    key,
                    RectF(x + gap, y + gap, x + keyWidth - gap, y + rowHeight - gap),
                )
                x += keyWidth
            }
            y += rowHeight
        }
        return placed
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = theme.keyCornerRadiusDp * resources.displayMetrics.density

        for (placed in placedKeys) {
            // With flat keys only the pressed one gets a shape: the resting keys are their labels
            // and nothing else, so the panel (or the user's background image) shows through.
            val paint = when {
                isPressed(placed) -> keyPressedPaint
                theme.flatKeys -> null
                placed.spec.style == KeyStyle.CHARACTER -> keyPaint
                else -> modifierPaint
            }
            if (paint != null) canvas.drawRoundRect(placed.bounds, radius, radius, paint)
            val baseline = placed.bounds.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(faceLabel(placed.spec), placed.bounds.centerX(), baseline, labelPaint)
        }

        // Guides go on top of every key so a guide near the edge is never clipped by a neighbour.
        for (touch in activeTouches.values) {
            if (touch.key.spec.hasFlicks) drawFlickGuide(canvas, touch, radius)
        }
    }

    private fun drawFlickGuide(canvas: Canvas, touch: Touch, radius: Float) {
        val cells = guideCells(touch)
        if (cells.isEmpty()) return

        // The up-cell of a top-row key sits above this view entirely, and the side cells of the
        // outer columns sit beyond its edges. Nudging the whole cross back inside the drawable area
        // keeps every option readable; without it the top row's up-flick — う, く, す … — is simply
        // invisible, which is the one guide a user most needs to see.
        val shift = fitShift(cells.values)

        for ((direction, rect) in cells) {
            val output = touch.key.spec.output(direction) ?: continue
            rect.offset(shift.x, shift.y)
            val selected = direction == touch.direction
            guideBackgroundPaint.alpha = if (selected) 255 else 200
            canvas.drawRoundRect(rect, radius, radius, guideBackgroundPaint)
            guideLabelPaint.color =
                if (selected) theme.flickGuideSelectedLabelColor else theme.flickGuideLabelColor
            val baseline = rect.centerY() - (guideLabelPaint.descent() + guideLabelPaint.ascent()) / 2f
            canvas.drawText(output.label, rect.centerX(), baseline, guideLabelPaint)
        }
        guideBackgroundPaint.alpha = 255
    }

    private fun guideCells(touch: Touch): Map<FlickDirection, RectF> {
        val bounds = touch.key.bounds
        val w = bounds.width()
        val h = bounds.height()
        val cells = LinkedHashMap<FlickDirection, RectF>()
        for (direction in FlickDirection.entries) {
            if (touch.key.spec.output(direction) == null) continue
            val dx = when (direction) {
                FlickDirection.LEFT -> -w
                FlickDirection.RIGHT -> w
                else -> 0f
            }
            val dy = when (direction) {
                FlickDirection.UP -> -h
                FlickDirection.DOWN -> h
                else -> 0f
            }
            cells[direction] = RectF(bounds).apply { offset(dx, dy) }
        }
        return cells
    }

    /**
     * How far the guide has to move to sit inside the drawable area.
     *
     * The area extends [guideOverflowTop] above this view because the parent turns off child
     * clipping, letting the guide spill over the candidate strip rather than being cut off by it.
     */
    private fun fitShift(cells: Collection<RectF>): PointF {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (rect in cells) {
            left = min(left, rect.left)
            top = min(top, rect.top)
            right = max(right, rect.right)
            bottom = max(bottom, rect.bottom)
        }
        val dx = when {
            left < 0f -> -left
            right > width -> width - right
            else -> 0f
        }
        val dy = when {
            top < -guideOverflowTop -> -guideOverflowTop - top
            bottom > height -> height - bottom
            else -> 0f
        }
        return PointF(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                onPointerDown(event.getPointerId(index), event.getX(index), event.getY(index))
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    onPointerMove(event.getPointerId(index), event.getX(index), event.getY(index))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                onPointerUp(event.getPointerId(index))
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelRepeat()
                activeTouches.clear()
                invalidate()
            }
        }
        return true
    }

    private fun onPointerDown(pointerId: Int, x: Float, y: Float) {
        val key = placedKeys.firstOrNull { it.bounds.contains(x, y) } ?: return
        // A new finger means the previous key is settled; commit it rather than letting a stray
        // move event on the old pointer rewrite it.
        activeTouches.values.forEach { commit(it) }
        activeTouches.clear()
        cancelRepeat()

        val touch = Touch(key, x, y)
        activeTouches[pointerId] = touch
        if (theme.hapticFeedback) performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)

        if (key.spec.repeatable) {
            // Repeatable keys fire on press, not on release. Backspace has to delete the moment it
            // is touched, and firing again on release would delete one extra character after every
            // hold. Character keys keep firing on release, because the flick is not resolved until
            // the finger lifts.
            touch.firedOnPress = true
            emit(touch)
            scheduleRepeat(key.spec)
        }
        invalidate()
    }

    private fun onPointerMove(pointerId: Int, x: Float, y: Float) {
        val touch = activeTouches[pointerId] ?: return
        val direction = resolveDirection(touch, x, y)
        if (direction != touch.direction) {
            touch.direction = direction
            // Once the finger leaves the centre the key is no longer a hold-to-repeat candidate.
            cancelRepeat()
            invalidate()
        }
    }

    private fun onPointerUp(pointerId: Int) {
        val touch = activeTouches.remove(pointerId) ?: return
        cancelRepeat()
        commit(touch)
        invalidate()
    }

    private fun resolveDirection(touch: Touch, x: Float, y: Float): FlickDirection =
        FlickResolver.resolve(
            key = touch.key.spec,
            dx = x - touch.downX,
            dy = y - touch.downY,
            thresholdPx = flickThresholdPx,
        )

    private fun commit(touch: Touch) {
        if (touch.firedOnPress) return
        emit(touch)
    }

    private fun emit(touch: Touch) {
        val output = touch.key.spec.output(touch.direction) ?: return
        if (output.action is KeyAction.Shift) {
            // Tap cycles off → once → locked, the way every phone keyboard does it. Consumed here:
            // the service has nothing to do with it and never sees the key.
            shift = when (shift) {
                ShiftState.OFF -> ShiftState.ONCE
                ShiftState.ONCE -> ShiftState.LOCKED
                ShiftState.LOCKED -> ShiftState.OFF
            }
            invalidate()
            return
        }
        listener?.onKeyOutput(shiftApplied(output), touch.key.spec, touch.direction)
        if (shift == ShiftState.ONCE && output.action is KeyAction.Input) {
            shift = ShiftState.OFF
            invalidate()
        }
    }

    /**
     * Whether a finger is currently on [key].
     *
     * Scanned rather than collected into a set: onDraw runs on every touch move, and there are at
     * most a couple of fingers down, so a handful of reference comparisons beats allocating a list
     * and a set on each frame.
     */
    private fun isPressed(key: PlacedKey): Boolean {
        for (touch in activeTouches.values) {
            if (touch.key === key) return true
        }
        return false
    }

    /**
     * What to draw on a key face, which under shift is not what the layout says.
     *
     * The shift key itself reports the state rather than a fixed glyph — without that, a latched
     * shift is invisible and the next capital looks like a bug.
     */
    private fun faceLabel(spec: KeySpec): String {
        if (spec.center.action is KeyAction.Shift) {
            return when (shift) {
                ShiftState.OFF -> spec.faceLabel
                ShiftState.ONCE -> SHIFT_ONCE_LABEL
                ShiftState.LOCKED -> SHIFT_LOCKED_LABEL
            }
        }
        return if (shift == ShiftState.OFF) spec.faceLabel else spec.faceLabel.uppercase()
    }

    /** The output as typed under the current shift state. */
    private fun shiftApplied(output: KeyOutput): KeyOutput {
        if (shift == ShiftState.OFF) return output
        val action = output.action
        if (action !is KeyAction.Input) return output
        val upper = action.text.uppercase()
        if (upper == action.text) return output
        return output.copy(label = upper, action = KeyAction.Input(upper))
    }

    private fun scheduleRepeat(key: KeySpec) {
        val runnable = object : Runnable {
            override fun run() {
                listener?.onKeyOutput(key.center, key, FlickDirection.CENTER)
                if (theme.hapticFeedback) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
                handler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeatRunnable = runnable
        handler.postDelayed(runnable, REPEAT_DELAY_MS)
    }

    private fun cancelRepeat() {
        repeatRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable = null
    }

    override fun onDetachedFromWindow() {
        cancelRepeat()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val DEFAULT_FLICK_THRESHOLD_DP = 24f

        /** Filled arrow while shift is armed for one letter, underlined arrow while it is locked. */
        private const val SHIFT_ONCE_LABEL = "⬆"
        private const val SHIFT_LOCKED_LABEL = "⇪"
        /** How long the key must be held before it starts repeating. */
        private const val REPEAT_DELAY_MS = 400L

        /**
         * Gap between repeats. Explicit rather than ViewConfiguration.getKeyRepeatDelay(), which
         * despite the name is the initial delay and reads far too short to use as an interval.
         *
         * Slightly slower than a hardware key repeat on purpose: every repeat on a live composition
         * costs a full mozc prediction on this thread, and typing correction made those noticeably
         * more expensive.
         */
        private const val REPEAT_INTERVAL_MS = 70L
    }
}
