package com.wangzan101.mika.core.memory

import com.wangzan101.mika.core.record.Redactor

/**
 * 任务结束后,把稳定的偏好写进已有的记忆库(不另开一份)。
 * 用户能在记忆页里看到、改、删。已有相同内容的不再写。
 */
object PreferenceLearner {
    private val LASTING = Regex("以后|记住|总是|每次|不要再|always|never|prefer", RegexOption.IGNORE_CASE)
    private val STYLE = Regex(
        "简短|详细|用中文|中文回复|别用表情|不要表情|正式一点|短回复|short repl|in chinese|in english|reply in|replies in",
        RegexOption.IGNORE_CASE,
    )
    private val PATH = Regex("记住这个做法|记住做法|以后都这样做")
    private val PACKAGE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")

    /** 回答风格和语言对每一句都生效,不靠和当前任务撞词。已置顶的、以及以前没置顶的都算。 */
    fun isAlwaysOn(item: MemoryItem): Boolean {
        if (item.kind != MemoryKind.preference || item.status != MemoryStatus.active) return false
        return item.content.startsWith("回答风格:") || STYLE.containsMatchIn(item.content)
    }

    fun candidates(goal: String, utterances: List<String>, apps: List<String>): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (said in utterances.map { it.trim() }.filter { it.length in 4..160 }) {
            if (PATH.containsMatchIn(said)) continue
            val style = STYLE.containsMatchIn(said)
            if (!style && !LASTING.containsMatchIn(said)) continue
            val text = Redactor.text(said).trim()
            if (SensitiveFilter.hit(text) != null) continue
            val words = Tokenize.terms(text).filter { it.length >= 2 }.distinct().take(5)
            out += Candidate(if (style) "回答风格: $text" else text, words, said)
        }
        apps.map { it.trim() }.filter { it.length in 2..40 && !PACKAGE.matches(it) }.distinct().take(2).forEach { app ->
            if (SensitiveFilter.hit(app) != null) return@forEach
            out += Candidate("常用: $app", listOf(app), goal.trim().take(160))
        }
        return out.distinctBy { norm(it.content) }.take(3)
    }

    /** 写入 [store];重复的跳过。风格和语言会置顶。返回新记下的。 */
    fun remember(store: MemoryStore, goal: String, utterances: List<String>, apps: List<String>, atMs: Long, conversationId: String?): List<Change> {
        if (!store.available && !store.reloadIfNeeded()) return emptyList()
        val have = store.items.value.filter { it.status == MemoryStatus.active }.map { norm(it.content) }.toSet()
        val saved = ArrayList<Change>()
        for (c in candidates(goal, utterances, apps)) {
            if (norm(c.content) in have) continue
            val quote = c.quote.ifBlank { goal }.take(160)
            val change = store.add(
                MemoryKind.preference, c.content, c.keywords,
                MemorySource("user_said", quote, conversationId, atMs),
            ).getOrNull() ?: continue
            if (isAlwaysOn(change.item)) store.setPinned(change.item.id, true)
            saved += change
        }
        return saved
    }

    private fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")

    data class Candidate(val content: String, val keywords: List<String>, val quote: String)
}
