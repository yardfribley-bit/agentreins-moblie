package com.wangzan101.mika.vault

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class VaultTouchStoreTest {
    @Test fun forRunSplitsReadAndWrite() {
        val file = File.createTempFile("vault-touches", ".json")
        val store = VaultTouchStore(file)
        store.markRead(listOf("wiki/a.md", "wiki/b.md"), "r1", 1)
        store.markWrite(listOf("wiki/b.md"), "r1", 2)
        store.markRead(listOf("wiki/c.md"), "r2", 3)
        val (read, wrote) = store.forRun("r1")
        assertEquals(setOf("wiki/a.md", "wiki/b.md"), read.toSet())
        assertEquals(listOf("wiki/b.md"), wrote)
        assertEquals(listOf("wiki/c.md"), store.forRun("r2").first)
    }
}
