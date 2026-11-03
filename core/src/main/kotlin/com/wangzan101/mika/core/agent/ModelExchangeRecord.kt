package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.vault.VaultRedactor

/** 面向本机任务记录的脱敏摘要，不包含图片字节、认证头和模型私有字段。 */
internal object ModelExchangeRecord {
    private const val LIMIT = 24000

    fun request(system: String, messages: List<Message>, tools: List<ToolSpec>, index: ToolIndex): String {
        val history = buildString { messages.forEach { message ->
            append('[').append(message.role.name).append("]\n")
            message.parts.forEach { append(part(it, index)).append('\n') }
            append('\n')
        } }
        val definitions = buildString { tools.forEach {
            append(it.name).append(": ").append(it.description).append("\n").append(it.parametersJsonSchema).append('\n')
        } }
        return "[system]\n${section(system, 20000)}\n\n[messages]\n${section(history, 60000, tail = true)}\n\n[tools]\n${section(definitions, 20000)}"
    }

    fun response(reply: ModelReply, index: ToolIndex): String = bounded(buildString {
        reply.parts.forEach { append(part(it, index)).append('\n') }
        append("stop: ").append(reply.stopReason.name)
    })

    private fun part(part: Part, index: ToolIndex): String = when (part) {
        is Part.Text -> part.text
        is Part.Image -> "[image ${part.mime}, ${part.width}x${part.height}; bytes omitted]"
        is Part.ToolCall -> "[tool ${part.name}] " + Redactor.args(index.verb(part.name), part.argsJson, secretField = true)
        is Part.ToolResult -> "[tool result ${part.callId}] " + (Redactor.network(body = part.content).body ?: "")
        is Part.Thinking -> "[provider-private reasoning omitted]"
    }

    private fun bounded(text: String): String {
        return section(text, LIMIT)
    }

    private fun section(text: String, limit: Int, tail: Boolean = false): String {
        val clean = VaultRedactor.redact(text)
        if (clean.length <= limit) return clean
        return if (tail) "[earlier content truncated]\n" + clean.takeLast(limit) else clean.take(limit) + "\n[truncated]"
    }
}
