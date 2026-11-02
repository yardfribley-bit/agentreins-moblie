package com.wangzan101.mika.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.OverlayPlacement
import com.wangzan101.mika.cap.phone.a11y.OverlayWindow
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility

/** Ongoing 「正在出声」 notification and, when accessibility can draw, a tappable capsule. */
object SpeakingIndicator {
    private const val SPEAKING = 7822
    private const val ASK = 7823
    private const val CHANNEL = "live-speech"
    private val main = Handler(Looper.getMainLooper())
    private var capsule: OverlayWindow? = null

    fun show(context: Context, onStop: () -> Unit) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "直播出声", NotificationManager.IMPORTANCE_DEFAULT))
        val stop = PendingIntent.getBroadcast(
            app, 1, Intent(app, SpeechControlReceiver::class.java).setAction(SpeechControlReceiver.STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("正在出声")
            .setContentText("点此或点胶囊上的字可立即停止，并释放麦位")
            .setOngoing(true)
            .setColor(AndroidColor.parseColor("#B3261E"))
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
        manager.notify(SPEAKING, notification)
        main.post {
            val host = ScreenAccessibility.instance.value?.overlays() ?: return@post
            capsule?.close()
            capsule = host.open(OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, y = 160)) {
                SpeakingChip(onStop)
            }
        }
    }

    fun hide(context: Context) {
        context.applicationContext.getSystemService(NotificationManager::class.java).cancel(SPEAKING)
        main.post {
            capsule?.close()
            capsule = null
        }
    }

    fun ask(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "直播出声", NotificationManager.IMPORTANCE_HIGH))
        fun answer(action: String, code: Int, label: String): Notification.Action {
            val intent = PendingIntent.getBroadcast(
                app, code, Intent(app, SpeechControlReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Action.Builder(null, label, intent).build()
        }
        val notification = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("允许申请连麦？")
            .setContentText("助手要在直播间上麦并出声。同意后才会去点连麦。")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(answer(SpeechControlReceiver.ALLOW, 2, "同意"))
            .addAction(answer(SpeechControlReceiver.DENY, 3, "拒绝"))
            .build()
        manager.notify(ASK, notification)
        runCatching {
            app.startActivity(Intent(app, MicConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun dismissAsk(context: Context) {
        context.applicationContext.getSystemService(NotificationManager::class.java).cancel(ASK)
    }
}

@Composable
private fun SpeakingChip(onStop: () -> Unit) {
    Text(
        "正在出声",
        modifier = Modifier
            .clickable(onClick = onStop)
            .background(Color(0xCCB3261E))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        color = Color.White,
        fontSize = 16.sp,
    )
}
