package com.example.medicalrecord

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
private val sourceColors = listOf(Color(0xFF1769AA), Color(0xFFB35F2B), Color(0xFF7658A8), Color(0xFF13816F), Color(0xFF9A435E))
private fun trendStageColor(stage: Stage): Color = stage.themeColor().primary
private fun trendStageColor(category: String): Color = StagePalette.styles[(category.hashCode() and 0x7fffffff) % StagePalette.styles.size].primary

private fun axisFor(points: List<TrendPoint>, reference: Pair<TrendPoint, ReferenceLimits>?): UnitAxis? =
    if (points.isEmpty()) null else run {
        val unit = points.lastOrNull { it.observation.unit.isNotBlank() }?.observation?.unit.orEmpty()
        val values = points.map { it.number } +
            listOfNotNull(reference?.second?.lower, reference?.second?.upper)
        val low = values.minOrNull() ?: 0.0
        val high = values.maxOrNull() ?: 1.0
        val spread = (high - low).coerceAtLeast(abs(high) * 0.1).coerceAtLeast(1.0)
        UnitAxis(unit, low - spread * 0.12, high + spread * 0.12, Color(0xFF1769AA))
    }

private fun displayUnit(unit: String) = unit.ifBlank { "无单位" }

private fun pointsFor(key: String, rows: List<Observation>, reports: List<Report>): List<TrendPoint> {
    val byId = reports.associateBy { it.id }
    return rows.filter { it.indicatorKey == key }.mapNotNull { row ->
        val report = byId[row.reportId] ?: return@mapNotNull null
        if (report.status != "已确认" || !validDate(report.sampleDate)) return@mapNotNull null
        val value = row.trendValue() ?: return@mapNotNull null
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
    val reference = points.mapNotNull { point -> referenceLimits(point.observation.reference)?.let { point to it } }.lastOrNull()
    val axis = axisFor(points, reference)
    val chartPoints = points
    val sourceCategories = chartPoints.map { it.report.category }.distinct().sorted()
    val sourcePalette = sourceCategories.mapIndexed { index, category -> category to sourceColors[index % sourceColors.size] }.toMap()
    val shownStages = if (chartPoints.isEmpty()) emptyList() else visibleStages(stages, chartPoints.first().day, chartPoints.last().day)
    if (landscape) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("$key  ·  横屏趋势", style = MaterialTheme.typography.titleLarge)
                WindowSelector(window) { window = it }
                TextButton(onClick = { landscape = false; activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }) { Text("退出横屏") }
            }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(0.7f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (axis != null) TrendPanel(chartPoints, shownStages, axis, reference, sourcePalette, true,
                        Modifier.fillMaxWidth().weight(1f), onPoint = { selected = it }, onOpenReport = onOpenReport)
                    else Text("该时间段没有记录")
                }
                Card(Modifier.weight(0.3f).fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))) {
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
                                Text("${stage.category} · ${stage.title}", color = trendStageColor(stage), fontWeight = FontWeight.SemiBold)
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
            }
            item {
                if (axis == null) Text("该时间段没有记录")
                else TrendPanel(chartPoints, shownStages, axis, reference, sourcePalette, false,
                    Modifier.fillMaxWidth(), onPoint = { selected = it }, onOpenReport = onOpenReport)
            }
            selected?.let { point -> item {
                Card(
                    Modifier.fillMaxWidth().bouncyClickable { onOpenReport(point.report.id) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${point.report.sampleDate} · ${point.observation.value} ${point.observation.unit}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                            FlagBadge(point.observation.flag)
                        }
                        Text("当次参考：${point.observation.reference.ifBlank { "报告未提供" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${point.report.category} · ${point.report.type} · 点击查看原报告", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } }
            item { TextButton(onClick = onStages) { Text("管理阶段标记 →") } }
            item { Text("全部记录", style = MaterialTheme.typography.titleMedium) }
            items(chartPoints.reversed(), key = { it.observation.id }) { point ->
                Card(
                    Modifier.fillMaxWidth().bouncyClickable { selected = point },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(point.report.sampleDate, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FlagBadge(point.observation.flag)
                            Text("${point.observation.value} ${point.observation.unit}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
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
    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))) {
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
            if (!wide) Text("可单指左右滑动或双指捏合缩放尺度 · 点击点位可查看报告", color = MedicalPalette.Muted, style = MaterialTheme.typography.bodySmall)
            DirectTrendChart(points, stages, limits, axis, sourcePalette, if (wide) 145.dp else 235.dp,
                onPoint = onPoint, onOpenReport = onOpenReport)
            if (!wide && stages.isNotEmpty()) {
                Text("阶段（彩色线段）", style = MaterialTheme.typography.labelLarge)
                stages.forEach { stage ->
                    Text("━ ${stage.category} · ${stage.title}  ·  ${stage.startDate}—${stage.endDate.ifBlank { "至今" }}",
                        color = trendStageColor(stage), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (reference != null) Text("参考范围取最近一份可解析报告；各次报告可能不同。", color = MedicalPalette.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ZoomChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.bouncyClickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
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

    val yAxisWidth = 56.dp
    val yAxisWidthPx = with(density) { yAxisWidth.toPx() }
    val leftPad = with(density) { 16.dp.toPx() }
    val rightPad = with(density) { 38.dp.toPx() }
    val top = with(density) { (if (compact) 26.dp else 36.dp).toPx() }
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

    var zoomScale by remember { mutableFloatStateOf(1.0f) }
    var isFitMode by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    var initialScrolled by remember { mutableStateOf(false) }

    var popup by remember(points, stages) { mutableStateOf<Pair<Any, IntOffset>?>(null) }
    var activePointId by remember { mutableStateOf<Long?>(null) }

    fun y(value: Double): Float = top + ((axis.max - value) / (axis.max - axis.min)).toFloat() * (plotBottom - top)

    val primaryColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val defaultCategoryColor = MaterialTheme.colorScheme.onSurfaceVariant
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val pointSurfaceColor = MaterialTheme.colorScheme.surface
    val refZoneColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val totalAvailableWidth = maxWidth
        val minChartContentWidth = maxOf(0.dp, totalAvailableWidth - yAxisWidth)
        val minChartContentWidthPx = with(density) { minChartContentWidth.toPx() }

        val sortedPoints = remember(points) { points.sortedBy { it.day } }

        val (pointXMap, neededWidthPx) = remember(sortedPoints, isFitMode, zoomScale, minChartContentWidthPx) {
            density.run {
                val map = mutableMapOf<Long, Float>()
                if (sortedPoints.isEmpty()) {
                    map to minChartContentWidthPx
                } else if (isFitMode) {
                    val availablePlotWidthPx = maxOf(1f, minChartContentWidthPx - leftPad - rightPad)
                    if (minDay == maxDay) {
                        val midX = leftPad + availablePlotWidthPx / 2f
                        sortedPoints.forEachIndexed { idx, pt ->
                            map[pt.observation.id] = midX + (idx - sortedPoints.size / 2f) * 8.dp.toPx()
                        }
                    } else {
                        val maxAllowedGap = if (sortedPoints.size > 1) {
                            availablePlotWidthPx / (sortedPoints.size - 1)
                        } else 40.dp.toPx()
                        val fitMinGap = minOf(40.dp.toPx(), maxOf(4.dp.toPx(), maxAllowedGap))
                        var lastX = -Float.MAX_VALUE
                        for (pt in sortedPoints) {
                            val rawX = leftPad + (pt.day - minDay).toFloat() / (maxDay - minDay).toFloat() * availablePlotWidthPx
                            val posX = maxOf(rawX, lastX + fitMinGap).coerceAtMost(minChartContentWidthPx - rightPad)
                            map[pt.observation.id] = posX
                            lastX = posX
                        }
                    }
                    map to minChartContentWidthPx
                } else {
                    val effectiveMinGap = (52f * zoomScale).dp.toPx()
                    val baseTimeWidthPx = ((sortedPoints.size * 80f + 100f) * zoomScale).dp.toPx()
                    val availablePlotWidthPx = maxOf(1f, baseTimeWidthPx - leftPad - rightPad)
                    var currentX = leftPad
                    for (pt in sortedPoints) {
                        val rawX = if (minDay == maxDay) {
                            currentX
                        } else {
                            leftPad + (pt.day - minDay).toFloat() / (maxDay - minDay).toFloat() * availablePlotWidthPx
                        }
                        val posX = maxOf(rawX, currentX)
                        map[pt.observation.id] = posX
                        currentX = posX + effectiveMinGap
                    }
                    val requiredWidth = maxOf(minChartContentWidthPx, currentX + rightPad + 24.dp.toPx())
                    map to requiredWidth
                }
            }
        }

        val chartContentWidth = with(density) { neededWidthPx.toDp() }
        val chartContentWidthPx = neededWidthPx

        LaunchedEffect(chartContentWidth, scrollState.maxValue) {
            if (!initialScrolled && !isFitMode && scrollState.maxValue > 0) {
                scrollState.scrollTo(scrollState.maxValue)
                initialScrolled = true
            }
        }

        fun getX(point: TrendPoint): Float = pointXMap[point.observation.id] ?: leftPad

        fun stageBounds(stage: Stage): Pair<Float, Float>? {
            val start = runCatching { LocalDate.parse(stage.startDate).toEpochDay() }.getOrNull() ?: return null
            val end = runCatching { LocalDate.parse(stage.endDate).toEpochDay() }.getOrNull() ?: maxDay
            if (start > maxDay || end < minDay) return null

            fun dayToX(day: Long): Float {
                if (sortedPoints.isEmpty()) return leftPad
                if (day <= sortedPoints.first().day) return pointXMap[sortedPoints.first().observation.id] ?: leftPad
                if (day >= sortedPoints.last().day) return pointXMap[sortedPoints.last().observation.id] ?: (chartContentWidthPx - rightPad)
                val p1 = sortedPoints.lastOrNull { it.day <= day } ?: sortedPoints.first()
                val p2 = sortedPoints.firstOrNull { it.day > day } ?: sortedPoints.last()
                val x1 = pointXMap[p1.observation.id] ?: leftPad
                val x2 = pointXMap[p2.observation.id] ?: (chartContentWidthPx - rightPad)
                if (p1.day == p2.day) return x1
                val fraction = (day - p1.day).toFloat() / (p2.day - p1.day).toFloat()
                return x1 + fraction * (x2 - x1)
            }

            val startX = dayToX(start)
            val endX = dayToX(end)
            return startX to maxOf(endX, startX + with(density) { 12.dp.toPx() })
        }

        Column(Modifier.fillMaxWidth()) {
            // Row 1: Presets & jump to latest
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ZoomChip(
                    text = "一屏全览",
                    selected = isFitMode,
                    onClick = {
                        isFitMode = true
                        zoomScale = 1.0f
                        coroutineScope.launch { scrollState.scrollTo(0) }
                    }
                )
                ZoomChip(
                    text = "标准",
                    selected = !isFitMode && abs(zoomScale - 1.0f) < 0.1f,
                    onClick = {
                        isFitMode = false
                        zoomScale = 1.0f
                    }
                )
                ZoomChip(
                    text = "展开 2x",
                    selected = !isFitMode && abs(zoomScale - 2.0f) < 0.1f,
                    onClick = {
                        isFitMode = false
                        zoomScale = 2.0f
                    }
                )
                ZoomChip(
                    text = "展开 3x",
                    selected = !isFitMode && abs(zoomScale - 3.0f) < 0.1f,
                    onClick = {
                        isFitMode = false
                        zoomScale = 3.0f
                    }
                )
                if (!isFitMode && scrollState.maxValue > 0) {
                    Spacer(Modifier.weight(1f))
                    ZoomChip(
                        text = "至最新 →",
                        selected = false,
                        onClick = {
                            coroutineScope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                        }
                    )
                }
            }

            // Row 2: Percentage scale & Stepper controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isFitMode) "模式：一屏全览" else "尺度：${(zoomScale * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isFitMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "· 可双指捏合缩放",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.bouncyClickable {
                            isFitMode = false
                            zoomScale = (zoomScale - 0.25f).coerceAtLeast(0.5f)
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ) {
                        Text(
                            "－",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Surface(
                        modifier = Modifier.bouncyClickable {
                            isFitMode = false
                            zoomScale = (zoomScale + 0.25f).coerceAtMost(4.0f)
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ) {
                        Text(
                            "＋",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (!isFitMode && abs(zoomScale - 1.0f) > 0.05f) {
                        TextButton(
                            onClick = { zoomScale = 1.0f },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("重置", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth()) {
                // 1. FIXED Y-AXIS (Left side)
                Canvas(
                    Modifier
                        .width(yAxisWidth)
                        .height(chartHeight)
                ) {
                    val canvas = drawContext.canvas.nativeCanvas
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = with(density) { 10.5.dp.toPx() }
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    }

                    for (index in 0..4) {
                        val gridY = top + index * (plotBottom - top) / 4f
                        val tick = axis.max - (axis.max - axis.min) * index / 4.0
                        val nearReference = listOfNotNull(reference?.lower, reference?.upper)
                            .any { abs(y(it) - gridY) < 16.dp.toPx() }
                        if (!nearReference) {
                            paint.color = axisTextColor
                            paint.textAlign = Paint.Align.RIGHT
                            canvas.drawText("%.2f".format(tick), yAxisWidthPx - 6.dp.toPx(), gridY + paint.textSize / 3f, paint)
                        }
                    }

                    listOfNotNull(reference?.lower, reference?.upper).distinct().forEach { limit ->
                        val limitY = y(limit)
                        paint.color = primaryColor.toArgb()
                        paint.textAlign = Paint.Align.RIGHT
                        canvas.drawText("参 %.2f".format(limit), yAxisWidthPx - 6.dp.toPx(), limitY + paint.textSize / 3f, paint)
                    }

                    drawLine(
                        color = gridColor,
                        start = Offset(yAxisWidthPx - 1f, top),
                        end = Offset(yAxisWidthPx - 1f, plotBottom),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // 2. SCROLLABLE & PINCH-ZOOMABLE CHART AREA (Right side)
                Box(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(scrollState)
                ) {
                    Box(
                        Modifier
                            .width(chartContentWidth)
                            .pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        val active = event.changes.filter { it.pressed }
                                        if (active.size >= 2) {
                                            val zoom = event.calculateZoom()
                                            if (zoom != 1f) {
                                                isFitMode = false
                                                val newScale = (zoomScale * zoom).coerceIn(0.5f, 4.0f)
                                                active.forEach { it.consume() }
                                                zoomScale = newScale
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                    ) {
                        Canvas(
                            Modifier
                                .width(chartContentWidth)
                                .height(chartHeight)
                                .pointerInput(points, stages, axis, pointXMap, chartContentWidth) {
                                    detectTapGestures { tap ->
                                        val hit = 28.dp.toPx()
                                        val point = points.minByOrNull { abs(getX(it) - tap.x) + abs(y(it.number) - tap.y) }
                                        if (tap.y < plotBottom && point != null && abs(getX(point) - tap.x) < hit && abs(y(point.number) - tap.y) < hit) {
                                            activePointId = point.observation.id
                                            onPoint(point)
                                            popup = point to IntOffset(getX(point).toInt(), y(point.number).toInt())
                                        } else if (tap.y >= plotBottom) {
                                            stages.mapNotNull { stage -> stageBounds(stage)?.let { stage to it } }
                                                .firstOrNull { (stage, bounds) ->
                                                    val lineY = plotBottom + stageOffset + (stageLanes[stage.id] ?: 0) * laneHeight
                                                    abs(tap.y - lineY) < hit && tap.x in (bounds.first - hit)..(bounds.second + hit)
                                                }?.first?.let { stage ->
                                                    val midStageX = stageBounds(stage)?.let { (it.first + it.second) / 2f }?.toInt() ?: tap.x.toInt()
                                                    val lineY = plotBottom + stageOffset + (stageLanes[stage.id] ?: 0) * laneHeight
                                                    popup = stage to IntOffset(midStageX, lineY.toInt())
                                                }
                                        }
                                    }
                                }
                        ) {
                        val canvas = drawContext.canvas.nativeCanvas
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            textSize = with(density) { 11.dp.toPx() }
                            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                        }

                        if (reference?.lower != null && reference.upper != null) {
                            val high = y(reference.upper)
                            val low = y(reference.lower)
                            drawRect(
                                refZoneColor,
                                Offset(0f, high),
                                androidx.compose.ui.geometry.Size(size.width, low - high)
                            )
                        }

                        for (index in 0..4) {
                            val gridY = top + index * (plotBottom - top) / 4f
                            drawLine(gridColor, Offset(0f, gridY), Offset(size.width, gridY), 1.dp.toPx())
                        }

                        listOfNotNull(reference?.lower, reference?.upper).distinct().forEach { limit ->
                            val limitY = y(limit)
                            drawLine(
                                primaryColor.copy(alpha = 0.65f),
                                Offset(0f, limitY),
                                Offset(size.width, limitY),
                                1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx()))
                            )
                        }

                        points.zipWithNext().forEach { (a, b) ->
                            drawLine(
                                axis.color,
                                Offset(getX(a), y(a.number)),
                                Offset(getX(b), y(b.number)),
                                4.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                        }

                        val activePoint = points.firstOrNull { it.observation.id == activePointId }
                        if (activePoint != null) {
                            val ax = getX(activePoint)
                            val ay = y(activePoint.number)
                            drawLine(
                                primaryColor.copy(alpha = 0.45f),
                                Offset(ax, top),
                                Offset(ax, plotBottom),
                                1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
                            )
                            drawCircle(primaryColor.copy(alpha = 0.22f), 15.dp.toPx(), Offset(ax, ay))
                        }

                        val minNumber = points.minOf { it.number }
                        val maxNumber = points.maxOf { it.number }
                        var lastDrawnLabelX = -Float.MAX_VALUE
                        val minLabelGap = with(density) { 46.dp.toPx() }

                        points.forEachIndexed { idx, point ->
                            val px = getX(point)
                            val py = y(point.number)
                            val center = Offset(px, py)
                            val isFirstOrLast = idx == 0 || idx == points.size - 1
                            val isExtreme = point.number == minNumber || point.number == maxNumber
                            val isSelected = point.observation.id == activePointId
                            val shouldDrawLabel = isSelected || isFirstOrLast || isExtreme || (px - lastDrawnLabelX >= minLabelGap)

                            drawCircle(pointSurfaceColor, 9.dp.toPx(), center)
                            drawCircle(sourcePalette[point.report.category] ?: axis.color, 6.5f.dp.toPx(), center)

                            if (shouldDrawLabel) {
                                lastDrawnLabelX = px
                                val valueText = point.observation.value
                                val textWidth = paint.measureText(valueText)
                                val halfTextWidth = textWidth / 2f

                                val (drawX, align) = when {
                                    px + halfTextWidth > size.width - 4.dp.toPx() -> (size.width - 6.dp.toPx()) to Paint.Align.RIGHT
                                    px - halfTextWidth < 4.dp.toPx() -> 4.dp.toPx() to Paint.Align.LEFT
                                    else -> px to Paint.Align.CENTER
                                }

                                paint.color = axis.color.toArgb()
                                paint.textAlign = align
                                canvas.drawText(valueText, drawX, center.y - 12.dp.toPx(), paint)

                                paint.color = (sourcePalette[point.report.category] ?: defaultCategoryColor).toArgb()
                                paint.textSize = 10.dp.toPx()
                                canvas.drawText(point.report.category.take(4), drawX, center.y + 20.dp.toPx(), paint)
                                paint.textSize = 11.dp.toPx()
                            }
                        }

                        drawLine(dividerColor, Offset(0f, plotBottom), Offset(size.width, plotBottom), 1.2.dp.toPx())

                        stages.forEach { stage ->
                            val bounds = stageBounds(stage) ?: return@forEach
                            val lineY = plotBottom + stageOffset + (stageLanes[stage.id] ?: 0) * laneHeight
                            val color = trendStageColor(stage)
                            drawLine(
                                color,
                                Offset(bounds.first, lineY),
                                Offset(maxOf(bounds.second, bounds.first + 8.dp.toPx()), lineY),
                                6.dp.toPx(),
                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                            )
                            paint.color = color.toArgb()
                            paint.textAlign = Paint.Align.LEFT
                            canvas.drawText("${stage.category} · ${stage.title}".take(12), bounds.first + 2.dp.toPx(), lineY - 7.dp.toPx(), paint)
                        }

                        paint.color = axisTextColor
                        var lastDateX = -Float.MAX_VALUE
                        val minDateGap = with(density) { 38.dp.toPx() }
                        val sortedDays = points.distinctBy { it.day }.sortedBy { it.day }

                        sortedDays.forEachIndexed { dIdx, point ->
                            val px = getX(point)
                            val isEdge = dIdx == 0 || dIdx == sortedDays.size - 1
                            if (isEdge || px - lastDateX >= minDateGap) {
                                lastDateX = px
                                val date = LocalDate.ofEpochDay(point.day)
                                val dateStr = "${date.monthValue}/${date.dayOfMonth}"
                                val dateWidth = paint.measureText(dateStr)
                                val halfDateWidth = dateWidth / 2f

                                val (drawDateX, dateAlign) = when {
                                    px + halfDateWidth > size.width - 4.dp.toPx() -> (size.width - 4.dp.toPx()) to Paint.Align.RIGHT
                                    px - halfDateWidth < 4.dp.toPx() -> 4.dp.toPx() to Paint.Align.LEFT
                                    else -> px to Paint.Align.CENTER
                                }

                                paint.textAlign = dateAlign
                                canvas.drawText(
                                    dateStr,
                                    drawDateX,
                                    plotBottom + (stageLaneEnds.size * laneDp + if (compact) 26 else 43).dp.toPx(),
                                    paint
                                )
                            }
                        }
                    }

                    popup?.let { (content, anchor) ->
                        Popup(
                            onDismissRequest = { popup = null; activePointId = null },
                            properties = PopupProperties(focusable = true),
                            popupPositionProvider = object : PopupPositionProvider {
                                override fun calculatePosition(
                                    anchorBounds: IntRect,
                                    windowSize: IntSize,
                                    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                    popupContentSize: IntSize
                                ): IntOffset {
                                    val currentTargetX = when (content) {
                                        is TrendPoint -> getX(content)
                                        is Stage -> stageBounds(content)?.let { (it.first + it.second) / 2f } ?: anchor.x.toFloat()
                                        else -> anchor.x.toFloat()
                                    }
                                    val currentTargetY = when (content) {
                                        is TrendPoint -> y(content.number)
                                        is Stage -> (plotBottom + stageOffset + (stageLanes[content.id] ?: 0) * laneHeight)
                                        else -> anchor.y.toFloat()
                                    }
                                    val px = (anchorBounds.left + currentTargetX.toInt() - popupContentSize.width / 2)
                                        .coerceIn(12, (windowSize.width - popupContentSize.width - 12).coerceAtLeast(0))
                                    val py = (anchorBounds.top + currentTargetY.toInt() - popupContentSize.height - 14).coerceAtLeast(16)
                                    return IntOffset(px, py)
                                }
                            }
                        ) {
                            Card(
                                Modifier.width(260.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    when (content) {
                                        is TrendPoint -> {
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Text(content.report.sampleDate, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                                FlagBadge(content.observation.flag)
                                            }
                                            Text(
                                                "${content.observation.value} ${content.observation.unit}",
                                                style = MaterialTheme.typography.headlineSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text("当次参考：${content.observation.reference.ifBlank { "未提供" }}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                            Text("来源分类：${content.report.category} · ${content.report.type}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                            Button(
                                                onClick = { popup = null; activePointId = null; onOpenReport(content.report.id) },
                                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                                            ) {
                                                Text("查看原报告")
                                            }
                                        }
                                        is Stage -> {
                                            Text(content.title, color = trendStageColor(content), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                            Text("${content.startDate} — ${content.endDate.ifBlank { "至今" }}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                            Text(content.note.ifBlank { "未填写具体措施" }, style = MaterialTheme.typography.bodyMedium)
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
}
}
