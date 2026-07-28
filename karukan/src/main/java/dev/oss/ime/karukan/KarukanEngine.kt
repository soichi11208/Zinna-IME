package dev.oss.ime.karukan

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Experimental neural kana-kanji conversion, from karukan.
 *
 * A small GPT-2 run through llama.cpp, offered as a second opinion alongside mozc rather than as a
 * replacement: mozc stays in charge of composition, learning and the candidate list, and this
 * contributes conversions of the same reading. If it is unavailable — no model bundled, no native
 * library for this ABI, load failed — [isAvailable] is false and nothing else changes.
 *
 * Everything here is slow by the standards of the rest of the keyboard. Loading maps a file of
 * tens of megabytes and conversion runs an actual language model, so both belong on a background
 * thread, and the feature is off unless the user turns it on.
 *
 * No network. karukan normally fetches its weights from HuggingFace on first use; this build has
 * no INTERNET permission, so the model is whatever was bundled at build time and the download path
 * is patched out of the engine entirely.
 */
class KarukanEngine private constructor(private val handle: Long) {

    /** Converts [reading] (hiragana), conditioned on the text already committed to its left. */
    fun convert(reading: String, context: String = "", count: Int = 3): List<String> {
        if (handle == 0L || reading.isEmpty()) return emptyList()
        return try {
            KarukanNative.nativeConvert(handle, reading, context, count).toList()
        } catch (e: Throwable) {
            Log.e(TAG, "conversion failed", e)
            emptyList()
        }
    }

    fun close() {
        if (handle != 0L) KarukanNative.nativeClose(handle)
    }

    companion object {
        private const val TAG = "KarukanEngine"
        private const val ASSET_TOKENIZER = "tokenizer.json"
        private const val ASSET_MODEL_NAME = "MODEL"

        /** True when this build could have a model at all. Cheap; no files are touched. */
        val isBundled: Boolean get() = BuildConfig.MODEL_BUNDLED

        /**
         * Loads the model. Returns null when the feature is not usable on this build or device.
         *
         * Blocks for as long as the model takes to map — call it from a background thread.
         */
        fun open(context: Context): KarukanEngine? {
            if (!isBundled) return null
            try {
                System.loadLibrary("karukan")
            } catch (e: UnsatisfiedLinkError) {
                // Expected on an ABI the engine was not built for; scripts/build_karukan.sh does
                // arm64 alone by default.
                Log.w(TAG, "libkarukan.so unavailable for this ABI", e)
                return null
            }

            val gguf = extract(context, modelFileName(context) ?: return null) ?: return null
            val tokenizer = extract(context, ASSET_TOKENIZER) ?: return null

            val handle = KarukanNative.nativeOpen(gguf.absolutePath, tokenizer.absolutePath)
            if (handle == 0L) {
                Log.e(TAG, "model failed to load: ${gguf.name}")
                return null
            }
            Log.i(TAG, "karukan ready: ${gguf.name}")
            return KarukanEngine(handle)
        }

        /** The bundled variant is recorded at fetch time rather than guessed from the asset list. */
        private fun modelFileName(context: Context): String? = runCatching {
            context.assets.open(ASSET_MODEL_NAME).bufferedReader().use { it.readText() }.trim()
        }.onFailure { Log.e(TAG, "no $ASSET_MODEL_NAME asset", it) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }

        /**
         * Copies an asset out to a real file, which llama.cpp needs in order to mmap it.
         *
         * Freshness is a stamp holding the package's `lastUpdateTime`, not a size comparison: an
         * asset is deflated inside the APK, so its stored length is not the extracted length and
         * `openFd` cannot open it at all. lastUpdateTime changes on install and upgrade, which is
         * exactly when the extracted copy goes stale. The same trap as mozc.data, same answer.
         */
        private fun extract(context: Context, name: String): File? {
            val target = File(context.filesDir, "karukan/$name")
            val stamp = File(context.filesDir, "karukan/$name.stamp")
            return try {
                val version = context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .lastUpdateTime
                    .toString()
                if (target.isFile && stamp.isFile && stamp.readText() == version) return target

                target.parentFile?.mkdirs()
                context.assets.open(name).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                stamp.writeText(version)
                target
            } catch (e: Exception) {
                Log.e(TAG, "failed to extract $name", e)
                null
            }
        }
    }
}
