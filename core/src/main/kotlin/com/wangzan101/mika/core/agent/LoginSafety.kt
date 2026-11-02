package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.tool.ToolExecution

/**
 * 凭据连续失败和生物识别。密码/验证码提交被页面拒绝连续 [MAX_FAILURES] 次就停；
 * 人脸、指纹、摄像头真人验证第一次看到就交给用户，不再点。
 */
object LoginSafety {
    const val MAX_FAILURES = 2
    const val BIOMETRIC_MESSAGE =
        "这一步是人脸、指纹或摄像头真人验证，只能由你本人完成。页面留给你，我不会在这一页继续点。"
    const val BIOMETRIC_NOTE =
        "Biometric or camera liveness was handed to the user. Do not tap this page again. After they hand it back, read the screen once and continue only if the verification is gone."
    const val CREDENTIAL_STOP =
        "同一组密码或验证码已经连续失败 2 次，已停止，避免触发账号风控。可选：1) 把正确的密码或验证码发我；2) 走找回密码；3) 先停在这里。我不会再提交第三次。"

    private val REJECTED = Regex(
        "密码有误|密码错误|密码不正确|验证码错误|验证码不正确|验证码有误|动态码错误|动态码不正确|" +
            "(?i)wrong password|incorrect password|password is incorrect|invalid (?:verification )?code|incorrect code|code is incorrect",
    )
    private val BIOMETRIC = Regex(
        "真人验证|人脸验证|人脸识别|面部识别|面容识别|请验证指纹|验证指纹|指纹验证|摄像头验证|眨眨眼|摇头验证|" +
            "(?i)liveness|face verification|facial recognition",
    )
    private val SUBMIT = setOf(
        "phone_tap", "web_tap", "web_click", "phone_press_key", "web_press_key", "do_steps",
    )

    fun rejected(text: String) = REJECTED.containsMatchIn(text)

    fun biometric(text: String) = BIOMETRIC.containsMatchIn(text)

    fun couldSubmit(tool: String) = tool in SUBMIT

    fun blob(exec: ToolExecution): String = buildString {
        append(exec.content)
        exec.observations.forEach { append('\n'); append(it.text) }
    }
}
