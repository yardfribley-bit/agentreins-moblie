package com.wangzan101.mika.relay

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.task.TaskService

/**
 * 已加入 Hub 时把进程留在前台,让收指令的线程在退到后台、被系统杀掉之后还能起来。
 * 不停止正在执行的老板指令。认领 Hub 任务仍然默认关,这里也不去认领。
 */
class HubRelayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ensureChannel(this)
            startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: Exception) {
            Log.i(TAG, foregroundFailure(e))
            running = false
            (application as? MikaApp)?.hubRelay
            stopSelf()
            return START_NOT_STICKY
        }
        val blocker = listenBlocker(this)
        if (blocker != null) {
            Log.i(TAG, "sync skip: $blocker")
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 建出运行时,收指令的线程从这里启动。系统按 START_STICKY 把服务拉起来时也会走到。
        (application as MikaApp).hubRelay
        running = true
        Log.i(TAG, "foreground service started")
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "POST_NOTIFICATIONS not granted; notification may be hidden")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.hub_listen_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(TaskService.openApp(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "HubMcp"
        private const val CHANNEL = "hub_relay_listen"
        private const val OLD_CHANNEL = "hub_relay"
        private const val ID = 21
        private const val PREFS = "hub_relay"

        @Volatile
        private var running = false

        /** 界面回到前台后再启动一次。冷启动的 Application.onCreate 里系统经常还不允许前台服务。 */
        fun promoteWhenResumed(): Application.ActivityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (running || !listening(activity)) return
                val view = activity.window?.decorView
                if (view == null) {
                    sync(activity)
                    return
                }
                val again = Runnable {
                    if (!running && listening(activity)) sync(activity)
                }
                view.post(again)
                // 有的系统要等这一帧结束才把进程算作前台,晚半秒再试一次。
                view.postDelayed(again, 500)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }

        fun sync(context: Context) {
            val app = context.applicationContext
            val blocker = listenBlocker(app)
            if (blocker != null) {
                Log.i(TAG, "sync skip: $blocker")
                running = false
                app.stopService(Intent(app, HubRelayService::class.java))
                return
            }
            missingDataSyncPermission(app)?.let { Log.i(TAG, "startForeground failed: missing permission $it") }
            // 前台服务起不来时,进程还在就照样开始收。不打断已经在跑的老板指令。
            try {
                (app as? MikaApp)?.hubRelay
            } catch (e: Exception) {
                Log.i(TAG, "inbound start failed: ${e.javaClass.name}: ${e.message}")
            }
            try {
                app.startForegroundService(Intent(app, HubRelayService::class.java))
            } catch (e: Exception) {
                Log.i(TAG, foregroundFailure(e))
            }
        }

        fun listening(context: Context): Boolean = settings(context).wantsInbound()

        internal fun foregroundFailure(e: Throwable): String =
            "startForeground failed: ${e.javaClass.name}: ${e.message}"

        /** 安卓 14 起 dataSync 前台服务要有这两项安装时权限,并在 service 上声明 foregroundServiceType。 */
        internal fun missingDataSyncPermission(context: Context): String? {
            if (Build.VERSION.SDK_INT < 34) return null
            val missing = listOf(
                android.Manifest.permission.FOREGROUND_SERVICE,
                android.Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC,
            ).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            return missing.joinToString(", ").ifBlank { null }
        }

        private fun listenBlocker(context: Context): String? = settings(context).inboundBlocker()

        private fun settings(context: Context) = HubRelaySettings(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            KeystoreKeyCipher("mika-hub-relay-token"),
        )

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            // 旧频道是 IMPORTANCE_MIN,系统不允许再调高,通知会一直藏着。换新 id,旧的删掉。
            nm.deleteNotificationChannel(OLD_CHANNEL)
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, tr(R.string.hub_listen_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
