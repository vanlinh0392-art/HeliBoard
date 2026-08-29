// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.database.Cursor
import android.provider.UserDictionary
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.LocaleUtils.localizedDisplayName
import helium314.keyboard.settings.DropDownField
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.ThreeButtonAlertDialog
import java.text.Normalizer
import java.util.Locale

@Composable
fun PersonalDictionaryScreen(
    onClickBack: () -> Unit,
    locale: Locale?
) {
    val ctx = LocalContext.current
    var refreshTrigger by remember { mutableStateOf(0) }
    val words = remember(locale, refreshTrigger) { getAll(locale, ctx) }
    fun showToast(message: String) = Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) {
            val result = exportWords(uri, words, locale, ctx)
            showToast(ctx.resources.getQuantityString(R.plurals.user_dict_backup_exported, result, result))
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val result = importWords(uri, locale, ctx)
            refreshTrigger++
            showToast(
                ctx.resources.getString(
                    R.string.user_dict_backup_import_result,
                    result.imported,
                    result.skippedDuplicates
                )
            )
        }
    }
    var selectedWord: Word? by remember { mutableStateOf(null) }
    var showDeleteShortcutsDialog by remember { mutableStateOf(false) }
    val shortcutCount = words.count { !it.shortcut.isNullOrBlank() }
    SearchScreen(
        onClickBack = onClickBack,
        title = {
            Column {
                Text(stringResource(R.string.edit_personal_dictionary))
                Text(
                    locale.getLocaleDisplayNameForUserDictSettings(ctx),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        menu = listOf(
            stringResource(R.string.user_dict_backup_export) to {
                exportLauncher.launch(defaultBackupFileName(locale))
            },
            stringResource(R.string.user_dict_backup_import) to {
                importLauncher.launch(arrayOf("text/*", "text/comma-separated-values", "application/csv", "*/*"))
            },
            stringResource(R.string.user_dict_delete_all_shortcuts) to {
                showDeleteShortcutsDialog = true
            }
        ),
        filteredItems = { term ->
            val cleanTerm = Normalizer.normalize(term.trim(), Normalizer.Form.NFC)
            if (cleanTerm.isEmpty()) words
            else words.filter {
                val cleanWord = Normalizer.normalize(it.word, Normalizer.Form.NFC)
                val cleanShortcut = it.shortcut?.let { s -> Normalizer.normalize(s, Normalizer.Form.NFC) }
                cleanWord.contains(cleanTerm, ignoreCase = true) || cleanShortcut?.contains(cleanTerm, ignoreCase = true) == true
            }
        },
        itemContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedWord = it }
                    .padding(vertical = 6.dp, horizontal = 16.dp)
            ) {
                Column {
                    Text(it.word, style = MaterialTheme.typography.bodyLarge)
                    val details = if (it.shortcut == null) it.weight.toString() else "${it.weight}  |  ${it.shortcut}"
                    Text(details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(painterResource(R.drawable.ic_edit), stringResource(R.string.user_dict_settings_edit_dialog_title))
            }
        }
    )
    if (showDeleteShortcutsDialog) {
        ConfirmationDialog(
            onDismissRequest = { showDeleteShortcutsDialog = false },
            onConfirmed = {
                val deleted = deleteAllShortcuts(words, locale, ctx.contentResolver)
                refreshTrigger++
                showToast(ctx.resources.getQuantityString(R.plurals.user_dict_shortcuts_deleted, deleted, deleted))
            },
            confirmButtonText = stringResource(R.string.delete),
            title = { Text(stringResource(R.string.user_dict_delete_all_shortcuts)) },
            content = {
                Text(stringResource(R.string.user_dict_delete_all_shortcuts_confirmation, shortcutCount))
            }
        )
    }
    if (selectedWord != null) {
        EditWordDialog(
            word = selectedWord!!,
            locale = locale,
            onWordChanged = { refreshTrigger++ },
            onDismissRequest = { selectedWord = null }
        )
    }
    ExtendedFloatingActionButton(
        onClick = { selectedWord = Word("", null, null) },
        text = { Text(stringResource(R.string.user_dict_add_word_button)) },
        icon = { Icon(painter = painterResource(R.drawable.ic_edit), stringResource(R.string.user_dict_add_word_button)) },
        modifier = Modifier.wrapContentSize(Alignment.BottomEnd).padding(all = 12.dp)
            .then(Modifier.safeDrawingPadding())
    )
}

@Composable
private fun EditWordDialog(
    word: Word,
    locale: Locale?,
    onWordChanged: () -> Unit,
    onDismissRequest: () -> Unit
) {
    val ctx = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    var newWord by remember { mutableStateOf(word) }
    var newLocale by remember { mutableStateOf(locale) }
    val wordValid = (newWord.word == word.word && locale == newLocale) || !doesWordExist(newWord.word, newLocale, ctx)
    fun save() {
        val cleanWord = Normalizer.normalize(newWord.word.trim(), Normalizer.Form.NFC)
        val cleanShortcut = newWord.shortcut?.trim()?.takeIf { it.isNotEmpty() }?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
        if (cleanWord.isEmpty()) return
        val wordToSave = newWord.copy(word = cleanWord, shortcut = cleanShortcut)
        if (wordToSave != word || locale != newLocale) {
            deleteWord(word, locale, ctx.contentResolver)
            val saveWeight = wordToSave.weight ?: WEIGHT_FOR_USER_DICTIONARY_ADDS
            runCatching {
                UserDictionary.Words.addWord(ctx, wordToSave.word, saveWeight, wordToSave.shortcut, newLocale)
            }
            onWordChanged()
        }
    }
    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        onConfirmed = { save() },
        checkOk = { newWord.word.isNotBlank() && wordValid },
        confirmButtonText = stringResource(R.string.save),
        neutralButtonText = stringResource(R.string.delete),
        onNeutral = {
            deleteWord(word, locale, ctx.contentResolver) // delete the originally selected word
            onWordChanged()
            onDismissRequest()
        },
        title = {
            Column {
                Text(stringResource(R.string.user_dict_settings_edit_dialog_title))
                Text(
                    locale.getLocaleDisplayNameForUserDictSettings(ctx),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        content = {
            LaunchedEffect(word) {
                if (word.word == "" && word.weight == null && word.shortcut == null)
                    focusRequester.requestFocus() // user clicked add word
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = newWord.word,
                    onValueChange = { newWord = newWord.copy(word = it) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    singleLine = true,
                    label = { Text(stringResource(R.string.user_dict_settings_add_word_hint))},
                    keyboardActions = KeyboardActions {
                        if (newWord.word.isNotBlank() && wordValid) {
                            save()
                            onDismissRequest()
                        }
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.user_dict_settings_add_shortcut_option_name), Modifier.fillMaxWidth(0.3f))
                    TextField(
                        value = newWord.shortcut ?: "",
                        onValueChange = { newWord = newWord.copy(shortcut = it.ifBlank { null }) },
                        label = { Text(stringResource(R.string.user_dict_settings_add_shortcut_hint))},
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Text(
                    stringResource(R.string.user_dict_settings_add_shortcut_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.user_dict_settings_add_locale_option_name), Modifier.fillMaxWidth(0.3f))
                    DropDownField(
                        items = getSpecificallySortedLocales(locale),
                        selectedItem = newLocale,
                        onSelected = { newLocale = it },
                    ) {
                        Text(it.getLocaleDisplayNameForUserDictSettings(ctx))
                    }
                }
                if (!wordValid)
                    Text(
                        stringResource(R.string.user_dict_word_already_present, newLocale.getLocaleDisplayNameForUserDictSettings(ctx)),
                        color = MaterialTheme.colorScheme.error
                    )
            }
        }
    )
}

private fun deleteWord(wordDetails: Word, locale: Locale?, resolver: ContentResolver): Int {
    return runCatching {
        val (word, shortcut, weightInt) = wordDetails
        val cleanWord = Normalizer.normalize(word.trim(), Normalizer.Form.NFC)
        if (cleanWord.isEmpty()) return@runCatching 0
        val cleanShortcut = shortcut?.trim()?.takeIf { it.isNotEmpty() }?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
        val weight = weightInt?.toString() ?: WEIGHT_FOR_USER_DICTIONARY_ADDS.toString()
        if (cleanShortcut.isNullOrBlank()) {
            if (locale == null) {
                resolver.delete(
                    UserDictionary.Words.CONTENT_URI,
                    DELETE_SELECTION_WITHOUT_SHORTCUT_AND_WITH_ALL_LOCALES,
                    arrayOf(cleanWord, weight)
                )
            } else {
                resolver.delete( // requires use of locale string for interaction with Android system
                    UserDictionary.Words.CONTENT_URI,
                    DELETE_SELECTION_WITHOUT_SHORTCUT_AND_WITH_LOCALE,
                    arrayOf(cleanWord, weight, locale.toString())
                )
            }
        } else {
            if (locale == null) {
                resolver.delete(
                    UserDictionary.Words.CONTENT_URI,
                    DELETE_SELECTION_WITH_SHORTCUT_AND_WITH_ALL_LOCALES,
                    arrayOf(cleanWord, cleanShortcut, weight)
                )
            } else {
                resolver.delete( // requires use of locale string for interaction with Android system
                    UserDictionary.Words.CONTENT_URI,
                    DELETE_SELECTION_WITH_SHORTCUT_AND_WITH_LOCALE,
                    arrayOf(cleanWord, cleanShortcut, weight, locale.toString())
                )
            }
        }
    }.getOrDefault(0)
}

private fun deleteAllShortcuts(words: List<Word>, locale: Locale?, resolver: ContentResolver): Int {
    val shortcuts = words.filter { !it.shortcut.isNullOrBlank() }
    var deleted = 0
    shortcuts.forEach {
        if (deleteWord(it, locale, resolver) > 0) {
            deleted++
        }
    }
    return deleted
}

private fun doesWordExist(word: String, locale: Locale?, context: Context): Boolean {
    val cleanWord = Normalizer.normalize(word.trim(), Normalizer.Form.NFC)
    if (cleanWord.isEmpty()) return false
    return runCatching {
        val hasWordProjection = arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.LOCALE)
        val select: String
        val selectArgs: Array<String>?
        if (locale == null) {
            select = "${UserDictionary.Words.WORD}=? AND ${UserDictionary.Words.LOCALE} is null"
            selectArgs = arrayOf(cleanWord)
        } else {
            select = "${UserDictionary.Words.WORD}=? AND ${UserDictionary.Words.LOCALE}=?"
            selectArgs = arrayOf(cleanWord, locale.toString())
        }
        val cursor = context.contentResolver.query(UserDictionary.Words.CONTENT_URI, hasWordProjection, select, selectArgs, null)
        cursor?.use { it.count > 0 } ?: false
    }.getOrDefault(false)
}

private fun exportWords(uri: Uri, words: List<Word>, locale: Locale?, context: Context): Int {
    var count = 0
    runCatching {
        context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.appendLine("shortcut,word,locale,weight")
            words.forEach { word ->
                writer.appendLine(
                    listOf(
                        word.shortcut.orEmpty(),
                        word.word,
                        locale?.toString().orEmpty(),
                        (word.weight ?: WEIGHT_FOR_USER_DICTIONARY_ADDS).toString()
                    ).joinToString(",") { it.toCsvField() }
                )
                count++
            }
        }
    }
    return count
}

private fun importWords(uri: Uri, fallbackLocale: Locale?, context: Context): ImportResult {
    var imported = 0
    var skippedDuplicates = 0
    val importedShortcuts = mutableSetOf<String>()
    runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
            lines.drop(1).forEach { line ->
                val fields = parseCsvLine(line)
                if (fields.size < 2) return@forEach
                val shortcut = fields[0].trim().takeIf { it.isNotEmpty() }?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
                val word = Normalizer.normalize(fields[1].trim(), Normalizer.Form.NFC).takeIf { it.isNotEmpty() } ?: return@forEach
                val importedLocale = fields.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }?.let { Locale.forLanguageTag(it.replace('_', '-')) }
                val weight = fields.getOrNull(3)?.trim()?.toIntOrNull()?.coerceIn(0, 255) ?: WEIGHT_FOR_USER_DICTIONARY_ADDS
                val locale = importedLocale ?: fallbackLocale
                if (!shortcut.isNullOrBlank()) {
                    val shortcutKey = "${locale?.toString().orEmpty()}\u0000${shortcut.lowercase(Locale.ROOT)}"
                    if (!importedShortcuts.add(shortcutKey) || doesShortcutExist(shortcut, locale, context)) {
                        skippedDuplicates++
                        return@forEach
                    }
                } else if (doesWordExist(word, locale, context)) {
                    skippedDuplicates++
                    return@forEach
                }
                runCatching {
                    UserDictionary.Words.addWord(context, word, weight, shortcut, locale)
                    imported++
                }
            }
        }
    }
    return ImportResult(imported, skippedDuplicates)
}

