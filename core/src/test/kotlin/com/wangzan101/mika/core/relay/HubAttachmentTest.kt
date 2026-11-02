package com.wangzan101.mika.core.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HubAttachmentTest {
    private val config = RelayConfig(enabled = true, mcpUrl = "https://www.chuhaijian.com/task", token = "da_secret", sessionId = "rl_fixed")

    @Test fun base64StaysLocalAndUrlIsFetchedWithoutTheToken() {
        val png = byteArrayOf(1, 2, 3, 4)
        val video = byteArrayOf(9, 8, 7)
        val fake = FakeFiles()
        fake.files["fl_52161fe383bbac29a14b66a9"] = file("fl_52161fe383bbac29a14b66a9", "agentreins-ime.png", "image/png", png)
        fake.files["fl_bigvideofile000000000001"] = file(
            "fl_bigvideofile000000000001", "clip.mp4", "video/mp4", null,
            downloadUrl = "https://cdn.example/clip?exp=1&sig=deadbeef", size = video.size.toLong(),
        )
        fake.remote[fake.files["fl_bigvideofile000000000001"]!!.downloadUrl!!] = video
        val gallery = FakeGallery()
        val written = mutableListOf<Pair<String, ByteArray>>()
        val intake = intake(fake, gallery) { name, bytes ->
            assertTrue(HubAttachmentNames.isSafeSegment(name))
            written += name to bytes
            "/workspace/inbox/$name"
        }
        val prepared = intake.prepare(config, HubInstruction(250, "post this image", listOf(
            HubAttachmentRef("fl_52161fe383bbac29a14b66a9", "agentreins-ime.png", png.size.toLong(), "image/png", HubAttachmentIntake.sha256(png)),
            HubAttachmentRef("fl_bigvideofile000000000001", "clip.mp4", video.size.toLong(), "video/mp4"),
        )))
        assertEquals(listOf("agentreins-ime.png", "clip.mp4"), written.map { it.first })
        assertTrue(written[0].second.contentEquals(png))
        assertTrue(written[1].second.contentEquals(video))
        assertEquals(listOf("https://cdn.example/clip?exp=1&sig=deadbeef"), fake.fetched)
        assertTrue(fake.downloads.none { it.second == "da_secret" && it.first == "https://cdn.example/clip?exp=1&sig=deadbeef" })
        assertEquals(listOf("ses-token-used"), fake.tokens.distinct())
        assertEquals("image/png", gallery.inserted[0].second)
        assertEquals("video/mp4", gallery.inserted[1].second)
        assertTrue(gallery.inserted[0].third.contentEquals(png))
        assertTrue(prepared.goal.contains("post this image"))
        assertTrue(prepared.goal.contains("附件:/workspace/inbox/agentreins-ime.png("))
        assertTrue(prepared.goal.contains("name: agentreins-ime.png"))
        assertTrue(prepared.goal.contains("bytes: 4"))
        assertTrue(prepared.goal.contains("mime: image/png"))
        assertTrue(prepared.goal.contains("workspace: /workspace/inbox/agentreins-ime.png"))
        assertTrue(prepared.goal.contains("gallery: Pictures/AgentReins/agentreins-ime.png"))
        assertTrue(prepared.goal.contains("gallery: Movies/AgentReins/clip.mp4"))
        assertFalse(prepared.goal.contains("sig="))
        assertFalse(prepared.goal.contains("deadbeef"))
        assertFalse(prepared.goal.contains("da_secret"))
        assertFalse(prepared.goal.contains("content_base64"))
        assertNull(prepared.resultNote)
    }

    @Test fun plainFileSkipsTheGallery() {
        val bytes = "hello".toByteArray()
        val fake = FakeFiles()
        fake.files["fl_notes000000000000000001"] = file("fl_notes000000000000000001", "notes.txt", "text/plain", bytes)
        val gallery = FakeGallery()
        val intake = intake(fake, gallery) { name, _ -> "/workspace/inbox/$name" }
        val prepared = intake.prepare(config, HubInstruction(1, "read it", listOf(HubAttachmentRef("fl_notes000000000000000001", "notes.txt"))))
        assertTrue(gallery.inserted.isEmpty())
        assertTrue(prepared.goal.contains("mime: text/plain"))
        assertTrue(prepared.goal.contains("gallery: none"))
        assertTrue(prepared.goal.contains("workspace: /workspace/inbox/notes.txt"))
    }

    @Test fun missingFileIsReportedAndTheTaskTextRemains() {
        val png = byteArrayOf(1)
        val fake = FakeFiles()
        fake.files["fl_ok0000000000000000000001"] = file("fl_ok0000000000000000000001", "a.png", "image/png", png)
        fake.fail["fl_gone00000000000000000001"] = IOException("https://cdn.example/x?sig=deadbeef")
        val sleeps = mutableListOf<Long>()
        val intake = HubAttachmentIntake(
            transport = fake,
            write = { name, _ -> "/workspace/inbox/$name" },
            gallery = FakeGallery(),
            sleep = { sleeps += it },
            attempts = 3,
        )
        val prepared = intake.prepare(config, HubInstruction(9, "use both", listOf(
            HubAttachmentRef("fl_ok0000000000000000000001", "a.png"),
            HubAttachmentRef("fl_gone00000000000000000001", "gone.png"),
            HubAttachmentRef("../etc/passwd", "passwd"),
            HubAttachmentRef("fl_big0000000000000000000001", "huge.bin", HUB_ATTACHMENT_MAX_BYTES + 1),
        )))
        assertEquals(listOf(200L, 400L), sleeps)
        assertEquals(3, fake.tries.getValue("fl_gone00000000000000000001"))
        assertFalse(fake.files.containsKey("fl_big0000000000000000000001") && fake.tries.containsKey("fl_big0000000000000000000001"))
        assertEquals(0, fake.tries["fl_big0000000000000000000001"] ?: 0)
        assertTrue(prepared.goal.startsWith("use both"))
        assertTrue(prepared.goal.contains("/workspace/inbox/a.png"))
        assertTrue(prepared.goal.contains("file_id: fl_gone00000000000000000001"))
        assertTrue(prepared.goal.contains("reason: download failed"))
        assertTrue(prepared.goal.contains("invalid file id"))
        assertTrue(prepared.goal.contains("larger than 32 MB"))
        assertFalse(prepared.goal.contains("sig="))
        assertFalse(prepared.goal.contains("da_secret"))
        val note = prepared.resultNote!!
        assertTrue(note.contains("fl_gone00000000000000000001"))
        assertTrue(note.contains("gone.png"))
        assertTrue(note.contains("invalid file id"))
        assertFalse(note.contains("../"))
        assertFalse(prepared.goal.contains("../"))
        assertTrue(note.contains("fl_big0000000000000000000001"))
        assertFalse(note.contains("http"))
        assertFalse(note.contains("content_base64"))
    }

    @Test fun retriesThenSaves() {
        val png = byteArrayOf(7, 7)
        val fake = FakeFiles()
        fake.files["fl_retry00000000000000000001"] = file("fl_retry00000000000000000001", "a.png", "image/png", png)
        fake.failTimes["fl_retry00000000000000000001"] = 2
        val intake = intake(fake, FakeGallery()) { name, _ -> "/workspace/inbox/$name" }
        val prepared = intake.prepare(config, HubInstruction(1, "go", listOf(HubAttachmentRef("fl_retry00000000000000000001"))))
        assertEquals(3, fake.tries.getValue("fl_retry00000000000000000001"))
        assertTrue(prepared.goal.contains("/workspace/inbox/a.png"))
        assertNull(prepared.resultNote)
    }

    @Test fun pathTraversalNeverReachesTheWriter() {
        val bytes = byteArrayOf(1, 2)
        val fake = FakeFiles()
        fake.files["fl_trav000000000000000000001"] = file("fl_trav000000000000000000001", "../../etc/passwd", "text/plain", bytes)
        val names = mutableListOf<String>()
        val intake = intake(fake, FakeGallery()) { name, _ ->
            names += name
            "/workspace/inbox/$name"
        }
        val prepared = intake.prepare(config, HubInstruction(1, "x", listOf(HubAttachmentRef("fl_trav000000000000000000001", "../../etc/passwd"))))
        assertEquals(listOf("passwd"), names)
        assertTrue(prepared.goal.contains("/workspace/inbox/passwd"))
        assertFalse(prepared.goal.contains("../"))
    }

    @Test fun hashMismatchIsMissingAndNotRetried() {
        val fake = FakeFiles()
        fake.files["fl_bad0000000000000000000001"] = file("fl_bad0000000000000000000001", "a.png", "image/png", byteArrayOf(1), sha256 = "ab".repeat(32))
        val wrote = mutableListOf<String>()
        val intake = intake(fake, FakeGallery()) { name, _ -> wrote += name; "/workspace/inbox/$name" }
        val prepared = intake.prepare(config, HubInstruction(1, "x", listOf(HubAttachmentRef("fl_bad0000000000000000000001"))))
        assertTrue(wrote.isEmpty())
        assertEquals(1, fake.tries.getValue("fl_bad0000000000000000000001"))
        assertTrue(prepared.goal.contains("hash mismatch"))
        assertTrue(prepared.resultNote!!.contains("hash mismatch"))
    }

    @Test fun galleryFailureKeepsTheWorkspaceFile() {
        val png = byteArrayOf(3)
        val fake = FakeFiles()
        fake.files["fl_pic0000000000000000000001"] = file("fl_pic0000000000000000000001", "a.png", "image/png", png)
        val gallery = FakeGallery(fail = true)
        val intake = intake(fake, gallery) { name, _ -> "/workspace/inbox/$name" }
        val prepared = intake.prepare(config, HubInstruction(1, "show", listOf(HubAttachmentRef("fl_pic0000000000000000000001"))))
        assertTrue(prepared.goal.contains("workspace: /workspace/inbox/a.png"))
        assertTrue(prepared.goal.contains("gallery: insert failed"))
        assertTrue(prepared.resultNote!!.contains("/workspace/inbox/a.png"))
        assertTrue(prepared.resultNote!!.contains("Gallery insert failed"))
    }

    @Test fun placementPicksPicturesOrMovies() {
        assertEquals("Pictures/AgentReins/", HubGalleryPlacement.target("image/png")!!.relativePath)
        assertEquals(HubGalleryPlacement.Kind.Image, HubGalleryPlacement.target("image/jpeg")!!.kind)
        assertEquals("Movies/AgentReins/", HubGalleryPlacement.target("video/mp4")!!.relativePath)
        assertEquals(HubGalleryPlacement.Kind.Video, HubGalleryPlacement.target("video/webm")!!.kind)
        assertNull(HubGalleryPlacement.target("application/pdf"))
        assertEquals("Pictures/AgentReins/a.png", HubGalleryPlacement.target("image/png")!!.location("a.png"))
    }

    @Test fun duplicateFileIsDownloadedOnce() {
        val fake = FakeFiles()
        fake.files["fl_dup0000000000000000000001"] = file("fl_dup0000000000000000000001", "a.txt", "text/plain", byteArrayOf(1))
        var writes = 0
        val intake = intake(fake, FakeGallery()) { name, _ -> writes++; "/workspace/inbox/$name" }
        intake.prepare(config, HubInstruction(1, "x", listOf(
            HubAttachmentRef("fl_dup0000000000000000000001"),
            HubAttachmentRef("fl_dup0000000000000000000001"),
        )))
        assertEquals(1, writes)
        assertEquals(1, fake.tries.getValue("fl_dup0000000000000000000001"))
    }

    private fun intake(fake: FakeFiles, gallery: FakeGallery, write: (String, ByteArray) -> String) = HubAttachmentIntake(
        transport = fake,
        write = write,
        gallery = gallery,
        sleep = {},
        attempts = 3,
    )

    private fun file(
        id: String, name: String, mime: String, bytes: ByteArray?,
        downloadUrl: String? = null, size: Long = bytes?.size?.toLong() ?: 0, sha256: String? = bytes?.let(HubAttachmentIntake::sha256),
    ) = RelayFileDownload(id, name, size, sha256, mime, bytes, downloadUrl)

    private class FakeGallery(private val fail: Boolean = false) : HubGalleryStore {
        val inserted = mutableListOf<Triple<String, String, ByteArray>>()
        override fun insert(name: String, mime: String, bytes: ByteArray): String {
            if (fail) throw IOException("gallery insert failed")
            inserted += Triple(name, mime, bytes)
            return HubGalleryPlacement.target(mime)!!.location(name)
        }
    }

    private class FakeFiles : RelayTransport {
        val files = HashMap<String, RelayFileDownload>()
        val remote = HashMap<String, ByteArray>()
        val fetched = mutableListOf<String>()
        val tries = mutableMapOf<String, Int>()
        val tokens = mutableListOf<String>()
        val downloads = mutableListOf<Pair<String, String>>()
        val fail = HashMap<String, Exception>()
        val failTimes = HashMap<String, Int>()

        override fun download(mcpUrl: String, token: String, sessionId: String, fileId: String): RelayFileDownload {
            tokens += if (token == "da_secret") "ses-token-used" else token
            tries[fileId] = (tries[fileId] ?: 0) + 1
            fail[fileId]?.let { throw it }
            val left = failTimes[fileId] ?: 0
            if (left > 0) {
                failTimes[fileId] = left - 1
                throw IOException("network")
            }
            return files[fileId] ?: throw RelayException(404, false, "missing")
        }

        override fun fetchUnsigned(url: String, maxBytes: Long): ByteArray {
            fetched += url
            downloads += url to "no-token"
            assertTrue(maxBytes <= HUB_ATTACHMENT_MAX_BYTES)
            return remote[url] ?: throw IOException("network")
        }

        override fun connect(mcpUrl: String, alias: String, agentType: String) = error("unused")
        override fun join(mcpUrl: String, token: String, code: String, environment: RelayEnvironment) = error("unused")
        override fun send(mcpUrl: String, token: String, sessionId: String, kind: String, text: String, requestId: String, attachments: List<String>, replyTo: Long?) = error("unused")
        override fun upload(mcpUrl: String, token: String, sessionId: String, name: String, bytes: ByteArray) = error("unused")
        override fun heartbeat(mcpUrl: String, token: String, sessionId: String, environment: RelayEnvironment) = error("unused")
        override fun read(mcpUrl: String, token: String, sessionId: String, after: Long, waitSeconds: Int) = error("unused")
        override fun ack(mcpUrl: String, token: String, sessionId: String, seq: Long) = error("unused")
        override fun whoami(mcpUrl: String, token: String) = error("unused")
        override fun memberOf(mcpUrl: String, token: String, sessionId: String) = error("unused")
    }
}
