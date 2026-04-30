package helium314.keyboard.settings.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import helium314.keyboard.latin.R
import helium314.keyboard.settings.BackButton
import helium314.keyboard.sticker.Sticker
import helium314.keyboard.sticker.StickerManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerPackDetailScreen(
    packId: String,
    onClickBack: () -> Unit
) {
    val context = LocalContext.current
    var stickerManager by remember { mutableStateOf(StickerManager(context)) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    
    val pack = stickerManager.getPack(packId)
    
    if (pack == null) {
        // Handle error finding pack
        LaunchedEffect(Unit) { onClickBack() }
        return
    }

    LaunchedEffect(refreshTrigger) {
        stickerManager = StickerManager(context)
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            var addedCount = 0
            uris.forEach { uri ->
                if (stickerManager.addStickerFromUri(packId, uri) != null) addedCount++
            }
            refreshTrigger++
            val message = if (addedCount > 0)
                context.resources.getQuantityString(R.plurals.stickers_added, addedCount, addedCount)
            else
                context.getString(R.string.sticker_add_failed)
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pack.name) },
                navigationIcon = { BackButton(onClick = onClickBack) },
                windowInsets = WindowInsets.safeDrawing
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                Icon(Icons.Filled.Add, contentDescription = "Add Stickers")
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        if (pack.stickers.isEmpty()) {
             Box(modifier = Modifier.padding(innerPadding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No stickers in this pack yet.")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 80.dp),
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pack.stickers) { sticker ->
                    StickerItem(
                        sticker = sticker,
                        onDelete = {
                            stickerManager.removeSticker(packId, sticker.id)
                            refreshTrigger++
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun StickerItem(
    sticker: Sticker,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(sticker.uri)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Inside
        )
        
        // Delete button overlay
        IconButton(
            onClick = onDelete,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), RoundedCornerShape(50))
                .fillMaxSize(0.3f) // Make it small
        ) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.padding(2.dp))
        }
    }
}
