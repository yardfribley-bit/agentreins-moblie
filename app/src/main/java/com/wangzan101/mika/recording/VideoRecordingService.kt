package com.wangzan101.mika.recording

import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One explicit recording at a time. Target frames are supplied by the agent's virtual display. */
class VideoRecordingService : Service() {
    private val lock = Any()
    private var recorder: MediaRecorder? = null
    private var surface: Surface? = null
    private var file: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var display: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var frames = 0
    @Volatile private var targetMode = false
    private val targetSource = AtomicReference<Any?>(null)
    private val encoderThread = HandlerThread("target-video-encoder").apply { start() }
    private val encoderHandler = Handler(encoderThread.looper)
    private val framePending = AtomicBoolean(false)
    @Volatile private var lastFrameAt = 0L
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finishRecording("录制已停止"); return START_NOT_STICKY }
        if (intent == null || current != null) return START_NOT_STICKY
        targetMode = intent.action == TARGET
        current = this
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "视频录制", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 1, Intent(this, VideoRecordingActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("X 小助手正在录屏").setContentText(if (targetMode) "任务画面 · 点击停止结束录制" else "手机屏幕 · 点击停止结束录制")
                .setOngoing(true).setContentIntent(open).addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
            val serviceType = if (targetMode) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(7844, notification, serviceType)
            if (targetMode) {
                status = "等待任务画面；开始任务后自动录制"
            } else {
                @Suppress("DEPRECATION")
                val grant = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("grant", Intent::class.java) else intent.getParcelableExtra("grant")
                requireNotNull(grant) { "未获得系统录屏授权" }
                val metrics = resources.displayMetrics
                val (w, h) = dimensions(metrics.widthPixels, metrics.heightPixels)
                val p = requireNotNull(getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, grant))
                projection = p
                p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { finishRecording("系统已停止录屏") } }, android.os.Handler(mainLooper))
                prepare(w, h)
                display = p.createVirtualDisplay("AgentReinsRecording", w, h, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
                requireNotNull(display) { "无法创建录屏画面" }
                status = "正在录制手机屏幕（无声音）"
            }
        } catch (e: Exception) {
            finishRecording("录制启动失败：${e.message ?: e.javaClass.simpleName}")
        }
        return START_NOT_STICKY
    }

    private fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(1.0, 720.0 / minOf(width, height))
        return ((width * scale).toInt().coerceAtLeast(2) / 2 * 2) to ((height * scale).toInt().coerceAtLeast(2) / 2 * 2)
    }

    private fun prepare(width: Int, height: Int) {
        val name = "AgentReins_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AgentReins")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val output = requireNotNull(contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        uri = output
        val descriptor = requireNotNull(contentResolver.openFileDescriptor(output, "w"))
        file = descriptor
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        recorder = r
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(width, height)
        r.setVideoFrameRate(if (targetMode) 10 else 30)
        r.setVideoEncodingBitRate(4_000_000)
        r.setOutputFile(descriptor.fileDescriptor)
        r.prepare()
        surface = r.surface
        r.start()
    }

    private fun targetFrame(source: Any, bitmap: Bitmap, width: Int, height: Int) {
        if (stopping || !targetMode) return
        targetSource.compareAndSet(null, source)
        if (targetSource.get() !== source) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < 100 || !framePending.compareAndSet(false, true)) return
        lastFrameAt = now
        try {
            val (w, h) = dimensions(width, height)
            // Copy before the virtual display reuses its bitmap; never wait for the encoder.
            val copy = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(copy).drawBitmap(bitmap, Rect(0, 0, width, height), Rect(0, 0, w, h), null)
            if (!encoderHandler.post { encodeTargetFrame(copy) }) {
                framePending.set(false)
                copy.recycle()
            }
        } catch (e: Exception) {
            framePending.set(false)
            Handler(mainLooper).post { finishRecording("任务画面录制失败：${e.message ?: e.javaClass.simpleName}") }
        }
    }

    private fun encodeTargetFrame(bitmap: Bitmap) = synchronized(lock) {
        try {
            if (stopping) return@synchronized
            if (recorder == null) {
                prepare(bitmap.width, bitmap.height)
                status = "正在录制任务画面（无声音）"
            }
            val s = requireNotNull(surface)
            val canvas = s.lockHardwareCanvas()
            try {
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bitmap, null, Rect(0, 0, canvas.width, canvas.height), null)
            } finally { s.unlockCanvasAndPost(canvas) }
            frames++
        } catch (e: Exception) {
            android.os.Handler(mainLooper).post { finishRecording("任务画面录制失败：${e.message ?: e.javaClass.simpleName}") }
        } finally {
            framePending.set(false)
            bitmap.recycle()
        }
    }

    private fun finishRecording(message: String) = synchronized(lock) {
        if (stopping) return@synchronized
        stopping = true
        current = null
        display?.release(); display = null
        projection?.stop(); projection = null
        var saved = false
        if (recorder != null) {
            saved = runCatching { recorder?.stop(); true }.getOrDefault(false)
            runCatching { recorder?.reset() }
            runCatching { recorder?.release() }
        }
        recorder = null
        runCatching { surface?.release() }; surface = null
        runCatching { file?.close() }; file = null
        uri?.let { video ->
            if (saved && (!targetMode || frames > 0)) {
                saved = runCatching { contentResolver.update(video, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) == 1 }.getOrDefault(false)
                if (saved) lastVideo = video.toString()
            } else {
                saved = false
                runCatching { contentResolver.delete(video, null, null) }
            }
        }
        uri = null
        status = if (saved && (!targetMode || frames > 0)) "$message，已保存到相册 Movies/AgentReins" else "$message；没有可保存的视频"
        encoderThread.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { if (!stopping && current === this) finishRecording("录制结束"); super.onDestroy() }

    companion object {
        const val TARGET = "record_target"
        const val SCREEN = "record_screen"
        const val STOP = "record_stop"
        private const val CHANNEL = "video-recording"
        @Volatile private var current: VideoRecordingService? = null
        @Volatile var status: String = "未录制"; private set
        @Volatile var lastVideo: String? = null; private set
        fun isRecording() = current != null
        fun offerTargetFrame(source: Any, bitmap: Bitmap, width: Int, height: Int) { current?.targetFrame(source, bitmap, width, height) }
    }
}
