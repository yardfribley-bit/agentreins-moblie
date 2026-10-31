package com.wangzan101.mika.core.relay

/** 一条要在手机上执行的所有者指令。 */
data class HubInstruction(val seq: Long, val text: String)

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
            else HubInstruction(event.seq, event.text!!.trim())
        }
        return HubInboundPlan(fresh.maxOfOrNull { it.seq }, fresh, page.nextCursor)
    }

    fun encodeQueue(items: List<HubInstruction>): String =
        items.joinToString("\n") { "${it.seq}\u001e${it.text.replace("\\", "\\\\").replace("\n", "\\n")}" }

    fun decodeQueue(raw: String): List<HubInstruction> =
        raw.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val sep = line.indexOf('\u001e')
            if (sep <= 0) return@mapNotNull null
            val seq = line.substring(0, sep).toLongOrNull() ?: return@mapNotNull null
            val text = line.substring(sep + 1).replace("\\n", "\n").replace("\\\\", "\\")
            if (text.isBlank()) null else HubInstruction(seq, text)
        }.toList()

    /** 按序号去重后追加。 */
    fun enqueue(queue: List<HubInstruction>, fresh: List<HubInstruction>): List<HubInstruction> {
        if (fresh.isEmpty()) return queue
        val seen = queue.map { it.seq }.toMutableSet()
        val out = queue.toMutableList()
        for (item in fresh) if (seen.add(item.seq)) out += item
        return out
    }
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
}
