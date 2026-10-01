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
import androidx.compose.material3.LinearProgressIndicator
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
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val MAX_IMPORT_CONCURRENCY = 10

private data class UpdateDownloadProgress(val downloadedBytes: Long, val totalBytes: Long?, val verifying: Boolean = false)

private fun formatDownloadSize(bytes: Long): String = String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0)

private data class DuplicateReview(
    val index: Int,
    val newReport: Report,
    val existingReports: List<Report>
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
    private var importWorkers: ExecutorService = Executors.newFixedThreadPool(5)
    private var availableUpdate by mutableStateOf<AppUpdate?>(null)
    private var updateDownloading by mutableStateOf(false)
    private var updateProgress by mutableStateOf<UpdateDownloadProgress?>(null)
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
        importWorkers.shutdownNow()
        super.onDestroy()
    }

    @Composable
    private fun App() {
        var reports by remember { mutableStateOf(db.reports()) }
        var observations by remember { mutableStateOf(db.observations()) }
        var stages by remember { mutableStateOf(db.stages()) }
        var categories by remember { mutableStateOf(db.categories()) }
        var tab by remember { mutableStateOf(0) }
        var dataPage by remember { mutableStateOf(false) }
        var dataPreview by remember { mutableStateOf<TransferPreview?>(null) }
        var dataBusy by remember { mutableStateOf(false) }
        var dataMessage by remember { mutableStateOf("") }
        var dataWorkingMessage by remember { mutableStateOf("") }
        var lastBackupAt by remember { mutableStateOf(getSharedPreferences("data-backup", MODE_PRIVATE).getLong("last_export", 0L)) }
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
        var importItems by remember { mutableStateOf<List<ImportImageItem>>(emptyList()) }
        var importSources by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var importVisible by remember { mutableStateOf(false) }
        var importConcurrency by remember {
            mutableStateOf(getSharedPreferences("image-import", MODE_PRIVATE).getInt("concurrency", 5).coerceIn(1, MAX_IMPORT_CONCURRENCY))
        }
        val pendingResults = remember { mutableMapOf<Int, RecognizedReport>() }
        val pendingMatches = remember { mutableMapOf<Int, List<Report>>() }
        val skippedPaths = remember { mutableListOf<String>() }
        var duplicateReview by remember { mutableStateOf<DuplicateReview?>(null) }
        val currentVersion = remember { packageManager.getPackageInfo(packageName, 0).versionName ?: "未知" }

        fun goBack() {
            when {
                dataPage -> dataPage = false
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
        BackHandler(dataPage || selectedReport != null || selectedIndicator != null || selectedCategory != null || tab != 0) { goBack() }

        fun refresh() {
            reports = db.reports()
            observations = db.observations()
            stages = db.stages()
            categories = db.categories()
        }

        fun finishImportIfReady() {
            if (importItems.isEmpty() || importItems.any { it.status == ImportImageStatus.Waiting || it.status == ImportImageStatus.Working || it.status == ImportImageStatus.Duplicate }) return
            busy = false
            val imported = importItems.count { it.status == ImportImageStatus.Imported }
            val skipped = importItems.count { it.status == ImportImageStatus.Skipped }
            val failed = importItems.count { it.status == ImportImageStatus.Failed }
            notice = "已导入 $imported/${importItems.size} 张。" +
                (if (skipped > 0) "跳过重复图片 $skipped 张。" else "") +
                (if (failed > 0) "失败 $failed 张，可在导入面板中重试。" else if (imported > 0) "请逐份校对。" else "")
        }

        fun updateImportItem(index: Int, change: (ImportImageItem) -> ImportImageItem) {
            importItems = importItems.map { if (it.index == index) change(it) else it }
            finishImportIfReady()
        }

        fun recognizeImportItem(index: Int, source: Uri, apiKey: String) {
            importWorkers.execute {
                var path = importItems.firstOrNull { it.index == index }?.path.orEmpty()
                try {
                    if (path.isBlank()) {
                        runOnUiThread { updateImportItem(index) { it.copy(status = ImportImageStatus.Working, stage = "读取图片") } }
                        path = copyImage(this, source)
                    }
                    val imagePath = path
                    runOnUiThread { updateImportItem(index) { it.copy(path = imagePath, status = ImportImageStatus.Working, stage = "准备识别") } }
                    val recognized = recognizeImage(imagePath, apiKey,
                        onStage = { stage -> runOnUiThread { updateImportItem(index) { it.copy(stage = stage) } } },
                        onStream = { stream -> runOnUiThread { updateImportItem(index) { it.copy(stream = stream) } } })
                    runOnUiThread {
                        try {
                            val matches = matchingReports(recognized.report, db.reports() + pendingResults.values.map { it.report })
                            if (matches.isNotEmpty()) {
                                pendingResults[index] = recognized
                                pendingMatches[index] = matches
                                updateImportItem(index) { it.copy(status = ImportImageStatus.Duplicate, stage = "待确认重复报告", itemCount = recognized.observations.size) }
                            } else {
                                val id = db.addReport(recognized.report, recognized.observations)
                                reviewQueue = reviewQueue + id
                                refresh()
                                updateImportItem(index) { it.copy(status = ImportImageStatus.Imported, stage = "已导入，待校对", reportId = id, itemCount = recognized.observations.size) }
                            }
                        } catch (error: Exception) {
                            updateImportItem(index) { it.copy(status = ImportImageStatus.Failed, stage = "导入失败", error = error.message?.take(250) ?: "未知错误") }
                        }
                    }
                } catch (error: Exception) {
                    val failedPath = path
                    runOnUiThread {
                        updateImportItem(index) { it.copy(path = failedPath, status = ImportImageStatus.Failed, stage = "识别失败", error = error.message?.take(250) ?: "未知错误") }
                    }
                }
            }
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
            importItems.filter { it.status == ImportImageStatus.Failed }.forEach { item ->
                if (item.path.isNotBlank()) File(item.path).delete()
            }
            skippedPaths.forEach { File(it).delete() }
            pendingResults.clear()
            pendingMatches.clear()
            skippedPaths.clear()
            importSources = uris.take(10)
            importItems = importSources.mapIndexed { index, _ -> ImportImageItem(index) }
            importVisible = true
            importWorkers.shutdown()
            importWorkers = Executors.newFixedThreadPool(minOf(importConcurrency, importSources.size))
            importSources.forEachIndexed { index, uri -> recognizeImportItem(index, uri, apiKey) }
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

        fun performDataWork(stage: String, work: () -> String, onSuccess: () -> Unit = {}) {
            if (dataBusy) return
            dataBusy = true
            dataWorkingMessage = stage
            dataMessage = ""
            Thread {
                val result = runCatching(work)
                runOnUiThread {
                    dataBusy = false
                    result.onSuccess { message -> onSuccess(); dataMessage = message }
                        .onFailure { error -> dataMessage = error.message ?: "数据处理失败" }
                }
            }.start()
        }

        fun previewData(uri: Uri, kind: TransferKind) {
            performDataWork("正在检查文件…", {
                val preview = DataTransfer.preview(this, db, uri, kind)
                runOnUiThread { dataPreview = preview }
                "已读取文件，请确认导入方式。"
            })
        }

        val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) performDataWork("正在生成包含原图的备份…", {
                DataTransfer.exportBackup(this, db, uri)
                "完整备份已保存。请确认文件位于卸载应用后仍可访问的位置。"
            }, onSuccess = {
                lastBackupAt = System.currentTimeMillis()
                getSharedPreferences("data-backup", MODE_PRIVATE).edit().putLong("last_export", lastBackupAt).apply()
            })
        }
        val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) previewData(uri, TransferKind.Backup)
        }
        val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) performDataWork("正在导出指标表格…", {
                DataTransfer.exportCsv(this, db, uri)
                "指标表格已导出。"
            })
        }
        val importCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) previewData(uri, TransferKind.Csv)
        }

        fun applyDataPreview(replace: Boolean) {
            val preview = dataPreview ?: return
            performDataWork(if (replace) "正在替换本机数据…" else "正在追加导入…", {
                val result = DataTransfer.import(this, db, preview.uri, preview.kind, replace)
                "已导入 ${result.reportsAdded} 份报告、${result.stagesAdded} 个阶段；跳过 ${result.reportsSkipped} 份相同报告。"
            }, onSuccess = {
                dataPreview = null
                reviewQueue = emptyList()
                importItems = emptyList()
                importSources = emptyList()
                importVisible = false
                refresh()
            })
        }

        Scaffold(floatingActionButton = {
            if (importItems.isNotEmpty() && !importVisible && duplicateReview == null) {
                ImportBatchBanner(importItems) { importVisible = true }
            }
        }, bottomBar = {
            if (selectedReport == null && selectedIndicator == null && !dataPage) {
                NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                    listOf("首页", "报告", "阶段", "设置").forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index; dataPage = false; if (index != 1) selectedCategory = null },
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
                        onStages = { tab = 2 })
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
                    else -> if (dataPage) DataManagementScreen(
                        reports.size, stages.size, lastBackupAt, dataMessage,
                        onBack = { dataPage = false },
                        onExportBackup = {
                            if (busy) dataMessage = "请等待图片识别完成后再备份。"
                            else exportBackup.launch("medical-record-backup-${LocalDate.now()}.zip")
                        },
                        onRestoreBackup = {
                            if (busy) dataMessage = "请等待图片识别完成后再恢复。"
                            else restoreBackup.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                        },
                        onImportCsv = {
                            if (busy) dataMessage = "请等待图片识别完成后再导入。"
                            else importCsv.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/octet-stream"))
                        },
                        onExportCsv = {
                            if (busy) dataMessage = "请等待图片识别完成后再导出。"
                            else exportCsv.launch("medical-record-indicators-${LocalDate.now()}.csv")
                        }
                    ) else SettingsScreen(hasKey, currentVersion, updateChecking, updateCheckStatus, importConcurrency,
                        onImportConcurrencyChange = { count ->
                            importConcurrency = count.coerceIn(1, MAX_IMPORT_CONCURRENCY)
                            getSharedPreferences("image-import", MODE_PRIVATE).edit().putInt("concurrency", importConcurrency).apply()
                        },
                        onCheckUpdate = { checkForUpdates(manual = true) }, onDataManagement = { dataPage = true },
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
        dataPreview?.let { preview ->
            TransferPreviewDialog(preview, dataMessage, onCancel = { dataPreview = null },
                onAppend = { applyDataPreview(false) }, onReplace = { applyDataPreview(true) })
        }
        if (dataBusy) TransferWorkingDialog(dataWorkingMessage)
        if (duplicateReview == null) progress?.let { RecognitionProgressCard(it) { progress = null } }
        if (importVisible && duplicateReview == null && importItems.isNotEmpty()) {
            ImportBatchCard(
                importItems,
                onCollapse = { importVisible = false },
                onClose = {
                    importItems.filter { it.status == ImportImageStatus.Failed }.forEach { item ->
                        if (item.path.isNotBlank()) File(item.path).delete()
                    }
                    skippedPaths.forEach { File(it).delete() }
                    skippedPaths.clear()
                    importItems = emptyList()
                    importSources = emptyList()
                    importVisible = false
                },
                onReviewDuplicate = { index ->
                    val result = pendingResults[index]
                    val matches = pendingMatches[index]
                    if (result != null && matches != null) duplicateReview = DuplicateReview(index, result.report, matches)
                },
                onRetry = { index ->
                    val source = importSources.getOrNull(index)
                    if (source != null) {
                        busy = true
                        updateImportItem(index) { it.copy(status = ImportImageStatus.Waiting, stage = "等待重试", stream = "", error = "", itemCount = 0) }
                        recognizeImportItem(index, source, keyStore.read())
                    }
                },
                onReviewReports = {
                    val id = reviewQueue.firstOrNull() ?: importItems.firstNotNullOfOrNull { it.reportId }
                    if (id != null) {
                        tab = 1
                        categoryView = "报告"
                        selectedReport = id
                        selectedCategory = reports.firstOrNull { it.id == id }?.category
                        importVisible = false
                    }
                }
            )
        }
        duplicateReview?.let { review ->
            DuplicateReportReview(review.newReport, review.existingReports) { shouldImport ->
                val result = pendingResults.remove(review.index)
                pendingMatches.remove(review.index)
                if (result != null) {
                    if (shouldImport) {
                        runCatching { db.addReport(result.report, result.observations) }
                            .onSuccess { id ->
                                reviewQueue = reviewQueue + id
                                refresh()
                                updateImportItem(review.index) { it.copy(status = ImportImageStatus.Imported, stage = "已导入，待校对", reportId = id) }
                            }.onFailure { error ->
                                updateImportItem(review.index) { it.copy(status = ImportImageStatus.Failed, stage = "导入失败", error = error.message?.take(250) ?: "未知错误") }
                            }
                    } else {
                        skippedPaths.add(result.report.imagePath)
                        updateImportItem(review.index) { it.copy(status = ImportImageStatus.Skipped, stage = "已跳过重复图片") }
                    }
                }
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(update.releaseNotes.joinToString(separator = "\n") { "•  $it" })
                        Text("请直接覆盖安装；卸载旧版会删除本机保存的报告和 API Key。", style = MaterialTheme.typography.bodySmall)
                        if (updateDownloading) {
                            val progress = updateProgress
                            when {
                                progress == null -> {
                                    Text("正在连接下载服务…")
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                progress.verifying -> {
                                    Text("下载完成，正在校验安装包…")
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                                else -> {
                                    val total = progress.totalBytes
                                    if (total != null) {
                                        val fraction = (progress.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
                                        Text("正在下载：${formatDownloadSize(progress.downloadedBytes)} / ${formatDownloadSize(total)}（${(fraction * 100).toInt()}%）")
                                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                                    } else {
                                        Text("正在下载：已接收 ${formatDownloadSize(progress.downloadedBytes)}")
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                        if (updateMessage.isNotBlank()) Text(updateMessage)
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
                            updateProgress = null
                            updateMessage = ""
                            Thread {
                                val result = runCatching {
                                    AppUpdateService.download(this@MainActivity, update,
                                        onProgress = { downloaded, total ->
                                            runOnUiThread { updateProgress = UpdateDownloadProgress(downloaded, total) }
                                        },
                                        onVerifying = {
                                            runOnUiThread { updateProgress = updateProgress?.copy(verifying = true) }
                                        })
                                }
                                runOnUiThread {
                                    updateDownloading = false
                                    updateProgress = null
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
        updateProgress = null
    }
}
