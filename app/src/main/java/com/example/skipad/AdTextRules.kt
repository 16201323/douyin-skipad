package com.example.skipad

/** 只识别明确的广告 UI 文案，不把会员入口、评论中的“广告”当角标。 */
internal object AdTextRules {
    enum class Kind { NONE, BADGE, PROMPT }
    private val whitespace = Regex("\\s+")
    private val badge = Regex("^广告(?:\\d+/\\d+)?$")
    private val countdown = Regex("^\\d+秒后可继续(?:看下一集|上滑看剧)[。！!]?$")
    private val prompts = setOf("上滑继续观看短剧", "上滑继续看短剧", "倒计时结束后可滑动")

    fun matches(value: CharSequence?): Boolean {
        return kind(value) != Kind.NONE
    }

    fun kind(value: CharSequence?): Kind {
        val text = value?.toString()?.replace(whitespace, "") ?: return Kind.NONE
        return when {
            countdown.matches(text) || text in prompts -> Kind.PROMPT
            badge.matches(text) -> Kind.BADGE
            else -> Kind.NONE
        }
    }
}
