package dev.oss.ime.keyboard

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every bundled layout must deserialize and be internally consistent. */
class LayoutParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun layouts(): List<Pair<String, KeyboardLayout>> =
        File("src/main/assets/layouts").listFiles { f -> f.name.endsWith(".json") }!!
            .sortedBy { it.name }
            .map { it.name to json.decodeFromString(KeyboardLayout.serializer(), it.readText()) }

    @Test
    fun everyBundledLayoutParses() {
        val all = layouts()
        assertTrue("no layouts found", all.size >= 7)
        for ((name, layout) in all) {
            assertEquals("id must match filename", name.removeSuffix(".json"), layout.id)
            assertTrue("$name has no rows", layout.rows.isNotEmpty())
        }
    }

    @Test
    fun qwertyRowsAreTheSameWidth() {
        for ((name, layout) in layouts().filter { it.first.startsWith("qwerty") }) {
            val widths = layout.rows.map { it.totalWeight }
            for (w in widths) {
                assertEquals("$name rows must align: $widths", widths[0], w, 0.001f)
            }
        }
    }

    @Test
    fun everyLayoutSwitchTargetExists() {
        val all = layouts()
        val ids = all.map { it.second.id }.toSet()
        for ((name, layout) in all) {
            for (row in layout.rows) {
                for (key in row.keys) {
                    for (dir in FlickDirection.entries) {
                        val action = key.output(dir)?.action
                        if (action is KeyAction.SwitchLayout) {
                            assertTrue(
                                "$name points at missing layout '${action.layoutId}'",
                                action.layoutId in ids,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Reaching page two has to be a round trip — it is the only way back, since the pages replace
     * each other rather than stacking.
     *
     * Characters are allowed on both pages: page one already carries →, and the arrow key needs it
     * more than page one does.
     */
    @Test
    fun theTwoFlickSymbolPagesLinkBothWays() {
        fun page(id: String) = layouts().first { it.second.id == id }.second

        fun switchTargets(layout: KeyboardLayout): Set<String> = buildSet {
            for (row in layout.rows) {
                for (key in row.keys) {
                    (key.center.action as? KeyAction.SwitchLayout)?.let { add(it.layoutId) }
                }
            }
        }

        val first = page("flick_symbol")
        val second = page("flick_symbol2")

        assertTrue("flick_symbol cannot reach page two", "flick_symbol2" in switchTargets(first))
        assertTrue("flick_symbol2 is a dead end", "flick_symbol" in switchTargets(second))

        // Page one's characters are all keys in mozc's number table, so Input works there. Page
        // two's are not, and that table reuses punctuation to select digits, so anything sent as
        // Input would come back as a number. See KeyAction.InsertSymbol.
        for (row in second.rows) {
            for (key in row.keys) {
                for (dir in FlickDirection.entries) {
                    val action = key.output(dir)?.action
                    assertTrue(
                        "page two must insert verbatim, not through the number table: $action",
                        action !is KeyAction.Input,
                    )
                }
            }
        }
    }

    /** Shift only belongs where mozc's table actually composes upper case. */
    @Test
    fun shiftOnlyOnLatinPlanes() {
        for ((name, layout) in layouts()) {
            val hasShift = layout.rows.any { row ->
                row.keys.any { it.center.action is KeyAction.Shift }
            }
            if (hasShift) {
                assertEquals(
                    "$name has shift but is not a latin plane",
                    3,
                    layout.inputStyle.mozcCompositionMode,
                )
            }
        }
    }
}
