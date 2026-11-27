package com.example.skipad

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

internal enum class AdConfidence(val confirmMs: Long) {
    NONE(0), WEAK(0), MEDIUM(300), HIGH(120)
}

internal data class AdObservation(
    val packageName: String,
    val windowId: Int,
    val inPlayer: Boolean,
    val confidence: AdConfidence,
    val reason: String,
    val ruleId: String? = null,
    val ruleName: String? = null,
    val rulesVersion: String = ""
) {
    val scope: String get() = "$packageName:$windowId"
    val isAd: Boolean get() = inPlayer && confidence >= AdConfidence.MEDIUM
    val ruleKey: String get() = "$rulesVersion:$ruleId"
}

/** 一次有界扫描。没有经过真机验证的资源 ID 只用于排除区域，不作为广告白名单。 */
internal object AdDetector {
    private val episode = Regex("^(?:正在播放)?第\\s*\\d+\\s*集(?:[ /·].*)?$")
    private val excludedIds = Regex("(?:comment|reply|search|member|vip|profile)")
    private val dramaTitle = Regex("^短剧\\s*[·•]\\s*\\S+")
    private val negativeCaption = Regex("(?:不是|没有|免|无|去)广告$")

    fun inspect(root: AccessibilityNodeInfo, target: String, rules: AdRuleSet = AdRuleSet.defaults): AdObservation? = inspectSnapshot(root, target, rules)

