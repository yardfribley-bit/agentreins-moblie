package com.wangzan101.mika.settings

import org.junit.Assert.*
import org.junit.Test

class DeepSeekBalanceTest {
    @Test fun parsesOfficialBalanceAndAppliesThresholds() {
        val result = DeepSeekBalance.parse("""{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"4.99"}]}""")
        assertEquals("4.99", result.entries.single().total.toPlainString())
        assertTrue(DeepSeekBalance.isLow(result))
        assertFalse(DeepSeekBalance.isLow(DeepSeekBalance.parse("""{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"5.00"}]}""")))
        assertTrue(DeepSeekBalance.isLow(DeepSeekBalance.parse("""{"is_available":false,"balance_infos":[{"currency":"USD","total_balance":"4.00"}]}""")))
    }

    @Test fun neverSendsKeysToImpersonatingHosts() {
        fun provider(url: String) = Provider("id", "DeepSeek", ProviderConfig.Kind.OpenAiCompat, url)
        assertTrue(DeepSeekBalance.isOfficial(provider("https://api.deepseek.com")))
        assertFalse(DeepSeekBalance.isOfficial(provider("https://api.deepseek.com.attacker.example")))
        assertFalse(DeepSeekBalance.isOfficial(provider("http://api.deepseek.com")))
        assertFalse(DeepSeekBalance.isOfficial(provider("https://user@api.deepseek.com")))
        assertFalse(DeepSeekBalance.isOfficial(provider("https://api.deepseek.com:8443")))
    }
}
