package com.wangzan101.mika.audio

/**
 * How long one in-memory listening session may run.
 * The two-minute idle applies only after a task has claimed the session. Before that,
 * the user is often still in Douyin getting the chat agent attached; stopping at two
 * minutes is what left the panel showing speech the tool then reported as not listening.
 * New transcripts are not reads: once a task owns the session, only its snapshots refresh
 * the idle clock. The one-hour cap and an explicit stop always end capture.
 */
internal object LiveAudioWatch {
    const val IDLE_MS = 120_000L
    const val MAX_MS = 3_600_000L

    const val NEVER_STARTED = "never_started"
    const val USER_STOPPED = "user_stopped"
    const val IDLE_TIMEOUT = "idle_timeout"
    const val MAX_DURATION = "max_duration"
    const val ASR_ERROR = "asr_error"
    const val START_FAILED = "start_failed"
    const val CAPTURE_REVOKED = "capture_revoked"
    const val CAPTURE_ERROR = "capture_error"
    const val TASK_ENDED = "task_ended"

    /** Null while the session may keep running. */
    fun limit(now: Long, startedAt: Long, lastRead: Long, claimed: Boolean): String? {
        if (now - startedAt > MAX_MS) return MAX_DURATION
        if (claimed && now - lastRead > IDLE_MS) return IDLE_TIMEOUT
        return null
    }

    fun message(reason: String): String = when (reason) {
        IDLE_TIMEOUT -> "监听已结束：任务超过两分钟没有读取。请重新打开「设置 → 干活时 → 直播监听」再开始"
        MAX_DURATION -> "监听已结束：已达一小时上限。请重新打开「设置 → 干活时 → 直播监听」再开始"
        else -> "监听已结束"
    }
}
