package io.github.soichi11208.zinna.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 theme for the settings screen.
 *
 * Follows the same rules as the keyboard itself (see [MaterialYouTheme]): the wallpaper palette on
 * Android 12 and later, a static scheme below that, and light or dark from the system rather than
 * from a setting of our own. A keyboard that recolours with the wallpaper next to a settings screen
 * frozen in Compose's default purple looks like two different apps.
 *
 * @param dark defaults to the system setting; passed explicitly only by previews.
 */
@Composable
fun ZinnaTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> darkColorScheme(
            primary = ZinnaPink,
            secondary = ZinnaGold,
            tertiary = ZinnaGreen,
        )

        else -> lightColorScheme(
            primary = ZinnaDeepPink,
            secondary = ZinnaBrown,
            tertiary = ZinnaGreen,
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

// The launcher icon's palette, so the pre-Android-12 fallback still looks like this app rather than
// like an unstyled Compose sample. Kept in sync with scripts/gen_launcher_icon.py by hand — there
// are five values and they change roughly never.
private val ZinnaPink = Color(0xFFFA7C93)
private val ZinnaDeepPink = Color(0xFFC62E63)
private val ZinnaGold = Color(0xFFFFC64A)
private val ZinnaBrown = Color(0xFF7E4126)
private val ZinnaGreen = Color(0xFF37795A)
