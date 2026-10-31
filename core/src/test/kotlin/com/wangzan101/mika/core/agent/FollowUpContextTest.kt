package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.verify.Grounding
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 追问要能用上一轮的工具原文。最终回答没复述的事实,下一轮仍要看得到。
 */
class FollowUpContextTest {
    private fun text(turns: List<Turn>, next: String? = null) =
        (Conversation.context(turns, next = next)!!.parts.single() as Part.Text).text

    @Test fun findingsAndFollowUpStayInTheNextPrompt() {
        val prior = Turn(
            "我已经进了直播间,监听开着",
            "没听到",
            "succeeded",
            findings = listOf("phone_listen_live: 主播刚才说价格是 39 元", "phone_listen_live: status=listening"),
        )
        val sent = text(listOf(prior), next = "他们刚才说的价格是多少")
        assertTrue(sent, "我已经进了直播间,监听开着" in sent)
        assertTrue(sent, "39 元" in sent)
        assertTrue(sent, "status=listening" in sent)
        assertTrue(sent, Conversation.FOLLOW_UP in sent)
        assertTrue(sent, "trust the tool result" in sent)
        // 新任务里的「这个」不是承接
        val fresh = text(listOf(prior), next = "这个月的账单帮我看看")
        assertFalse(Conversation.FOLLOW_UP in fresh)
        assertFalse(Conversation.continues("这个月的账单"))
        assertTrue(Conversation.continues("keep listening"))
    }

    @Test fun carriedSpeechCountsAsSomethingRead() {
        val screens = Conversation.evidence(listOf(
            Turn("听直播", "没听到", "succeeded", findings = listOf("web_read_page: 主播刚才说价格是 39 元")),
        ))
        val answer = "主播刚才说「价格是 39 元」"
        assertEquals(true, Grounding.check("他们刚才说的价格是多少", answer, screens).found)
        assertEquals(false, Grounding.check("他们刚才说的价格是多少", answer, emptyList()).found)
    }

    @Test fun toolSpeechIsKeptAndScreenDumpsAreNot() {
        val speech = """{"ok":true,"status":"listening","segments":[{"text":"${Untrusted.wrap("主播刚才说价格是 39 元")}"}]}"""
        val screen = """{"ok":true,"elements":[{"text":"弹幕"}]}"""
        val quiet = """{"ok":true,"status":"listening","message":"正在监听","segments":[]}"""
        val history = listOf(Message(Role.User, listOf(Part.Text("听")), Author.User)) +
            round("1", "phone_observe", screen) +
            round("2", "phone_listen_live", speech) +
            (3..8).flatMap { round("$it", "phone_listen_live", quiet) }
        val facts = TurnFacts.fromHistory(history)
        assertTrue(facts.toString(), facts.any { "39 元" in it && "<untrusted>" !in it })
        assertFalse(facts.any { "弹幕" in it })
        assertEquals(1, facts.count { "status=listening" in it })
    }

    @Test fun foldedSummaryKeepsTheSpokenFact() {
        val head = listOf(Message(Role.User, listOf(Part.Text("听直播")), Author.User))
        val heard = round("c0", "phone_listen_live", """{"ok":true,"text":"${Untrusted.wrap("主播刚才说价格是 39 元")}"}""")
        val taps = (1..15).flatMap { round("t$it", "phone_tap", """{"ok":true}""") }
        val summary = ContextCompressor(TOOLS).compress(head + heard + taps, contextTokens = 100).messages
            .map { it.plainText() }.first { it.startsWith(ContextCompressor.HEADER) }
        assertTrue(summary, "39 元" in summary)
        assertTrue(ContextCompressor.SUMMARY_REMINDER in summary)
        assertFalse("for reference only" in summary)
    }

    @Test fun followUpRunSeesThePreviousToolResult() = runTest {
        val web = FakeWebPort().apply { page = page.copy(text = "主播刚才说价格是 39 元") }
        val first = FakeModel(reply(Part.ToolCall("r1", "web_read_page", "{}")), answerReply("没听到"))
        val loop = AgentLoop(first, toolDispatcher(FakeScreen(), web = web), "BASE")
        val end = loop.run("我已经在直播间里,帮我听主播刚才说了什么")
        assertTrue(end is TaskState.Succeeded)
        assertTrue(loop.carriedFacts.toString(), loop.carriedFacts.any { "39 元" in it })
        assertFalse(loop.carriedFacts.any { "没听到" in it })

        val turn = Turn("我已经在直播间里,帮我听主播刚才说了什么", "没听到", "succeeded", findings = loop.carriedFacts)
        val second = FakeModel(answerReply("价格是 39 元"))
        AgentLoop(second, toolDispatcher(FakeScreen(), web = web), "BASE").run("他们刚才说的价格是多少", listOf(turn))
        val sent = second.requests.first().joinToString("\n") { it.plainText() }
        assertTrue(sent, "39 元" in sent && "我已经在直播间里" in sent && Conversation.FOLLOW_UP in sent)
        assertTrue(sent, "没听到" in sent)
    }

    private fun reply(call: Part.ToolCall) = com.wangzan101.mika.core.model.ModelReply(listOf(call), com.wangzan101.mika.core.model.StopReason.ToolUse, null)

    private fun round(id: String, tool: String, content: String) = listOf(
        Message(Role.Assistant, listOf(Part.ToolCall(id, tool, "{}")), Author.Mika),
        Message(Role.Tool, listOf(Part.ToolResult(id, content, false)), Author.Mika),
    )
}
