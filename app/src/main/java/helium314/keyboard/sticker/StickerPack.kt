package helium314.keyboard.sticker

import androidx.core.net.toUri
import kotlinx.serialization.Serializable

enum class StickerOutputFormat(
    val extension: String,
    val mimeType: String,
    val label: String,
) {
    ORIGINAL("", "", "Giữ nguyên"),
    WEBP("webp", "image/webp", "WEBP"),
    JPEG("jpg", "image/jpeg", "JPG"),
    PNG("png", "image/png", "PNG")
}

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

    fun getFileTypeLabel(): String {
        val extension = listOf(name, uri)
            .asSequence()
            .map { it.substringBefore('?').substringBefore('#').substringAfterLast('.', "") }
            .firstOrNull { it.matches(Regex("[A-Za-z0-9]+")) }
            ?: mimeType.substringAfter("image/", "")

        return when (extension.lowercase()) {
            "jpeg", "jpg" -> "JPG"
            "png" -> "PNG"
            "webp" -> "WEBP"
            "gif" -> "GIF"
            else -> extension.uppercase().take(6)
        }
    }
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
