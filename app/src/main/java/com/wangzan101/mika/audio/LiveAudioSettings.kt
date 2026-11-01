package com.wangzan101.mika.audio

import android.content.Context
import com.wangzan101.mika.core.cap.phone.AsrApplyResult
import com.wangzan101.mika.core.cap.phone.AsrConfig
import com.wangzan101.mika.core.cap.phone.AsrPublic
import com.wangzan101.mika.core.cap.phone.AsrResolve
import com.wangzan101.mika.core.cap.phone.AsrSetupRequest
import com.wangzan101.mika.core.cap.phone.AsrStored
import com.wangzan101.mika.core.cap.phone.LiveAudioSetup
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

    fun snapshot(): AsrStored = AsrStored(endpoint, model, key, microphone)

    /**
     * Write the fields the screen or the agent just confirmed.
     * [key] null leaves the stored key untouched, so a blank password field that the user never edited
     * cannot wipe a key. A blank string clears it.
     */
    fun save(endpoint: String, model: String, microphone: Boolean, key: String?, commit: Boolean) {
        val editor = prefs.edit()
            .putString("endpoint", endpoint.trim())
            .putString("model", model.trim())
            .putBoolean("microphone", microphone)
        if (key != null) editor.putString("key", encode(key))
        if (commit) check(editor.commit()) else editor.apply()
    }

    private fun encode(value: String): String? =
        if (value.isBlank()) null else Base64.getEncoder().encodeToString(cipher.encrypt(value.trim().toByteArray(Charsets.UTF_8)))
}

/** Agent-facing store. Results go through [AsrConfig.publish], which omits the key. */
class LiveAudioSetupStore(context: Context) : LiveAudioSetup {
    private val settings = LiveAudioSettings(context.applicationContext)
    override fun view(): AsrPublic = AsrConfig.publish(settings.snapshot())
    override fun apply(request: AsrSetupRequest): AsrApplyResult {
        val current = settings.snapshot()
        return when (val resolved = AsrConfig.resolve(current, request)) {
            is AsrResolve.Failed -> AsrApplyResult(false, resolved.code, resolved.message, AsrConfig.publish(current))
            is AsrResolve.Ready -> {
                val saved = runCatching {
                    settings.save(resolved.stored.endpoint, resolved.stored.model, resolved.stored.microphone, resolved.stored.key, commit = true)
                }
                if (saved.isFailure) AsrApplyResult(false, "save_failed", "Could not save the transcription settings on this phone.", AsrConfig.publish(current))
                else AsrApplyResult(true, null, null, AsrConfig.publish(resolved.stored))
            }
        }
    }
}
