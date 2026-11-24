package com.example.skipad

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal fun interface UpdateTextClient { fun get(url: String): String }

internal object HttpUpdateTextClient : UpdateTextClient {
    override fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000; connection.readTimeout = 15000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", "douyin-skipad-android")
            if (connection.responseCode != 200) throw IOException("更新来源暂时不可用")
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    require(output.size() + count <= 512 * 1024) { "版本信息超过允许大小" }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally { connection.disconnect() }
    }
}

internal class GitHubUpdateSource(private val client: UpdateTextClient = HttpUpdateTextClient) : UpdateSource {
    override fun latest(): UpdateRelease = UpdateRelease.parse(client.get(UpdateRelease.API_URL))
}

internal class StaticUpdateSource(private val origin: String, private val client: UpdateTextClient = HttpUpdateTextClient) : UpdateSource {
    override fun latest(): UpdateRelease = UpdateRelease.parseStatic(client.get("$origin/update.json"), origin)
}

internal class FallbackUpdateSource(private val primary: UpdateSource, private val backup: UpdateSource) : UpdateSource {
    override fun latest(): UpdateRelease = try { primary.latest() } catch (primaryError: Exception) {
        try { backup.latest() } catch (backupError: Exception) {
            throw IOException("更新站点与备用来源均不可用，请稍后重试", primaryError).apply { addSuppressed(backupError) }
        }
    }
}

internal object UpdateSources {
    fun default(): UpdateSource = if (BuildConfig.STATIC_UPDATE_ORIGIN.isBlank()) GitHubUpdateSource()
        else FallbackUpdateSource(StaticUpdateSource(BuildConfig.STATIC_UPDATE_ORIGIN), GitHubUpdateSource())
}
