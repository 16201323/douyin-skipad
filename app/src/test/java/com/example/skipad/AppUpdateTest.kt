package com.example.skipad

import android.content.Context
import android.view.View
import android.app.DownloadManager
import android.app.job.JobScheduler
import android.content.Intent
import android.widget.RadioButton
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppUpdateTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        context.getSharedPreferences("app_update", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun aboutOffersAnUpdatePageWithoutReplacingReleaseHistory() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val root = controller.get().window.decorView
        root.findViewWithTag<View>("nav_settings").performClick()
        assertNotNull("应提供软件更新入口", root.findViewWithTag<View>("app_update"))
        assertNotNull(root.findViewWithTag<View>("open_releases"))
        root.findViewWithTag<View>("app_update").performClick()
        assertEquals("com.example.skipad.AppUpdateActivity", shadowOf(controller.get()).nextStartedActivity.component!!.className)
        controller.destroy()
    }

    private fun releaseJson(tag: String = "v9.0.0"): JSONObject = JSONObject().put("tag_name", tag)
        .put("draft", false).put("prerelease", false).put("body", "更新说明")
        .put("assets", JSONArray().put(JSONObject().put("id", 123).put("name", UpdateRelease.APK_NAME)
            .put("size", 4).put("digest", "sha256:" + "a".repeat(64))
            .put("browser_download_url", "https://github.com/16201323/douyin-skipad/releases/download/$tag/${UpdateRelease.APK_NAME}")))

    private fun release() = UpdateRelease.parse(releaseJson().toString())
    private fun engine() = AppUpdateEngine(context, UpdateSource { release() })
    private fun request(id: Long) = shadowOf(shadowOf(context.getSystemService(DownloadManager::class.java)).getRequest(id))
    private fun rejected(json: JSONObject) { assertTrue(runCatching { UpdateRelease.parse(json.toString()) }.isFailure) }

    @Test fun versionsUseNumericOrderingAndRejectPreviewTags() {
        assertTrue(ReleaseVersion.parse("v1.10.0")!! > ReleaseVersion.parse("1.9.99")!!)
        assertEquals(ReleaseVersion.parse("v2.0.0"), ReleaseVersion.parse("2.0.0"))
        assertNull(ReleaseVersion.parse("1.4.0-beta"))
        rejected(releaseJson().put("prerelease", true))
        rejected(releaseJson().put("draft", true))
    }

    @Test fun releaseRequiresTheExactRepositoryAssetSizeAndDigest() {
        assertEquals("9.0.0", release().version.toString())
        assertEquals(release(), UpdateRelease.parse(release().json()))
        for ((field, value) in listOf("digest" to "", "size" to 0,
            "browser_download_url" to "https://github.com/other/project/releases/download/v9.0.0/douyin-skipad-release.apk",
            "browser_download_url" to "http://github.com/16201323/douyin-skipad/releases/download/v9.0.0/douyin-skipad-release.apk")) {
            val data = releaseJson(); data.getJSONArray("assets").getJSONObject(0).put(field, value); rejected(data)
        }
        rejected(releaseJson().put("assets", JSONArray()))
    }

    @Test fun automaticDownloadsDefaultToWifiAndCanPersistAnyNetwork() {
        val engine = engine()
        assertTrue(engine.store.automatic)
        assertEquals(UpdateNetwork.WIFI, engine.store.network)
        engine.check(true)
        val first = engine.store.downloadId
        assertTrue(first >= 0)
        assertEquals(DownloadManager.Request.NETWORK_WIFI, request(first).allowedNetworkTypes)
        assertFalse(request(first).allowedOverMetered)
        engine.changeNetwork(UpdateNetwork.ANY)
        val second = engine.store.downloadId
        assertTrue(second != first)
        assertEquals(UpdateNetwork.ANY, UpdateStore(context).network)
        assertTrue(request(second).allowedOverMetered)
        assertFalse(request(second).allowedOverRoaming)
        assertTrue(request(second).allowedNetworkTypes and DownloadManager.Request.NETWORK_MOBILE != 0)
    }

    @Test fun manualPolicyDoesNotEnqueueAndCancelDoesNotImmediatelyRedownload() {
        val engine = engine()
        engine.store.network = UpdateNetwork.MANUAL
        assertEquals(UpdatePhase.AVAILABLE, engine.check(true).phase)
        assertEquals(-1L, engine.store.downloadId)
        engine.startDownload(false)
        assertTrue(engine.store.downloadId >= 0)
        engine.cancel()
        engine.store.network = UpdateNetwork.WIFI
        engine.check(true)
        assertEquals(-1L, engine.store.downloadId)
    }

    @Test fun taskAndProgressRecoverAcrossEngineRecreation() {
        val engine = engine()
        engine.check(true)
        request(engine.store.downloadId).apply {
            status = DownloadManager.STATUS_RUNNING; bytesSoFar = 2; totalSize = 4
        }
        val restored = engine()
        val view = restored.refresh()
        assertEquals(engine.store.downloadId, restored.store.downloadId)
        assertEquals(UpdatePhase.DOWNLOADING, view.phase)
        assertEquals(2L, view.downloaded)
        assertEquals(4L, view.total)
    }

    @Test fun readyPackageIsCheckedAgainBeforeInstallation() {
        var checks = 0
        val engine = AppUpdateEngine(context, UpdateSource { release() }) { file, _ ->
            checks++; if (file.readBytes().first() == 1.toByte()) null else "内容已变化"
        }
        engine.check(true)
        val file = engine.downloadFile(release())
        file.parentFile!!.mkdirs(); file.writeBytes(byteArrayOf(1, 2, 3, 4))
        request(engine.store.downloadId).status = DownloadManager.STATUS_SUCCESSFUL
        assertEquals(UpdatePhase.READY, engine.refresh().phase)
        assertEquals(1, checks)
        file.writeBytes(byteArrayOf(0, 2, 3, 4))
        assertTrue(runCatching { engine.fileForInstall() }.isFailure)
        assertEquals(2, checks)
        assertEquals(UpdatePhase.ERROR, engine.store.phase)
    }

    @Test fun verifierRejectsWrongPackageSignatureVersionAndCorruptContent() {
        val release = release()
        val installed = UpdatePackage("com.example.skipad", "1.4.0", 10, setOf("own-signature"))
        val archive = installed.copy(versionName = "9.0.0", versionCode = 100)
        assertNull(UpdateVerifier.error(release, installed, archive, 4, release.sha256))
        assertNotNull(UpdateVerifier.error(release, installed, archive.copy(packageName = "other.app"), 4, release.sha256))
        assertNotNull(UpdateVerifier.error(release, installed, archive.copy(signers = setOf("other")), 4, release.sha256))
        assertNotNull(UpdateVerifier.error(release, installed, archive.copy(versionCode = 10), 4, release.sha256))
        assertNotNull(UpdateVerifier.error(release, installed, archive.copy(versionName = "9.0.1"), 4, release.sha256))
        assertNotNull(UpdateVerifier.error(release, installed, archive, 3, release.sha256))
        assertNotNull(UpdateVerifier.error(release, installed, archive, 4, "b".repeat(64)))
    }

    @Test fun backgroundChecksAreThrottledAndCanBeDisabledWithoutPausingAds() {
        var fetches = 0
        val engine = AppUpdateEngine(context, UpdateSource { fetches++; release() })
        engine.store.network = UpdateNetwork.MANUAL
        engine.check(false, 1000L); engine.check(false, 2000L)
        assertEquals(1, fetches)
        engine.store.automatic = false
        engine.check(false, 30_000_000L)
        assertEquals(1, fetches)
        AppUpdateScheduler.onAppOpened(context)
        assertNull(context.getSystemService(JobScheduler::class.java).getPendingJob(AppUpdateScheduler.PERIODIC))
    }

    @Test fun completionBroadcastOnlyHandlesItsOwnDownloadId() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        UpdateStore(context).downloadId = 7L
        val receiver = AppUpdateDownloadReceiver()
        receiver.onReceive(context, Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE).putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 8L))
        assertNull(scheduler.getPendingJob(AppUpdateScheduler.DOWNLOAD))
        receiver.onReceive(context, Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE).putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 7L))
        assertNotNull(scheduler.getPendingJob(AppUpdateScheduler.DOWNLOAD))
    }

    @Test fun changingNetworkWhileCheckingStillSavesTheChoice() {
        val controller = Robolectric.buildActivity(AppUpdateActivity::class.java).create()
        AppUpdateActivity::class.java.getDeclaredField("busy").apply { isAccessible = true }.setBoolean(controller.get(), true)
        controller.get().window.decorView.findViewWithTag<RadioButton>("update_network_ANY").performClick()
        assertEquals(UpdateNetwork.ANY, UpdateStore(context).network)
        controller.destroy()
    }

    @Test fun disablingAutomaticCheckDuringRequestDoesNotStartADownload() {
        val engine = AppUpdateEngine(context, UpdateSource {
            UpdateStore(context).automatic = false
            release()
        })
        engine.check(false, 1000L)
        assertEquals(-1L, engine.store.downloadId)
    }

    @Test fun unreadableCompletedPackageCanBeRetriedInsteadOfKeepingTheBrokenTask() {
        val engine = AppUpdateEngine(context, UpdateSource { release() }) { _, _ -> throw java.io.IOException("read failed") }
        engine.check(true)
        val first = engine.store.downloadId
        val file = engine.downloadFile(release())
        file.parentFile!!.mkdirs(); file.writeBytes(byteArrayOf(1, 2, 3, 4))
        request(first).status = DownloadManager.STATUS_SUCCESSFUL
        assertEquals(UpdatePhase.ERROR, engine.refresh().phase)
        assertEquals(-1L, engine.store.downloadId)
        engine.startDownload(true)
        assertTrue(engine.store.downloadId >= 0 && engine.store.downloadId != first)
    }
}
