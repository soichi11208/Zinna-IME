package io.github.soichi11208.zinna.theme

import android.graphics.Color
import kotlinx.serialization.Serializable

/**
 * Everything a user can restyle about the keyboard surface.
 *
 * Themes are serialized to JSON and stored alongside layouts, so a theme is shareable as a single
 * file. Colors are held as ARGB ints and parsed from `#RRGGBB` / `#AARRGGBB` strings on the way in,
 * which keeps hand-written theme files readable.
 */
@Serializable
data class KeyboardTheme(
    val id: String,
    val label: String,
    @Serializable(with = ColorAsStringSerializer::class) val backgroundColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val keyColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val keyPressedColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val modifierKeyColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val labelColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val flickGuideColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val flickGuideLabelColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val flickGuideSelectedLabelColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val candidateBackgroundColor: Int,
    @Serializable(with = ColorAsStringSerializer::class) val candidateTextColor: Int,
    val keyHeightDp: Float = 56f,
    val keyGapDp: Float = 2f,
    val keyCornerRadiusDp: Float = 8f,
    val labelSizeSp: Float = 17f,
    val hapticFeedback: Boolean = true,
    /**
     * Draw keys as bare labels with no filled shape behind them.
     *
     * The label already says where the key is; an outline around each one is decoration that costs
     * contrast and gets in the way of a background image. Set false for the older filled look,
     * which then uses [keyColor] and [modifierKeyColor].
     */
    val flatKeys: Boolean = true,
) {
    /**
     * True-black variant for OLED panels, where a black pixel is an unlit pixel.
     *
     * Only the large flat areas go to black — key press feedback and labels keep the source
     * theme's colours, since blacking those out would just make the keyboard unreadable.
     */
    fun asPureBlack(): KeyboardTheme = copy(
        backgroundColor = Color.BLACK,
        candidateBackgroundColor = Color.BLACK,
        keyColor = Color.BLACK,
        modifierKeyColor = Color.BLACK,
    )

    companion object {
        val Default = KeyboardTheme(
            id = "default-dark",
            label = "Default Dark",
            backgroundColor = Color.parseColor("#1B1C1E"),
            keyColor = Color.parseColor("#2E3033"),
            keyPressedColor = Color.parseColor("#4A4D52"),
            modifierKeyColor = Color.parseColor("#232528"),
            labelColor = Color.parseColor("#ECEDEE"),
            flickGuideColor = Color.parseColor("#3D6BD6"),
            flickGuideLabelColor = Color.parseColor("#D6DEF0"),
            flickGuideSelectedLabelColor = Color.parseColor("#FFFFFF"),
            candidateBackgroundColor = Color.parseColor("#1B1C1E"),
            candidateTextColor = Color.parseColor("#ECEDEE"),
        )

        val Light = Default.copy(
            id = "default-light",
            label = "Default Light",
            backgroundColor = Color.parseColor("#E7E9EC"),
            keyColor = Color.parseColor("#FDFDFD"),
            keyPressedColor = Color.parseColor("#C9CDD3"),
            modifierKeyColor = Color.parseColor("#D3D7DD"),
            labelColor = Color.parseColor("#1B1C1E"),
            flickGuideColor = Color.parseColor("#4C7DF0"),
            flickGuideLabelColor = Color.parseColor("#EDF1FB"),
            flickGuideSelectedLabelColor = Color.parseColor("#FFFFFF"),
            candidateBackgroundColor = Color.parseColor("#E7E9EC"),
            candidateTextColor = Color.parseColor("#1B1C1E"),
        )
    }
}
