package com.example.medicalrecord

import android.net.Uri
import android.os.Bundle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue

private data class DuplicateReview(
    val newReport: Report,
    val existingReports: List<Report>,
    val decision: ArrayBlockingQueue<Boolean>
)

private fun matchingReports(newReport: Report, existing: List<Report>): List<Report> {
    val date = newReport.sampleDate.ifBlank { newReport.reportDate }
    if (!validDate(date)) return emptyList()
    return existing.filter { report ->
        report.category.trim() == newReport.category.trim() &&
            report.sampleDate.ifBlank { report.reportDate } == date
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var db: RecordDb
    @Volatile private var pendingDuplicateDecision: ArrayBlockingQueue<Boolean>? = null
    private var availableUpdate by mutableStateOf<AppUpdate?>(null)
    private var updateDownloading by mutableStateOf(false)
    private var updateMessage by mutableStateOf("")
    private var updateChecking by mutableStateOf(false)
    private var updateCheckStatus by mutableStateOf("打开应用时会自动检查更新")
    private var pendingInstallerFile by mutableStateOf<File?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = RecordDb(this)
        setContent { MedicalRecordTheme { App() } }
        checkForUpdates(manual = false)
    }

    private fun checkForUpdates(manual: Boolean) {
        if (updateChecking) return
        updateChecking = true
        updateCheckStatus = "正在检查更新…"
        Thread {
            val result = runCatching { AppUpdateService.check(this) }
            runOnUiThread {
                updateChecking = false
                result.onSuccess { update ->
                    if (update == null) {
                        updateCheckStatus = "已是最新版本"
                    } else {
                        updateCheckStatus = "发现新版本 ${update.versionName}"
                        val dismissed = getSharedPreferences("app-updates", MODE_PRIVATE)
                            .getLong("dismissed_version", 0L)
                        if (manual || update.forceUpdate || dismissed < update.versionCode) availableUpdate = update
                    }
                }.onFailure { error ->
                    updateCheckStatus = "检查失败：${error.message ?: "无法连接更新服务"}"
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        val apk = pendingInstallerFile ?: return
        if (AppUpdateService.canInstallPackages(this)) {
            pendingInstallerFile = null
            runCatching { AppUpdateService.install(this, apk) }
                .onFailure { updateMessage = it.message ?: "无法打开系统安装程序" }
        }
    }

    override fun onDestroy() {
        pendingDuplicateDecision?.offer(false)
        super.onDestroy()
    }

    @Composable
    private fun App() {
        var reports by remember { mutableStateOf(db.reports()) }
        var observations by remember { mutableStateOf(db.observations()) }
        var stages by remember { mutableStateOf(db.stages()) }
        var categories by remember { mutableStateOf(db.categories()) }
        var tab by remember { mutableStateOf(0) }
        var selectedCategory by remember { mutableStateOf<String?>(null) }
        var categoryView by remember { mutableStateOf("报告") }
        var selectedReport by remember { mutableStateOf<Long?>(null) }
        var selectedIndicator by remember { mutableStateOf<String?>(null) }
        var reportOriginIndicator by remember { mutableStateOf<String?>(null) }
        var reviewQueue by remember { mutableStateOf<List<Long>>(emptyList()) }
        var busy by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf("") }
        var cameraFile by remember { mutableStateOf<File?>(null) }
        val keyStore = remember { ApiKeyStore(this) }
        var hasKey by remember { mutableStateOf(keyStore.read().isNotBlank()) }
        var pendingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var progress by remember { mutableStateOf<RecognitionProgress?>(null) }
        var duplicateReview by remember { mutableStateOf<DuplicateReview?>(null) }
        val currentVersion = remember { packageManager.getPackageInfo(packageName, 0).versionName ?: "未知" }

        fun goBack() {
            when {
                selectedReport != null -> {
                    selectedReport = null
                    selectedIndicator = reportOriginIndicator
                    reportOriginIndicator = null
                }
                selectedIndicator != null -> selectedIndicator = null
                tab == 3 -> {
                    pendingUris = emptyList()
                    tab = if (selectedCategory != null) 1 else 0
                }
                selectedCategory != null -> selectedCategory = null
                tab != 0 -> {
                    tab = 0
                }
            }
        }
        BackHandler(selectedReport != null || selectedIndicator != null || selectedCategory != null || tab != 0) { goBack() }

        fun refresh() {
            reports = db.reports()
            observations = db.observations()
            stages = db.stages()
            categories = db.categories()
        }

        fun importUris(uris: List<Uri>) {
            if (uris.isEmpty() || busy) return
            val apiKey = keyStore.read()
            if (apiKey.isBlank()) {
                pendingUris = uris
                tab = 3
                notice = "请先在设置中填写 API Key，保存后会继续识别所选图片。"
                return
            }
            busy = true
            notice = "正在识别 ${uris.size} 张报告…"
            progress = RecognitionProgress(1, uris.size, 0, "准备图片")
            Thread {
                var succeeded = 0
                var skipped = 0
                val importedIds = mutableListOf<Long>()
                val failures = mutableListOf<String>()
                uris.forEachIndexed { index, uri ->
                    var path = ""
                    try {
                        runOnUiThread { progress = RecognitionProgress(index + 1, uris.size, index, "读取图片") }
                        path = copyImage(this, uri)
                        val recognized = recognizeImage(path, apiKey,
                            onStage = { stage -> runOnUiThread { progress = progress?.copy(stage = stage) } },
                            onStream = { stream -> runOnUiThread { progress = progress?.copy(stream = stream) } })
                        val matches = matchingReports(recognized.report, db.reports())
                        val shouldImport = if (matches.isEmpty()) true else {
                            val decision = ArrayBlockingQueue<Boolean>(1)
                            pendingDuplicateDecision = decision
                            runOnUiThread {
                                progress = progress?.copy(stage = "发现同日同类报告，等待图片核对")
                                duplicateReview = DuplicateReview(recognized.report, matches, decision)
                            }
                            try { decision.take() } finally { pendingDuplicateDecision = null }
                        }
                        if (shouldImport) {
                            importedIds.add(db.addReport(recognized.report, recognized.observations))
                            succeeded++
                        } else {
                            File(path).delete()
                            skipped++
                        }
                    } catch (error: Exception) {
                        if (path.isNotEmpty()) {
                            db.addReport(Report(0, "其他", "", "", "", path, "识别失败", ""), emptyList())
                        }
                        val message = error.message?.take(250) ?: "未知错误"
                        failures.add("第 ${index + 1} 张：$message")
                        runOnUiThread { progress = progress?.copy(error = message) }
                    }
                    runOnUiThread { progress = progress?.copy(completed = index + 1, stage = "已处理 ${index + 1}/${uris.size} 张") }
                }
                runOnUiThread {
                    refresh()
                    busy = false
                    tab = 1
                    categoryView = "报告"
                    reviewQueue = importedIds
                    selectedReport = importedIds.firstOrNull()
                    selectedCategory = reports.firstOrNull { it.id == selectedReport }?.category
                    notice = "已导入 $succeeded/${uris.size} 张。" +
                        (if (skipped > 0) "跳过重复图片 $skipped 张。" else "") +
                        (if (failures.isEmpty()) { if (succeeded > 0) "请逐份校对。" else "" } else "失败 ${failures.size} 张，详情见识别进度。")
                    progress = progress?.copy(stage = notice, error = failures.joinToString("；"), active = false)
                }
            }.start()
        }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> importUris(uris) }
        val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) cameraFile?.let { importUris(listOf(FileProvider.getUriForFile(this, "$packageName.files", it))) }
        }
        fun takePhoto() {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            cameraFile = file
            camera.launch(FileProvider.getUriForFile(this, "$packageName.files", file))
        }
        fun selectPhotos() {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        Scaffold(bottomBar = {
            if (selectedReport == null && selectedIndicator == null && tab != 3) {
                NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                    listOf("首页", "报告", "阶段").forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index; if (index != 1) selectedCategory = null },
                            icon = { NavigationGlyph(index, tab == index) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MedicalPalette.Teal,
                                selectedTextColor = MedicalPalette.Teal,
                                indicatorColor = MedicalPalette.Mint,
                                unselectedIconColor = MedicalPalette.Muted,
                                unselectedTextColor = MedicalPalette.Muted
                            )
                        )
                    }
                }
            }
        }, containerColor = MedicalPalette.Canvas) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selectedReport != null) {
                    val report = reports.firstOrNull { it.id == selectedReport }
                    if (report != null) ReportDetail(
                        report, observations.filter { it.reportId == report.id }, categories,
                        onBack = ::goBack,
                        onSaveReport = {
                            db.updateReport(it)
                            refresh()
                            if (reportOriginIndicator == null && tab == 1) selectedCategory = it.category
                            if (it.status == "已确认" && it.id in reviewQueue) {
                                reviewQueue = reviewQueue.filterNot { id -> id == it.id }
                                selectedReport = reviewQueue.firstOrNull()
                                if (selectedReport != null) selectedCategory = reports.firstOrNull { item -> item.id == selectedReport }?.category
                                if (selectedReport == null) notice = "全部校对完成，可在报告分类中查看指标趋势。"
                            }
                        },
                        onSaveObservation = { db.updateObservation(it); db.updateReport(report.copy(status = "待校对")); refresh() },
                        onAddObservation = { db.addObservation(report.id, it); db.updateReport(report.copy(status = "待校对")); refresh() },
                        onDeleteObservation = { db.deleteObservation(it); db.updateReport(report.copy(status = "待校对")); refresh() },
                        onDeleteReport = {
                            db.deleteReport(report.id)
                            File(report.imagePath).delete()
                            reviewQueue = reviewQueue.filterNot { it == report.id }
                            selectedReport = reviewQueue.firstOrNull()
                            refresh()
                        },
                        onAddCategory = { name -> db.addCategory(name); refresh() },
                        onRetry = {
                            if (busy) return@ReportDetail
                            val apiKey = keyStore.read()
                            if (apiKey.isBlank()) {
                                notice = "请先在设置中填写 API Key"
                                selectedReport = null
                                tab = 3
                                return@ReportDetail
                            }
                            busy = true
                            notice = "正在重新识别…"
                            progress = RecognitionProgress(1, 1, 0, "准备图片")
                            Thread {
                                try {
                                    val result = recognizeImage(report.imagePath, apiKey,
                                        onStage = { stage -> runOnUiThread { progress = progress?.copy(stage = stage) } },
                                        onStream = { stream -> runOnUiThread { progress = progress?.copy(stream = stream) } })
                                    db.updateReport(report.copy(type = result.report.type, sampleDate = result.report.sampleDate, reportDate = result.report.reportDate, institution = result.report.institution, status = "待校对", suggestedCategory = result.report.suggestedCategory))
                                    db.replaceObservations(report.id, result.observations)
                                    runOnUiThread { refresh(); notice = "重新识别完成，请校对新增项目。"; busy = false; progress = progress?.copy(completed = 1, stage = notice, active = false) }
                                } catch (error: Exception) {
                                    runOnUiThread { notice = "重新识别失败"; busy = false; progress = progress?.copy(stage = notice, error = error.message ?: "未知错误", active = false) }
                                }
                            }.start()
                        }
                    ) else selectedReport = null
                } else if (selectedIndicator != null) {
                    TrendDetail(
                        selectedIndicator!!, observations, reports, stages,
                        onBack = ::goBack,
                        onOpenReport = { reportOriginIndicator = selectedIndicator; selectedReport = it; selectedIndicator = null },
                        onStages = { selectedIndicator = null; selectedCategory = null; tab = 2 },
                        sourceCategory = selectedCategory
                    )
                } else when (tab) {
                    0 -> Overview(reports, observations, stages, busy, notice, hasKey,
                        ::selectPhotos, ::takePhoto, { id -> tab = 1; categoryView = "报告"; selectedReport = id; selectedCategory = reports.firstOrNull { it.id == id }?.category }, { selectedIndicator = it },
                        onReports = { tab = 1; selectedCategory = null },
                        onTrends = {
                            tab = 1
                            selectedCategory = reports.firstOrNull { report ->
                                report.status == "已确认" && observations.any { it.reportId == report.id && it.number() != null }
                            }?.category
                            categoryView = "趋势"
                        },
                        onStages = { tab = 2 }, onSettings = { tab = 3 })
                    1 -> if (selectedCategory == null) ReportsScreen(
                        reports, observations, categories, busy, notice, ::selectPhotos, ::takePhoto,
                        onOpenCategory = { selectedCategory = it; categoryView = "报告" },
                        onAddCategory = { db.addCategory(it); refresh() },
                        onManual = {
                            val today = java.time.LocalDate.now().toString()
                            val category = "手工录入"
                            val id = db.addReport(Report(0, category, today, today, "", "", "待校对", "手工"), emptyList())
                            refresh(); categoryView = "报告"; selectedCategory = category; selectedReport = id
                        }
                    ) else CategoryReportsScreen(
                        selectedCategory!!, reports, observations, categoryView,
                        onBack = { selectedCategory = null },
                        onOpen = { selectedReport = it },
                        onViewChange = { categoryView = it },
                        onOpenTrend = { selectedIndicator = it; categoryView = "趋势" },
                        onRename = { name ->
                            db.renameCategory(selectedCategory!!, name)
                            selectedCategory = name
                            refresh()
                        },
                        onDeleteEmpty = { db.deleteEmptyCategory(selectedCategory!!); selectedCategory = null; refresh() }
                    )
                    2 -> StagesScreen(stages,
                        onAdd = { db.addStage(it); refresh() },
                        onUpdate = { db.updateStage(it); refresh() },
                        onDelete = { db.deleteStage(it); refresh() })
                    else -> SettingsScreen(hasKey, currentVersion, updateChecking, updateCheckStatus, onCheckUpdate = { checkForUpdates(manual = true) }, onBack = ::goBack,
                        onSave = { key ->
                            keyStore.save(key)
                            hasKey = true
                            notice = "API Key 已保存"
                            if (pendingUris.isNotEmpty()) {
                                val selected = pendingUris
                                pendingUris = emptyList()
                                importUris(selected)
                            }
                        },
                        onClear = { keyStore.clear(); hasKey = false; notice = "API Key 已清除" })
                }
            }
        }
        if (duplicateReview == null) progress?.let { RecognitionProgressCard(it) { progress = null } }
        duplicateReview?.let { review ->
            DuplicateReportReview(review.newReport, review.existingReports) { shouldImport ->
                review.decision.offer(shouldImport)
                duplicateReview = null
            }
        }
        availableUpdate?.let { update ->
            AlertDialog(
                onDismissRequest = {
                    if (!update.forceUpdate && !updateDownloading) dismissUpdate(update.versionCode)
                },
                title = { Text("发现新版本 ${update.versionName}") },
                text = {
                    Column {
                        Text(update.releaseNotes.joinToString(separator = "\n") { "•  $it" })
                        if (updateDownloading) Text("\n正在下载并校验安装包…")
                        if (updateMessage.isNotBlank()) Text("\n$updateMessage")
                    }
                },
                confirmButton = {
                    Button(enabled = !updateDownloading, onClick = {
                        val cached = pendingInstallerFile
                        if (cached != null) {
                            if (AppUpdateService.canInstallPackages(this@MainActivity)) {
                                pendingInstallerFile = null
                                runCatching { AppUpdateService.install(this@MainActivity, cached) }
                                    .onFailure { updateMessage = it.message ?: "无法打开系统安装程序" }
                            } else {
                                updateMessage = "请在系统设置中允许本 APP 安装未知应用，返回后会继续安装。"
                                AppUpdateService.requestInstallPermission(this@MainActivity)
                            }
                        } else {
                            updateDownloading = true
                            updateMessage = ""
                            Thread {
                                val result = runCatching { AppUpdateService.download(this@MainActivity, update) }
                                runOnUiThread {
                                    updateDownloading = false
                                    result.onSuccess { apk ->
                                        pendingInstallerFile = apk
                                        if (AppUpdateService.canInstallPackages(this@MainActivity)) {
                                            pendingInstallerFile = null
                                            runCatching { AppUpdateService.install(this@MainActivity, apk) }
                                                .onFailure { updateMessage = it.message ?: "无法打开系统安装程序" }
                                        } else {
                                            updateMessage = "下载与校验完成。请授权安装，返回后即可继续。"
                                            AppUpdateService.requestInstallPermission(this@MainActivity)
                                        }
                                    }.onFailure { error ->
                                        updateMessage = error.message ?: "下载更新失败，请稍后重试。"
                                    }
                                }
                            }.start()
                        }
                    }) {
                        Text(if (updateDownloading) "正在下载…" else if (pendingInstallerFile != null) "继续安装" else "立即更新")
                    }
                },
                dismissButton = if (update.forceUpdate) null else ({
                    TextButton(enabled = !updateDownloading, onClick = { dismissUpdate(update.versionCode) }) {
                        Text("稍后")
                    }
                })
            )
        }
    }

    private fun dismissUpdate(versionCode: Long) {
        getSharedPreferences("app-updates", MODE_PRIVATE).edit()
            .putLong("dismissed_version", versionCode).apply()
        availableUpdate = null
        updateMessage = ""
    }
}
