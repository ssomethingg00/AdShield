package com.example.adshield.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val hasUpdate: Boolean,
    val currentVersion: String,
    val latestVersion: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val releasePageUrl: String
)

object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val GITHUB_REPO = "ssomethingg00/AdShield"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    const val GITHUB_REPO_URL = "https://github.com/$GITHUB_REPO"

    /**
     * Checks GitHub Releases API for the latest version and compares with [currentVersion].
     */
    suspend fun checkForUpdates(currentVersion: String): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val url = URL(API_URL)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "AdShield-Android-App")
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                return@withContext Result.failure(
                    Exception("GitHub returned response code: $responseCode")
                )
            }

            val jsonString = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            val json = JSONObject(jsonString)

            val tagName = json.optString("tag_name", "").trim()
            val releaseTitle = json.optString("name", tagName)
            val releaseNotes = json.optString("body", "Bug fixes and improvements.")
            val releasePageUrl = json.optString("html_url", "$GITHUB_REPO_URL/releases/latest")

            var downloadUrl = ""
            val assets = json.optJSONArray("assets")
            if (assets != null && assets.length() > 0) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }

            if (downloadUrl.isEmpty()) {
                downloadUrl = releasePageUrl
            }

            val hasUpdate = isNewerVersion(currentVersion, tagName)

            Result.success(
                UpdateInfo(
                    hasUpdate = hasUpdate,
                    currentVersion = currentVersion,
                    latestVersion = tagName,
                    releaseTitle = releaseTitle,
                    releaseNotes = releaseNotes,
                    downloadUrl = downloadUrl,
                    releasePageUrl = releasePageUrl
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads APK via DownloadManager or opens the browser download link.
     */
    fun startDownload(context: Context, downloadUrl: String, fileName: String = "AdShield-Update.apk") {
        try {
            val request = DownloadManager.Request(Uri.parse(downloadUrl)).apply {
                setTitle("Downloading AdShield Update")
                setDescription("Fetching latest version from GitHub")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
            }
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
        } catch (e: Exception) {
            Log.w(TAG, "DownloadManager failed, opening in browser: ${e.message}")
            openInBrowser(context, downloadUrl)
        }
    }

    fun openInBrowser(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open browser: ${e.message}")
        }
    }

    /**
     * Semantic version comparator (e.g. v1.0.2 vs v1.0.1).
     */
    private fun isNewerVersion(current: String, latest: String): Boolean {
        val curClean = current.trimStart('v', 'V').split(".").mapNotNull { it.toIntOrNull() }
        val latClean = latest.trimStart('v', 'V').split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(curClean.size, latClean.size)
        for (i in 0 until maxLen) {
            val c = curClean.getOrElse(i) { 0 }
            val l = latClean.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
