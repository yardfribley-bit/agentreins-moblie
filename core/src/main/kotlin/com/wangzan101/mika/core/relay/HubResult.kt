package com.wangzan101.mika.core.relay

import kotlinx.serialization.Serializable

/** 一条 Hub 指令在手机上的终态。 [Question] 用 question 而不是 result 发回。 */
enum class HubRunOutcome { Succeeded, Failed, NeedsInput, Interrupted, Question }

enum class HubTerminal { Succeeded, Failed, Cancelled }

@Serializable
data class HubInflight(
    val seq: Long,
    val requestId: String? = null,
    val resultNote: String? = null,
    val runKey: String? = null,
    /** 已经组好、还没确认送到 Hub 的正文。进程被杀后按原文重发。 */
    val pendingText: String? = null,
    val pendingKind: String? = null,
)

object HubResultText {
    private val SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$")
    const val INTERRUPTED = "运行被中断了，没有做完。可以再发一次指令。"

    fun isSafe(raw: String?): Boolean = raw != null && SAFE.matches(raw.trim())

    /** 指令自带的 request_id 原样用；没有或不合规则时用稳定的 hub-result:<seq>。 */
    fun requestId(raw: String?, seq: Long): String {
        val id = raw?.trim().orEmpty()
        return if (SAFE.matches(id)) id else "hub-result:$seq"
    }

    fun outcome(terminal: HubTerminal, body: String, question: String? = null): HubRunOutcome {
        if (!question.isNullOrBlank()) return HubRunOutcome.Question
        if (terminal == HubTerminal.Cancelled) return HubRunOutcome.Interrupted
        if (needsInput(body)) return HubRunOutcome.NeedsInput
        return if (terminal == HubTerminal.Succeeded) HubRunOutcome.Succeeded else HubRunOutcome.Failed
    }

    fun kind(outcome: HubRunOutcome): String =
        if (outcome == HubRunOutcome.Question) RelayKind.QUESTION else RelayKind.RESULT

    fun body(outcome: HubRunOutcome, text: String, requestId: String, note: String? = null): String {
        val status = when (outcome) {
            HubRunOutcome.Succeeded -> "成功"
            HubRunOutcome.Failed -> "失败"
            HubRunOutcome.NeedsInput -> "需要补充"
            HubRunOutcome.Interrupted -> "中断"
            HubRunOutcome.Question -> "需要确认"
        }
        val head = status + "\n" + text.trim()
        val withId = if (requestId in head) head else head + "\nrequest_id: " + requestId
        return if (note.isNullOrBlank()) RelayEventText.clip(withId) else HubAttachmentText.appendResult(withId, note)
    }

    fun withRequestId(text: String, requestId: String?): String {
        if (requestId.isNullOrBlank() || requestId in text) return text
        return HubAttachmentText.appendResult(text, "request_id: $requestId")
    }

    private fun needsInput(body: String): Boolean {
        val folded = body.lowercase()
        val phrases = listOf(
            "请再发", "请重新发", "重新发送", "再发一", "发给我",
            "please resend", "please send", "resend the", "send me the", "need the image", "need the file",
        )
        return phrases.any { it in body || it in folded }
    }
}

object HubInflightCodec {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(item: HubInflight): String = json.encodeToString(HubInflight.serializer(), item)

    fun decode(raw: String): HubInflight? =
        runCatching { json.decodeFromString(HubInflight.serializer(), raw) }.getOrNull()?.takeIf { it.seq > 0 }
}

/** https 链接出现在老板指令里时，告诉模型先下载，不要当成文件已经在手机上。 */
object HubInstructionUrls {
    private val URL = Regex("""https://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
    private const val HINT =
        "https URLs in this instruction are not on the phone yet. Call download_url_to_workspace for each one before searching the workspace or saying the file is missing. The tool result names the workspace path under /workspace/inbox and, for an image or video, the gallery path under Pictures/AgentReins or Movies/AgentReins."

    fun hasHttps(text: String): Boolean = URL.containsMatchIn(text)

    fun append(goal: String, source: String = goal): String {
        if (!hasHttps(source) || "download_url_to_workspace" in goal) return goal
        return goal.trimEnd() + "\n\n" + HINT
    }
}
