package com.wangzan101.mika.recording

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** User explicitly selects a source; screen capture always uses Android's consent prompt. */
class VideoRecordingActivity : ComponentActivity() {
    private lateinit var state: TextView
    private lateinit var target: Button
    private lateinit var screen: Button
    private lateinit var stop: Button
    private lateinit var view: Button
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            val running = VideoRecordingService.isRecording()
            state.text = VideoRecordingService.status
            target.isEnabled = !running
            screen.isEnabled = !running
            stop.isEnabled = running
            view.isEnabled = VideoRecordingService.lastVideo != null
            handler.postDelayed(this, 500)
        }
    }
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startForegroundService(Intent(this, VideoRecordingService::class.java).setAction(VideoRecordingService.SCREEN).putExtra("grant", result.data))
        } else state.text = "未授权录制屏幕"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun text(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setPadding(0, pad / 2, 0, pad / 2); box.addView(this) }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() }; box.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        text("录制视频", 24f)
        text("选择一种画面。视频保存到相册 Movies/AgentReins，当前版本不录制声音。录屏可能包含通知或其他敏感内容，发布前请检查。", 16f)
        state = text(VideoRecordingService.status, 16f)
        screen = button("录制手机屏幕 / 小助手界面") { capture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
        target = button("录制任务操作画面") { startForegroundService(Intent(this, VideoRecordingService::class.java).setAction(VideoRecordingService.TARGET)) }
        stop = button("停止并保存") { startService(Intent(this, VideoRecordingService::class.java).setAction(VideoRecordingService.STOP)) }
        view = button("查看最近视频") {
            VideoRecordingService.lastVideo?.let { video ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(video), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            }
        }
        button("返回") { finish() }
        setContentView(box)
    }
    override fun onStart() { super.onStart(); handler.post(refresh) }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }
}
