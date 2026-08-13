package io.github.soichi11208.zinna.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.soichi11208.zinna.keyboard.FlickGuideStyle
import io.github.soichi11208.zinna.keyboard.KeyboardStyle
import io.github.soichi11208.zinna.keyboard.LayoutRepository
import io.github.soichi11208.zinna.mozc.MozcEngine
import io.github.soichi11208.zinna.mozc.ProfileBackup
import io.github.soichi11208.zinna.mozc.UserDictionary
import io.github.soichi11208.zinna.theme.ZinnaTheme
import kotlin.system.exitProcess

/**
 * Setup and status screen.
 *
 * Deliberately thin for now: enabling the IME and confirming the native engine actually loaded are
 * the two things a user needs on first run, and the second one is the difference between "the
 * keyboard is broken" and "libmozc.so is missing for this ABI".
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZinnaTheme {
                Scaffold { padding ->
                    SettingsScreen(Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { LayoutRepository(context) }
    val engineStatus = remember { describeEngine(context) }
    val layouts = remember { repository.availableLayoutIds() }
    val themes = remember { repository.availableThemeIds() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("zinna-IME", style = MaterialTheme.typography.headlineMedium)

        InfoCard(title = "変換エンジン", body = engineStatus)
        InfoCard(title = "追加辞書", body = describeDictionaries(context))
        InfoCard(title = "配列", body = layouts.joinToString(", ").ifEmpty { "(なし)" })
        InfoCard(title = "テーマ", body = themes.joinToString(", ").ifEmpty { "(なし)" })

        UserDictionaryCard()

        BackupCard()

        KeyboardCard()

        AppearanceCard()

        TestInputField()

        Button(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("キーボードを有効化") }

        Button(
            onClick = {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("キーボードを選択") }
    }
}

@Composable
private fun UserDictionaryCard() {
    val context = LocalContext.current
    // Recomputed on every composition rather than remembered: returning from the editor
    // recomposes this screen, and a cached count would show the pre-edit number.
    val count = UserDictionary(context).entries().size

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ユーザー辞書", style = MaterialTheme.typography.titleMedium)
            Text(
                if (count == 0) "登録された単語はありません" else "$count 語を登録済み",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = {
                    context.startActivity(Intent(context, UserDictionaryActivity::class.java))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("単語を編集") }
        }
    }
}

/**
 * Export and import of everything the user cannot recreate. See [ProfileBackup].
 *
 * The passphrase is asked for before the file picker opens, so a mistyped one is caught while the
 * user still remembers what they meant to type rather than after a file has been written.
 */
