package com.wangzan101.mika.core.cap.phone.speech

import kotlinx.serialization.json.JsonObject

/** Plays model replies. The implementation owns the speaker and the speaking indicator. */
interface SpeechPort {
    suspend fun speak(text: String): JsonObject
    /** [releaseMic] is true for the one-tap stop. A voice or text stop leaves the seat. */
    fun stop(releaseMic: Boolean): JsonObject
    fun status(): JsonObject
}

/** Consent and seat state. Screen taps are not done here. */
interface MicSeatPort {
    suspend fun request(): JsonObject
    fun markHeld(): JsonObject
    fun release(): JsonObject
    fun view(): JsonObject
}
