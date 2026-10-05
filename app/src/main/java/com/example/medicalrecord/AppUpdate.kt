package com.example.medicalrecord

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
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
    const val MANIFEST_URL = "https://gitee.com/api/v5/repos/afeng66/MedicalExaminationRecord/releases/latest"
    private const val SITE_MANIFEST_URL = "https://acupofttu.top/apps/medical-record/update.json"
    private const val SITE_DOWNLOAD_BASE = "https://acupofttu.top/downloads"
    private const val RELEASE_DOWNLOAD_BASE = "https://gitee.com/afeng66/MedicalExaminationRecord/releases/download"
    private const val MAX_APK_BYTES = 150L * 1024 * 1024

    fun check(context: Context): AppUpdate? {
        val current = installedVersionCode(context)
        return checkSources(current, listOf(
            "自建站" to { checkSite(context.packageName, current) },
            "Gitee" to { checkGitee(context.packageName, current) }
        ))
    }

    internal fun checkSources(current: Long, sources: List<Pair<String, () -> AppUpdate>>): AppUpdate? {
        val results = sources.map { (name, check) -> name to runCatching(check) }
        val latest = results.mapNotNull { it.second.getOrNull() }.maxByOrNull { it.versionCode }
        if (latest != null && latest.versionCode > current) return latest
        val failures = results.filter { it.second.isFailure }
        if (failures.isNotEmpty()) {
            error("无法确认最新版本：" + failures.joinToString("；") { (name, result) ->
                "$name：${result.exceptionOrNull()?.message ?: "无法连接更新服务"}"
            })
        }
        return null
    }

    private fun checkSite(packageName: String, current: Long): AppUpdate {
        val json = fetchJson(SITE_MANIFEST_URL)
        if (json.optString("app_id") != "medical-record") error("更新信息中的应用 ID 不匹配")
        val version = json.optString("version_name")
        if (!version.matches(Regex("\\d+\\.\\d+\\.\\d+"))) error("更新信息中的版本名称无效")
        val expectedDownloadUrl = "$SITE_DOWNLOAD_BASE/medical-record-v$version.apk"
        if (json.optString("download_url") != expectedDownloadUrl) error("更新信息中的下载地址无效")
        return parseManifest(json, packageName, current)
    }

    private fun checkGitee(packageName: String, current: Long): AppUpdate {
        val release = fetchJson(MANIFEST_URL)
        val tag = release.optString("tag_name")
        if (!tag.matches(Regex("v\\d+\\.\\d+\\.\\d+"))) error("Gitee 发行版标签无效")
        val assets = release.optJSONArray("assets") ?: error("Gitee 发行版缺少附件")
        val manifestUrl = "$RELEASE_DOWNLOAD_BASE/$tag/update.json"
        if (!assets.containsDownload("update.json", manifestUrl)) error("Gitee 发行版缺少更新信息附件")
        val json = fetchJson(manifestUrl)
        if (json.optString("version_name") != tag.removePrefix("v")) error("更新信息与发行版标签不匹配")
        val downloadUrl = json.optString("download_url").takeIf(String::isNotBlank) ?: error("更新信息缺少下载地址")
        val apkName = "medical-record-$tag.apk"
        val expectedDownloadUrl = "$RELEASE_DOWNLOAD_BASE/$tag/$apkName"
        if (downloadUrl != expectedDownloadUrl || !assets.containsDownload(apkName, downloadUrl)) {
            error("更新信息中的下载地址无效")
        }
        return parseManifest(json, packageName, current)
    }

    private fun parseManifest(json: JSONObject, packageName: String, current: Long): AppUpdate {
        if (json.optString("package_name") != packageName) error("更新信息中的应用标识不匹配")
        val versionCode = json.optLong("version_code", -1)
        if (versionCode < 1) error("更新信息中的版本号无效")
        val downloadUrl = json.optString("download_url")
        val sha256 = json.optString("sha256").lowercase()
        if (!sha256.matches(Regex("[a-f0-9]{64}"))) error("更新信息中的安装包校验值无效")
        val minimum = json.optLong("minimum_supported_version_code", 1)
        return AppUpdate(
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
    }

    private fun JSONArray.containsDownload(name: String, url: String): Boolean =
        (0 until length()).any { index ->
            optJSONObject(index)?.let { it.optString("name") == name && it.optString("browser_download_url") == url } == true
        }

    private fun fetchJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
        }
        return try {
            if (connection.responseCode !in 200..299) error("更新服务返回 HTTP ${connection.responseCode}")
            JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    fun download(
        context: Context,
        update: AppUpdate,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
        onVerifying: () -> Unit = {}
    ): File {
        val directory = File(context.cacheDir, "app-updates").apply { mkdirs() }
        val temporary = File(directory, "medical-record.apk.tmp")
        val apk = File(directory, "medical-record.apk")
        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) error("下载失败（HTTP ${connection.responseCode}）")
            val size = connection.contentLengthLong
            if (size > MAX_APK_BYTES) error("安装包超过 150 MB，已停止下载")
            val knownSize = size.takeIf { it > 0 }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var lastUpdate = SystemClock.elapsedRealtime()
            onProgress(0, knownSize)
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
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUpdate >= 150) {
                            onProgress(total, knownSize)
                            lastUpdate = now
                        }
                    }
                }
            }
            onProgress(total, knownSize)
            onVerifying()
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
