package io.github.soichi11208.zinna.mozc

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated chunks keep large TTT checkpoints below Android's Java heap limit. */
internal object SnapshotCipher {
    private val magic = "ZINNAS01".toByteArray()
    private const val CHUNK = 1024 * 1024

    private fun cipher(mode: Int, key: SecretKey, nonce: ByteArray, index: Long, size: Int): Cipher {
        val iv = nonce.copyOf()
        val counter = ByteBuffer.allocate(8).putLong(index).array()
        for (i in counter.indices) iv[iv.size - counter.size + i] = (iv[iv.size - counter.size + i].toInt() xor counter[i].toInt()).toByte()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, iv))
            updateAAD(magic + nonce + ByteBuffer.allocate(12).putLong(index).putInt(size).array())
        }
    }

    fun encrypt(output: OutputStream, key: SecretKey, writer: (OutputStream) -> Unit) {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val sink = DataOutputStream(output)
        sink.write(magic); sink.write(nonce)
        object : OutputStream() {
            val buffer = ByteArray(CHUNK)
            var used = 0
            var index = 0L
            var closed = false
            fun chunk(size: Int) {
                sink.writeInt(size)
                sink.write(cipher(Cipher.ENCRYPT_MODE, key, nonce, index++, size).doFinal(buffer, 0, size))
            }
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                check(!closed)
                var at = offset
                var remaining = length
                while (remaining > 0) {
                    val n = minOf(remaining, CHUNK - used)
                    bytes.copyInto(buffer, used, at, at + n)
                    used += n; at += n; remaining -= n
                    if (used == CHUNK) { chunk(used); used = 0 }
                }
            }
            override fun close() {
                if (closed) return
                if (used > 0) chunk(used)
                chunk(0) // Authenticated end marker; truncated files never count as restored.
                sink.flush()
                closed = true
            }
        }.use(writer)
    }

    fun decrypt(input: InputStream, key: SecretKey, reader: (InputStream) -> Unit) {
        val source = DataInputStream(input)
        val header = ByteArray(magic.size).also { source.readFully(it) }
        check(header.contentEquals(magic))
        val nonce = ByteArray(12).also { source.readFully(it) }
        val decrypted = object : InputStream() {
            var index = 0L
            var current = ByteArrayInputStream(byteArrayOf())
            var ended = false
            fun next(): Boolean {
                if (ended) return false
                val size = source.readInt()
                check(size in 0..CHUNK)
                val encrypted = ByteArray(size + 16).also { source.readFully(it) }
                val plain = cipher(Cipher.DECRYPT_MODE, key, nonce, index++, size).doFinal(encrypted)
                if (size == 0) { check(source.read() == -1); ended = true; return false }
                current = ByteArrayInputStream(plain)
                return true
            }
            override fun read(): Int {
                var value = current.read()
                while (value < 0 && next()) value = current.read()
                return value
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                var count = current.read(bytes, offset, length)
                while (count < 0 && next()) count = current.read(bytes, offset, length)
                return count
            }
        }
        reader(decrypted)
        check(decrypted.read() == -1)
    }
}
