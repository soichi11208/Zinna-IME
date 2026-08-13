package io.github.soichi11208.zinna.theme

import android.graphics.Color
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Reads/writes ARGB ints as `#RRGGBB` or `#AARRGGBB`, so theme JSON stays hand-editable.
 * Round-trips through the 8-digit form to avoid silently dropping a non-opaque alpha.
 */
object ColorAsStringSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.github.soichi11208.zinna.theme.Color", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeString(String.format("#%08X", value))
    }

    override fun deserialize(decoder: Decoder): Int = Color.parseColor(decoder.decodeString())
}
