package io.github.soichi11208.zinna.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.soichi11208.zinna.mozc.UserDictionary
import io.github.soichi11208.zinna.theme.ZinnaTheme

/**
 * Add, edit and delete the user's own words.
 *
 * Every edit writes the TSV and re-imports it into mozc immediately, so a word is usable the moment
 * the dialog closes — there is no apply step and nothing to forget to press.
 */
class UserDictionaryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZinnaTheme { UserDictionaryScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserDictionaryScreen() {
    val context = LocalContext.current
    val dictionary = remember { UserDictionary(context) }
    var entries by remember { mutableStateOf(dictionary.entries()) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("ユーザー辞書") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = EditTarget(null) }) {
                Icon(Icons.Filled.Add, contentDescription = "単語を追加")
            }
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "登録された単語はありません。\n右下の + から追加できます。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries) { entry ->
                    EntryRow(entry) { editing = EditTarget(entry) }
                }
            }
        }
    }

    editing?.let { target ->
        EntryDialog(
            initial = target.entry,
            onDismiss = { editing = null },
            onSave = { updated ->
                entries = dictionary.save(updated, replacing = target.entry)
                editing = null
            },
            onDelete = target.entry?.let {
                {
                    entries = dictionary.delete(it)
                    editing = null
                }
            },
        )
    }
}

/** null [entry] means "adding", otherwise the row being edited. */
private data class EditTarget(val entry: UserDictionary.Entry?)

@Composable
private fun EntryRow(entry: UserDictionary.Entry, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Text(entry.word, style = MaterialTheme.typography.titleMedium)
            Text(
                "${entry.reading}   ${entry.pos}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (entry.comment.isNotEmpty()) {
                Text(entry.comment, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDialog(
    initial: UserDictionary.Entry?,
    onDismiss: () -> Unit,
    onSave: (UserDictionary.Entry) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var reading by remember { mutableStateOf(initial?.reading.orEmpty()) }
    var word by remember { mutableStateOf(initial?.word.orEmpty()) }
    var pos by remember { mutableStateOf(initial?.pos ?: UserDictionary.DEFAULT_POS) }
    var comment by remember { mutableStateOf(initial?.comment.orEmpty()) }
    var posMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "単語を追加" else "単語を編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = reading,
                    onValueChange = { reading = it },
                    label = { Text("よみ") },
                    placeholder = { Text("ひらがなで入力") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text("単語") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenuBox(
                    expanded = posMenuOpen,
                    onExpandedChange = { posMenuOpen = it },
                ) {
                    OutlinedTextField(
                        value = pos,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("品詞") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = posMenuOpen)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = posMenuOpen,
                        onDismissRequest = { posMenuOpen = false },
                    ) {
                        UserDictionary.POS_TYPES.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate) },
                                onClick = {
                                    pos = candidate
                                    posMenuOpen = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("コメント (任意)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(UserDictionary.Entry(reading, word, pos, comment)) },
                // Both fields are required — mozc drops a row that is missing either.
                enabled = reading.isNotBlank() && word.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("削除") }
                }
                TextButton(onClick = onDismiss) { Text("キャンセル") }
            }
        },
    )
}
