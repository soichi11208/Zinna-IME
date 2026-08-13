package io.github.soichi11208.zinna.keyboard

/**
 * Which family of layouts each half of the keyboard uses.
 *
 * The bundled layouts come in two families, flick and qwerty, and each one's plane-switch keys
 * point inside its own family. That is fine until someone wants kana on a flick pad and the
 * alphabet on qwerty, which is a common preference: flick is faster for kana, qwerty is what the
 * fingers already know for latin.
 *
 * Rather than shipping a third set of layouts with different switch targets, the service resolves
 * every switch through [resolve]. The layout files stay family-local and the mixing lives in one
 * place.
 */
enum class KeyboardStyle(private val kana: String, private val ascii: String) {
    FLICK(kana = FLICK_KANA, ascii = FLICK_ASCII),
    QWERTY(kana = QWERTY_KANA, ascii = QWERTY_ASCII),
    MIXED(kana = FLICK_KANA, ascii = QWERTY_ASCII);

    /** The plane the keyboard opens on. */
    val defaultLayoutId: String get() = kana

    /**
     * The layout to actually show when a key asks for [requested].
     *
     * Only the kana and alphabet planes are redirected. Symbol pages are left alone on purpose:
     * they are reached from within a family — ?123 from qwerty, 数字 from the flick pad — and
     * sending the qwerty symbol key to the flick number pad would be a plane switch nobody asked
     * for. An unrecognised id (a user's own layout) also passes straight through.
     */
    fun resolve(requested: String): String = when (requested) {
        FLICK_KANA, QWERTY_KANA -> kana
        FLICK_ASCII, QWERTY_ASCII -> ascii
        else -> requested
    }

    companion object {
        fun of(name: String?): KeyboardStyle =
            entries.firstOrNull { it.name == name } ?: FLICK
    }
}

private const val FLICK_KANA = "flick_kana"
private const val FLICK_ASCII = "flick_ascii"
private const val QWERTY_KANA = "qwerty_kana"
private const val QWERTY_ASCII = "qwerty_ascii"
