package com.wangzan101.mika.core.relay

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException

/** 下载结果。 [message] 写给模型，必须写明文件落在哪里。 */
data class UrlDownloadResult(
    val ok: Boolean,
    val message: String,
    val workspacePath: String? = null,
    val gallery: String? = null,
    val bytes: Long = 0,
    val mime: String? = null,
    val name: String? = null,
)

fun interface UrlFetcher {
    fun fetch(url: String, maxBytes: Long): Fetched
}

data class Fetched(val bytes: ByteArray, val mime: String?, val name: String?)

fun interface UrlDownloadPort {
    fun download(url: String): UrlDownloadResult
}

/** 只接受 https，超限整段拒绝。图片和视频再放进相册。 */
class UrlWorkspaceDownload(
    private val fetch: UrlFetcher,
    private val write: (name: String, bytes: ByteArray) -> String,
    private val gallery: HubGalleryStore,
    private val maxBytes: Long = HUB_ATTACHMENT_MAX_BYTES,
) {
    fun download(url: String): UrlDownloadResult {
        val problem = HttpsUrls.reject(url)
        if (problem != null) return UrlDownloadResult(false, problem)
        val loaded = try {
            fetch.fetch(url.trim(), maxBytes)
        } catch (e: Exception) {
            return UrlDownloadResult(false, reason(e))
        }
        if (loaded.bytes.isEmpty()) return UrlDownloadResult(false, "empty file")
        if (loaded.bytes.size.toLong() > maxBytes) return UrlDownloadResult(false, "larger than ${maxBytes shr 20} MB")
        val mime = HubAttachmentNames.mimeOf(loaded.mime)
        val name = HubAttachmentNames.safe(loaded.name ?: HttpsUrls.leaf(url), "download")
        if (!HubAttachmentNames.isSafeSegment(name)) return UrlDownloadResult(false, "bad file name")
        val path = try {
            write(name, loaded.bytes)
        } catch (e: Exception) {
            return UrlDownloadResult(false, reason(e))
        }
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
        val saved = HubSavedAttachment(fileId = "url", name = actual, size = loaded.bytes.size.toLong(), mime = mime, workspacePath = path, gallery = galleryPath, galleryFailed = galleryFailed)
        val message = buildString {
            append("Saved ").append(actual).append(" (").append(HubAttachmentText.sizeLabel(saved.size)).append(").\n")
            append("workspace: ").append(path).append('\n')
            append("gallery: ").append(if (galleryFailed) "insert failed" else galleryPath ?: "none").append('\n')
            append("Use the workspace path. ")
            if (galleryPath != null) append("The file is also in the phone gallery at ").append(galleryPath).append('.')
        }.trim()
        return UrlDownloadResult(true, message, path, galleryPath, saved.size, mime, actual)
    }

    private fun reason(e: Exception): String {
        val raw = e.message?.takeIf { it.isNotBlank() } ?: "download failed"
        return raw.replace(Regex("https?://\\S+"), "").replace(Regex("\\s+"), " ").trim().ifBlank { "download failed" }.take(120)
    }
}

object HttpsUrls {
    fun reject(raw: String): String? {
        val url = raw.trim()
        if (url.length > 2000) return "url too long"
        val parsed = url.toHttpUrlOrNull() ?: return "https only"
        if (parsed.scheme != "https") return "https only"
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return "https only"
        if (parsed.host.isBlank()) return "https only"
        return null
    }

    fun leaf(raw: String): String =
        raw.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotBlank() }.orEmpty()
}
