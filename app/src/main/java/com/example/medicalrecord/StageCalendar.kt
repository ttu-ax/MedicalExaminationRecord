package com.example.medicalrecord

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val monthTitle = DateTimeFormatter.ofPattern("yyyy年M月")
private val stageCategories = listOf("饮食", "运动", "用药", "作息", "其他")

private fun stageColor(category: String): Color = when (category) {
    "饮食" -> Color(0x883DAA80)
    "运动" -> Color(0x8878A9DA)
    "用药" -> Color(0x88E7B46D)
    "作息" -> Color(0x88AD9ED6)
    else -> Color(0x8896AAA7)
}

private fun Stage.firstDay(): LocalDate? = runCatching { LocalDate.parse(startDate) }.getOrNull()
private fun Stage.lastDay(): LocalDate? =
    runCatching { if (endDate.isBlank()) LocalDate.now().coerceAtLeast(LocalDate.parse(startDate)) else LocalDate.parse(endDate) }.getOrNull()

private fun stageLanes(stages: List<Stage>): Map<Long, Int> {
    val laneEnds = mutableListOf<LocalDate>()
    return stages.mapNotNull { stage ->
        val start = stage.firstDay() ?: return@mapNotNull null
        val end = stage.lastDay() ?: return@mapNotNull null
        Triple(stage, start, end)
    }.sortedWith(compareBy<Triple<Stage, LocalDate, LocalDate>> { it.second }.thenBy { it.third })
        .associate { (stage, start, end) ->
            val lane = laneEnds.indexOfFirst { !it.isAfter(start) }.let { if (it < 0) laneEnds.size else it }
            if (lane == laneEnds.size) laneEnds.add(end) else laneEnds[lane] = end
            stage.id to lane
        }
}

