package com.wangzan101.mika.core.relay

import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/** 已经放进工作区的一个附件。 [gallery] 是「Pictures/AgentReins/…」或「Movies/AgentReins/…」，不是媒体则为 null。 */
data class HubSavedAttachment(
    val fileId: String,
    val name: String,
    val size: Long,
    val mime: String,
    val workspacePath: String,
    val gallery: String?,
    val galleryFailed: Boolean = false,
)

/** 没能放下的附件。 [reason] 是短句，不含字节、链接或令牌。 */
data class HubMissingAttachment(
    val fileId: String,
    val name: String?,
    val reason: String,
)

/** 交给任务的正文，以及要附在 Hub result 上的一句（没有缺失时为 null）。 */
data class HubPreparedTask(val goal: String, val resultNote: String?)

/**
 * 图片进「Pictures/AgentReins」，视频进「Movies/AgentReins」。
 * 具体写入由 [HubGalleryStore] 做；这里只决定是不是媒体、以及告诉模型的位置。
 */
object HubGalleryPlacement {
    const val FOLDER = "AgentReins"

    enum class Kind { Image, Video }

    data class Target(val kind: Kind, val relativePath: String) {
        fun location(displayName: String): String = relativePath + displayName
    }

    fun target(mime: String): Target? {
        val type = mime.trim().lowercase(Locale.US)
        val root = when {
            type.startsWith("image/") -> "Pictures"
            type.startsWith("video/") -> "Movies"
            else -> return null
        }
        val kind = if (root == "Pictures") Kind.Image else Kind.Video
        return Target(kind, "$root/$FOLDER/")
    }
}

/** 把图片或视频放进系统相册。实现不得把字节写进日志。 */
fun interface HubGalleryStore {
    /** 返回相册里的相对位置，例如 `Pictures/AgentReins/a.png`。 */
    fun insert(name: String, mime: String, bytes: ByteArray): String
}

/** 附件文件名：只留最后一段，去掉控制字符，拒绝 `.` 和 `..`。写不进工作区的名字换成 `<fileId>.bin`。 */
object HubAttachmentNames {
    private val FILE_ID = Regex("^fl_[A-Za-z0-9]{8,64}$")
    private val MIME = Regex("^[a-z0-9][a-z0-9.+-]{0,40}/[a-z0-9][a-z0-9.+-]{0,40}$")

    fun validFileId(id: String): Boolean = FILE_ID.matches(id)

    fun mimeOf(raw: String?): String {
        val type = raw?.trim()?.lowercase(Locale.US).orEmpty()
        return if (MIME.matches(type)) type else "application/octet-stream"
    }

    /** 单个路径段，可以交给工作区关口。 */
    fun safe(raw: String?, fileId: String): String {
        val leaf = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
        val stripped = buildString {
            var i = 0
            while (i < leaf.length) {
                val cp = leaf.codePointAt(i)
                i += Character.charCount(cp)
                if (cp == '/'.code || cp == '\\'.code || hidden(cp)) continue
                appendCodePoint(cp)
            }
        }.trim().trimStart('.')
        if (isSafeSegment(stripped)) return fit(stripped)
        val id = fileId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40).ifBlank { "attachment" }
        return "$id.bin"
    }

    fun isSafeSegment(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && "/" !in name && "\\" !in name && ".." !in name &&
            name.codePoints().noneMatch(::hidden)

    /** 截到 120 个字符、240 字节，尽量留住扩展名。 */
    private fun fit(name: String, maxChars: Int = 120, maxBytes: Int = 240): String {
        fun bytes(t: String) = t.toByteArray(Charsets.UTF_8).size
        fun chars(t: String) = t.codePointCount(0, t.length)
        if (bytes(name) <= maxBytes && chars(name) <= maxChars) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot) else ""
        val keepExt = ext.isNotEmpty() && bytes(ext) <= 32 && chars(ext) < maxChars
        val base = if (keepExt) name.substring(0, dot) else name
        val roomBytes = maxBytes - if (keepExt) bytes(ext) else 0
        val roomChars = maxChars - if (keepExt) chars(ext) else 0
        val sb = StringBuilder()
        var used = 0
        var count = 0
        var i = 0
        while (i < base.length && count < roomChars) {
            val cp = base.codePointAt(i)
            val n = bytes(String(Character.toChars(cp)))
            if (used + n > roomBytes) break
            sb.appendCodePoint(cp)
            used += n
            count++
            i += Character.charCount(cp)
        }
        return (sb.toString() + if (keepExt) ext else "").ifBlank { "attachment" }
    }

    private fun hidden(cp: Int): Boolean =
        Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() || cp == 0x2028 || cp == 0x2029
}

