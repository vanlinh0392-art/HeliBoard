package helium314.keyboard.sticker

import android.content.Context
import android.net.Uri
import helium314.keyboard.latin.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class StickerImportProgress(
    val message: String,
    val completed: Int = 0,
    val total: Int? = null,
)

internal fun parseTelegramStickerSetName(rawUrl: String): String? {
    val trimmedUrl = rawUrl.trim()
    if (trimmedUrl.isEmpty()) {
        return null
    }

    val normalizedUrl = if ("://" in trimmedUrl) trimmedUrl else "https://$trimmedUrl"
    val uri = runCatching { Uri.parse(normalizedUrl) }.getOrNull() ?: return null
    val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
    if (host !in setOf("t.me", "telegram.me", "telegram.dog")) {
        return null
    }

    val pathSegments = uri.pathSegments
    if (pathSegments.size < 2 || pathSegments[0] != "addstickers") {
        return null
    }

    return pathSegments[1].takeIf { it.isNotBlank() }
}

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
        telegramOutputFormat: StickerOutputFormat = StickerOutputFormat.WEBP,
        onProgress: ((StickerImportProgress) -> Unit)? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val normalizedUrl = url.trim()
            val telegramSetName = parseTelegramStickerSetName(normalizedUrl)
            when {
                telegramSetName != null -> importFromTelegram(telegramSetName, telegramOutputFormat, onProgress)
                normalizedUrl.endsWith(".zip", ignoreCase = true) || normalizedUrl.contains("zip") ->
                    importFromZipUrl(normalizedUrl, onProgress)
                else -> Pair(false, "Vui long nhap link ZIP hoac link Telegram (t.me/addstickers/TenGoi)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(false, "Loi tai xuong: ${e.message}")
        }
    }

    private suspend fun importFromZipUrl(
        url: String,
        onProgress: ((StickerImportProgress) -> Unit)? = null
    ): Pair<Boolean, String> {
        var tempZipFile: File? = null
        return try {
            reportProgress(onProgress, StickerImportProgress(message = "Dang tai file ZIP..."))
            val request = Request.Builder().url(url).build()
            executeRequest(request).use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    return Pair(false, "Khong the tai file, HTTP ${response.code}")
                }

                val zipFile = File(context.cacheDir, "temp_pack_${System.currentTimeMillis()}.zip")
                tempZipFile = zipFile
                body.byteStream().use { input ->
                    FileOutputStream(zipFile).use { output ->
                        input.copyTo(output)
                    }
                }

                reportProgress(onProgress, StickerImportProgress(message = "Dang giai nen goi sticker..."))
                val newPackId = stickerManager.importPackFromZip(Uri.fromFile(zipFile))
                tempZipFile?.delete()
                tempZipFile = null

                if (newPackId != null) {
                    Pair(true, "Nhap goi sticker thanh cong!")
                } else {
                    Pair(false, "Tai xong nhung khong trich xuat duoc sticker hop le.")
                }
            }
        } catch (e: CancellationException) {
            tempZipFile?.delete()
            throw e
        } catch (e: Exception) {
            Pair(false, "Loi khi tai file ZIP: ${e.message}")
        }
    }

    private suspend fun importFromTelegram(
        setName: String,
        outputFormat: StickerOutputFormat,
        onProgress: ((StickerImportProgress) -> Unit)? = null
    ): Pair<Boolean, String> {
        if (telegramBotToken.isBlank()) {
            return Pair(false, "Tinh nang Telegram chua duoc cau hinh. Vui long dung folder hoac ZIP.")
        }

        var packCreatedId: String? = null
        return try {
            reportProgress(onProgress, StickerImportProgress(message = "Dang lay thong tin bo sticker..."))
            val apiUrl = telegramApiUrl("getStickerSet")
                .newBuilder()
                .addQueryParameter("name", setName)
                .build()
            val request = Request.Builder().url(apiUrl).build()
            val jsonText = executeRequest(request).use { response ->
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

            var downloadedCount = 0

            for ((index, stickerObj) in staticStickers.withIndex()) {
                currentCoroutineContext().ensureActive()
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
                val fileRespText = executeRequest(fileReq).use { fileRes ->
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
                val tempFile = executeRequest(dlReq).use { dlRes ->
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

                try {
                    val addedSticker = stickerManager.addStickerFromUri(
                        packId = currentPackId,
                        uri = Uri.fromFile(tempFile),
                        outputFormat = outputFormat
                    )
                    if (addedSticker != null) {
                        downloadedCount++
                    }
                } finally {
                    tempFile.delete()
                }

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
        } catch (e: CancellationException) {
            packCreatedId?.let { stickerManager.deletePack(it) }
            throw e
        } catch (e: Exception) {
            Pair(false, "Loi ket noi Telegram API: ${e.message}")
        }
    }

    private suspend fun executeRequest(request: Request): Response {
        currentCoroutineContext().ensureActive()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            try {
                val response = call.execute()
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
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
