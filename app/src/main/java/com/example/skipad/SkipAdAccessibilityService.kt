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
         * 优先通过 rootInActiveWindow 实时获取
         *
         * 【诊断日志说明】本轮仅加日志、不改逻辑：
         * 分别打印 rootInActiveWindow 的实时取值与缓存值，用于验证以下假设：
         * - 现象2（切到桌面/其他app仍显示）：实时取值是否仍返回本app、或回退到过时缓存
         * - 现象5（切回抖音不显示）：实时取值/缓存是否仍停留在非目标包名
         */
        fun getForegroundPackage(): String? {
            var realtimePkg: String? = null
            try {
                val rootNode = instance?.rootInActiveWindow
                realtimePkg = rootNode?.packageName?.toString()
                if (!realtimePkg.isNullOrEmpty()) {
                    foregroundPackage = realtimePkg
                    Log.d(TAG, "getForegroundPackage: 实时取值=$realtimePkg, 缓存=$foregroundPackage")
                    return realtimePkg
                }
            } catch (e: Exception) {
                Log.w(TAG, "getForegroundPackage: rootInActiveWindow 异常: ${e.message}")
            }
            // 实时取不到（null 或异常）→ 回退缓存，并打印对比，便于判断缓存是否过时
            Log.d(TAG, "getForegroundPackage: 实时为空/异常, 回退缓存=$foregroundPackage")
            return foregroundPackage
        }

        /**
         * 尝试通过无障碍服务点击"跳过"按钮
         * @return 是否成功点击
         */
        fun trySkipByClick(): Boolean {
            val svc = instance ?: return false
            return svc.clickSkipButton()
        }

        /**
         * 检测当前界面是否正在播放广告
         * 抖音广告界面会显示"广告"字样，通过查找该文字判断
         * @return 是否检测到广告
         */
        fun isAdShowing(): Boolean {
            val svc = instance ?: return false
            return svc.detectAd()
        }

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
        // 服务连接时获取当前前台应用包名
        try {
            val rootNode = rootInActiveWindow
            val pkg = rootNode?.packageName?.toString()
            if (!pkg.isNullOrEmpty()) {
                foregroundPackage = pkg
                Log.d(TAG, "服务连接时前台应用: $pkg")
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取初始前台应用失败: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 窗口切换：更新前台应用包名
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val pkg = event.packageName?.toString()
                // 【诊断日志】打印事件类型与旧→新包名，验证事件流是否正确覆盖了每次前台切换
                val typeName = when (event.eventType) {
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "STATE_CHANGED"
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
                    else -> event.eventType.toString()
                }
                if (!pkg.isNullOrEmpty() && foregroundPackage != pkg) {
                    val old = foregroundPackage
                    foregroundPackage = pkg
                    Log.d(TAG, "前台应用切换[$typeName]: $old -> $pkg")
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务中断")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
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
    private fun detectAd(): Boolean {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.d(TAG, "detectAd: rootNode 为空")
            return false
        }
        try {
            // 广告关键词列表（按优先级排序）
            // 注意：文案必须与抖音实际显示完全一致（子串包含匹配），否则永远匹配不上。
            // 截图实证：可上滑时显示"上滑继续观看短剧"，倒计时中显示"X 秒后可继续看下一集"。
            val adKeywords = listOf(
                "上滑继续观看短剧",   // 可上滑跳过（截图真实文案，含"观"字）
                "上滑继续看短剧",     // 兼容部分版本的旧文案
                "秒后可继续看下一集", // 倒计时中（截图真实文案）
                "秒后可继续上滑看剧", // 兼容部分版本
                "倒计时结束后可滑动",
                "免广告",             // 直播间/商品引流广告：顶部"免广告"入口，正常播放页无此文案
                "查看详情",           // 直播间/商品引流广告：底部红色按钮，正常短剧页无此按钮
                "广告"
            )

            // 1. 搜索 text 包含关键词的节点
            for (keyword in adKeywords) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        val text = node.text?.toString() ?: continue
                        Log.d(TAG, "detectAd: text节点=\"$text\" 匹配关键词\"$keyword\"")
                        // 对于"广告"关键词，只匹配短文本（避免误匹配推荐内容）
                        if (keyword == "广告" && text.length > 5) continue
                        Log.d(TAG, "检测到广告(text): \"$text\"")
                        return true
                    }
                }
            }

            // 2. 搜索 contentDescription 包含关键词的节点
            for (keyword in adKeywords) {
                val nodes = findNodesByDescription(rootNode, keyword)
                for (node in nodes) {
                    val desc = node.contentDescription?.toString() ?: continue
                    Log.d(TAG, "detectAd: desc节点=\"$desc\" 匹配关键词\"$keyword\"")
                    if (keyword == "广告" && desc.length > 5) continue
                    Log.d(TAG, "检测到广告(desc): \"$desc\"")
                    return true
                }
            }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "检测广告失败: ${e.message}")
            return false
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
                sb.append("pkg=${node.packageName}\n")
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
     * 递归搜索 contentDescription 包含指定关键字的节点
     */
    private fun findNodesByDescription(node: android.view.accessibility.AccessibilityNodeInfo, keyword: String): List<android.view.accessibility.AccessibilityNodeInfo> {
        val result = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        try {
            val desc = node.contentDescription?.toString()
            if (!desc.isNullOrEmpty() && desc.contains(keyword)) {
                result.add(node)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                result.addAll(findNodesByDescription(child, keyword))
            }
        } catch (e: Exception) {
            // 忽略
        }
        return result
    }

    /**
     * 查找界面上的"跳过"按钮并点击
     * 抖音广告界面可能有"跳过"按钮（倒计时结束后出现）
     */
    private fun clickSkipButton(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        try {
            val keywords = listOf("跳过广告", "跳过", "关闭广告", "关闭", "skip ad", "skip", "close ad", "close")

            for (keyword in keywords) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
                if (nodes.isNullOrEmpty()) continue

                for (node in nodes) {
                    if (node.text.isNullOrBlank()) continue
                    if (!node.text.toString().contains(keyword, ignoreCase = true)) continue

                    val clickable = findClickableAncestor(node)
                    if (clickable != null) {
                        Log.d(TAG, "找到跳过按钮: \"${node.text}\"，执行点击")
                        return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
