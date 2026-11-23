package com.example.skipad

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务
 *
 * 两个作用：
 * 1. 前台应用检测：通过 TYPE_WINDOW_STATE_CHANGED 事件记录当前前台应用包名，
 *    供 FloatingButtonService 判断是否显示悬浮窗。
 * 2. 查找"跳过"按钮：抖音广告界面可能有"跳过"按钮，通过无障碍服务查找并点击。
 *
 * 注意：抖音主要通过 MediaSession.skipToNext() 跳过广告，无障碍服务作为辅助。
 */
class SkipAdAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SkipAd_A11y"

        // 全局实例引用
        @Volatile
        private var instance: SkipAdAccessibilityService? = null

        // 当前前台应用包名
        @Volatile
        private var foregroundPackage: String? = null

        fun isRunning(): Boolean = instance != null

        /**
         * 获取当前前台应用包名
         * 通过 rootInActiveWindow 实时获取，未知状态不回退旧缓存。
         */
        fun getForegroundPackage(): String? {
            // 当前根窗口不可读时返回未知；缓存只用于日志，不能据此操作旧应用。
            return try {
                instance?.rootInActiveWindow?.packageName?.toString()
            } catch (e: Exception) {
                Log.w(TAG, "读取当前窗口失败", e)
                null
            }
        }

        /**
         * 尝试通过无障碍服务点击"跳过"按钮
         * @return 是否成功点击
         */
        fun trySkipByClick(targetPackage: String, canProceed: () -> Boolean = { true }): Boolean {
            val svc = instance ?: return false
            return svc.clickSkipButton(targetPackage, canProceed)
        }

        /**
         * 检测当前界面是否正在播放广告
         * 抖音广告界面会显示"广告"字样，通过查找该文字判断
         * @return 是否检测到广告
         */
        fun isAdShowing(targetPackage: String? = null): Boolean {
            val svc = instance ?: return false
            return svc.detectAd(targetPackage)
        }

        /** null 表示无法观测，不能被解释成“广告已消失”。 */
        fun observeAd(targetPackage: String): Boolean? {
            val observation = observeEvidence(targetPackage) ?: return null
            if (!observation.inPlayer || observation.confidence == AdConfidence.WEAK) return null
            return observation.isAd
        }

        internal fun observeEvidence(targetPackage: String): AdObservation? = instance?.readEvidence(targetPackage)

        /**
         * Dump 当前界面所有无障碍节点信息（用于调试）
         */
        fun dumpAllNodes(): String {
            val svc = instance ?: return "无障碍服务未运行"
            return svc.dumpAllNodesInfo()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "无障碍服务已连接")
        // 前台根节点由检测线程按需获取，绑定回调不在 UI 线程预读取。
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val windowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        val contentChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        if (!windowChange && !contentChange) return
        val pkg = event.packageName?.toString()
        if (windowChange && !pkg.isNullOrEmpty()) foregroundPackage = pkg
        // 只唤醒已经运行的服务；不因事件偷偷启动悬浮窗。
        FloatingButtonService.onAccessibilityChange(pkg, windowChange)
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务中断")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        foregroundPackage = null
        Log.d(TAG, "无障碍服务已解绑")
        return super.onUnbind(intent)
    }

    /**
     * 检测当前界面是否正在播放广告
     *
     * 根据抖音开放平台文档，短剧广告（Draw流广告）有两种样式：
     * 1. 无必看时长：底部提示"上滑继续看短剧"
     * 2. 有必看时长：toast提示"倒计时结束后可滑动"，底部提示"X秒后可继续上滑看剧"
     *
     * 因此检测以下关键词：
     * - "上滑继续看短剧"
     * - "秒后可继续上滑看剧"（包含倒计时数字）
     * - "倒计时结束后可滑动"
     * - "广告"（备用）
     */
    private fun detectAd(): Boolean = detectAd(null)

    private fun detectAd(targetPackage: String?): Boolean {
        val target = targetPackage ?: rootInActiveWindow?.packageName?.toString() ?: return false
        return readEvidence(target)?.isAd == true
    }

    private fun readEvidence(target: String): AdObservation? {
        return try {
            if (!ConfigManager.get(this).isAppEnabled(target)) return null
            val root = rootInActiveWindow ?: return null
            AdDetector.inspectFocused(root, target)
        } catch (e: Exception) {
            Log.e(TAG, "读取广告证据失败", e)
            null
        }
    }

    /**
     * Dump 当前界面所有无障碍节点的 text 和 contentDescription
     * 用于调试：确认广告界面的提示文字是否是无障碍节点
     */
    fun dumpAllNodesInfo(): String {
        val rootNode = rootInActiveWindow ?: return "rootNode 为空"
        val sb = StringBuilder()
        try {
            dumpNode(rootNode, 0, sb)
        } catch (e: Exception) {
            sb.append("dump失败: ${e.message}")
        }
        return sb.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        try {
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            if (!text.isNullOrBlank() || !desc.isNullOrBlank()) {
                sb.append("  ".repeat(depth))
                if (!text.isNullOrBlank()) sb.append("text=\"$text\" ")
                if (!desc.isNullOrBlank()) sb.append("desc=\"$desc\" ")
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                sb.append("pkg=${node.packageName} id=${node.viewIdResourceName} bounds=$bounds visible=${node.isVisibleToUser}\n")
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                dumpNode(child, depth + 1, sb)
            }
        } catch (e: Exception) {
            // 忽略
        }
    }

    /**
     * 查找界面上的"跳过"按钮并点击
     * 抖音广告界面可能有"跳过"按钮（倒计时结束后出现）
     */
    private fun clickSkipButton(targetPackage: String, canProceed: () -> Boolean): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        if (rootNode.packageName?.toString() != targetPackage ||
            !ConfigManager.get(this).isAppEnabled(targetPackage)) return false
        try {
            // 【连跳修复】关键词收窄：只认"跳过广告"/"关闭广告"这类广告页专属按钮文案。
            // 旧表里的"跳过"、"关闭"、"skip"、"close"等宽泛词会在正片全树匹配到
            // 弹窗关闭钮、播放器控件等，误点后可能触发额外切集/关弹窗等误操作。
            val keywords = listOf("跳过广告", "关闭广告", "skip ad", "close ad")

            for (keyword in keywords) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
                if (nodes.isNullOrEmpty()) continue

                for (node in nodes) {
                    if (!node.isVisibleToUser || node.packageName?.toString() != targetPackage) continue
                    if (node.text.isNullOrBlank()) continue
                    if (!node.text.toString().contains(keyword, ignoreCase = true)) continue

                    val clickable = findClickableAncestor(node)
                    if (clickable != null && clickable.isVisibleToUser && clickable.isEnabled &&
                        clickable.packageName?.toString() == targetPackage) {
                        Log.d(TAG, "找到跳过按钮: \"${node.text}\"，执行点击")
                        if (!canProceed()) return false
                        if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    }
                }
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "查找跳过按钮失败: ${e.message}", e)
            return false
        }
    }

    /**
     * 向上查找最近的可点击祖先节点
     */
    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }
}
