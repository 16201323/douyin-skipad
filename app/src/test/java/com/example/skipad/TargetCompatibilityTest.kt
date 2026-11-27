package com.example.skipad

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [QueryNodeShadow::class])
class TargetCompatibilityTest {
    private val packages = listOf("com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.lite")

    private fun node(pkg: String, text: String, bounds: Rect, id: String? = null) = AccessibilityNodeInfo.obtain().apply {
        packageName = pkg
        this.text = text
        isVisibleToUser = true
        viewIdResourceName = id?.let { "$pkg:id/$it" }
        setBoundsInScreen(bounds)
    }

    @Test fun originalAndRenamedIdsKeepAdsSeparateFromNormalContentForBothPackages() {
        for (pkg in packages) for ((exit, footer) in listOf("b4=" to "cq-", "b5i" to "crf")) {
            for ((caption, expected) in listOf("商品介绍 广告" to true, "普通剧情介绍" to false, "这个视频不是广告" to false)) {
                val root = node(pkg, "", Rect(0, 0, 1240, 2772))
                shadowOf(root).addChild(node(pkg, "退出短剧", Rect(189, 185, 413, 262), exit))
                shadowOf(root).addChild(node(pkg, "短剧 · 测试剧名", Rect(161, 2658, 575, 2720), footer))
                shadowOf(root).addChild(node(pkg, caption, Rect(42, 2249, 914, 2406), "desc"))
                val observation = AdDetector.inspectFocused(root, pkg)!!
                assertTrue("$pkg / $exit 应保留播放器上下文", observation.inPlayer)
                assertEquals("$pkg / $exit / $caption", expected, observation.isAd)
                assertNull("不能把另一包名的页面作为当前目标", AdDetector.inspectFocused(root, "other.package"))
            }
        }
    }

    @Test fun descriptionIdDoesNotHideEpisodeAndSelectionAnchorsInOlderLayouts() {
        for (pkg in packages) {
            val root = node(pkg, "", Rect(0, 0, 1240, 2772))
            shadowOf(root).addChild(node(pkg, "第12集", Rect(40, 180, 250, 270)))
            shadowOf(root).addChild(node(pkg, "选集", Rect(1000, 2500, 1200, 2600)))
            shadowOf(root).addChild(node(pkg, "普通介绍", Rect(42, 2249, 914, 2406), "desc"))
            shadowOf(root).addChild(node(pkg, "5秒后可继续看下一集", Rect(320, 1850, 920, 2010)))
            assertTrue("$pkg 的介绍栏不能阻止读取旧版集数和选集", AdDetector.inspectFocused(root, pkg)!!.isAd)
        }
    }

    @Test fun bothTargetsRemainEnabledOnFreshInstallation() {
        val context = RuntimeEnvironment.getApplication()
        ConfigManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        context.getSharedPreferences("skip_ad_config", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(packages.toSet(), ConfigManager.get(context).getEnabledApps())
    }
}
