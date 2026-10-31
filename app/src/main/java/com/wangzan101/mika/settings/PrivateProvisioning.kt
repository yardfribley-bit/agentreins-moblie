package com.wangzan101.mika.settings

import android.content.Context
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.audio.LiveAudioSettings

/** Seed once, then respect edits/deletion made in Settings. The APK itself still contains its build key. */
object PrivateProvisioning {
    fun install(context: Context, store: ProviderStore) {
        val key = BuildConfig.EMBEDDED_OPENROUTER_KEY
        if (key.isBlank()) return
        val prefs = context.getSharedPreferences("private-provisioning", Context.MODE_PRIVATE)
        if (prefs.getBoolean("openrouter-preset-v1", false)) return
        runCatching {
            OpenRouterDefaults.install(store, key)
            LiveAudioSettings(context).apply {
                endpoint = OpenRouterDefaults.TRANSCRIPTION_URL
                model = OpenRouterDefaults.TRANSCRIPTION_MODEL
                this.key = key
            }
            check(prefs.edit().putBoolean("openrouter-preset-v1", true).commit())
        }.onFailure {
            // Never log exceptions or configuration values that could include credentials.
            Log.w("PrivateProvisioning", "Private model preset could not be saved; configure models in Settings")
        }
    }
}
