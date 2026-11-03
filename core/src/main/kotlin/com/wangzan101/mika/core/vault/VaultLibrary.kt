package com.wangzan101.mika.core.vault

import java.time.Instant
import java.time.ZoneId

/** 库文件。路径都相对库根(用户选的那个文件夹),不是相对子文件夹。 */
interface VaultFiles {
    /** 做法笔记,不含 raw/runs。 */
    fun listNotes(root: String, limit: Int = 200): List<Pair<String, String>>
    /** [dir] 的直接子项名字。 */
    fun listNames(dir: String): List<String>
    fun read(path: String): String?
    fun write(path: String, text: String)
}

interface VaultMemory {
    fun searchEnabled(): Boolean
    fun appendEnabled(): Boolean
    fun search(query: String, appPackage: String?, limit: Int = 4): List<VaultHit>
    fun append(request: VaultAppend): VaultAppendResult
    fun contextFor(goal: String, runId: String?): String?
}

/**
 * 读、追加、运行记录都走这里。写之前脱敏,并且只写在子文件夹里。
 * 同一条 run id 再写一次不会另起一段。
 */
class VaultLibrary(
    private val files: VaultFiles,
    private val root: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private val lock = Any()

    fun rootNow() = VaultPaths.normalizeRoot(root())

    fun notes(): List<VaultNote> = files.listNotes(rootNow()).map { (p, t) -> VaultNotes.parse(p, t) }

    fun readContext(goal: String, appPackage: String? = null): VaultRead {
        val hits = VaultRetriever.rank(notes() + recentRunNotes().map { VaultNotes.parse(it.first, it.second) }, goal, appPackage, today(), includeRuns = true)
        return VaultRead(VaultRetriever.render(hits), hits.map { it.path })
    }

    fun search(query: String, appPackage: String?, limit: Int, includeRuns: Boolean = true): List<VaultHit> {
        val extra = if (!includeRuns) emptyList() else recentRunNotes().map { VaultNotes.parse(it.first, it.second) }
        return VaultRetriever.rank(notes() + extra, query, appPackage, today(), limit, includeRuns = includeRuns)
    }

    /** Backfill a retained run without changing indexes or overwriting existing material. */
    fun archiveHistory(spec: VaultRunSpec): VaultAppendResult = synchronized(lock) {
        val root = rootNow()
        val day = VaultRunLog.day(spec.startedAt, zone)
        val path = VaultPaths.runFile(root, day, VaultRunLog.hm(spec.startedAt, zone), VaultRunLog.shortId(spec.runId))
        if (!VaultPaths.allowed(path, root)) return VaultAppendResult(false, code = "outside")
        val previous = files.read(path)
        if (previous != null) return if (previous.contains("request_id: ${spec.runId}")) VaultAppendResult(true, path, "exists") else VaultAppendResult(false, path, "collision")
        files.write(path, VaultRunLog.render(root, spec.copy(goal = VaultRedactor.redact(spec.goal)), zone))
        VaultAppendResult(true, path, "ok")
    }

    fun append(request: VaultAppend, runPath: String? = null): VaultAppendResult = synchronized(lock) {
        val redacted = redact(request)
        if (blank(redacted)) return VaultAppendResult(false, code = "empty")
        val root = rootNow()
        val probe = redacted.runId.startsWith("probe-") || redacted.topic == "测试读写"
        val topic = VaultPaths.topicFrom(redacted.goal, redacted.topic)
        val path = choose(root, if (probe) emptyList() else notes(), redacted, topic, probe)
        if (!VaultPaths.allowed(path, root)) return VaultAppendResult(false, code = "outside")
        val at = redacted.atMillis ?: clock()
        val heading = "### ${VaultRunLog.stamp(at, zone)} · ${mark(redacted.outcome)} · run:${redacted.runId}"
        val entry = VaultMerger.renderEntry(heading, redacted.goal, redacted.outcome, redacted.steps, redacted.pitfalls, redacted.tips, runPath?.let { VaultPaths.link(it, "这次运行") })
        val patch = VaultMerger.FrontPatch(
            updated = VaultRunLog.day(at, zone),
            created = VaultRunLog.day(at, zone),
            type = if (probe) "concept" else "concept",
            tags = tagsFor(redacted, probe),
            aliases = listOfNotNull(redacted.appLabel?.takeIf { it.isNotBlank() }, topic.takeIf { redacted.appPackage == null }),
            app = redacted.appPackage,
            sources = listOfNotNull(runPath?.let { VaultPaths.link(it) }),
            title = redacted.appLabel?.takeIf { it.isNotBlank() } ?: topic,
        )
        val existing = files.read(path)
        if (existing?.contains("run:${redacted.runId}") == true) return VaultAppendResult(true, path, "exists")
        val merged = VaultMerger.mergeKnowledge(existing, entry, redacted.runId, patch)
        files.write(path, merged)
        VaultAppendResult(true, path, "ok")
    }

    fun writeRun(spec: VaultRunSpec): VaultAppendResult = synchronized(lock) {
        val root = rootNow()
        val day = VaultRunLog.day(spec.startedAt, zone)
        val dir = "${VaultPaths.runsDir(root)}/$day"
        val count = files.listNames(dir).count { it.endsWith(".md") }
        val runPath = VaultPaths.runFile(root, day, VaultRunLog.hm(spec.startedAt, zone), VaultRunLog.shortId(spec.runId))
        if (!VaultPaths.allowed(runPath, root)) return VaultAppendResult(false, code = "outside")
        val capped = count >= VaultPaths.MAX_RUNS_PER_DAY && files.read(runPath) == null
        if (!capped) {
            val note = VaultRunLog.render(root, spec.copy(
                goal = VaultRedactor.redact(spec.goal),
            ), zone)
            val existing = files.read(runPath)
            if (existing != null && !existing.contains("request_id: ${spec.runId}")) return VaultAppendResult(false, runPath, "collision")
            if (existing == null) files.write(runPath, note)
        }
        val link = if (capped) "- ${VaultRunLog.stamp(spec.startedAt, zone)} 本日记录已达 ${VaultPaths.MAX_RUNS_PER_DAY} 条,这条没有单独成文" else "- ${VaultRunLog.stamp(spec.startedAt, zone)} ${VaultPaths.link(runPath, spec.goal.lineSequence().first().take(24))}"
        val dayPath = VaultPaths.dayFile(root, day)
        files.write(dayPath, VaultMerger.replaceMarked(
            files.read(dayPath), VaultRunLog.DAY_START, VaultRunLog.DAY_END,
            VaultRunLog.capBullets(prepend(markedInner(files.read(dayPath), VaultRunLog.DAY_START, VaultRunLog.DAY_END), link), VaultPaths.MAX_RUNS_PER_DAY),
            dayPrologue(day),
        ))
        val logPath = VaultPaths.logFile(root)
        val logLine = if (capped) link else VaultRunLog.logLine(root, spec, runPath, zone)
        files.write(logPath, VaultMerger.replaceMarked(
            files.read(logPath), VaultRunLog.LOG_START, VaultRunLog.LOG_END,
            VaultRunLog.capBullets(prepend(markedInner(files.read(logPath), VaultRunLog.LOG_START, VaultRunLog.LOG_END), logLine), VaultPaths.MAX_LOG_LINES),
            logPrologue(),
        ))
        refreshIndex(root, spec, if (capped) null else runPath)
        VaultAppendResult(true, if (capped) dayPath else runPath, if (capped) "capped" else "ok")
    }

    fun refreshIndex(root: String = rootNow(), spec: VaultRunSpec? = null, runPath: String? = null) {
        val all = notes().filter { !it.probe }
        val apps = all.filter { "/wiki/apps/" in it.path }
        val tasks = all.filter { "/wiki/tasks/" in it.path }
        val days = files.listNames(VaultPaths.runsDir(root)).filter { it.endsWith(".md") }.map { it.removeSuffix(".md") }.sortedDescending().take(14)
        val index = VaultPaths.indexFile(root)
        files.write(index, VaultMerger.replaceMarked(
            files.read(index), VaultRunLog.INDEX_START, VaultRunLog.INDEX_END,
            VaultRunLog.indexInner(apps, tasks, days, root),
            indexPrologue(root),
        ))
        val recent = listOfNotNull(runPath?.let { VaultPaths.link(it, spec?.goal?.lineSequence()?.first()?.take(24)) }) +
            days.take(7).map { VaultPaths.link(VaultPaths.dayFile(root, it), it) }
        val home = VaultPaths.homeFile(root)
        files.write(home, VaultMerger.replaceMarked(
            files.read(home), VaultRunLog.HOME_START, VaultRunLog.HOME_END,
            VaultRunLog.homeInner(
                recent.distinct().take(8),
                apps.take(8).map { VaultPaths.link(it.path, it.title) },
                (apps + tasks).sortedByDescending { it.updatedDay ?: "" }.take(8).map { VaultPaths.link(it.path, it.title) },
            ),
            homePrologue(),
        ))
    }

    private fun choose(root: String, existing: List<VaultNote>, req: VaultAppend, topic: String, probe: Boolean): String {
        if (probe) return VaultPaths.probeFile(root)
        if (!req.appPackage.isNullOrBlank()) {
            existing.firstOrNull { it.app == req.appPackage && !it.probe }?.path?.takeIf { VaultPaths.allowed(it, root) }?.let { return it }
            return VaultPaths.appFile(root, req.appPackage)
        }
        existing.firstOrNull { !it.probe && it.path.substringAfterLast('/') == "$topic.md" && "/wiki/tasks/" in it.path }?.path?.let { return it }
        return VaultPaths.taskFile(root, topic)
    }

    private fun recentRunNotes(): List<Pair<String, String>> {
        val root = rootNow()
        val days = files.listNames(VaultPaths.runsDir(root)).mapNotNull { Regex("^(\\d{4}-\\d{2}-\\d{2})(?:\\.md(?: \\(\\d+\\))?(?:\\.txt)?)?$").matchEntire(it)?.groupValues?.get(1) }.distinct().sortedDescending().take(90)
        return days.asSequence().flatMap { day ->
            files.listNames("${VaultPaths.runsDir(root)}/$day").filter { it.endsWith(".md") || it.endsWith(".md.txt") || it.matches(Regex(".*\\.md \\(\\d+\\)\\.txt")) }.sortedDescending().asSequence().take(100).mapNotNull { name ->
                val path = "${VaultPaths.runsDir(root)}/$day/$name"
                files.read(path)?.let { path to it }
            }
        }.take(400).toList()
    }

    private fun redact(a: VaultAppend): VaultAppend {
        fun lines(xs: List<String>) = xs.map { VaultRedactor.redact(it).trim() }.filter { it.isNotEmpty() && it != "***" }
        return a.copy(
            goal = VaultRedactor.redact(a.goal).trim(),
            appLabel = a.appLabel?.let { VaultRedactor.redact(it).trim() },
            steps = lines(a.steps),
            pitfalls = lines(a.pitfalls),
            tips = lines(a.tips),
        )
    }

    private fun blank(a: VaultAppend) = a.goal.isBlank() && a.steps.isEmpty() && a.pitfalls.isEmpty() && a.tips.isEmpty()

    private fun tagsFor(a: VaultAppend, probe: Boolean): List<String> = buildList {
        add("agentreins")
        if (probe) add("probe")
        if (a.appPackage != null) add("app") else add("task")
    }

    private fun mark(o: VaultOutcome) = when (o) {
        VaultOutcome.Success -> "成功"
        VaultOutcome.Failure -> "失败"
        VaultOutcome.Note -> "备注"
        VaultOutcome.Stopped -> "停下"
    }

    private fun today() = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()

    private fun markedInner(text: String?, start: String, end: String): String {
        if (text == null) return ""
        val s = text.indexOf(start)
        val e = if (s >= 0) text.indexOf(end, s + start.length) else -1
        if (s < 0 || e < 0) return ""
        return text.substring(s + start.length, e).trim()
    }

    private fun prepend(existing: String, line: String): String =
        (line + "\n" + existing).trim()

    private fun dayPrologue(day: String) = """
        ---
        type: overview
        created: $day
        updated: $day
        tags: [agentreins, run]
        ---

        # $day
    """.trimIndent()

    private fun logPrologue() = """
        ---
        type: overview
        tags: [agentreins, log]
        ---

        # 日志

        新的在上面。
    """.trimIndent()

    private fun indexPrologue(root: String) = """
        ---
        type: overview
        tags: [agentreins, index]
        ---

        # 索引

        做法在 wiki,一次运行的原始记录在 raw/runs。首页：${VaultPaths.link(VaultPaths.homeFile(root), "首页")}
    """.trimIndent()

    private fun homePrologue() = """
        ---
        type: overview
        tags: [agentreins]
        ---

        # AgentReins

        这里是最近的运行和记下的做法。可以改上面的字,两条标记之间的列表会更新。
    """.trimIndent()
}
