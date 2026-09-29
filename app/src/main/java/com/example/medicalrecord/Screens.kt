package com.example.medicalrecord

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun InfoCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MedicalPalette.Outline)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { content() }
    }
}

@Composable
fun Overview(
    reports: List<Report>, observations: List<Observation>, stages: List<Stage>,
    busy: Boolean, notice: String, hasKey: Boolean, onPick: () -> Unit, onCamera: () -> Unit,
    onReport: (Long) -> Unit, onIndicator: (String) -> Unit,
    onReports: () -> Unit, onTrends: () -> Unit, onStages: () -> Unit, onSettings: () -> Unit
) {
    val confirmed = reports.filter { it.status == "已确认" }
    val pending = reports.filter { it.status != "已确认" }
    val numericKeys = observations.filter { row -> confirmed.any { it.id == row.reportId } && row.number() != null }.map { it.indicatorKey }.distinct()
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("体检记录", style = MaterialTheme.typography.headlineMedium)
                    Text("每一次检查，都有迹可循", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onSettings) { Text("设置  ›") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = MedicalPalette.DeepTeal)) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("下一步  ·  健康档案", style = MaterialTheme.typography.labelLarge, color = Color(0xFFA6DDC9))
                    Text(
                        when {
                            pending.isNotEmpty() -> "还有 ${pending.size} 份报告待校对"
                            reports.isEmpty() -> "导入第一份体检报告"
                            else -> "记录下一次检查"
                        },
                        style = MaterialTheme.typography.headlineSmall, color = Color.White
                    )
                    Text(
                        when {
                            pending.isNotEmpty() -> "核对日期、数值和参考范围后，指标才会进入趋势图。"
                            else -> "选择报告照片，识别后逐项确认。"
                        },
                        color = Color(0xFFD5E8E1), style = MaterialTheme.typography.bodyMedium
                    )
                    if (pending.isNotEmpty()) Button(
                        onClick = { onReport(pending.first().id) }, modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MedicalPalette.DeepTeal)
                    ) { Text("继续校对  →") }
                    else Button(
                        onClick = onPick, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MedicalPalette.DeepTeal)
                    ) { Text("选择报告图片  →") }
                    Text("导入  ━  校对  ━  查看趋势", color = Color(0xFFA6DDC9), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onPick, enabled = !busy, modifier = Modifier.weight(1f)) { Text("从相册导入") }
                OutlinedButton(onClick = onCamera, enabled = !busy, modifier = Modifier.weight(1f)) { Text("拍照导入") }
            }
            if (!hasKey) Text("首次识别需配置 API Key，选图后会引导设置。", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (notice.isNotBlank()) Text(notice, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoCard(Modifier.weight(1f).clickable(onClick = onReports)) {
                    Text("${reports.size}", style = MaterialTheme.typography.headlineMedium, color = MedicalPalette.DeepTeal)
                    Text("报告档案  ↗", color = MedicalPalette.Muted)
                }
                InfoCard(Modifier.weight(1f).clickable(onClick = onTrends)) {
                    Text("${numericKeys.size}", style = MaterialTheme.typography.headlineMedium, color = MedicalPalette.DeepTeal)
                    Text("追踪指标  ↗", color = MedicalPalette.Muted)
                }
            }
        }
        val recent = observations.filter { row -> confirmed.any { it.id == row.reportId } }.groupBy { it.indicatorKey }.values.mapNotNull { rows -> rows.maxByOrNull { row -> confirmed.find { it.id == row.reportId }?.sampleDate ?: "" } }.take(3)
        if (recent.isNotEmpty()) {
            item { SectionTitle("最近指标") }
            items(recent, key = { it.indicatorKey }) { row ->
                InfoCard(Modifier.clickable { onIndicator(row.indicatorKey) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(row.indicatorKey, fontWeight = FontWeight.SemiBold)
                        Text("${row.value} ${row.unit}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                    Text("查看完整趋势  ›", color = MedicalPalette.Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            InfoCard(Modifier.clickable(onClick = onStages)) {
                Text("阶段时间线  ›", fontWeight = FontWeight.SemiBold, color = MedicalPalette.DeepTeal)
                Text(if (stages.isEmpty()) "记录饮食、运动或用药变化，与指标趋势对照。" else stages.maxByOrNull { it.startDate }!!.let { "${it.title} · ${it.startDate} 起" }, color = MedicalPalette.Muted)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
fun ReportsScreen(
    reports: List<Report>, observations: List<Observation>, categories: List<String>, busy: Boolean, notice: String,
    onPick: () -> Unit, onCamera: () -> Unit, onOpenCategory: (String) -> Unit,
    onAddCategory: (String) -> Unit, onManual: () -> Unit
) {
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val allCategories = (categories + reports.map { it.category }).distinct().sorted()
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Spacer(Modifier.height(20.dp))
            SectionTitle("报告分类")
            Text("按类型整理每次检查，随时回看和比较。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPick, enabled = !busy, modifier = Modifier.weight(1f)) { Text("＋ 导入报告") }
                OutlinedButton(onClick = onCamera, enabled = !busy, modifier = Modifier.weight(1f)) { Text("拍照导入") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { adding = true }) { Text("＋ 新建分类") }
                TextButton(onClick = onManual) { Text("手工录入") }
            }
            if (notice.isNotEmpty()) Text(notice, color = MaterialTheme.colorScheme.primary)
        }
        if (allCategories.isEmpty()) item {
            InfoCard {
                Text("还没有报告分类", fontWeight = FontWeight.SemiBold)
                Text("导入第一份报告后会自动归类，也可以先新建一个分类。", color = MedicalPalette.Muted)
            }
        }
        items(allCategories, key = { it }) { category ->
            val count = reports.count { it.category == category }
            val pending = reports.count { it.category == category && it.status != "已确认" }
            val confirmedIds = reports.filter { it.category == category && it.status == "已确认" && validDate(it.sampleDate) }.map { it.id }.toSet()
            val trendCount = observations.filter { it.reportId in confirmedIds && it.value.trim().replace(",", "").toDoubleOrNull() != null }
                .map { it.indicatorKey }.distinct().size
            InfoCard(Modifier.clickable { onOpenCategory(category) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(MedicalPalette.Mint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Text(category.take(1), color = MedicalPalette.DeepTeal, style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(category, style = MaterialTheme.typography.titleMedium)
                        Text("$count 份报告 · $trendCount 项趋势", color = MedicalPalette.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
                }
                if (pending > 0) Text("$pending 份待校对", color = MedicalPalette.Amber, style = MaterialTheme.typography.labelMedium)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
    if (adding) AlertDialog(
        onDismissRequest = { adding = false; newName = "" },
        title = { Text("新建分类") },
        text = { OutlinedTextField(newName, { newName = it }, label = { Text("分类名称") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            if (newName.trim().isNotEmpty()) { onAddCategory(newName.trim().take(40)); newName = ""; adding = false }
        }) { Text("创建") } },
        dismissButton = { TextButton(onClick = { adding = false; newName = "" }) { Text("取消") } }
    )
}

@Composable
fun CategoryReportsScreen(
    category: String, reports: List<Report>, observations: List<Observation>, view: String,
    onBack: () -> Unit, onOpen: (Long) -> Unit, onViewChange: (String) -> Unit, onOpenTrend: (String) -> Unit,
    onRename: (String) -> Unit, onDeleteEmpty: () -> Unit
) {
    var query by remember(category, view) { mutableStateOf("") }
    var renaming by remember(category) { mutableStateOf(false) }
    var deletingCategory by remember(category) { mutableStateOf(false) }
    var newName by remember(category) { mutableStateOf(category) }
    var error by remember(category) { mutableStateOf("") }
    val inCategory = reports.filter { it.category == category }
    val confirmedIds = inCategory.filter { it.status == "已确认" && validDate(it.sampleDate) }.map { it.id }.toSet()
    val trendKeys = observations.filter { it.reportId in confirmedIds && it.value.trim().replace(",", "").toDoubleOrNull() != null }
        .map { it.indicatorKey }.distinct().sorted()
    val reportById = reports.associateBy { it.id }
    val shown = inCategory.filter { query.isBlank() || listOf(it.type, it.institution, it.sampleDate).any { value -> value.contains(query, ignoreCase = true) } }
        .sortedWith(compareBy<Report> { it.status == "已确认" }.thenByDescending { it.sampleDate }.thenByDescending { it.id })
    val shownKeys = trendKeys.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
    LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = onBack) { Text("← 所有分类") }
            SectionTitle(category)
            Text("${inCategory.size} 份报告 · ${trendKeys.size} 项趋势", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (view == "报告") Button(onClick = { onViewChange("报告") }) { Text("报告") }
                else OutlinedButton(onClick = { onViewChange("报告") }) { Text("报告") }
                if (view == "趋势") Button(onClick = { onViewChange("趋势") }) { Text("趋势") }
                else OutlinedButton(onClick = { onViewChange("趋势") }) { Text("趋势") }
            }
            Row {
                TextButton(onClick = { renaming = true }) { Text("重命名分类") }
                if (inCategory.isEmpty()) TextButton(onClick = { deletingCategory = true }) { Text("删除空分类") }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
        if (view == "报告") {
            if (inCategory.size > 3) item { OutlinedTextField(query, { query = it }, label = { Text("搜索报告") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            if (shown.isEmpty()) item { Text(if (inCategory.isEmpty()) "这个分类还没有报告。可在报告详情中将报告移入此分类。" else "没有匹配的报告。") }
            items(shown, key = { it.id }) { report ->
                InfoCard(Modifier.clickable { onOpen(report.id) }) {
                    Text("${report.type} · ${report.sampleDate.ifBlank { "日期待确认" }}", fontWeight = FontWeight.SemiBold)
                    Text("${report.institution.ifBlank { "机构未识别" }} · ${observations.count { it.reportId == report.id }} 项")
                    Text(report.status, color = if (report.status == "已确认") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        } else {
            item {
                Text("选一个指标，查看跨分类的全部已确认记录。每个数据点都会标明来源。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (trendKeys.size > 3) OutlinedTextField(query, { query = it }, label = { Text("搜索指标") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            if (shownKeys.isEmpty()) item { Text(if (trendKeys.isEmpty()) "当前分类尚无可绘制指标。先校对报告，确认后会出现在这里。" else "没有匹配的指标。") }
            items(shownKeys, key = { it }) { key ->
                val allRows = observations.filter {
                    it.indicatorKey == key && it.value.trim().replace(",", "").toDoubleOrNull() != null &&
                        reportById[it.reportId]?.let { report -> report.status == "已确认" && validDate(report.sampleDate) } == true
                }
                val sources = allRows.mapNotNull { reportById[it.reportId]?.category }.distinct()
                InfoCard(Modifier.clickable { onOpenTrend(key) }) {
                    Text(key, fontWeight = FontWeight.SemiBold)
                    Text("${allRows.size} 次记录 · 来源：${sources.joinToString("、")}")
                }
            }
        }
    }
    if (renaming) AlertDialog(
        onDismissRequest = { renaming = false },
        title = { Text("重命名分类") },
        text = { OutlinedTextField(newName, { newName = it }, label = { Text("分类名称") }, singleLine = true) },
        confirmButton = { TextButton(onClick = {
            if (newName.trim().isNotEmpty()) {
                try { onRename(newName.trim()); renaming = false } catch (exception: Exception) { error = exception.message ?: "重命名失败"; renaming = false }
            }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } }
    )
    if (deletingCategory) AlertDialog(
        onDismissRequest = { deletingCategory = false },
        title = { Text("删除分类“$category”？") },
        text = { Text("这个分类目前没有报告。删除后需要重新创建。") },
        confirmButton = { TextButton(onClick = { deletingCategory = false; onDeleteEmpty() }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { deletingCategory = false }) { Text("取消") } }
    )
}

@Composable
fun ReportDetail(
    report: Report, rows: List<Observation>, categories: List<String>, onBack: () -> Unit,
    onSaveReport: (Report) -> Unit, onSaveObservation: (Observation) -> Unit,
    onAddObservation: (Observation) -> Unit, onDeleteObservation: (Long) -> Unit,
    onDeleteReport: () -> Unit, onRetry: () -> Unit, onAddCategory: (String) -> Unit
) {
    var type by remember(report.id, report.type) { mutableStateOf(report.type) }
    var category by remember(report.id, report.category) { mutableStateOf(report.category) }
    var sampleDate by remember(report.id, report.sampleDate) { mutableStateOf(report.sampleDate) }
    var reportDate by remember(report.id, report.reportDate) { mutableStateOf(report.reportDate) }
    var institution by remember(report.id, report.institution) { mutableStateOf(report.institution) }
    var imageExpanded by remember(report.id) { mutableStateOf(false) }
    var choosingCategory by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Observation?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var editingInfo by remember(report.id) { mutableStateOf(!validDate(report.sampleDate)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← 返回报告") }
            Text(if (report.status == "已确认") "查看报告" else "校对报告", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        }
        if (report.imagePath.isNotBlank()) ZoomableReportImage(
            report.imagePath,
            Modifier.weight(if (imageExpanded) 0.64f else 0.44f),
            imageExpanded,
            onToggleExpanded = { imageExpanded = !imageExpanded }
        )
    LazyColumn(Modifier.weight(if (report.imagePath.isNotBlank()) { if (imageExpanded) 0.36f else 0.56f } else 1f).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            InfoCard {
                Text(type, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("采样日期：${sampleDate.ifBlank { "待确认" }}")
                Text(institution.ifBlank { "机构未识别" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { choosingCategory = true }) { Text("分类：$category · 修改") }
                TextButton(onClick = { editingInfo = !editingInfo }) { Text(if (editingInfo) "收起信息" else "修改报告信息") }
            }
            if (editingInfo) {
                OutlinedTextField(type, { type = it }, label = { Text("报告类型") }, modifier = Modifier.fillMaxWidth())
                DatePickerField("采样日期", sampleDate, { sampleDate = it })
                DatePickerField("报告日期", reportDate, { reportDate = it }, optional = true)
                OutlinedTextField(institution, { institution = it }, label = { Text("检验机构") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    if (!validDate(sampleDate)) error = "请填写有效的采样日期" else {
                        onSaveReport(report.copy(type = type, category = category, sampleDate = sampleDate, reportDate = reportDate, institution = institution, status = "待校对"))
                        editingInfo = false
                        error = "信息已保存"
                    }
                }) { Text("保存报告信息") }
            }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (report.imagePath.isNotEmpty()) TextButton(onClick = onRetry) { Text("重新识别") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SectionTitle("检验项目 (${rows.size})")
                TextButton(onClick = { editing = Observation(0, report.id, "", "", "", "", "", "") }) { Text("＋ 添加") }
            }
            Text("原图保持在上方；点击项目后可直接在下方修改。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (editing?.id == 0L) item {
            ObservationEditorInline(editing!!, onClose = { editing = null }, onSave = { onAddObservation(it); editing = null }, onDelete = {})
        }
        items(rows, key = { it.id }) { row ->
            if (editing?.id == row.id) ObservationEditorInline(
                row, onClose = { editing = null },
                onSave = { onSaveObservation(it); editing = null },
                onDelete = { onDeleteObservation(row.id); editing = null }
            ) else InfoCard(Modifier.clickable { editing = row }) {
                    Text(row.name, fontWeight = FontWeight.SemiBold)
                    Text("${row.value.ifBlank { "未识别" }} ${row.unit}  ${row.flag}")
                    Text("参考：${row.reference.ifBlank { "报告未识别" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
        }
        item { TextButton(onClick = { deleting = true }) { Text("删除报告") } }
    }
    Button(onClick = {
        when {
            !validDate(sampleDate) -> { error = "确认前请填写有效采样日期"; editingInfo = true }
            rows.isEmpty() -> error = "请先添加或识别检验项目"
            else -> {
                onSaveReport(report.copy(type = type, category = category, sampleDate = sampleDate, reportDate = reportDate, institution = institution, status = "已确认"))
                error = "已确认，数值指标现可进入趋势。"
            }
        }
    }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text(if (report.status == "已确认") "保存并更新趋势" else "确认报告，加入趋势") }
    }
    if (choosingCategory) AlertDialog(
        onDismissRequest = { choosingCategory = false },
        title = { Text("选择报告分类") },
        text = {
            Column {
                LazyColumn(Modifier.height(260.dp)) {
                    items(categories.distinct(), key = { it }) { option ->
                        TextButton(onClick = {
                            category = option
                            onSaveReport(report.copy(category = option))
                            choosingCategory = false
                        }, modifier = Modifier.fillMaxWidth()) { Text(option) }
                    }
                }
                OutlinedTextField(newCategory, { newCategory = it }, label = { Text("或新建分类") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = {
            val value = newCategory.trim().take(40)
            if (value.isNotEmpty()) {
                onAddCategory(value)
                category = value
                onSaveReport(report.copy(category = value))
                newCategory = ""
                choosingCategory = false
            }
        }) { Text("新建并选用") } },
        dismissButton = { TextButton(onClick = { choosingCategory = false }) { Text("取消") } }
    )
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除这份${report.type}报告？") }, text = { Text("原图、全部指标及相关趋势点会一同删除。") }, confirmButton = { TextButton(onClick = { deleting = false; onDeleteReport() }) { Text("确认删除") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
}

@Composable
private fun ObservationEditorInline(row: Observation, onClose: () -> Unit, onSave: (Observation) -> Unit, onDelete: () -> Unit) {
    var name by remember(row.id) { mutableStateOf(row.name) }
    var value by remember(row.id) { mutableStateOf(row.value) }
    var unit by remember(row.id) { mutableStateOf(row.unit) }
    var reference by remember(row.id) { mutableStateOf(row.reference) }
    var flag by remember(row.id) { mutableStateOf(row.flag) }
    var confirmingDelete by remember(row.id) { mutableStateOf(false) }
    InfoCard {
        Text(if (row.id == 0L) "新增项目" else "校对项目", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(name, { name = it }, label = { Text("项目名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value, { value = it }, label = { Text("结果原文") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(unit, { unit = it }, label = { Text("单位") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(reference, { reference = it }, label = { Text("参考范围原文") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(flag, { flag = it }, label = { Text("报告提示 ↑/↓") }, modifier = Modifier.fillMaxWidth())
        Row {
            Button(onClick = {
                if (name.isNotBlank()) onSave(row.copy(name = name, value = value, unit = unit, reference = reference, flag = flag, indicatorKey = indicatorKey(name)))
            }) { Text("保存项目") }
            TextButton(onClick = onClose) { Text("取消") }
            if (row.id != 0L) TextButton(onClick = { confirmingDelete = true }) { Text("删除") }
        }
    }
    if (confirmingDelete) AlertDialog(
        onDismissRequest = { confirmingDelete = false },
        title = { Text("删除“${row.name}”？") },
        text = { Text("这项指标会从当前报告中移除，相关趋势点也会消失。") },
        confirmButton = { TextButton(onClick = { confirmingDelete = false; onDelete() }) { Text("确认删除") } },
        dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("取消") } }
    )
}
