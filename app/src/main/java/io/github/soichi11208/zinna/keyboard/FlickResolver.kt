package io.github.soichi11208.zinna.keyboard

import kotlin.math.abs
import kotlin.math.max

/**
 * Pure geometry for "which way did the finger go".
 *
 * Split out of [FlickKeyboardView] because this is the one piece of the touch path with real edge
 * cases — diagonal drags, unassigned directions, sub-threshold jitter — and testing it through a
 * View means instrumenting a device to assert on arithmetic.
 */
object FlickResolver {

    /**
     * @param key the key under the finger, used to reject directions it has nothing assigned to
     * @param dx horizontal travel from the touch-down point, in pixels
     * @param dy vertical travel, in pixels (screen coordinates: positive is down)
     * @param thresholdPx distance the finger must travel before a flick counts
     */
    fun resolve(key: KeySpec, dx: Float, dy: Float, thresholdPx: Float): FlickDirection {
        if (max(abs(dx), abs(dy)) < thresholdPx) return FlickDirection.CENTER

        // Ties go to the horizontal axis. Thumbs drift vertically more than horizontally, so
        // treating an exact diagonal as horizontal matches how people actually aim at い/え.
        val candidate = if (abs(dx) >= abs(dy)) {
            if (dx < 0) FlickDirection.LEFT else FlickDirection.RIGHT
        } else {
            if (dy < 0) FlickDirection.UP else FlickDirection.DOWN
        }

        // Falling back to CENTER keeps a sloppy flick usable instead of silently eating the
        // keystroke, which is what a strict reading of the gesture would do.
        return if (key.output(candidate) != null) candidate else FlickDirection.CENTER
    }
}
