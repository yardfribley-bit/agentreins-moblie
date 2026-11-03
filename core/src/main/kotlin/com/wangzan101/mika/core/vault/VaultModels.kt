package com.wangzan101.mika.core.vault

import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.experience.ExperienceDraft
import com.wangzan101.mika.core.experience.ExperienceStep
import com.wangzan101.mika.core.memory.Tokenize
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.relay.RelayEventText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class VaultOutcome { Success, Failure, Note, Stopped }

data class VaultAppend(
    val runId: String,
    val outcome: VaultOutcome,
    val goal: String,
    val appPackage: String? = null,
    val appLabel: String? = null,
    val topic: String? = null,
    val steps: List<String> = emptyList(),
    val pitfalls: List<String> = emptyList(),
    val tips: List<String> = emptyList(),
    val atMillis: Long? = null,
)

data class VaultAppendResult(val ok: Boolean, val path: String? = null, val code: String? = null, val detail: String? = null)

data class VaultNote(
    val path: String,
    val text: String,
    val app: String?,
    val tags: List<String>,
    val updatedDay: String?,
    val title: String,
) {
    val probe: Boolean get() = VaultPaths.isProbe(path) || "probe" in tags
    val searchBlob: String get() = "$title ${app.orEmpty()} ${tags.joinToString(" ")} $path $text"
}

data class VaultHit(val path: String, val title: String, val excerpt: String, val score: Double, val updatedDay: String?)

data class VaultRead(val text: String?, val paths: List<String>)

/** 一次运行要写进库的材料。步骤文字用已有的 [RelayEventText],这里不再另写一套摘要。 */
data class VaultRunSpec(
    val runId: String,
    val goal: String,
    val source: String,
    val end: VaultOutcome,
    val events: List<RunEvent>,
    val readPaths: List<String>,
    val writtenPaths: List<String>,
    val startedAt: Long,
    val endedAt: Long,
)

object VaultNotes {
    fun parse(path: String, text: String): VaultNote {
        val (front, body) = VaultMerger.split(text)
        val tags = bracket(front, "tags")
        val app = scalar(front, "app")
        val updated = scalar(front, "updated")?.take(10)?.takeIf { it.length == 10 && it[4] == '-' }
        val title = body.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
            ?: path.substringAfterLast('/').removeSuffix(".md")
        return VaultNote(path, text, app, tags, updated, title)
    }

    fun scalar(front: List<String>?, key: String): String? =
        front?.firstOrNull { it.trimStart().startsWith("$key:") }?.substringAfter(":")?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }

    fun bracket(front: List<String>?, key: String): List<String> {
        val raw = scalar(front, key) ?: return emptyList()
        if (!raw.startsWith("[") || !raw.endsWith("]")) return emptyList()
        return raw.removePrefix("[").removeSuffix("]").split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
    }
}

object VaultLessons {
    private val FAIL = setOf(FailKind.GaveUp, FailKind.Guard, FailKind.StepLimit)

    fun from(goal: String, runId: String, end: TaskState, draft: ExperienceDraft?): VaultAppend? = when (end) {
        is TaskState.Succeeded -> success(goal, runId, end, draft)
        is TaskState.Failed -> failure(goal, runId, end, draft)
        else -> null
    }

    private fun success(goal: String, runId: String, end: TaskState.Succeeded, draft: ExperienceDraft?): VaultAppend? {
        val steps = draft?.steps?.map(::line).orEmpty()
        val pits = (draft?.pitfalls.orEmpty() + draft?.avoid.orEmpty()).distinct()
        if (steps.isEmpty() && pits.isEmpty()) return null
        val tip = tip(end.answer)?.let { listOf(it) }.orEmpty()
        return VaultAppend(runId, VaultOutcome.Success, draft?.intent ?: goal, draft?.appPackage, draft?.appLabel, steps = steps, pitfalls = pits, tips = tip)
    }

    private fun failure(goal: String, runId: String, end: TaskState.Failed, draft: ExperienceDraft?): VaultAppend? {
        if (end.kind !in FAIL) return null
        val pits = (draft?.avoid.orEmpty() + draft?.pitfalls.orEmpty() + listOfNotNull(end.reason.takeIf { it.isNotBlank() })).distinct()
        val tried = draft?.steps?.map(::line).orEmpty()
        if (pits.isEmpty() && tried.isEmpty()) return null
        return VaultAppend(runId, VaultOutcome.Failure, draft?.intent ?: goal, draft?.appPackage, draft?.appLabel, steps = tried, pitfalls = pits)
    }

    private fun line(s: ExperienceStep) = buildString {
        append(s.action)
        s.target?.let { append(" 「").append(it).append("」") }
        s.where?.let { append(" · ").append(it) }
    }

    private fun tip(answer: String): String? {
        val line = answer.lineSequence().map { it.trim() }.firstOrNull { it.length in 8..160 } ?: return null
        if (VaultRedactor.redact(line).contains("***")) return null
        return line
    }
}

