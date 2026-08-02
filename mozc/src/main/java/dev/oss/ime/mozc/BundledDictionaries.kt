package dev.oss.ime.mozc

import android.content.Context
import android.util.Log
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoUserDictionaryStorage.UserDictionaryImportData
import java.io.File

/**
 * Supplementary dictionaries shipped inside the APK and loaded into mozc's user dictionary.
 *
 * They go in as *user* dictionaries rather than being compiled into mozc.data. That keeps
 * third_party/mozc a pristine upstream checkout — building them into the system dictionary would
 * mean patching its bazel dictionary sources — and it lets a dictionary be refreshed by dropping in
 * a new asset instead of rebuilding 18 MB of native data.
 *
 * mozc does the heavy lifting: `IMPORT_USER_DICTIONARY` parses the TSV on its own thread, replaces
 * any dictionary of the same name wholesale, reloads it so entries take effect immediately, and
 * saves it into the user profile directory. So this only has to run once per install.
 */
object BundledDictionaries {

    private const val TAG = "BundledDictionaries"
    private const val ASSET_DIR = "dictionaries"
    private const val STAMP_FILE = "bundled_dictionaries.stamp"

    /** Dictionary name as it appears in mozc's user dictionary list, keyed by asset file name. */
    private val DICTIONARIES = mapOf(
        "dic-nico-intersection-pixiv.txt" to "ニコニコ大百科×ピクシブ百科事典",
        "katakana-english.txt" to "カタカナ語→英語",
    "jawiki.txt" to "Wikipedia 見出し",
    )

    data class Status(val name: String, val entryCount: Int)

    /**
     * Imports anything not yet imported for this install.
     *
     * Blocking and slow — several MB of TSV crosses into native code — so callers must be off the
     * main thread. Returns what is now installed, or null if nothing needed doing.
     */
    fun importIfNeeded(context: Context, engine: MozcEngine): List<Status>? {
        val stamp = File(context.filesDir, STAMP_FILE)
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrElse { return null }

        if (stamp.isFile && stamp.readText().startsWith("$version\n")) return null

        val imported = mutableListOf<Status>()
        for ((assetName, dictionaryName) in DICTIONARIES) {
            val tsv = runCatching {
                context.assets.open("$ASSET_DIR/$assetName").bufferedReader().use { it.readText() }
            }.onFailure { Log.e(TAG, "cannot read $assetName", it) }.getOrNull() ?: continue

            val output = engine.eval(
                Input.newBuilder()
                    .setType(Input.CommandType.IMPORT_USER_DICTIONARY)
                    .setUserDictionaryImportData(
                        UserDictionaryImportData.newBuilder()
                            .setDictionaryName(dictionaryName)
                            .setData(tsv)
                    )
            )
            if (output == null) {
                Log.e(TAG, "IMPORT_USER_DICTIONARY failed for $dictionaryName")
                continue
            }
            // Comment lines carry the source's metadata header, not entries.
            val entries = tsv.lineSequence().count { it.isNotBlank() && !it.startsWith("#") }
            Log.i(TAG, "imported $dictionaryName ($entries entries)")
            imported += Status(dictionaryName, entries)
        }

        if (imported.isEmpty()) return null
        stamp.writeText(buildString {
            append(version).append('\n')
            imported.forEach { append(it.name).append('\t').append(it.entryCount).append('\n') }
        })
        return imported
    }

    /** What the last successful import installed, for display in settings. */
    fun installed(context: Context): List<Status> {
        val stamp = File(context.filesDir, STAMP_FILE)
        if (!stamp.isFile) return emptyList()
        return runCatching {
            stamp.readLines().drop(1).mapNotNull { line ->
                val (name, count) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
                Status(name, count.toIntOrNull() ?: return@mapNotNull null)
            }
        }.getOrDefault(emptyList())
    }
}
