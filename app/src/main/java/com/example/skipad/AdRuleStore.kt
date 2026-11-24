package com.example.skipad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal enum class RuleMode(val label: String) {
    EXACT("完整匹配"), CONTAINS("包含关键词"), COUNTDOWN("倒计时模板"), NUMBERED_BADGE("编号角标"), SUFFIX("结尾匹配")
}

internal enum class RuleEvidence(val label: String) {
    PROMPT("广告提示"), BADGE("边缘小角标"), CAPTION("介绍广告后缀"), LIVE("短剧内直播引流")
}

internal data class AdRule(val id: String, val name: String, val pattern: String,
    val mode: RuleMode = RuleMode.EXACT, val evidence: RuleEvidence = RuleEvidence.PROMPT,
    val enabled: Boolean = true) {
    val query: String get() = normalize(pattern).removePrefix("{秒}")

    fun validationError(): String? = when {
        id.isBlank() || id.length > 80 -> "规则编号无效"
        name.isBlank() || name.length > 32 -> "名称应为 1～32 个字符"
        normalize(pattern).length !in 2..60 || query.length < 2 -> "匹配内容应为 2～60 个字符"
        mode == RuleMode.COUNTDOWN && (!normalize(pattern).startsWith("{秒}") ||
            normalize(pattern).removePrefix("{秒}").contains("{秒}")) -> "倒计时模板以 {秒} 开头，例如 {秒}秒后可继续看下一集"
        else -> null
    }

    companion object {
        private val whitespace = Regex("\\s+")
        fun normalize(value: String): String = value.replace(whitespace, "")
    }
}

internal class AdRuleSet(val rules: List<AdRule>, val version: String) {
    private class Matcher(val rule: AdRule) {
        val text = AdRule.normalize(rule.pattern)
        val regex = when (rule.mode) {
            RuleMode.COUNTDOWN -> Regex("^\\d{1,6}" + Regex.escape(text.removePrefix("{秒}")) + "[。！!]?$" )
            RuleMode.NUMBERED_BADGE -> Regex("^" + Regex.escape(text) + "(?:\\d+/\\d+)?$")
            else -> null
        }
        fun matches(value: String): Boolean = when (rule.mode) {
            RuleMode.EXACT -> value == text
            RuleMode.CONTAINS -> value.contains(text)
            RuleMode.SUFFIX -> value.endsWith(text)
            else -> regex!!.matches(value)
        }
    }
    private val matchers = rules.filter { it.enabled }.map(::Matcher)
    val queries: List<String> = (rules.filter { it.enabled }.map { it.query } +
        if (rules.any { it.enabled && it.evidence == RuleEvidence.LIVE }) listOf("直播中") else emptyList()).distinct()

    fun matching(value: String): List<AdRule> {
        val text = AdRule.normalize(value)
        if (text.isEmpty()) return emptyList()
        return matchers.filter { it.matches(text) }.map { it.rule }
    }

    companion object {
        val defaultRules: List<AdRule> = listOf(
            AdRule("builtin_badge", "广告角标", "广告", RuleMode.NUMBERED_BADGE, RuleEvidence.BADGE),
            AdRule("builtin_next", "下一集倒计时", "{秒}秒后可继续看下一集", RuleMode.COUNTDOWN),
            AdRule("builtin_swipe", "上滑倒计时", "{秒}秒后可继续上滑看剧", RuleMode.COUNTDOWN),
            AdRule("builtin_continue", "上滑继续观看短剧", "上滑继续观看短剧"),
            AdRule("builtin_continue_short", "上滑继续看短剧", "上滑继续看短剧"),
            AdRule("builtin_wait", "倒计时结束提示", "倒计时结束后可滑动"),
            AdRule("builtin_caption", "介绍末尾广告", "广告", RuleMode.SUFFIX, RuleEvidence.CAPTION),
            AdRule("builtin_live_entry", "短剧内直播入口", "点击进入直播间", evidence = RuleEvidence.LIVE),
            AdRule("builtin_live_end", "短剧内直播结束", "直播已结束", evidence = RuleEvidence.LIVE)
        )
        val defaults: AdRuleSet by lazy { create(defaultRules) }
        fun create(rules: List<AdRule>): AdRuleSet {
            val serialized = AdRuleStore.encode(rules)
            val digest = MessageDigest.getInstance("SHA-256").digest(serialized.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return AdRuleSet(rules.toList(), digest)
        }
    }
}

/** 编译结果只在配置字符串变化时更新；检测过程中使用同一份不可变规则快照。 */
internal class AdRuleStore(context: Context) {
    private val prefs = context.getSharedPreferences("skip_ad_config", Context.MODE_PRIVATE)
    private var cachedRaw: String? = null
    private var cached: AdRuleSet? = null

    @Synchronized fun snapshot(): AdRuleSet {
        val raw = prefs.getString("ad_rules_v1", null)
        if (cached != null && raw == cachedRaw) return cached!!
        val rules = if (raw == null) AdRuleSet.defaultRules else runCatching {
            val array = JSONArray(raw)
            require(array.length() <= MAX_RULES)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                AdRule(item.getString("id"), item.getString("name"), item.getString("pattern"),
                    RuleMode.valueOf(item.getString("mode")), RuleEvidence.valueOf(item.getString("evidence")),
                    item.optBoolean("enabled", true)).also { require(it.validationError() == null) }
            }.also { list -> require(list.map { it.id }.distinct().size == list.size) }
        }.getOrElse { AdRuleSet.defaultRules }
        return AdRuleSet.create(rules).also { cachedRaw = raw; cached = it }
    }

    @Synchronized fun save(rules: List<AdRule>) {
        require(rules.size <= MAX_RULES) { "最多保存 $MAX_RULES 条规则" }
        require(rules.map { it.id }.distinct().size == rules.size) { "规则编号重复" }
        rules.forEach { require(it.validationError() == null) { it.validationError()!! } }
        prefs.edit().putString("ad_rules_v1", encode(rules)).apply()
        cached = null
    }

    fun reset() = save(AdRuleSet.defaultRules)

    companion object {
        const val MAX_RULES = 24
        fun encode(rules: List<AdRule>): String = JSONArray().apply {
            rules.forEach { rule -> put(JSONObject().put("id", rule.id).put("name", rule.name)
                .put("pattern", rule.pattern).put("mode", rule.mode.name).put("evidence", rule.evidence.name)
                .put("enabled", rule.enabled)) }
        }.toString()
    }
}