@Composable
private fun BackupCard() {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pending?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var restorePending by remember { mutableStateOf(ProfileBackup.hasStagedRestore(context)) }

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME)
    ) { uri ->
        val phrase = passphrase.toCharArray()
        passphrase = ""
        if (uri == null) return@rememberLauncherForActivityResult
        val archive = ProfileBackup.export(context, phrase, BACKUP_EXTRAS)
        message = if (archive == null) {
            "書き出せませんでした。キーボードを一度使ってから試してください"
        } else {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(archive) }
                    ?: error("no stream")
                "書き出しました"
            }.getOrElse { "保存できませんでした: ${it.message}" }
        }
    }

    val load = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val phrase = passphrase.toCharArray()
        passphrase = ""
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        message = if (bytes == null) {
            "ファイルを読めませんでした"
        } else {
            when (val result = ProfileBackup.stageRestore(context, bytes, phrase)) {
                ProfileBackup.Result.Ok -> {
                    restorePending = true
                    "読み込みました。反映するにはアプリを終了して開き直してください"
                }
                ProfileBackup.Result.NotABackup -> "このファイルはバックアップではありません"
                ProfileBackup.Result.WrongPassphrase ->
                    "パスフレーズが違うか、ファイルが壊れています"
                is ProfileBackup.Result.TooNew ->
                    "新しいバージョンのアプリで作られたバックアップです (形式 ${result.format})"
                is ProfileBackup.Result.Failed -> result.message
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("バックアップ", style = MaterialTheme.typography.titleMedium)
            Text(
                "ユーザー辞書・変換の学習内容・キーボードの設定を1つのファイルにまとめます。" +
                    "打った内容そのものを含むので、パスフレーズで暗号化します。忘れると復元できません",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pending = Pending.EXPORT },
                    modifier = Modifier.weight(1f),
                ) { Text("書き出す") }
                OutlinedButton(
                    onClick = { pending = Pending.IMPORT },
                    modifier = Modifier.weight(1f),
                ) { Text("読み込む") }
            }
            if (restorePending) {
                Text(
                    "復元待ちです。学習内容はキーボードが動いている間ずっと書き戻されるので、" +
                        "一度終了させないと上書きされてしまいます",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        // Ends this process, keyboard included. Android starts the input method
                        // again the next time a text field is focused, and that start is what
                        // unpacks the restore.
                        onClick = { exitProcess(0) },
                        modifier = Modifier.weight(1f),
                    ) { Text("終了して反映") }
                    OutlinedButton(
                        onClick = {
                            ProfileBackup.discardStagedRestore(context)
                            restorePending = false
                            message = "復元を取り消しました"
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("取り消す") }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    pending?.let { what ->
        // Twice for an export, because a typo in a passphrase nobody has typed before is only
        // discovered when the backup is needed — by which time it is too late to matter.
        var confirmation by remember(what) { mutableStateOf("") }
        val exporting = what == Pending.EXPORT
        val ready = passphrase.length >= MIN_PASSPHRASE &&
            (!exporting || confirmation == passphrase)

        AlertDialog(
            onDismissRequest = { pending = null; passphrase = "" },
            title = { Text(if (exporting) "バックアップを書き出す" else "バックアップを読み込む") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("パスフレーズ") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (exporting) {
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = { confirmation = it },
                            label = { Text("もう一度") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "$MIN_PASSPHRASE 文字以上。このパスフレーズなしでは誰も — 私たちも — " +
                                "中身を読めません",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = ready,
                    onClick = {
                        pending = null
                        message = null
                        if (exporting) save.launch(ProfileBackup.suggestedFileName())
                        else load.launch(arrayOf("*/*"))
                    },
                ) { Text("続ける") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pending = null; passphrase = "" }) { Text("やめる") }
            },
        )
    }
}

private enum class Pending { EXPORT, IMPORT }

private const val BACKUP_MIME = "application/octet-stream"

/**
 * Everything outside mozc's own profile that the user chose rather than typed: the settings, the
 * background image they picked, and any layout or theme they dropped in by hand.
 */
private val BACKUP_EXTRAS = ProfileBackup.Extras(
    preferences = listOf(ImeSettings.NAME),
    directories = listOf(
        ImeSettings.BACKGROUND_DIR,
        LayoutRepository.LAYOUTS_DIR,
        LayoutRepository.THEMES_DIR,
    ),
)

/** Short enough not to be a chore, long enough that PBKDF2 has something to work with. */
private const val MIN_PASSPHRASE = 8

/**
 * How the keyboard is laid out and how big it is — choices about the input surface rather than
 * about how it looks, which is why they are not in [AppearanceCard].
 */
@Composable
private fun KeyboardCard() {
    val context = LocalContext.current
    val settings = remember { ImeSettings(context) }

    var style by remember { mutableStateOf(settings.keyboardStyle) }
    var guide by remember { mutableStateOf(settings.flickGuideStyle) }
    var symbolAllDirections by remember { mutableStateOf(settings.showAllDirectionsOnSymbolPlane) }
    var heightScale by remember { mutableStateOf(settings.keyHeightScale) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("キーボード", style = MaterialTheme.typography.titleMedium)

            Text("入力方式", style = MaterialTheme.typography.bodyLarge)
            Text(
                "かなと英字それぞれの配列。キーボード上のキーで面を切り替えたときも、この選択に従います",
                style = MaterialTheme.typography.bodySmall,
            )
            for ((option, label) in listOf(
                KeyboardStyle.FLICK to "フリック",
                KeyboardStyle.QWERTY to "QWERTY",
                KeyboardStyle.MIXED to "かなフリック + 英字QWERTY",
            )) {
                val onSelect = {
                    style = option
                    settings.keyboardStyle = option
                }
                if (style == option) {
                    Button(onClick = onSelect, modifier = Modifier.fillMaxWidth()) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = onSelect,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label) }
                }
            }

            HorizontalDivider()

            Text("フリック中の表示", style = MaterialTheme.typography.bodyLarge)
            Text(
                "キーを押している間に何を出すか。フリック配列のときだけ効きます",
                style = MaterialTheme.typography.bodySmall,
            )
            for ((option, label) in listOf(
                FlickGuideStyle.PREVIEW to "離したら入る文字だけ",
                FlickGuideStyle.DIRECTIONS to "4方向すべて",
            )) {
                val onSelect = {
                    guide = option
                    settings.flickGuideStyle = option
                }
                if (guide == option) {
                    Button(onClick = onSelect, modifier = Modifier.fillMaxWidth()) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = onSelect,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("記号面は常に4方向", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "記号はどの方向に何があるか覚えにくいので、上の選択に関わらず記号面だけ" +
                            "4方向すべてを出します",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = symbolAllDirections,
                    onCheckedChange = {
                        symbolAllDirections = it
                        settings.showAllDirectionsOnSymbolPlane = it
                    },
                )
            }

            HorizontalDivider()

            Text(
                "キーボードの高さ  ${(heightScale * 100).toInt()}%",
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = heightScale,
                onValueChange = { heightScale = it },
                onValueChangeFinished = { settings.keyHeightScale = heightScale },
                valueRange = ImeSettings.MIN_KEY_HEIGHT_SCALE..ImeSettings.MAX_KEY_HEIGHT_SCALE,
            )
        }
    }
}

