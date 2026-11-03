package com.wangzan101.mika.core.cap.phone.speech

/** Where the speaking turn is. [Stopped] is what a stop event produces; the next [FloorEvent.Start] may speak again. */
enum class Floor { Idle, Speaking, Yielded, Stopped }

enum class FloorEvent {
    /** Begin a new utterance. */
    Start,
    /** Playback of this utterance drained on its own. */
    Finished,
    /** Owner voice or text command: stop audio, keep the mic seat. */
    OwnerStop,
    /** Notification or capsule tap: stop audio and release the mic seat. */
    TapStop,
    /** Someone else is talking on a capture path that does not contain our own playback. */
    OthersTalking,
}

data class FloorStep(val floor: Floor, val stopAudio: Boolean, val releaseMic: Boolean)

object FloorMachine {
    fun reduce(floor: Floor, event: FloorEvent): FloorStep = when (event) {
        FloorEvent.Start -> FloorStep(Floor.Speaking, stopAudio = false, releaseMic = false)
        FloorEvent.Finished -> when (floor) {
            Floor.Speaking, Floor.Yielded, Floor.Stopped -> FloorStep(Floor.Idle, stopAudio = false, releaseMic = false)
            Floor.Idle -> FloorStep(Floor.Idle, stopAudio = false, releaseMic = false)
        }
        FloorEvent.OwnerStop -> if (floor == Floor.Speaking || floor == Floor.Yielded) {
            FloorStep(Floor.Stopped, stopAudio = true, releaseMic = false)
        } else FloorStep(floor, stopAudio = false, releaseMic = false)
        FloorEvent.TapStop -> FloorStep(
            floor = if (floor == Floor.Speaking || floor == Floor.Yielded) Floor.Stopped else floor,
            stopAudio = floor == Floor.Speaking || floor == Floor.Yielded,
            releaseMic = true,
        )
        FloorEvent.OthersTalking -> if (floor == Floor.Speaking) {
            FloorStep(Floor.Yielded, stopAudio = true, releaseMic = false)
        } else FloorStep(floor, stopAudio = false, releaseMic = false)
    }
}

/** One speaking session's floor. The audio player applies [FloorStep.stopAudio] and [FloorStep.releaseMic]. */
class FloorSession {
    var floor: Floor = Floor.Idle
        private set

    fun on(event: FloorEvent): FloorStep {
        val step = FloorMachine.reduce(floor, event)
        floor = step.floor
        return step
    }
}
