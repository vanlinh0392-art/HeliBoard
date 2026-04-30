package helium314.keyboard.sticker

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import android.content.ClipDescription
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.net.toUri

/**
 * Handles sending stickers via InputConnection (Commit Content API)
 */
class StickerSender(private val context: Context) {
    
    companion object {
        private const val TAG = "StickerSender"
        private val SUPPORTED_MIME_TYPES = arrayOf(
            "image/png",
            "image/jpeg", 
            "image/webp",
            "image/gif"
        )
    }
    
    /**
     * Check if the current editor supports receiving images
     */
    fun isContentCommitSupported(editorInfo: EditorInfo?): Boolean {
        if (editorInfo == null) return false
        
        val supportedMimes = EditorInfoCompat.getContentMimeTypes(editorInfo)
        Log.d(TAG, "Editor supported MIME types: ${supportedMimes.joinToString()}")
        
        return SUPPORTED_MIME_TYPES.any { mimeType ->
            supportedMimes.any { supportedMime ->
                ClipDescription.compareMimeTypes(mimeType, supportedMime)
            }
        }
    }
    
    /**
     * Get supported MIME types for the current editor
     */
    fun getSupportedMimeTypes(editorInfo: EditorInfo?): Array<String> {
        if (editorInfo == null) return emptyArray()
        
        val editorMimes = EditorInfoCompat.getContentMimeTypes(editorInfo)
        return SUPPORTED_MIME_TYPES.filter { mimeType ->
            editorMimes.any { editorMime ->
                ClipDescription.compareMimeTypes(mimeType, editorMime)
            }
        }.toTypedArray()
    }
    
    /**
     * Send a sticker to the current input field
     */
    fun sendSticker(
        sticker: Sticker,
        inputConnection: InputConnection?,
        editorInfo: EditorInfo?
    ): Boolean {
        Log.d(TAG, "Attempting to send sticker: ${sticker.name}, URI: ${sticker.uri}")
        
        if (inputConnection == null || editorInfo == null) {
            Log.e(TAG, "InputConnection or EditorInfo is null")
            return false
        }
        
        if (!isContentCommitSupported(editorInfo)) {
            Log.e(TAG, "Editor does not support content commit for this MIME type")
            return false
        }
        
        val uri = try {
            val parsedUri = sticker.uri.toUri()
            if (parsedUri.scheme == "file") {
                if (parsedUri.path?.startsWith("/android_asset/") == true) {
                    // Handle asset file: copy to cache and get FileProvider URI
                    val parsedPath = parsedUri.path ?: return false
                    val assetPath = parsedPath.substring("/android_asset/".length)
                    val cacheFile = copyAssetToCache(assetPath)
                    if (cacheFile != null) {
                        androidx.core.content.FileProvider.getUriForFile(
                            context,
                            context.packageName + ".fileprovider",
                            cacheFile
                        )
                    } else {
                        Log.e(TAG, "Failed to copy asset to cache: $assetPath")
                        return false
                    }
                } else {
                    // Handle regular file
                    val parsedPath = parsedUri.path ?: return false
                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        context.packageName + ".fileprovider",
                        java.io.File(parsedPath)
                    )
                }
            } else {
                parsedUri
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing URI", e)
            return false
        }

        val mimeType = sticker.mimeType
        
        // Create ClipDescription with the sticker's MIME type
        val description = ClipDescription(sticker.name, arrayOf(mimeType))
        
        // Create InputContentInfo
        val contentInfo = InputContentInfoCompat(
            uri,
            description,
            null // No link URI
        )
        
        // Commit the content with read permission flag
        try {
            val result = InputConnectionCompat.commitContent(
                inputConnection,
                editorInfo,
                contentInfo,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null // No opts bundle
            )
            Log.d(TAG, "commitContent result: $result")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error committing content", e)
            return false
        }
    }
    
    /**
     * Send sticker from URI directly (for quick sharing)
     */
    fun sendStickerUri(
        uri: Uri,
        mimeType: String,
        inputConnection: InputConnection?,
        editorInfo: EditorInfo?
    ): Boolean {
        if (inputConnection == null || editorInfo == null) {
            return false
        }
        
        val description = ClipDescription("sticker", arrayOf(mimeType))
        val contentInfo = InputContentInfoCompat(uri, description, null)
        
        return InputConnectionCompat.commitContent(
            inputConnection,
            editorInfo,
            contentInfo,
            InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
            null
        )
    }
    private fun copyAssetToCache(assetPath: String): java.io.File? {
        return try {
            val cacheDir = java.io.File(context.cacheDir, "stickers")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            
            // Create a unique filename based on the asset path (sanitize it)
            val fileName = assetPath.replace("/", "_")
            val cacheFile = java.io.File(cacheDir, fileName)
            
            // If file already exists and is not empty, reuse it (simple caching)
            if (cacheFile.exists() && cacheFile.length() > 0) {
                return cacheFile
            }
            
            context.assets.open(assetPath).use { input ->
                java.io.FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            cacheFile
        } catch (e: Exception) {
            Log.e(TAG, "Error copying asset to cache", e)
            null
        }
    }
}
