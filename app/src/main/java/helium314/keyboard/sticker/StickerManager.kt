package helium314.keyboard.sticker

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.edit
import androidx.core.graphics.scale
import androidx.core.net.toUri
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Manages sticker packs - handles CRUD operations and persistence
 */
class StickerManager(private val context: Context) {
    private data class StoredStickerFile(
        val file: File,
        val mimeType: String,
    )
    
    companion object {
        private const val PREFS_NAME = "sticker_prefs"
        private const val KEY_PACKS = "sticker_packs"
        private const val KEY_RECENT = "recent_stickers"
        private const val KEY_DELETED_ASSET_PACKS = "deleted_asset_packs"
        private const val MAX_RECENT = 30
        private const val MAX_STICKER_SIZE = 512
        const val STICKER_DIR = "stickers"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    
    private var _packs: MutableList<StickerPack> = mutableListOf()
    val packs: List<StickerPack> get() = _packs
    
    private var _recentPack: StickerPack = StickerPack(
        id = "recent",
        name = "Recent",
        isRecent = true
    )
    val recentPack: StickerPack get() = _recentPack

    val favoritePack: StickerPack
        get() = StickerPack(
            id = "favorites",
            name = "Favorites",
            stickers = getAllStickers().filter { it.isFavorite }.toMutableList()
        )
    
    init {
        loadPacks()
        loadRecent()
        initDefaultPacks()
        enrichStickerMetadata()
        syncRecentWithPacks()
        validateRecentStickers()
    }
    
    // ========== Default Assets Packs ==========
    
    private fun initDefaultPacks() {
        val assetManager = context.assets
        try {
            var packFolders = assetManager.list(STICKER_DIR)
            
            // Fallback for Xiaomi/Other devices where asset list might fail
            if (packFolders.isNullOrEmpty()) {
                android.util.Log.w("StickerManager", "Asset listing failed or empty, using fallback list")
                packFolders = arrayOf("Memeisphong", "MonoMemeee", "Oldmasters", "PEPEtop", "PePeFroGi")
            }
            android.util.Log.d("StickerManager", "Found asset packs: ${packFolders.joinToString()}")
            
            if (packFolders.isEmpty()) {
                android.util.Log.w("StickerManager", "No asset packs found even with fallback, skipping update")
                return
            }

            // 1. Identify current asset packs
            val currentAssetPacks = _packs.filter { it.isAssetPack }.map { it.id }.toSet()
            val newAssetPacks = mutableListOf<String>()
            val deletedAssetPacks = prefs.getStringSet(KEY_DELETED_ASSET_PACKS, emptySet()).orEmpty()

            for (packName in packFolders) {
                val packId = "default_$packName"
                newAssetPacks.add(packId)
                if (deletedAssetPacks.contains(packId)) {
                    android.util.Log.d("StickerManager", "Pack $packId was deleted by user, skipping")
                    continue
                }
                
                // If pack already exists, skip (or update? simpler to skip for now unless we version them)
                if (_packs.any { it.id == packId }) {
                    android.util.Log.d("StickerManager", "Pack $packId already exists, skipping")
                    continue
                }

                val packPath = "$STICKER_DIR/$packName"
                val stickerFiles = assetManager.list(packPath) ?: continue
                android.util.Log.d("StickerManager", "Pack $packName contains ${stickerFiles.size} files")
                
                if (stickerFiles.isEmpty()) continue
                
                // Create the pack
                val pack = StickerPack(
                    id = packId,
                    name = packName,
                    isAssetPack = true
                )
                
                // Add stickers from assets
                for (fileName in stickerFiles.sortedBy { 
                    it.substringBefore(".").toIntOrNull() ?: Int.MAX_VALUE 
                }) {
                    val stickerPath = "file:///android_asset/$packPath/$fileName"
                    val extension = fileName.substringAfterLast(".", "webp")
                    val mimeType = when (extension.lowercase()) {
                        "png" -> "image/png"
                        "jpg", "jpeg" -> "image/jpeg"
                        "webp" -> "image/webp"
                        "gif" -> "image/gif"
                        else -> "image/webp"
                    }
                    
                    val sticker = Sticker(
                        id = "${pack.id}_$fileName",
                        uri = stickerPath,
                        mimeType = mimeType,
                        name = fileName.substringBeforeLast("."),
                        tags = tagsForSticker(packName, fileName)
                    )
                    pack.addSticker(sticker)
                }
                
                // Set cover as first sticker
                if (pack.stickers.isNotEmpty()) {
                    _packs.add(pack.copy(coverUri = pack.stickers.first().uri))
                    android.util.Log.d("StickerManager", "Added pack $packId with ${pack.stickers.size} stickers")
                }
            }
            
            // 2. Remove asset packs that no longer exist in assets
            val packsToRemove = _packs.filter { it.isAssetPack && !newAssetPacks.contains(it.id) }
            if (packsToRemove.isNotEmpty()) {
                android.util.Log.d("StickerManager", "Removing stale asset packs: ${packsToRemove.map { it.id }}")
                _packs.removeAll(packsToRemove)
            }
            
            if (packsToRemove.isNotEmpty() || newAssetPacks.any { !currentAssetPacks.contains(it) }) {
                 savePacks()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("StickerManager", "Error initDefaultPacks", e)
        }
    }
    
    // ========== CRUD Operations ==========
    
    fun createPack(name: String): StickerPack {
        val pack = StickerPack(
            id = UUID.randomUUID().toString(),
            name = name
        )
        _packs.add(pack)
        savePacks()
        return pack
    }
    
    fun deletePack(packId: String) {
        _packs.find { it.id == packId && it.isAssetPack }?.let {
            val deletedAssetPacks = prefs.getStringSet(KEY_DELETED_ASSET_PACKS, emptySet()).orEmpty().toMutableSet()
            deletedAssetPacks.add(packId)
            prefs.edit { putStringSet(KEY_DELETED_ASSET_PACKS, deletedAssetPacks) }
        }
        _packs.removeAll { it.id == packId }
        // Delete sticker files
        val packDir = File(context.filesDir, "$STICKER_DIR/$packId")
        packDir.deleteRecursively()
        savePacks()
    }
    
    fun renamePack(packId: String, newName: String) {
        _packs.find { it.id == packId }?.let { pack ->
            val index = _packs.indexOf(pack)
            _packs[index] = pack.copy(name = newName)
            savePacks()
        }
    }
    
    fun getPack(packId: String): StickerPack? {
        return _packs.find { it.id == packId }
    }
    
    // ========== Sticker Operations ==========
    
    fun addStickerFromUri(packId: String, uri: Uri, normalizeToWebp: Boolean = false): Sticker? {
        val pack = getPack(packId) ?: return null
        
        // Copy image to app storage
        val stickerId = UUID.randomUUID().toString()
        val storedSticker = copyStickerToStorage(
            sourceUri = uri,
            packId = packId,
            stickerId = stickerId,
            normalizeToWebp = normalizeToWebp
        ) ?: return null
        
        val sticker = Sticker(
            id = stickerId,
            uri = Uri.fromFile(storedSticker.file).toString(),
            mimeType = storedSticker.mimeType,
            name = uri.lastPathSegment?.substringBeforeLast(".").orEmpty(),
            tags = tagsForSticker(pack.name, uri.lastPathSegment.orEmpty())
        )
        
        pack.addSticker(sticker)
        
        // Update cover if first sticker
        if (pack.stickers.size == 1) {
            val index = _packs.indexOf(pack)
            _packs[index] = pack.copy(coverUri = sticker.uri)
        }
        
        savePacks()
        return sticker
    }
    
    fun addStickersFromFolder(packId: String, folderUri: Uri): List<Sticker> {
        val addedStickers = mutableListOf<Sticker>()
        try {
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri)
            if (docFile != null && docFile.isDirectory) {
                for (file in docFile.listFiles()) {
                    if (file.isFile && file.type?.startsWith("image/") == true) {
                        addStickerFromUri(packId, file.uri)?.let { addedStickers.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("StickerManager", "Error reading folder", e)
        }
        return addedStickers
    }

    fun importPackFromFolder(folderUri: Uri): String? {
        try {
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri)
            if (docFile != null && docFile.isDirectory) {
                val packName = docFile.name ?: "Imported Folder"
                val pack = createPack(packName)
                
                var addedCount = 0
                for (file in docFile.listFiles()) {
                    if (file.isFile && file.type?.startsWith("image/") == true) {
                        addStickerFromUri(pack.id, file.uri)
                        addedCount++
                    }
                }
                
                if (addedCount == 0) {
                    deletePack(pack.id) // Cleanup if no valid images
                    return null
                }
                return pack.id
            }
        } catch (e: Exception) {
            e.printStackTrace()
             android.util.Log.e("StickerManager", "Error importing pack from folder", e)
        }
        return null
    }

    fun importPackFromZip(zipUri: Uri): String? {
        try {
            // Get original filename or generic name
            var packName = "Imported ZIP"
            context.contentResolver.query(zipUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        packName = cursor.getString(nameIdx).substringBeforeLast(".")
                    }
                }
            }
            
            val pack = createPack(packName)
            var addedCount = 0
            
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                java.util.zip.ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && !entry.name.contains("__MACOSX")) { // ignore Mac metafiles
                            val ext = entry.name.substringAfterLast(".", "").lowercase()
                            if (ext in listOf("png", "jpg", "jpeg", "webp", "gif")) {
                                val stickerId = UUID.randomUUID().toString()
                                val packDir = File(context.filesDir, "$STICKER_DIR/${pack.id}")
                                if (!packDir.exists()) packDir.mkdirs()
                                
                                val destFile = File(packDir, "$stickerId.$ext")
                                destFile.outputStream().use { fos ->
                                    zis.copyTo(fos)
                                }
                                
                                val mimeType = "image/$ext" // approximation
                                val sticker = Sticker(
                                    id = stickerId,
                                    uri = Uri.fromFile(destFile).toString(),
                                    mimeType = mimeType,
                                    name = entry.name.substringAfterLast("/").substringBeforeLast("."),
                                    tags = tagsForSticker(pack.name, entry.name)
                                )
                                pack.addSticker(sticker)
                                addedCount++
                                
                                // Set cover uri on first image
                                if (addedCount == 1) {
                                    val index = _packs.indexOf(pack)
                                    _packs[index] = pack.copy(coverUri = sticker.uri)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            
            if (addedCount > 0) {
                savePacks()
                return pack.id
            } else {
                deletePack(pack.id)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("StickerManager", "Error importing pack from zip", e)
        }
        return null
    }
    
    fun removeSticker(packId: String, stickerId: String) {
        getPack(packId)?.let { pack ->
            val sticker = pack.stickers.find { it.id == stickerId }
            sticker?.let {
                // Delete file
                try {
                    val file = File(it.uri.toUri().path ?: "")
                    file.delete()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                pack.removeSticker(stickerId)
                savePacks()
            }
        }
    }
    
    // ========== Recent Stickers ==========
    
    fun addToRecent(sticker: Sticker) {
        _recentPack.stickers.removeAll { it.id == sticker.id }
        _recentPack.stickers.add(0, sticker)
        if (_recentPack.stickers.size > MAX_RECENT) {
            _recentPack.stickers.removeAt(_recentPack.stickers.lastIndex)
        }
        saveRecent()
    }

    fun toggleFavorite(sticker: Sticker): Sticker? {
        val updatedSticker = updateSticker(sticker.id) { it.copy(isFavorite = !it.isFavorite) }
        if (updatedSticker != null) {
            updateRecentSticker(updatedSticker)
            savePacks()
            saveRecent()
        }
        return updatedSticker
    }

    private fun updateSticker(stickerId: String, transform: (Sticker) -> Sticker): Sticker? {
        _packs.forEach { pack ->
            val index = pack.stickers.indexOfFirst { it.id == stickerId }
            if (index >= 0) {
                val updatedSticker = transform(pack.stickers[index])
                pack.stickers[index] = updatedSticker
                return updatedSticker
            }
        }
        return null
    }

    private fun updateRecentSticker(updatedSticker: Sticker) {
        val recentIndex = _recentPack.stickers.indexOfFirst { it.id == updatedSticker.id }
        if (recentIndex >= 0) {
            _recentPack.stickers[recentIndex] = updatedSticker
        }
    }

    private fun enrichStickerMetadata() {
        var changed = false
        _packs.forEach { pack ->
            pack.stickers.forEachIndexed { index, sticker ->
                if (sticker.tags.isEmpty()) {
                    pack.stickers[index] = sticker.copy(tags = tagsForSticker(pack.name, sticker.name))
                    changed = true
                }
            }
        }
        if (changed) savePacks()
    }

    private fun syncRecentWithPacks() {
        val stickersById = getAllStickers().associateBy { it.id }
        var changed = false
        _recentPack.stickers.forEachIndexed { index, sticker ->
            val currentSticker = stickersById[sticker.id]
            if (currentSticker != null && currentSticker != sticker) {
                _recentPack.stickers[index] = currentSticker
                changed = true
            }
        }
        if (changed) saveRecent()
    }
    
    // ========== Persistence ==========
    
    private fun savePacks() {
        val data = json.encodeToString(_packs)
        prefs.edit { putString(KEY_PACKS, data) }
    }
    
    private fun loadPacks() {
        val data = prefs.getString(KEY_PACKS, null)
        if (data != null) {
            try {
                _packs = json.decodeFromString(data)
            } catch (e: Exception) {
                e.printStackTrace()
                _packs = mutableListOf()
            }
        }
    }
    
    private fun saveRecent() {
        val data = json.encodeToString(_recentPack.stickers.toList())
        prefs.edit { putString(KEY_RECENT, data) }
    }
    
    private fun loadRecent() {
        val data = prefs.getString(KEY_RECENT, null)
        if (data != null) {
            try {
                val stickers: List<Sticker> = json.decodeFromString(data)
                _recentPack = _recentPack.copy(stickers = stickers.toMutableList())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    // ========== File Operations ==========
    
    private fun copyStickerToStorage(
        sourceUri: Uri,
        packId: String,
        stickerId: String,
        normalizeToWebp: Boolean = false
    ): StoredStickerFile? {
        return try {
            val packDir = File(context.filesDir, "$STICKER_DIR/$packId")
            if (!packDir.exists()) packDir.mkdirs()
            
            val extension = if (normalizeToWebp) "webp" else getExtension(sourceUri)
            val destFile = File(packDir, "$stickerId.$extension")
            
            if (normalizeToWebp) {
                if (!writeStickerAsWebp(sourceUri, destFile)) {
                    return null
                }
            } else {
                val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return null
                inputStream.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                if (destFile.length() <= 0L) return null
            }
            
            StoredStickerFile(
                file = destFile,
                mimeType = mimeTypeForExtension(extension)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun writeStickerAsWebp(sourceUri: Uri, destFile: File): Boolean {
        val bitmap = context.contentResolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: return false

        val scaledBitmap = scaleBitmapIfNeeded(bitmap)
        val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

        val success = FileOutputStream(destFile).use { output ->
            scaledBitmap.compress(compressFormat, 100, output)
        }

        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle()
        }
        bitmap.recycle()

        return success
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap): Bitmap {
        val largestEdge = maxOf(bitmap.width, bitmap.height)
        if (largestEdge <= MAX_STICKER_SIZE) {
            return bitmap
        }

        val scale = MAX_STICKER_SIZE.toFloat() / largestEdge.toFloat()
        val scaledWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return bitmap.scale(scaledWidth, scaledHeight)
    }
    
    private fun getExtension(uri: Uri): String {
        val mimeType = context.contentResolver.getType(uri)
        return when (mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "png"
        }
    }

    private fun mimeTypeForExtension(extension: String): String {
        return when (extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            else -> "image/webp"
        }
    }
    
    // ========== Utility ==========
    
    fun getAllStickers(): List<Sticker> {
        return _packs.flatMap { it.stickers }
    }
    
    fun searchStickers(query: String): List<Sticker> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        return getAllStickers().filter { sticker ->
            terms.all { term ->
                sticker.name.contains(term, ignoreCase = true) ||
                    sticker.tags.any { it.contains(term, ignoreCase = true) }
            }
        }
    }

    private fun tagsForSticker(packName: String, fileName: String): List<String> {
        val source = listOf(packName, fileName.substringBeforeLast("."))
        return source
            .flatMap { it.split(Regex("[^\\p{L}\\p{N}]+")) }
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 }
            .distinct()
    }
    
    // ========== Validation ==========

    private fun validateRecentStickers() {
        val validStickers = _recentPack.stickers.filter { sticker ->
            isValidStickerUri(sticker.uri)
        }
        if (validStickers.size != _recentPack.stickers.size) {
            _recentPack = _recentPack.copy(stickers = validStickers.toMutableList())
            saveRecent()
        }
    }

    private fun isValidStickerUri(uriString: String): Boolean {
        return try {
            val uri = uriString.toUri()
            when (uri.scheme) {
                "file" -> {
                    if (uriString.startsWith("file:///android_asset/")) {
                         // Check asset existence
                         val path = uriString.removePrefix("file:///android_asset/")
                         try {
                             context.assets.open(path).use { it.close() }
                             true
                         } catch (e: Exception) {
                             false
                         }
                    } else {
                        File(uri.path ?: "").exists()
                    }
                }
                // Assume content URIs and others are valid for now
                else -> true 
            }
        } catch (e: Exception) {
            false
        }
    }
}
