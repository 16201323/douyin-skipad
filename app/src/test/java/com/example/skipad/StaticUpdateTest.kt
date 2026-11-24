package com.example.skipad

import android.app.DownloadManager
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StaticUpdateTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val origin = "https://skipad-test.pages.dev"
    private fun manifest() = JSONObject().put("schemaVersion", 1).put("packageName", "com.example.skipad")
        .put("versionName", "9.0.0").put("versionCode", 900).put("apk", "releases/v9.0.0/douyin-skipad-release.apk")
        .put("size", 4).put("sha256", "a".repeat(64)).put("notes", "静态更新说明")
    private fun release() = UpdateRelease.parseStatic(manifest().toString(), origin)

    @Before fun setup() { context.getSharedPreferences("app_update", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun staticManifestBuildsOnlyTheConfiguredVersionedApkUrlAndRestores() {
        val release = release()
        assertEquals("$origin/releases/v9.0.0/douyin-skipad-release.apk", release.url)
        assertEquals(900L, release.expectedVersionCode)
        assertEquals("静态更新说明", release.notes)
        assertEquals(release, UpdateRelease.parseStored(release.json(), origin))
        assertTrue(runCatching { UpdateRelease.parseStored(release.json(), "https://other.pages.dev") }.isFailure)
        assertTrue(runCatching { UpdateRelease.parseStatic(manifest().toString(), "") }.isFailure)
    }

    @Test fun staticManifestRejectsForeignUrlsTraversalBadIdentityAndMissingIntegrity() {
        for ((field, value) in listOf("schemaVersion" to 2, "schemaVersion" to 1.5, "packageName" to "other.app", "versionName" to "9.0.0-beta",
            "versionCode" to 0, "versionCode" to 1.5, "size" to 0, "size" to 104857601L,
            "sha256" to "bad", "apk" to "https://evil.test/app.apk", "apk" to "//evil.test/app.apk",
            "apk" to "releases/../app.apk", "apk" to "releases/v9.0.0/douyin-skipad-release.apk?x=1",
            "apk" to "releases/v9.0.1/douyin-skipad-release.apk")) {
            assertTrue("must reject $field=$value", runCatching { UpdateRelease.parseStatic(manifest().put(field, value).toString(), origin) }.isFailure)
        }
        for (base in listOf("http://skipad-test.pages.dev", "https://user@skipad-test.pages.dev", "$origin/path", "$origin?key=secret")) {
            assertTrue(runCatching { UpdateRelease.parseStatic(manifest().toString(), base) }.isFailure)
        }
    }

    @Test fun failedStaticMetadataUsesGithubButValidStaticMetadataDoesNotContactGithub() {
        var backups = 0
        val primary = StaticUpdateSource(origin, UpdateTextClient { url ->
            assertEquals("$origin/update.json", url); manifest().toString()
        })
        val source = FallbackUpdateSource(primary, UpdateSource { backups++; release() })
        assertEquals(release(), source.latest())
        assertEquals(0, backups)
        val unavailable = FallbackUpdateSource(UpdateSource { throw IOException("offline") }, UpdateSource { backups++; release() })
        assertEquals(release(), unavailable.latest())
        assertEquals(1, backups)
        val bothFailed = FallbackUpdateSource(UpdateSource { throw IOException("primary") }, UpdateSource { throw IOException("backup") })
        assertTrue(runCatching { bothFailed.latest() }.isFailure)
    }

    @Test fun staticDownloadsUseTheSelectedNetworkAndPersistAcrossRestart() {
        val engine = AppUpdateEngine(context, UpdateSource { release() }, staticOrigin = origin)
        engine.check(true)
        val manager = context.getSystemService(DownloadManager::class.java)
        var request = shadowOf(shadowOf(manager).getRequest(engine.store.downloadId))
        assertEquals(release().url, request.uri.toString())
        assertFalse(request.allowedOverMetered)
        engine.changeNetwork(UpdateNetwork.ANY)
        request = shadowOf(shadowOf(manager).getRequest(engine.store.downloadId))
        assertTrue(request.allowedOverMetered)
        val restored = AppUpdateEngine(context, UpdateSource { release() }, staticOrigin = origin)
        assertEquals(release(), restored.store.release)
        assertEquals(engine.store.downloadId, restored.store.downloadId)
    }

    @Test fun failedStaticDownloadFallsBackOnceWithoutLosingWifiPolicyAndCancelStopsIt() {
        val engine = AppUpdateEngine(context, UpdateSource { release() }, staticOrigin = origin)
        val manager = context.getSystemService(DownloadManager::class.java)
        engine.check(true)
        val first = engine.store.downloadId
        shadowOf(shadowOf(manager).getRequest(first)).status = DownloadManager.STATUS_FAILED
        engine.refresh()
        val second = engine.store.downloadId
        assertTrue(second >= 0 && second != first)
        val backup = shadowOf(shadowOf(manager).getRequest(second))
        assertEquals(release().fallbackUrl, backup.uri.toString())
        assertFalse(backup.allowedOverMetered)
        backup.status = DownloadManager.STATUS_FAILED
        assertEquals(UpdatePhase.ERROR, engine.refresh().phase)
        assertEquals(-1L, engine.store.downloadId)
        engine.startDownload(true)
        engine.cancel(); engine.check(true)
        assertEquals(-1L, engine.store.downloadId)
    }

    @Test fun staticVersionCodeMustMatchTheDownloadedPackage() {
        val installed = UpdatePackage("com.example.skipad", "1.4.1", 11, setOf("own"))
        val archive = installed.copy(versionName = "9.0.0", versionCode = 901)
        assertNotNull(UpdateVerifier.error(release(), installed, archive, 4, release().sha256))
        assertNull(UpdateVerifier.error(release(), installed, archive.copy(versionCode = 900), 4, release().sha256))
    }

    @Test fun configurationOrDigestFailureDoesNotBecomeAnUnverifiedDownload() {
        val engine = AppUpdateEngine(context, StaticUpdateSource(origin, UpdateTextClient { manifest().put("sha256", "").toString() }), staticOrigin = origin)
        assertEquals(UpdatePhase.ERROR, engine.check(true).phase)
        assertEquals(-1L, engine.store.downloadId)
    }

    @Test fun completedStaticPackageThatFailsVerificationDoesNotTryABackup() {
        val engine = AppUpdateEngine(context, UpdateSource { release() }, staticOrigin = origin) { _, _ -> "签名不一致" }
        engine.check(true)
        val manager = context.getSystemService(DownloadManager::class.java)
        val file = engine.downloadFile(release())
        file.parentFile!!.mkdirs(); file.writeBytes(byteArrayOf(1, 2, 3, 4))
        shadowOf(shadowOf(manager).getRequest(engine.store.downloadId)).status = DownloadManager.STATUS_SUCCESSFUL
        assertEquals(UpdatePhase.ERROR, engine.refresh().phase)
        assertEquals(-1L, engine.store.downloadId)
        assertFalse(engine.store.downloadFallback)
    }

    @Test fun cancellingAVersionSurvivesMetadataFailoverToTheSameGithubPackage() {
        val static = release()
        var useGithub = false
        val github = static.copy(assetId = 123, url = static.fallbackUrl!!, staticOrigin = "", expectedVersionCode = null)
        val engine = AppUpdateEngine(context, UpdateSource { if (useGithub) github else static }, staticOrigin = origin)
        engine.check(true); engine.cancel()
        useGithub = true
        engine.check(true)
        assertEquals("切换来源不能取消用户的停止下载意愿", -1L, engine.store.downloadId)
    }
}
