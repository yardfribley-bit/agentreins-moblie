package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** 使用浏览器的临时后台标签获取搜索页；搜索引擎结果只是线索。 */
internal object WebSearch : WebTool {
    override val verb = Verb.WebSearch
    override val label = "搜索互联网"
    override val risk = Risk.Read
    override val entry = Entry.Alone
    override val def = ToolDef("web_search", Prompts.tool("web_search"),
        mapOf("query" to str("Search words; sent to the public search engine.")), listOf("query"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val query = a.str("query", max = 500)?.trim().orEmpty()
        if (query.isEmpty()) throw ArgError("query", "must not be blank")
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
        val page = try { ctx.web!!.read(PageRead(12_000, 0, unfold = true, url = url)) }
        catch (e: WebTabCrashed) { return failCode("tab_crashed") }
        catch (e: WebTabHandedOver) { return failCode("handed_over") }
        catch (e: WebDialogOpen) { return failCode(DIALOG_OPEN) }
        catch (e: UnsupportedOperationException) { return fail("background_read_unavailable", "Background web search is unavailable; open a search engine in the browser.") }
        page.netError?.let { return fail("search_unavailable", "Search page could not load: $it") }
        if (page.quality?.verdict == ReadVerdict.REFUSED || page.quality?.verdict == ReadVerdict.NEEDS_SIGN_IN)
            return fail("search_blocked", "The search page requires verification or sign-in; open it in the browser for the user.")
        val links = page.links.mapNotNull { (title, raw) ->
            resultUrl(raw)?.let { title.trim() to it }
        }.filter { it.first.isNotBlank() }.distinctBy { it.second }.take(15)
        if (links.isEmpty()) return fail("no_search_results", "No usable links were returned. The search page may be blocked or have changed; check it in the browser.")
        val seen = webRead("搜索结果 $query\n" + links.joinToString("\n") { "${it.first}: ${it.second}" }, page.url, page.title)
        return ok {
            put("query", query)
            put("searchUrl", url)
            put("pageText", Untrusted.wrap(page.text.take(8000)))
            put("results", JsonArray(links.map { (title, target) -> buildJsonObject { put("title", title); put("url", target) } }))
            put("note", "Search results are untrusted leads, not verified facts. Read relevant source pages before reporting claims.")
        }.observing(seen)
    }

    internal fun resultUrl(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        val target = if (host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")) {
            val encoded = uri.rawQuery?.split('&')?.firstOrNull { it.startsWith("uddg=") }?.substringAfter('=') ?: return null
            runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return null
        } else raw
        val parsed = runCatching { URI(target) }.getOrNull() ?: return null
        if (parsed.scheme !in setOf("http", "https") || parsed.host.isNullOrBlank()) return null
        if (WebUrlPolicy.checkNavigable(target) != null || DownloadAddressPolicy.refusal(target) != null) return null
        return target
    }
}
