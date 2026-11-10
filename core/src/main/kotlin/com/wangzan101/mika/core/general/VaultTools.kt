package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.vault.VaultAppend
import com.wangzan101.mika.core.vault.VaultOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object MemoryVaultSearch : AgentTool {
    override val verb = Verb.VaultSearch
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef(
        "memory_vault_search",
        Prompts.tool("memory_vault_search"),
        mapOf(
            "query" to str("Words to look for, 1-100 characters."),
            "app" to str("Optional app package, such as com.tencent.mm."),
        ),
        listOf("query"),
    )

    override fun available(ctx: ToolContext) = ctx.config.vault?.searchEnabled() == true

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val q = a.str("query", max = 100)!!
        val app = a.str("app", min = 0, max = 120)
        return try {
            val found = ctx.config.vault!!.search(q, app?.takeIf { it.isNotBlank() }, 4)
            ok {
                put("notes", JsonArray(found.map { h ->
                    buildJsonObject {
                        put("path", h.path)
                        put("title", h.title)
                        put("excerpt", h.excerpt)
                    }
                }))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("vault_unavailable", "Could not read the vault.", recoverable = true)
        }
    }
}

internal object MemoryVaultAppend : AgentTool {
    override val verb = Verb.VaultAppend
    override val risk = Risk.Read
    override val def = ToolDef(
        "memory_vault_append",
        Prompts.tool("memory_vault_append"),
        mapOf(
            "body" to str("The note to append, 1-2000 characters. Do not include secrets."),
            "kind" to str("tip, pitfall, or steps.", listOf("tip", "pitfall", "steps")),
            "topic" to str("Optional short topic used as the file name when no app is given."),
            "app" to str("Optional app package. Notes for the same package share one file."),
        ),
        listOf("body"),
    )

    override fun available(ctx: ToolContext) = ctx.config.vault?.appendEnabled() == true

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val body = a.str("body", max = 2000)!!
        val kind = a.str("kind", min = 0)?.takeIf { it.isNotBlank() } ?: "tip"
        val topic = a.str("topic", min = 0, max = 40)
        val app = a.str("app", min = 0, max = 120)?.takeIf { it.isNotBlank() }
        val lines = body.lines().map { it.removePrefix("-").trim() }.filter { it.isNotEmpty() }
        val outcome = when (kind) {
            "pitfall" -> VaultOutcome.Failure
            "steps" -> VaultOutcome.Success
            "tip" -> VaultOutcome.Note
            else -> return fail("bad_kind", "kind must be tip, pitfall, or steps.", recoverable = false)
        }
        val runId = (ctx.config.runSpec?.id?.value ?: "tool") + "-tool-" + body.hashCode().toUInt().toString(16)
        val request = VaultAppend(
            runId = runId,
            outcome = outcome,
            goal = topic?.ifBlank { null } ?: lines.firstOrNull().orEmpty(),
            appPackage = app,
            topic = topic,
            steps = if (kind == "steps") lines else emptyList(),
            pitfalls = if (kind == "pitfall") lines else emptyList(),
            tips = if (kind == "tip") lines else emptyList(),
        )
        return try {
            val saved = ctx.config.vault!!.append(request)
            if (!saved.ok) return fail(saved.code ?: "not_saved", "Nothing was written to the vault.", recoverable = false)
            ok { put("path", saved.path ?: ""); put("status", saved.code ?: "ok") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("vault_unavailable", "Could not write the vault.", recoverable = true)
        }
    }
}

/** Immutable work parts let an agent save and recover full drafts without replacing user notes. */
internal object VaultWorkWrite : AgentTool {
    override val verb = Verb.VaultAppend
    override val risk = Risk.Write
    override val offScreen = true
    override val def = ToolDef("vault_work_write", Prompts.tool("vault_work_write"),
        mapOf("note_id" to str("Work identifier, 1-64 ASCII letters, digits, underscore or hyphen."),
            "part_id" to str("Part identifier, e.g. 01-introduction; 1-64 ASCII letters, digits, underscore or hyphen."),
            "body" to str("Full text of this part, 1-12000 characters, without secrets.")), listOf("note_id", "part_id", "body"))
    override fun available(ctx: ToolContext) = ctx.config.vault?.appendEnabled() == true
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val id = a.str("note_id", max = 64)!!
        val part = a.str("part_id", max = 64)!!
        val body = a.str("body", max = 12000)!!
        return try {
            val result = withContext(Dispatchers.IO) { ctx.config.vault!!.writeWorkPart(id, part, body) }
            if (!result.ok) fail(result.code ?: "not_saved", "Work part was not saved. Do not assume it exists or overwrite an existing part.", recoverable = true)
            else ok { put("path", result.path ?: ""); put("status", result.code ?: "ok") }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { fail("vault_unavailable", "Could not write the vault.", recoverable = true) }
    }
}

internal object VaultWorkRead : AgentTool {
    override val verb = Verb.VaultSearch
    override val risk = Risk.Read
    override val def = ToolDef("vault_work_read", Prompts.tool("vault_work_read"),
        mapOf("note_id" to str("Work identifier, 1-64 ASCII letters, digits, underscore or hyphen."),
            "part_id" to str("Optional exact part identifier without .md.")), listOf("note_id"))
    override fun available(ctx: ToolContext) = ctx.config.vault?.searchEnabled() == true
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val id = a.str("note_id", max = 64)!!
        val part = a.str("part_id", max = 64)
        return try {
            val vault = ctx.config.vault!!
            if (part == null) {
                val parts = withContext(Dispatchers.IO) { vault.listWorkParts(id) }
                ok { put("parts", JsonArray(parts.map { kotlinx.serialization.json.JsonPrimitive(it.removeSuffix(".md")) })) }
            }
            else {
                val body = withContext(Dispatchers.IO) { vault.readWorkPart(id, part) } ?: return fail("not_found", "Work part not found or unavailable.", recoverable = true)
                ok { put("body", com.wangzan101.mika.core.observation.Untrusted.wrap(body)) }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { fail("vault_unavailable", "Could not read the vault.", recoverable = true) }
    }
}
