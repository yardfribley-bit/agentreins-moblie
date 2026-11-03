package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class WebSearchTest {
    @Test fun unwrapsSearchRedirectAndRejectsUnsafeUrls() {
        assertEquals("https://example.org/article?q=a", WebSearch.resultUrl("https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Farticle%3Fq%3Da"))
        assertNull(WebSearch.resultUrl("https://duckduckgo.com/settings"))
        assertNull(WebSearch.resultUrl("javascript:alert(1)"))
        assertNull(WebSearch.resultUrl("http://127.0.0.1/private"))
    }

    @Test fun searchesInBackgroundAndReportsActualResults() = runTest {
        val web = FakeWebPort().apply { page = page.copy(text = "Result snippet", links = listOf("Example" to "https://example.org/article")) }
        val dispatcher = toolDispatcher(FakeScreen(), web = web)
        val r = dispatcher.execute(Part.ToolCall("s", "web_search", """{"query":"AI agent security"}""")).content
        assertEquals("true", r["ok"]!!.jsonPrimitive.content)
        assertTrue(r.toString().contains("https://example.org/article"))
        assertTrue(web.calls.any { it.contains("duckduckgo.com/html/?q=AI+agent+security") })
    }

    @Test fun reportsBlockedPageInsteadOfInventingResults() = runTest {
        val web = FakeWebPort().apply { readUrlError = "net::ERR_TIMED_OUT" }
        val r = toolDispatcher(FakeScreen(), web = web).execute(Part.ToolCall("s", "web_search", """{"query":"X"}""")).content
        assertEquals("false", r["ok"]!!.jsonPrimitive.content)
        assertEquals("search_unavailable", r["error"]!!.jsonPrimitive.content)
    }
}
