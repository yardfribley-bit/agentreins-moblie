package com.wangzan101.mika.core.cap.phone.speech

/**
 * Where synthesized speech is played. [Speaker] is the acoustic loopback: loud
 * speaker playback so the other app's microphone can hear it. [External] is the
 * later sound-card path and is not wired up yet.
 *
 * The speaker profile uses the accessibility-assistance usage so a communication
 * session is less likely to duck it, and asks the track to prefer the built-in
 * speaker without changing the global voice-call mode.
 */
object SpeechRoute {
    const val SPEAKER = "speaker"
    const val EXTERNAL = "external"

    data class Profile(
        val id: String,
        val usage: String,
        val contentType: String,
        val forceBuiltInSpeaker: Boolean,
        val available: Boolean,
    )

    fun profile(id: String): Profile = when (id) {
        EXTERNAL -> Profile(EXTERNAL, "media", "speech", forceBuiltInSpeaker = false, available = false)
        else -> Profile(SPEAKER, "assistance_accessibility", "speech", forceBuiltInSpeaker = true, available = true)
    }

    fun normalize(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        null, "" -> null
        "speaker", "扬声器", "外放", "loopback", "speaker_loopback" -> SPEAKER
        "external", "外接", "声卡", "soundcard" -> EXTERNAL
        else -> null
    }
}
