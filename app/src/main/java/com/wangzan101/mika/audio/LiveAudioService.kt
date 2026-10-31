package com.wangzan101.mika.audio

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import com.wangzan101.mika.R
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** Capture and transcription run independently so model/network latency doesn't pause recording. */
class LiveAudioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    @Volatile private var recorder: AudioRecord? = null
    private var started = false
    private var destroying = false
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (started || intent == null) return START_NOT_STICKY
        started = true
        val mic = intent.getBooleanExtra("microphone", false)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("live-audio", "直播监听", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, LiveAudioActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "live-audio").setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("agentreins-moblie 正在监听直播").setContentText(if (mic) "麦克风收音 · 点此查看或停止" else "抖音内部音频 · 点此查看或停止")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止监听", stop).build()).build()
        try {
            startForeground(7821, notification, if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "未授权录音" }
            val settings = LiveAudioSettings(this)
            val transcriber = LiveTranscriber(settings.endpoint, settings.key, settings.model)
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "设备不支持 16kHz 录音" }
            val builder = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(maxOf(minimum * 2, 32000))
            if (mic) builder.setAudioSource(MediaRecorder.AudioSource.MIC) else {
                val grant = intent.getParcelableExtra<Intent>("projection") ?: error("需要重新授权音频采集")
                val media = requireNotNull(getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, grant)) { "音频采集授权已失效" }
                projection = media
                media.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { if (!destroying) { LiveAudioSession.end("系统已撤销采集授权"); stopSelf() } }
                }, Handler(Looper.getMainLooper()))
                val uid = packageManager.getApplicationInfo("com.ss.android.ugc.aweme", 0).uid
                builder.setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration.Builder(media)
                    .addMatchingUid(uid).addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build())
            }
            val record = builder.build().also { recorder = it }
            check(record.state == AudioRecord.STATE_INITIALIZED) { "音频采集初始化失败" }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法开始录音" }
            LiveAudioSession.begin(mic)
            // Four short chunks bound memory and latency; never send old speech as a live reply.
            val queue = Channel<Pair<Long, ByteArray>>(4)
            scope.launch {
                try {
                    for ((time, pcm) in queue) {
                        if (System.currentTimeMillis() - time > 30_000) { LiveAudioSession.gap(); continue }
                        try { LiveAudioSession.add(time, transcriber.transcribe(pcm)) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { LiveAudioSession.error(e.message ?: "语音识别失败"); stopSelf(); break }
                    }
                } finally { queue.cancel() }
            }
            scope.launch {
                val buffer = ShortArray(1600)
                var silent = 0
                try {
                    while (isActive) {
                        val time = System.currentTimeMillis()
                        val bytes = ByteArrayOutputStream(256000)
                        var peak = 0
                        while (bytes.size() < 256000 && isActive) {
                            val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                            check(count > 0) { "音频读取失败 ($count)" }
                            for (i in 0 until count) {
                                val value = buffer[i].toInt(); peak = maxOf(peak, abs(value))
                                bytes.write(value and 255); bytes.write((value shr 8) and 255)
                            }
                        }
                        if (peak < 120) {
                            silent++
                            if (silent >= 3) LiveAudioSession.error(if (mic) "未检测到声音，请检查外放音量和麦克风" else "未采集到抖音声音：请关闭 App 静音；若仍无声，停止后改用麦克风外放收音")
                        } else {
                            silent = 0
                            if (queue.trySend(time to bytes.toByteArray()).isFailure) LiveAudioSession.gap()
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { LiveAudioSession.error(e.message ?: "音频采集失败"); stopSelf() }
                finally { queue.close() }
            }
            scope.launch {
                val start = System.currentTimeMillis()
                while (isActive) {
                    delay(5000)
                    val now = System.currentTimeMillis()
                    if (now - start > 3_600_000 || now - LiveAudioSession.lastRead > 120_000) {
                        LiveAudioSession.end("监听已结束：达到一小时或两分钟无任务读取"); stopSelf(); break
                    }
                }
            }
        } catch (e: Exception) {
            LiveAudioSession.end("监听启动失败：${e.message ?: e.javaClass.simpleName}"); stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        destroying = true
        scope.cancel()
        recorder?.let { runCatching { it.stop() }; it.release() }; recorder = null
        projection?.stop(); projection = null
        if (LiveAudioSession.running) LiveAudioSession.end(if (LiveAudioSession.status == "正在监听") "监听已停止" else LiveAudioSession.status)
        super.onDestroy()
    }
}
