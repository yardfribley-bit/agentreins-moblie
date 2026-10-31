package com.wangzan101.mika.core.memory

import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.skills.SkillRedactor
import kotlinx.serialization.Serializable

/** 一次任务的短摘要,下次按用词对上后再注入。不含密码和金额。 */
@Serializable
data class TaskNote(
    val runId: String,
    val goal: String,
    val outcome: String,
    val stuck: String? = null,
    val steps: String = "",
    val atMs: Long = 0,
)

interface TaskNotePersistence {
    fun load(): List<TaskNote>
    fun save(items: List<TaskNote>)
}

class TaskNoteBook(private val persistence: TaskNotePersistence, private val limit: Int = 40) {
    private val lock = Any()
    private var items = runCatching { persistence.load() }.getOrDefault(emptyList())

    fun all(): List<TaskNote> = synchronized(lock) { items.toList() }

    fun add(note: TaskNote) = synchronized(lock) {
        val next = (listOf(note) + items.filter { it.runId != note.runId }).take(limit)
        persistence.save(next)
        items = next
    }

    fun clear() = synchronized(lock) {
        persistence.save(emptyList())
        items = emptyList()
    }

    fun search(query: String, limit: Int = 3): List<TaskNote> = synchronized(lock) {
        val q = terms(query)
        if (q.isEmpty()) return emptyList()
        items.mapNotNull { n ->
            val doc = terms(n.goal + " " + n.steps).toSet()
            val hit = q.count { it in doc }
            if (hit == 0) null else n to hit.toDouble() / q.size
        }.filter { it.second >= 0.34 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private fun terms(text: String) = Tokenize.terms(text).filter { it.length >= 2 && it !in STOP }

    companion object {
        private val STOP = setOf("帮我", "给我", "一下", "我的", "这个", "那个", "可以", "看看")
    }
}

object TaskRecall {
    const val MAX_CHARS = 600

    fun summarize(runId: String, goal: String, events: List<RunEvent>, atMs: Long): TaskNote {
        val ended = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()
        val outcome = clip(ended?.let { "${it.outcome}: ${it.answerOrReason}" } ?: "unknown")
        val stuck = events.filterIsInstance<RunEvent.Barrier>().lastOrNull()?.kind
            ?: events.filterIsInstance<RunEvent.Activity>().lastOrNull { it.failed }?.let { listOfNotNull(it.label, it.target).joinToString(" ") }
        val steps = events.filterIsInstance<RunEvent.Activity>().filter { !it.failed }
            .map { it.label }.distinct().take(6).joinToString(", ")
        return TaskNote(runId, clip(goal), outcome, stuck?.let(::clip), clip(steps, 120), atMs)
    }

    fun render(notes: List<TaskNote>): String? {
        if (notes.isEmpty()) return null
        val body = notes.joinToString("\n") { n ->
            val stuck = n.stuck?.let { "; stuck: $it" }.orEmpty()
            val steps = n.steps.takeIf { it.isNotBlank() }?.let { "; steps: $it" }.orEmpty()
            "- ${n.goal} → ${n.outcome}$stuck$steps"
        }
        val text = "Earlier tasks (may be out of date; what the user says now wins):\n$body"
        return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS - 1) + "…"
    }

    private fun clip(raw: String, max: Int = 80): String {
        val scrubbed = SkillRedactor.scrubNumbers(Redactor.text(raw), emptySet())
        val one = scrubbed.replace('\n', ' ').trim()
        return if (one.length <= max) one else one.take(max) + "…"
    }
}
