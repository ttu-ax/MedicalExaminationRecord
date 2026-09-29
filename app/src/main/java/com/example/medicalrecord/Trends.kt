package com.example.medicalrecord

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
    var activeUnit by remember(key) { mutableStateOf<String?>(null) }
    var landscape by remember(key) { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) { onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
    BackHandler(landscape) {
        landscape = false
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    val latest = all.maxOfOrNull { it.day } ?: 0L
    val points = when (window) {
        "3个月" -> all.filter { it.day >= latest - 92 }
        "1年" -> all.filter { it.day >= latest - 366 }
        else -> all
    }
    var selected by remember(key, window) { mutableStateOf<TrendPoint?>(null) }
    var selectedStage by remember(key, window) { mutableStateOf<Stage?>(null) }
    val references = points.groupBy { it.observation.unit }.mapNotNull { (unit, unitPoints) ->
        unitPoints.mapNotNull { point -> referenceLimits(point.observation.reference)?.let { point to it } }.lastOrNull()?.let { unit to it }
    }.toMap()
    val axes = axesFor(points, references)
    val axis = axes.firstOrNull { it.unit == activeUnit } ?: axes.firstOrNull()
    val chartPoints = if (axis == null) emptyList() else points.filter { it.observation.unit == axis.unit }
    val sourceCategories = chartPoints.map { it.report.category }.distinct().sorted()
    val sourcePalette = sourceCategories.mapIndexed { index, category -> category to sourceColors[index % sourceColors.size] }.toMap()
    val shownStages = if (chartPoints.isEmpty()) emptyList() else visibleStages(stages, chartPoints.first().day, chartPoints.last().day)
    if (landscape) {
        Column(Modifier.fillMaxSize().background(MedicalPalette.Canvas).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("$key  ·  横屏趋势", style = MaterialTheme.typography.titleLarge)
                WindowSelector(window) { window = it }
                TextButton(onClick = { landscape = false; activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }) { Text("退出横屏") }
            }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(0.7f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UnitSelector(axes, axis?.unit) { activeUnit = it }
                    if (axis != null) TrendPanel(chartPoints, shownStages, axis, references[axis.unit], sourcePalette, true,
                        Modifier.fillMaxWidth().weight(1f), onPoint = { selected = it }, onOpenReport = onOpenReport)
                    else Text("该时间段没有记录")
                }
                Card(Modifier.weight(0.3f).fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, MedicalPalette.Outline)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("检测记录", style = MaterialTheme.typography.titleMedium)
                        chartPoints.reversed().forEach { point ->
                            Column(Modifier.fillMaxWidth().clickable { selected = point }.padding(vertical = 5.dp)) {
                                Text("${point.report.sampleDate}  ${point.observation.value} ${point.observation.unit}", fontWeight = FontWeight.SemiBold)
                                Text(point.report.category, color = sourcePalette[point.report.category] ?: MedicalPalette.Muted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        selected?.let { point ->
                            Text("当次参考：${point.observation.reference.ifBlank { "报告未提供" }}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onOpenReport(point.report.id) }) { Text("查看原报告") }
                        }
                        if (shownStages.isNotEmpty()) Text("阶段", style = MaterialTheme.typography.titleMedium)
                        shownStages.forEach { stage ->
                            Column(Modifier.clickable { selectedStage = stage }) {
                                Text("${stage.category} · ${stage.title}", color = trendStageColor(stage.category), fontWeight = FontWeight.SemiBold)
                                Text("${stage.startDate} — ${stage.endDate.ifBlank { "至今" }}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        selectedStage?.let { Text(it.note.ifBlank { "未填写具体措施" }, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TextButton(onClick = onBack) { Text(if (sourceCategory == null) "← 返回" else "← 返回${sourceCategory}趋势") }
                Text(key, style = MaterialTheme.typography.headlineSmall)
                Text("按采样日期查看变化。数值、来源和阶段直接标在图表附近。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    WindowSelector(window) { window = it }
                    TextButton(onClick = { landscape = true; activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }) { Text("横屏展开 ↗") }
                }
                UnitSelector(axes, axis?.unit) { activeUnit = it }
            }
            item {
                if (axis == null) Text("该时间段没有记录")
                else TrendPanel(chartPoints, shownStages, axis, references[axis.unit], sourcePalette, false,
                    Modifier.fillMaxWidth(), onPoint = { selected = it }, onOpenReport = onOpenReport)
            }
            selected?.let { point -> item {
                Card(Modifier.fillMaxWidth().clickable { onOpenReport(point.report.id) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${point.report.sampleDate} · ${point.observation.value} ${point.observation.unit}", fontWeight = FontWeight.SemiBold)
                        Text("当次参考：${point.observation.reference.ifBlank { "报告未提供" }}")
                        Text("${point.report.category} · ${point.report.type} · 点击查看原报告", color = MedicalPalette.Muted)
                    }
                }
            } }
            item { TextButton(onClick = onStages) { Text("管理阶段标记 →") } }
            item { Text("全部记录", style = MaterialTheme.typography.titleMedium) }
            items(chartPoints.reversed(), key = { it.observation.id }) { point ->
                Card(Modifier.fillMaxWidth().clickable { selected = point }) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(point.report.sampleDate)
                        Text("${point.observation.value} ${point.observation.unit} ${point.observation.flag}")
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun WindowSelector(value: String, onChoose: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("3个月", "1年", "全部").forEach { option ->
            if (value == option) Button(onClick = { onChoose(option) }) { Text(option) }
            else OutlinedButton(onClick = { onChoose(option) }) { Text(option) }
        }
    }
}

@Composable
private fun UnitSelector(axes: List<UnitAxis>, current: String?, onChoose: (String) -> Unit) {
    if (axes.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        axes.forEach { axis ->
            if (axis.unit == current) Button(onClick = { onChoose(axis.unit) }) { Text(displayUnit(axis.unit)) }
            else OutlinedButton(onClick = { onChoose(axis.unit) }) { Text(displayUnit(axis.unit)) }
        }
    }
}

@Composable
private fun TrendPanel(
    points: List<TrendPoint>, stages: List<Stage>, axis: UnitAxis,
    reference: Pair<TrendPoint, ReferenceLimits>?, sourcePalette: Map<String, Color>, wide: Boolean,
    modifier: Modifier = Modifier, onPoint: (TrendPoint) -> Unit, onOpenReport: (Long) -> Unit
) {
    val limits = reference?.second
    val range = when {
        limits?.lower != null && limits.upper != null -> "${limits.lower}–${limits.upper}"
        limits?.lower != null -> "≥ ${limits.lower}"
        limits?.upper != null -> "≤ ${limits.upper}"
        else -> "报告未提供"
    }
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, MedicalPalette.Outline)) {
        Column((if (wide) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (wide) {
                Text("${displayUnit(axis.unit)}  ·  参考范围 $range", color = axis.color, fontWeight = FontWeight.SemiBold)
            } else {
                Text("指标值 · ${displayUnit(axis.unit)}", color = axis.color, fontWeight = FontWeight.SemiBold)
                Text("参考范围 $range" + (reference?.let { " · ${it.first.report.sampleDate} 报告" } ?: ""), style = MaterialTheme.typography.bodySmall)
                if (points.isNotEmpty()) Text("日期 ${points.first().report.sampleDate} — ${points.last().report.sampleDate}", color = MedicalPalette.Muted, style = MaterialTheme.typography.bodySmall)
                if (sourcePalette.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    sourcePalette.forEach { (category, color) -> Text("● $category", color = color, style = MaterialTheme.typography.labelMedium) }
                }
            }
            if (!wide && points.size > 3) Text("左右滑动图表查看全部日期 →", color = MedicalPalette.Muted, style = MaterialTheme.typography.bodySmall)
            DirectTrendChart(points, stages, limits, axis, sourcePalette, if (wide) 145.dp else 235.dp,
                onPoint = onPoint, onOpenReport = onOpenReport)
            if (!wide && stages.isNotEmpty()) {
                Text("阶段（彩色线段）", style = MaterialTheme.typography.labelLarge)
                stages.forEach { stage ->
                    Text("━ ${stage.category} · ${stage.title}  ·  ${stage.startDate}—${stage.endDate.ifBlank { "至今" }}",
                        color = trendStageColor(stage.category), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (reference != null) Text("参考范围取最近一份可解析报告；各次报告可能不同。", color = MedicalPalette.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DirectTrendChart(
    points: List<TrendPoint>, stages: List<Stage>, reference: ReferenceLimits?, axis: UnitAxis,
    sourcePalette: Map<String, Color>, baseHeight: Dp,
    onPoint: (TrendPoint) -> Unit, onOpenReport: (Long) -> Unit
) {
    if (points.isEmpty()) return
    val minDay = points.minOf { it.day }
    val maxDay = points.maxOf { it.day }
    val density = LocalDensity.current
    val compact = baseHeight < 180.dp
    val left = with(density) { 68.dp.toPx() }
    val right = with(density) { 22.dp.toPx() }
    val top = with(density) { (if (compact) 26.dp else 38.dp).toPx() }
    val plotBottom = with(density) { (baseHeight - 12.dp).toPx() }
    val laneHeight = with(density) { (if (compact) 24.dp else 32.dp).toPx() }
    val stageOffset = with(density) { (if (compact) 18.dp else 23.dp).toPx() }
    val laneDp = if (compact) 24 else 32
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
    val chartHeight = baseHeight + (stageLaneEnds.size * laneDp + if (compact) 35 else 55).dp
    var width by remember { mutableFloatStateOf(1f) }
    var popup by remember(points, stages) { mutableStateOf<Pair<Any, IntOffset>?>(null) }
    fun x(day: Long): Float = if (minDay == maxDay) (left + width - right) / 2f
        else left + (day - minDay).toFloat() / (maxDay - minDay).toFloat() * (width - left - right)
    fun y(value: Double): Float = top + ((axis.max - value) / (axis.max - axis.min)).toFloat() * (plotBottom - top)
    fun stageBounds(stage: Stage): Pair<Float, Float>? {
        val start = runCatching { LocalDate.parse(stage.startDate).toEpochDay() }.getOrNull() ?: return null
        val end = runCatching { LocalDate.parse(stage.endDate).toEpochDay() }.getOrNull() ?: maxDay
        if (start > maxDay || end < minDay) return null
        return x(start.coerceIn(minDay, maxDay)) to x(end.coerceIn(minDay, maxDay))
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val chartWidth = maxOf(maxWidth, (points.size * 76 + 80).dp)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Box(Modifier.width(chartWidth)) {
                Canvas(Modifier.width(chartWidth).height(chartHeight).onSizeChanged { width = it.width.toFloat() }
                    .pointerInput(points, stages, axis, width) {
                        detectTapGestures { tap ->
                            val hit = 24.dp.toPx()
                            val point = points.minByOrNull { abs(x(it.day) - tap.x) + abs(y(it.number) - tap.y) }
                            if (tap.y < plotBottom && point != null && abs(x(point.day) - tap.x) < hit && abs(y(point.number) - tap.y) < hit) {
                                onPoint(point)
                                popup = point to IntOffset(tap.x.toInt(), tap.y.toInt())
                            } else if (tap.y >= plotBottom) {
                                stages.mapNotNull { stage -> stageBounds(stage)?.let { stage to it } }
                                    .firstOrNull { (stage, bounds) ->
                                        val lineY = plotBottom + stageOffset + (stageLanes[stage.id] ?: 0) * laneHeight
                                        abs(tap.y - lineY) < hit && tap.x in (bounds.first - hit)..(bounds.second + hit)
                                    }?.first?.let { popup = it to IntOffset(tap.x.toInt(), tap.y.toInt()) }
                            }
                        }
                    }) {
                    val canvas = drawContext.canvas.nativeCanvas
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = with(density) { 11.dp.toPx() }
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    }
                    if (reference?.lower != null && reference.upper != null) {
                        val high = y(reference.upper)
                        val low = y(reference.lower)
                        drawRect(MedicalPalette.Mint.copy(alpha = 0.42f), Offset(left, high),
                            androidx.compose.ui.geometry.Size(size.width - left - right, low - high))
                    }
                    for (index in 0..4) {
                        val gridY = top + index * (plotBottom - top) / 4f
                        drawLine(MedicalPalette.Outline, Offset(left, gridY), Offset(size.width - right, gridY), 1.dp.toPx())
                        paint.color = MedicalPalette.Muted.toArgb()
                        paint.textAlign = Paint.Align.LEFT
                        val tick = axis.max - (axis.max - axis.min) * index / 4.0
                        val nearReference = listOfNotNull(reference?.lower, reference?.upper)
                            .any { abs(y(it) - gridY) < 18.dp.toPx() }
                        if (!nearReference) canvas.drawText("%.2f".format(tick), 2.dp.toPx(), gridY + paint.textSize / 3f, paint)
                    }
                    listOfNotNull(reference?.lower, reference?.upper).distinct().forEach { limit ->
                        val limitY = y(limit)
                        drawLine(MedicalPalette.Teal.copy(alpha = 0.65f), Offset(left, limitY), Offset(size.width - right, limitY),
                            1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())))
                        paint.color = MedicalPalette.Teal.toArgb()
                        paint.textAlign = Paint.Align.LEFT
                        canvas.drawText("参 %.2f".format(limit), 2.dp.toPx(), limitY - 3.dp.toPx(), paint)
                    }
                    points.zipWithNext().forEach { (a, b) ->
                        drawLine(axis.color, Offset(x(a.day), y(a.number)), Offset(x(b.day), y(b.number)),
                            5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                    points.forEach { point ->
                        val center = Offset(x(point.day), y(point.number))
                        drawCircle(Color.White, 9.dp.toPx(), center)
                        drawCircle(sourcePalette[point.report.category] ?: axis.color, 7.dp.toPx(), center)
                        paint.color = axis.color.toArgb()
                        paint.textAlign = Paint.Align.CENTER
                        canvas.drawText(point.observation.value, center.x, center.y - 12.dp.toPx(), paint)
                        paint.color = (sourcePalette[point.report.category] ?: MedicalPalette.Muted).toArgb()
                        paint.textSize = 10.dp.toPx()
                        canvas.drawText(point.report.category.take(4), center.x, center.y + 21.dp.toPx(), paint)
                        paint.textSize = 11.dp.toPx()
                    }
                    drawLine(MedicalPalette.Muted, Offset(left, plotBottom), Offset(size.width - right, plotBottom), 1.2.dp.toPx())
                    stages.forEach { stage ->
                        val bounds = stageBounds(stage) ?: return@forEach
                        val lineY = plotBottom + stageOffset + (stageLanes[stage.id] ?: 0) * laneHeight
                        val color = trendStageColor(stage.category)
                        drawLine(color, Offset(bounds.first, lineY), Offset(maxOf(bounds.second, bounds.first + 8.dp.toPx()), lineY),
                            6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        paint.color = color.toArgb()
                        paint.textAlign = Paint.Align.LEFT
                        canvas.drawText("${stage.category} · ${stage.title}".take(12), bounds.first + 2.dp.toPx(), lineY - 7.dp.toPx(), paint)
                    }
                    paint.color = MedicalPalette.Muted.toArgb()
                    paint.textAlign = Paint.Align.CENTER
                    points.distinctBy { it.day }.forEach { point ->
                        val date = LocalDate.ofEpochDay(point.day)
                        canvas.drawText("${date.monthValue}/${date.dayOfMonth}", x(point.day),
                            plotBottom + (stageLaneEnds.size * laneDp + if (compact) 26 else 43).dp.toPx(), paint)
                    }
                }
                popup?.let { (content, anchor) ->
                    Popup(onDismissRequest = { popup = null }, properties = PopupProperties(focusable = true), popupPositionProvider = object : PopupPositionProvider {
                        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: IntSize): IntOffset {
                            val px = (anchorBounds.left + anchor.x - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                            val py = (anchorBounds.top + anchor.y - popupContentSize.height - 12).coerceAtLeast(0)
                            return IntOffset(px, py)
                        }
                    }) {
                        Card(Modifier.width(250.dp)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                when (content) {
                                    is TrendPoint -> {
                                        Text("${content.report.sampleDate} · ${content.observation.value} ${content.observation.unit}", fontWeight = FontWeight.SemiBold)
                                        Text("${content.report.category} · 参考 ${content.observation.reference.ifBlank { "未提供" }}")
                                        TextButton(onClick = { popup = null; onOpenReport(content.report.id) }) { Text("查看原报告") }
                                    }
                                    is Stage -> {
                                        Text(content.title, color = trendStageColor(content.category), fontWeight = FontWeight.SemiBold)
                                        Text("${content.startDate} — ${content.endDate.ifBlank { "至今" }}")
                                        Text(content.note.ifBlank { "未填写具体措施" })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
