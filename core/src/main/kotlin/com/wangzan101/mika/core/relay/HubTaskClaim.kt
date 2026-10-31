package com.wangzan101.mika.core.relay

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
