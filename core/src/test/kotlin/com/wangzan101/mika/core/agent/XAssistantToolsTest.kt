package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.tool.FakeScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XAssistantToolsTest {
    @Test fun doesNotOfferCodeExecution() {
        val names = toolDispatcher(FakeScreen(), sandbox = FakeSandbox(), web = FakeWebPort(), disableCode = true).definitions().map { it.name }.toSet()
        for (name in listOf("run_command", "run_bundled_script", "phone_system_command", "web_evaluate_js", "web_script")) {
            assertFalse("unexpected tool: $name", name in names)
        }
        assertTrue("phone_launch_app" in names)
        assertTrue("web_navigate" in names)
    }
}
