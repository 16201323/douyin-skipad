package com.example.skipad

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** 仅测试 APK 注册。直接读当前窗口，不等待视频、弹幕页面进入空闲。 */
class UiSnapshotInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val snapshots = JSONArray()
            repeat(3) {
                val root = automation.rootInActiveWindow
                val scanStarted = SystemClock.uptimeMillis()
                val observation = root?.let { AdDetector.inspectFocused(it, "com.ss.android.ugc.aweme") }
                val scanMs = SystemClock.uptimeMillis() - scanStarted
                val selected = JSONArray()
                var visited = 0
                fun visit(node: AccessibilityNodeInfo, depth: Int) {
                    if (++visited > 1200 || depth > 60) return
                    val text = node.text?.toString().orEmpty()
                    val desc = node.contentDescription?.toString().orEmpty()
                    val relevant = Regex("广告|选集|全集|第\\s*\\d+\\s*集|短剧|看剧|倒计时")
                    if (node.isVisibleToUser && (relevant.containsMatchIn(text) || relevant.containsMatchIn(desc))) {
                        val bounds = Rect().also(node::getBoundsInScreen)
                        selected.put(JSONObject().put("text", text.take(160)).put("desc", desc.take(160))
                            .put("id", node.viewIdResourceName).put("parentId", node.parent?.viewIdResourceName)
                            .put("bounds", bounds.toShortString()))
                    }
                    for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
                }
                if (root != null && root.packageName?.toString() == "com.ss.android.ugc.aweme") visit(root, 0)
                snapshots.put(JSONObject().put("package", root?.packageName).put("visited", visited)
                    .put("scanMs", scanMs).put("player", observation?.inPlayer)
                    .put("confidence", observation?.confidence).put("reason", observation?.reason)
                    .put("nodes", selected))
                SystemClock.sleep(400L)
            }
            result.putString("stream", snapshots.toString())
            finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "snapshot failed: ${e.javaClass.simpleName}: ${e.message}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
