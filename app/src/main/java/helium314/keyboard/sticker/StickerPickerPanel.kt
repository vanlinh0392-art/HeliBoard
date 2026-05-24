package helium314.keyboard.sticker

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.res.colorResource
import helium314.keyboard.latin.R

/**
 * Main Sticker Picker Panel - displays sticker packs as tabs and stickers in a grid
 */
@Composable
fun StickerPickerPanel(
    stickerManager: StickerManager,
    onStickersSelected: (List<Sticker>, StickerSendMode) -> Unit,
    onBackClick: () -> Unit,
    onAddPackClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedPackIndex by remember { mutableIntStateOf(0) }
    var selectedStickers by remember { mutableStateOf<List<Sticker>>(emptyList()) }
    var stickersWaitingForSendMode by remember { mutableStateOf<List<Sticker>>(emptyList()) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val isSelectionMode = selectedStickers.isNotEmpty()
    val allPacks = remember(refreshTick, stickerManager) {
        listOf(stickerManager.recentPack, stickerManager.favoritePack) + stickerManager.packs
    }
    val selectedPack = allPacks.getOrNull(selectedPackIndex)
    val visibleStickers = selectedPack?.stickers.orEmpty()
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .background(colorResource(R.color.keyboard_background_lxx_base))
    ) {
        if (isSelectionMode) {
            // Selection Action Bar
            SelectionActionBar(
                selectedCount = selectedStickers.size,
                onCancel = { selectedStickers = emptyList() },
                onSelectAll = { 
                    selectedStickers = visibleStickers
                },
                onSend = {
                    if (selectedStickers.size > 1) {
                        stickersWaitingForSendMode = selectedStickers
                    } else {
                        onStickersSelected(selectedStickers, StickerSendMode.SEND_ALL_NOW)
                        selectedStickers = emptyList()
                    }
                }
            )
        } else {
            // Pack Tabs
            StickerPackTabs(
                packs = allPacks,
                selectedIndex = selectedPackIndex,
                onPackSelected = { 
                    selectedPackIndex = it 
                    selectedStickers = emptyList() // clear selection on tab change
                },
                onBackClick = onBackClick,
                onAddClick = onAddPackClick,
                onSettingsClick = onSettingsClick
            )
        }
        
        if (stickersWaitingForSendMode.isNotEmpty()) {
            StickerSendModePrompt(
                selectedCount = stickersWaitingForSendMode.size,
                onSendAllNow = {
                    onStickersSelected(stickersWaitingForSendMode, StickerSendMode.SEND_ALL_NOW)
                    stickersWaitingForSendMode = emptyList()
                    selectedStickers = emptyList()
                },
                onSendOneByOne = {
                    onStickersSelected(stickersWaitingForSendMode, StickerSendMode.SHOW_ONE_BY_ONE)
                    stickersWaitingForSendMode = emptyList()
                    selectedStickers = emptyList()
                },
                onCancel = {
                    stickersWaitingForSendMode = emptyList()
                }
            )
        }

        // Sticker Grid
        if (selectedPack != null) {
            StickerGrid(
                stickers = visibleStickers,
                selectedStickers = selectedStickers,
                isSelectionMode = isSelectionMode,
                onStickerClick = { sticker ->
                    if (isSelectionMode) {
                        // Toggle selection
                        selectedStickers = if (selectedStickers.contains(sticker)) {
                            selectedStickers - sticker
                        } else {
                            selectedStickers + sticker
                        }
                        if (selectedStickers.isEmpty()) {
                            // exit selection mode automatically if empty
                        }
                    } else {
                        // Single send immediately
                        onStickersSelected(listOf(sticker), StickerSendMode.SEND_ALL_NOW)
                    }
                },
                onStickerLongClick = { sticker ->
                    if (!isSelectionMode) {
                        // Enter selection mode
                        selectedStickers = listOf(sticker)
                    }
                },
                onFavoriteClick = { sticker ->
                    val updatedSticker = stickerManager.toggleFavorite(sticker)
                    if (updatedSticker != null) {
                        selectedStickers = selectedStickers.map {
                            if (it.id == updatedSticker.id) updatedSticker else it
                        }
                        refreshTick++
                    }
                },
                modifier = Modifier.weight(1f)
            )
        } else {
            EmptyState(
                message = "Chưa có sticker nào",
                onAddClick = onAddPackClick
            )
        }

    }
}