object VaultRetriever {
    const val MAX_CHARS = 1400
    const val MAX_NOTES = 3
    const val MIN_SCORE = 0.28

    private const val HEADER =
        "Notes from the owner's Obsidian vault (they edit these; may be outdated; when they conflict with what the user says now, follow the user; a section marked 失败 is a pitfall, not a plan):"

    fun rank(notes: List<VaultNote>, query: String, appPackage: String?, today: LocalDate, limit: Int = MAX_NOTES, includeRuns: Boolean = false): List<VaultHit> {
        val qTerms = Tokenize.terms(query, withUnigrams = true).toSet()
        return notes.mapNotNull { note ->
            if (note.probe || VaultPaths.isProbe(note.path)) return@mapNotNull null
            if (!includeRuns && VaultPaths.isRunLog(note.path)) return@mapNotNull null
            val score = score(note, qTerms, query, appPackage, today)
            if (score < MIN_SCORE) null else VaultHit(note.path, note.title, excerpt(note, qTerms), score, note.updatedDay)
        }.sortedWith(compareByDescending<VaultHit> { it.score }.thenByDescending { it.updatedDay ?: "" }).take(limit)
    }

    fun render(hits: List<VaultHit>): String? {
        if (hits.isEmpty()) return null
        val parts = mutableListOf(HEADER)
        for (h in hits) {
            val block = VaultRedactor.redact("— ${h.path}\n${h.title}\n${h.excerpt}")
            val next = (parts + block).joinToString("\n\n")
            if (next.length > MAX_CHARS) {
                if (parts.size == 1) parts += block.take((MAX_CHARS - HEADER.length - 2).coerceAtLeast(0)).trimEnd() + "…"
                break
            }
            parts += block
        }
        return parts.takeIf { it.size > 1 }?.joinToString("\n\n")
    }

    private fun score(note: VaultNote, qTerms: Set<String>, query: String, appPackage: String?, today: LocalDate): Double {
        val doc = Tokenize.terms(note.searchBlob, withUnigrams = false).toSet()
        var s = if (qTerms.isEmpty()) 0.0 else qTerms.count { it in doc }.toDouble() / qTerms.size
        if (appPackage != null && note.app == appPackage) s += 0.55
        else if (appPackage != null && note.app != null && note.app != appPackage) s -= 0.25
        note.tags.forEach { tag -> if (tag.length >= 2 && query.contains(tag, ignoreCase = true)) s += 0.08 }
        val age = note.updatedDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today) }
        if (age != null && age >= 0) s += when {
            age <= 14 -> 0.15
            age <= 60 -> 0.08
            age <= 180 -> 0.03
            else -> 0.0
        }
        return s
    }

    private fun excerpt(note: VaultNote, qTerms: Set<String>): String {
        val (_, body) = VaultMerger.split(note.text)
        val owner = body.substringBefore(VaultPaths.RECORD).trim()
        val blocks = body.substringAfter(VaultPaths.RECORD, "").split(Regex("""(?=^### )""", RegexOption.MULTILINE))
            .map { it.trim() }.filter { it.startsWith("###") }
        val picked = blocks.sortedByDescending { block ->
            val terms = Tokenize.terms(block, withUnigrams = false).toSet()
            if (qTerms.isEmpty()) 0 else qTerms.count { it in terms }
        }.take(2)
        return buildString {
            if (owner.isNotBlank()) append(owner.take(280))
            picked.forEach { append("\n\n").append(it.take(360)) }
        }.trim().take(700)
    }
}

object VaultRunLog {
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val HM = DateTimeFormatter.ofPattern("HHmm")
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    const val LOG_START = "<!-- agentreins:log:start -->"
    const val LOG_END = "<!-- agentreins:log:end -->"
    const val INDEX_START = "<!-- agentreins:index:start -->"
    const val INDEX_END = "<!-- agentreins:index:end -->"
    const val HOME_START = "<!-- agentreins:home:start -->"
    const val HOME_END = "<!-- agentreins:home:end -->"
    const val DAY_START = "<!-- agentreins:day:start -->"
    const val DAY_END = "<!-- agentreins:day:end -->"

