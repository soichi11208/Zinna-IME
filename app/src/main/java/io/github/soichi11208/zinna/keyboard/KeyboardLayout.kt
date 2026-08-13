package io.github.soichi11208.zinna.keyboard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Declarative keyboard description.
 *
 * Layouts are data, not code: they ship as JSON in assets/layouts/ and users can drop replacements
 * into the app's layouts directory. That is the hinge the whole customization story hangs on — key
 * arrangement, flick assignments and per-key actions are all editable without touching the app.
 */
@Serializable
data class KeyboardLayout(
    val id: String,
    val label: String,
    /** Which mozc romanji table this layout feeds. See [InputStyle]. */
    val inputStyle: InputStyle = InputStyle.FLICK_HIRAGANA,
    val rows: List<KeyRow>,
) {
    /** Largest column count across rows; used to normalise key widths. */
    val columnWeight: Float = rows.maxOfOrNull { it.totalWeight } ?: 1f
}

@Serializable
data class KeyRow(
    val keys: List<KeySpec>,
    /** Row height relative to the other rows. */
    val weight: Float = 1f,
    /**
     * Empty space at the ends, in the same units as [KeySpec.weight].
     *
     * A row is stretched to the full width on its own, so a nine-key home row would otherwise come
     * out wider than the ten-key row above it and the columns would not line up. On a qwerty
     * surface that misalignment is not just untidy — the keys stop being where the thumb has
     * learned they are.
     */
    val padStart: Float = 0f,
    val padEnd: Float = 0f,
) {
    /** Total horizontal weight including the padding, which is what the row is divided into. */
    val totalWeight: Float
        get() = padStart + padEnd + keys.sumOf { it.weight.toDouble() }.toFloat()
}

@Serializable
data class KeySpec(
    /** Horizontal share of the row. */
    val weight: Float = 1f,
    /** What the key does when tapped without flicking. */
    val center: KeyOutput,
    val left: KeyOutput? = null,
    val up: KeyOutput? = null,
    val right: KeyOutput? = null,
    val down: KeyOutput? = null,
    /** Overrides the label drawn on the key face; defaults to [center]'s label. */
    val label: String? = null,
    /** Holding the key repeats it (backspace, cursor movement). */
    val repeatable: Boolean = false,
    /** Visual role, so themes can style modifiers differently from character keys. */
    val style: KeyStyle = KeyStyle.CHARACTER,
) {
    fun output(direction: FlickDirection): KeyOutput? = when (direction) {
        FlickDirection.CENTER -> center
        FlickDirection.LEFT -> left
        FlickDirection.UP -> up
        FlickDirection.RIGHT -> right
        FlickDirection.DOWN -> down
    }

    /** True when this key has anything to show in a flick guide. */
    val hasFlicks: Boolean get() = left != null || up != null || right != null || down != null

    val faceLabel: String get() = label ?: center.label
}

@Serializable
data class KeyOutput(
    /** Text drawn in the flick guide. */
    val label: String,
    val action: KeyAction,
)

@Serializable
sealed interface KeyAction {
    /** Feed [text] to mozc as a key event. The usual case for character keys. */
    @Serializable
    @SerialName("input")
    data class Input(val text: String) : KeyAction

    /**
     * Insert [text] verbatim, bypassing the active romanji table.
     *
     * Unused by the bundled layouts — the symbol plane's characters are all keys in mozc's number
     * table, so they go through [Input] like anything else. It exists for hand-written layouts:
     * the tables reuse punctuation as *table keys* (in the latin table `<` selects 7 and `%`
     * selects 5), so anyone adding a symbol to a spare flick direction elsewhere needs a way in
     * that does not get reinterpreted as a digit.
     */
    @Serializable
    @SerialName("symbol")
    data class InsertSymbol(val text: String) : KeyAction

    /** Cycle the preceding character through dakuten / handakuten / small forms. */
    @Serializable
    @SerialName("modify")
    data object ModifyChar : KeyAction

    /** Undoes the last commit, restoring it to a composition that can be re-converted. */
    @Serializable
    @SerialName("undo")
    data object Undo : KeyAction

    @Serializable
    @SerialName("backspace")
    data object Backspace : KeyAction

