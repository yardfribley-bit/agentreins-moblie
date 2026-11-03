package com.wangzan101.mika.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Notification actions for stop and for the mic-seat confirmation. Not exported. */
class SpeechControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            STOP -> SpeechRuntime.tapStop()
            ALLOW -> MicConsent.answer(true)
            DENY -> MicConsent.answer(false)
        }
    }

    companion object {
        const val STOP = "com.wangzan101.mika.speech.STOP"
        const val ALLOW = "com.wangzan101.mika.speech.ALLOW"
        const val DENY = "com.wangzan101.mika.speech.DENY"
    }
}
