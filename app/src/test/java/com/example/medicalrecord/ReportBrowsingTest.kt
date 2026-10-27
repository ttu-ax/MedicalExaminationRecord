package com.example.medicalrecord

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportBrowsingTest {
    private fun report(id: Long, category: String, status: String = "已确认", date: String = "2026-10-27") =
        Report(id, category, date, date, "", "", status, "", category)

    private fun observation(id: Long, reportId: Long, key: String, value: String = "1") =
        Observation(id, reportId, key, value, "", "", "", key)

    @Test fun categoriesIncludeEmptyOnesAndSortByAllReportCounts() {
        val reports = listOf(report(1, "B"), report(2, "B", "待校对"), report(3, "A"))
        assertEquals(listOf("B", "A", "C"), reportCategoriesByCount(reports, listOf("C", "A", "B")))
    }

    @Test fun indicatorsUseCrossCategoryCountsAndExcludeUnplottableRecords() {
        val reports = listOf(report(1, "生化"), report(2, "其他"),
            report(3, "生化", "待校对"), report(4, "生化", date = ""))
        val rows = listOf(observation(1, 1, "A"), observation(2, 1, "B"),
            observation(3, 2, "B", "1,000"), observation(4, 2, "C"),
            observation(5, 3, "A"), observation(6, 4, "A"),
            observation(7, 1, "A", "阴性"), observation(8, 999, "A"),
            observation(9, 1, "A", "NaN"), observation(10, 1, "A", "1e2"))
        val result = categoryTrendIndicators("生化", reports, rows, emptySet())
        assertEquals(listOf("B", "A"), result.map { it.key })
        assertEquals(listOf(2, 1), result.map { it.recordCount })
        assertEquals(setOf("生化", "其他"), result.first().sources.toSet())
    }

    @Test fun pinsTakePriorityAndUnpinningRestoresCountOrder() {
        val reports = listOf(report(1, "生化"), report(2, "生化"))
        val rows = listOf(observation(1, 1, "A"), observation(2, 1, "B"), observation(3, 2, "B"))
        assertEquals(listOf("A", "B"), categoryTrendIndicators("生化", reports, rows, setOf("A", "missing")).map { it.key })
        assertEquals(listOf("B", "A"), categoryTrendIndicators("生化", reports, rows, emptySet()).map { it.key })
    }

    @Test fun equalCountsHaveStableNameOrderWithinPinnedAndNormalGroups() {
        val reports = listOf(report(1, "生化"))
        val rows = listOf("D", "B", "C", "A").mapIndexed { i, key -> observation(i.toLong(), 1, key) }
        assertEquals(listOf("B", "D", "A", "C"),
            categoryTrendIndicators("生化", reports, rows, setOf("B", "D")).map { it.key })
    }
}
