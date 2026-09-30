package com.example.medicalrecord

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

data class ImportImageItem(
    val index: Int,
    val path: String = "",
    val stage: String = "等待处理",
    val stream: String = "",
    val error: String = "",
    val status: ImportImageStatus = ImportImageStatus.Waiting,
    val reportId: Long? = null,
    val itemCount: Int = 0
)

enum class ImportImageStatus { Waiting, Working, Duplicate, Imported, Skipped, Failed }

private val finishedImportStatuses = setOf(ImportImageStatus.Imported, ImportImageStatus.Skipped, ImportImageStatus.Failed)

@Composable
fun ImportBatchBanner(items: List<ImportImageItem>, onOpen: () -> Unit) {
    if (items.isEmpty()) return
    val finished = items.count { it.status in finishedImportStatuses }
    val duplicates = items.count { it.status == ImportImageStatus.Duplicate }
    val label = if (finished == items.size) "图片导入已结束 · 查看结果" else "图片导入 $finished/${items.size}" +
        if (duplicates > 0) " · $duplicates 张待确认重复" else " · 点击查看"
    Surface(Modifier.clickable(onClick = onOpen), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 4.dp) {
        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
fun ImportBatchCard(
    items: List<ImportImageItem>,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    onReviewDuplicate: (Int) -> Unit,
    onRetry: (Int) -> Unit,
    onReviewReports: () -> Unit
) {
    var expanded by remember { mutableStateOf<Int?>(null) }
    val finished = items.count { it.status in finishedImportStatuses }
    val working = items.count { it.status == ImportImageStatus.Working }
    val duplicates = items.count { it.status == ImportImageStatus.Duplicate }
    val waiting = items.count { it.status == ImportImageStatus.Waiting }
    val failed = items.count { it.status == ImportImageStatus.Failed }
    Dialog(onDismissRequest = { if (finished == items.size) onClose() else onCollapse() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("图片导入", style = MaterialTheme.typography.titleLarge)
                Text("已处理 $finished/${items.size} · 识别中 $working · 等待 $waiting" +
                    (if (duplicates > 0) " · 待确认重复 $duplicates" else "") +
                    (if (failed > 0) " · 失败 $failed" else ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { item ->
                        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth().clickable { expanded = if (expanded == item.index) null else item.index }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ImportThumbnail(item.path)
                                    Column(Modifier.weight(1f)) {
                                        Text("第 ${item.index + 1} 张 · ${item.stage}", style = MaterialTheme.typography.bodyMedium)
                                        if (item.itemCount > 0) Text("识别到 ${item.itemCount} 个指标", style = MaterialTheme.typography.bodySmall)
                                        if (item.error.isNotBlank()) Text(item.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        if (item.stream.isNotBlank()) Text(if (expanded == item.index) "收起模型响应" else "查看模型响应", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                if (expanded == item.index && item.stream.isNotBlank()) {
                                    ImportStreamText(item.stream)
                                }
                                if (item.status == ImportImageStatus.Duplicate) {
                                    TextButton(onClick = { onReviewDuplicate(item.index) }) { Text("核对重复报告") }
                                }
                                if (item.status == ImportImageStatus.Failed) {
                                    TextButton(onClick = { onRetry(item.index) }) { Text("重试这张") }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (items.any { it.reportId != null }) TextButton(onClick = onReviewReports) { Text("去校对") }
                    TextButton(onClick = { if (finished == items.size) onClose() else onCollapse() }) {
                        Text(if (finished == items.size) "关闭" else "收起")
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportStreamText(stream: String) {
    val scroll = rememberScrollState()
    LaunchedEffect(stream, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Text(stream, Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(scroll), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ImportThumbnail(path: String) {
    val bitmap = remember(path) {
        if (path.isBlank() || !File(path).exists()) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 128) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    if (bitmap == null) Text("图片", Modifier.size(52.dp), style = MaterialTheme.typography.labelSmall)
    else Image(bitmap, "报告图片", Modifier.size(52.dp), contentScale = ContentScale.Crop)
}

data class RecognitionProgress(
    val current: Int,
    val total: Int,
    val completed: Int,
    val stage: String,
    val stream: String = "",
    val error: String = "",
    val active: Boolean = true
)

@Composable
fun RecognitionProgressCard(progress: RecognitionProgress, onDismiss: () -> Unit) {
    val streamScroll = rememberScrollState()
    LaunchedEffect(progress.current, progress.stream, streamScroll.maxValue) {
        streamScroll.scrollTo(streamScroll.maxValue)
    }
    AlertDialog(
        onDismissRequest = { if (!progress.active) onDismiss() },
        title = { Text("识别进度 · ${progress.current}/${progress.total} 张") },
        text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(progress = { progress.completed.toFloat() / progress.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            Text(progress.stage, color = MaterialTheme.colorScheme.primary)
            if (progress.active) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (progress.error.isNotBlank()) Text(progress.error, color = MaterialTheme.colorScheme.error)
            if (progress.stream.isNotBlank()) {
                Text("模型实时返回（原始内容）", style = MaterialTheme.typography.labelLarge)
                Text(
                    progress.stream,
                    Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(streamScroll),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        },
        confirmButton = { if (!progress.active) TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
fun SettingsScreen(
    hasKey: Boolean, currentVersion: String, checkingUpdate: Boolean, updateStatus: String,
    importConcurrency: Int, onImportConcurrencyChange: (Int) -> Unit, onCheckUpdate: () -> Unit,
    onBack: () -> Unit, onSave: (String) -> Unit, onClear: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var confirmingClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onBack) { Text("← 返回首页") }
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Text("应用更新 · 当前版本 $currentVersion", style = MaterialTheme.typography.titleMedium)
        Text(updateStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onCheckUpdate, enabled = !checkingUpdate) { Text(if (checkingUpdate) "正在检查…" else "检查更新") }
        Text("百炼 API Key：${if (hasKey) "已保存" else "未填写"}")
        Text("输入后可直接在手机上识别报告，无需运行电脑端服务。密钥使用设备密钥库加密保存。")
        OutlinedTextField(
            input, { input = it; error = "" }, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (hasKey) "填写新 Key 以替换" else "填写 API Key") },
            visualTransformation = PasswordVisualTransformation(), singleLine = true
        )
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            if (input.isBlank()) error = "请输入 API Key" else {
                onSave(input.trim())
                input = ""
                error = ""
            }
        }) { Text("保存 API Key") }
        if (hasKey) TextButton(onClick = { confirmingClear = true }) { Text("清除已保存的 Key") }
        Text("识图模型：qwen3.7-flash")
        Text("同时识别图片：$importConcurrency 张（下次导入生效）")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10).forEach { count ->
                OutlinedButton(onClick = { onImportConcurrencyChange(count) }, enabled = importConcurrency != count) {
                    Text("$count 张")
                }
            }
        }
        Text("报告类型辅助判断：decision-model-preview")
        Text("地域：北京 · 业务空间已内置")
    }
    if (confirmingClear) AlertDialog(
        onDismissRequest = { confirmingClear = false },
        title = { Text("清除 API Key？") },
        text = { Text("清除后，重新识别报告前需要再次填写 Key。已保存的报告不会删除。") },
        confirmButton = { TextButton(onClick = { confirmingClear = false; onClear() }) { Text("确认清除") } },
        dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text("取消") } }
    )
}