private fun doesShortcutExist(shortcut: String, locale: Locale?, context: Context): Boolean {
    val cleanShortcut = Normalizer.normalize(shortcut.trim(), Normalizer.Form.NFC)
    if (cleanShortcut.isEmpty()) return false
    return runCatching {
        val projection = arrayOf(UserDictionary.Words.SHORTCUT)
        val selection: String
        val selectionArgs: Array<String>?
        if (locale == null) {
            selection = "UPPER(${UserDictionary.Words.SHORTCUT})=UPPER(?) AND ${UserDictionary.Words.LOCALE} is null"
            selectionArgs = arrayOf(cleanShortcut)
        } else {
            selection = "UPPER(${UserDictionary.Words.SHORTCUT})=UPPER(?) AND ${UserDictionary.Words.LOCALE}=?"
            selectionArgs = arrayOf(cleanShortcut, locale.toString())
        }
        context.contentResolver.query(
            UserDictionary.Words.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor -> cursor.count > 0 } ?: false
    }.getOrDefault(false)
}

private data class ImportResult(val imported: Int, val skippedDuplicates: Int)

private fun defaultBackupFileName(locale: Locale?): String {
    val suffix = locale?.toLanguageTag()?.takeIf { it.isNotBlank() } ?: "all"
    return "tu-viet-tat-$suffix.csv"
}

