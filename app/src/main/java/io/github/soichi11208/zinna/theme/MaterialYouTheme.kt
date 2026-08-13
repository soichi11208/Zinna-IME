package io.github.soichi11208.zinna.theme

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Builds a [KeyboardTheme] from the system's Material You palette.
 *
 * Android 12 exposes the wallpaper-derived tonal palettes as plain colour resources
 * (`android.R.color.system_*`), so no library is needed — just read the tones and assign them to
 * roles. Below API 31 those resources do not exist and there is nothing to derive from, so the
 * bundled static themes stand in.
 *
 * Role assignment follows Gboard's Material You look: character keys sit near the surface, the
 * function columns take an accent tint so the two columns read as a frame, and the flick guide
 * uses a saturated accent so it stays legible over either.
 */
object MaterialYouTheme {

    const val ID = "material_you"

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * @param forceDark build the dark palette regardless of the system setting. Pure black mode
     *   needs it: a light palette over a black panel is unreadable.
     * @return a dynamic theme, or the matching static default when the platform has no palette.
     */
    fun create(context: Context, forceDark: Boolean = false): KeyboardTheme {
        val dark = forceDark || context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        if (!isSupported) return if (dark) KeyboardTheme.Default else KeyboardTheme.Light

        fun color(resId: Int) = ContextCompat.getColor(context, resId)

        return if (dark) {
            KeyboardTheme(
                id = ID,
                label = "Material You",
                backgroundColor = color(android.R.color.system_neutral1_900),
                keyColor = color(android.R.color.system_neutral2_800),
                keyPressedColor = color(android.R.color.system_accent2_700),
                modifierKeyColor = color(android.R.color.system_neutral1_800),
                labelColor = color(android.R.color.system_neutral1_50),
                flickGuideColor = color(android.R.color.system_accent1_600),
                flickGuideLabelColor = color(android.R.color.system_accent1_100),
                flickGuideSelectedLabelColor = color(android.R.color.system_neutral1_50),
                candidateBackgroundColor = color(android.R.color.system_neutral1_900),
                candidateTextColor = color(android.R.color.system_neutral1_50),
            )
        } else {
            KeyboardTheme(
                id = ID,
                label = "Material You",
                backgroundColor = color(android.R.color.system_accent2_100),
                keyColor = color(android.R.color.system_neutral1_50),
                keyPressedColor = color(android.R.color.system_accent2_200),
                modifierKeyColor = color(android.R.color.system_accent2_200),
                labelColor = color(android.R.color.system_neutral1_900),
                flickGuideColor = color(android.R.color.system_accent1_600),
                flickGuideLabelColor = color(android.R.color.system_accent1_100),
                flickGuideSelectedLabelColor = color(android.R.color.system_neutral1_50),
                candidateBackgroundColor = color(android.R.color.system_accent2_100),
                candidateTextColor = color(android.R.color.system_neutral1_900),
            )
        }
    }
}
