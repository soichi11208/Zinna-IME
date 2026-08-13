package io.github.soichi11208.zinna.keyboard

import android.content.Context
import android.util.Log
import io.github.soichi11208.zinna.theme.KeyboardTheme
import io.github.soichi11208.zinna.theme.MaterialYouTheme
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Loads layouts and themes, preferring user-supplied files over the bundled defaults.
 *
 * Lookup order for both kinds of resource is user directory first, then assets. That single rule is
 * what makes the app customizable without a theme editor: dropping `layouts/flick_kana.json` into
 * the app's files directory overrides the shipped layout of the same id, and deleting it restores
 * the default. The in-app editor, when it lands, is just a writer for these same files.
 */
class LayoutRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val layoutsDir = File(context.filesDir, LAYOUTS_DIR)
    private val themesDir = File(context.filesDir, THEMES_DIR)

    fun loadLayout(id: String): KeyboardLayout? =
        readOverridable("$LAYOUTS_DIR/$id.json", layoutsDir, "$id.json")
            ?.let { decode(it, "layout $id") { text -> json.decodeFromString<KeyboardLayout>(text) } }

    fun loadTheme(id: String): KeyboardTheme? =
        readOverridable("$THEMES_DIR/$id.json", themesDir, "$id.json")
            ?.let { decode(it, "theme $id") { text -> json.decodeFromString<KeyboardTheme>(text) } }

    /** Ids of every layout available, user overrides and bundled defaults merged. */
    fun availableLayoutIds(): List<String> = availableIds(LAYOUTS_DIR, layoutsDir)

    /**
     * Bundled and user themes, plus the dynamic Material You theme. That one has no file behind it
     * — it is derived from the system palette at runtime — so it has to be named explicitly or the
     * settings screen would not list the theme that is actually in use.
     */
    fun availableThemeIds(): List<String> =
        (listOf(MaterialYouTheme.ID) + availableIds(THEMES_DIR, themesDir)).distinct()

    fun saveLayout(layout: KeyboardLayout) {
        layoutsDir.mkdirs()
        File(layoutsDir, "${layout.id}.json")
            .writeText(json.encodeToString(KeyboardLayout.serializer(), layout))
    }

    fun saveTheme(theme: KeyboardTheme) {
        themesDir.mkdirs()
        File(themesDir, "${theme.id}.json")
            .writeText(json.encodeToString(KeyboardTheme.serializer(), theme))
    }

    /** Removes a user override, falling back to the bundled version if one exists. */
    fun deleteOverride(directory: String, id: String): Boolean {
        val dir = if (directory == LAYOUTS_DIR) layoutsDir else themesDir
        return File(dir, "$id.json").delete()
    }

    private fun availableIds(assetDir: String, userDir: File): List<String> {
        val fromAssets = runCatching { context.assets.list(assetDir)?.toList() }.getOrNull().orEmpty()
        val fromUser = userDir.listFiles()?.map { it.name }.orEmpty()
        return (fromAssets + fromUser)
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .distinct()
            .sorted()
    }

    private fun readOverridable(assetPath: String, userDir: File, fileName: String): String? {
        val userFile = File(userDir, fileName)
        if (userFile.isFile) {
            runCatching { return userFile.readText() }
                .onFailure { Log.w(TAG, "unreadable override $userFile, falling back to asset", it) }
        }
        return runCatching {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    private fun <T> decode(text: String, what: String, block: (String) -> T): T? =
        runCatching { block(text) }
            .onFailure { Log.e(TAG, "failed to parse $what", it) }
            .getOrNull()

    companion object {
        private const val TAG = "LayoutRepository"
        const val LAYOUTS_DIR = "layouts"
        const val THEMES_DIR = "themes"
        const val DEFAULT_LAYOUT_ID = "flick_kana"
    }
}
