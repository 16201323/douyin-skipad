package com.example.skipad

import android.graphics.Path

/**
 * 悬浮窗形状的路径构建工具。
 *
 * 集中管理所有「非原生」形状（爱心 / 五角星 / 云朵 / 三角形）的 Path 生成，
 * 供两处复用，保证显示一致：
 * 1. FloatingView 绘制悬浮窗背景 / 裁剪图片（图片模式同样生效）
 * 2. MainActivity 形状选择器的图形预览按钮
 *
 * 圆形 / 圆角矩形 / 方形由 Canvas 原生方法绘制，这里不处理（buildPath 返回 null）。
 */
object ShapePainter {

    /** 根据形状常量构建轮廓 Path；原生形状返回 null，由调用方用 Canvas 原生方法绘制 */
    fun buildPath(shape: Int, w: Float, h: Float): Path? = when (shape) {
        ConfigManager.SHAPE_HEART -> buildHeartPath(w, h)
        ConfigManager.SHAPE_STAR -> buildStarPath(w, h)
        ConfigManager.SHAPE_CLOUD -> buildCloudPath(w, h)
        ConfigManager.SHAPE_TRIANGLE -> buildTrianglePath(w, h)
        else -> null
    }

    /**
     * 构建爱心轮廓：两段对称的三次贝塞尔曲线围成。
     * 关键点：心形整体留 8% 内边距，左右最远点严格不超过 View 边界，
     * 避免被窗口裁切（旧版用 0.52w 超出 0.5w，导致左右叶被切断）。
     */
    private fun buildHeartPath(w: Float, h: Float): Path {
        val path = Path()
        val cx = w / 2
        // 心形可用区域：四周留 8% 内边距，确保完整显示不被裁切
        val pad = w * 0.08f
        val cw = w - 2 * pad
        val ch = h - 2 * pad
        val top = pad
        val bottom = pad + ch
        val halfW = cw / 2   // 左右叶最远点距中心的距离，= 0.42w，不会越界

        // 起笔于顶部中央的凹陷处
        path.moveTo(cx, top + ch * 0.30f)
        // 左半心：向左上方鼓起形成左叶，再向下收至心尖
        path.cubicTo(cx, top + ch * 0.12f, cx - halfW, top + ch * 0.12f, cx - halfW, top + ch * 0.40f)
        path.cubicTo(cx - halfW, top + ch * 0.64f, cx, top + ch * 0.76f, cx, bottom)
        // 右半心：从心尖镜像回到起点
        path.cubicTo(cx, top + ch * 0.76f, cx + halfW, top + ch * 0.64f, cx + halfW, top + ch * 0.40f)
        path.cubicTo(cx + halfW, top + ch * 0.12f, cx, top + ch * 0.12f, cx, top + ch * 0.30f)
        path.close()
        return path
    }

    /** 构建五角星轮廓：10 个顶点（外/内半径交替）连线 */
    private fun buildStarPath(w: Float, h: Float): Path {
        val path = Path()
        val cx = w / 2
        val cy = h / 2
        val outer = minOf(w, h) / 2        // 外接圆半径
        val inner = outer * 0.4f           // 内接圆半径（决定星的“胖瘦”）
        val step = Math.PI / 5             // 相邻顶点夹角 36°
        var angle = -Math.PI / 2           // 从正上方开始
        path.moveTo(cx, cy - outer)
        for (i in 1..9) {
            angle += step
            val r = if (i % 2 == 0) outer else inner
            val x = cx + (r * Math.cos(angle)).toFloat()
            val y = cy + (r * Math.sin(angle)).toFloat()
            path.lineTo(x, y)
        }
        path.close()
        return path
    }

    /** 构建云朵轮廓：多个相交圆形叠加，视觉上连成云朵 */
    private fun buildCloudPath(w: Float, h: Float): Path {
        val path = Path()
        val cx = w / 2
        val cy = h / 2
        val base = minOf(w, h)
        // 中间大圆 + 左右两侧圆 + 顶部两个小圆，重叠处自然融合
        path.addCircle(cx, cy, base * 0.28f, Path.Direction.CW)
        path.addCircle(cx - w * 0.22f, cy + h * 0.06f, base * 0.20f, Path.Direction.CW)
        path.addCircle(cx + w * 0.22f, cy + h * 0.06f, base * 0.20f, Path.Direction.CW)
        path.addCircle(cx - w * 0.13f, cy - h * 0.12f, base * 0.18f, Path.Direction.CW)
        path.addCircle(cx + w * 0.13f, cy - h * 0.12f, base * 0.18f, Path.Direction.CW)
        return path
    }

    /** 构建三角形轮廓：顶角在上的等腰三角形 */
    private fun buildTrianglePath(w: Float, h: Float): Path {
        val path = Path()
        path.moveTo(w / 2, 0f)       // 顶点
        path.lineTo(0f, h)           // 左下角
        path.lineTo(w, h)            // 右下角
        path.close()
        return path
    }
}
