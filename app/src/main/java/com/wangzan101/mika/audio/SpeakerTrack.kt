package com.wangzan101.mika.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.wangzan101.mika.core.cap.phone.speech.SpeechRoute

/**
 * Acoustic loopback. The track prefers the built-in speaker and uses the
 * accessibility-assistance usage so a live app's communication focus is less
 * likely to duck or mute it. This does not change the global audio mode and
 * does not call setSpeakerphoneOn: that would fight the app holding the mic.
 * An external sound-card route can implement the same [write] / [stopNow] pair later.
 */
class SpeakerTrack(context: Context, volume: Float) {
    private val track: AudioTrack
    private var written = 0
    private var startedAt = 0L
    var firstAudioMs: Long? = null
        private set

    init {
        val min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, min * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val manager = context.getSystemService(AudioManager::class.java)
        val speaker = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        if (SpeechRoute.profile(SpeechRoute.SPEAKER).forceBuiltInSpeaker && speaker != null) {
            track.setPreferredDevice(speaker)
        }
        track.setVolume(volume.coerceIn(0f, 1f))
        track.play()
        startedAt = System.currentTimeMillis()
    }

    fun write(pcm: ByteArray) {
        var offset = 0
        while (offset < pcm.size) {
            val n = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            if (firstAudioMs == null) firstAudioMs = System.currentTimeMillis() - startedAt
            offset += n
            written += n
        }
    }

    /** Let the last buffered samples leave the speaker before the echo gate closes. */
    fun drain(aborted: () -> Boolean) {
        val remain = (track.bufferSizeInFrames * 1000L / SAMPLE_RATE).coerceAtMost(1500L)
        val until = System.currentTimeMillis() + remain
        while (!aborted() && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    fun stopNow() {
        runCatching { track.pause() }
        runCatching { track.flush() }
    }

    fun release() {
        stopNow()
        runCatching { track.stop() }
        track.release()
    }

    companion object {
        const val SAMPLE_RATE = 24_000
    }
}