@Composable
fun StagesScreen(stages: List<Stage>, onAdd: (Stage) -> Unit, onUpdate: (Stage) -> Unit, onDelete: (Long) -> Unit) {
    var selectionStart by remember { mutableStateOf<LocalDate?>(null) }
    var editing by remember { mutableStateOf<Stage?>(null) }
    var deleting by remember { mutableStateOf<Stage?>(null) }

    fun chooseDate(day: LocalDate) {
        val first = selectionStart
        if (first == null || day.isBefore(first)) selectionStart = day
        else {
            editing = Stage(0, "", "饮食", first.toString(), day.toString(), "")
            selectionStart = null
        }
    }

    val todayMonth = YearMonth.now()
    val firstMonth = minOf(todayMonth.minusMonths(12), stages.mapNotNull { it.firstDay()?.let(YearMonth::from) }.minOrNull() ?: todayMonth)
    val lastMonth = maxOf(todayMonth.plusMonths(12), stages.mapNotNull { it.lastDay()?.let(YearMonth::from) }.maxOrNull() ?: todayMonth)
    val months = remember(firstMonth, lastMonth) { generateSequence(firstMonth) { if (it < lastMonth) it.plusMonths(1) else null }.toList() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = months.indexOf(todayMonth))
    val lanes = remember(stages) { stageLanes(stages) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("阶段日历", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("上下滚动选择月份；先点开始日期，再滚动到结束日期。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selectionStart != null) {
            Column {
                Text("已选开始：$selectionStart。继续点结束日期，也可点同一天。", color = MaterialTheme.colorScheme.primary)
                Row {
                    TextButton(onClick = {
                        editing = Stage(0, "", "饮食", selectionStart.toString(), "", "")
                        selectionStart = null
                    }) { Text("持续至今") }
                    TextButton(onClick = { selectionStart = null }) { Text("取消选择") }
                }
            }
        }
    LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(months, key = { _, month -> month.toString() }) { _, month ->
            val firstOfMonth = month.atDay(1)
            val calendarStart = firstOfMonth.minusDays((firstOfMonth.dayOfWeek.value - 1).toLong())
            val weekCount = (firstOfMonth.dayOfWeek.value - 1 + month.lengthOfMonth() + 6) / 7
            val inMonth = stages.filter { stage ->
                val start = stage.firstDay() ?: return@filter false
                val end = stage.lastDay() ?: return@filter false
                start <= month.atEndOfMonth() && end >= firstOfMonth
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(month.format(monthTitle), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                        Text(label, Modifier.weight(1f), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                repeat(weekCount) { weekIndex ->
            val weekStart = calendarStart.plusDays((weekIndex * 7).toLong())
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { dayIndex ->
                        val day = weekStart.plusDays(dayIndex.toLong())
                        val selected = day == selectionStart
                        Box(
                            Modifier.weight(1f).height(44.dp)
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable(enabled = YearMonth.from(day) == month) { chooseDate(day) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                day.dayOfMonth.toString(),
                                color = when {
                                    selected -> MaterialTheme.colorScheme.primary
                                    YearMonth.from(day) != month -> Color.Transparent
                                    day == LocalDate.now() -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (day == LocalDate.now() || selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                val weekStages = stages.filter { stage ->
                    val start = stage.firstDay() ?: return@filter false
                    val end = stage.lastDay() ?: return@filter false
                    start <= minOf(weekStart.plusDays(6), month.atEndOfMonth()) && end >= maxOf(weekStart, firstOfMonth)
                }
                for (lane in 0..(weekStages.maxOfOrNull { lanes[it.id] ?: 0 } ?: -1)) {
                    val laneStages = weekStages.filter { lanes[it.id] == lane }
                    BoxWithConstraints(Modifier.fillMaxWidth().height(25.dp)) {
                        laneStages.forEach { stage ->
                    val start = stage.firstDay()!!.coerceAtLeast(weekStart).coerceAtLeast(firstOfMonth)
                    val end = stage.lastDay()!!.coerceAtMost(weekStart.plusDays(6)).coerceAtMost(month.atEndOfMonth())
                    val left = (start.toEpochDay() - weekStart.toEpochDay()).toInt()
                    val span = (end.toEpochDay() - start.toEpochDay() + 1).toInt()
                        Box(
                            Modifier.offset(x = maxWidth / 7 * left).width(maxWidth / 7 * span)
                                .height(23.dp).background(stageColor(stage.category), RoundedCornerShape(5.dp))
                                .clickable { editing = stage }.padding(horizontal = 5.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(stage.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                        }
                        }
                    }
                }
            }
        }
                Text("本月阶段（${inMonth.size}）", style = MaterialTheme.typography.titleMedium)
                inMonth.forEach { stage ->
            Card(Modifier.fillMaxWidth().clickable { editing = stage }) {
                Column(Modifier.padding(12.dp)) {
                    Text(stage.title, fontWeight = FontWeight.SemiBold)
                    Text("${stage.category} · ${stage.startDate} — ${stage.endDate.ifBlank { "至今" }}")
                }
            }
        }
            }
        }
        item { Text("已显示前后各 12 个月及所有已有阶段所在月份", Modifier.padding(bottom = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    }
    editing?.let { stage ->
        StageDetailsDialog(stage,
            onClose = { editing = null },
            onSave = { updated ->
                if (updated.id == 0L) onAdd(updated) else onUpdate(updated)
                editing = null
            },
            onRequestDelete = { deleting = stage; editing = null }
        )
    }
    deleting?.let { stage ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除阶段“${stage.title}”？") },
            text = { Text("删除后，月历色条和趋势图中的阶段标记都会消失。") },
            confirmButton = { TextButton(onClick = { onDelete(stage.id); deleting = null }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun StageDetailsDialog(stage: Stage, onClose: () -> Unit, onSave: (Stage) -> Unit, onRequestDelete: () -> Unit) {
    var title by remember(stage.id, stage.startDate) { mutableStateOf(stage.title) }
    var category by remember(stage.id, stage.startDate) { mutableStateOf(stage.category) }
    var start by remember(stage.id, stage.startDate) { mutableStateOf(stage.startDate) }
    var end by remember(stage.id, stage.startDate) { mutableStateOf(stage.endDate) }
    var note by remember(stage.id, stage.startDate) { mutableStateOf(stage.note) }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (stage.id == 0L) "新建阶段" else "编辑阶段") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("类型")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    stageCategories.forEach { option ->
                        FilterChip(selected = category == option, onClick = { category = option }, label = { Text(option) })
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text("阶段名称，例如：开始规律运动") }, modifier = Modifier.fillMaxWidth())
                DatePickerField("开始日期", start, { start = it })
                DatePickerField("结束日期", end, { end = it }, optional = true)
                OutlinedTextField(note, { note = it }, label = { Text("具体做了什么") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = {
            if (title.isBlank() || !validDate(start) || (end.isNotBlank() && (!validDate(end) || end < start))) error = "请检查名称和起止日期"
            else onSave(stage.copy(title = title.trim(), category = category, startDate = start, endDate = end, note = note.trim()))
        }) { Text("保存") } },
        dismissButton = {
            Row {
                if (stage.id != 0L) TextButton(onClick = onRequestDelete) { Text("删除") }
                TextButton(onClick = onClose) { Text("取消") }
            }
        }
    )
}
