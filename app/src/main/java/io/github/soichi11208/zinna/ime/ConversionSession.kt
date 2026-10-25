package io.github.soichi11208.zinna.ime

import android.content.Context
import io.github.soichi11208.zinna.keyboard.InputStyle
import io.github.soichi11208.zinna.settings.ImeSettings
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Context as ClientContext
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent

/** Routes the whole composition session, not just the displayed candidates. */
internal class ConversionSession(private val context: Context) {
    private var mozc: MozcSession? = null
    private var style = InputStyle.FLICK_HIRAGANA
    private val kana = KanaComposer(runCatching {
        context.assets.open("zenzai/romaji.tsv").bufferedReader().use { KanaComposer.rules(it.readLines()) }
    }.getOrDefault(emptyMap()))
    var zenzaiEnabled = ImeSettings(context).zenzaiPlusEnabled
        private set
    private var mozcAlongside = ImeSettings(context).zenzaiMozcEnabled
    private val usesMozc get() = ZenzaiConversion.usesMozc(zenzaiEnabled, mozcAlongside)
    var clientContext: ClientContext? = null
        set(value) { field = value; if (usesMozc) native().clientContext = value }
    val isAvailable get() = zenzaiEnabled || native().isAvailable

    private fun native(): MozcSession = mozc ?: MozcSession(context).also {
        mozc = it
        it.applyInputStyle(style)
        it.clientContext = clientContext
    }

    fun setZenzaiEnabled(enabled: Boolean, alongside: Boolean) {
        if (enabled == zenzaiEnabled && alongside == mozcAlongside) return
        mozc?.close()
        mozc = null
        kana.clear()
        zenzaiEnabled = enabled
        mozcAlongside = alongside
        clientContext = null
    }

    fun applyInputStyle(value: InputStyle) {
        style = value
        if (usesMozc) native().applyInputStyle(value)
    }

    fun sendText(text: String, label: String = text): MozcSession.State? {
        if (usesMozc) return native().sendText(text)
        if (text == "*") kana.modify()
        else if (!style.fullWidthSpace) kana.insert(label)
        else if (style == InputStyle.QWERTY_HIRAGANA || style == InputStyle.GODAN_HIRAGANA) kana.roman(text)
        else kana.insert(label)
        return kana.state()
    }

    fun sendSpecialKey(key: KeyEvent.SpecialKey): MozcSession.State? {
        if (usesMozc) return native().sendSpecialKey(key)
        return when (key) {
            KeyEvent.SpecialKey.BACKSPACE -> { kana.backspace(); kana.state() }
            KeyEvent.SpecialKey.LEFT -> { kana.move(-1); kana.state() }
            KeyEvent.SpecialKey.RIGHT -> { kana.move(1); kana.state() }
            KeyEvent.SpecialKey.SPACE -> if (kana.text.isEmpty()) MozcSession.State()
                else submit()?.let { if (style.fullWidthSpace) it else it.copy(committedText = it.committedText + " ") }
            else -> kana.state()
        }
    }

    fun submit(): MozcSession.State? {
        if (usesMozc) return native().submit()
        val text = kana.finish()
        kana.clear()
        return MozcSession.State(committedText = text, consumed = true)
    }
    fun revert(): MozcSession.State? = if (usesMozc) native().revert() else { kana.clear(); kana.state() }
    fun resetContext(): MozcSession.State? = if (usesMozc) native().resetContext() else { kana.clear(); kana.state() }
    fun undo(): MozcSession.State? = if (usesMozc) native().undo() else kana.state()
    fun selectCandidate(id: Int): MozcSession.State? = if (usesMozc) native().selectCandidate(id) else kana.state()
    fun stopKeyToggling(): MozcSession.State? = if (usesMozc) native().stopKeyToggling() else kana.state()
    fun close() { mozc?.close(); mozc = null; kana.clear() }
}

/** Android key labels already resolve flick directions; only roman input needs a rule table. */
internal class KanaComposer(private val rules: Map<String, Pair<String, String>>) {
    private var committed = ""
    private var pending = ""
    private var cursor = 0
    val text get() = committed + pending
    fun clear() { committed = ""; pending = ""; cursor = 0 }
    fun insert(value: String) {
        flush()
        committed = committed.substring(0, cursor) + value + committed.substring(cursor)
        cursor += value.length
    }
    fun roman(value: String) {
        if (cursor != committed.length) { insert(value); return }
        pending += value.lowercase()
        while (pending.isNotEmpty()) {
            val rule = rules[pending]
            if (rule != null && (pending != "n")) {
                committed += rule.first; pending = rule.second; cursor = committed.length
                if (pending.isEmpty() || rules.keys.any { it.startsWith(pending) }) break
            } else if (rules.keys.any { it.startsWith(pending) }) break
            else if (pending.startsWith("n") && pending.length > 1 && pending[1] !in "aiueoyn") {
                committed += "ん"; pending = pending.drop(1); cursor = committed.length
            } else {
                committed += pending.first(); pending = pending.drop(1); cursor = committed.length
            }
        }
    }
    private fun flush() {
        if (pending.isNotEmpty()) {
            committed = committed.substring(0, cursor) + (if (pending == "n") "ん" else pending) + committed.substring(cursor)
            cursor += pending.length
            pending = ""
        }
    }
    fun finish(): String { flush(); return committed }
    fun backspace() {
        if (pending.isNotEmpty()) pending = pending.dropLast(1)
        else if (cursor > 0) {
            val previous = committed.offsetByCodePoints(cursor, -1)
            committed = committed.removeRange(previous, cursor); cursor = previous
        }
    }
    fun move(delta: Int) { flush(); cursor = committed.offsetByCodePoints(cursor,
        delta.coerceIn(-committed.codePointCount(0, cursor), committed.codePointCount(cursor, committed.length))) }
    fun modify() {
        flush()
        if (cursor == 0) return
        val c = committed[cursor - 1]
        val cycle = listOf("あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ", "かが", "きぎ", "くぐ", "けげ", "こご",
            "さざ", "しじ", "すず", "せぜ", "そぞ", "ただ", "ちぢ", "つっづ", "てで", "とど",
            "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ", "やゃ", "ゆゅ", "よょ", "わゎ")
            .firstOrNull { c in it }
        val next = cycle?.let { it[(it.indexOf(c) + 1) % it.length] }
            ?: if (c.isLowerCase()) c.uppercaseChar() else c.lowercaseChar()
        committed = committed.replaceRange(cursor - 1, cursor, next.toString())
    }
    fun state() = MozcSession.State(preedit = text, preeditReading = committed + if (pending == "n") "ん" else pending,
        preeditCursor = cursor + pending.length, consumed = true,
        candidates = if (text.isEmpty()) emptyList() else listOf(MozcSession.Candidate(-3, text)))
    companion object {
        fun rules(lines: List<String>): Map<String, Pair<String, String>> = lines.filter { !it.startsWith("#") }
            .mapNotNull { line -> line.split('\t').let { if (it.size < 2) null else it[0] to (it[1] to it.getOrElse(2) { "" }) } }.toMap()
    }
}
