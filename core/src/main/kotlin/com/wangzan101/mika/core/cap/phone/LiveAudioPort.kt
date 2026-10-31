package com.wangzan101.mika.core.cap.phone

import kotlinx.serialization.json.JsonObject

/** User-authorized, continuously captured audio. Each task has its own cursor. */
interface LiveAudioPort {
    suspend fun read(after: Long, waitSeconds: Int): JsonObject
    fun stop(): Boolean
}
