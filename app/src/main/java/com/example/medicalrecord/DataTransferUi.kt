package com.example.medicalrecord

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DataManagementScreen(
    reportCount: Int, stageCount: Int, lastBackupAt: Long, message: String,
    onBack: () -> Unit, onExportBackup: () -> Unit, onRestoreBackup: () -> Unit,
    onImportCsv: () -> Unit, onExportCsv: () -> Unit
) {
    val lastBackup = if (lastBackupAt > 0) DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(lastBackupAt)) else "尚未备份"
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text("← 返回设置") }
        Text("数据与备份", style = MaterialTheme.typography.headlineMedium)
        Text("本机有 $reportCount 份报告、$stageCount 个阶段 · 上次导出备份：$lastBackup",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("备份与恢复", style = MaterialTheme.typography.titleMedium)
                Text("完整备份包含报告、指标、分类、阶段和原始图片。API Key 不包含在备份中。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onExportBackup, modifier = Modifier.fillMaxWidth()) { Text("导出完整备份") }
                OutlinedButton(onClick = onRestoreBackup, modifier = Modifier.fillMaxWidth()) { Text("从备份恢复") }
                Text("备份文件包含健康数据且未加密，请保存到可信位置。卸载应用前请确认文件已保存。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("数据交换", style = MaterialTheme.typography.titleMedium)
                Text("CSV 可用表格软件查看和编辑；导入 CSV 后的报告需重新校对，不包含原始图片和阶段。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onImportCsv, modifier = Modifier.fillMaxWidth()) { Text("导入指标表格（CSV）") }
                OutlinedButton(onClick = onExportCsv, modifier = Modifier.fillMaxWidth()) { Text("导出指标表格（CSV）") }
                Text("CSV 文件未加密，分享前请留意其中的健康数据。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun TransferPreviewDialog(preview: TransferPreview, message: String, onCancel: () -> Unit, onAppend: () -> Unit, onReplace: () -> Unit) {
    var confirmingReplace by remember(preview.uri) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (preview.kind == TransferKind.Backup) "恢复备份" else "导入指标表格") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("文件中有 ${preview.reports} 份报告、${preview.observations} 条指标、${preview.stages} 个阶段、${preview.images} 张原图。")
                Text("追加时预计跳过 ${preview.duplicateReports} 份相同报告；相同阶段也会跳过。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (preview.kind == TransferKind.Csv) Text("CSV 导入的报告状态为待校对，且没有原图。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (message.isNotBlank() && message != "已读取文件，请确认导入方式。") Text(message, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = onAppend) { Text("追加导入") } },
        dismissButton = {
            Row {
                if (preview.kind == TransferKind.Backup) TextButton(onClick = { confirmingReplace = true }) { Text("替换本机数据") }
                TextButton(onClick = onCancel) { Text("取消") }
            }
        }
    )
    if (confirmingReplace) AlertDialog(
        onDismissRequest = { confirmingReplace = false },
        title = { Text("替换全部本机数据？") },
        text = { Text("当前报告、分类、指标和阶段将被备份文件替换。请确认已保留需要的本机记录。") },
        confirmButton = { Button(onClick = onReplace) { Text("确认替换") } },
        dismissButton = { TextButton(onClick = { confirmingReplace = false }) { Text("返回") } }
    )
}

@Composable
fun TransferWorkingDialog(message: String) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("正在处理数据") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {}
    )
}
