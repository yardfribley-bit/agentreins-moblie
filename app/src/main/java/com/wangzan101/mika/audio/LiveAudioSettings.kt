package com.wangzan101.mika.audio

import android.content.Context
import com.wangzan101.mika.settings.KeystoreKeyCipher
import java.util.Base64

/** Separate transcription endpoint: a chat-model key does not imply audio support. */
class LiveAudioSettings(context: Context) {
    private val prefs = context.getSharedPreferences("live-audio", Context.MODE_PRIVATE)
    private val cipher = KeystoreKeyCipher("mika-live-audio-key")
    var endpoint: String
        get() = prefs.getString("endpoint", "https://api.openai.com/v1/audio/transcriptions")!!
        set(value) { prefs.edit().putString("endpoint", value).apply() }
    var model: String
        get() = prefs.getString("model", "whisper-1")!!
        set(value) { prefs.edit().putString("model", value).apply() }
    var microphone: Boolean
        get() = prefs.getBoolean("microphone", false)
        set(value) { prefs.edit().putBoolean("microphone", value).apply() }
    var key: String
        get() = runCatching {
            prefs.getString("key", null)?.let { String(cipher.decrypt(Base64.getDecoder().decode(it))) }
        }.getOrNull().orEmpty()
        set(value) {
            val encoded = if (value.isBlank()) null else Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray()))
            prefs.edit().putString("key", encoded).apply()
        }
}
