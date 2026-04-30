package helium314.keyboard.sticker

import androidx.core.net.toUri
import kotlinx.serialization.Serializable

/**
 * Represents a single sticker in the collection
 */
@Serializable
data class Sticker(
    val id: String,
    val uri: String,  // Content URI as string
    val name: String = "",
    val mimeType: String = "image/png",
    val addedTime: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList(),
    val isFavorite: Boolean = false
) {
    fun getContentUri() = uri.toUri()
}

/**
 * Represents a sticker pack (folder/collection)
 */
@Serializable
data class StickerPack(
    val id: String,
    val name: String,
    val coverUri: String? = null,
    val stickers: MutableList<Sticker> = mutableListOf(),
    val createdTime: Long = System.currentTimeMillis(),
    val isRecent: Boolean = false,
    val isAssetPack: Boolean = false  // True for bundled stickers from assets
) {
    fun getCoverContentUri() = coverUri?.toUri()
    
    fun addSticker(sticker: Sticker) {
        stickers.add(sticker)
    }
    
    fun removeSticker(stickerId: String) {
        stickers.removeAll { it.id == stickerId }
    }
    
    fun getStickerCount(): Int = stickers.size
}
