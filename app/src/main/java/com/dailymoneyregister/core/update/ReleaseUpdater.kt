package com.dailymoneyregister.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checks only the repository's latest stable GitHub release. Android's package installer
 * remains responsible for enforcing the installed application's signing identity.
 */
object ReleaseUpdater {
    private const val RELEASE_API =
        "https://api.github.com/repos/guruoshan/Paid-Due/releases/latest"
    private const val APK_PREFIX = "paid-due-v"
    private const val APK_MIME = "application/vnd.android.package-archive"

    data class CheckResult(
        val latestVersion: Long,
        val currentVersion: Long,
        val downloadUrl: String?,
        val sha256: String?,
        val assetName: String?
    ) {
        val updateAvailable: Boolean get() = latestVersion > currentVersion
    }

    suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val release = requestJson(RELEASE_API)
        require(!release.optBoolean("draft", true)) { "The latest GitHub release is a draft." }
        require(!release.optBoolean("prerelease", true)) { "The latest GitHub release is a prerelease." }

        val tag = release.optString("tag_name")
        val latestVersion = tag.removePrefix("v").substringAfterLast('.', "").toLongOrNull()
            ?: throw IllegalStateException("GitHub release tag is not in the expected v1.0.N format.")
        require(tag.matches(Regex("v1\\.0\\.\\d+"))) {
            "GitHub release tag is not in the expected v1.0.N format."
        }

        val currentVersion = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        }

        val assets = release.optJSONArray("assets")
            ?: throw IllegalStateException("GitHub release has no assets.")
        var apkUrl: String? = null
        var apkDigest: String? = null
        var apkName: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (name == "$APK_PREFIX$tag.apk") {
                val url = asset.optString("browser_download_url")
                val digest = asset.optString("digest")
                require(url.startsWith("https://github.com/guruoshan/Paid-Due/releases/download/")) {
                    "APK download URL is not an official Paid&Due GitHub release asset."
                }
                require(digest.startsWith("sha256:") && digest.removePrefix("sha256:").matches(Regex("[0-9a-fA-F]{64}"))) {
                    "GitHub did not provide a valid SHA-256 digest for this APK. Installation was blocked."
                }
                apkUrl = url
                apkDigest = digest.removePrefix("sha256:").lowercase()
                apkName = name
                break
            }
        }
        CheckResult(
            latestVersion = latestVersion,
            currentVersion = currentVersion,
            downloadUrl = apkUrl,
            sha256 = apkDigest,
            assetName = apkName
        )
    }

    suspend fun downloadAndInstall(context: Context, result: CheckResult) = withContext(Dispatchers.IO) {
        require(result.updateAvailable) { "There is no newer version to install." }
        val url = result.downloadUrl ?: throw IllegalStateException("The release APK is missing.")
        val expectedSha256 = result.sha256 ?: throw IllegalStateException("The release APK checksum is missing.")
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updatesDir, "paid-due-update.apk")
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "PaidDue-Android-Updater")
            }
            try {
                require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "Could not download the update (HTTP ${connection.responseCode})."
                }
                connection.inputStream.use { input ->
                    apkFile.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }
            require(apkFile.isFile && apkFile.length() > 0L) { "The downloaded APK is empty." }
            val actual = MessageDigest.getInstance("SHA-256").digest(apkFile.readBytes())
                .joinToString("") { "%02x".format(it) }
            require(actual.equals(expectedSha256, ignoreCase = true)) {
                "The downloaded APK failed its SHA-256 integrity check. Installation was blocked."
            }

            withContext(Dispatchers.Main) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !context.packageManager.canRequestPackageInstalls()
                ) {
                    val settingsIntent = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(settingsIntent)
                    throw IllegalStateException(
                        "Allow Paid&Due to install updates, then return to the app and tap Update again."
                    )
                }

                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, APK_MIME)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            }
        } catch (error: Exception) {
            apkFile.delete()
            throw error
        }
    }

    private fun requestJson(address: String): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "PaidDue-Android-Updater")
        }
        try {
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Could not check GitHub releases (HTTP ${connection.responseCode})."
            }
            return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }
}
