package com.wangzan101.mika.core.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlWorkspaceDownloadTest {
    @Test fun httpsOnlyAndImageLandsInWorkspaceAndGallery() {
        assertEquals("https only", HttpsUrls.reject("http://www.chuhaijian.com/a.png"))
        assertEquals("https only", HttpsUrls.reject("https://user:pw@example.com/a.png"))
        var fetched = false
        val down = UrlWorkspaceDownload(
            fetch = UrlFetcher { _, _ ->
                fetched = true
                Fetched(byteArrayOf(1, 2, 3), "image/png", "agentreins-ime.png")
            },
            write = { name, _ -> "/workspace/inbox/$name" },
            gallery = HubGalleryStore { name, _, _ -> "Pictures/AgentReins/$name" },
        )
        val refused = down.download("http://www.chuhaijian.com/media-tmp/agentreins-ime.png")
        assertFalse(refused.ok)
        assertFalse(fetched)
        val saved = down.download("https://www.chuhaijian.com/media-tmp/agentreins-ime.png")
        assertTrue(saved.ok)
        assertEquals("/workspace/inbox/agentreins-ime.png", saved.workspacePath)
        assertEquals("Pictures/AgentReins/agentreins-ime.png", saved.gallery)
        assertTrue(saved.message.contains("workspace: /workspace/inbox/agentreins-ime.png"))
        assertTrue(saved.message.contains("gallery: Pictures/AgentReins/agentreins-ime.png"))
        assertNull(HttpsUrls.reject("https://www.chuhaijian.com/media-tmp/agentreins-ime.png"))
    }
}
