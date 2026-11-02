package com.wangzan101.mika.audio

import android.content.Context
import com.wangzan101.mika.core.cap.phone.speech.SpeechApplyResult
import com.wangzan101.mika.core.cap.phone.speech.SpeechConfig
import com.wangzan101.mika.core.cap.phone.speech.SpeechPresets
import com.wangzan101.mika.core.cap.phone.speech.SpeechPublic
import com.wangzan101.mika.core.cap.phone.speech.SpeechResolve
import com.wangzan101.mika.core.cap.phone.speech.SpeechRoute
import com.wangzan101.mika.core.cap.phone.speech.SpeechSetup
import com.wangzan101.mika.core.cap.phone.speech.SpeechSetupRequest
import com.wangzan101.mika.core.cap.phone.speech.SpeechStored
import com.wangzan101.mika.settings.KeystoreKeyCipher
import java.util.Base64

/** Speech endpoint separate from transcription. The key is encrypted and never logged. */
class SpeechSettings(context: Context) {
    private val prefs = context.getSharedPreferences("live-speech", Context.MODE_PRIVATE)
    private val cipher = KeystoreKeyCipher("mika-live-speech-key")

    fun snapshot(): SpeechStored = SpeechStored(
        allow = prefs.getBoolean("allow", false),
        provider = prefs.getString("provider", "online") ?: "online",
        endpoint = prefs.getString("endpoint", SpeechPresets.openRouter.endpoint) ?: SpeechPresets.openRouter.endpoint,
        model = prefs.getString("model", SpeechPresets.openRouter.model) ?: SpeechPresets.openRouter.model,
        voice = prefs.getString("voice", SpeechPresets.openRouter.voice) ?: SpeechPresets.openRouter.voice,
        key = decode(),
        rate = prefs.getFloat("rate", 1f),
        volume = prefs.getFloat("volume", 1f),
        route = prefs.getString("route", SpeechRoute.SPEAKER) ?: SpeechRoute.SPEAKER,
    )

    /** [key] null leaves the stored key. A blank string clears it. [allow] is only written from the settings screen. */
    fun save(stored: SpeechStored, key: String?, commit: Boolean) {
        val editor = prefs.edit()
            .putBoolean("allow", stored.allow)
            .putString("provider", stored.provider)
            .putString("endpoint", stored.endpoint.trim())
            .putString("model", stored.model.trim())
            .putString("voice", stored.voice.trim())
            .putFloat("rate", stored.rate)
            .putFloat("volume", stored.volume)
            .putString("route", stored.route)
        if (key != null) editor.putString("key", encode(key))
        if (commit) check(editor.commit()) else editor.apply()
    }

    private fun decode(): String = runCatching {
        prefs.getString("key", null)?.let { String(cipher.decrypt(Base64.getDecoder().decode(it)), Charsets.UTF_8) }
    }.getOrNull().orEmpty()

    private fun encode(value: String): String? =
        if (value.isBlank()) null else Base64.getEncoder().encodeToString(cipher.encrypt(value.trim().toByteArray(Charsets.UTF_8)))
}

class SpeechSetupStore(context: Context) : SpeechSetup {
    private val settings = SpeechSettings(context.applicationContext)
    override fun view(): SpeechPublic = SpeechConfig.publish(settings.snapshot())
    override fun apply(request: SpeechSetupRequest): SpeechApplyResult {
        val current = settings.snapshot()
        return when (val resolved = SpeechConfig.resolve(current, request)) {
            is SpeechResolve.Failed -> SpeechApplyResult(false, resolved.code, resolved.message, SpeechConfig.publish(current))
            is SpeechResolve.Ready -> {
                val saved = runCatching { settings.save(resolved.stored, resolved.stored.key, commit = true) }
                if (saved.isFailure) SpeechApplyResult(false, "save_failed", "Could not save the speech settings on this phone.", SpeechConfig.publish(current))
                else SpeechApplyResult(true, null, null, SpeechConfig.publish(resolved.stored))
            }
        }
    }
}
