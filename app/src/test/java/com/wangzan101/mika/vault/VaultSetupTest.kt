package com.wangzan101.mika.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultSetupTest {
    @Test fun plainFolderIsCreatedForANewUser() {
        assertEquals("md.obsidian", VaultSetup.OBSIDIAN_PACKAGE)
        assertEquals("primary:Documents/AgentReins知识库", VaultSetup.plainFolderDocumentId())
        assertFalse(VaultSetup.plainFolderDocumentId().contains(".."))
        assertFalse(VaultSetup.PLAIN_FOLDER.contains("/"))
    }
}
