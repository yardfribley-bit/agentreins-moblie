package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role

/**
 * 一轮对话:用户说了什么、结果如何。[outcome] 是成功时的答案或失败原因;
 * [status] 为 succeeded / unverified(办完了但没能核实,[note] 是原因,[unconfirmed] 是标成未确认的几处)/ failed / cancelled。
 * [runId] 是这轮的运行记录;没开起记录或更早的轮次为 null。
 */
/**
 * [memoryNotes]/[usedMemory]/[skillNotes]:结果卡片下的记忆与技能提示,任务结束后补上;不进给模型的上文。
 * [result]:交出回答后判的结局([RunOutcome.wire]),后台补上;没判过为 null。
 */
data class Turn(
    val userText: String, val outcome: String, val status: String, val note: String? = null, val runId: String? = null,
    val memoryNotes: List<com.wangzan101.mika.core.memory.MemoryNote> = emptyList(),
    val usedMemory: List<String> = emptyList(),
    val skillNotes: List<com.wangzan101.mika.core.skills.SkillNote> = emptyList(),
    val unconfirmed: List<String> = emptyList(),
    val result: String? = null,
    /** 任务进行中用户插的、已交给这个任务的话(界面在那一轮下面显示)。 */
    val steers: List<String> = emptyList(),
    /** 这一轮真的用过的能力组(手机、网页、沙箱……);下一轮开局预先展开,不进给模型的上文。 */
    val groups: List<String> = emptyList(),
    /**
     * 这一轮工具结果里下一轮还用得着的原文(讲话、读到的正文、监听状态)。
     * 最终回答没复述的事实也在这里,追问才能接着用。
     */
    val findings: List<String> = emptyList(),
)

object Conversation {
    const val HEADER = "Earlier turns of this conversation. The latest message is the current instruction; these turns and their tool results still apply. If a tool result and the previous answer disagree, trust the tool result. Resolve 这个, 刚才, 继续, this, that and continue from them. Do not invent a limit the user did not state. A fresh read wins if it conflicts."

    /** 新的一句是在接着上一轮做,而不是另起一件事。 */
    const val FOLLOW_UP = "This message continues the previous task. Carry forward its goal and the tool results below; do not treat it as a new request."

    /** 停止的那一轮还没有交代(或没什么可交代)时记下的话。 */
    const val CANCELLED_OUTCOME = "已取消"

    /** 停止的那一轮补上的交代;没有时为 null。 */
    fun stopSummary(t: Turn): String? =
        t.outcome.trim().takeIf { t.status == "cancelled" && it.isNotEmpty() && it != CANCELLED_OUTCOME }

    /**
     * 把之前的轮次做成一条用户角色的参考消息,最近的在最后;太长就丢掉较早的轮次。没有轮次返回 null。
     * [next] 是这一轮用户刚说的话:像「继续 / 刚才」时标明这是在接着上一轮做。
     */
    fun context(turns: List<Turn>, maxTurns: Int = 8, maxChars: Int = 8_000, next: String? = null): Message? {
        var recent = turns.takeLast(maxTurns.coerceAtLeast(0))
        if (recent.isEmpty()) return null
        val head = header(next)
        fun render(ts: List<Turn>, clip: Int) = ts.mapIndexed { i, t -> line(i + 1, t, clip) }.joinToString("\n")
        while (recent.size > 1 && head.length + 1 + render(recent, Int.MAX_VALUE).length > maxChars) recent = recent.drop(1)
        var body = render(recent, Int.MAX_VALUE)
        if (head.length + 1 + body.length > maxChars) {
            // 只剩一轮还超长:各字段截断
            val slots = 3 + recent.sumOf { it.findings.size.coerceAtMost(3) }
            body = render(recent, ((maxChars - head.length) / slots).coerceAtLeast(20))
        }
        var text = "$head\n$body"
        if (text.length > maxChars) text = text.take((maxChars - 1).coerceAtLeast(0)) + "…"
        return Message(Role.User, listOf(Part.Text(text)), Author.Mika)
    }

    /** 核对回答时,之前几轮里用户说的、答过的、工具留下的原文都算读到过。 */
    fun evidence(turns: List<Turn>): List<String> =
        turns.takeLast(3).flatMap { t -> (listOf(t.userText, t.outcome) + t.steers + t.findings).map { it.trim() }.filter { it.isNotEmpty() } }
            .map { if (it.length <= 2_000) it else it.take(2_000) }

    /** 短的承接说法(继续、刚才、keep listening)。单独的「这个」不算,免得把新任务当成追问。 */
    fun continues(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return false
        return STRONG_FOLLOW_UP.any { it in t }
    }

    private fun header(next: String?) = if (next != null && continues(next)) "$HEADER\n$FOLLOW_UP" else HEADER

    private fun line(n: Int, t: Turn, clip: Int): String {
        // 回答如实说没办成或只办了一部分:按交出后判的结局说,下一轮别当它办成了
        val answered = t.status == "succeeded" || t.status == "unverified"
        val result = when {
            answered && t.result == RunOutcome.Partial.wire -> "only partly done: ${t.outcome.clip(clip)}"
            answered && t.result == RunOutcome.NotPossible.wire -> "not done: ${t.outcome.clip(clip)}"
            t.status == "succeeded" -> "result: ${t.outcome.clip(clip)}"
            t.status == "unverified" -> "result (could not be verified): ${t.outcome.clip(clip)}"
            // 停止后补上的交代(做过什么、哪些没做完)带上,下一句「继续」才知道从哪接着做;旧记录里只有「已取消」
            t.status == "cancelled" -> stopSummary(t)?.let { "the user stopped this task partway; before the stop: ${it.clip(clip)}" } ?: "the user cancelled this task"
            else -> "not finished, reason: ${t.outcome.clip(clip)}"
        }
        val base = "$n. The user said: ${t.userText.clip(clip)}; $result"
        if (t.findings.isEmpty()) return base
        val facts = t.findings.take(3).joinToString("\n") { "   tool result: ${it.clip(clip)}" }
        return "$base\n$facts"
    }

    private fun String.clip(max: Int) = if (length <= max) this else take(max) + "…"

    private val STRONG_FOLLOW_UP = listOf(
        "继续", "接着", "刚才", "刚刚", "再听", "再说",
        "keep listening", "keep going", "what did they", "what did he", "what did she", "go on", "continue",
    )
}