/** 写进任务正文和 Hub result 的句子。不含文件字节和下载链接。 */
object HubAttachmentText {
    fun goal(instruction: String, saved: List<HubSavedAttachment>, missing: List<HubMissingAttachment>): String {
        if (saved.isEmpty() && missing.isEmpty()) return instruction
        return buildString {
            append(instruction.trimEnd())
            if (isNotEmpty()) append("\n\n")
            for (file in saved) {
                append("附件:").append(file.workspacePath).append("(").append(sizeLabel(file.size)).append(")\n")
            }
            if (saved.isNotEmpty()) {
                append("Hub attachments are already on this phone. Use the workspace path; do not ask for the file again.\n")
                for (file in saved) {
                    append("- name: ").append(file.name)
                    append("; bytes: ").append(file.size)
                    append("; mime: ").append(file.mime)
                    append("; workspace: ").append(file.workspacePath)
                    append("; gallery: ").append(galleryLabel(file))
                    append('\n')
                }
            }
            if (missing.isNotEmpty()) {
                append("Hub attachments missing (not downloaded):\n")
                for (item in missing) append(missingLine(item)).append('\n')
            }
        }.trimEnd()
    }

    /** 附在 Hub 的 result 末尾。没有缺失、相册也没失败时返回 null。 */
    fun resultNote(missing: List<HubMissingAttachment>, galleryFailures: List<HubSavedAttachment>): String? {
        if (missing.isEmpty() && galleryFailures.isEmpty()) return null
        val text = buildString {
            if (missing.isNotEmpty()) {
                append("Missing Hub attachments:")
                for (item in missing) {
                    append("\n- ").append(item.fileId)
                    if (!item.name.isNullOrBlank()) append(" (").append(item.name).append(")")
                    append(": ").append(item.reason)
                }
            }
            if (galleryFailures.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("Gallery insert failed (file is still in the workspace):")
                for (file in galleryFailures) {
                    append("\n- ").append(file.name).append(": ").append(file.workspacePath)
                }
            }
        }
        return RelayEventText.clip(text, 1_500)
    }

    /** 下载整批都抛了：任务照跑，正文和 result 里写明每个附件没到。 */
    fun failed(instruction: HubInstruction): HubPreparedTask {
        val missing = instruction.attachments.map { ref ->
            if (HubAttachmentNames.validFileId(ref.fileId)) {
                HubMissingAttachment(ref.fileId, ref.name?.let { HubAttachmentNames.safe(it, ref.fileId) }, "download failed")
            } else {
                HubMissingAttachment("invalid", null, "invalid file id")
            }
        }.ifEmpty { listOf(HubMissingAttachment("invalid", null, "download failed")) }
        return HubPreparedTask(goal(instruction.text, emptyList(), missing), resultNote(missing, emptyList()))
    }

    /** 把缺失说明接在 result 后面，并保证整段不超过 Hub 的正文上限。 */
    fun appendResult(text: String, note: String): String {
        val suffix = "\n" + note.trim()
        val room = RELAY_MAX_TEXT - suffix.length
        if (room <= 1) return RelayEventText.clip(note, RELAY_MAX_TEXT)
        val body = if (text.length <= room) text else text.take(room - 1) + "…"
        return body + suffix
    }

    private fun galleryLabel(file: HubSavedAttachment): String = when {
        file.galleryFailed -> "insert failed"
        file.gallery != null -> file.gallery
        else -> "none"
    }

    private fun missingLine(item: HubMissingAttachment): String = buildString {
        append("- file_id: ").append(item.fileId)
        if (!item.name.isNullOrBlank()) append("; name: ").append(item.name)
        append("; reason: ").append(item.reason)
    }

