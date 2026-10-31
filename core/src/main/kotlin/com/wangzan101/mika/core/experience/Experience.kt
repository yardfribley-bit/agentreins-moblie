package com.wangzan101.mika.core.experience

import com.wangzan101.mika.core.memory.SensitiveFilter
import com.wangzan101.mika.core.memory.Tokenize
import kotlinx.serialization.Serializable

/** 一步怎么做:工具名和屏上的字,不记坐标。 */
@Serializable
data class ExperienceStep(val action: String, val target: String? = null, val where: String? = null)

/**
 * 一次办成的做法。失败过的同类任务先记在 [avoid] 里,成功后再补上 [steps]。
 * [confidence] 为 0 时不再当作推荐计划注入。
 */
@Serializable
data class Experience(
    val id: String,
    val intent: String,
    val keywords: List<String>,
    val appPackage: String? = null,
    val appLabel: String? = null,
    val steps: List<ExperienceStep> = emptyList(),
    val preconditions: List<String> = emptyList(),
    val pitfalls: List<String> = emptyList(),
    val avoid: List<String> = emptyList(),
    val confidence: Int = 1,
    val successes: Int = 0,
    val failures: Int = 0,
    val failureStreak: Int = 0,
    val updatedAt: Long = 0,
)

interface ExperiencePersistence {
    fun load(): List<Experience>
    fun save(items: List<Experience>)
}

