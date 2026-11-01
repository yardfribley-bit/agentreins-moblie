package com.wangzan101.mika.core.relay

import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayEventTextTest {
    @Test fun sameIndexIsIdempotent() {
        val event = RunEvent.TaskStarted(1, "查天气", "m1")
        val a = RelayEventText.map("20261031-120000-abcdef", 3, event)
        val b = RelayEventText.map("20261031-120000-abcdef", 3, event)
        assertEquals(a, b)
        assertEquals("20261031-120000-abcdef:3", a!!.requestId)
        assertEquals(RelayKind.PROGRESS, a.kind)
        assertTrue(a.text.startsWith("开始："))
        assertEquals("20261031-120000-abcdef:4", RelayEventText.map("20261031-120000-abcdef", 4, event)!!.requestId)
    }

    @Test fun toolStepAndFinalAnswer() {
        val step = RelayEventText.map("run", 1, RunEvent.ToolResult(2, 1, "phone_observe", """{"ok":true,"message":"看到 25 度"}""", null, null))
        assertEquals(RelayKind.PROGRESS, step!!.kind)
        assertEquals("run:1", step.requestId)
        assertTrue(step.text.contains("phone_observe"))
        assertTrue(step.text.contains("ok"))
        assertTrue(step.text.contains("25 度"))

        val end = RelayEventText.map("run", 2, RunEvent.TaskEnded(3, "succeeded", "25 度"))
        assertEquals(RelayKind.RESULT, end!!.kind)
        assertTrue(end.text.contains("succeeded"))
        assertTrue(end.text.contains("25 度"))

        val judged = RelayEventText.map("run", 3, RunEvent.OutcomeJudged(4, "done", "办成了"))
        assertEquals(RelayKind.RESULT, judged!!.kind)
        assertTrue(judged.text.contains("done"))
    }

    @Test fun secretsAreMaskedAndTextIsCapped() {
        val leaked = RelayEventText.map(
            "run", 0,
            RunEvent.ModelReply(1, 1, "用这个", "phone_type", """{"text":"Bearer abcdefghijklmnop sk-abcdefghijklmnopqrstuvwxyz"}""", null, null, null),
        )!!
        assertFalse(leaked.text.contains("abcdefghijklmnop"))
        assertFalse(leaked.text.contains("sk-abcdefghijklmnopqrstuvwxyz"))
        assertTrue(leaked.text.contains("***"))
        assertTrue(leaked.text.contains("phone_type"))

        val pin = RelayEventText.map(
            "run", 4,
            RunEvent.ModelReply(1, 22, "输入支付密码", "do_steps",
                """{"reason":"输入支付密码 142536","steps":[{"tool":"phone_tap","args":{"x":499,"y":969,"target":"密码键盘 0"}}]}""",
                null, null, null),
        )!!
        assertFalse(pin.text, pin.text.contains("142536"))
        assertFalse(pin.text, pin.text.contains("499"))
        assertFalse(pin.text, "密码键盘 0" in pin.text)
        assertTrue(pin.text.contains("密码键盘 *"))

        val long = RelayEventText.map("run", 1, RunEvent.TaskEnded(2, "succeeded", "答".repeat(9000)))!!
        assertTrue(long.text.length <= RELAY_MAX_TEXT)
    }

    @Test fun internalBookkeepingIsSkipped() {
        assertNull(RelayEventText.map("run", 0, RunEvent.PromptVersion(1, "abc")))
        assertNull(RelayEventText.map("run", 1, RunEvent.Compressed(2, 4)))
        assertNull(RelayEventText.map("run", 2, RunEvent.ModelReply(3, 1, "不算", "phone_observe", "{}", null, null, null, role = "light_rejected")))
    }
}
