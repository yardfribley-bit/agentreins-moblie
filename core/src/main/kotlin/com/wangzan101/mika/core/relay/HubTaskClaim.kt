package com.wangzan101.mika.core.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 以后从固定 Hub 会话里认领任务时用的判断。现在没有执行器:
 * [decide] 在开关关上时直接拒绝,打开了也只说明还没实现,不会自己跑远程任务。
 * 付款、红包、转账、删除、对外发消息、输入密码即使以后做了,也必须先在手机上让用户确认。
 * 只接受这个会话的所有者下的指令。
 */
enum class HubSensitiveAction {
    None,
    Payment,
    RedPacket,
    Transfer,
    Deletion,
    ExternalMessage,
    PasswordEntry,
}

data class HubRemoteInstruction(
    val sessionId: String,
    val fromOwner: Boolean,
    val action: HubSensitiveAction,
)

sealed interface HubClaimDecision {
    data object Disabled : HubClaimDecision
    data object IgnoredNotOwner : HubClaimDecision
    data class NeedsUserConfirmation(val action: HubSensitiveAction) : HubClaimDecision
    data object NotImplemented : HubClaimDecision
}

object HubTaskClaim {
    fun decide(claimEnabled: Boolean, instruction: HubRemoteInstruction): HubClaimDecision {
        if (!claimEnabled) return HubClaimDecision.Disabled
        if (!instruction.fromOwner) return HubClaimDecision.IgnoredNotOwner
        if (instruction.action != HubSensitiveAction.None) return HubClaimDecision.NeedsUserConfirmation(instruction.action)
        return HubClaimDecision.NotImplemented
    }
}

/** `diaodu_relay_read` 的一页。认领保持关闭时不取指令。 */
data class HubRelayEvent(
    val seq: Long,
    val envelopeKind: String,
    val fromOwner: Boolean,
    val messageKind: String? = null,
)

data class HubRelayPage(
    val events: List<HubRelayEvent>,
    val nextCursor: Long,
    val hasMore: Boolean,
)

object HubInstructionFilter {
    const val MAX_WAIT_SECONDS = 20

    fun waitSeconds(requested: Int): Int = requested.coerceIn(0, MAX_WAIT_SECONDS)

    /** 只收创建者下的指令。进度、结果、别人的话都丢掉。 */
    fun accept(event: HubRelayEvent): Boolean =
        event.envelopeKind == "message" && event.messageKind == "instruction" && event.fromOwner

    /** 开关关着时一页都不收,避免把远程任务跑起来。 */
    fun take(claimEnabled: Boolean, page: HubRelayPage): List<HubRelayEvent> {
        if (!claimEnabled) return emptyList()
        return page.events.filter(::accept)
    }

    fun parse(obj: JsonObject): HubRelayPage {
        val events = (obj["events"] as? JsonArray).orEmpty().mapNotNull { item ->
            val row = item as? JsonObject ?: return@mapNotNull null
            val seq = (row["seq"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val kind = (row["kind"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val fromOwner = (row["from_owner"] as? JsonPrimitive)?.booleanOrNull == true
            val messageKind = ((row["message"] as? JsonObject)?.get("kind") as? JsonPrimitive)?.contentOrNull
            HubRelayEvent(seq, kind, fromOwner, messageKind)
        }
        val cursor = (obj["next_cursor"] as? JsonPrimitive)?.longOrNull ?: events.lastOrNull()?.seq ?: 0L
        val more = (obj["has_more"] as? JsonPrimitive)?.booleanOrNull == true
        return HubRelayPage(events, cursor, more)
    }
}

/** 用上一页的 [HubRelayPage.nextCursor] 当下一次的 after。 */
class HubRelayCursor(var after: Long = 0) {
    fun advance(claimEnabled: Boolean, page: HubRelayPage): List<HubRelayEvent> {
        after = page.nextCursor
        return HubInstructionFilter.take(claimEnabled, page)
    }
}
