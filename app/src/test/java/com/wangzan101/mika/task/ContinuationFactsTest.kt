package com.wangzan101.mika.task

import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assert.*
import org.junit.Test

class ContinuationFactsTest {
    @Test fun onlySuccessfulVaultWritesAreCarriedForward() {
        fun call(step: Int) = RunEvent.ModelReply(0, step, "", "vault_work_write", """{"note_id":"article","part_id":"0$step"}""", null, null, null)
        fun result(step: Int, ok: Boolean) = RunEvent.ToolResult(0, step, "vault_work_write", """{"ok":$ok}""", null, null)
        val facts = continuationFactsFrom(listOf(call(1), result(1, true), call(2), result(2, false), call(3)))
        assertTrue(facts.any { it.contains("part_id=01") })
        assertFalse(facts.any { it.contains("part_id=02") || it.contains("part_id=03") })
    }
}
