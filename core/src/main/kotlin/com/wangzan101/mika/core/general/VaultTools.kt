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
