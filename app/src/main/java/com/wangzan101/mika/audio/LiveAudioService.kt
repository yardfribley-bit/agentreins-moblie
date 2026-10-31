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
import android.util.Log
import com.wangzan101.mika.R
import com.wangzan101.mika.core.cap.phone.LiveListenPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

/** Capture and transcription run independently so model/network latency doesn't pause recording. */
class LiveAudioService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private val recorderLock = Any()
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var destroying = false
    /** True while a new listen is replacing the recorder, so the old loop must not stop the service. */
    @Volatile private var replacing = false
    private val jobs = mutableListOf<Job>()
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            releaseHardware()
            if (LiveAudioSession.running) LiveAudioSession.end("你已停止监听", LiveAudioWatch.USER_STOPPED)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) return START_NOT_STICKY
        val mic = intent.getBooleanExtra("microphone", false)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("live-audio", "直播监听", NotificationManager.IMPORTANCE_LOW))
        val notification = listeningNotification(if (mic) "麦克风收音 · 点此查看或停止" else "抖音内部音频 · 点此查看或停止")
        try {
            // Prefer to take the microphone type while this start is still in the foreground, so a
            // later silent-internal fallback can open the mic. If the device rejects the combined
            // type, keep the previous projection-only start and try again at fallback time.
            val projectionType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            val micType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            var micForeground = mic
            if (mic) startForeground(7821, notification, micType) else {
                try {
                    startForeground(7821, notification, projectionType or micType)
                    micForeground = true
                } catch (_: RuntimeException) {
                    startForeground(7821, notification, projectionType)
                    micForeground = false
                }
            }
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "未授权录音" }
            replacing = true
            releaseHardware(stopProjection = true)
            val settings = LiveAudioSettings(this)
            val transcriber = LiveTranscriber(settings.endpoint, settings.key, settings.model)
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "设备不支持 16kHz 录音" }
            val record = if (mic) openMicrophone() else openPlayback(intent)
            recorder = record
            val sessionId = LiveAudioSession.begin(mic)
            Log.d(LiveListenPolicy.LOG_TAG, "session=$sessionId source=${if (mic) "mic" else "internal"} decision=start reason=new listen")
            replacing = false
            // Four short chunks bound memory and latency; never send old speech as a live reply.
            val queue = Channel<Pair<Long, ByteArray>>(4)
            jobs += scope.launch {
                try {
                    for ((time, pcm) in queue) {
                        if (System.currentTimeMillis() - time > 30_000) { LiveAudioSession.gap(); continue }
                        try { LiveAudioSession.add(time, transcriber.transcribe(pcm)) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            if (replacing || LiveAudioSession.generation != sessionId) break
                            LiveAudioSession.end(e.message ?: "语音识别失败", LiveAudioWatch.ASR_ERROR); stopSelf(); break
                        }
                    }
                } finally { queue.cancel() }
            }
            jobs += scope.launch {
                val buffer = ShortArray(1600)
                var policy = LiveAudioFallback.State(if (mic) LiveAudioFallback.Source.Microphone else LiveAudioFallback.Source.Internal)
                var active = record
                try {
                    while (isActive && LiveAudioSession.generation == sessionId) {
                        val time = System.currentTimeMillis()
                        val bytes = ByteArrayOutputStream(256000)
                        var peak = 0
                        var sumSquares = 0.0
                        var samples = 0
                        while (bytes.size() < 256000 && isActive && LiveAudioSession.generation == sessionId) {
                            val count = active.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                            check(count > 0) { "音频读取失败 ($count)" }
                            for (i in 0 until count) {
                                val value = buffer[i].toInt(); peak = maxOf(peak, abs(value))
                                sumSquares += value.toDouble() * value.toDouble()
                                samples++
                                bytes.write(value and 255); bytes.write((value shr 8) and 255)
                            }
                        }
                        if (LiveAudioSession.generation != sessionId) break
                        val rms = if (samples == 0) 0 else sqrt(sumSquares / samples).toInt()
                        val decision = LiveAudioFallback.onSegment(policy, peak)
                        Log.d(LiveListenPolicy.LOG_TAG, LiveListenPolicy.segmentLog(sessionId, policy.source, peak, rms, decision))
                        LiveAudioSession.noteLevel(peak, rms)
                        when (decision) {
                            is LiveAudioFallback.Decision.Keep -> policy = decision.state
                            is LiveAudioFallback.Decision.Hear -> {
                                policy = decision.state
                                if (queue.trySend(time to bytes.toByteArray()).isFailure) LiveAudioSession.gap()
                            }
                            is LiveAudioFallback.Decision.UseMicrophone -> {
                                if (!micForeground) {
                                    try {
                                        startForeground(7821, listeningNotification(decision.status), projectionType or micType)
                                        micForeground = true
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) {
                                        LiveAudioSession.end(LiveAudioFallback.INTERNAL_SILENCE, LiveAudioWatch.CAPTURE_ERROR)
                                        stopSelf(); break
                                    }
                                }
                                try { active = replaceWithMicrophone(active) }
                                catch (e: CancellationException) { throw e }
                                catch (_: Exception) {
                                    LiveAudioSession.end(LiveAudioFallback.INTERNAL_SILENCE, LiveAudioWatch.CAPTURE_ERROR)
                                    stopSelf(); break
                                }
                                // This listen only. The saved switch stays what the user chose, so the next listen retries internal capture.
                                runCatching { manager.notify(7821, listeningNotification(decision.status)) }
                                LiveAudioSession.adoptMicrophone(decision.status)
                                policy = decision.state
                            }
                            is LiveAudioFallback.Decision.ReportSilence -> {
                                policy = decision.state
                                LiveAudioSession.error(decision.message)
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (!replacing && LiveAudioSession.generation == sessionId) {
                        LiveAudioSession.end(e.message ?: "音频采集失败", LiveAudioWatch.CAPTURE_ERROR); stopSelf()
                    }
                }
                finally { queue.close() }
            }
            jobs += scope.launch {
                while (isActive && LiveAudioSession.generation == sessionId) {
                    delay(5000)
                    if (LiveAudioSession.applyWatch(System.currentTimeMillis())) { stopSelf(); break }
                }
            }
        } catch (e: Exception) {
            LiveAudioSession.end("监听启动失败：${e.message ?: e.javaClass.simpleName}", LiveAudioWatch.START_FAILED); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun listeningNotification(text: String): Notification {
        val stop = PendingIntent.getService(this, 0, Intent(this, javaClass).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, LiveAudioActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "live-audio").setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("agentreins-moblie 正在监听直播").setContentText(text)
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止监听", stop).build()).build()
    }
    private fun pcmFormat() = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
    private fun pcmBufferBytes(): Int {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return maxOf(minimum * 2, 32000)
    }
    private fun openMicrophone(
        initError: String = "音频采集初始化失败",
        startError: String = "无法开始录音",
    ): AudioRecord {
        val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(pcmFormat()).setBufferSizeInBytes(pcmBufferBytes()).build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { initError }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { startError }
        } catch (e: Exception) {
            runCatching { record.release() }
            throw e
        }
        return record
    }
    private fun openPlayback(intent: Intent): AudioRecord {
        val grant = intent.getParcelableExtra<Intent>("projection") ?: error("需要重新授权音频采集")
        val media = requireNotNull(getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, grant)) { "音频采集授权已失效" }
        projection = media
        media.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { if (!destroying) { LiveAudioSession.end("系统已撤销采集授权", LiveAudioWatch.CAPTURE_REVOKED); stopSelf() } }
        }, Handler(Looper.getMainLooper()))
        val uid = packageManager.getApplicationInfo("com.ss.android.ugc.aweme", 0).uid
        val record = AudioRecord.Builder().setAudioFormat(pcmFormat()).setBufferSizeInBytes(pcmBufferBytes())
            .setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration.Builder(media)
                .addMatchingUid(uid).addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build())
            .build()
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "音频采集初始化失败" }
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "无法开始录音" }
        } catch (e: Exception) {
            runCatching { record.release() }
            throw e
        }
        return record
    }
    /** Drop playback capture and record from the mic. The projection token stays until destroy:
     *  stopping it ends the media-projection foreground service on some OEMs, including Huawei. */
    private fun replaceWithMicrophone(current: AudioRecord): AudioRecord = synchronized(recorderLock) {
        if (destroying) throw CancellationException()
        if (recorder === current) recorder = null
        runCatching { current.stop() }
        current.release()
        val next = openMicrophone("麦克风初始化失败", "无法开始麦克风录音")
        if (destroying) {
            runCatching { next.stop() }; next.release()
            throw CancellationException()
        }
        recorder = next
        next
    }
    private fun releaseHardware(stopProjection: Boolean = false) {
        jobs.forEach { it.cancel() }
        jobs.clear()
        synchronized(recorderLock) {
            recorder?.let { runCatching { it.stop() }; it.release() }
            recorder = null
        }
        if (stopProjection) {
            projection?.stop(); projection = null
        }
    }
    override fun onDestroy() {
        destroying = true
        releaseHardware(stopProjection = true)
        scope.cancel()
        if (LiveAudioSession.running) {
            val message = if (LiveAudioSession.status == "正在监听") "你已停止监听" else LiveAudioSession.status
            LiveAudioSession.end(message, LiveAudioWatch.USER_STOPPED)
        }
        super.onDestroy()
    }
}
