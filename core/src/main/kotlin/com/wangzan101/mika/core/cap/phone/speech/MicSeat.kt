package com.wangzan101.mika.core.cap.phone.speech

/** Owner-authorized co-host seat. The screen taps stay with the model; this only tracks consent. */
enum class Seat { Off, Awaiting, Authorized, Held }

data class SeatResult(
    val ok: Boolean,
    val code: String?,
    val seat: Seat,
    /** The on-screen seat should be left with the existing screen tools. */
    val releaseUi: Boolean,
    val message: String,
)

class MicSeatMachine {
    var seat: Seat = Seat.Off
        private set

    fun request(allowSpeech: Boolean): SeatResult {
        if (!allowSpeech) return denied("speech_disabled", "允许出声 is off. Ask the owner to turn it on in 设置 → 干活时 → 直播出声. Do not turn it on yourself.")
        return when (seat) {
            Seat.Held -> SeatResult(true, null, seat, false, "Already on a mic seat. Speak with phone_speak, then keep listening.")
            Seat.Authorized -> SeatResult(true, null, seat, false, GUIDE_TAKE)
            Seat.Awaiting -> SeatResult(false, "awaiting_confirmation", seat, false, "Still waiting for the owner to allow taking a mic seat.")
            Seat.Off -> {
                seat = Seat.Awaiting
                SeatResult(false, "confirmation_required", seat, false, "Ask the owner on this phone before taking a mic seat. Do not tap the live room yet.")
            }
        }
    }

    fun ownerAnswer(approved: Boolean): SeatResult {
        if (seat != Seat.Awaiting) return SeatResult(seat != Seat.Off, null, seat, false, "No mic-seat confirmation is waiting.")
        return if (!approved) {
            seat = Seat.Off
            SeatResult(false, "confirmation_declined", seat, false, "The owner declined taking a mic seat. Do not tap 连麦 or 上麦.")
        } else {
            seat = Seat.Authorized
            SeatResult(true, null, seat, false, GUIDE_TAKE)
        }
    }

    fun markHeld(): SeatResult {
        if (seat != Seat.Authorized && seat != Seat.Held) {
            return denied("not_authorized", "Taking a mic seat needs the owner's confirmation first. Call phone_take_mic and wait for it.")
        }
        seat = Seat.Held
        return SeatResult(true, null, seat, false, "Mic seat is held. Speak with phone_speak, then call phone_listen_live. If hearing is silent, read the on-screen chat.")
    }

    fun release(): SeatResult {
        val wasOn = seat == Seat.Held || seat == Seat.Authorized
        seat = Seat.Off
        return SeatResult(true, null, seat, wasOn, if (wasOn) GUIDE_LEAVE else "Mic seat is already released.")
    }

    private fun denied(code: String, message: String) = SeatResult(false, code, seat, false, message)

    companion object {
        const val GUIDE_TAKE =
            "The owner allowed a mic seat. Do not use fixed coordinates. Observe the live room and tap the control whose visible label asks to join or take a mic (连麦, 申请连麦, 上麦, or the empty seat). Read the skill live-mic if the screen is unclear. After the screen shows this phone is on mic, call phone_take_mic with seated=true."
        const val GUIDE_LEAVE =
            "Leave the mic seat with the on-screen control (下麦, 结束连麦, 挂断, or leaving the seat). Find it by its label from the current screen. Do not use fixed coordinates. Then keep listening or stop, as the owner asked."
    }
}
