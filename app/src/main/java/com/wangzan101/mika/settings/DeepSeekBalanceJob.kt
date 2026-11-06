package com.wangzan101.mika.settings

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.util.concurrent.TimeUnit

/** Best-effort periodic check. The OS can delay jobs; network failures are not low balances. */
class DeepSeekBalanceJob : JobService() {
    private var running: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            try {
                val store = (application as MikaApp).providers
                val prefs = getSharedPreferences("deepseek_balance_alerts", Context.MODE_PRIVATE)
                store.providers.value.filter(DeepSeekBalance::isOfficial).forEach { provider ->
                    val key = store.providerKey(provider.id).orEmpty()
                    if (key.isBlank()) return@forEach
                    val snapshot = runCatching { DeepSeekBalance.fetch(key) }.getOrNull() ?: return@forEach
                    val low = DeepSeekBalance.isLow(snapshot)
                    val marker = "low_${provider.id}"
                    if (!low) prefs.edit().putBoolean(marker, false).apply()
                    else if (!prefs.getBoolean(marker, false) && notifyLow()) prefs.edit().putBoolean(marker, true).apply()
                }
            } finally { if (currentCoroutineContext().isActive) jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun notifyLow(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "模型余额提醒", NotificationManager.IMPORTANCE_DEFAULT))
            val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("DeepSeek 余额偏低")
                .setContentText("请到设置 → 模型 → DeepSeek 查看余额。")
                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(intent).setAutoCancel(true).build())
            true
        }.getOrDefault(false)
    }

    companion object {
        private const val JOB_ID = 481739
        private const val NOTIFICATION_ID = 481740
        private const val INITIAL_JOB_ID = 481741
        private const val CHANNEL = "deepseek_balance_v1"
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, DeepSeekBalanceJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.HOURS.toMillis(12)).setPersisted(true).build())
            scheduler.schedule(JobInfo.Builder(INITIAL_JOB_ID, ComponentName(context, DeepSeekBalanceJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(1000).build())
        }
    }
}