private fun String.toCsvField(): String {
    val escaped = replace("\"", "\"\"")
    return if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
}

private fun parseCsvLine(line: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            quoted && char == '"' && index + 1 < line.length && line[index + 1] == '"' -> {
                current.append('"')
                index++
            }
            char == '"' -> quoted = !quoted
            char == ',' && !quoted -> {
                result.add(current.toString())
                current.clear()
            }
            else -> current.append(char)
        }
        index++
    }
    result.add(current.toString())
    return result
}

private fun getSpecificallySortedLocales(firstLocale: Locale?): List<Locale?> {
    val list: MutableList<Locale?> = getSortedDictionaryLocales().toMutableList()
    list.remove(firstLocale)
    list.remove(null)
    list.add(0, firstLocale)
    if (firstLocale != null)
        list.add(null)
    return list
}

fun Locale?.getLocaleDisplayNameForUserDictSettings(context: Context) =
    this?.localizedDisplayName(context.resources) ?: context.resources.getString(R.string.user_dict_settings_all_languages)

// weight is frequency but different name towards user
private data class Word(val word: String, val shortcut: String?, val weight: Int?)

// getting all words instead of reading directly cursor, because filteredItems expects a list
private fun getAll(locale: Locale?, context: Context): List<Word> {
    return runCatching {
        val cursor = createCursor(locale, context) ?: return@runCatching emptyList<Word>()
        cursor.use { c ->
            if (!c.moveToFirst()) return@runCatching emptyList<Word>()
            val result = mutableListOf<Word>()
            val wordIndex = c.getColumnIndex(UserDictionary.Words.WORD)
            val shortcutIndex = c.getColumnIndex(UserDictionary.Words.SHORTCUT)
            val frequencyIndex = c.getColumnIndex(UserDictionary.Words.FREQUENCY)
            if (wordIndex < 0) return@runCatching emptyList<Word>()
            while (!c.isAfterLast) {
                val word = c.getString(wordIndex)
                val shortcut = if (shortcutIndex >= 0 && !c.isNull(shortcutIndex)) c.getString(shortcutIndex) else null
                val freq = if (frequencyIndex >= 0 && !c.isNull(frequencyIndex)) c.getInt(frequencyIndex) else null
                if (!word.isNullOrBlank()) {
                    result.add(Word(word, shortcut, freq))
                }
                c.moveToNext()
            }
            result
        }
    }.getOrDefault(emptyList())
}

