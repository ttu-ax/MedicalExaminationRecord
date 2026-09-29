package com.example.medicalrecord

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.UUID

data class RecognizedReport(val report: Report, val observations: List<Observation>)

private const val MODEL_BASE_URL = "https://llm-nznju0c9y94pes1q.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
private const val VISION_MODEL = "qwen3.7-flash"
private val reportTypes = setOf("血常规", "生化", "凝血", "其他")
private val datePattern = Regex("(\\d{4})[-年/.](\\d{1,2})[-月/.](\\d{1,2})")
private const val PROMPT = """你是检验报告逐字转录助手。读取这一张报告图片，仅返回 JSON 对象。
不要诊断、推断、解释或补充常见正常值。不要提取姓名、病历号等身份信息。
格式：{"report_type":"血常规|生化|凝血|其他", "suggested_category":"建议分类名或空字符串", "sample_date":"YYYY-MM-DD 或空字符串", "report_date":"YYYY-MM-DD 或空字符串", "institution":"报告上可见机构或空字符串", "observations":[{"name":"报告项目原文", "value":"结果原文", "unit":"单位原文或空字符串", "reference":"参考范围原文或空字符串", "flag":"报告提示↑、↓或空字符串"}]}。
必须逐行检查表格两栏/多栏，保持项目与结果、范围、单位同行对应。看不清的字段写空字符串，不猜测。报告类型不确定写其他；此时根据报告标题和项目名称给出简短、具体的 suggested_category，不确定则留空。其他类型的 suggested_category 留空。日期仅使用报告上实际印刷的采样或报告日期。只输出 JSON。"""

fun copyImage(context: Context, source: Uri): String {
    val mime = context.contentResolver.getType(source) ?: "image/jpeg"
    val extension = when (mime) { "image/png" -> ".png"; "image/webp" -> ".webp"; else -> ".jpg" }
    val directory = File(context.filesDir, "report_images").apply { mkdirs() }
    val target = File(directory, UUID.randomUUID().toString() + extension)
    context.contentResolver.openInputStream(source).use { input ->
        requireNotNull(input) { "无法打开图片" }
        target.outputStream().use { output -> input.copyTo(output) }
    }
    return target.absolutePath
}

private fun modelConnection(path: String, apiKey: String): HttpURLConnection =
    (URL("$MODEL_BASE_URL/$path").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connectTimeout = 20_000
        readTimeout = 180_000
        doOutput = true
    }

private fun sendJson(connection: HttpURLConnection, body: JSONObject) {
    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
}

private fun modelError(connection: HttpURLConnection): String {
    val detail = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    val message = try {
        val json = JSONObject(detail)
        json.optJSONObject("error")?.optString("message") ?: json.optString("message")
    } catch (_: Exception) { "" }
    return when (connection.responseCode) {
        401, 403 -> "API Key 无效或没有模型权限，请在设置中检查"
        else -> "百炼 HTTP ${connection.responseCode}：${message.ifBlank { detail.take(240) }}"
    }
}

private fun clean(value: Any?, limit: Int = 200): String =
    if (value == null || value == JSONObject.NULL) "" else value.toString().trim().take(limit)

private fun normalizedDate(value: Any?): String {
    val match = datePattern.find(clean(value, 60)) ?: return ""
    return try { LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()).toString() }
    catch (_: Exception) { "" }
}

private fun imageBase64(path: String): String {
    val original = BitmapFactory.decodeFile(path) ?: throw IllegalArgumentException("无法读取报告图片")
    val scale = minOf(1f, 2600f / maxOf(original.width, original.height))
    val image = if (scale < 1f) Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true) else original
    val bytes = ByteArrayOutputStream().use { stream -> image.compress(Bitmap.CompressFormat.JPEG, 85, stream); stream.toByteArray() }
    if (image !== original) image.recycle()
    original.recycle()
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

