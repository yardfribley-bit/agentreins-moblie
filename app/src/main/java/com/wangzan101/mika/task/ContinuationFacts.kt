package com.wangzan101.mika.task

import com.wangzan101.mika.core.record.RunEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Redacted event evidence only; never replay an old action or assert its final effect. */
internal fun continuationFactsFrom(events: List<RunEvent>): List<String> {
    val facts = mutableListOf<String>()
    val results = events.filterIsInstance<RunEvent.ToolResult>().associateBy { it.step }
    events.filterIsInstance<RunEvent.ModelReply>().filter { it.tool == "vault_work_write" }.forEach { call ->
        val result = results[call.step] ?: return@forEach
        val ok = runCatching { Json.parseToJsonElement(result.resultJson).jsonObject["ok"]?.jsonPrimitive?.content == "true" }.getOrDefault(false)
        val args = runCatching { Json.parseToJsonElement(call.argsJson.orEmpty()).jsonObject }.getOrNull()
        val note = args?.get("note_id")?.jsonPrimitive?.content
        val part = args?.get("part_id")?.jsonPrimitive?.content
        if (ok && note != null && part != null) facts += "Obsidian work part saved: note_id=$note, part_id=$part. Read it with vault_work_read before continuing."
    }
    val steps = events.filterIsInstance<RunEvent.Activity>().takeLast(12).joinToString("; ") {
        "${it.label}${it.target?.let { target -> " ($target)" }.orEmpty()}${if (it.failed) " failed" else ""}"
    }
    if (steps.isNotBlank()) facts += "Recent recorded actions (not proof of final completion): $steps. Recheck the current app and saved draft before continuing; do not repeat external actions without verification."
    return facts.takeLast(3)
}
