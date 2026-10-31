package com.wangzan101.mika.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.KeystoreKeyCipher

/**
 * 已加入 Hub 时把进程留在前台,让收指令的线程在退到后台、被系统杀掉之后还能起来。
 * 不停止正在执行的老板指令。
 */
class HubRelayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.hub_listen_channel), NotificationManager.IMPORTANCE_MIN))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!listening(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // 建出运行时,收指令的线程从这里启动。系统按 START_STICKY 把服务拉起来时也会走到。
        (application as MikaApp).hubRelay
        return START_STICKY
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(tr(R.string.hub_listen_title))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object {
        private const val CHANNEL = "hub_relay"
        private const val ID = 21
        private const val PREFS = "hub_relay"

        fun sync(context: Context) {
            val app = context.applicationContext
            if (!listening(app)) {
                app.stopService(Intent(app, HubRelayService::class.java))
                return
            }
            try {
                app.startForegroundService(Intent(app, HubRelayService::class.java))
            } catch (e: Exception) {
                Log.w("HubMcp", "foreground")
                // 前台服务起不来时,进程还在就照样开始收。
                (app as MikaApp).hubRelay
            }
        }

        fun listening(context: Context): Boolean = HubRelaySettings(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
            KeystoreKeyCipher("mika-hub-relay-token"),
        ).wantsInbound()
    }
}