private fun createCursor(locale: Locale?, context: Context): Cursor? {
    return runCatching {
        val select: String
        val selectArgs: Array<String>?
        if (locale == null) {
            select = QUERY_SELECTION_ALL_LOCALES
            selectArgs = null
        } else {
            select = QUERY_SELECTION
            selectArgs = arrayOf(locale.toString())
        }

        context.contentResolver.query(
            UserDictionary.Words.CONTENT_URI, QUERY_PROJECTION, select, selectArgs, SORT_ORDER
        )
    }.getOrNull()
}

private val QUERY_PROJECTION =
    arrayOf(UserDictionary.Words._ID, UserDictionary.Words.WORD, UserDictionary.Words.SHORTCUT, UserDictionary.Words.FREQUENCY)
// Case-insensitive sort
private const val SORT_ORDER = "UPPER(" + UserDictionary.Words.WORD + ")"

// Either the locale is empty (means the word is applicable to all locales)
// or the word equals our current locale
private const val QUERY_SELECTION = UserDictionary.Words.LOCALE + "=?"
private const val QUERY_SELECTION_ALL_LOCALES = UserDictionary.Words.LOCALE + " is null"

private const val DELETE_SELECTION_WITH_SHORTCUT_AND_WITH_LOCALE =
    "${UserDictionary.Words.WORD}=? AND ${UserDictionary.Words.SHORTCUT}=? AND ${UserDictionary.Words.FREQUENCY}=? AND ${UserDictionary.Words.LOCALE}=?"

private const val DELETE_SELECTION_WITH_SHORTCUT_AND_WITH_ALL_LOCALES =
    "${UserDictionary.Words.WORD}=? AND ${UserDictionary.Words.SHORTCUT}=? AND ${UserDictionary.Words.FREQUENCY}=? AND ${UserDictionary.Words.LOCALE} is null"

private const val DELETE_SELECTION_WITHOUT_SHORTCUT_AND_WITH_LOCALE =
    "${UserDictionary.Words.WORD}=? AND (${UserDictionary.Words.SHORTCUT} is null OR ${UserDictionary.Words.SHORTCUT}='') AND ${UserDictionary.Words.FREQUENCY}=? AND ${UserDictionary.Words.LOCALE}=?"

private const val DELETE_SELECTION_WITHOUT_SHORTCUT_AND_WITH_ALL_LOCALES =
    "${UserDictionary.Words.WORD}=? AND (${UserDictionary.Words.SHORTCUT} is null OR ${UserDictionary.Words.SHORTCUT}='') AND ${UserDictionary.Words.FREQUENCY}=? AND ${UserDictionary.Words.LOCALE} is null"

private const val WEIGHT_FOR_USER_DICTIONARY_ADDS = 250
