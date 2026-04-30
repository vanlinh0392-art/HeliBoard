package helium314.keyboard.sticker

import android.content.Context
import android.net.Uri
import helium314.keyboard.latin.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class StickerImportProgress(
    val message: String,
    val completed: Int = 0,
    val total: Int? = null,
)

/**
 * Handles downloading stickers from direct ZIP URLs or Telegram sticker pack links.
 */
class StickerDownloader(private val context: Context, private val stickerManager: StickerManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val telegramBotToken = BuildConfig.TELEGRAM_BOT_TOKEN

    suspend fun downloadAndImport(
        url: String,
        onProgress: ((StickerImportProgress) -> Unit)? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val normalizedUrl = url.trim()
            when {
                normalizedUrl.contains("t.me/addstickers/") -> importFromTelegram(normalizedUrl, onProgress)
                normalizedUrl.endsWith(".zip", ignoreCase = true) || normalizedUrl.contains("zip") ->
                    importFromZipUrl(normalizedUrl, onProgress)
                else -> Pair(false, "Vui long nhap link ZIP hoac link Telegram (t.me/addstickers/...)")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, "Loi tai xuong: ${e.message}")
        }
    }

    private suspend fun importFromZipUrl(
        url: String,
        onProgress: ((StickerImportProgress) -> Unit)? = null
    ): Pair<Boolean, String> {
        return try {
            reportProgress(onProgress, StickerImportProgress(message = "Dang tai file ZIP..."))
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    return Pair(false, "Khong the tai file, HTTP ${response.code}")
                }

                val tempZipFile = File(context.cacheDir, "temp_pack_${System.currentTimeMillis()}.zip")
                body.byteStream().use { input ->
                    FileOutputStream(tempZipFile).use { output ->
                        input.copyTo(output)
                    }
                }

                reportProgress(onProgress, StickerImportProgress(message = "Dang giai nen goi sticker..."))
                val newPackId = stickerManager.importPackFromZip(Uri.fromFile(tempZipFile))
                tempZipFile.delete()

                if (newPackId != null) {
                    Pair(true, "Nhap goi sticker thanh cong!")
                } else {
                    Pair(false, "Tai xong nhung khong trich xuat duoc sticker hop le.")
                }
            }
        } catch (e: Exception) {
            Pair(false, "Loi khi tai file ZIP: ${e.message}")
        }
    }

    private suspend fun importFromTelegram(
        url: String,
        onProgress: ((StickerImportProgress) -> Unit)? = null
    ): Pair<Boolean, String> {
        if (telegramBotToken.isBlank()) {
            return Pair(false, "Tinh nang lay sticker Telegram tam thoi khong kha dung. Vui long dung folder hoac ZIP.")
        }

        return try {
            reportProgress(onProgress, StickerImportProgress(message = "Dang lay thong tin bo sticker..."))
            val setName = url.substringAfterLast("addstickers/").substringBefore("?").trim()
            if (setName.isEmpty()) {
                return Pair(false, "Link Telegram khong hop le.")
            }

            val apiUrl = telegramApiUrl("getStickerSet")
                .newBuilder()
                .addQueryParameter("name", setName)
                .build()
            val request = Request.Builder().url(apiUrl).build()
            val jsonText = client.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    return Pair(false, "Khong the lay thong tin goi sticker tu Telegram.")
                }
                body.string()
            }

            val jsonObject = JSONObject(jsonText)
            if (!jsonObject.getBoolean("ok")) {
                return Pair(false, "Telegram tra ve loi: ${jsonObject.optString("description")}")
            }

            val resultObj = jsonObject.getJSONObject("result")
            val packTitle = resultObj.optString("title", setName)
            val stickersArray = resultObj.getJSONArray("stickers")
            if (stickersArray.length() == 0) {
                return Pair(false, "Goi sticker nay trong.")
            }

            val staticStickers = mutableListOf<JSONObject>()
            for (i in 0 until stickersArray.length()) {
                val stickerObj = stickersArray.getJSONObject(i)
                val isAnimated = stickerObj.optBoolean("is_animated", false)
                val isVideo = stickerObj.optBoolean("is_video", false)
                if (!isAnimated && !isVideo) {
                    staticStickers.add(stickerObj)
                }
            }

            if (staticStickers.isEmpty()) {
                return Pair(false, "Goi nay khong co sticker tinh de import.")
            }

            var packCreatedId: String? = null
            var downloadedCount = 0

            for ((index, stickerObj) in staticStickers.withIndex()) {
                reportProgress(
                    onProgress,
                    StickerImportProgress(
                        message = "Dang xu ly sticker ${index + 1}/${staticStickers.size}...",
                        completed = index,
                        total = staticStickers.size
                    )
                )

                val fileId = stickerObj.getString("file_id")
                val fileUrl = telegramApiUrl("getFile")
                    .newBuilder()
                    .addQueryParameter("file_id", fileId)
                    .build()
                val fileReq = Request.Builder().url(fileUrl).build()
                val fileRespText = client.newCall(fileReq).execute().use { fileRes ->
                    val body = fileRes.body
                    if (!fileRes.isSuccessful || body == null) {
                        null
                    } else {
                        body.string()
                    }
                } ?: continue

                val fileRespJson = JSONObject(fileRespText)
                if (!fileRespJson.getBoolean("ok")) {
                    continue
                }

                val filePath = fileRespJson.getJSONObject("result").getString("file_path")
                val downloadUrl = telegramFileUrl(filePath)
                val dlReq = Request.Builder().url(downloadUrl).build()
                val tempFile = client.newCall(dlReq).execute().use { dlRes ->
                    val body = dlRes.body
                    if (!dlRes.isSuccessful || body == null) {
                        null
                    } else {
                        val ext = filePath.substringAfterLast('.', "webp")
                        val file = File(context.cacheDir, "tg_sticker_${System.currentTimeMillis()}.$ext")
                        FileOutputStream(file).use { out ->
                            body.byteStream().use { input ->
                                input.copyTo(out)
                            }
                        }
                        file
                    }
                } ?: continue

                val currentPackId = packCreatedId ?: stickerManager.createPack(packTitle).id.also {
                    packCreatedId = it
                }

                val addedSticker = stickerManager.addStickerFromUri(
                    packId = currentPackId,
                    uri = Uri.fromFile(tempFile),
                    normalizeToWebp = true
                )
                if (addedSticker != null) {
                    downloadedCount++
                }
                tempFile.delete()

                reportProgress(
                    onProgress,
                    StickerImportProgress(
                        message = "Da xu ly ${index + 1}/${staticStickers.size} sticker...",
                        completed = index + 1,
                        total = staticStickers.size
                    )
                )
            }

            if (downloadedCount > 0) {
                Pair(true, "Da import $downloadedCount sticker tinh tu bo Telegram $packTitle.")
            } else {
                packCreatedId?.let { stickerManager.deletePack(it) }
                Pair(false, "Khong lay duoc sticker nao. Goi nay co the toan sticker dong.")
            }
        } catch (e: Exception) {
            Pair(false, "Loi ket noi Telegram API: ${e.message}")
        }
    }

    private suspend fun reportProgress(
        onProgress: ((StickerImportProgress) -> Unit)?,
        progress: StickerImportProgress
    ) {
        if (onProgress == null) {
            return
        }
        withContext(Dispatchers.Main) {
            onProgress(progress)
        }
    }

    private fun telegramApiUrl(method: String) =
        "https://api.telegram.org/bot$telegramBotToken/$method".toHttpUrl()

    private fun telegramFileUrl(filePath: String) =
        "https://api.telegram.org/file/bot$telegramBotToken/".toHttpUrl()
            .newBuilder()
            .addPathSegments(filePath)
            .build()
}
