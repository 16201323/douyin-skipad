package com.example.skipad

import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator

/** 只比较当前窗口快照；不使用上次前台包名，也不根据媒体会话猜测可见性。 */
internal data class AppWindowFrame(val id: Int, val layer: Int, val order: Int, val bounds: Rect,
    val eligible: Boolean, val focused: Boolean, val active: Boolean, val region: Region? = null)

internal object VisibleWindowPolicy {
    private fun above(other: AppWindowFrame, target: AppWindowFrame): Boolean =
        other.layer > target.layer || (other.layer == target.layer && other.order < target.order)

    private fun shape(window: AppWindowFrame): Region =
        (window.region?.let(::Region) ?: Region(window.bounds)).apply { op(window.bounds, Region.Op.INTERSECT) }

    fun exposedArea(target: AppWindowFrame, windows: List<AppWindowFrame>, viewport: Rect): Long {
        val exposed = shape(target).apply { op(viewport, Region.Op.INTERSECT) }
        for (cover in windows.filter { above(it, target) }) {
            exposed.op(shape(cover), Region.Op.DIFFERENCE)
            if (exposed.isEmpty) return 0
        }
        val iterator = RegionIterator(exposed)
        val piece = Rect()
        var area = 0L
        while (iterator.next(piece)) area += piece.width().toLong() * piece.height()
        return area
    }

    fun select(windows: List<AppWindowFrame>, viewport: Rect): AppWindowFrame? = windows.filter { it.eligible }
        .map { it to exposedArea(it, windows, viewport) }.filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<AppWindowFrame, Long>> { it.second }
            .thenByDescending { it.first.focused }.thenByDescending { it.first.active }.thenByDescending { it.first.layer })
        .firstOrNull()?.first

    fun canClick(target: AppWindowFrame, bounds: Rect, windows: List<AppWindowFrame>, viewport: Rect): Boolean {
        if (bounds.isEmpty || !viewport.contains(bounds.centerX(), bounds.centerY()) ||
            !shape(target).contains(bounds.centerX(), bounds.centerY())) return false
        return windows.none { above(it, target) && shape(it).contains(bounds.centerX(), bounds.centerY()) }
    }
}
