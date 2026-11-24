package com.example.skipad

import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

internal object AppUpdateScheduler {
    const val PERIODIC = 14001
    const val FOREGROUND = 14002
    const val DOWNLOAD = 14003
    fun onAppOpened(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!UpdateStore(context).automatic) {
            scheduler.cancel(PERIODIC); scheduler.cancel(FOREGROUND); return
        }
        val component = ComponentName(context, AppUpdateJobService::class.java)
        if (scheduler.getPendingJob(PERIODIC) == null) scheduler.schedule(JobInfo.Builder(PERIODIC, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(12 * 60 * 60 * 1000L)
            .setPersisted(true).build())
        val lastCheck = UpdateStore(context).lastCheck
        val recent = lastCheck > 0 && System.currentTimeMillis() - lastCheck in 0 until AppUpdateEngine.CHECK_INTERVAL
        if (!recent && scheduler.getPendingJob(FOREGROUND) == null) scheduler.schedule(JobInfo.Builder(FOREGROUND, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(1000L).build())
    }
    fun downloadFinished(context: Context) {
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(DOWNLOAD,
            ComponentName(context, AppUpdateJobService::class.java)).setMinimumLatency(0).build())
    }
}

internal class AppUpdateJobService : JobService() {
    private val active = ConcurrentHashMap<Int, JobParameters>()
    override fun onStartJob(params: JobParameters): Boolean {
        active[params.jobId] = params
        fun finished() { if (active.remove(params.jobId, params)) jobFinished(params, false) }
        if (params.jobId == AppUpdateScheduler.DOWNLOAD) {
            AppUpdates.execute { try { AppUpdates.engine(this).refresh() } finally { finished() } }
        } else AppUpdates.check(this, false) { finished() }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { active.remove(params.jobId, params); return true }
}

internal class AppUpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id >= 0 && id == UpdateStore(context).downloadId) AppUpdateScheduler.downloadFinished(context)
    }
}

internal class AppUpdateReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AppUpdateScheduler.onAppOpened(context)
            AppUpdateScheduler.downloadFinished(context)
        }
    }
}
