package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.*
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.UUID

/** Configuration presence is not proof of a live connection or a cloud worker. */
internal object CloudHostStatus : AgentTool {
    override val verb = Verb.Diagnose
    override val risk = Risk.Read
    override val offScreen = true
    override val def = ToolDef("cloud_host_status", Prompts.tool("cloud_host_status"), emptyMap(), emptyList())
    override fun available(ctx: ToolContext) = ctx.config.cloudHostConfigured != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = try {
        ok {
            put("configured", ctx.config.cloudHostConfigured!!.invoke())
            put("connection", "not_checked")
            put("remote_execution_available", ctx.config.cloudCommand != null && ctx.config.cloudHostConfigured!!.invoke())
            put("cloud_background_jobs_available", ctx.config.cloudCommand != null && ctx.config.cloudHostConfigured!!.invoke())
            put("cloud_scheduler_available", false)
            put("settings_location", "设置 → 云工作台")
        }
    } catch (e: Exception) {
        fail("cloud_status_unavailable", "Could not read cloud host configuration.", recoverable = true)
    }
}

/** A detached, one-shot job on a POSIX host. It does not survive a server reboot. */
internal object CloudBackgroundJob : AgentTool {
    override val verb = Verb.Interact
    override val risk = Risk.External
    override val approvalRisk = com.wangzan101.mika.core.action.Risk(com.wangzan101.mika.core.action.ActionRisk.External)
    override val offScreen = true
    override val def = ToolDef("cloud_background_job", Prompts.tool("cloud_background_job"), mapOf("operation" to str("start or status"), "command" to str("Shell command for start, maximum 4000 characters"), "job_id" to str("Job ID returned by start, for status")), listOf("operation"))
    override fun available(ctx: ToolContext) = ctx.config.cloudCommand != null && ctx.config.cloudHostConfigured?.invoke() == true
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val op = a.str("operation")!!
        val id = when (op) {
            "start" -> UUID.randomUUID().toString()
            "status" -> a.str("job_id", max = 36)?.takeIf { it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) }
                ?: return fail("invalid_job_id", "Use the job_id returned by start.")
            else -> return fail("invalid_operation", "Use start or status.")
        }
        val remote = if (op == "start") {
            val command = a.str("command", max = 4000) ?: return fail("missing_command", "A command is required to start a job.")
            startCommand(id, command)
        } else statusCommand(id)
        return try {
            val r = ctx.config.cloudCommand!!.invoke(remote)
            ok {
                put("job_id", id)
                put("operation", op)
                put("output", com.wangzan101.mika.core.observation.Untrusted.wrap(r.output))
                r.exitCode?.let { put("ssh_exit_code", it) }
                put("timed_out", r.timedOut)
                put("truncated", r.truncated)
                put("survives_server_reboot", false)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            // Return the identifier even when SSH drops after the remote launch.
            ok { put("job_id", id); put("operation", op); put("outcome", "unknown"); put("message", "Connection lost; inspect this job_id before retrying.") }
        }
    }

    internal fun startCommand(id: String, command: String): String {
        val encoded = Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_8))
        return """umask 077; d="${'$'}HOME/.agentreins/jobs/$id"; mkdir -p "${'$'}HOME/.agentreins/jobs" && mkdir "${'$'}d" || exit 1
printf '%s' '$encoded' | base64 -d > "${'$'}d/command.sh" || exit 1
printf 'starting\n' > "${'$'}d/state"
nohup sh -c 'd=${'$'}1; printf "running\\n" > "${'$'}d/state"; sh "${'$'}d/command.sh" > "${'$'}d/output" 2>&1; code=${'$'}?; printf "%s\\n" "${'$'}code" > "${'$'}d/exit_code"; printf "finished\\n" > "${'$'}d/state"' sh "${'$'}d" </dev/null >/dev/null 2>&1 &
printf 'submitted\n'"""
    }

    internal fun statusCommand(id: String): String = """d="${'$'}HOME/.agentreins/jobs/$id"; if [ ! -d "${'$'}d" ]; then echo 'job_not_found'; exit 2; fi
printf 'state='; cat "${'$'}d/state" 2>/dev/null || echo unknown
if [ -f "${'$'}d/exit_code" ]; then printf 'exit_code='; cat "${'$'}d/exit_code"; fi
echo 'output_tail:'; tail -c 12000 "${'$'}d/output" 2>/dev/null || true"""
}

internal object CloudCommand : AgentTool {
    override val verb = Verb.Interact
    override val risk = Risk.External
    override val approvalRisk = com.wangzan101.mika.core.action.Risk(com.wangzan101.mika.core.action.ActionRisk.External)
    override val offScreen = true
    override val def = ToolDef("cloud_ssh_command", Prompts.tool("cloud_ssh_command"), mapOf("command" to str("Remote command, maximum 4000 characters.")), listOf("command"))
    override fun available(ctx: ToolContext) = ctx.config.cloudCommand != null && ctx.config.cloudHostConfigured?.invoke() == true
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val command = a.str("command", max = 4000)!!
        return try {
            val r = ctx.config.cloudCommand!!.invoke(command)
            ok {
                put("output", com.wangzan101.mika.core.observation.Untrusted.wrap(r.output))
                r.exitCode?.let { put("exit_code", it) }
                put("timed_out", r.timedOut)
                put("truncated", r.truncated)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { fail("cloud_command_failed", "SSH command failed or connection was lost; remote outcome is unknown. Check host configuration and connection before retrying.", recoverable = true) }
    }
}
