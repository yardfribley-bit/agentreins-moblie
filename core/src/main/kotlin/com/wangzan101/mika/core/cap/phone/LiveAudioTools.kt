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
