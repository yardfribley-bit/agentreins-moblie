package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.ModelRole
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class OpenRouterDefaultsTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun provisionsRolesAndPersistsEncryptedKeyWithoutDuplicates() {
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = AesGcmKeyCipher { aes }
        val store = ProviderStore(tmp.root, cipher)
        OpenRouterDefaults.install(store, "dummy-test-credential")
        OpenRouterDefaults.install(store, "dummy-test-credential")
        assertEquals(1, store.providers.value.size)
        val main = store.configFor(ModelRole.Main)!!
        assertEquals(OpenRouterDefaults.MAIN_MODEL, main.model)
        assertEquals(OpenRouterDefaults.BASE_URL, main.baseUrl)
        assertTrue(main.capabilities.toolCalls); assertTrue(main.capabilities.vision)
        assertEquals(main.id, store.configFor(ModelRole.Vision)!!.id)
        assertEquals(main.id, store.configFor(ModelRole.Light)!!.id)
        val restored = ProviderStore(tmp.root, cipher)
        assertEquals("dummy-test-credential", restored.apiKey(main.id))
        tmp.root.listFiles()!!.forEach { assertFalse(it.readText().contains("dummy-test-credential")) }
        assertEquals(OpenRouterDefaults.TRANSCRIPTION_URL, OpenRouterDefaults.BASE_URL + "/audio/transcriptions")
    }
}
