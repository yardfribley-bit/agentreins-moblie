package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.EvidenceLog
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.tool.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

internal object DownloadUrl : AgentTool {
    override val verb = Verb.DownloadUrl
    override val label = "下载链接"
    override val risk = Risk.Write
    override val offScreen = true
    override val def = ToolDef(
        "download_url_to_workspace",
        Prompts.tool("download_url_to_workspace"),
        mapOf("url" to str("https URL of the image or file, at most 2000 characters.")),
        listOf("url"),
    )

    override fun available(ctx: ToolContext) = ctx.config.urlDownload != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val url = a.str("url", max = 2000)!!.trim()
        val port = ctx.config.urlDownload ?: return fail("download_unavailable", "Downloading URLs is not available.", recoverable = false)
        val saved = withContext(Dispatchers.IO) { port.download(url) }
        if (!saved.ok) return fail("download_failed", saved.message, recoverable = true)
        return ToolExecution(okObj {
            put("saved", true)
            put("name", saved.name)
            put("bytes", saved.bytes)
            put("mime", saved.mime)
            put("workspace", saved.workspacePath)
            put("gallery", saved.gallery ?: "none")
            put("message", saved.message)
        }, changed = true)
    }

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        val path = (r.content["workspace"] as? JsonPrimitive)?.content ?: return emptyList()
        val message = (r.content["message"] as? JsonPrimitive)?.content ?: path
        return listOf(Observation(ObsKind.Attachment, message, source = "core", place = Place.file(path)))
    }
}
