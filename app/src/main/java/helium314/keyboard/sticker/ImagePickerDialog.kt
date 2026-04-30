package helium314.keyboard.sticker

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Dialog to pick images from gallery or folder
 */
@Composable
fun ImagePickerDialog(
    stickerManager: StickerManager,
    targetPackId: String?,
    onDismiss: () -> Unit,
    onImagesAdded: (List<Sticker>) -> Unit
) {
    var showCreatePackDialog by remember { mutableStateOf(targetPackId == null) }
    var selectedPackId by remember { mutableStateOf(targetPackId) }
    
    // Multi-image picker
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty() && selectedPackId != null) {
            val addedStickers = uris.mapNotNull { uri ->
                stickerManager.addStickerFromUri(selectedPackId!!, uri)
            }
            onImagesAdded(addedStickers)
        }
    }
    
    // Folder picker
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null && selectedPackId != null) {
            val addedStickers = stickerManager.addStickersFromFolder(selectedPackId!!, uri)
            onImagesAdded(addedStickers)
        }
    }
    
    if (showCreatePackDialog) {
        CreatePackDialog(
            onDismiss = onDismiss,
            onCreate = { name ->
                val pack = stickerManager.createPack(name)
                selectedPackId = pack.id
                showCreatePackDialog = false
            }
        )
    } else {
        Dialog(onDismissRequest = onDismiss) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Thêm Sticker",
                        style = MaterialTheme.typography.titleLarge
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Pick from Gallery
                    Button(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📷 Chọn từ Thư viện")
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Pick Folder
                    OutlinedButton(
                        onClick = { folderPickerLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📁 Chọn Thư mục")
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Cancel button
                    TextButton(onClick = onDismiss) {
                        Text("Hủy")
                    }
                }
            }
        }
    }
}

/**
 * Dialog to create a new sticker pack
 */
@Composable
fun CreatePackDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var packName by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tạo gói sticker") },
        text = {
            OutlinedTextField(
                value = packName,
                onValueChange = { packName = it },
                label = { Text("Tên gói") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { 
                    if (packName.isNotBlank()) {
                        onCreate(packName.trim())
                    }
                },
                enabled = packName.isNotBlank()
            ) {
                Text("Tạo")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        }
    )
}

/**
 * Dialog to manage sticker packs (rename, delete, reorder)
 */
@Composable
fun PackManagerDialog(
    stickerManager: StickerManager,
    onDismiss: () -> Unit
) {
    var packToDelete by remember { mutableStateOf<StickerPack?>(null) }
    var packToRename by remember { mutableStateOf<StickerPack?>(null) }
    
    // Delete confirmation
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
        return
    }
    
    // Rename dialog
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
        return
    }
    
    // Main manager dialog
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Quản lý gói sticker",
                    style = MaterialTheme.typography.titleLarge
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                if (stickerManager.packs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Chưa có gói sticker nào")
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(1),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(stickerManager.packs) { pack ->
                            PackManagerItem(
                                pack = pack,
                                onRename = { packToRename = pack },
                                onDelete = { packToDelete = pack }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Xong")
                }
            }
        }
    }
}

@Composable
fun PackManagerItem(
    pack: StickerPack,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit = {}
) {
    val context = LocalContext.current
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Pack cover
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (pack.coverUri != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(pack.coverUri ?: pack.stickers.firstOrNull()?.uri)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        // Pack info
        Column(modifier = Modifier.weight(1f)) {
            Text(pack.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${pack.getStickerCount()} sticker",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Actions
        IconButton(onClick = onRename) {
             Icon(Icons.Filled.Edit, contentDescription = "Đổi tên")
        }
        IconButton(onClick = onDelete) {
             Icon(Icons.Filled.Delete, contentDescription = "Xóa")
        }
    }
}
