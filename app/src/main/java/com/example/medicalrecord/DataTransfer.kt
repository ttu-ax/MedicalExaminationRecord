package com.example.medicalrecord

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class TransferReport(val report: Report, val observations: List<Observation>, val imageEntry: String = "")
data class TransferData(val reports: List<TransferReport>, val categories: List<String>, val stages: List<Stage>)
data class TransferResult(val reportsAdded: Int, val reportsSkipped: Int, val stagesAdded: Int)
enum class TransferKind { Backup, Csv }
data class TransferPreview(val uri: Uri, val kind: TransferKind, val reports: Int, val observations: Int, val stages: Int,
    val images: Int, val duplicateReports: Int)

object DataTransfer {
    private const val FORMAT = "medical-record-backup"
    private const val SCHEMA_VERSION = 1
    private const val MAX_DATA_BYTES = 20L * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 100L * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 1024L * 1024 * 1024
    private val imageEntryPattern = Regex("images/[0-9]+\\.(jpg|jpeg|png|webp)")
    private val csvHeader = listOf("report_key", "type", "category", "sample_date", "report_date", "institution", "status", "model", "suggested_category", "name", "value", "unit", "reference", "flag")

    fun exportBackup(context: Context, db: RecordDb, uri: Uri) {
        val data = snapshot(db)
        val images = data.reports.mapNotNull { item ->
            val path = item.report.imagePath
            if (path.isBlank()) null else {
                val file = File(path)
                require(file.isFile) { "报告 #${item.report.id} 的原图不存在，已停止导出" }
                require(file.length() <= MAX_IMAGE_BYTES) { "报告 #${item.report.id} 的原图超过 100 MB" }
                val extension = file.extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
                "images/${item.report.id}.$extension" to file
            }
        }.toMap()
        val entriesByReportId = images.keys.associateBy { it.substringAfter('/').substringBefore('.').toLong() }
        val json = encodeData(data.copy(reports = data.reports.map { item ->
            item.copy(imageEntry = entriesByReportId[item.report.id].orEmpty())
        })).toString().toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_DATA_BYTES) { "备份记录过多，无法生成数据清单" }
        require(images.values.sumOf { it.length() } + json.size <= MAX_ARCHIVE_BYTES) { "备份数据超过 1 GB" }
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法创建备份文件")
        output.use { stream ->
            ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(ZipEntry("data.json"))
                zip.write(json)
                zip.closeEntry()
                images.forEach { (entry, file) ->
                    zip.putNextEntry(ZipEntry(entry))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun exportCsv(context: Context, db: RecordDb, uri: Uri) {
        val data = snapshot(db)
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法创建表格文件")
        OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
            writer.write('\uFEFF'.code)
            writer.write(csvHeader.joinToString(",") + "\r\n")
            data.reports.forEach { item ->
                val report = item.report
                val rows = item.observations.ifEmpty { listOf(Observation(0, 0, "", "", "", "", "", "")) }
                rows.forEach { row ->
                    writer.write(listOf(report.id.toString(), report.type, report.category, report.sampleDate,
                        report.reportDate, report.institution, report.status, report.model, report.suggestedCategory,
                        row.name, row.value, row.unit, row.reference, row.flag).joinToString(",", transform = ::csvCell))
                    writer.write("\r\n")
                }
            }
        }
    }

    fun preview(context: Context, db: RecordDb, uri: Uri, kind: TransferKind): TransferPreview {
        val data = when (kind) { TransferKind.Backup -> readBackup(context, uri, null); TransferKind.Csv -> readCsv(context, uri) }
        return TransferPreview(uri, kind, data.reports.size, data.reports.sumOf { it.observations.size }, data.stages.size,
            data.reports.count { it.imageEntry.isNotBlank() }, db.countDuplicateReports(data))
    }

    fun import(context: Context, db: RecordDb, uri: Uri, kind: TransferKind, replace: Boolean): TransferResult {
        require(kind == TransferKind.Backup || !replace) { "表格导入只能追加" }
        val staged = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        val newImages = mutableListOf<File>()
        var committed = false
        try {
            val data = when (kind) { TransferKind.Backup -> readBackup(context, uri, staged); TransferKind.Csv -> readCsv(context, uri) }
            val targetDir = File(context.filesDir, "report_images").apply { mkdirs() }
            val ready = data.copy(reports = data.reports.map { item ->
                if (item.imageEntry.isBlank()) item else {
                    val source = File(staged, item.imageEntry.substringAfter('/'))
                    require(source.isFile) { "备份缺少报告原图：${item.imageEntry}" }
                    val target = File(targetDir, "${UUID.randomUUID()}.${source.extension}")
                    newImages.add(target)
                    source.copyTo(target)
                    item.copy(report = item.report.copy(imagePath = target.absolutePath))
                }
            })
            val oldImages = if (replace) db.reports().map { it.imagePath }.filter(String::isNotBlank) else emptyList()
            val result = db.importTransfer(ready, replace)
            committed = true
            runCatching {
                if (replace) oldImages.forEach { File(it).delete() }
                if (result.reportsSkipped > 0) {
                    val retained = db.reports().map { it.imagePath }.toSet()
                    newImages.filterNot { it.absolutePath in retained }.forEach(File::delete)
                }
            }
            return result
        } catch (error: Exception) {
            if (!committed) newImages.forEach(File::delete)
            throw error
        } finally {
            staged.deleteRecursively()
        }
    }

    private fun snapshot(db: RecordDb): TransferData {
        val rows = db.observations().groupBy { it.reportId }
        return TransferData(db.reports().map { TransferReport(it, rows[it.id].orEmpty()) }, db.categories(), db.stages())
    }

    private fun encodeData(data: TransferData): JSONObject = JSONObject()
        .put("format", FORMAT).put("schema_version", SCHEMA_VERSION).put("exported_at", Instant.now().toString())
        .put("categories", JSONArray(data.categories))
        .put("stages", JSONArray().apply { data.stages.forEach { stage -> put(JSONObject()
            .put("title", stage.title).put("category", stage.category).put("start_date", stage.startDate)
            .put("end_date", stage.endDate).put("note", stage.note)) } })
        .put("reports", JSONArray().apply { data.reports.forEach { item ->
            val report = item.report
            put(JSONObject().put("type", report.type).put("category", report.category)
                .put("sample_date", report.sampleDate).put("report_date", report.reportDate)
                .put("institution", report.institution).put("status", report.status).put("model", report.model)
                .put("suggested_category", report.suggestedCategory).put("image", item.imageEntry)
                .put("observations", JSONArray().apply { item.observations.forEach { row -> put(JSONObject()
                    .put("name", row.name).put("value", row.value).put("unit", row.unit)
                    .put("reference", row.reference).put("flag", row.flag)) } }))
        } })

    private fun readBackup(context: Context, uri: Uri, staged: File?): TransferData {
        val input = context.contentResolver.openInputStream(uri) ?: error("无法打开备份文件")
        var json: ByteArray? = null
        val foundImages = mutableSetOf<String>()
        var archiveBytes = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "备份包含无效目录" }
                val isData = entry.name == "data.json"
                require(isData || imageEntryPattern.matches(entry.name)) { "备份包含无效文件：${entry.name}" }
                require(if (isData) json == null else foundImages.add(entry.name)) { "备份包含重复文件" }
                require(foundImages.size <= 5000) { "备份中的原图过多" }
                val buffer = ByteArray(32 * 1024)
                var entryBytes = 0L
                val output = if (isData) ByteArrayOutputStream() else staged?.let { File(it, entry.name.substringAfter('/')).outputStream() }
                try {
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entryBytes += count
                        archiveBytes += count
                        require(entryBytes <= (if (isData) MAX_DATA_BYTES else MAX_IMAGE_BYTES) && archiveBytes <= MAX_ARCHIVE_BYTES) { "备份文件过大" }
                        output?.write(buffer, 0, count)
                    }
                    if (isData) json = (output as ByteArrayOutputStream).toByteArray()
                } finally { output?.close() }
                zip.closeEntry()
            }
        }
        val data = decodeData(JSONObject(String(json ?: error("备份缺少 data.json"), Charsets.UTF_8)))
        val expectedImages = data.reports.mapNotNull { it.imageEntry.takeIf(String::isNotBlank) }
        require(expectedImages.distinct().size == expectedImages.size && foundImages == expectedImages.toSet()) { "备份中的原图与报告清单不一致" }
        return data
    }

    private fun decodeData(json: JSONObject): TransferData {
        require(json.optString("format") == FORMAT && json.optInt("schema_version") == SCHEMA_VERSION) { "不支持的备份格式或版本" }
        val reports = json.optJSONArray("reports") ?: error("备份缺少报告列表")
        val categories = json.optJSONArray("categories") ?: error("备份缺少分类列表")
        val stages = json.optJSONArray("stages") ?: error("备份缺少阶段列表")
        require(reports.length() <= 5000 && categories.length() <= 5000 && stages.length() <= 5000) { "备份内容过多" }
        var rowCount = 0
        return TransferData(
            reports = (0 until reports.length()).map { index ->
                val obj = reports.getJSONObject(index)
                val rows = obj.getJSONArray("observations")
                rowCount += rows.length()
                require(rowCount <= 200000) { "指标记录过多" }
                val image = obj.optString("image")
                require(image.isBlank() || imageEntryPattern.matches(image)) { "报告原图路径无效" }
                val type = obj.getString("type")
                val category = obj.getString("category")
                val sampleDate = obj.getString("sample_date")
                val reportDate = obj.getString("report_date")
                TransferReport(Report(0, type, sampleDate, reportDate,
                    obj.getString("institution"), "", obj.getString("status"),
                    obj.getString("model"), category, obj.optString("suggested_category")),
                    (0 until rows.length()).map { rowIndex ->
                        val row = rows.getJSONObject(rowIndex)
                        val name = row.getString("name")
                        Observation(0, 0, name, row.getString("value"), row.getString("unit"),
                            row.getString("reference"), row.getString("flag"), indicatorKey(name))
                    }, image)
            },
            categories = (0 until categories.length()).map { categories.getString(it) }.filter(String::isNotBlank),
            stages = (0 until stages.length()).map { index ->
                val obj = stages.getJSONObject(index)
                val start = obj.getString("start_date")
                val end = obj.getString("end_date")
                Stage(0, obj.getString("title"), obj.getString("category"),
                    start, end, obj.getString("note"))
            }
        )
    }

    private fun readCsv(context: Context, uri: Uri): TransferData {
        val input = context.contentResolver.openInputStream(uri) ?: error("无法打开表格文件")
        val bytes = input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= MAX_DATA_BYTES) { "表格文件超过 20 MB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val rows = parseCsv(String(bytes, Charsets.UTF_8).removePrefix("\uFEFF"))
        require(rows.isNotEmpty() && rows.first() == csvHeader) { "CSV 列名不匹配，请使用本应用导出的表格格式" }
        require(rows.size <= 200001) { "表格行数过多" }
        val groups = linkedMapOf<String, MutableList<List<String>>>()
        rows.drop(1).forEachIndexed { index, row ->
            require(row.size == csvHeader.size && row[0].isNotBlank()) { "第 ${index + 2} 行格式无效" }
            groups.getOrPut(row[0]) { mutableListOf() }.add(row)
        }
        require(groups.size <= 5000) { "报告数量过多" }
        val reports = groups.values.map { group ->
            val first = group.first()
            require(group.all { it.subList(1, 9) == first.subList(1, 9) }) { "同一报告的基本信息不一致" }
            val category = first[2].trim().take(40)
            require(category.isNotBlank()) { "报告分类不能为空" }
            require((first[3].isBlank() || validDate(first[3])) && (first[4].isBlank() || validDate(first[4]))) { "CSV 报告日期无效" }
            val rowsForReport = group.mapNotNull { row ->
                val name = row[9].trim().take(200)
                if (name.isBlank()) null else Observation(0, 0, name, row[10].take(200), row[11].take(200),
                    row[12].take(200), row[13].take(200), indicatorKey(name))
            }
            TransferReport(Report(0, first[1].ifBlank { category }.take(40), first[3], first[4], first[5].take(200),
                "", "待校对", first[7].take(100), category, first[8].take(40)), rowsForReport)
        }
        return TransferData(reports, reports.map { it.report.category }.distinct(), emptyList())
    }

    private fun csvCell(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
        "\"${value.replace("\"", "\"\"")}\"" else value

    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> { cell.append('"'); index++ }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> { cells.add(cell.toString()); cell.clear() }
                (char == '\n' || char == '\r') && !quoted -> {
                    cells.add(cell.toString()); cell.clear()
                    if (cells.any(String::isNotEmpty)) rows.add(cells.toList())
                    cells.clear()
                    if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                }
                else -> cell.append(char)
            }
            index++
        }
        require(!quoted) { "CSV 引号未闭合" }
        cells.add(cell.toString())
        if (cells.any(String::isNotEmpty)) rows.add(cells)
        return rows
    }
}