/** 做法的唯一写入口。按意图和 App 合并,成功替换步骤并加信心,连续失败就降信心。 */
class ExperienceBook(
    private val persistence: ExperiencePersistence,
    private val ids: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limit: Int = 80,
) {
    private val lock = Any()
    private var items = runCatching { persistence.load() }.getOrDefault(emptyList())

    fun all(): List<Experience> = synchronized(lock) { items.toList() }

    fun delete(id: String) = synchronized(lock) { commit(items.filter { it.id != id }) }

    fun clear() = synchronized(lock) { commit(emptyList()) }

    fun update(id: String, transform: (Experience) -> Experience): Experience? = synchronized(lock) {
        val cur = items.find { it.id == id } ?: return null
        val next = transform(cur).copy(updatedAt = clock())
        commit(items.map { if (it.id == id) next else it })
        next
    }

    /** 成功或用户要求记住时写入;失败只给已经对上的做法记坑并降信心。 */
    fun apply(draft: ExperienceDraft): Experience? = synchronized(lock) {
        if (draft.intent.isBlank() || SensitiveFilter.hit(draft.intent) != null) return null
        val match = findMatch(draft.intent, draft.appPackage, draft.appLabel)
        val now = clock()
        if (!draft.keepSteps || draft.steps.isEmpty()) {
            val extra = draft.avoid
            if (match == null) {
                if (extra.isEmpty()) return null
                return insert(blank(draft, now).copy(avoid = extra, confidence = 0, failures = 1, failureStreak = 1))
            }
            return insert(downgrade(match, extra, now), replacing = match.id)
        }
        val saved = if (match == null) fresh(draft, now) else promote(match, draft, now)
        insert(saved, replacing = match?.id)
    }

    /** 注入用:有步骤、信心还在、和这句话对得上的几条。 */
    fun search(query: String, limit: Int = 2): List<Experience> = synchronized(lock) {
        items.mapNotNull { e -> score(query, e).takeIf { it >= MIN_SCORE }?.let { e to it } }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun insert(item: Experience, replacing: String? = null): Experience {
        val rest = items.filter { it.id != replacing && it.id != item.id }
        val next = (listOf(item) + rest).take(limit)
        commit(next)
        return item
    }

    private fun commit(next: List<Experience>) {
        persistence.save(next)
        items = next
    }

    private fun blank(draft: ExperienceDraft, now: Long) = Experience(
        id = ids(), intent = draft.intent, keywords = draft.keywords, appPackage = draft.appPackage, appLabel = draft.appLabel,
        preconditions = draft.preconditions, pitfalls = draft.pitfalls, updatedAt = now,
    )

    private fun fresh(draft: ExperienceDraft, now: Long) = blank(draft, now).copy(
        steps = draft.steps, avoid = draft.avoid, confidence = 1, successes = 1,
    )

    private fun promote(prev: Experience, draft: ExperienceDraft, now: Long) = prev.copy(
        intent = draft.intent.ifBlank { prev.intent },
        keywords = (draft.keywords + prev.keywords).distinct().take(8),
        appPackage = draft.appPackage ?: prev.appPackage,
        appLabel = draft.appLabel ?: prev.appLabel,
        steps = draft.steps,
        preconditions = (draft.preconditions + prev.preconditions).distinct().take(4),
        pitfalls = (draft.pitfalls + prev.pitfalls).distinct().take(4),
        avoid = (draft.avoid + prev.avoid).distinct().take(MAX_AVOID),
        confidence = (prev.confidence + 1).coerceAtMost(MAX_CONFIDENCE),
        successes = prev.successes + 1,
        failureStreak = 0,
        updatedAt = now,
    )

    private fun downgrade(prev: Experience, avoid: List<String>, now: Long): Experience {
        val streak = prev.failureStreak + 1
        val confidence = if (streak >= 2) (prev.confidence - 1).coerceAtLeast(0) else prev.confidence
        return prev.copy(
            avoid = (avoid + prev.avoid).distinct().take(MAX_AVOID),
            failures = prev.failures + 1,
            failureStreak = streak,
            confidence = confidence,
            updatedAt = now,
        )
    }

    private fun findMatch(intent: String, pkg: String?, label: String?): Experience? =
        items.filter { sameApp(it, pkg, label) && overlap(intent, it) >= MATCH_OVERLAP }
            .maxByOrNull { overlap(intent, it) }

    private fun sameApp(e: Experience, pkg: String?, label: String?): Boolean {
        if (pkg != null && e.appPackage != null && pkg != e.appPackage) return false
        if (label != null && e.appLabel != null && pkg == null && e.appPackage == null && label != e.appLabel) return false
        return true
    }

    private fun overlap(query: String, e: Experience): Double = score(query, e.copy(confidence = 1, steps = listOf(ExperienceStep("x"))))

    private fun score(query: String, e: Experience): Double {
        if (e.confidence <= 0 || e.steps.isEmpty()) return 0.0
        val q = terms(query)
        if (q.isEmpty()) return 0.0
        val doc = (terms(e.intent) + e.keywords).toSet()
        val hit = q.count { it in doc }
        if (hit == 0) return 0.0
        var s = hit.toDouble() / q.size
        val label = e.appLabel
        val pkg = e.appPackage
        if (label != null && label in query) s += 0.4
        else if (pkg != null && pkg in query) s += 0.4
        else if (!label.isNullOrBlank() && label !in query && !pkg.isNullOrBlank() && pkg !in query) s -= 0.15
        return s
    }

    private fun terms(text: String) = Tokenize.terms(text).filter { it !in STOP && it.length >= 2 }

    companion object {
        const val MIN_SCORE = 0.34
        private const val MATCH_OVERLAP = 0.45
        private const val MAX_CONFIDENCE = 5
        private const val MAX_AVOID = 6
        private val STOP = setOf("帮我", "给我", "一下", "我的", "这个", "那个", "可以", "一个", "今天", "现在", "什么", "怎么", "看看", "please")
    }
}

/** 从一次运行提炼出的、还没落盘的做法。 */
data class ExperienceDraft(
    val intent: String,
    val keywords: List<String>,
    val appPackage: String?,
    val appLabel: String?,
    val steps: List<ExperienceStep>,
    val preconditions: List<String>,
    val pitfalls: List<String>,
    val avoid: List<String>,
    /** 成功,或用户明确说记住这个做法:步骤可以当计划。 */
    val keepSteps: Boolean,
)
