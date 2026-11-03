package com.wangzan101.mika.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.experience.ExperienceDistiller
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.vault.VaultAppend
import com.wangzan101.mika.core.vault.VaultAppendResult
import com.wangzan101.mika.core.vault.VaultHit
import com.wangzan101.mika.core.vault.VaultLessons
import com.wangzan101.mika.core.vault.VaultLibrary
import com.wangzan101.mika.core.vault.VaultMemory
import com.wangzan101.mika.core.vault.VaultNote
import com.wangzan101.mika.core.vault.VaultOutcome
import com.wangzan101.mika.core.vault.VaultPaths
import com.wangzan101.mika.core.vault.VaultRunSpec
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 界面上一条库笔记。 */
data class VaultNoteRow(
    val path: String,
    val title: String,
    val group: String,
    val readAt: Long?,
    val writeAt: Long?,
    val readRuns: List<String>,
    val writeRuns: List<String>,
)

/**
 * 手机上的 Obsidian 库。阅读和写回跟应用里原有的记忆分开:没选文件夹时什么都不做。
 */
class VaultRuntime(context: Context) : VaultMemory {
    private val app = context.applicationContext
    val settings = VaultSettings(File(app.filesDir, "vault_settings.json"))
    private val touches = VaultTouchStore(File(app.filesDir, "vault_touches.json"))
    private val files = SafVaultFiles(app) { settings.state.value.treeUri?.let(Uri::parse) }
    private val library = VaultLibrary(files, { settings.state.value.subfolder })
    private val reads = ConcurrentHashMap<String, List<String>>()
    private val toolWrites = ConcurrentHashMap<String, MutableList<String>>()