/**
 * Appearance controls that belong to this device rather than to a shareable theme file.
 *
 * The keyboard picks changes up the next time it opens — it lives in another process and only
 * re-reads settings when its input view starts.
 */
@Composable
private fun AppearanceCard() {
    val context = LocalContext.current
    val settings = remember { ImeSettings(context) }

    var pureBlack by remember { mutableStateOf(settings.pureBlack) }
    var opacity by remember { mutableStateOf(settings.backgroundOpacity) }
    var hasImage by remember { mutableStateOf(settings.backgroundImage != null) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Read it out now rather than holding the URI: the IME runs in a process that never got
        // this grant, and the picked file can be deleted or its volume unmounted later.
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes != null) {
            settings.setBackgroundImage(bytes)
            hasImage = true
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("外観", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ピュアブラック", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "有機EL向け。背景を完全な黒にして画素を消灯させます",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = pureBlack,
                    onCheckedChange = {
                        pureBlack = it
                        settings.pureBlack = it
                    },
                )
            }

            HorizontalDivider()

            Text("キーボードの背景画像", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { pickImage.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f),
                ) { Text(if (hasImage) "変更" else "選択") }
                OutlinedButton(
                    onClick = {
                        settings.clearBackgroundImage()
                        hasImage = false
                    },
                    enabled = hasImage,
                    modifier = Modifier.weight(1f),
                ) { Text("解除") }
            }

            if (hasImage) {
                Text(
                    "画像の濃さ  ${(opacity * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    onValueChangeFinished = { settings.backgroundOpacity = opacity },
                    valueRange = 0f..1f,
                )
            }
        }
    }
}

/**
 * Somewhere to actually try the keyboard without leaving the app.
 *
 * A multi-line field on purpose: it is the only way to see what the Enter key does when the editor
 * has no action attached, which is exactly the case that is easy to get wrong.
 */
@Composable
private fun TestInputField() {
    var text by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("試し入力", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                placeholder = { Text("ここで入力を試せます") },
                singleLine = false,
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The bundled dictionaries import in the background on first run, so this doubles as the way to
 * tell "not imported yet" from "imported and empty" without digging through logcat.
 */
private fun describeDictionaries(context: Context): String =
    // Baked into mozc.data at build time rather than imported at runtime, so there is no state to
    // report — either the engine loaded or it did not.
    if (MozcEngine.get(context) == null) "—"
    else "ニコニコ大百科×ピクシブ百科事典 / カタカナ語→英語 / Wikipedia 見出し"

private fun describeEngine(context: Context): String {
    val engine = MozcEngine.get(context)
        ?: return "読み込み失敗 — この ABI 用の libmozc.so がありません (scripts/build_mozc.sh を実行)"
    val version = engine.dataVersion.ifEmpty { "unknown" }
    return "オフライン動作中 / 辞書バージョン $version"
}
