package io.github.soichi11208.zinna.keyboard

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Lays children out left to right, wrapping onto a new line when the row is full.
 *
 * Android has no flow container, and the expanded candidate list needs one: the chips are as wide
 * as the words on them, so neither a fixed column count nor a fixed width would do. Written here
 * rather than pulled in as a dependency because it is thirty lines and the alternative is a library
 * for one screen.
 */
class FlowLayout(context: Context) : ViewGroup(context) {

    /** Vertical gap between rows, in pixels. Horizontal spacing comes from the chips' padding. */
    var rowSpacing = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val childSpec = MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST)
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        var x = 0
        var rowHeight = 0
        var height = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(childSpec, unbounded)
            if (x > 0 && x + child.measuredWidth > available) {
                height += rowHeight + rowSpacing
                x = 0
                rowHeight = 0
            }
            x += child.measuredWidth
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        height += rowHeight

        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = width - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (x > paddingLeft && x + child.measuredWidth > paddingLeft + available) {
                y += rowHeight + rowSpacing
                x = paddingLeft
                rowHeight = 0
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
}
