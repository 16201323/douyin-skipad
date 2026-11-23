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
    val reason: String
) {
    val scope: String get() = "$packageName:$windowId"
    val isAd: Boolean get() = inPlayer && confidence >= AdConfidence.MEDIUM
}

/** 一次有界扫描。没有经过真机验证的资源 ID 只用于排除区域，不作为广告白名单。 */
internal object AdDetector {
    private val episode = Regex("^(?:正在播放)?第\\s*\\d+\\s*集(?:[ /·].*)?$")
    private val excludedIds = Regex("(?:comment|reply|search|member|vip|profile)")

    fun inspect(root: AccessibilityNodeInfo, target: String): AdObservation? = inspectSnapshot(root, target)

    /** 系统定向查询取代千节点跨进程遍历；已实测的布局只读取少数相关控件。 */
    fun inspectFocused(root: AccessibilityNodeInfo, target: String): AdObservation? {
        if (root.packageName?.toString() != target) return null
        val selected = mutableListOf<AccessibilityNodeInfo>()
        for (id in listOf("desc", "cq-", "b4=")) {
            selected.addAll(root.findAccessibilityNodeInfosByViewId("$target:id/$id").orEmpty().take(20))
        }
        val knownLayout = selected.any { it.isVisibleToUser }
        val keywords = if (knownLayout) listOf("广告", "秒后可继续", "上滑继续", "倒计时结束")
            else listOf("选集", "全集", "第", "短剧", "广告", "秒后可继续", "上滑继续", "倒计时结束")
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
            return inspectSnapshot(root, target, maxNodes = 32)
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
        return inspectSnapshot(root, target, distinct)
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

    private fun inspectSnapshot(root: AccessibilityNodeInfo, target: String,
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
                            (text.length <= 120 && Regex("^短剧\\s*[·•]\\s*\\S+").containsMatchIn(text))) dramaFound = true
                        if (id == "b4=" && text == "退出短剧") exitDramaFound = true
                        if (id == "desc" && text.length > 4 && text.endsWith("广告") &&
                            !Regex("(?:不是|没有|免|无|去)广告$").containsMatchIn(text) &&
                            bounds.top >= screen.top + screen.height() * 0.55f &&
                            bounds.height() <= screen.height() * 0.12f) captionAdFound = true
                        when (AdTextRules.kind(text)) {
                            AdTextRules.Kind.PROMPT -> promptFound = true
                            AdTextRules.Kind.BADGE -> {
                                weakBadgeFound = true
                                // 角标必须是小控件，位于屏幕两侧，避免正文/评论里的同名文字。
                                val small = bounds.width() <= screen.width() * 0.30f &&
                                    bounds.height() <= screen.height() * 0.08f
                                val edge = bounds.left <= screen.left + screen.width() * 0.20f ||
                                    bounds.right >= screen.right - screen.width() * 0.20f
                                if (small && edge) badgeFound = true
                            }
                            AdTextRules.Kind.NONE -> Unit
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
        val confidence = when {
            !inPlayer -> AdConfidence.NONE
            promptFound -> AdConfidence.HIGH
            badgeFound || (exitDramaFound && captionAdFound) -> AdConfidence.MEDIUM
            weakBadgeFound -> AdConfidence.WEAK
            else -> AdConfidence.NONE
        }
        val reason = when (confidence) {
            AdConfidence.HIGH -> "播放器锚点+明确广告提示"
            AdConfidence.MEDIUM -> if (exitDramaFound && captionAdFound) "退出短剧控件+已验证底部介绍广告后缀" else "播放器锚点+边缘小角标"
            AdConfidence.WEAK -> "孤立角标缺少位置证据"
            AdConfidence.NONE -> if (inPlayer) "播放器内未发现广告" else "播放器上下文不足或面板遮挡"
        }
        return AdObservation(target, root.windowId, inPlayer, confidence,
            "$reason episode=$episodeFound selection=$selectionFound drama=$dramaFound exit=$exitDramaFound panel=$blockingPanel")
    }
}
