package com.wangzan101.mika.core.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 有的兼容接口（DeepSeek flash 一类）不走 `tool_calls`，把调用写进正文：
 * `<｜DSML｜invoke name="...">`。这里把它收成真正的工具调用，避免把标记原样交给用户。
 * 竖线用全角 `｜`（U+FF5C），一条或两条都认。
 */
object EmbeddedToolCalls {
    private val INVOKE = Regex(
        """<\uFF5C+DSML\uFF5C+\s*invoke\b([^>]*)>(.*?)</\uFF5C+DSML\uFF5C+\s*invoke>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val PARAM = Regex(
        """<\uFF5C+DSML\uFF5C+\s*parameter\b([^>]*)>(.*?)</\uFF5C+DSML\uFF5C+\s*parameter>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val NAME = Regex("""\bname\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val MARKUP = Regex("""</?\uFF5C+DSML\uFF5C+[^>]*>""", RegexOption.IGNORE_CASE)
    private val ALIASES = mapOf(
        "memory_add" to "memory_save",
        "memory_remember" to "memory_save",
    )

    /** 没有这类标记时原样返回。 */
    fun lift(reply: ModelReply): ModelReply {
        val text = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        if (!INVOKE.containsMatchIn(text)) return reply
        val calls = INVOKE.findAll(text).mapIndexedNotNull { i, m -> call(i, m) }.toList()
        if (calls.isEmpty()) return reply
        val kept = reply.parts.mapNotNull { part ->
            if (part !is Part.Text) part
            else {
                val stripped = MARKUP.replace(part.text, "").trim()
                stripped.takeIf { it.isNotEmpty() }?.let { Part.Text(it) }
            }
        }
        val stop = if (reply.stopReason == StopReason.EndTurn || reply.stopReason == StopReason.Other) StopReason.ToolUse else reply.stopReason
        return reply.copy(parts = kept + calls, stopReason = stop)
    }

    private fun call(index: Int, invoke: MatchResult): Part.ToolCall? {
        val name = NAME.find(invoke.groupValues[1])?.groupValues?.get(1)?.trim().orEmpty()
        if (name.isEmpty()) return null
        val args = buildJsonObject {
            PARAM.findAll(invoke.groupValues[2]).forEach { p ->
                val key = NAME.find(p.groupValues[1])?.groupValues?.get(1)?.trim().orEmpty()
                if (key.isNotEmpty()) put(key, JsonPrimitive(p.groupValues[2].trim()))
            }
        }
        val tool = ALIASES[name] ?: name
        val withQuote = if (tool == "memory_save" && "quote" !in args) {
            buildJsonObject {
                args.forEach { (k, v) -> put(k, v) }
                put("quote", FROM_REMEMBER)
            }
        } else args
        return Part.ToolCall("dsml-${index + 1}", tool, withQuote.toString())
    }

    /** memory_add 没有 quote 时填上，由 memory_save 换成用户这次说的「记住」。 */
    const val FROM_REMEMBER = "__from_remember_request__"
}
