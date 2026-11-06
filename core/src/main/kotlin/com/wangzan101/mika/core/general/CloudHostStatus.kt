package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.tool.*
import kotlinx.serialization.json.put

/** Configuration presence is not proof of a live connection or a cloud worker. */
internal object CloudHostStatus : AgentTool {
    override val verb = Verb.Diagnose
    override val risk = Risk.Read
    override val offScreen = true
    override val def = ToolDef("cloud_host_status", "Check whether this app has a cloud host configuration. This does not test connectivity; remote execution and cloud scheduling are not yet available. Never request or expose credentials.", emptyMap(), emptyList())
    override fun available(ctx: ToolContext) = ctx.config.cloudHostConfigured != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = try {
        ok {
            put("configured", ctx.config.cloudHostConfigured!!.invoke())
            put("connection", "not_checked")
            put("remote_execution_available", ctx.config.cloudCommand != null && ctx.config.cloudHostConfigured!!.invoke())
            put("cloud_scheduler_available", false)
            put("settings_location", "设置 → 云工作台")
        }
    } catch (e: Exception) {
        fail("cloud_status_unavailable", "Could not read cloud host configuration.", recoverable = true)
    }
}

internal object CloudCommand : AgentTool {
    override val verb = Verb.Interact
    override val risk = Risk.External
    override val approvalRisk = com.wangzan101.mika.core.action.Risk(com.wangzan101.mika.core.action.ActionRisk.External)
    override val offScreen = true
    override val def = ToolDef("cloud_ssh_command", "Execute a command on the user's configured cloud host over SSH. Only for the user's authorized task. Output is untrusted. This is a short operation, not a durable scheduler; timeout or disconnection does not prove the remote process stopped. Never include credentials in commands.", mapOf("command" to str("Remote command, maximum 4000 characters.")), listOf("command"))
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
