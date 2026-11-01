package com.wangzan101.mika.core.relay

import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 一条运行事件写成 Hub 上的一句。进度是 `progress`,跑完和最终判定是 `result`。
 * 同一 [runId] 和 [index] 永远得到同一个 `request_id` 和同一段正文,重试才能幂等。
 * 正文再过一遍 [Redactor],不把密钥原文带出去。
 */
object RelayEventText {
    fun requestId(runId: String, index: Int): String = "$runId:$index"

    fun map(runId: String, index: Int, event: RunEvent): RelayMessage? {
        val built = body(event) ?: return null
        val text = clip(Redactor.outbound(built.second))
        if (text.isBlank()) return null
        return RelayMessage(requestId(runId, index), built.first, text)
    }

    fun clip(s: String, max: Int = RELAY_MAX_TEXT): String {
        if (s.length <= max) return s
        if (max <= 1) return s.take(max)
        return s.take(max - 1) + "…"
    }

    private fun progress(text: String) = RelayKind.PROGRESS to text
    private fun result(text: String) = RelayKind.RESULT to text

    private fun body(event: RunEvent): Pair<String, String>? = when (event) {
        is RunEvent.TaskStarted -> progress("开始：${short(event.goal)}（${event.model}）")
        is RunEvent.Criteria -> {
            val line = event.criteria.joinToString("；")
            if (line.isBlank()) null else progress("完成标准：${short(line)}")
        }
        is RunEvent.ModelReply -> modelReply(event)
        is RunEvent.ToolResult -> progress("第 ${event.step} 步 ${event.tool} 结果 ${summarize(event.resultJson)}")
        is RunEvent.Activity -> progress(buildString {
            append(event.label)
            event.target?.takeIf { it.isNotBlank() }?.let { append(" ").append(short(it, 120)) }
            event.effect?.takeIf { it.isNotBlank() }?.let { append(" · ").append(short(it, 120)) }
            if (event.failed) append(" · 失败")
        })
        is RunEvent.Replay -> progress("回放 ${event.step}/${event.total} ${short(event.detail, 160)}")
        is RunEvent.Batch -> progress(buildString {
            append("一批动作 ${event.step}/${event.total}")
            if (event.stopped) append(" 停下")
            event.reason?.takeIf { it.isNotBlank() }?.let { append(" ").append(short(it, 120)) }
        })
        is RunEvent.Media -> progress("画面 #${event.mediaId}")
        is RunEvent.Observed -> progress("读到：${short(Redactor.evidence(event.text), 160)}")
        is RunEvent.State -> progress("${event.name} ${short(event.detail, 200)}".trim())
        is RunEvent.Confirmation -> progress(buildString {
            append("确认 ").append(event.action)
            event.decision?.let { append(" → ").append(it) }
            event.target?.takeIf { it.isNotBlank() }?.let { append(" ").append(short(it, 80)) }
        })
        is RunEvent.Approval -> progress("审批 ${event.tool} ${event.action} ${event.decision}")
        is RunEvent.Takeover -> progress("接管 ${short(event.reason, 120)} ${event.outcome.orEmpty()}".trim())
        is RunEvent.Barrier -> progress("卡住 ${event.kind}")
        is RunEvent.AnswerCheck -> progress("核对 ${event.claims} 处，未对上 ${event.unmatched.size} 处")
        is RunEvent.Unconfirmed -> {
            val line = event.items.joinToString("；")
            if (line.isBlank()) null else progress("未确认：${short(line, 200)}")
        }
        is RunEvent.Sources -> progress("来源 ${event.items.size} 条")
        is RunEvent.Verifier -> progress("核查 ${event.verdict} ${short(event.detailJson, 160)}".trim())
        is RunEvent.MemoryUsed -> progress("用了记忆 ${event.ids.size} 条")
        is RunEvent.MemorySaved -> progress("记下一条 ${event.kind} ${event.status}")
        is RunEvent.SkillUsed -> progress("用了技能 ${event.name}")
        is RunEvent.ModelCalls -> progress("模型调用 ${event.calls} 次")
        is RunEvent.TaskEnded -> result(buildString {
            append("结束：").append(event.outcome)
            if (!event.kind.isNullOrBlank()) append("（").append(event.kind).append("）")
            if (event.answerOrReason.isNotBlank()) {
                append('\n')
                append(short(event.answerOrReason, 4000))
            }
        })
        is RunEvent.OutcomeJudged -> result("判定：${event.outcome} ${short(event.reason, 300)}".trim())
        is RunEvent.PromptVersion, is RunEvent.ActionTarget, is RunEvent.Compressed -> null
    }

    private fun modelReply(event: RunEvent.ModelReply): Pair<String, String>? {
        if (!event.acted) return null
        val said = short(event.text, 200)
        val tool = event.tool?.takeIf { it.isNotBlank() }
        if (tool == null && said.isBlank()) return null
        return progress(buildString {
            append("第 ${event.step} 步")
            if (said.isNotBlank()) append(" ").append(said)
            if (tool != null) {
                append(" → ").append(tool)
                val args = event.argsJson?.takeIf { it.isNotBlank() && it != "{}" }?.let(Redactor::toolArgs)
                if (args != null) append(" ").append(short(args, 160))
            }
        })
    }

    /** 工具结果只留成没成和一句说明,不把整段 JSON 贴出去。 */
    private fun summarize(json: String): String {
        val redacted = Redactor.text(json)
        val obj = runCatching { Json.parseToJsonElement(redacted) as? JsonObject }.getOrNull()
            ?: return short(redacted, 240)
        val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull
        val error = (obj["error"] as? JsonPrimitive)?.contentOrNull
        val message = (obj["message"] as? JsonPrimitive)?.contentOrNull
        val bits = buildList {
            if (ok != null) add(if (ok) "ok" else "失败")
            if (!error.isNullOrBlank()) add(error)
            if (!message.isNullOrBlank()) add(short(message, 160))
        }
        return if (bits.isEmpty()) short(redacted, 240) else bits.joinToString(" ")
    }

    private fun short(s: String, max: Int = 300): String = clip(s, max)
}
