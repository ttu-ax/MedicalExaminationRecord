package com.example.medicalrecord

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

data class Report(
    val id: Long,
    val type: String,
    val sampleDate: String,
    val reportDate: String,
    val institution: String,
    val imagePath: String,
    val status: String,
    val model: String,
    val category: String = type,
    val suggestedCategory: String = ""
)

data class Observation(
    val id: Long,
    val reportId: Long,
    val name: String,
    val value: String,
    val unit: String,
    val reference: String,
    val flag: String,
    val indicatorKey: String
) {
    fun number(): Double? = Regex("[-+]?\\d+(?:\\.\\d+)?").find(value)?.value?.toDoubleOrNull()
}

data class Stage(
    val id: Long,
    val title: String,
    val category: String,
    val startDate: String,
    val endDate: String,
    val note: String
)

fun indicatorKey(name: String): String {
    val cleaned = name.trim().replace(Regex("^[△▲▼↑↓*\\s]+"), "").replace(" ", "")
    return when (cleaned) {
        "甘油三脂" -> "甘油三酯"
        "谷丙转氨酶", "丙氨酸氨基转移酶" -> "丙氨酸氨基转移酶"
        "谷草转氨酶", "天门冬氨酸氨基转移酶" -> "天门冬氨酸氨基转移酶"
        else -> cleaned
    }
}

