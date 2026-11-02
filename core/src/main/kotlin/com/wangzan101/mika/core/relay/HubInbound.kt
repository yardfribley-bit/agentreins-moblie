package com.wangzan101.mika.core.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 一条要在手机上执行的所有者指令。附件在真正开跑之前才下载。 */
data class HubInstruction(
    val seq: Long,
    val text: String,
    val attachments: List<HubAttachmentRef> = emptyList(),
)

/** Hub 事件上的一个附件。没有签名链接：链接会过期，执行时再向 Hub 要字节。 */
@Serializable
data class HubAttachmentRef(
    val fileId: String,
    val name: String? = null,
    val size: Long? = null,
    val mime: String? = null,
    val sha256: String? = null,
)

/**
 * 读到一页之后要做的事:确认到哪一号(待确认计数靠 `diaodu_relay_ack`),
 * 新指令,以及要记住的 `next_cursor`(重启不重放、也不跳过)。
 */
data class HubInboundPlan(
    val ackSeq: Long?,
    val fresh: List<HubInstruction>,
    val cursor: Long,
)

object HubInbound {
    /**
     * [handled] 是已经收下的最大指令序号。小于等于它的不再入队。
     * 有新指令时 [HubInboundPlan.ackSeq] 是其中最大的 seq,交给 `diaodu_relay_ack`。
     */
    fun plan(page: HubRelayPage, handled: Long): HubInboundPlan {
        val fresh = page.events.mapNotNull { event ->
            if (!HubInstructionFilter.accept(event) || event.seq <= handled) null
            else HubInstruction(event.seq, event.text!!.trim(), event.attachments)
        }
        return HubInboundPlan(fresh.maxOfOrNull { it.seq }, fresh, page.nextCursor)
    }

    fun encodeQueue(items: List<HubInstruction>): String =
        items.joinToString("\n") { item ->
            if (item.attachments.isEmpty()) {
                "${item.seq}\u001e${escape(item.text)}"
            } else {
                json.encodeToString(QueueLine.serializer(), QueueLine(item.seq, item.text, item.attachments))
            }
        }

    fun decodeQueue(raw: String): List<HubInstruction> =
        raw.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            if (line.startsWith("{")) {
                val row = runCatching { json.decodeFromString(QueueLine.serializer(), line) }.getOrNull()
                    ?: return@mapNotNull null
                if (row.text.isBlank()) null else HubInstruction(row.seq, row.text, row.attachments)
            } else {
                val sep = line.indexOf('\u001e')
                if (sep <= 0) return@mapNotNull null
                val seq = line.substring(0, sep).toLongOrNull() ?: return@mapNotNull null
                val text = unescape(line.substring(sep + 1))
                if (text.isBlank()) null else HubInstruction(seq, text)
            }
        }.toList()

    private fun escape(text: String) = text.replace("\\", "\\\\").replace("\n", "\\n")

    private fun unescape(text: String) = text.replace("\\n", "\n").replace("\\\\", "\\")

    /** 按序号去重后追加。 */
    fun enqueue(queue: List<HubInstruction>, fresh: List<HubInstruction>): List<HubInstruction> {
        if (fresh.isEmpty()) return queue
        val seen = queue.map { it.seq }.toMutableSet()
        val out = queue.toMutableList()
        for (item in fresh) if (seen.add(item.seq)) out += item
        return out
    }

    @Serializable
    private data class QueueLine(val seq: Long, val text: String, val attachments: List<HubAttachmentRef> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
}

/**
 * 有任务在跑时把指令留在队列里。开始后要看到忙过再结束,才取下一条;
 * 一直没忙起来就过 [stallMs] 把这一条放回队首,避免卡死。
 */
class HubRunGate(private val stallMs: Long = 20_000) {
    var inflight: HubInstruction? = null
        private set
    private var sawBusy = false
    private var startedAt = 0L

    fun poll(now: Long, busy: Boolean, queue: List<HubInstruction>): Pair<List<HubInstruction>, HubInstruction?> {
        val current = inflight
        if (current != null) {
            if (busy) {
                sawBusy = true
                return queue to null
            }
            if (sawBusy) {
                inflight = null
                sawBusy = false
            } else if (now - startedAt >= stallMs) {
                inflight = null
                sawBusy = false
                return HubInbound.enqueue(listOf(current), queue) to null
            } else {
                return queue to null
            }
        }
        if (busy || queue.isEmpty()) return queue to null
        val next = queue.first()
        inflight = next
        startedAt = now
        sawBusy = false
        return queue.drop(1) to next
    }

    /**
     * 下载附件可能超过 [stallMs]。还没看见任务忙起来时，把计时拨到 [now]，
     * 避免附件刚下完、任务刚启动就被当成卡住而重新入队。
     */
    fun noteStarting(now: Long) {
        if (inflight != null && !sawBusy) startedAt = now
    }
}