    fun persisted(uri: Uri = Uri.parse(settings.state.value.treeUri ?: return false)): Boolean =
        app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }

    fun choose(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.takePersistableUriPermission(uri, flags)
        settings.chooseFolder(uri.toString(), displayName(uri))
    }

    override fun searchEnabled() = configured() && settings.state.value.readEnabled
    override fun appendEnabled() = configured() && settings.state.value.writeEnabled

    override fun contextFor(goal: String, runId: String?): String? {
        if (!searchEnabled()) return null
        return try {
            val read = library.readContext(goal)
            if (runId != null) reads[runId] = read.paths
            touches.markRead(read.paths, runId, System.currentTimeMillis())
            settings.markRead(true, read.paths.size.toString())
            read.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            settings.markRead(false, "permission"); null
        } catch (e: Exception) {
            settings.markRead(false, short(e)); null
        }
    }

    override fun search(query: String, appPackage: String?, limit: Int): List<VaultHit> {
        if (!searchEnabled()) return emptyList()
        return try {
            library.search(query, appPackage, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun append(request: VaultAppend): VaultAppendResult {
        if (!appendEnabled()) return VaultAppendResult(false, code = "disabled")
        return writeAppend(request)
    }

    fun probe(): VaultAppendResult {
        if (!configured()) return VaultAppendResult(false, code = "no_folder", detail = "no_folder")
        val id = "probe-" + System.currentTimeMillis()
        return try {
            val result = library.append(VaultAppend(id, VaultOutcome.Note, "测试读写", topic = "测试读写", tips = listOf("读写正常")))
            val text = result.path?.let { files.read(it) }
            val ok = result.ok && text?.contains("run:$id") == true
            settings.markRead(ok, if (ok) "1" else "0")
            settings.markWrite(ok, result.path ?: result.code.orEmpty())
            if (ok) result else VaultAppendResult(false, result.path, "mismatch")
        } catch (e: SecurityException) {
            settings.markWrite(false, "permission")
            VaultAppendResult(false, code = "permission", detail = "permission")
        } catch (e: Exception) {
            settings.markWrite(false, short(e))
            VaultAppendResult(false, code = "error", detail = short(e))
        }
    }

    /**
     * 一次运行结束。成功(以及标成失败的坑)在写回开关开着时追加做法;
     * 运行日志开关开着时,不论成败都写一条能在 Obsidian 里打开的记录。
     */
    fun finish(runId: String, goal: String, source: String, end: TaskState, events: List<RunEvent>, tools: ToolIndex) {
        if (!configured()) return
        val prefs = settings.state.value
        val outcome = when (end) {
            is TaskState.Succeeded -> VaultOutcome.Success
            is TaskState.Failed -> VaultOutcome.Failure
            else -> VaultOutcome.Stopped
        }
        val written = toolWrites.remove(runId)?.toMutableList() ?: mutableListOf()
        if (prefs.writeEnabled && outcome != VaultOutcome.Stopped) {
            val draft = runCatching { ExperienceDistiller.distill(goal, events, tools) }.getOrNull()
            val lesson = VaultLessons.from(goal, runId, end, draft)
            if (lesson != null) {
                val saved = writeAppend(lesson)
                if (saved.ok && saved.path != null) written += saved.path
            }
        }
        if (!prefs.logEnabled) return
        try {
            val started = events.minOfOrNull { it.atMs } ?: System.currentTimeMillis()
            val ended = events.maxOfOrNull { it.atMs } ?: started
            val spec = VaultRunSpec(runId, goal, source, outcome, events, reads.remove(runId).orEmpty(), written.distinct(), started, ended)
            val saved = library.writeRun(spec)
            if (saved.path != null && saved.code != "capped") touches.rememberRun(runId, saved.path)
            settings.markWrite(saved.ok, saved.path ?: saved.code.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            settings.markWrite(false, "permission")
        } catch (e: Exception) {
            settings.markWrite(false, short(e))
        }
    }

    fun browse(query: String): List<VaultNoteRow> {
        if (!configured()) return emptyList()
        val q = query.trim()
        val notes = runCatching { library.notes() }.getOrDefault(emptyList()).filter { !it.probe }
        val rows = notes.map { row(it, groupOf(it.path)) }
        val runs = recentRuns()
        return (rows + runs).filter { q.isBlank() || q.lowercase() in (it.title + it.path).lowercase() }
    }

    fun readNote(path: String): String? = if (!configured()) null else runCatching { files.read(path) }.getOrNull()

    fun runPath(runId: String): String? = touches.runPath(runId)

    fun touch(path: String) = touches.touch(path)

    private fun writeAppend(request: VaultAppend): VaultAppendResult = try {
        val saved = library.append(request)
        val base = request.runId.substringBefore("-tool-")
        if (saved.ok && saved.path != null) {
            toolWrites.getOrPut(base) { mutableListOf() }.add(saved.path)
            touches.markWrite(listOf(saved.path), base, System.currentTimeMillis())
            settings.markWrite(true, saved.path)
        } else if (!saved.ok) {
            settings.markWrite(false, saved.code ?: "error")
        }
        saved
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        settings.markWrite(false, "permission")
        VaultAppendResult(false, code = "permission")
    } catch (e: Exception) {
        settings.markWrite(false, short(e))
        VaultAppendResult(false, code = "error", detail = short(e))
    }

    private fun recentRuns(): List<VaultNoteRow> {
        val root = VaultPaths.normalizeRoot(settings.state.value.subfolder)
        val dir = VaultPaths.runsDir(root)
        val days = runCatching { files.listNames(dir) }.getOrDefault(emptyList()).filter { it.endsWith(".md") }.map { it.removeSuffix(".md") }.sortedDescending().take(7)
        return days.flatMap { day ->
            val folder = "$dir/$day"
            runCatching { files.listNames(folder) }.getOrDefault(emptyList()).filter { it.endsWith(".md") }.sortedDescending().take(8).map { name ->
                val path = "$folder/$name"
                val title = name.removeSuffix(".md")
                val t = touches.touch(path)
                VaultNoteRow(path, "$day $title", "运行", t.readAt, t.writeAt ?: 0L, t.readRuns, t.writeRuns)
            }
        }
    }

    private fun row(note: VaultNote, group: String): VaultNoteRow {
        val t = touches.touch(note.path)
        return VaultNoteRow(note.path, note.title, group, t.readAt, t.writeAt, t.readRuns, t.writeRuns)
    }

    private fun groupOf(path: String) = when {
        "/wiki/apps/" in path -> "应用"
        "/wiki/tasks/" in path -> "任务"
        VaultPaths.isRunLog(path) -> "运行"
        else -> "其他"
    }

    private fun configured(): Boolean {
        val uri = settings.state.value.treeUri ?: return false
        return persisted(Uri.parse(uri))
    }

    private fun displayName(uri: Uri): String {
        val doc = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, android.provider.DocumentsContract.getTreeDocumentId(uri))
        app.contentResolver.query(doc, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "库"
        }
        return "库"
    }

    private fun short(e: Exception) = (e.message ?: e.javaClass.simpleName).take(80)
}
