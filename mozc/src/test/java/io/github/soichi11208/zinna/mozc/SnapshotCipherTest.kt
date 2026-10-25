package io.github.soichi11208.zinna.mozc

import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class SnapshotCipherTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private fun encrypt(plain: ByteArray) = ByteArrayOutputStream().also { out ->
        SnapshotCipher.encrypt(out, key) { it.write(plain) }
    }.toByteArray()
    private fun decrypt(encoded: ByteArray) = ByteArrayOutputStream().also { out ->
        SnapshotCipher.decrypt(encoded.inputStream(), key) { it.copyTo(out) }
    }.toByteArray()
    @Test fun multiChunkAndEmptySnapshotsRoundTrip() {
        for (size in listOf(0, 1, 1024 * 1024, 2 * 1024 * 1024 + 97)) {
            val data = ByteArray(size) { (it % 251).toByte() }
            assertArrayEquals(data, decrypt(encrypt(data)))
        }
    }
    @Test fun truncationTamperingAndMissingEndMarkerAreRejected() {
        val encoded = encrypt(ByteArray(100) { 42 })
        for (invalid in listOf(encoded.dropLast(1).toByteArray(), encoded.dropLast(20).toByteArray(),
            encoded.copyOf().apply { this[35] = (this[35].toInt() xor 1).toByte() },
            encoded + byteArrayOf(0))) {
            assertTrue(runCatching { decrypt(invalid) }.isFailure)
        }
    }
    @Test fun differentFilesHaveDifferentNoncesAndWrongKeysFail() {
        assertFalse(encrypt(byteArrayOf(1)).contentEquals(encrypt(byteArrayOf(1))))
        assertTrue(runCatching {
            SnapshotCipher.decrypt(encrypt(byteArrayOf(1)).inputStream(),
                SecretKeySpec(ByteArray(32) { 99 }, "AES")) { it.readBytes() }
        }.isFailure)
    }
}
