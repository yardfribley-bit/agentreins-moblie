package com.wangzan101.mika.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in against a user-selected test host; no credentials or addresses in the test. */
class CloudSshIntegrationTest {
    @Test fun diagnosticsDistinguishResetAndTimeoutWithoutExposingDetails() {
        assertEquals("连接在 SSH 握手时被重置，请检查服务器和网络。", CloudSsh.failureMessage(java.net.SocketException("Connection reset")))
        assertEquals("连接服务器超时，请检查网络和 SSH 端口。", CloudSsh.failureMessage(java.net.SocketTimeoutException("timed out")))
    }
    @Test fun connectAndExecuteReadOnlyCommand() = runBlocking {
        val host = System.getenv("CLOUD_SSH_TEST_HOST").orEmpty()
        val user = System.getenv("CLOUD_SSH_TEST_USER").orEmpty()
        val password = System.getenv("CLOUD_SSH_TEST_PASSWORD").orEmpty()
        val fingerprint = System.getenv("CLOUD_SSH_TEST_FINGERPRINT").orEmpty()
        assumeTrue(host.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty() && fingerprint.isNotEmpty())
        assertEquals(fingerprint, CloudSsh.probe(host, 22, user))
        val result = CloudSsh.command(host, 22, user, password, fingerprint, "printf cloud-test-ok")
        assertEquals(0, result.exitCode)
        assertEquals("cloud-test-ok", result.output)
        assertFalse(result.timedOut)
    }
}