enum class StickerSendMode {
    SEND_ALL_NOW,
    SHOW_ONE_BY_ONE
}

@Composable
fun StickerSendModePrompt(
    selectedCount: Int,
    onSendAllNow: () -> Unit,
    onSendOneByOne: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = "Gửi $selectedCount sticker?",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCancel) {
                Text("Hủy")
            }
            TextButton(onClick = onSendOneByOne) {
                Text("Từng cái")
            }
            TextButton(onClick = onSendAllNow) {
                Text("Gửi ngay tất cả")
            }
        }
    }
}

/**
 * Horizontal tabs for sticker packs
 */
@Composable
fun StickerPackTabs(
    packs: List<StickerPack>,
    selectedIndex: Int,
    onPackSelected: (Int) -> Unit,
    onBackClick: () -> Unit,
    onAddClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back Button
        IconButton(onClick = onBackClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Quay lại"
            )
        }

        // Pack tabs (scrollable)
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            items(packs.size) { index ->
                val pack = packs[index]
                StickerPackTab(
                    pack = pack,
                    isSelected = index == selectedIndex,
                    onClick = { onPackSelected(index) }
                )
            }
        }
        
        // Add button
        IconButton(onClick = onAddClick) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Thêm gói"
            )
        }
        
        // Settings button  
        IconButton(onClick = onSettingsClick) {
             Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Cài đặt"
            )
        }
    }
}

/**
 * Single pack tab
 */
@Composable
fun StickerPackTab(
    pack: StickerPack,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    
    Box(
        modifier = Modifier
            .padding(4.dp)
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                 if (isSelected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (pack.isRecent) {
            // Recent icon
            Text("ðŸ•", style = MaterialTheme.typography.titleMedium)
        } else if (pack.id == "favorites") {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = pack.name,
                tint = MaterialTheme.colorScheme.primary
            )
        } else if (pack.coverUri != null) {
            // Pack cover image
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(pack.getCoverContentUri())
                    .crossfade(true)
                    .build(),
                contentDescription = pack.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // Default pack icon
            Text("ðŸ“", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * Action bar replacing tabs when in selection mode
 */
@Composable
fun SelectionActionBar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.primaryContainer),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Filled.Close, contentDescription = "Hủy")
            }
            Text(
                text = "Đã chọn $selectedCount",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        
        Row {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.SelectAll, contentDescription = "Chọn tất cả")
            }
            IconButton(onClick = onSend, enabled = selectedCount > 0) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gửi", tint = if (selectedCount > 0) MaterialTheme.colorScheme.primary else Color.Gray)
            }
        }
    }
}

/**
 * Grid of stickers
 */
@Composable
fun StickerGrid(
    stickers: List<Sticker>,
    selectedStickers: List<Sticker>,
    isSelectionMode: Boolean,
    onStickerClick: (Sticker) -> Unit,
    onStickerLongClick: (Sticker) -> Unit,
    onFavoriteClick: (Sticker) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    if (stickers.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Gói này chưa có sticker",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(stickers) { sticker ->
                val isSelected = selectedStickers.contains(sticker)
                StickerItem(
                    sticker = sticker,
                    isSelected = isSelected,
                    isSelectionMode = isSelectionMode,
                    onClick = { onStickerClick(sticker) },
                    onLongClick = { onStickerLongClick(sticker) },
                    onFavoriteClick = { onFavoriteClick(sticker) }
                )
            }
        }
    }
}

/**
 * Single sticker item in the grid
 */
@Composable
fun StickerItem(
    sticker: Sticker,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
            .padding(if (isSelected) 4.dp else 0.dp) // Shrink effect when selected
            .pointerInput(sticker) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(sticker.getContentUri())
                .crossfade(true)
                .build(),
            contentDescription = sticker.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            error = painterResource(R.drawable.ic_warning)
        )
        IconButton(
            onClick = onFavoriteClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(28.dp)
                .background(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(bottomStart = 8.dp)
                )
        ) {
            Icon(
                imageVector = if (sticker.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = if (sticker.isFavorite) "Bỏ yêu thích" else "Thêm yêu thích",
                tint = if (sticker.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Empty state when no stickers
 */
@Composable
fun EmptyState(
    message: String,
    onAddClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Use a large text emoji or vector if available
        Text(
            text = "ðŸ“·",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onAddClick,
            shape = RoundedCornerShape(24.dp) // Pill shape
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Tạo gói sticker")
        }
    }
}

