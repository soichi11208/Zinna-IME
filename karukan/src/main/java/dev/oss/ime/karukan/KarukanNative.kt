package dev.oss.ime.karukan

/**
 * Raw bindings to libkarukan.so. Nothing outside this package should touch these.
 *
 * The signatures must match `native/karukan-jni/src/lib.rs`; the symbol names are derived from this
 * class's package and name, so moving it renames them.
 */
internal object KarukanNative {

    /** @return an opaque handle, or 0 when the model could not be loaded. */
    external fun nativeOpen(ggufPath: String, tokenizerPath: String): Long

    /** @return candidates best first, or an empty array on any failure. */
    external fun nativeConvert(
        handle: Long,
        reading: String,
        context: String,
        count: Int,
    ): Array<String>

    external fun nativeClose(handle: Long)
}
