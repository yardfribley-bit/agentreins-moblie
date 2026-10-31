package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.observation.Untrusted
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一轮里值得交给下一轮的工具结果:讲话、读到的正文、监听状态。
 * 界面元素表不算(过时,而且下一轮会重读)。从最新的结果往回取,讲话优先于只有状态的轮询。
 */
internal object TurnFacts {
    const val KEPT_FACT = "keptFact"
    const val QUOTE_CHARS = 80
    private const val ITEM_CHARS = 480
    private const val MAX_SPEECH = 3
    private const val MIN_CHARS = 4
    private val SPEECH_KEYS = listOf("text", "transcript", "speech", "summary", "output", "content")
    private val PLACEHOLDERS = listOf(HistoryPruner.OMITTED, HistoryPruner.OMITTED_READ, HistoryPruner.OMITTED_IMAGE)

    /** 本轮历史里要带去下一轮的几条,按时间从早到晚。 */
    fun fromHistory(messages: List<Message>): List<String> {
        val names = HashMap<String, String>()
        messages.forEach { m -> m.parts.filterIsInstance<Part.ToolCall>().forEach { names[it.id] = it.name } }
        val speech = ArrayList<String>()
        val status = ArrayList<String>()
        for (m in messages.asReversed()) {
            for (r in m.parts.filterIsInstance<Part.ToolResult>().asReversed()) {
                val obj = parse(r.content) ?: continue
                if ("elements" in obj && speechOf(obj) == null) continue
                val name = names[r.callId] ?: continue
                val said = speechOf(obj)
                if (said != null) {
                    val line = clip("$name: $said", ITEM_CHARS)
                    if (line !in speech) speech += line
                } else {
                    statusOf(obj)?.let { s ->
                        val line = clip("$name: $s", ITEM_CHARS)
                        if (line !in status) status += line
                    }
                }
            }
        }
        return speech.take(MAX_SPEECH).asReversed() + status.take(1)
    }

    /**
     * 折叠或缩短大结果时留下的一句原文。界面占位、空话不要。
     * 已经缩短过的结果读 [KEPT_FACT]。
     */
    fun quote(obj: JsonObject, max: Int = QUOTE_CHARS): String? {
        val speech = speechOf(obj) ?: usable(string(obj[KEPT_FACT]))
        return speech?.let { clip(it, max) }
    }

    private fun speechOf(obj: JsonObject): String? {
        val parts = ArrayList<String>()
        val segments = obj["segments"] as? JsonArray
        if (segments != null) {
            val texts = segments.mapNotNull { (it as? JsonObject)?.let { o -> usable(string(o["text"])) } }
            if (texts.isNotEmpty()) parts += texts.joinToString(" ")
        }
        for (k in SPEECH_KEYS) usable(string(obj[k]))?.let { parts += it }
        return parts.distinct().joinToString(" ").trim().takeIf { it.length >= MIN_CHARS }
    }

    private fun statusOf(obj: JsonObject): String? {
        val status = usable(string(obj["status"]))?.takeUnless { it in STATUS_NOISE }
        val message = usable(string(obj["message"]))
        val reason = usable(string(obj["reason"]))
        val bits = listOfNotNull(status?.let { "status=$it" }, message, reason?.takeIf { message == null }?.let { "reason=$it" })
        return bits.joinToString("; ").takeIf { it.length >= MIN_CHARS }
    }

    private fun usable(raw: String?): String? {
        val t = raw?.let(::unwrap)?.trim()?.takeIf { it.length >= MIN_CHARS } ?: return null
        if (t in PLACEHOLDERS || PLACEHOLDERS.any { t.startsWith(it) }) return null
        return t
    }

    private fun unwrap(s: String): String {
        var t = s.trim()
        if (t.startsWith(Untrusted.OPEN)) t = t.removePrefix(Untrusted.OPEN).removeSuffix(Untrusted.CLOSE).trim()
        return t.replace("<\\/untrusted>", Untrusted.CLOSE)
    }

    private fun string(v: kotlinx.serialization.json.JsonElement?) = (v as? JsonPrimitive)?.contentOrNull

    private fun clip(s: String, max: Int): String {
        val one = s.replace('\n', ' ').trim()
        return if (one.length <= max) one else one.take(max) + "…"
    }

    private fun parse(json: String): JsonObject? = try {
        Json.parseToJsonElement(json.ifBlank { "{}" }) as? JsonObject
    } catch (e: SerializationException) {
        null
    }

    private val STATUS_NOISE = setOf("ok", "true", "false", "applied")
}
