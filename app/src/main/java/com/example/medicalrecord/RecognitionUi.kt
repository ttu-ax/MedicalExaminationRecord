package com.example.medicalrecord

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

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
    var showDetails by remember { mutableStateOf(false) }
    LaunchedEffect(progress.current, progress.stream, streamScroll.maxValue) {
        streamScroll.scrollTo(streamScroll.maxValue)
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("识别进度 · ${progress.current}/${progress.total} 张", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { progress.completed.toFloat() / progress.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            Text(progress.stage, color = MaterialTheme.colorScheme.primary)
            if (progress.active) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (progress.error.isNotBlank()) Text(progress.error, color = MaterialTheme.colorScheme.error)
            if (!progress.active && progress.stream.isNotBlank()) {
                TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "隐藏模型输出" else "查看模型输出") }
            }
            if (progress.stream.isNotBlank() && (progress.active || showDetails)) {
                Text("模型实时返回（原始内容）", style = MaterialTheme.typography.labelLarge)
                Text(
                    progress.stream,
                    Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(streamScroll),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!progress.active) TextButton(onClick = onDismiss) { Text("关闭进度") }
        }
    }
}

@Composable
fun SettingsScreen(hasKey: Boolean, onBack: () -> Unit, onSave: (String) -> Unit, onClear: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var confirmingClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onBack) { Text("← 返回首页") }
        Text("设置", style = MaterialTheme.typography.headlineMedium)
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
