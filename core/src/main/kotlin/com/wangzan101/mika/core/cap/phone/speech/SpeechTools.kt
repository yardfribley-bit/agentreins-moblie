package com.wangzan101.mika.core.cap.phone.speech

import com.wangzan101.mika.core.cap.phone.PhoneTool
import com.wangzan101.mika.core.cap.phone.phone
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Entry
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.bool
import com.wangzan101.mika.core.tool.num
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object SpeakLive : PhoneTool {
    override val verb = Verb.Speak
    override val risk = Risk.Write
    override val offScreen = true
    override val sandboxCallable = false
    override val yieldsToSteer = true
    override val entry = Entry.Opens
    override val label = "出声说话"
    override val def = ToolDef("phone_speak", Prompts.tool("phone_speak"), mapOf(
        "text" to str("What to say aloud, in the language the room should hear. Omit when stop is true."),
        "stop" to bool("Stop speaking immediately. Default false. Does not release the mic seat; the one-tap 正在出声 control does."),
    ))

    override fun available(ctx: ToolContext) = ctx.phone.speech != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx.phone.speech!!
        if (a.bool("stop", false)) return ToolExecution(port.stop(releaseMic = false))
        val text = a.str("text", max = 2000) ?: throw ArgError("text", "required unless stop is true")
        return ToolExecution(port.speak(text))
    }
}

internal object TakeMic : PhoneTool {
    override val verb = Verb.TakeMic
    override val risk = Risk.Write
    override val offScreen = true
    override val sandboxCallable = false
    override val yieldsToSteer = true
    override val entry = Entry.Opens
    override val label = "申请上麦"
    override val def = ToolDef("phone_take_mic", Prompts.tool("phone_take_mic"), mapOf(
        "seated" to bool("True only after the screen shows this phone is on a mic seat. Omit to ask the owner first."),
    ))

    override fun available(ctx: ToolContext) = ctx.phone.micSeat != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val port = ctx.phone.micSeat!!
        val result = if (a.bool("seated", false)) port.markHeld() else port.request()
        return ToolExecution(result)
    }
}

internal object LeaveMic : PhoneTool {
    override val verb = Verb.LeaveMic
    override val risk = Risk.Write
    override val offScreen = true
    override val sandboxCallable = false
    override val entry = Entry.Opens
    override val label = "释放麦位"
    override val def = ToolDef("phone_leave_mic", Prompts.tool("phone_leave_mic"), emptyMap())

    override fun available(ctx: ToolContext) = ctx.phone.micSeat != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution =
        ToolExecution(ctx.phone.micSeat!!.release())
}

internal object ConfigureSpeech : PhoneTool {
    override val verb = Verb.ConfigureSpeech
    override val risk = Risk.Write
    override val offScreen = true
    override val sandboxCallable = false
    override val entry = Entry.Alone
    override val label = "配置出声"
    override val def = ToolDef("phone_configure_speech", Prompts.tool("phone_configure_speech"), mapOf(
        "preset" to str("openrouter, openai, openai-fast, system, or custom.", listOf("openrouter", "openai", "openai-fast", "system", "custom")),
        "endpoint" to str("Full HTTPS speech URL, OpenAI-compatible /audio/speech. Never put the API key here."),
        "model" to str("Speech model id, such as openai/gpt-4o-mini-tts-2025-12-15, gpt-4o-mini-tts, or tts-1."),
        "voice" to str("Voice name. mini-tts: alloy, ash, ballad, coral, echo, fable, nova, onyx, sage, shimmer, verse. tts-1: alloy, echo, fable, onyx, nova, shimmer."),
        "key" to str("API key. Never repeat this value. OpenRouter keys start with sk-or-."),
        "paste" to str("One pasted block containing a URL, model, voice, and/or key. The key is stored and not echoed."),
        "rate" to num("Speaking rate from 0.5 to 2. 1 is normal.", 0.5, 2.0),
        "volume" to num("Playback gain from 0 to 1. 1 is full for the speaker loopback.", 0.0, 1.0),
        "route" to str("speaker (acoustic loopback) or external (sound card, not connected yet).", listOf("speaker", "external")),
        "provider" to str("online or system.", listOf("online", "system")),
    ))

    override fun available(ctx: ToolContext) = ctx.phone.speechSetup != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val setup = ctx.phone.speechSetup!!
        val request = SpeechSetupRequest(
            preset = a.str("preset", max = 32),
            endpoint = a.str("endpoint", max = 2000),
            model = a.str("model", max = 200),
            voice = a.str("voice", max = 80),
            key = a.str("key", max = 2000),
            paste = a.str("paste", max = 8000),
            rate = a.number("rate")?.toFloat(),
            volume = a.number("volume")?.toFloat(),
            route = a.str("route", max = 32),
            provider = a.str("provider", max = 32),
        )
        val changing = listOf(request.preset, request.endpoint, request.model, request.voice, request.key, request.paste, request.route, request.provider).any { it != null } ||
            request.rate != null || request.volume != null
        val applied = if (changing) setup.apply(request) else SpeechApplyResult(true, null, null, setup.view())
        return ToolExecution(applied.toContent(changing))
    }
}

private fun SpeechApplyResult.toContent(changed: Boolean) = buildJsonObject {
    put("ok", ok)
    code?.let { put("error", it) }
    val note = message ?: if (!ok) null else if (changed)
        "Saved. 允许出声 stays under the owner's control in 设置 → 干活时 → 直播出声 and is not changed here. Do not repeat the API key."
    else "Current speech settings. The API key is not included. 允许出声 is only changed by the owner in Settings."
    note?.let { put("message", it) }
    put("allow", view.allow)
    put("provider", view.provider)
    put("endpoint", view.endpoint)
    put("model", view.model)
    put("voice", view.voice)
    put("preset", view.preset)
    put("keySet", view.keySet)
    put("keyHint", view.keyHint)
    put("rate", view.rate)
    put("volume", view.volume)
    put("route", view.route)
    put("routeAvailable", view.routeAvailable)
    view.warning?.let { put("warning", it) }
    put("allowChanged", false)
}
