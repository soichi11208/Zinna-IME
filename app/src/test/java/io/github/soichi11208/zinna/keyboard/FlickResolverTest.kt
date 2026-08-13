package io.github.soichi11208.zinna.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class FlickResolverTest {

    private fun output(label: String) = KeyOutput(label, KeyAction.Input(label))

    /** The あ key: every direction assigned. */
    private val fullKey = KeySpec(
        center = output("1"),
        left = output("_"),
        up = output(";"),
        right = output(":"),
        down = output("@"),
    )

    /** A cursor key with only horizontal outputs, like the ◀▶ key in the default layout. */
    private val horizontalOnlyKey = KeySpec(
        center = output("<"),
        left = output("<"),
        right = output(">"),
    )

    private val threshold = 24f

    @Test
    fun `movement below threshold stays on center`() {
        assertEquals(FlickDirection.CENTER, FlickResolver.resolve(fullKey, 20f, 0f, threshold))
        assertEquals(FlickDirection.CENTER, FlickResolver.resolve(fullKey, 0f, -23.9f, threshold))
        assertEquals(FlickDirection.CENTER, FlickResolver.resolve(fullKey, 17f, 17f, threshold))
    }

    @Test
    fun `each axis resolves to its direction`() {
        assertEquals(FlickDirection.LEFT, FlickResolver.resolve(fullKey, -40f, 0f, threshold))
        assertEquals(FlickDirection.RIGHT, FlickResolver.resolve(fullKey, 40f, 0f, threshold))
        assertEquals(FlickDirection.UP, FlickResolver.resolve(fullKey, 0f, -40f, threshold))
        assertEquals(FlickDirection.DOWN, FlickResolver.resolve(fullKey, 0f, 40f, threshold))
    }

    @Test
    fun `dominant axis wins on a diagonal`() {
        assertEquals(FlickDirection.LEFT, FlickResolver.resolve(fullKey, -50f, 30f, threshold))
        assertEquals(FlickDirection.DOWN, FlickResolver.resolve(fullKey, 30f, 50f, threshold))
    }

    @Test
    fun `exact diagonal is treated as horizontal`() {
        assertEquals(FlickDirection.RIGHT, FlickResolver.resolve(fullKey, 40f, 40f, threshold))
        assertEquals(FlickDirection.LEFT, FlickResolver.resolve(fullKey, -40f, -40f, threshold))
    }

    @Test
    fun `unassigned direction falls back to center rather than dropping the key`() {
        assertEquals(FlickDirection.UP, FlickResolver.resolve(fullKey, 0f, -40f, threshold))
        assertEquals(
            FlickDirection.CENTER,
            FlickResolver.resolve(horizontalOnlyKey, 0f, -40f, threshold),
        )
        assertEquals(
            FlickDirection.CENTER,
            FlickResolver.resolve(horizontalOnlyKey, 0f, 40f, threshold),
        )
        assertEquals(
            FlickDirection.RIGHT,
            FlickResolver.resolve(horizontalOnlyKey, 40f, 0f, threshold),
        )
    }

    @Test
    fun `threshold is measured per axis, not by euclidean distance`() {
        // 17,17 is 24 px away as the crow flies but under threshold on both axes, which is the
        // behaviour we want: a small circular wobble should never register as a flick.
        assertEquals(FlickDirection.CENTER, FlickResolver.resolve(fullKey, 17f, 17f, threshold))
        assertEquals(FlickDirection.RIGHT, FlickResolver.resolve(fullKey, 24f, 17f, threshold))
    }
}