class RecordDb(context: Context) : SQLiteOpenHelper(context, "records.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE reports (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, sample_date TEXT NOT NULL, report_date TEXT NOT NULL, institution TEXT NOT NULL, image_path TEXT NOT NULL, status TEXT NOT NULL, model TEXT NOT NULL, category TEXT NOT NULL, suggested_category TEXT NOT NULL DEFAULT '')""")
        db.execSQL("CREATE TABLE report_categories (name TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("""CREATE TABLE observations (id INTEGER PRIMARY KEY AUTOINCREMENT, report_id INTEGER NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL, unit TEXT NOT NULL, reference TEXT NOT NULL, flag TEXT NOT NULL, indicator_key TEXT NOT NULL, FOREIGN KEY(report_id) REFERENCES reports(id) ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE stages (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, category TEXT NOT NULL, start_date TEXT NOT NULL, end_date TEXT NOT NULL, note TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX observations_indicator ON observations(indicator_key)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE reports ADD COLUMN category TEXT NOT NULL DEFAULT '其他'")
            db.execSQL("UPDATE reports SET category = type")
            db.execSQL("CREATE TABLE report_categories (name TEXT PRIMARY KEY NOT NULL)")
            db.execSQL("INSERT OR IGNORE INTO report_categories(name) SELECT DISTINCT category FROM reports")
        }
        if (oldVersion < 3) db.execSQL("ALTER TABLE reports ADD COLUMN suggested_category TEXT NOT NULL DEFAULT ''")
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    fun reports(): List<Report> = buildList {
        readableDatabase.rawQuery("SELECT * FROM reports ORDER BY sample_date DESC, id DESC", null).use { c ->
            while (c.moveToNext()) add(Report(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9)))
        }
    }

    fun categories(): List<String> = buildList {
        readableDatabase.rawQuery("SELECT name FROM report_categories ORDER BY name", null).use { c ->
            while (c.moveToNext()) add(c.getString(0))
        }
    }

    fun addCategory(name: String) {
        val value = name.trim()
        require(value.isNotEmpty() && value.length <= 40) { "分类名称应为 1–40 字" }
        writableDatabase.insertWithOnConflict("report_categories", null, ContentValues().apply { put("name", value) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun renameCategory(old: String, new: String) {
        val value = new.trim()
        require(value.isNotEmpty() && value.length <= 40) { "分类名称应为 1–40 字" }
        if (old == value) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("report_categories", null, ContentValues().apply { put("name", value) })
            db.update("reports", ContentValues().apply { put("category", value) }, "category=?", arrayOf(old))
            db.delete("report_categories", "name=?", arrayOf(old))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun deleteEmptyCategory(name: String) {
        val db = writableDatabase
        db.rawQuery("SELECT COUNT(*) FROM reports WHERE category=?", arrayOf(name)).use { c ->
            if (c.moveToFirst() && c.getInt(0) > 0) throw IllegalStateException("请先将报告移到其他分类")
        }
        db.delete("report_categories", "name=?", arrayOf(name))
    }

    fun observations(): List<Observation> = buildList {
        readableDatabase.rawQuery("SELECT * FROM observations ORDER BY id", null).use { c ->
            while (c.moveToNext()) add(Observation(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getString(7)))
        }
    }

    fun stages(): List<Stage> = buildList {
        readableDatabase.rawQuery("SELECT * FROM stages ORDER BY start_date DESC, id DESC", null).use { c ->
            while (c.moveToNext()) add(Stage(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5)))
        }
    }

    fun addReport(report: Report, rows: List<Observation>): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("reports", null, ContentValues().apply {
                put("type", report.type); put("sample_date", report.sampleDate); put("report_date", report.reportDate)
                put("institution", report.institution); put("image_path", report.imagePath); put("status", report.status); put("model", report.model)
                put("category", report.category); put("suggested_category", report.suggestedCategory)
            })
            db.insertWithOnConflict("report_categories", null, ContentValues().apply { put("name", report.category) }, SQLiteDatabase.CONFLICT_IGNORE)
            rows.forEach { row -> insertObservation(db, id, row) }
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    private fun insertObservation(db: SQLiteDatabase, reportId: Long, row: Observation) {
        db.insertOrThrow("observations", null, ContentValues().apply {
            put("report_id", reportId); put("name", row.name); put("value", row.value); put("unit", row.unit)
            put("reference", row.reference); put("flag", row.flag); put("indicator_key", indicatorKey(row.name))
        })
    }

    fun updateReport(report: Report) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("report_categories", null, ContentValues().apply { put("name", report.category) }, SQLiteDatabase.CONFLICT_IGNORE)
            db.update("reports", ContentValues().apply {
                put("type", report.type); put("sample_date", report.sampleDate); put("report_date", report.reportDate)
                put("institution", report.institution); put("status", report.status); put("category", report.category)
                put("suggested_category", report.suggestedCategory)
            }, "id=?", arrayOf(report.id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun updateObservation(row: Observation) {
        writableDatabase.update("observations", ContentValues().apply {
            put("name", row.name); put("value", row.value); put("unit", row.unit)
            put("reference", row.reference); put("flag", row.flag); put("indicator_key", indicatorKey(row.name))
        }, "id=?", arrayOf(row.id.toString()))
    }

    fun addObservation(reportId: Long, row: Observation) = insertObservation(writableDatabase, reportId, row)

    fun replaceObservations(reportId: Long, rows: List<Observation>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("observations", "report_id=?", arrayOf(reportId.toString()))
            rows.forEach { insertObservation(db, reportId, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteObservation(id: Long) { writableDatabase.delete("observations", "id=?", arrayOf(id.toString())) }

    fun deleteReport(id: Long) { writableDatabase.delete("reports", "id=?", arrayOf(id.toString())) }

    fun addStage(stage: Stage) {
        writableDatabase.insertOrThrow("stages", null, ContentValues().apply {
            put("title", stage.title); put("category", stage.category); put("start_date", stage.startDate)
            put("end_date", stage.endDate); put("note", stage.note)
        })
    }

    fun updateStage(stage: Stage) {
        writableDatabase.update("stages", ContentValues().apply {
            put("title", stage.title); put("category", stage.category)
            put("start_date", stage.startDate); put("end_date", stage.endDate); put("note", stage.note)
        }, "id=?", arrayOf(stage.id.toString()))
    }

    fun deleteStage(id: Long) { writableDatabase.delete("stages", "id=?", arrayOf(id.toString())) }
}

fun validDate(value: String): Boolean = try { LocalDate.parse(value); true } catch (_: Exception) { false }
