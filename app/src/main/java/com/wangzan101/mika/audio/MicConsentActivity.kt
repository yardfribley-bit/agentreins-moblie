package com.wangzan101.mika.audio

import android.app.Activity
import android.os.Bundle

/** On-device confirm for taking a mic seat. The notification actions do the same thing if this cannot come forward. */
class MicConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.app.AlertDialog.Builder(this)
            .setTitle("允许申请连麦？")
            .setMessage("助手将在当前直播间寻找「连麦」或「上麦」并出声。可以拒绝。停止出声时会同时释放麦位。")
            .setPositiveButton("同意") { _, _ -> MicConsent.answer(true); finish() }
            .setNegativeButton("拒绝") { _, _ -> MicConsent.answer(false); finish() }
            .setOnCancelListener { MicConsent.answer(false); finish() }
            .show()
    }
}
