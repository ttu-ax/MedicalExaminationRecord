package com.example.medicalrecord

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import android.graphics.Paint
import java.time.LocalDate
import kotlin.math.abs

private data class ReferenceLimits(val lower: Double?, val upper: Double?)

private fun referenceLimits(text: String): ReferenceLimits? {
    val number = """[+-]?\d+(?:\.\d+)?"""
    val between = Regex("($number)\\s*(?:-|–|—|~|～|至|到)\\s*($number)").find(text)
    if (between != null) {
        val lower = between.groupValues[1].toDoubleOrNull()
        val upper = between.groupValues[2].toDoubleOrNull()
        if (lower != null && upper != null && lower <= upper) return ReferenceLimits(lower, upper)
    }
    val oneSided = Regex("(≤|<|≥|>)\\s*($number)").find(text) ?: return null
    val limit = oneSided.groupValues[2].toDoubleOrNull() ?: return null
    return if (oneSided.groupValues[1] == "≤" || oneSided.groupValues[1] == "<") ReferenceLimits(null, limit)
    else ReferenceLimits(limit, null)
}

private fun visibleStages(stages: List<Stage>, firstDay: Long, lastDay: Long): List<Stage> =
    stages.filter { stage ->
        val start = runCatching { LocalDate.parse(stage.startDate).toEpochDay() }.getOrNull() ?: return@filter false
        val end = runCatching { LocalDate.parse(stage.endDate).toEpochDay() }.getOrNull() ?: lastDay
        start <= lastDay && end >= firstDay
    }

private data class TrendPoint(val observation: Observation, val report: Report, val day: Long, val number: Double)

private data class UnitAxis(val unit: String, val min: Double, val max: Double, val color: Color)
private val unitColors = listOf(Color(0xFF1769AA), Color(0xFFB35F2B), Color(0xFF7658A8), Color(0xFF13816F), Color(0xFF9A435E))
private val sourceColors = listOf(Color(0xFF1769AA), Color(0xFFB35F2B), Color(0xFF7658A8), Color(0xFF13816F), Color(0xFF9A435E))
private fun trendStageColor(category: String): Color = when (category) {
    "饮食" -> Color(0xFF278361)
    "运动" -> Color(0xFF3979B7)
    "用药" -> Color(0xFFC07B28)
    "作息" -> Color(0xFF8664B1)
    else -> Color(0xFF71858A)
}

private fun axesFor(points: List<TrendPoint>, references: Map<String, Pair<TrendPoint, ReferenceLimits>>): List<UnitAxis> =
    points.map { it.observation.unit }.distinct().mapIndexed { index, unit ->
        val values = points.filter { it.observation.unit == unit }.map { it.number } +
            listOfNotNull(references[unit]?.second?.lower, references[unit]?.second?.upper)
        val low = values.minOrNull() ?: 0.0
        val high = values.maxOrNull() ?: 1.0
        val spread = (high - low).coerceAtLeast(abs(high) * 0.1).coerceAtLeast(1.0)
        UnitAxis(unit, low - spread * 0.12, high + spread * 0.12, unitColors[index % unitColors.size])
    }

private fun displayUnit(unit: String) = unit.ifBlank { "无单位" }

private fun pointsFor(key: String, rows: List<Observation>, reports: List<Report>): List<TrendPoint> {
    val byId = reports.associateBy { it.id }
    return rows.filter { it.indicatorKey == key }.mapNotNull { row ->
        val report = byId[row.reportId] ?: return@mapNotNull null
        if (report.status != "已确认" || !validDate(report.sampleDate)) return@mapNotNull null
        val text = row.value.trim().replace(",", "")
        if (!Regex("^[-+]?\\d+(?:\\.\\d+)?$").matches(text)) return@mapNotNull null
        val value = text.toDoubleOrNull() ?: return@mapNotNull null
        TrendPoint(row, report, LocalDate.parse(report.sampleDate).toEpochDay(), value)
    }.sortedWith(compareBy<TrendPoint> { it.day }.thenBy { it.observation.id })
}

