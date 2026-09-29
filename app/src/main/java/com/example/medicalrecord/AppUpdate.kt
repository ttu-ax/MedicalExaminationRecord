package com.example.medicalrecord

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

internal data class AppUpdate(
    val versionName: String,
    val versionCode: Long,
    val minimumSupportedVersionCode: Long,
    val forceUpdate: Boolean,
    val releaseNotes: List<String>,
    val downloadUrl: String,
    val sha256: String
)

internal object AppUpdateService {
    const val MANIFEST_URL = "https://acupofttu.top/update.json"
    private const val SITE_HOST = "acupofttu.top"
    private const val MAX_APK_BYTES = 150L * 1024 * 1024

    fun check(context: Context): AppUpdate? {
        val connection = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7_000
            readTimeout = 7_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (json.optString("package_name") != context.packageName) return null
            val versionCode = json.optLong("version_code", -1)
            if (versionCode <= installedVersionCode(context)) return null
            val downloadUrl = json.optString("download_url").takeIf(String::isNotBlank) ?: return null
            val sha256 = json.optString("sha256").lowercase()
            if (!sha256.matches(Regex("[a-f0-9]{64}"))) return null
            val uri = URI(downloadUrl)
            if (uri.scheme != "https" || uri.host != SITE_HOST || uri.userInfo != null) return null
            val current = installedVersionCode(context)
            val minimum = json.optLong("minimum_supported_version_code", 1)
            AppUpdate(
                versionName = json.optString("version_name"),
                versionCode = versionCode,
                minimumSupportedVersionCode = minimum,
                forceUpdate = json.optBoolean("force_update") || current < minimum,
                releaseNotes = json.optJSONArray("release_notes")?.let { array ->
                    (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
                } ?: emptyList(),
                downloadUrl = downloadUrl,
                sha256 = sha256
            )
        } finally {
            connection.disconnect()
        }
    }

    fun download(context: Context, update: AppUpdate): File {
        val directory = File(context.cacheDir, "app-updates").apply { mkdirs() }
        val temporary = File(directory, "medical-record.apk.tmp")
        val apk = File(directory, "medical-record.apk")
        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = false
        }
        try {
            if (connection.responseCode !in 200..299) error("下载失败（HTTP ${connection.responseCode}）")
            val size = connection.contentLengthLong
            if (size > MAX_APK_BYTES) error("安装包超过 150 MB，已停止下载")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_APK_BYTES) error("安装包超过 150 MB，已停止下载")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualHash.equals(update.sha256, ignoreCase = true)) error("安装包校验失败，请稍后重试")
            if (!temporary.renameTo(apk)) {
                temporary.copyTo(apk, overwrite = true)
                temporary.delete()
            }
            return apk
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    @Suppress("DEPRECATION")
    private fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }
}
