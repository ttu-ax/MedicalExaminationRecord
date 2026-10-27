package com.example.medicalrecord

private val trendNumberPattern = Regex("^[-+]?\\d+(?:\\.\\d+)?$")

internal fun Observation.trendValue(): Double? {
    val text = value.trim().replace(",", "")
    return if (trendNumberPattern.matches(text)) text.toDoubleOrNull()?.takeIf { it.isFinite() } else null
}

internal fun reportCategoriesByCount(reports: List<Report>, categories: List<String>): List<String> {
    val counts = reports.groupingBy { it.category }.eachCount()
    return (categories + counts.keys).distinct()
        .sortedWith(compareByDescending<String> { counts[it] ?: 0 }.thenBy { it })
}

internal data class TrendIndicatorSummary(
    val key: String,
    val recordCount: Int,
    val sources: List<String>
)

internal fun categoryTrendIndicators(
    category: String,
    reports: List<Report>,
    observations: List<Observation>,
    pinned: Set<String>
): List<TrendIndicatorSummary> {
    val confirmed = reports.filter { it.status == "已确认" && validDate(it.sampleDate) }.associateBy { it.id }
    val rowsByKey = observations.filter {
        it.reportId in confirmed && it.trendValue() != null
    }.groupBy { it.indicatorKey }
    return rowsByKey.mapNotNull { (key, rows) ->
        val sources = rows.mapNotNull { confirmed[it.reportId]?.category }.distinct().sorted()
        if (category !in sources) null else TrendIndicatorSummary(key, rows.size, sources)
    }.sortedWith(compareByDescending<TrendIndicatorSummary> { it.key in pinned }
        .thenByDescending { it.recordCount }.thenBy { it.key })
}
