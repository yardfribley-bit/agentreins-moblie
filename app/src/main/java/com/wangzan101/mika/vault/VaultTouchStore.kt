package com.wangzan101.mika.vault

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** 每条笔记最近一次被读进任务、被写回的时间,以及对应的运行编号。存在应用私有目录,不改库里的原文。 */
class VaultTouchStore(private val file: File) {
    data class Touch(val readAt: Long? = null, val writeAt: Long? = null, val readRuns: List<String> = emptyList(), val writeRuns: List<String> = emptyList())

    private val lock = Any()
    private var notes = linkedMapOf<String, Touch>()
    private var runs = linkedMapOf<String, String>()

    init { load() }

    fun touch(path: String): Touch = synchronized(lock) { notes[path] ?: Touch() }

    /** 这一次运行读过、写过的笔记路径。 */
    fun forRun(runId: String): Pair<List<String>, List<String>> = synchronized(lock) {
        val read = notes.filter { runId in it.value.readRuns }.keys.toList()
        val wrote = notes.filter { runId in it.value.writeRuns }.keys.toList()
        read to wrote
    }

    fun runPath(runId: String): String? = synchronized(lock) { runs[runId] }

    fun markRead(paths: List<String>, runId: String?, at: Long) = edit(paths, runId, at, write = false)

    fun markWrite(paths: List<String>, runId: String?, at: Long) = edit(paths, runId, at, write = true)

    fun rememberRun(runId: String, path: String) = synchronized(lock) {
        runs[runId] = path
        if (runs.size > 200) runs.keys.take(runs.size - 200).forEach { runs.remove(it) }
        persist()
    }

    private fun edit(paths: List<String>, runId: String?, at: Long, write: Boolean) = synchronized(lock) {
        paths.distinct().forEach { path ->
            val cur = notes[path] ?: Touch()
            notes[path] = if (write) cur.copy(writeAt = at, writeRuns = bump(cur.writeRuns, runId))
            else cur.copy(readAt = at, readRuns = bump(cur.readRuns, runId))
        }
        persist()
    }

    private fun bump(had: List<String>, runId: String?) = (listOfNotNull(runId) + had).distinct().take(12)

    private fun load() {
        val o = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return
        (o["notes"] as? JsonObject)?.forEach { (path, el) ->
            val n = el as? JsonObject ?: return@forEach
            notes[path] = Touch(
                (n["readAt"] as? JsonPrimitive)?.longOrNull,
                (n["writeAt"] as? JsonPrimitive)?.longOrNull,
                strings(n["readRuns"] as? JsonArray),
                strings(n["writeRuns"] as? JsonArray),
            )
        }
        (o["runs"] as? JsonObject)?.forEach { (id, el) ->
            (el as? JsonPrimitive)?.content?.let { runs[id] = it }
        }
    }

    private fun strings(a: JsonArray?) = a?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    private fun persist() {
        file.parentFile?.mkdirs()
        file.writeText(buildJsonObject {
            put("notes", buildJsonObject {
                notes.forEach { (path, t) ->
                    put(path, buildJsonObject {
                        t.readAt?.let { put("readAt", it) }
                        t.writeAt?.let { put("writeAt", it) }
                        put("readRuns", kotlinx.serialization.json.JsonArray(t.readRuns.map { JsonPrimitive(it) }))
                        put("writeRuns", kotlinx.serialization.json.JsonArray(t.writeRuns.map { JsonPrimitive(it) }))
                    })
                }
            })
            put("runs", buildJsonObject { runs.forEach { (id, path) -> put(id, path) } })
        }.toString())
    }
}
