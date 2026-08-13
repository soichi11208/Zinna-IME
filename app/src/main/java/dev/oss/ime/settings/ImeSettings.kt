package dev.oss.ime.settings

import android.content.Context
import android.content.SharedPreferences
import dev.oss.ime.keyboard.FlickGuideStyle
import dev.oss.ime.keyboard.KeyboardStyle
import java.io.File

/**
 * User-tunable appearance settings.
 *
 * Separate from [dev.oss.ime.theme.KeyboardTheme]: a theme is a shareable file describing a look,
 * while these are per-device choices layered on top of whichever theme is active. Pure black is
 * about this screen's panel, and a background photo belongs to this user — neither travels with a
 * theme someone hands you.
 *
 * Backed by SharedPreferences rather than DataStore because the IME reads them synchronously while
 * building its input view, on a path where suspending would mean rendering the keyboard twice.
 */
class ImeSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val backgroundDir = File(context.applicationContext.filesDir, BACKGROUND_DIR)

    /** Forces a true-black panel. On OLED those pixels are switched off entirely. */
    var pureBlack: Boolean
        get() = prefs.getBoolean(KEY_PURE_BLACK, false)
        set(value) = prefs.edit().putBoolean(KEY_PURE_BLACK, value).apply()

    /** How strongly the background image shows through, 0f–1f. */
    var backgroundOpacity: Float
        get() = prefs.getFloat(KEY_BACKGROUND_OPACITY, DEFAULT_BACKGROUND_OPACITY)
        set(value) = prefs.edit().putFloat(KEY_BACKGROUND_OPACITY, value.coerceIn(0f, 1f)).apply()

    /**
     * Multiplier on the theme's key height.
     *
     * A scale rather than an absolute height so it composes with whatever theme is active instead
     * of overriding a value the theme author chose. The range stops well short of filling the
     * screen: past that the candidate strip and the editor stop sharing the display usefully.
     */
    var keyHeightScale: Float
        get() = prefs.getFloat(KEY_HEIGHT_SCALE, 1f)
        set(value) = prefs.edit()
            .putFloat(KEY_HEIGHT_SCALE, value.coerceIn(MIN_KEY_HEIGHT_SCALE, MAX_KEY_HEIGHT_SCALE))
            .apply()

    /**
     * Flick, qwerty, or one of each. See [KeyboardStyle].
     *
     * Stored by name so adding a combination later does not invalidate what is on disk; an
     * unreadable value falls back to flick rather than leaving the user with no keyboard.
     */
    var keyboardStyle: KeyboardStyle
        get() = KeyboardStyle.of(prefs.getString(KEY_KEYBOARD_STYLE, null))
        set(value) {
            prefs.edit().putString(KEY_KEYBOARD_STYLE, value.name).apply()
            bumpRevision()
        }

    /**
     * What a flick key shows while it is held. See [FlickGuideStyle].
     *
     * Defaults to the preview: the cross of four directions covers the neighbouring keys, which is
     * where the eye is looking, and most of the time only one of the four is wanted.
     */
    var flickGuideStyle: FlickGuideStyle
        get() = FlickGuideStyle.of(prefs.getString(KEY_FLICK_GUIDE, null))
        set(value) {
            prefs.edit().putString(KEY_FLICK_GUIDE, value.name).apply()
            bumpRevision()
        }

    /**
     * Overrides [flickGuideStyle] with the full cross on the symbol plane.
     *
     * The preview answers "what will this type", which is enough on the kana plane because the four
     * directions of か are already known. The symbol plane is the opposite case: nobody remembers
     * that ※ hides on 7 or that ≦ is up from ±, and the preview only speaks once the finger is
     * already moving the right way. On by default for that reason — the plane it affects is small,
     * and it is the one place the cross earns the keys it covers.
     */
    var showAllDirectionsOnSymbolPlane: Boolean
        get() = prefs.getBoolean(KEY_SYMBOL_ALL_DIRECTIONS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_SYMBOL_ALL_DIRECTIONS, value).apply()
            bumpRevision()
        }

    /**
     * The keyboard background image, or null for a plain colour.
     *
     * A copy under our own files directory, not the content URI the user picked: the IME runs in a
     * process that never held the SAF grant, and the picked document can be deleted or unmounted
     * at any time. Copying makes the setting survive both.
     */
    val backgroundImage: File?
        get() = File(backgroundDir, BACKGROUND_FILE).takeIf { it.isFile }

    /** Replaces the background image with [bytes]. */
    fun setBackgroundImage(bytes: ByteArray) {
        backgroundDir.mkdirs()
        File(backgroundDir, BACKGROUND_FILE).writeBytes(bytes)
        bumpRevision()
    }

    fun clearBackgroundImage() {
        File(backgroundDir, BACKGROUND_FILE).delete()
        bumpRevision()
    }

    /**
     * Changes whenever anything here does.
     *
     * The IME compares this against the value it built its current view from, so it can rebuild
     * exactly when something moved instead of on every input session.
     */
    val revision: Int
        get() = prefs.getInt(KEY_REVISION, 0) +
            (if (pureBlack) 1 shl 16 else 0) +
            (backgroundOpacity * 100).toInt() * (1 shl 8) +
            (keyHeightScale * 100).toInt() * (1 shl 20)

    private fun bumpRevision() {
        prefs.edit().putInt(KEY_REVISION, prefs.getInt(KEY_REVISION, 0) + 1).apply()
    }

    companion object {
        /** Also the name [dev.oss.ime.mozc.ProfileBackup] stores these under. */
        const val NAME = "ime_settings"
        private const val KEY_PURE_BLACK = "pure_black"
        private const val KEY_BACKGROUND_OPACITY = "background_opacity"
        private const val KEY_REVISION = "revision"
        const val BACKGROUND_DIR = "backgrounds"
        private const val BACKGROUND_FILE = "keyboard_background"
        private const val KEY_HEIGHT_SCALE = "key_height_scale"
        private const val KEY_KEYBOARD_STYLE = "keyboard_style"
        private const val KEY_FLICK_GUIDE = "flick_guide_style"
        private const val KEY_SYMBOL_ALL_DIRECTIONS = "symbol_all_directions"
        const val DEFAULT_BACKGROUND_OPACITY = 0.45f
        const val MIN_KEY_HEIGHT_SCALE = 0.7f
        const val MAX_KEY_HEIGHT_SCALE = 1.5f
    }
}
