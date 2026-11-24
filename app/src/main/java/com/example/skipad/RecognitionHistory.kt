package com.example.skipad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class RecognitionEntry(val time: Long, val kind: String, val message: String,
    val packageName: String, val ruleId: String, val ruleName: String)

/** 仅保存简短诊断状态，不采集页面全文、截图或媒体元数据。 */
internal object RecognitionHistory {
    const val LIMIT = 100
    private fun preferences(context: Context) = context.getSharedPreferences("recognition_history", Context.MODE_PRIVATE)

    @Synchronized fun load(context: Context): List<RecognitionEntry> = runCatching {
        val array = JSONArray(preferences(context).getString("entries", "[]"))
        (maxOf(0, array.length() - LIMIT) until array.length()).map { index ->
            val item = array.getJSONObject(index)
            RecognitionEntry(item.getLong("time"), item.getString("kind"), item.getString("message"),
                item.optString("package"), item.optString("ruleId"), item.optString("ruleName"))
        }
    }.getOrDefault(emptyList())

    @Synchronized fun record(context: Context, kind: String, message: String,
        observation: AdObservation? = null, packageName: String? = null, now: Long = System.currentTimeMillis()) {
        val entries = load(context).toMutableList()
        val entry = RecognitionEntry(now, kind.take(24), message.take(200),
            (observation?.packageName ?: packageName).orEmpty().take(100),
            observation?.ruleId.orEmpty().take(80), observation?.ruleName.orEmpty().take(32))
        val last = entries.lastOrNull { it.kind == entry.kind && it.packageName == entry.packageName }
        if (last != null && now >= last.time && now - last.time < 5000L && entry.copy(time = last.time) == last) return
        entries.add(entry)
        val array = JSONArray()
        entries.takeLast(LIMIT).forEach { item -> array.put(JSONObject().put("time", item.time)
            .put("kind", item.kind).put("message", item.message).put("package", item.packageName)
            .put("ruleId", item.ruleId).put("ruleName", item.ruleName)) }
        preferences(context).edit().putString("entries", array.toString()).apply()
    }

    @Synchronized fun clear(context: Context) { preferences(context).edit().remove("entries").apply() }
}
