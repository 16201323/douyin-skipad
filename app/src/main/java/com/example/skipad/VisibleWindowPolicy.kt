package com.example.skipad

import android.graphics.Rect

/** 只比较当前窗口快照；不使用上次前台包名，也不根据媒体会话猜测可见性。 */
internal data class AppWindowFrame(val id: Int, val layer: Int, val order: Int, val bounds: Rect,
    val eligible: Boolean, val focused: Boolean, val active: Boolean)

internal object VisibleWindowPolicy {
    private fun above(other: AppWindowFrame, target: AppWindowFrame): Boolean =
        other.layer > target.layer || (other.layer == target.layer && other.order < target.order)

    private fun clipped(bounds: Rect, viewport: Rect): Rect? = Rect(bounds).takeIf { it.intersect(viewport) && !it.isEmpty }

    private fun subtract(piece: Rect, cover: Rect): List<Rect> {
        val overlap = Rect(piece)
        if (!overlap.intersect(cover)) return listOf(piece)
        return listOf(Rect(piece.left, piece.top, piece.right, overlap.top),
            Rect(piece.left, overlap.bottom, piece.right, piece.bottom),
            Rect(piece.left, overlap.top, overlap.left, overlap.bottom),
            Rect(overlap.right, overlap.top, piece.right, overlap.bottom)).filterNot { it.isEmpty }
    }

    fun exposedArea(target: AppWindowFrame, windows: List<AppWindowFrame>, viewport: Rect): Long {
        var pieces = listOf(clipped(target.bounds, viewport) ?: return 0)
        for (cover in windows.filter { above(it, target) }) {
            val rect = clipped(cover.bounds, viewport) ?: continue
            pieces = pieces.flatMap { subtract(it, rect) }
            if (pieces.isEmpty()) return 0
        }
        return pieces.sumOf { it.width().toLong() * it.height() }
    }

    fun select(windows: List<AppWindowFrame>, viewport: Rect): AppWindowFrame? = windows.filter { it.eligible }
        .map { it to exposedArea(it, windows, viewport) }.filter { it.second > 0 }
        .sortedWith(compareByDescending<Pair<AppWindowFrame, Long>> { it.second }
            .thenByDescending { it.first.focused }.thenByDescending { it.first.active }.thenByDescending { it.first.layer })
        .firstOrNull()?.first

    fun canClick(target: AppWindowFrame, bounds: Rect, windows: List<AppWindowFrame>, viewport: Rect): Boolean {
        if (bounds.isEmpty || !viewport.contains(bounds.centerX(), bounds.centerY()) ||
            !target.bounds.contains(bounds.centerX(), bounds.centerY())) return false
        return windows.none { above(it, target) && it.bounds.contains(bounds.centerX(), bounds.centerY()) }
    }
}
