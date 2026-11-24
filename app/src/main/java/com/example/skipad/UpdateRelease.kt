package com.example.skipad

import org.json.JSONObject
import org.json.JSONArray
import java.net.URI

internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString(): String = "$major.$minor.$patch"
    companion object {
        fun parse(value: String): ReleaseVersion? {
            val parts = Regex("^v?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})$").matchEntire(value.trim()) ?: return null
            return ReleaseVersion(parts.groupValues[1].toInt(), parts.groupValues[2].toInt(), parts.groupValues[3].toInt())
        }
    }
}

internal enum class UpdateNetwork(val label: String) {
    WIFI("仅 Wi-Fi 自动下载"), ANY("任意网络自动下载"), MANUAL("仅提醒，手动下载")
}

internal data class UpdateRelease(val tag: String, val version: ReleaseVersion, val assetId: Long,
    val url: String, val bytes: Long, val sha256: String, val notes: String,
    val staticOrigin: String = "", val expectedVersionCode: Long? = null) {
    val fallbackUrl: String? get() = if (staticOrigin.isNotEmpty()) "https://github.com/$REPOSITORY/releases/download/$tag/$APK_NAME" else null
    val key: String get() = "$tag:$sha256"
    val fileName: String get() = "skipad-$version-$assetId.apk"

    fun json(): String = if (staticOrigin.isNotEmpty()) JSONObject().put("schemaVersion", 1)
        .put("origin", staticOrigin).put("packageName", "com.example.skipad").put("versionName", version.toString())
        .put("versionCode", expectedVersionCode).put("apk", "releases/$tag/$APK_NAME")
        .put("size", bytes).put("sha256", sha256).put("notes", notes).toString()
        else JSONObject().put("tag_name", tag).put("body", notes).put("draft", false)
        .put("prerelease", false).put("assets", JSONArray().put(JSONObject().put("id", assetId)
            .put("name", APK_NAME).put("browser_download_url", url).put("size", bytes)
            .put("digest", "sha256:$sha256"))).toString()

    companion object {
        fun parseStatic(raw: String, origin: String): UpdateRelease {
            require(raw.length <= 512 * 1024) { "版本信息过大" }
            val base = URI(origin)
            require(base.scheme == "https" && !base.host.isNullOrBlank() && base.rawAuthority == base.host &&
                base.rawPath.isEmpty() && base.query == null && base.fragment == null) { "更新站点尚未正确配置" }
            val data = JSONObject(raw)
            require(data.opt("schemaVersion") == 1 && data.getString("packageName") == "com.example.skipad") { "版本信息不属于本应用" }
            require(!data.has("origin") || data.getString("origin") == origin) { "保存的更新来源与配置不一致" }
            val version = requireNotNull(ReleaseVersion.parse(data.getString("versionName"))) { "版本格式不支持" }
            val tag = "v$version"
            val path = data.getString("apk")
            require(path == "releases/$tag/$APK_NAME") { "安装包来源或路径不匹配" }
            fun positiveInteger(key: String): Long {
                val value = data.get(key)
                require(value is Number && Regex("^[0-9]+$").matches(value.toString())) { "版本数值无效" }
                return value.toString().toLong().also { require(it > 0) { "版本数值无效" } }
            }
            val code = positiveInteger("versionCode")
            require(code <= Int.MAX_VALUE) { "版本编号无效" }
            val bytes = positiveInteger("size")
            require(bytes <= MAX_BYTES) { "安装包大小无效" }
            val digest = data.getString("sha256").lowercase()
            require(Regex("^[0-9a-f]{64}$").matches(digest)) { "发布版本缺少有效校验信息" }
            return UpdateRelease(tag, version, code, "$origin/$path", bytes, digest, data.optString("notes").take(4000), origin, code)
        }

        fun parseStored(raw: String, origin: String = BuildConfig.STATIC_UPDATE_ORIGIN): UpdateRelease =
            if (JSONObject(raw).has("schemaVersion")) parseStatic(raw, origin) else parse(raw)
        const val REPOSITORY = "16201323/douyin-skipad"
        const val RELEASES_URL = "https://github.com/$REPOSITORY/releases"
        const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        const val APK_NAME = "douyin-skipad-release.apk"
        const val MAX_BYTES = 100L * 1024 * 1024

        fun parse(raw: String): UpdateRelease {
            require(raw.length <= 512 * 1024) { "版本信息过大" }
            val data = JSONObject(raw)
            require(!data.optBoolean("draft") && !data.optBoolean("prerelease")) { "仅支持正式发布版本" }
            val tag = data.getString("tag_name")
            val version = requireNotNull(ReleaseVersion.parse(tag)) { "版本格式不支持" }
            val assets = data.getJSONArray("assets")
            val matching = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == APK_NAME }
            require(matching.size == 1) { "发布版本缺少唯一的安装包" }
            val asset = matching.single()
            val uri = URI(asset.getString("browser_download_url"))
            require(uri.scheme == "https" && uri.host.equals("github.com", true) && uri.userInfo == null &&
                uri.port in listOf(-1, 443) && uri.query == null && uri.fragment == null &&
                uri.rawPath == "/$REPOSITORY/releases/download/$tag/$APK_NAME") { "安装包来源不匹配" }
            val bytes = asset.getLong("size")
            require(bytes in 1..MAX_BYTES) { "安装包大小无效" }
            val digest = asset.getString("digest").lowercase()
            require(Regex("^sha256:[0-9a-f]{64}$").matches(digest)) { "发布版本缺少有效校验信息" }
            val id = asset.getLong("id")
            require(id > 0) { "附件编号无效" }
            return UpdateRelease(tag, version, id, uri.toString(), bytes, digest.removePrefix("sha256:"), data.optString("body").take(4000))
        }
    }
}

internal data class UpdatePackage(val packageName: String, val versionName: String, val versionCode: Long,
    val signers: Set<String>)

internal object UpdateVerifier {
    fun error(release: UpdateRelease, installed: UpdatePackage, archive: UpdatePackage?,
        fileBytes: Long, sha256: String): String? = when {
        fileBytes != release.bytes -> "安装包大小不完整，请重新下载"
        !sha256.equals(release.sha256, true) -> "安装包校验失败，请重新下载"
        archive == null -> "无法读取安装包信息"
        archive.packageName != installed.packageName -> "安装包不属于本应用"
        archive.versionCode <= installed.versionCode -> "安装包版本未高于当前版本"
        release.expectedVersionCode != null && archive.versionCode != release.expectedVersionCode -> "安装包版本编号与发布信息不一致"
        ReleaseVersion.parse(archive.versionName) != release.version -> "安装包版本与发布信息不一致"
        installed.signers.isEmpty() || archive.signers != installed.signers -> "安装包签名与当前应用不一致"
        else -> null
    }
}