@Composable
fun TrendDetail(
    key: String, observations: List<Observation>, reports: List<Report>, stages: List<Stage>,
    onBack: () -> Unit, onOpenReport: (Long) -> Unit, onStages: () -> Unit, sourceCategory: String?
) {
    val all = remember(key, observations, reports) { pointsFor(key, observations, reports) }
    var window by remember(key) { mutableStateOf("全部") }
    val latest = all.maxOfOrNull { it.day } ?: 0L
    val points = when (window) {
        "3个月" -> all.filter { it.day >= latest - 92 }
        "1年" -> all.filter { it.day >= latest - 366 }
        else -> all
    }
    var selected by remember(key, window) { mutableStateOf<TrendPoint?>(points.lastOrNull()) }
    var selectedStage by remember(key, window) { mutableStateOf<Stage?>(null) }
    val references = points.groupBy { it.observation.unit }.mapNotNull { (unit, unitPoints) ->
        unitPoints.mapNotNull { point -> referenceLimits(point.observation.reference)?.let { point to it } }.lastOrNull()?.let { unit to it }
    }.toMap()
    val axes = axesFor(points, references)
    val sourceCategories = points.map { it.report.category }.distinct().sorted()
    val sourcePalette = sourceCategories.mapIndexed { index, category -> category to sourceColors[index % sourceColors.size] }.toMap()
    val shownStages = if (points.isEmpty()) emptyList() else visibleStages(stages, points.first().day, points.last().day)
    LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            TextButton(onClick = onBack) { Text(if (sourceCategory == null) "← 返回" else "← 返回${sourceCategory}趋势") }
            Text(key, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("按采样日期汇总所有分类的同名指标；点按圆点查看来源报告。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onStages) { Text("管理阶段标记 →") }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("3个月", "1年", "全部").forEach { option ->
                    if (window == option) Button(onClick = { window = option }) { Text(option) }
                    else OutlinedButton(onClick = { window = option }) { Text(option) }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (points.isEmpty()) Text("该时间段没有记录")
                    else {
                        Text("${points.size} 次检测 · ${sourceCategories.size} 个来源分类", style = MaterialTheme.typography.labelMedium)
                        axes.forEach { axis ->
                            Text("━ ${displayUnit(axis.unit)}：纵轴 ${"%.2f".format(axis.min)}–${"%.2f".format(axis.max)}", color = axis.color, style = MaterialTheme.typography.bodySmall)
                        }
                        if (axes.size > 1) Text("不同单位共用日期横轴，使用图中同色的纵轴刻度；曲线不跨单位连接。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TrendChart(points, shownStages, references, axes, sourcePalette,
                            onPoint = { selected = it }, onOpenReport = onOpenReport)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(points.first().report.sampleDate, style = MaterialTheme.typography.labelSmall)
                            Text(points.last().report.sampleDate, style = MaterialTheme.typography.labelSmall)
                        }
                        Text("实线颜色表示单位及其纵轴；圆点颜色表示来源分类。", style = MaterialTheme.typography.bodySmall)
                        sourceCategories.forEach { category ->
                            Text("● $category", color = sourcePalette[category] ?: MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                        Text("点按圆点查看当次结果、参考范围与来源。", style = MaterialTheme.typography.bodySmall)
                        if (references.isNotEmpty()) {
                            references.forEach { (unit, source) ->
                                val limits = source.second
                                val description = when {
                                    limits.lower != null && limits.upper != null -> "${limits.lower}–${limits.upper}"
                                    limits.lower != null -> "≥ ${limits.lower}"
                                    else -> "≤ ${limits.upper}"
                                }
                                Text("${displayUnit(unit)} 参考界限：$description（取 ${source.first.report.sampleDate} 报告），横向虚线标示。", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("各次报告的参考范围可能不同，点按圆点可查看当次范围。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else Text("这些报告没有可绘制的数值参考范围；点按圆点查看报告原文。", style = MaterialTheme.typography.bodySmall)
                        if (shownStages.isNotEmpty()) {
                            Text("横轴彩色线段表示阶段；重叠阶段分行显示。点按线段或名称查看记录。", style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                shownStages.forEach { stage ->
                                    OutlinedButton(onClick = { selectedStage = stage }) {
                                        Text("━ ${stage.title}", color = trendStageColor(stage.category))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        selected?.let { point -> item {
            Card(Modifier.fillMaxWidth().clickable { onOpenReport(point.report.id) }) {
                Column(Modifier.padding(16.dp)) {
                    Text("${point.report.sampleDate} · ${point.observation.value} ${point.observation.unit}", fontWeight = FontWeight.SemiBold)
                    Text("参考范围：${point.observation.reference.ifBlank { "报告未提供" }}")
                    Text("来源：${point.report.category} · ${point.report.type} · ${point.report.institution.ifBlank { "机构未填写" }}")
                    Text("点击查看原报告", color = MaterialTheme.colorScheme.primary)
                }
            }
        } }
        selectedStage?.let { stage -> item {
            Card(Modifier.fillMaxWidth().clickable { selectedStage = null }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stage.title, color = trendStageColor(stage.category), fontWeight = FontWeight.SemiBold)
                    Text("${stage.category} · ${stage.startDate} — ${stage.endDate.ifBlank { "至今" }}")
                    Text(stage.note.ifBlank { "未填写具体措施" })
                }
            }
        } }
        item { Text("全部记录", style = MaterialTheme.typography.titleMedium) }
        items(points.reversed(), key = { it.observation.id }) { point ->
            Card(Modifier.fillMaxWidth().clickable { selected = point }) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(point.report.sampleDate)
                    Text("${point.observation.value} ${point.observation.unit} ${point.observation.flag}")
                }
            }
        }
    }
}

@Composable
private fun TrendChart(
    points: List<TrendPoint>, stages: List<Stage>,
    references: Map<String, Pair<TrendPoint, ReferenceLimits>>, axes: List<UnitAxis>, sourcePalette: Map<String, Color>,
    onPoint: (TrendPoint) -> Unit, onOpenReport: (Long) -> Unit
) {
    val minDay = points.minOf { it.day }
    val maxDay = points.maxOf { it.day }
    val density = LocalDensity.current
    val axisColumn = with(density) { 60.dp.toPx() }
    val left = axisColumn * axes.size + with(density) { 8.dp.toPx() }
    val right = with(density) { 12.dp.toPx() }
    val top = with(density) { 14.dp.toPx() }
    val plotBottom = with(density) { 218.dp.toPx() }
    val stageSpacing = with(density) { 20.dp.toPx() }
    val stageLaneEnds = mutableListOf<Long>()
    val stageLanes = stages.mapNotNull { stage ->
        val start = runCatching { LocalDate.parse(stage.startDate).toEpochDay() }.getOrNull() ?: return@mapNotNull null
        val end = runCatching { LocalDate.parse(stage.endDate).toEpochDay() }.getOrNull() ?: maxDay
        Triple(stage, start, end)
    }.sortedWith(compareBy<Triple<Stage, Long, Long>> { it.second }.thenBy { it.third }).associate { (stage, start, end) ->
        val lane = stageLaneEnds.indexOfFirst { it <= start }.let { if (it < 0) stageLaneEnds.size else it }
        if (lane == stageLaneEnds.size) stageLaneEnds.add(end) else stageLaneEnds[lane] = end
        stage.id to lane
    }
    var width by remember { mutableFloatStateOf(1f) }
    var popup by remember(points, stages) { mutableStateOf<Pair<Any, IntOffset>?>(null) }
    fun x(day: Long): Float = if (minDay == maxDay) (left + width - right) / 2f else left + ((day - minDay).toFloat() / (maxDay - minDay).toFloat()) * (width - left - right)
    fun y(value: Double, unit: String): Float {
        val axis = axes.firstOrNull { it.unit == unit } ?: return plotBottom / 2f
        return top + ((axis.max - value) / (axis.max - axis.min)).toFloat() * (plotBottom - top)
    }
    fun stageBounds(stage: Stage): Pair<Float, Float>? {
        val start = runCatching { LocalDate.parse(stage.startDate).toEpochDay() }.getOrNull() ?: return null
        val end = runCatching { LocalDate.parse(stage.endDate).toEpochDay() }.getOrNull() ?: maxDay
        if (end < minDay || start > maxDay) return null
        return x(start.coerceIn(minDay, maxDay)) to x(end.coerceIn(minDay, maxDay))
    }
    Box(Modifier.fillMaxWidth()) {
    Canvas(Modifier.fillMaxWidth().height((240 + stageLaneEnds.size * 20).dp).onSizeChanged { width = it.width.toFloat() }.pointerInput(points, stages, axes, width) {
        detectTapGestures { tap ->
            val hitRadius = 24.dp.toPx()
            val point = points.minByOrNull { candidate -> abs(x(candidate.day) - tap.x) + abs(y(candidate.number, candidate.observation.unit) - tap.y) }
            if (tap.y < plotBottom && point != null && abs(x(point.day) - tap.x) <= hitRadius && abs(y(point.number, point.observation.unit) - tap.y) <= hitRadius) {
                onPoint(point)
                popup = point to IntOffset(tap.x.toInt(), tap.y.toInt())
            } else {
                stages.mapNotNull { stage -> stageBounds(stage)?.let { stage to it } }
                    .filter { (stage, bounds) ->
                        val laneY = plotBottom + (stageLanes[stage.id] ?: 0) * stageSpacing + stageSpacing / 2f
                        tap.y in (laneY - hitRadius)..(laneY + hitRadius) &&
                            tap.x in (bounds.first - hitRadius)..(bounds.second + hitRadius)
                    }
                    .minByOrNull { (stage, _) -> abs(tap.y - (plotBottom + (stageLanes[stage.id] ?: 0) * stageSpacing + stageSpacing / 2f)) }
                    ?.first?.let { popup = it to IntOffset(tap.x.toInt(), tap.y.toInt()) }
            }
        }
    }) {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = with(density) { 10.dp.toPx() } }
        for (grid in 0..4) {
            val gridY = top + grid * (plotBottom - top) / 4f
            drawLine(Color(0xFFE4E9ED), Offset(left, gridY), Offset(size.width - right, gridY), 1f)
            axes.forEachIndexed { index, axis ->
                val value = axis.max - (axis.max - axis.min) * grid / 4.0
                labelPaint.color = android.graphics.Color.argb(255, (axis.color.red * 255).toInt(), (axis.color.green * 255).toInt(), (axis.color.blue * 255).toInt())
                drawContext.canvas.nativeCanvas.drawText("%.2f".format(value), 4f + index * axisColumn, gridY + labelPaint.textSize / 3f, labelPaint)
            }
        }
        references.forEach { (unit, source) ->
            val axis = axes.firstOrNull { it.unit == unit } ?: return@forEach
            val index = axes.indexOf(axis)
            listOfNotNull(source.second.lower, source.second.upper).distinct().forEach { limit ->
                val lineY = y(limit, unit)
                drawLine(if (axes.size == 1) Color(0xFF607D88) else axis.color.copy(alpha = 0.65f),
                    Offset(left, lineY), Offset(size.width - right, lineY), 2.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f)))
                labelPaint.color = android.graphics.Color.argb(255, (axis.color.red * 255).toInt(), (axis.color.green * 255).toInt(), (axis.color.blue * 255).toInt())
                labelPaint.isFakeBoldText = true
                drawContext.canvas.nativeCanvas.drawText("参 %.2f".format(limit), 4f + index * axisColumn, lineY - 3f, labelPaint)
                labelPaint.isFakeBoldText = false
            }
        }
        points.groupBy { it.observation.unit }.forEach { (unit, unitPoints) ->
            val color = axes.firstOrNull { it.unit == unit }?.color ?: Color(0xFF1769AA)
            unitPoints.zipWithNext().forEach { (a, b) ->
                drawLine(color, Offset(x(a.day), y(a.number, unit)), Offset(x(b.day), y(b.number, unit)), 4f)
            }
        }
        points.forEach { point ->
            val flagged = point.observation.flag.contains("↑") || point.observation.flag.contains("↓")
            val center = Offset(x(point.day), y(point.number, point.observation.unit))
            val sourceColor = sourcePalette[point.report.category] ?: Color(0xFF1769AA)
            if (flagged) drawCircle(Color(0xFFC26D1F), 10f, center)
            drawCircle(sourceColor, 7f, center)
            drawCircle(Color.White, 2.5f, center)
        }
        drawLine(Color(0xFF9AA9B0), Offset(left, plotBottom), Offset(size.width - right, plotBottom), 1.5f)
        stages.forEach { stage ->
            val bounds = stageBounds(stage) ?: return@forEach
            val lineY = plotBottom + (stageLanes[stage.id] ?: 0) * stageSpacing + stageSpacing / 2f
            drawLine(trendStageColor(stage.category), Offset(bounds.first, lineY), Offset(maxOf(bounds.second, bounds.first + 7f), lineY), 7f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
    popup?.let { (content, anchor) ->
        Popup(onDismissRequest = { popup = null }, properties = PopupProperties(focusable = true), popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = (anchorBounds.left + anchor.x - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = (anchorBounds.top + anchor.y - popupContentSize.height - 12).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }) {
            Card(Modifier.width(260.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    when (content) {
                        is TrendPoint -> {
                            Text("${content.report.sampleDate} · ${content.observation.value} ${content.observation.unit}", fontWeight = FontWeight.SemiBold)
                            Text("参考范围：${content.observation.reference.ifBlank { "报告未提供" }}")
                            Text("来源：${content.report.category} · ${content.report.type}")
                            TextButton(onClick = { popup = null; onOpenReport(content.report.id) }) { Text("查看原报告") }
                        }
                        is Stage -> {
                            Text(content.title, color = trendStageColor(content.category), fontWeight = FontWeight.SemiBold)
                            Text("${content.category} · ${content.startDate} — ${content.endDate.ifBlank { "至今" }}")
                            Text(content.note.ifBlank { "未填写具体措施" })
                        }
                    }
                }
            }
        }
    }
    }
}
