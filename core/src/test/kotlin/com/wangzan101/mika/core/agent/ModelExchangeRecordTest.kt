package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.tool.ToolIndex
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelExchangeRecordTest {
    @Test fun exchangeCanBeStoredButIsNotRelayed() {
        val event = com.wangzan101.mika.core.record.RunEvent.ModelExchange(1, "model", "private request", "response")
        val restored = com.wangzan101.mika.core.record.RunEvent.decode(com.wangzan101.mika.core.record.RunEvent.encode(event))
        assertEquals(event, restored)
        assertNull(com.wangzan101.mika.core.relay.RelayEventText.map("run", 0, event))
    }
    @Test fun requestAndReplyAreRedactedAndImagesAreNotStored() {
        val index = ToolIndex(emptyList())
        val request = ModelExchangeRecord.request("system", listOf(Message(Role.User, listOf(
            Part.Text("密码: hunter2"), Part.Image("SECRET_IMAGE_BYTES")), Author.User)), emptyList(), index)
        val response = ModelExchangeRecord.response(ModelReply(listOf(Part.Text("结果")), StopReason.EndTurn, null), index)
        assertTrue(request.contains("system"))
        assertFalse(request.contains("hunter2"))
        assertFalse(request.contains("SECRET_IMAGE_BYTES"))
        assertTrue(request.contains("bytes omitted"))
        assertTrue(response.contains("结果"))
    }

    @Test fun oversizedRequestShowsTruncation() {
        val request = ModelExchangeRecord.request("a".repeat(30000), emptyList(), emptyList(), ToolIndex(emptyList()))
        assertTrue(request.contains("[truncated]"))
    }
}