    fun sizeLabel(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

/**
 * 开跑之前准备附件。个别文件失败只记入缺失，不让整条指令停下来。
 * [write] 把字节放进任务工作区，返回模型看得见的路径（`/workspace/inbox/…`）。
 */
class HubAttachmentIntake(
    private val transport: RelayTransport,
    private val write: (name: String, bytes: ByteArray) -> String,
    private val gallery: HubGalleryStore,
    private val sleep: (Long) -> Unit = {},
    private val maxBytes: Long = HUB_ATTACHMENT_MAX_BYTES,
    private val maxFiles: Int = HUB_ATTACHMENT_MAX_FILES,
    private val attempts: Int = 3,
) {
    fun prepare(config: RelayConfig, instruction: HubInstruction): HubPreparedTask {
        if (instruction.attachments.isEmpty()) return HubPreparedTask(instruction.text, null)
        val saved = mutableListOf<HubSavedAttachment>()
        val missing = mutableListOf<HubMissingAttachment>()
        val seen = HashSet<String>()
        var taken = 0
        for (ref in instruction.attachments) {
            if (!seen.add(ref.fileId)) continue
            if (!HubAttachmentNames.validFileId(ref.fileId)) {
                missing += HubMissingAttachment("invalid", null, "invalid file id")
                continue
            }
            if (taken >= maxFiles) {
                missing += missing(ref, "too many attachments")
                continue
            }
            taken++
            if (ref.size != null && ref.size > maxBytes) {
                missing += missing(ref, sizeReason())
                continue
            }
            try {
                saved += save(config, ref)
            } catch (e: Exception) {
                missing += missing(ref, reason(e))
            }
        }
        val failures = saved.filter { it.galleryFailed }
        return HubPreparedTask(
            HubAttachmentText.goal(instruction.text, saved, missing),
            HubAttachmentText.resultNote(missing, failures),
        )
    }

    private fun save(config: RelayConfig, ref: HubAttachmentRef): HubSavedAttachment {
        val loaded = retry { load(config, ref) }
        val name = HubAttachmentNames.safe(loaded.name.ifBlank { ref.name }, ref.fileId)
        if (!HubAttachmentNames.isSafeSegment(name)) throw IOException("bad file name")
        val path = write(name, loaded.bytes)
        val mime = HubAttachmentNames.mimeOf(loaded.mime.ifBlank { ref.mime })
        val actual = path.substringAfterLast('/')
        var galleryPath: String? = null
        var galleryFailed = false
        if (HubGalleryPlacement.target(mime) != null) {
            try {
                galleryPath = gallery.insert(actual, mime, loaded.bytes)
            } catch (e: Exception) {
                galleryFailed = true
            }
        }
        return HubSavedAttachment(ref.fileId, actual, loaded.bytes.size.toLong(), mime, path, galleryPath, galleryFailed)
    }

    private data class Loaded(val name: String, val mime: String, val bytes: ByteArray)

    private fun load(config: RelayConfig, ref: HubAttachmentRef): Loaded {
        val file = transport.download(config.mcpUrl, config.token, config.sessionId, ref.fileId)
        if (file.size > maxBytes) throw RelayException(0, false, sizeReason())
        val bytes = when {
            file.bytes != null -> file.bytes
            !file.downloadUrl.isNullOrBlank() -> transport.fetchUnsigned(file.downloadUrl, maxBytes)
            else -> throw RelayException(200, false, "empty download")
        }
        if (bytes.isEmpty()) throw RelayException(200, false, "empty file")
        if (bytes.size.toLong() > maxBytes) throw RelayException(0, false, sizeReason())
        val sha = file.sha256?.takeIf { SHA.matches(it) } ?: ref.sha256?.takeIf { SHA.matches(it) }
        if (sha != null && !sha.equals(sha256(bytes), ignoreCase = true)) {
            throw RelayException(200, false, "hash mismatch")
        }
        return Loaded(
            name = file.name.ifBlank { ref.name.orEmpty() },
            mime = file.mime.ifBlank { ref.mime.orEmpty() },
            bytes = bytes,
        )
    }

    private fun <T> retry(block: () -> T): T {
        var last: Exception? = null
        repeat(attempts) { n ->
            try {
                return block()
            } catch (e: RelayException) {
                last = e
                if (!e.retryable || n == attempts - 1) throw e
                sleep(200L shl n)
            } catch (e: IOException) {
                last = e
                if (n == attempts - 1) throw e
                sleep(200L shl n)
            }
        }
        throw last ?: IOException("download failed")
    }

    private fun missing(ref: HubAttachmentRef, why: String) =
        HubMissingAttachment(ref.fileId, ref.name?.let { HubAttachmentNames.safe(it, ref.fileId) }, why)

    private fun sizeReason() = "larger than ${maxBytes shr 20} MB"

    private fun reason(e: Exception): String {
        val raw = when (e) {
            is RelayException -> e.message ?: "download failed"
            is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "network"
            else -> e.message?.takeIf { it.isNotBlank() && it.length <= 80 } ?: "download failed"
        }
        val cleaned = raw
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("(?i)bearer\\s+\\S+"), "")
            .replace(Regex("\\bda_[A-Za-z0-9]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifBlank { "download failed" }.take(120)
    }

    companion object {
        private val SHA = Regex("^[A-Fa-f0-9]{64}$")

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** 附件准备失败也不取消指令：正文里写上缺失，任务照样开始。 */
object HubOwnerLaunch {
    fun decorate(instruction: HubInstruction, prepare: (HubInstruction) -> HubPreparedTask): HubPreparedTask {
        if (instruction.attachments.isEmpty()) return HubPreparedTask(instruction.text, null)
        return try {
            prepare(instruction)
        } catch (e: Exception) {
            HubAttachmentText.failed(instruction)
        }
    }
}