fun recognizeImage(path: String, apiKey: String, onStage: (String) -> Unit, onStream: (String) -> Unit): RecognizedReport {
    require(apiKey.isNotBlank()) { "请先在设置中填写 API Key" }
    onStage("压缩图片")
    val encoded = imageBase64(path)
    val messages = JSONArray()
        .put(JSONObject().put("role", "system").put("content", PROMPT))
        .put(JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$encoded")))
            .put(JSONObject().put("type", "text").put("text", "请逐项转录这张报告。"))))
    val body = JSONObject()
        .put("model", VISION_MODEL)
        .put("messages", messages)
        .put("response_format", JSONObject().put("type", "json_object"))
        .put("enable_thinking", false)
        .put("stream", true)
    val connection = modelConnection("chat/completions", apiKey)
    connection.setRequestProperty("Accept", "text/event-stream")
    val content = StringBuilder()
    var model = VISION_MODEL
    var receivedDone = false
    var lastUiUpdate = 0L
    try {
        onStage("上传图片并等待模型")
        sendJson(connection, body)
        if (connection.responseCode !in 200..299) throw IllegalStateException(modelError(connection))
        connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("data:")) continue
                val data = line.substringAfter("data:").trim()
                if (data == "[DONE]") { receivedDone = true; break }
                if (data.isEmpty()) continue
                val chunk = JSONObject(data)
                if (chunk.has("error")) throw IllegalStateException(chunk.optJSONObject("error")?.optString("message") ?: "模型流式响应失败")
                model = chunk.optString("model", model)
                val delta = chunk.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                val fragment = delta?.optString("content", "") ?: ""
                if (fragment.isNotEmpty()) {
                    content.append(fragment)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUiUpdate >= 100) {
                        onStage("正在接收模型结果")
                        onStream(content.takeLast(6000).toString())
                        lastUiUpdate = now
                    }
                }
            }
        }
        onStream(content.takeLast(6000).toString())
        if (!receivedDone) throw IllegalStateException("模型响应中断，请重试")
    } finally {
        connection.disconnect()
    }
    onStage("解析检验项目")
    val parsed = try { JSONObject(content.toString()) } catch (_: Exception) { throw IllegalStateException("模型结果不是有效 JSON，请重试") }
    val items = parsed.optJSONArray("observations") ?: throw IllegalStateException("模型未返回检验项目")
    val rows = mutableListOf<Observation>()
    for (index in 0 until minOf(items.length(), 150)) {
        val item = items.optJSONObject(index) ?: continue
        val name = clean(item.opt("name"))
        if (name.isNotEmpty()) rows.add(Observation(0, 0, name, clean(item.opt("value")), clean(item.opt("unit")), clean(item.opt("reference")), clean(item.opt("flag")), indicatorKey(name)))
    }
    if (rows.isEmpty()) throw IllegalStateException("未识别到检验项目，请裁剪或重拍")
    var type = clean(parsed.opt("report_type"), 20).takeIf { it in reportTypes } ?: "其他"
    if (type == "其他") {
        onStage("判断报告类型")
        type = try { decideType(apiKey, rows) ?: type } catch (_: Exception) { type }
    }
    val suggestion = if (type == "其他") clean(parsed.opt("suggested_category"), 40).takeUnless { it == "其他" } ?: "" else ""
    return RecognizedReport(Report(0, type, normalizedDate(parsed.opt("sample_date")), normalizedDate(parsed.opt("report_date")), clean(parsed.opt("institution")), path, "待校对", model, suggestedCategory = suggestion), rows)
}

private fun decideType(apiKey: String, rows: List<Observation>): String? {
    val criteria = JSONObject()
        .put("blood_count", "血常规：白细胞、红细胞、血小板等血细胞指标")
        .put("biochemistry", "生化：肝肾功能、血脂、血糖等生化指标")
        .put("coagulation", "凝血：凝血酶原时间、纤维蛋白原、D-二聚体等")
        .put("other", "其他类别或证据不足")
    val question = JSONObject().put("type", "choice").put("instructions", "仅依据项目名称判断这份检验报告属于哪一类；不确定时选择 other。")
        .put("criteria", criteria)
    val body = JSONObject().put("model", "decision-model-preview")
        .put("state", JSONObject().put("item_names", JSONArray(rows.take(30).map { it.name })).put("reported_type", "其他"))
        .put("questions", JSONObject().put("report_type", question))
    val connection = modelConnection("systemone", apiKey)
    return try {
        sendJson(connection, body)
        if (connection.responseCode !in 200..299) return null
        val result = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        val answer = result.optJSONObject("answers")?.optJSONObject("report_type") ?: return null
        if (answer.optDouble("confidence", 0.0) < 0.8) return null
        when (answer.optString("choice")) { "blood_count" -> "血常规"; "biochemistry" -> "生化"; "coagulation" -> "凝血"; else -> null }
    } finally { connection.disconnect() }
}
