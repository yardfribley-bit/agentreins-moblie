package com.wangzan101.mika.core.cap.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrConfigTest {
    private val openAi = AsrStored(AsrPresets.openAi.endpoint, AsrPresets.openAi.model, "", false)
    private val secret = "sk-or-v1-" + "abcdefghijklmnopqrstuvwxyz1234"

    @Test fun presetsAndKeyPrefix() {
        assertEquals(AsrPresets.openRouter, AsrPresets.forKey(secret))
        assertEquals(AsrPresets.openAi, AsrPresets.forKey("sk-proj-" + "abcdefghijklmnopqrstuvwxyz"))
        assertNull(AsrPresets.forKey("not-a-key"))
        assertEquals("openrouter", AsrPresets.matching(AsrPresets.openRouter.endpoint, AsrPresets.openRouter.model)?.id)
        assertNull(AsrPresets.matching(AsrPresets.openRouter.endpoint, "openai/whisper-1"))
    }

    @Test fun keyOnlySelectsOpenRouterWhenCurrentValuesAreStillAPreset() {
        val ready = AsrConfig.resolve(openAi, AsrSetupRequest(key = secret)) as AsrResolve.Ready
        assertEquals(AsrPresets.openRouter.endpoint, ready.stored.endpoint)
        assertEquals(AsrPresets.openRouter.model, ready.stored.model)
        assertEquals(secret, ready.stored.key)
        val shown = AsrConfig.publish(ready.stored)
        assertEquals("openrouter", shown.preset)
        assertTrue(shown.keySet)
        assertEquals("sk-or-…1234", shown.keyHint)
        assertFalse(shown.keyHint.contains("abcdefghijklmnop"))
        assertNull(shown.warning)
        assertFalse(shown.toString().contains(secret))
        assertFalse(ready.stored.toString().contains(secret))
    }

    @Test fun explicitUrlWinsAndWarnsOnProviderMismatch() {
        val ready = AsrConfig.resolve(openAi, AsrSetupRequest(endpoint = AsrPresets.openAi.endpoint, key = secret)) as AsrResolve.Ready
        assertEquals(AsrPresets.openAi.endpoint, ready.stored.endpoint)
        val shown = AsrConfig.publish(ready.stored)
        assertTrue(shown.warning!!.contains("HTTP 401"))
        assertFalse(shown.warning!!.contains(secret))
        assertEquals("openrouter", AsrConfig.mismatch(ready.stored.endpoint, ready.stored.key)!!.keyKind)
    }

    @Test fun customHostDoesNotWarn() {
        val ready = AsrConfig.resolve(openAi, AsrSetupRequest(endpoint = "https://asr.example.com/v1/audio/transcriptions", model = "whisper-1", key = secret)) as AsrResolve.Ready
        assertNull(AsrConfig.publish(ready.stored).warning)
        assertEquals("custom", AsrConfig.publish(ready.stored).preset)
    }

    @Test fun rejectsUserInfoAndQueryKeyWithoutEchoingThem() {
        val userInfo = AsrConfig.resolve(openAi, AsrSetupRequest(endpoint = "https://user:$secret@api.openai.com/v1/audio/transcriptions")) as AsrResolve.Failed
        assertEquals("invalid_endpoint", userInfo.code)
        assertFalse(userInfo.message.contains(secret))
        val query = AsrConfig.resolve(openAi, AsrSetupRequest(endpoint = "http://api.openai.com/v1/audio/transcriptions")) as AsrResolve.Failed
        assertEquals("invalid_endpoint", query.code)
        val secretQuery = AsrConfig.endpointProblem("https://api.openai.com/v1/audio/transcriptions?api_key=$secret")
        assertEquals(EndpointProblem.SecretQuery, secretQuery)
    }

    @Test fun pasteBlockFillsUrlModelAndKey() {
        val block = """
            地址：https://openrouter.ai/api/v1/audio/transcriptions
            模型：openai/whisper-large-v3
            密钥：$secret
        """.trimIndent()
        val parsed = AsrConfigText.parse(block)
        assertEquals("https://openrouter.ai/api/v1/audio/transcriptions", parsed.endpoint)
        assertEquals("openai/whisper-large-v3", parsed.model)
        assertEquals(secret, parsed.key)
        assertTrue(AsrConfigText.looksLikeBundle(block))
        val ready = AsrConfig.resolve(openAi, AsrSetupRequest(paste = block)) as AsrResolve.Ready
        assertEquals(secret, ready.stored.key)
        assertEquals("openrouter", AsrConfig.publish(ready.stored).preset)

        val oneLine = "https://api.openai.com/v1/audio/transcriptions whisper-1 sk-proj-abcdefghijklmnopqrstuvwxyz"
        val loose = AsrConfigText.parse(oneLine)
        assertEquals("https://api.openai.com/v1/audio/transcriptions", loose.endpoint)
        assertEquals("whisper-1", loose.model)
        assertEquals("sk-proj-abcdefghijklmnopqrstuvwxyz", loose.key)

        val json = """{"url":"https://api.openai.com/v1/audio/transcriptions","model":"whisper-1","api_key":"$secret"}"""
        val fromJson = AsrConfigText.parse(json)
        assertEquals("whisper-1", fromJson.model)
        assertEquals(secret, fromJson.key)

        val onlyKey = AsrConfigText.parse(secret)
        assertEquals(secret, onlyKey.key)
        assertNull(onlyKey.endpoint)
        assertFalse(AsrConfigText.looksLikeBundle(secret))
        assertFalse(AsrConfigText.looksLikeBundle("https://api.openai.com/v1/audio/transcriptions"))
    }

    @Test fun queryKeyIsLiftedOutOfTheUrl() {
        val parsed = AsrConfigText.parse("https://api.openai.com/v1/audio/transcriptions?api_key=$secret")
        assertEquals("https://api.openai.com/v1/audio/transcriptions", parsed.endpoint)
        assertEquals(secret, parsed.key)
        assertFalse(parsed.endpoint!!.contains(secret))
    }

    @Test fun shortKeyHintDoesNotRevealIt() {
        assertEquals("saved", AsrConfig.hint("sk-or-short"))
        assertEquals("", AsrConfig.hint("  "))
    }
}
