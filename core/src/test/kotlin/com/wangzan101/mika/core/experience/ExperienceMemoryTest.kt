package com.wangzan101.mika.core.experience

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.core.memory.MemoryPersistence
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.memory.PreferenceLearner
import com.wangzan101.mika.core.memory.TaskNote
import com.wangzan101.mika.core.memory.TaskNoteBook
import com.wangzan101.mika.core.memory.TaskNotePersistence
import com.wangzan101.mika.core.memory.TaskRecall
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 红包这种来回试、最后才走通的任务:成功路径要留下,下次先按它做;失败过的点法记在避开里。
 */
class ExperienceMemoryTest {
    private class Mem : ExperiencePersistence {
        var stored = emptyList<Experience>()
        override fun load() = stored
        override fun save(items: List<Experience>) { stored = items }
    }

    private fun book() = ExperienceBook(Mem(), ids = { "e1" }, clock = { 10 })

    private fun reply(step: Int, tool: String, args: String) =
        RunEvent.ModelReply(step.toLong(), step, "", tool, args, null, null, null)

    private fun result(step: Int, tool: String, json: String, changed: Boolean?) =
        RunEvent.ToolResult(step.toLong(), step, tool, json, changed, null)

    private val screen = result(1, "phone_observe",
        """{"ok":true,"elements":[{"ref":"@e1","role":"button","name":"红包"},{"ref":"@e2","role":"button","name":"转账"}]}""", false)

    private fun wechat(vararg extra: RunEvent, end: String = "succeeded"): List<RunEvent> = listOf(
        reply(2, "phone_launch_app", """{"package":"com.tencent.mm"}"""),
        result(2, "phone_launch_app", """{"ok":true,"changed":true,"currentApp":"com.tencent.mm"}""", true),
        screen,
        reply(3, "phone_tap", """{"ref":"@e2","target":"转账"}"""),
        result(3, "phone_tap", """{"ok":false,"error":"not_found","currentApp":"com.tencent.mm"}""", false),
        reply(4, "phone_tap", """{"ref":"@e1","target":"红包"}"""),
        result(4, "phone_tap", """{"ok":true,"changed":true,"currentApp":"com.tencent.mm"}""", true),
    ) + extra + RunEvent.TaskEnded(9, end, "发出去了")

    @Test fun successKeepsTheWorkingStepsAndTheFailedAttempt() {
        val draft = ExperienceDistiller.distill("在微信群里发红包", wechat(), TOOLS)!!
        assertTrue(draft.keepSteps)
        assertEquals("com.tencent.mm", draft.appPackage)
        assertTrue(draft.steps.any { it.action == "phone_tap" && it.target == "红包" })
        assertFalse(draft.steps.any { it.target == "转账" })
        assertTrue(draft.avoid.any { "转账" in it })
        val saved = book().apply(draft)!!
        assertEquals(1, saved.confidence)
        assertTrue(saved.steps.any { it.target == "红包" })
    }

    @Test fun laterSuccessReplacesStepsAndFailureLowersConfidence() {
        val store = book()
        store.apply(ExperienceDistiller.distill("在微信群里发红包", wechat(), TOOLS)!!)
        val failed = ExperienceDistiller.distill("在微信群里发红包", wechat(end = "failed"), TOOLS)!!
        assertFalse(failed.keepSteps)
        val afterFail = store.apply(failed)!!
        assertEquals(1, afterFail.failures)
        assertEquals(1, afterFail.confidence)
        val again = store.apply(failed)!!
        assertEquals(0, again.confidence)
        assertTrue(store.search("在微信群里发红包").isEmpty())
        val againOk = store.apply(ExperienceDistiller.distill("在微信群里发红包", wechat(), TOOLS)!!)!!
        assertTrue(againOk.confidence >= 1)
        assertTrue(againOk.steps.any { it.target == "红包" })
        assertTrue(store.search("在微信群里再发红包").any { it.steps.any { s -> s.target == "红包" } })
    }

