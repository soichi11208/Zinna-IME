package io.github.soichi11208.zinna.ime

import android.content.Context
import android.os.ParcelFileDescriptor
import io.github.soichi11208.zinna.mozc.SecureStore
import io.github.soichi11208.zinna.mozc.UserDictionary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Android adapter for Hazkey's separate native trainer + immutable version publication. */
internal class ZenzaiTrainer(
    private val context: Context,
    private val model: () -> File,
    private val publish: (Long, Long) -> Unit,
    private val error: () -> Unit,
) {
    private val worker = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    @Volatile private var enabled = false
    @Volatile private var epoch = 0L
    private var handle = 0L
    private var updates = 0L
    private var snapshot: File? = null
    private var metadata: File? = null
    private val words = mutableSetOf<String>()
    private val examples = linkedMapOf<String, String>()
    private var scheduled: ScheduledFuture<*>? = null

    fun setEnabled(value: Boolean, activation: Long) {
        enabled = value
        epoch = activation
        if (!value) worker.execute {
            scheduled?.cancel(false)
            examples.clear()
            release()
        }
    }

    private fun ready(): Long {
        if (handle != 0L) return handle
        val base = model()
        handle = ZenzaiNative.load(base.absolutePath)
        snapshot = File(base.parentFile, "${base.nameWithoutExtension}.ttt.enc")
        metadata = File(base.parentFile, "${base.nameWithoutExtension}.words.enc")
        var restored = false
        if (snapshot!!.isFile) {
            ParcelFileDescriptor.adoptFd(ZenzaiNative.memoryFile()).use { memory ->
                val authenticated = SecureStore.readStream(snapshot!!) { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(memory.fileDescriptor)).use {
                        input.copyTo(it)
                    }
                }
                if (authenticated) {
                    updates = ZenzaiNative.restore(handle, memory.fd)
                    restored = true
                }
            }
        }
        words.clear()
        if (restored) {
            runCatching {
                val saved = JSONArray(SecureStore.read(metadata!!).orEmpty())
                for (i in 0 until saved.length()) words += saved.getString(i)
            }
            publish(ZenzaiNative.exportVersion(handle), epoch)
        }
        status()
        return handle
    }

    fun confirm(prompt: String, output: String) {
        if (!enabled || ZenzaiConversion.candidate(output) == null) return
        val activation = epoch
        worker.execute {
            if (!enabled || activation != epoch) return@execute
            // Same-reading corrections in one burst keep the user's last confirmed spelling.
            examples[prompt] = output
            while (examples.size > 32) examples.remove(examples.keys.first())
            scheduled?.cancel(false)
            scheduled = worker.schedule({
                if (!enabled || activation != epoch) return@schedule
                try {
                    val trainer = ready()
                    val batch = examples.toMap()
                    examples.clear()
                    for ((prefix, text) in batch) {
                        if (!enabled || activation != epoch) break
                        ZenzaiNative.train(trainer, (prefix + ZenzaiConversion.clean(text)).toByteArray(Charsets.UTF_8))
                        updates++
                    }
                    saveAndPublish(activation)
                } catch (_: Exception) { error() }
            }, 2, TimeUnit.SECONDS)
        }
    }

    fun dictionary(onEntries: (List<UserDictionary.Entry>) -> Unit) {
        if (!enabled) return
        val activation = epoch
        worker.execute {
            if (!enabled || activation != epoch) return@execute
            val entries = UserDictionary(context).entries()
            onEntries(entries)
            try {
                if (entries.isEmpty() && handle == 0L && !model().let {
                    File(it.parentFile, "${it.nameWithoutExtension}.ttt.enc").isFile
                }) return@execute
                val trainer = ready()
                var changed = false
                for (entry in entries) {
                    if (!enabled || activation != epoch) break
                    val prefix = ZenzaiConversion.prompt(entry.reading, "") ?: continue
                    if (ZenzaiConversion.candidate(entry.word) == null || entry.word.length > 128) continue
                    val identity = MessageDigest.getInstance("SHA-256")
                        .digest((prefix + "\u0000" + entry.word).toByteArray())
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (identity in words) continue
                    val result = ZenzaiNative.learnWord(trainer, prefix.toByteArray(Charsets.UTF_8),
                        entry.word.toByteArray(Charsets.UTF_8))
                    if (result > 0) { words += identity; changed = true }
                }
                if (changed) saveAndPublish(activation)
            } catch (_: Exception) { error() }
        }
    }

    private fun saveAndPublish(activation: Long) {
        // Publish first; encryption and snapshot I/O must not hold up conversion.
        publish(ZenzaiNative.exportVersion(handle), activation)
        ParcelFileDescriptor.adoptFd(ZenzaiNative.memoryFile()).use { memory ->
            ZenzaiNative.snapshot(handle, memory.fd, updates)
            check(SecureStore.writeStream(snapshot!!) { output ->
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(memory.fileDescriptor)).use {
                    it.copyTo(output)
                }
            })
        }
        val temporary = File(metadata!!.parentFile, metadata!!.name + ".new")
        check(SecureStore.write(temporary, JSONArray(words.toList()).toString()))
        check(temporary.renameTo(metadata!!))
        status()
    }

    private fun status() {
        context.getSharedPreferences("ime_settings", Context.MODE_PRIVATE).edit()
            .putLong("zenzai_ttt_updates", updates)
            .putInt("zenzai_dictionary_trained", words.size).apply()
    }

    private fun release() {
        if (handle != 0L) ZenzaiNative.free(handle)
        handle = 0L
        updates = 0
        words.clear()
    }

    fun close() {
        enabled = false
        epoch++
        worker.execute { scheduled?.cancel(false); examples.clear(); release() }
        worker.shutdown()
    }
}