    /** 系统定向查询取代千节点跨进程遍历；已实测的布局只读取少数相关控件。 */
    fun inspectFocused(root: AccessibilityNodeInfo, target: String, rules: AdRuleSet = AdRuleSet.defaults): AdObservation? {
        if (root.packageName?.toString() != target) return null
        val selected = mutableListOf<AccessibilityNodeInfo>()
        for (id in listOf("desc", "cq-", "b4=")) {
            selected.addAll(root.findAccessibilityNodeInfosByViewId("$target:id/$id").orEmpty().take(20))
        }
        // 控件 ID 可跨版本保留或改名，介绍栏存在不能代替播放器上下文查询。
        val keywords = (listOf("退出短剧", "短剧", "选集", "全集", "第") + rules.queries).distinct()
        for (keyword in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword).orEmpty()
            if (nodes.size > 60) return null
            selected.addAll(nodes)
        }
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let {
            if (it.isEditable && it.isFocused) selected.add(it)
        }
        if (selected.isEmpty()) {
            // 少量节点的版本仍可识别；大树不再退回秒级扫描，未知布局保守不动作。
            return inspectSnapshot(root, target, rules, maxNodes = 32)
        }
        val contextual = selected.distinct().toMutableList()
        for (node in selected.distinct()) {
            var parent = node.parent
            var depth = 0
            while (parent != null && depth++ < 8) {
                val current = parent
                val id = current.viewIdResourceName.orEmpty().substringAfter(":id/").lowercase(Locale.ROOT)
                if (excludedIds.containsMatchIn(id)) contextual.add(current)
                parent = current.parent
            }
        }
        val distinct = contextual.distinct()
        if (distinct.size > 120) return null
        return inspectSnapshot(root, target, rules, distinct)
    }

    private fun excludedAncestor(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        repeat(8) {
            val current = parent ?: return false
            val id = current.viewIdResourceName.orEmpty().substringAfter(":id/").lowercase(Locale.ROOT)
            if (excludedIds.containsMatchIn(id)) return true
            parent = current.parent
        }
        return false
    }

    private fun inspectSnapshot(root: AccessibilityNodeInfo, target: String, rules: AdRuleSet,
        focused: List<AccessibilityNodeInfo>? = null, maxNodes: Int = 1200): AdObservation? {
        if (root.packageName?.toString() != target) return null
        val screen = Rect().also(root::getBoundsInScreen)
        if (screen.isEmpty) return null
        var episodeFound = false
        var selectionFound = false
        var dramaFound = false
        var exitDramaFound = false
        var captionAdFound = false
        var promptFound = false
        var badgeFound = false
        var weakBadgeFound = false
        var dramaFooterFound = false
        var liveBadgeFound = false
        var promptRule: AdRule? = null
        var badgeRule: AdRule? = null
        var captionRule: AdRule? = null
        var liveRule: AdRule? = null
        var blockingPanel = false
        var visited = 0
        var truncated = false

        fun visit(node: AccessibilityNodeInfo, excluded: Boolean, depth: Int) {
            if (++visited > maxNodes || depth > 60) { truncated = true; return }
            val id = node.viewIdResourceName.orEmpty().substringAfter(":id/").lowercase(Locale.ROOT)
            val outsidePlayer = excluded || excludedIds.containsMatchIn(id)
            if (node.isVisibleToUser && node.packageName?.toString() == target) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val visible = !bounds.isEmpty && Rect.intersects(screen, bounds)
                if (visible && ((node.isEditable && node.isFocused) ||
                    (outsidePlayer && bounds.height() > screen.height() * 0.35f))) blockingPanel = true
                if (visible && !outsidePlayer) {
                    for (label in listOf(node.text, node.contentDescription)) {
                        val text = label?.toString()?.trim().orEmpty()
                        if (text.length <= 160 && episode.matches(text)) episodeFound = true
                        if (text == "选集" || text == "全集") selectionFound = true
                        if (text == "短剧" || text == "短剧播放器" ||
                            (text.length <= 120 && dramaTitle.containsMatchIn(text))) dramaFound = true
                        if (text == "退出短剧" && bounds.top >= screen.top &&
                            bounds.bottom <= screen.top + screen.height() * 0.18f &&
                            bounds.left <= screen.left + screen.width() * 0.5f &&
                            bounds.width() <= screen.width() * 0.6f &&
                            bounds.height() <= screen.height() * 0.12f) exitDramaFound = true
                        if (text.length <= 120 && dramaTitle.containsMatchIn(text) &&
                            bounds.top >= screen.top + screen.height() * 0.8f) dramaFooterFound = true
                        if (text == "直播中" && bounds.top >= screen.top + screen.height() * 0.65f &&
                            bounds.left <= screen.left + screen.width() * 0.2f &&
                            bounds.width() <= screen.width() * 0.3f && bounds.height() <= screen.height() * 0.08f) liveBadgeFound = true
                        for (rule in rules.matching(text)) when (rule.evidence) {
                            RuleEvidence.CAPTION -> if (id == "desc" && text.length > 4 &&
                                !negativeCaption.containsMatchIn(text) &&
                                bounds.top >= screen.top + screen.height() * 0.55f &&
                                bounds.height() <= screen.height() * 0.12f) { captionAdFound = true; if (captionRule == null) captionRule = rule }
                            RuleEvidence.PROMPT -> { promptFound = true; if (promptRule == null) promptRule = rule }
                            RuleEvidence.LIVE -> if (bounds.top >= screen.top + screen.height() * 0.4f &&
                                bounds.bottom <= screen.top + screen.height() * 0.85f &&
                                bounds.height() <= screen.height() * 0.15f) { if (liveRule == null) liveRule = rule }
                            RuleEvidence.BADGE -> {
                                weakBadgeFound = true
                                // 角标必须是小控件，位于屏幕两侧，避免正文/评论里的同名文字。
                                val small = bounds.width() <= screen.width() * 0.30f &&
                                    bounds.height() <= screen.height() * 0.08f
                                val edge = bounds.left <= screen.left + screen.width() * 0.20f ||
                                    bounds.right >= screen.right - screen.width() * 0.20f
                                if (small && edge) { badgeFound = true; if (badgeRule == null) badgeRule = rule }
                            }
                        }
                    }
                }
            }
            if (focused != null) return
            for (i in 0 until node.childCount) {
                if (truncated) return
                node.getChild(i)?.let { visit(it, outsidePlayer, depth + 1) }
            }
        }

        if (focused == null) visit(root, false, 0)
        else for (node in focused) visit(node, excludedAncestor(node), 0)
        if (truncated) return null // 扫描不完整不能据此确认广告消失。
        val inPlayer = !blockingPanel && (exitDramaFound || (selectionFound && (episodeFound || dramaFound)) ||
            (episodeFound && dramaFound) ||
            (episodeFound && promptFound))
        val liveAd = liveRule != null && exitDramaFound && dramaFooterFound && liveBadgeFound
        val matchedRule = when {
            !inPlayer -> null
            promptFound -> promptRule
            liveAd -> liveRule
            exitDramaFound && captionAdFound -> captionRule
            badgeFound -> badgeRule
            else -> null
        }
        val confidence = when {
            !inPlayer -> AdConfidence.NONE
            promptFound -> AdConfidence.HIGH
            badgeFound || (exitDramaFound && captionAdFound) || liveAd -> AdConfidence.MEDIUM
            weakBadgeFound -> AdConfidence.WEAK
            else -> AdConfidence.NONE
        }
        val reason = when (confidence) {
            AdConfidence.HIGH -> "播放器锚点+明确广告提示"
            AdConfidence.MEDIUM -> if (liveAd) "短剧入口+底部剧名+直播标记+直播提示"
                else if (exitDramaFound && captionAdFound) "退出短剧控件+已验证底部介绍广告后缀" else "播放器锚点+边缘小角标"
            AdConfidence.WEAK -> "孤立角标缺少位置证据"
            AdConfidence.NONE -> if (inPlayer) "播放器内未发现广告" else "播放器上下文不足或面板遮挡"
        }
        return AdObservation(target, root.windowId, inPlayer, confidence,
            "$reason episode=$episodeFound selection=$selectionFound drama=$dramaFound exit=$exitDramaFound panel=$blockingPanel",
            matchedRule?.id, matchedRule?.name, rules.version)
    }
}
