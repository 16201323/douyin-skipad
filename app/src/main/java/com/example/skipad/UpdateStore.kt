package com.example.skipad

import android.content.Context

internal enum class UpdatePhase { IDLE, CHECKING, LATEST, AVAILABLE, QUEUED, DOWNLOADING, READY, ERROR }
internal data class UpdateView(val phase: UpdatePhase, val message: String, val release: UpdateRelease?,
    val downloaded: Long = 0, val total: Long = 0)

internal class UpdateStore(context: Context, private val staticOrigin: String = BuildConfig.STATIC_UPDATE_ORIGIN) {
    private val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit().putBoolean("automatic", value).apply() }
    var network: UpdateNetwork
        get() = runCatching { UpdateNetwork.valueOf(prefs.getString("network", "WIFI")!!) }.getOrDefault(UpdateNetwork.WIFI)
        set(value) { prefs.edit().putString("network", value.name).apply() }
    var lastCheck: Long
        get() = prefs.getLong("last_check", 0)
        set(value) { prefs.edit().putLong("last_check", value).apply() }
    var release: UpdateRelease?
        get() = prefs.getString("release", null)?.let { runCatching { UpdateRelease.parseStored(it, staticOrigin) }.getOrNull() }
        set(value) { prefs.edit().putString("release", value?.json()).apply() }
    var downloadId: Long
        get() = prefs.getLong("download_id", -1)
        set(value) { prefs.edit().putLong("download_id", value).apply() }
    var downloadWifiOnly: Boolean
        get() = prefs.getBoolean("download_wifi", true)
        set(value) { prefs.edit().putBoolean("download_wifi", value).apply() }
    var downloadFallback: Boolean
        get() = prefs.getBoolean("download_fallback", false)
        set(value) { prefs.edit().putBoolean("download_fallback", value).apply() }
    var cancelledKey: String
        get() = prefs.getString("cancelled_key", "").orEmpty()
        set(value) { prefs.edit().putString("cancelled_key", value).apply() }
    val phase: UpdatePhase get() = runCatching {
        UpdatePhase.valueOf(prefs.getString("phase", "IDLE")!!)
    }.getOrDefault(UpdatePhase.IDLE)
    val message: String get() = prefs.getString("message", "尚未检查更新").orEmpty()
    fun state(phase: UpdatePhase, message: String) {
        prefs.edit().putString("phase", phase.name).putString("message", message).apply()
    }
}
