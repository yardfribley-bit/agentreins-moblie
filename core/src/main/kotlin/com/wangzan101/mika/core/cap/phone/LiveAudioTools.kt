package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.*
import kotlinx.serialization.json.*

internal object ListenLive : PhoneTool {
    override val verb = Verb.ListenLive
    override val risk = Risk.Read
    override val offScreen = true
    override val sandboxCallable = false
    override val yieldsToSteer = true
    override val entry = Entry.Opens
    override val label = "听直播"
    override val def = ToolDef("phone_listen_live", Prompts.tool("phone_listen_live"), mapOf(
        "waitSeconds" to int("Wait up to this many seconds for new speech; default 20.", 1, 30),
        "stop" to bool("Stop this task's listening session when done. Default false."),
    ))
    override fun available(ctx: ToolContext) = ctx.phone.liveAudio != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val phone = ctx.phone
        val port = phone.liveAudio!!
        if (a.bool("stop", false)) {
            val stopped = port.stop()
            return ToolExecution(buildJsonObject { put("ok", stopped); put("status", if (stopped) "stopped" else "busy") })
        }
        val result = port.read(phone.audioCursor, a.int("waitSeconds", 1, 30) ?: 20)
        result["cursor"]?.jsonPrimitive?.longOrNull?.let { phone.audioCursor = it }
        return ToolExecution(result)
    }
}

/** Save the transcription URL, model and key. The result never contains the key. */
internal object ConfigureLiveAudio : PhoneTool {
    override val verb = Verb.ConfigureLiveAudio
    override val risk = Risk.Write
    override val offScreen = true
    override val sandboxCallable = false
    override val entry = Entry.Alone
    override val label = "配置直播语音识别"
    override val def = ToolDef("phone_configure_live_audio", Prompts.tool("phone_configure_live_audio"), mapOf(
        "preset" to str("openrouter, openai, or custom. openrouter and openai fill the transcription URL and model.", listOf("openrouter", "openai", "custom")),
        "endpoint" to str("Full HTTPS transcription URL. Optional when preset is openrouter or openai. Never put the API key here."),
        "model" to str("Transcription model id, such as openai/whisper-large-v3 or whisper-1."),
        "key" to str("API key. Never repeat this value in your reply, in reason, or in any other field. OpenRouter keys start with sk-or-."),
        "paste" to str("One pasted block containing a URL, model, and/or key. The key is stored and not echoed back."),
        "microphone" to bool("Use the microphone instead of internal audio. Omit to leave the switch unchanged."),
    ))

    override fun available(ctx: ToolContext) = ctx.phone.liveAudioSetup != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val setup = ctx.phone.liveAudioSetup!!
        val request = AsrSetupRequest(
            preset = a.str("preset", max = 32),
            endpoint = a.str("endpoint", max = 2000),
            model = a.str("model", max = 200),
            key = a.str("key", max = 2000),
            paste = a.str("paste", max = 8000),
            microphone = if ("microphone" in a.o) a.bool("microphone", false) else null,
        )
        val changing = request.preset != null || request.endpoint != null || request.model != null ||
            request.key != null || request.paste != null || request.microphone != null
        val applied = if (changing) setup.apply(request) else AsrApplyResult(true, null, null, setup.view())
        return ToolExecution(applied.toContent(changing))
    }
}

private fun AsrApplyResult.toContent(changed: Boolean) = buildJsonObject {
    put("ok", ok)
    code?.let { put("error", it) }
    val note = message ?: if (!ok) null else if (changed)
        "Saved. Capture has not started. Ask the user to open 设置 → 干活时 → 直播监听 and tap 开始监听. Do not repeat the API key."
    else "Current transcription settings. The API key is not included. Do not repeat a key the user sent."
    note?.let { put("message", it) }
    put("endpoint", view.endpoint)
    put("model", view.model)
    put("preset", view.preset)
    put("keySet", view.keySet)
    put("keyHint", view.keyHint)
    put("microphone", view.microphone)
    view.warning?.let { put("warning", it) }
    put("listeningStarted", false)
}
