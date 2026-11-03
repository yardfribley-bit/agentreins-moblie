package com.wangzan101.mika.core.cap.phone.speech

/**
 * Process-wide speaking clock the listener consults. Audio playback sets it;
 * transcription reads it. Defaults are "not speaking", so a listen-only session is unchanged.
 */
object SpeechFloor {
    @Volatile var speaking: Boolean = false
        private set
    @Volatile var speakStartMs: Long = 0L
        private set
    @Volatile var speakEndMs: Long = 0L
        private set
    @Volatile var script: String = ""
        private set
    @Volatile var seatHeld: Boolean = false
    @Volatile var silentStreak: Int = 0
    @Volatile var lastPeak: Int = 0
    @Volatile var captureIsMic: Boolean = false
    /** Platform told us this capture is receiving silence (AudioRecord isClientSilenced). */
    @Volatile var clientSilenced: Boolean = false

    fun beginUtterance(text: String, now: Long) {
        script = text
        speakStartMs = now
        speakEndMs = 0L
        speaking = true
    }

    fun endUtterance(now: Long) {
        speaking = false
        speakEndMs = now
    }

    fun clearUtterance() {
        speaking = false
        speakStartMs = 0L
        speakEndMs = 0L
        script = ""
    }

    fun inWindow(segmentStart: Long, now: Long): Boolean =
        EchoGate.overlaps(segmentStart, now, speakStartMs, speakEndMs, speaking)

    fun classify(text: String, segmentStart: Long, now: Long, path: CapturePath): Heard =
        EchoGate.interpret(text, script, inWindow(segmentStart, now), path)

    fun path(): CapturePath = when {
        !captureIsMic && (seatHeld || speaking || speakStartMs > 0L) -> CapturePath.Playback
        captureIsMic -> CapturePath.Microphone
        else -> CapturePath.None
    }
}

/** Listener and player signals. The app sets these; tests leave them null. */
object SpeechControl {
    @Volatile var onOwnerStop: (() -> Unit)? = null
    @Volatile var onYield: (() -> Unit)? = null
}
