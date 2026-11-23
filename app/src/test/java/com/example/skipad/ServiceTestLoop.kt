package com.example.skipad

import android.os.Handler
import android.os.Looper
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController

/** 规则/时序回归使用虚拟时钟；线程隔离测试保留真实检测线程。 */
internal fun floatingServiceOnTestLooper(): ServiceController<FloatingButtonService> {
    val controller = Robolectric.buildService(FloatingButtonService::class.java).create()
    controller.get().javaClass.getDeclaredField("detectionHandler").apply { isAccessible = true }
        .set(controller.get(), Handler(Looper.getMainLooper()))
    return controller
}

internal fun stopLeakedFloatingService() {
    val service = FloatingButtonService::class.java.getDeclaredField("runningInstance")
        .apply { isAccessible = true }.get(null) as? FloatingButtonService
    service?.onDestroy()
}
