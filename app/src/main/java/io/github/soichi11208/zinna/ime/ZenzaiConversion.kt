package io.github.soichi11208.zinna.ime

import java.text.Normalizer

/** The v2 format used by the supplied fine-tuned model, independent of Android and JNI. */
internal object ZenzaiConversion {
    const val CANDIDATE_ID = -2
    fun usesMozc(zenzaiEnabled: Boolean, mozcAlongside: Boolean): Boolean = !zenzaiEnabled || mozcAlongside

    fun canConvert(enabled: Boolean, composing: Boolean, japanese: Boolean, password: Boolean): Boolean =
        enabled && composing && japanese && !password

    fun prompt(reading: String, preceding: String): String? {
        val kana = Normalizer.normalize(clean(reading), Normalizer.Form.NFKC)
            .map { if (it in '\u3041'..'\u3096') (it.code + 0x60).toChar() else it }
            .joinToString("")
        if (kana.isBlank() || kana.length > 128 || kana.none { it in '\u30A1'..'\u30FA' }) return null
        // Avoid querying a Japanese converter for unfinished roman input or converted kanji.
        if (kana.any { it.isLetter() && it !in '\u30A1'..'\u30FA' && it != 'ー' }) return null
        val context = clean(preceding).let {
            val points = it.codePoints().toArray().takeLast(40).toIntArray()
            String(points, 0, points.size)
        }
        return "\uEE00$kana" + (if (context.isEmpty()) "" else "\uEE02$context") + "\uEE01"
    }

    fun clean(text: String): String = text.filter {
        !it.isISOControl() && it !in '\uEE00'..'\uEE06'
    }.replace(' ', '　')

    fun candidate(text: String): MozcSession.Candidate? = text.takeIf {
        it.isNotBlank() && it.length <= 192 && it.none { c -> c.isISOControl() || c in '\uEE00'..'\uEE06' } &&
            '\uFFFD' !in it
    }?.let { MozcSession.Candidate(CANDIDATE_ID, it) }

    fun candidates(base: List<MozcSession.Candidate>, neural: MozcSession.Candidate): List<MozcSession.Candidate> =
        listOf(neural) + base.filter { it.text != neural.text }
}
