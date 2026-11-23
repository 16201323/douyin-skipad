package com.example.skipad

import android.view.accessibility.AccessibilityNodeInfo
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAccessibilityNodeInfo

/** 为内存节点树实现系统定向查询；不需要真实 AccessibilityInteractionConnection。 */
@Implements(AccessibilityNodeInfo::class)
class QueryNodeShadow : ShadowAccessibilityNodeInfo() {
    @RealObject private lateinit var node: AccessibilityNodeInfo

    private fun matching(predicate: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(current: AccessibilityNodeInfo) {
            if (predicate(current)) result.add(current)
            for (i in 0 until current.childCount) current.getChild(i)?.let(::visit)
        }
        visit(node)
        return result
    }

    @Implementation fun findAccessibilityNodeInfosByViewId(id: String): List<AccessibilityNodeInfo> =
        matching { it.viewIdResourceName == id }

    @Implementation fun findAccessibilityNodeInfosByText(text: String): List<AccessibilityNodeInfo> =
        matching { it.text?.contains(text, ignoreCase = true) == true ||
            it.contentDescription?.contains(text, ignoreCase = true) == true }

    @Implementation fun findFocus(focus: Int): AccessibilityNodeInfo? = matching { it.isFocused }.firstOrNull()
}