    @Serializable
    @SerialName("enter")
    data object Enter : KeyAction

    @Serializable
    @SerialName("space")
    data object Space : KeyAction

    /** Convert the current composition, or insert a full-width space when idle. */
    @Serializable
    @SerialName("convert")
    data object Convert : KeyAction

    @Serializable
    @SerialName("cursor")
    data class MoveCursor(val delta: Int) : KeyAction

    /** Switch to another layout by id, e.g. from kana to the alphabet plane. */
    @Serializable
    @SerialName("layout")
    data class SwitchLayout(val layoutId: String) : KeyAction

    /**
     * Shift, for the qwerty planes. Tap for one capital, tap again to lock, again to release.
     *
     * Handled inside [FlickKeyboardView] rather than by the service, because the state is mostly a
     * drawing concern: every letter key has to re-label itself. The view applies the case to the
     * text on its way out, so nothing downstream needs to know shift exists.
     *
     * Deliberately absent from the kana qwerty layout. mozc's romaji table only composes lowercase
     * — feeding it "KA" yields a literal "KA" rather than か — so a shift key there would look like
     * it broke kana input.
     */
    @Serializable
    @SerialName("shift")
    data object Shift : KeyAction

    /** Hand control back to the system input method picker. */
    @Serializable
    @SerialName("ime_picker")
    data object ShowImePicker : KeyAction
}

enum class KeyStyle { CHARACTER, MODIFIER, ACTION }

enum class FlickDirection { CENTER, LEFT, UP, RIGHT, DOWN }

/**
 * Maps onto mozc's `Request.SpecialRomanjiTable`. The app resolves the flick direction itself and
 * sends the resulting kana, so FLICK_TO_HIRAGANA is the natural pairing — mozc then treats each
 * key event as a finished kana rather than re-running its own toggle state machine.
 */
enum class InputStyle(
    val mozcTableNumber: Int,
    val mozcCompositionMode: Int,
    /**
     * Which space to type when the space key lands on an empty composition.
     *
     * mozc refuses that keystroke — it comes back `consumed=false` on every plane — so the client
     * picks the character. This follows mozc's own `space_character_form` default, where a space
     * takes the width of the input mode: 全角 while writing Japanese, 半角 otherwise.
     *
     * Stated per plane rather than derived from [mozcCompositionMode], because the number plane
     * borrows HIRAGANA mode for its conversions while still wanting an ASCII space.
     */
    val fullWidthSpace: Boolean,
) {
    FLICK_HIRAGANA(13, HIRAGANA, fullWidthSpace = true),
    TOGGLE_FLICK_HIRAGANA(16, HIRAGANA, fullWidthSpace = true),
    FLICK_HALFWIDTH_ASCII(14, HALF_ASCII, fullWidthSpace = false),

    /**
     * The Gboard-equivalent latin plane: one key per letter group, tapping repeatedly walks
     * a→b→c and the '*' key walks case. Digits live on the down flick.
     */
    TOGGLE_FLICK_HALFWIDTH_ASCII(17, HALF_ASCII, fullWidthSpace = false),

    /**
     * The 記号・数字 plane behind Gboard's ☺記 key: digits with the symbol sets flicked off them
     * (1 → ☆♪→, 5 → +×÷, 7 → 「」:). Stays in HIRAGANA so number input still offers the useful
     * conversions — "51" → 5月1日, 5時1分.
     */
    TOGGLE_FLICK_NUMBER(42, HIRAGANA, fullWidthSpace = false),

    /** Plain numeric pad with no symbols. Available to custom layouts. */
    FLICK_NUMBER(43, HALF_ASCII, fullWidthSpace = false),

    QWERTY_HIRAGANA(20, HIRAGANA, fullWidthSpace = true),
    QWERTY_HALFWIDTH_ASCII(22, HALF_ASCII, fullWidthSpace = false),
    GODAN_HIRAGANA(30, HIRAGANA, fullWidthSpace = true),
}

// mozc.commands.CompositionMode. The romanji table decides what a keystroke transliterates to, but
// the composition mode decides whether mozc then tries to convert it — leaving a latin plane in
// HIRAGANA mode gets you kanji candidates for "abc".
private const val HIRAGANA = 1
private const val HALF_ASCII = 3
