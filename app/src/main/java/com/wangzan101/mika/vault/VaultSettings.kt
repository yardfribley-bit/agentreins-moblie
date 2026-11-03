package com.wangzan101.mika.vault

import com.wangzan101.mika.core.vault.VaultPaths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** 库文件夹和三个开关。没选文件夹时开关保持关。 */
data class VaultPrefs(
    val treeUri: String? = null,
    val displayName: String? = null,
    val subfolder: String = VaultPaths.DEFAULT_ROOT,
    val readEnabled: Boolean = false,
    val writeEnabled: Boolean = false,
    val logEnabled: Boolean = false,
    val lastReadAt: Long? = null,
    val lastReadOk: Boolean? = null,
    val lastReadDetail: String? = null,
    val lastWriteAt: Long? = null,
    val lastWriteOk: Boolean? = null,
    val lastWriteDetail: String? = null,
)

class VaultSettings(private val file: File) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<VaultPrefs> = _state.asStateFlow()

    @Synchronized fun update(transform: (VaultPrefs) -> VaultPrefs) {
        val next = normalize(transform(_state.value))
        file.parentFile?.mkdirs()
        file.writeText(json(next))
        _state.value = next
    }

    /** 第一次选中文件夹时打开阅读、写回和运行日志。 */
    fun chooseFolder(uri: String, name: String) = update { cur ->
        val first = cur.treeUri == null
        cur.copy(
            treeUri = uri,
            displayName = name,
            readEnabled = if (first) true else cur.readEnabled,
            writeEnabled = if (first) true else cur.writeEnabled,
            logEnabled = if (first) true else cur.logEnabled,
        )
    }

    fun markRead(ok: Boolean, detail: String) = update {
        it.copy(lastReadAt = System.currentTimeMillis(), lastReadOk = ok, lastReadDetail = detail.take(80))
    }

    fun markWrite(ok: Boolean, detail: String) = update {
        it.copy(lastWriteAt = System.currentTimeMillis(), lastWriteOk = ok, lastWriteDetail = detail.take(120))
    }

    private fun normalize(p: VaultPrefs): VaultPrefs {
        val folder = VaultPaths.normalizeRoot(p.subfolder)
        if (p.treeUri.isNullOrBlank()) return p.copy(treeUri = null, subfolder = folder, readEnabled = false, writeEnabled = false, logEnabled = false)
        return p.copy(subfolder = folder)
    }

    private fun read(): VaultPrefs {
        val o = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return VaultPrefs()
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        fun b(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull
        fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
        return normalize(VaultPrefs(
            treeUri = s("treeUri"),
            displayName = s("displayName"),
            subfolder = s("subfolder") ?: VaultPaths.DEFAULT_ROOT,
            readEnabled = b("readEnabled") ?: false,
            writeEnabled = b("writeEnabled") ?: false,
            logEnabled = b("logEnabled") ?: false,
            lastReadAt = l("lastReadAt"),
            lastReadOk = b("lastReadOk"),
            lastReadDetail = s("lastReadDetail"),
            lastWriteAt = l("lastWriteAt"),
            lastWriteOk = b("lastWriteOk"),
            lastWriteDetail = s("lastWriteDetail"),
        ))
    }

    private fun json(p: VaultPrefs) = buildJsonObject {
        p.treeUri?.let { put("treeUri", it) }
        p.displayName?.let { put("displayName", it) }
        put("subfolder", p.subfolder)
        put("readEnabled", p.readEnabled)
        put("writeEnabled", p.writeEnabled)
        put("logEnabled", p.logEnabled)
        p.lastReadAt?.let { put("lastReadAt", it) }
        p.lastReadOk?.let { put("lastReadOk", it) }
        p.lastReadDetail?.let { put("lastReadDetail", it) }
        p.lastWriteAt?.let { put("lastWriteAt", it) }
        p.lastWriteOk?.let { put("lastWriteOk", it) }
        p.lastWriteDetail?.let { put("lastWriteDetail", it) }
    }.toString()
}
