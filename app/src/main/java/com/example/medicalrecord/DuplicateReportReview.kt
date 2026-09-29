package com.example.medicalrecord

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

@Composable
fun DuplicateReportReview(newReport: Report, existingReports: List<Report>, onDecision: (Boolean) -> Unit) {
    var expandedImage by remember(newReport.imagePath) { mutableStateOf<String?>(null) }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("请核对是否为同一张报告", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "${newReport.category} · ${newReport.sampleDate.ifBlank { newReport.reportDate }}，已有 ${existingReports.size} 份同日同类报告。点击图片可放大查看。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ReportImagePreview("新上传的图片", newReport.imagePath) { expandedImage = newReport.imagePath }
                    existingReports.forEachIndexed { index, report ->
                        ReportImagePreview(
                            "已保存的报告 ${index + 1} · ${report.institution.ifBlank { "机构未识别" }}",
                            report.imagePath
                        ) { expandedImage = report.imagePath }
                    }
                }
                Text("如与任意一张已有图片相同，跳过这次上传；否则继续导入。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onDecision(false) }, modifier = Modifier.weight(1f)) {
                        Text("同一张，跳过")
                    }
                    Button(onClick = { onDecision(true) }, modifier = Modifier.weight(1f)) {
                        Text("不同，继续导入")
                    }
                }
            }
        }
    }
    expandedImage?.let { path ->
        Dialog(onDismissRequest = { expandedImage = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                ZoomableReportImage(path, Modifier.fillMaxSize(), expanded = true, onToggleExpanded = { expandedImage = null })
            }
        }
    }
}

@Composable
private fun ReportImagePreview(label: String, path: String, onOpen: () -> Unit) {
    val bitmap = remember(path) {
        if (!File(path).exists()) null else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        if (bitmap == null) Text("图片不可用", color = MaterialTheme.colorScheme.error)
        else Image(
            bitmap,
            label,
            Modifier.fillMaxWidth().height(210.dp).background(Color(0xFFE7EEEB)).clickable(onClick = onOpen),
            contentScale = ContentScale.Fit
        )
    }
}
