package com.wangzan101.mika.core.vault

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.experience.ExperienceDraft
import com.wangzan101.mika.core.experience.ExperienceStep
import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class VaultMemoryTest {
    @Test fun longTaskReportIsRetainedAndExplicitlyMarksTruncation() {
        val answer = "发布记录：" + "内容".repeat(12000) + "末尾"
        val spec = VaultRunSpec("r-report", "回复帖子", "测试", VaultOutcome.Success,
            listOf(RunEvent.TaskEnded(1, "succeeded", answer)), emptyList(), emptyList(), 0, 1000)
        val rendered = VaultRunLog.render("AgentReins", spec, ZoneOffset.UTC)
        assertTrue(rendered.contains(VaultRedactor.redact(answer).take(20000)))
        assertTrue(rendered.contains("后续内容已截断"))
        assertFalse(rendered.contains("末尾"))
    }

    @Test fun redactsPaymentPinAndLeavesOrdinaryNumbers() {
        val out = VaultRedactor.redact("支付密码是258369,然后发200元,验证码 482913,密码：hunter2")
        assertFalse(out.contains("258369"))
        assertFalse(out.contains("482913"))
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("200"))
        assertTrue(out.contains("***"))
    }

    @Test fun redactsTokenJoinCodeAndLabeledPhoneOnly() {
        val out = VaultRedactor.redact("token: abcdefghijklmnop 加入码 AB12CD 手机号 13812345678 订单号 13812345678 明天下午3点")
        assertFalse(out.contains("abcdefghijklmnop"))
        assertFalse(out.contains("AB12CD"))
        assertFalse(out.contains("手机号 ***").not())
        assertTrue(out.contains("订单号 13812345678"))
        assertTrue(out.contains("3点"))
    }

    @Test fun yearAfterPaymentWordStays() {
        val out = VaultRedactor.redact("支付密码在2026年改过")
        assertTrue(out.contains("2026"))
    }

    @Test fun apiKeyIsMasked() {
        val out = VaultRedactor.redact("用 sk-abcdefghijklmnopqrstuvwxyz 调用")
        assertFalse(out.contains("sk-abc"))
    }

    @Test fun appendKeepsOwnerTextAndDoesNotDuplicateRun() {
        val files = MemFiles()
        val lib = library(files)
        val existing = """
            ---
            tags: [日记]
            mood: 晴
            updated: 2020-01-01
            ---

            我的备注：不要删掉这句话。

            ## AgentReins 记录

            ### 2020-01-01 00:00 · 成功 · run:old
            主人改过：这一句要留着。
        """.trimIndent() + "\n"
        files.map["AgentReins/wiki/apps/com.tencent.mm.md"] = existing
        val saved = lib.append(VaultAppend("r1", VaultOutcome.Success, "发帖", "com.tencent.mm", "微信", steps = listOf("打开微信")))
        val text = files.map.getValue(saved.path!!)
        assertTrue(text.contains("不要删掉这句话"))
        assertTrue(text.contains("这一句要留着"))
        assertTrue(text.contains("mood: 晴"))
        assertTrue(text.contains("日记"))
        assertTrue(text.contains("run:r1"))
        assertTrue(text.contains("updated: 1970-01-01"))
        assertFalse(text.contains("updated: 2020-01-01"))
        lib.append(VaultAppend("r1", VaultOutcome.Success, "发帖", "com.tencent.mm", steps = listOf("又一次")))
        assertEquals(1, Regex("run:r1").findAll(files.map.getValue(saved.path!!)).count())
    }

    @Test fun conflictFileIsAppendedNotRewritten() {
        val before = "主人的字\n<<<<<<< HEAD\n甲\n=======\n乙\n>>>>>>> other\n"
        val after = VaultMerger.mergeKnowledge(before, "### 新 · run:n1\n", "n1", patch())
        assertTrue(after.contains("主人的字"))
        assertTrue(after.contains("甲"))
        assertTrue(after.contains("乙"))
        assertTrue(after.contains("run:n1"))
        assertTrue(after.startsWith("主人的字"))
    }

    @Test fun markedSectionKeepsOwnerLinesWhenCapped() {
        val owner = "主人写在日志里的话"
        val dated = (1..5).joinToString("\n") { "- 2026-11-0$it 旧" }
        val inner = VaultRunLog.capBullets("$owner\n$dated", 2)
        assertTrue(inner.contains(owner))
        assertTrue(inner.contains("- 2026-11-01 旧"))
        assertFalse(inner.contains("- 2026-11-05 旧"))
        val merged = VaultMerger.replaceMarked("开头不要动\n\n$owner\n", VaultRunLog.LOG_START, VaultRunLog.LOG_END, "新行", "序")
        assertTrue(merged.contains("开头不要动"))
        assertTrue(merged.contains(owner))
        assertTrue(merged.contains("新行"))
    }

    @Test fun refusesPathEscape() {
        assertFalse(VaultPaths.allowed("AgentReins/../secret.md", "AgentReins"))
        assertFalse(VaultPaths.allowed("/sdcard/x.md", "AgentReins"))
        assertTrue(VaultPaths.allowed("AgentReins/wiki/apps/a.md", "AgentReins"))
        val files = MemFiles()
        val lib = library(files)
        val bad = lib.append(VaultAppend("r", VaultOutcome.Note, "x", topic = "../../etc/passwd", tips = listOf("提示")))
        assertTrue(bad.ok)
        assertTrue(bad.path!!.startsWith("AgentReins/"))
        assertFalse(bad.path.contains(".."))
    }

    @Test fun ranksAppAndRecencyAndCapsInjection() {
        val today = LocalDate.parse("2026-11-03")
        val wechat = note("AgentReins/wiki/apps/com.tencent.mm.md", "微信发帖", "com.tencent.mm", "2026-11-02")
        val weibo = note("AgentReins/wiki/apps/com.sina.weibo.md", "微博发帖", "com.sina.weibo", "2026-11-03")
        val old = note("AgentReins/wiki/tasks/发帖.md", "发帖", null, "2024-01-01")
        val fresh = note("AgentReins/wiki/tasks/发帖-new.md", "发帖", null, "2026-11-01", pathOverride = "AgentReins/wiki/tasks/新发帖.md")
        val ranked = VaultRetriever.rank(listOf(weibo, wechat, old, fresh), "微信里发帖", "com.tencent.mm", today)
        assertEquals(wechat.path, ranked.first().path)
        val tied = VaultRetriever.rank(listOf(old, fresh), "发帖", null, today)
        assertEquals(fresh.path, tied.first().path)
        assertTrue(VaultRetriever.rank(listOf(note("AgentReins/wiki/tasks/天气.md", "明天天气", null, "2026-11-03")), "微信发帖", null, today).isEmpty())
        val huge = note("AgentReins/wiki/apps/x.md", "发帖".repeat(2000), "a.b", "2026-11-03")
        val rendered = VaultRetriever.render(VaultRetriever.rank(listOf(huge), "发帖", "a.b", today))!!
        assertTrue(rendered.length <= VaultRetriever.MAX_CHARS)
    }

    @Test fun runLogRedactsAndLinks() {
        val spec = VaultRunSpec(
            runId = "abc-123",
            goal = "支付密码是258369 去发帖",
            source = "应用内聊天",
            end = VaultOutcome.Success,
            events = listOf(
                RunEvent.TaskStarted(1, "支付密码是258369", "m"),
                RunEvent.Activity(2, 1, "tap", "点发送", "按钮", null),
                RunEvent.Confirmation(3, "pay", "微信", null, "200", "allow"),
                RunEvent.Media(4, 7, 1, "com.tencent.mm"),
                RunEvent.TaskEnded(5, "succeeded", "好了"),
            ),
            readPaths = listOf("AgentReins/wiki/apps/com.tencent.mm.md"),
            writtenPaths = listOf("AgentReins/wiki/apps/com.tencent.mm.md"),
            startedAt = 0,
            endedAt = 90_000,
        )
        val md = VaultRunLog.render("AgentReins", spec, ZoneOffset.UTC)
        assertFalse(md.contains("258369"))
        assertTrue(md.contains("[[AgentReins/wiki/apps/com.tencent.mm]]"))
        assertTrue(md.contains("type: source"))
        assertTrue(md.contains("request_id: abc-123"))
        assertTrue(md.contains("画面 #7"))
        assertTrue(md.contains("应用内聊天"))
        assertTrue(md.contains("成功"))
    }

    @Test fun lessonSkipsEmptyChatAndRecordsFailure() {
        assertEquals(null, VaultLessons.from("你好", "r", TaskState.Succeeded("好"), null))
        val draft = ExperienceDraft("发帖", listOf("发帖"), "com.tencent.mm", "微信", listOf(ExperienceStep("phone_tap", "发送")), emptyList(), emptyList(), emptyList(), true)
        val ok = VaultLessons.from("发帖", "r", TaskState.Succeeded("发好了，记住群名"), draft)!!
        assertEquals(VaultOutcome.Success, ok.outcome)
        assertTrue(ok.steps.single().contains("发送"))
        val failed = VaultLessons.from("发帖", "r", TaskState.Failed("找不到发送", FailKind.GaveUp), draft)!!
        assertEquals(VaultOutcome.Failure, failed.outcome)
        assertTrue(failed.pitfalls.any { "找不到发送" in it })
    }

    @Test fun libraryRunWritesIndexWithoutDroppingOwnerHome() {
        val files = MemFiles()
        val lib = library(files)
        files.map["AgentReins/首页.md"] = "我的首页备注\n\n<!-- agentreins:home:start -->\n- 旧\n<!-- agentreins:home:end -->\n"
        val spec = VaultRunSpec("run-9", "发帖", "Hub 老板指令", VaultOutcome.Success, emptyList(), emptyList(), emptyList(), 0, 1000)
        val saved = lib.writeRun(spec)
        assertTrue(saved.ok)
        val home = files.map.getValue("AgentReins/首页.md")
        assertTrue(home.contains("我的首页备注"))
        assertTrue(files.map.keys.any { it.contains("raw/runs") && it.endsWith(".md") && !it.endsWith("/1970-01-01.md") || it.contains("raw/runs/1970-01-01/") })
        assertTrue(files.map.getValue("AgentReins/wiki/index.md").contains("type: overview"))
        assertTrue(files.map.getValue("AgentReins/wiki/log.md").contains("Hub") || files.map.getValue("AgentReins/wiki/log.md").contains("发帖"))
    }

    @Test fun runIdsRemainDistinctAndHistoryIncludesResult() {
        val files = MemFiles()
        val lib = library(files)
        fun spec(id: String, answer: String) = VaultRunSpec(id, "输入法发帖", "应用内聊天", VaultOutcome.Success,
            listOf(RunEvent.TaskEnded(1, "succeeded", answer)), emptyList(), emptyList(), 0, 1000)
        val a = lib.writeRun(spec("20261103-235947-a", "产品支持语音输入"))
        val b = lib.writeRun(spec("20261103-235947-b", "第二次发帖"))
        assertTrue(a.ok && b.ok)
        assertFalse(a.path == b.path)
        assertTrue(files.map.getValue(a.path!!).contains("产品支持语音输入"))
        val history = lib.readContext("输入法发帖").text.orEmpty()
        assertTrue(history.contains("输入法发帖"))
        assertTrue(history.contains("产品支持语音输入"))
    }

    @Test fun historyBackfillIsIdempotentAndKeepsExistingText() {
        val files = MemFiles()
        val lib = library(files)
        val spec = VaultRunSpec("20261103-235947-a", "搜索输入法", "历史任务", VaultOutcome.Success,
            listOf(RunEvent.TaskEnded(1, "succeeded", "已找到产品帖子")), emptyList(), emptyList(), 0, 1000)
        val first = lib.archiveHistory(spec)
        assertTrue(first.ok)
        val saved = files.map.getValue(first.path!!)
        assertTrue(lib.archiveHistory(spec).ok)
        assertEquals(saved, files.map.getValue(first.path!!))
        assertTrue(saved.contains("已找到产品帖子"))
    }

    private fun library(files: MemFiles) = VaultLibrary(files, { "AgentReins" }, clock = { 0 }, zone = ZoneOffset.UTC)

    private fun patch() = VaultMerger.FrontPatch("2026-11-03", "2026-11-03", "concept", listOf("agentreins"), title = "笔记")

    private fun note(path: String, body: String, app: String?, day: String?, pathOverride: String? = null): VaultNote {
        val p = pathOverride ?: path
        val appLine = if (app != null) "app: $app\n" else ""
        val text = "---\ntype: concept\ntags: [agentreins]\n${appLine}updated: ${day ?: "2020-01-01"}\n---\n\n# ${body.take(8)}\n\n$body\n"
        return VaultNotes.parse(p, text)
    }

    private class MemFiles : VaultFiles {
        val map = linkedMapOf<String, String>()
        override fun listNotes(root: String, limit: Int) =
            map.entries.filter { VaultPaths.allowed(it.key, root) && !VaultPaths.isRunLog(it.key) }.take(limit).map { it.key to it.value }
        override fun listNames(dir: String): List<String> {
            val prefix = dir.trimEnd('/') + "/"
            return map.keys.filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains("/") }.map { it.removePrefix(prefix) }
        }
        override fun read(path: String) = map[path]
        override fun write(path: String, text: String) {
            if (!VaultPaths.allowed(path, "AgentReins")) error("outside $path")
            map[path] = text
        }
    }
}
