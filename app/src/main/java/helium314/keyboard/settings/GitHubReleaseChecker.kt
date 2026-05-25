// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private const val PREF_IGNORED_RELEASE_KEY = "github_release_ignored_key"

data class GitHubReleaseInfo(
    val versionLabel: String,
    val releaseKey: String,
    val htmlUrl: String,
    val downloadUrl: String,
    val downloadFileName: String,
    val notes: String,
)

@Serializable
private data class GitHubReleaseResponse(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val body: String = "",
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
private data class GitHubReleaseAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

object GitHubReleaseChecker {
    private const val TAG = "GitHubReleaseChecker"
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()

    suspend fun checkForUpdate(context: Context): GitHubReleaseInfo? = withContext(Dispatchers.IO) {
        val owner = BuildConfig.GITHUB_RELEASE_REPO_OWNER
        val repo = BuildConfig.GITHUB_RELEASE_REPO_NAME
        if (owner.isBlank() || repo.isBlank()) {
            Log.w(TAG, "GitHub repo is not configured for update checks")
            return@withContext null
        }

        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Release check failed with HTTP ${response.code}")
                    return@use null
                }
                val responseBody = response.body?.string().orEmpty()
                val release = json.decodeFromString<GitHubReleaseResponse>(responseBody)
                val releaseKey = release.tagName.ifBlank { release.name }.trim()
                if (release.htmlUrl.isBlank() || releaseKey.isBlank()) {
                    return@use null
                }
                if (context.prefs().getString(PREF_IGNORED_RELEASE_KEY, null) == releaseKey) {
                    return@use null
                }
                if (!isNewerThanCurrent(release.tagName, release.name)) {
                    return@use null
                }
                GitHubReleaseInfo(
                    versionLabel = release.tagName.ifBlank { release.name },
                    releaseKey = releaseKey,
                    htmlUrl = release.htmlUrl,
                    downloadUrl = release.apkAsset()?.browserDownloadUrl.orEmpty(),
                    downloadFileName = release.apkAsset()?.name.orEmpty(),
                    notes = release.body.trim()
                )
            }
        }.onFailure {
            Log.w(TAG, "Release check failed", it)
        }.getOrNull()
    }

    fun ignoreRelease(context: Context, releaseKey: String) {
        context.prefs().edit { putString(PREF_IGNORED_RELEASE_KEY, releaseKey) }
    }

    private fun GitHubReleaseResponse.apkAsset(): GitHubReleaseAsset? {
        return assets.firstOrNull { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) &&
                asset.browserDownloadUrl.isNotBlank()
        }
    }

    internal fun isNewerThanCurrent(tagName: String, releaseName: String): Boolean {
        return listOf(tagName, releaseName)
            .filter { it.isNotBlank() }
            .distinct()
            .any { compareReleaseVersion(it, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) > 0 }
    }

    internal fun compareReleaseVersion(
        remoteVersion: String,
        localVersionName: String,
        localVersionCode: Int
    ): Int {
        val remoteParts = extractVersionParts(remoteVersion)
        if (remoteParts.isEmpty()) return -1

        if (remoteParts.size == 1 && remoteParts.first() >= 1000) {
            return remoteParts.first().compareTo(localVersionCode)
        }

        val localParts = extractVersionParts(localVersionName)
        if (localParts.isEmpty()) return -1
        return compareVersionParts(remoteParts, localParts)
    }

    internal fun extractVersionParts(text: String): List<Int> =
        Regex("""\d+""").findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()

    internal fun compareVersionParts(left: List<Int>, right: List<Int>): Int {
        val maxSize = maxOf(left.size, right.size)
        for (index in 0 until maxSize) {
            val leftValue = left.getOrElse(index) { 0 }
            val rightValue = right.getOrElse(index) { 0 }
            if (leftValue != rightValue) {
                return leftValue.compareTo(rightValue)
            }
        }
        return 0
    }
}
