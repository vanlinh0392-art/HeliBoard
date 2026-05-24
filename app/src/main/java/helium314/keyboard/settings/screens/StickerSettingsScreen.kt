package helium314.keyboard.settings.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.settings.BackButton
import android.widget.Toast
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import helium314.keyboard.sticker.CreatePackDialog
import helium314.keyboard.sticker.PackManagerItem
import helium314.keyboard.sticker.StickerManager
import helium314.keyboard.sticker.StickerDownloader
import helium314.keyboard.sticker.StickerImportProgress
import helium314.keyboard.sticker.StickerOutputFormat
import helium314.keyboard.sticker.StickerPack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerSettingsScreen(
    onClickBack: () -> Unit,
    onClickPack: (String) -> Unit
) {
    val context = LocalContext.current
    var stickerManager by remember { mutableStateOf(StickerManager(context)) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    
    // Dialog states
    var showAddOptionsDialog by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var packToDelete by remember { mutableStateOf<StickerPack?>(null) }
    var packToRename by remember { mutableStateOf<StickerPack?>(null) }
    
    // Link import states
    var showLinkImportDialog by remember { mutableStateOf(false) }
    var linkInputText by remember { mutableStateOf("") }
    var telegramOutputFormat by remember { mutableStateOf(StickerOutputFormat.WEBP) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<StickerImportProgress?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val stickerDownloader = remember(stickerManager) { StickerDownloader(context, stickerManager) }

    fun cancelLinkImport(closeDialog: Boolean) {
        downloadJob?.cancel()
        downloadJob = null
        isDownloading = false
        downloadProgress = null
        if (closeDialog) {
            showLinkImportDialog = false
        }
    }
    
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            stickerManager.importPackFromFolder(uri)
            refreshTrigger++
        }
    }
    
    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            stickerManager.importPackFromZip(uri)
            refreshTrigger++
        }
    }
    
    // Refresh stickers when trigger changes
    LaunchedEffect(refreshTrigger) {
        stickerManager = StickerManager(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gói Sticker") },
                navigationIcon = { BackButton(onClick = onClickBack) },
                actions = {
                    IconButton(onClick = { showAddOptionsDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Th?m g?i")
                    }
                },
                windowInsets = WindowInsets.safeDrawing
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (stickerManager.packs.isEmpty()) {
                Text(
                    text = "Chưa có gói sticker nào. Nhấn + để tạo.",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)
                ) {
                    items(stickerManager.packs) { pack ->
                        PackManagerItem(
                            pack = pack,
                            onRename = { packToRename = pack },
                            onDelete = { packToDelete = pack },
                            onClick = { onClickPack(pack.id) }
                        )
                    }
                }
            }
        }
    }
    
    if (showAddOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showAddOptionsDialog = false },
            title = { Text("Thêm gói Sticker") },
            text = {
                Column {
                    TextButton(onClick = { 
                        showAddOptionsDialog = false
                        showCreateDialog = true 
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Tạo Gói rỗng")
                    }
                    TextButton(onClick = { 
                        showAddOptionsDialog = false
                        folderPickerLauncher.launch(null) 
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Nhập từ Thư mục")
                    }
                    TextButton(onClick = { 
                        showAddOptionsDialog = false
                        // Using */* as some file managers have weird ZIP mime types
                        zipPickerLauncher.launch("application/zip") 
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Nhập từ file ZIP")
                    }
                    TextButton(onClick = { 
                        showAddOptionsDialog = false
                        showLinkImportDialog = true
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Nhập từ Link (ZIP/Telegram)")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddOptionsDialog = false }) {
                    Text("Hủy")
                }
            }
        )
    }

    // Create Dialog
    if (showCreateDialog) {
        CreatePackDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                stickerManager.createPack(name)
                showCreateDialog = false
                refreshTrigger++
            }
        )
    }

    // Link Import Dialog
    if (showLinkImportDialog) {
        AlertDialog(
            onDismissRequest = {
                cancelLinkImport(closeDialog = true)
            },
            title = { Text("Nhập từ Link") },
            text = {
                Column {
                    Text("Dán link tải file .zip hoặc link bộ sticker Telegram (t.me/addstickers/...) vào bên dưới:")
                    OutlinedTextField(
                        value = linkInputText,
                        onValueChange = { linkInputText = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        singleLine = true,
                        enabled = !isDownloading
                    )
                    Text(
                        text = "Định dạng khi nhập sticker Telegram:",
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                    StickerFormatOption(
                        label = "WEBP",
                        selected = telegramOutputFormat == StickerOutputFormat.WEBP,
                        enabled = !isDownloading,
                        onClick = { telegramOutputFormat = StickerOutputFormat.WEBP }
                    )
                    StickerFormatOption(
                        label = "JPG",
                        selected = telegramOutputFormat == StickerOutputFormat.JPEG,
                        enabled = !isDownloading,
                        onClick = { telegramOutputFormat = StickerOutputFormat.JPEG }
                    )
                    if (isDownloading) {
                        val progress = downloadProgress
                        Text(
                            text = progress?.message ?: "Dang xu ly...",
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        if (progress?.total != null && progress.total > 0) {
                            LinearProgressIndicator(
                                progress = { progress.completed.toFloat() / progress.total.toFloat() },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                            Text(
                                text = "${progress.completed}/${progress.total} sticker",
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (linkInputText.isNotBlank()) {
                            isDownloading = true
                            downloadProgress = StickerImportProgress(message = "Dang bat dau...")
                            var startedJob: Job? = null
                            startedJob = coroutineScope.launch {
                                try {
                                    val (success, message) = stickerDownloader.downloadAndImport(
                                        url = linkInputText,
                                        telegramOutputFormat = telegramOutputFormat
                                    ) { progress ->
                                        downloadProgress = progress
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    if (success) {
                                        showLinkImportDialog = false
                                        linkInputText = ""
                                        refreshTrigger++
                                    }
                                } catch (e: CancellationException) {
                                    Toast.makeText(context, "Da huy nhap sticker.", Toast.LENGTH_SHORT).show()
                                } finally {
                                    if (downloadJob == startedJob) {
                                        isDownloading = false
                                        downloadProgress = null
                                        downloadJob = null
                                    }
                                }
                            }
                            downloadJob = startedJob
                        }
                    },
                    enabled = !isDownloading && linkInputText.isNotBlank()
                ) {
                    Text("Tải & Nhập")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        cancelLinkImport(closeDialog = true)
                    },
                ) {
                    Text("Hủy")
                }
            }
        )
    }
    
    
    // Delete Confirmation
    packToDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { packToDelete = null },
            title = { Text("Xóa gói này?") },
            text = { Text("Xóa \"${pack.name}\" và tất cả ${pack.getStickerCount()} sticker?") },
            confirmButton = {
                Button(
                    onClick = {
                        stickerManager.deletePack(pack.id)
                        packToDelete = null
                        refreshTrigger++
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Xóa")
                }
            },
            dismissButton = {
                TextButton(onClick = { packToDelete = null }) {
                    Text("Hủy")
                }
            }
        )
    }
    
    // Rename Dialog
    packToRename?.let { pack ->
        var newName by remember { mutableStateOf(pack.name) }
        AlertDialog(
            onDismissRequest = { packToRename = null },
            title = { Text("Đổi tên gói") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Tên mới") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            stickerManager.renamePack(pack.id, newName.trim())
                            packToRename = null
                            refreshTrigger++
                        }
                    }
                ) {
                    Text("Đổi tên")
                }
            },
            dismissButton = {
                TextButton(onClick = { packToRename = null }) {
                    Text("Hủy")
                }
            }
        )
    }
}

@Composable
private fun StickerFormatOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled
        )
        Text(label)
    }
}