    @Test fun amountsAndPasswordsAreNotStored() {
        val events = listOf(
            reply(1, "phone_launch_app", """{"package":"com.tencent.mm"}"""),
            result(1, "phone_launch_app", """{"ok":true,"changed":true}""", true),
            reply(2, "phone_tap", """{"target":"200元红包"}"""),
            result(2, "phone_tap", """{"ok":true,"changed":true,"currentApp":"com.tencent.mm"}""", true),
            reply(3, "phone_tap", """{"target":"支付密码 123456"}"""),
            result(3, "phone_tap", """{"ok":true,"changed":true}""", true),
            RunEvent.TaskEnded(4, "succeeded", "好了"),
        )
        val draft = ExperienceDistiller.distill("发200元红包", events, TOOLS)!!
        val blob = (listOf(draft.intent) + draft.steps.map { it.target.orEmpty() }).joinToString(" ")
        assertFalse(blob, "200" in blob || "123456" in blob || "支付密码" in blob)
        assertTrue(ExperienceDistiller.wantsRemember(listOf("记住这个做法")))
    }

    @Test fun userCanForceSaveAfterTheyConfirm() {
        val events = wechat(
            RunEvent.Takeover(8, "payment", "user_finished", 1, 0),
            end = "failed",
        ).dropLast(1) + RunEvent.TaskEnded(9, "failed", "用户说自己发完了")
        val draft = ExperienceDistiller.distill("在微信群里发红包", events, TOOLS, listOf("记住这个做法"))!!
        assertTrue(draft.keepSteps)
        assertTrue(draft.steps.any { it.target == "红包" })
    }

    @Test fun planIsInjectedIntoTheNextRun() = runTest {
        val saved = book().apply(ExperienceDistiller.distill("在微信群里发红包", wechat(), TOOLS)!!)!!
        val plan = ExperienceContext.render(listOf(saved))!!
        assertTrue(plan.length <= ExperienceContext.MAX_CHARS)
        val model = FakeModel(answerReply("按上次的发"))
        AgentLoop(model, toolDispatcher(FakeScreen()), "BASE", experiencePlan = plan).run("再在微信群里发红包")
        val sent = model.requests.first().flatMap { it.parts }.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
        assertTrue(sent, "红包" in sent && "Follow these steps first" in sent && "转账" in sent)
    }

    @Test fun preferencesAreDedupedAndPastTasksMatchByWords() {
        val persistence = object : MemoryPersistence {
            var stored = emptyList<MemoryItem>()
            override fun load() = stored
            override fun save(items: List<MemoryItem>) { stored = items }
        }
        val store = MemoryStore(persistence, clock = { 1 }, ids = { "p" })
        val first = PreferenceLearner.remember(store, "点外卖", listOf("以后都不要香菜"), listOf("美团"), 1, "c")
        val second = PreferenceLearner.remember(store, "点外卖", listOf("以后都不要香菜"), listOf("美团"), 2, "c")
        assertEquals(1, first.count { it is com.wangzan101.mika.core.memory.Change.Added && (it as com.wangzan101.mika.core.memory.Change.Added).item.kind == MemoryKind.preference && "香菜" in (it as com.wangzan101.mika.core.memory.Change.Added).item.content })
        assertTrue(second.none { it is com.wangzan101.mika.core.memory.Change.Added && "香菜" in (it as com.wangzan101.mika.core.memory.Change.Added).item.content })
        assertEquals(1, store.items.value.count { it.content == "常用: 美团" })

        val notes = TaskNoteBook(object : TaskNotePersistence {
            var stored = emptyList<TaskNote>()
            override fun load() = stored
            override fun save(items: List<TaskNote>) { stored = items }
        })
        notes.add(TaskRecall.summarize("r1", "在微信群里发200元红包", listOf(
            RunEvent.Activity(1, 1, "tap", "点红包"),
            RunEvent.Barrier(2, "payment"),
            RunEvent.TaskEnded(3, "succeeded", "发出去了"),
        ), 5))
        notes.add(TaskRecall.summarize("r2", "查天气", listOf(RunEvent.TaskEnded(1, "succeeded", "晴")), 6))
        val hit = notes.search("在微信群里发红包")
        assertEquals(listOf("r1"), hit.map { it.runId })
        assertFalse(hit.single().goal.contains("200"))
        val rendered = TaskRecall.render(hit)!!
        assertTrue(rendered.length <= TaskRecall.MAX_CHARS && "红包" in rendered)
    }
}