    fun day(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(DAY)
    fun stamp(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(STAMP)
    fun hm(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(HM)
    fun clock(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(CLOCK)

    fun shortId(runId: String): String = runId.filter { it.isLetterOrDigit() }.take(8).ifBlank { "run" }

    fun render(root: String, spec: VaultRunSpec, zone: ZoneId): String {
        val started = spec.startedAt
        val goal = VaultRedactor.redact(spec.goal).trim()
        val title = VaultPaths.safeSegment(Tokenize.keywords(goal).firstOrNull() ?: goal.take(16))
        val whenClock = clock(started, zone)
        val outcome = when (spec.end) {
            VaultOutcome.Success -> "成功"
            VaultOutcome.Failure -> "失败"
            VaultOutcome.Note -> "备注"
            VaultOutcome.Stopped -> "停下"
        }
        val minutes = ((spec.endedAt - spec.startedAt).coerceAtLeast(0L) / 60000)
        val seconds = ((spec.endedAt - spec.startedAt).coerceAtLeast(0L) / 1000) % 60
        val duration = if (minutes > 0) "${minutes} 分 ${seconds} 秒" else "${seconds} 秒"
        val steps = spec.events.mapIndexedNotNull { i, e ->
            RelayEventText.map(spec.runId, i, e)?.text?.let { VaultRedactor.redact(it) }
        }.take(60)
        val shots = spec.events.filterIsInstance<RunEvent.Media>().take(6)
        return buildString {
            append("---\n")
            append("type: source\n")
            append("created: ").append(stamp(started, zone)).append('\n')
            append("updated: ").append(stamp(spec.endedAt, zone)).append('\n')
            append("tags: [agentreins, run]\n")
            append("aliases: [\"").append(shortId(spec.runId)).append("\"]\n")
            append("request_id: ").append(spec.runId).append('\n')
            append("source: ").append(spec.source).append('\n')
            append("---\n\n")
            append("# ").append(whenClock).append(' ').append(title).append("\n\n")
            append("- 来源：").append(spec.source).append('\n')
            append("- 请求编号：`").append(spec.runId).append("`\n")
            append("- 结果：").append(outcome).append('\n')
            append("- 用时：").append(duration).append("\n\n")
            append("## 指令\n\n").append(goal.ifBlank { "(空)" }).append("\n\n")
            append("## 读入的笔记\n\n")
            if (spec.readPaths.isEmpty()) append("这次没有读到相关笔记。\n\n")
            else spec.readPaths.distinct().forEach { append("- ").append(VaultPaths.link(it)).append('\n') }
            if (spec.readPaths.isNotEmpty()) append('\n')
            append("## 步骤\n\n")
            if (steps.isEmpty()) append("没有留下步骤。\n\n")
            else steps.forEachIndexed { i, line -> append(i + 1).append(". ").append(line).append('\n') }
            append("\n## 画面\n\n")
            if (shots.isEmpty()) append("没有单独引用画面。\n\n")
            else shots.forEach { append("- 第 ").append(it.step).append(" 步 画面 #").append(it.mediaId).append('\n') }
            append("\n## 写回的笔记\n\n")
            if (spec.writtenPaths.isEmpty()) append("这次没有写回做法。\n")
            else spec.writtenPaths.distinct().forEach { append("- ").append(VaultPaths.link(it)).append('\n') }
        }
    }

    fun logLine(root: String, spec: VaultRunSpec, runPath: String, zone: ZoneId): String {
        val mark = when (spec.end) {
            VaultOutcome.Success -> "成功"
            VaultOutcome.Failure -> "失败"
            VaultOutcome.Note -> "备注"
            VaultOutcome.Stopped -> "停下"
        }
        val title = spec.goal.lineSequence().first().take(24)
        val written = spec.writtenPaths.firstOrNull()?.let { " → " + VaultPaths.link(it) }.orEmpty()
        return "- ${stamp(spec.startedAt, zone)} $mark ${VaultPaths.link(runPath, title)}$written"
    }

    fun indexInner(apps: List<VaultNote>, tasks: List<VaultNote>, days: List<String>, root: String): String = buildString {
        append("## 应用\n")
        if (apps.isEmpty()) append("- (还没有)\n") else apps.forEach { append("- ").append(VaultPaths.link(it.path, it.title)).append('\n') }
        append("\n## 任务\n")
        if (tasks.isEmpty()) append("- (还没有)\n") else tasks.forEach { append("- ").append(VaultPaths.link(it.path, it.title)).append('\n') }
        append("\n## 运行\n")
        if (days.isEmpty()) append("- (还没有)\n")
        else days.forEach { append("- ").append(VaultPaths.link(VaultPaths.dayFile(root, it), it)).append('\n') }
    }

    fun homeInner(recent: List<String>, apps: List<String>, learnings: List<String>): String = buildString {
        append("## 最近的运行\n")
        if (recent.isEmpty()) append("- (还没有)\n") else recent.forEach { append("- ").append(it).append('\n') }
        append("\n## 常用的 App\n")
        if (apps.isEmpty()) append("- (还没有)\n") else apps.forEach { append("- ").append(it).append('\n') }
        append("\n## 最近记下的做法\n")
        if (learnings.isEmpty()) append("- (还没有)\n") else learnings.forEach { append("- ").append(it).append('\n') }
    }

    /** 标记里面我们自己的「- 20」开头的行只留最新的 [keep] 条,其余行(主人写的)都留。 */
    fun capBullets(inner: String, keep: Int): String {
        val lines = inner.lines()
        val datedIdx = lines.indices.filter { lines[it].trimStart().startsWith("- 20") }
        val drop = (datedIdx.size - keep).coerceAtLeast(0)
        if (drop == 0) return inner.trim()
        val doomed = datedIdx.takeLast(drop).toSet()
        return lines.filterIndexed { i, _ -> i !in doomed }.joinToString("\n").trim()
    }
}
