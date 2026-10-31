package com.wangzan101.mika.relay

import android.content.SharedPreferences
import com.wangzan101.mika.core.relay.DEFAULT_MCP_URL
import com.wangzan101.mika.core.relay.DEFAULT_RELAY_ALIAS
import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubRelaySettingsTest {
    @Test fun defaultsAreOffAndPointAtHub() {
        val settings = HubRelaySettings(MemoryPrefs(), XorCipher)
        val config = settings.config()
        assertFalse(config.enabled)
        assertFalse(config.uploadScreenshots)
        assertFalse(config.claimTasks)
        assertEquals(DEFAULT_MCP_URL, config.mcpUrl)
        assertEquals("", settings.snapshot().agentId)
        assertEquals(DEFAULT_RELAY_ALIAS, config.alias)
        assertEquals("", config.token)
        assertEquals("", config.sessionId)
    }

    @Test fun tokenIsStoredEncryptedAndRoundTrips() {
        val prefs = MemoryPrefs()
        val settings = HubRelaySettings(prefs, XorCipher)
        assertTrue(settings.setToken("da_super_secret_token"))
        val dumped = prefs.getAll().values.joinToString(" ")
        assertFalse(dumped.contains("da_super_secret_token"))
        assertEquals("da_super_secret_token", settings.token())
        assertTrue(settings.snapshot().hasToken)
        assertFalse(settings.config().tokenUnreadable)
        assertTrue(settings.setToken(""))
        assertNull(settings.token())
        assertFalse(settings.snapshot().hasToken)
    }

    @Test fun unreadableTokenIsNotSentAsCiphertext() {
        val prefs = MemoryPrefs()
        prefs.edit().putString("token", "@@@").commit()
        val config = HubRelaySettings(prefs, XorCipher).config()
        assertTrue(config.tokenUnreadable)
        assertEquals("", config.token)
    }

    @Test fun sessionAndStatusStick() {
        val settings = HubRelaySettings(MemoryPrefs(), XorCipher)
        settings.setEnabled(true)
        settings.setJoinCode(" TOPIC ")
        settings.setSessionId("ses_fixed")
        settings.setLastSeq(15)
        settings.setLastError("HTTP 401")
        settings.setUploadScreenshots(true)
        val snap = settings.snapshot()
        assertTrue(snap.enabled)
        assertEquals("TOPIC", snap.joinCode)
        assertEquals("ses_fixed", snap.sessionId)
        assertEquals(15L, snap.lastSeq)
        assertEquals("HTTP 401", snap.lastError)
        assertTrue(snap.uploadScreenshots)
        settings.setLastError(null)
        assertNull(settings.snapshot().lastError)
        assertEquals("ses_fixed", settings.snapshot().sessionId)
    }

    private object XorCipher : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(data: ByteArray): ByteArray = encrypt(data)
    }

    private class MemoryPrefs : SharedPreferences {
        private val values = HashMap<String, Any?>()

        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String) = key in values
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean {
                pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
