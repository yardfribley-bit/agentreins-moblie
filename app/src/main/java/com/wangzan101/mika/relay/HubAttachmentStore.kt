package com.wangzan101.mika.relay

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.core.relay.HubAttachmentNames
import com.wangzan101.mika.core.relay.HubGalleryPlacement
import com.wangzan101.mika.core.relay.HubGalleryStore
import com.wangzan101.mika.core.workspace.SandboxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 把 Hub 附件写进这次任务的工作区 inbox/。模型在沙箱里看到的是 `/workspace/inbox/<名字>`。
 * 经文件关口新建，不跟着链接写到工作区外面。
 */
class HubInboxWriter(private val context: Context, private val conversationId: String) {
    fun write(name: String, bytes: ByteArray): String = runBlocking {
        val dirs = SandboxDirs(context)
        dirs.tree.shared {
            withContext(Dispatchers.IO) {
                if (!HubAttachmentNames.isSafeSegment(name)) throw IOException("bad file name")
                val workspace = dirs.workspace(conversationId)
                workspace.mkdirs()
                val (actual, file) = dirs.gate.createNumbered(workspace, "inbox", name)
                file.use {
                    try {
                        it.write(bytes)
                    } catch (e: Throwable) {
                        runCatching { it.delete() }
                        throw e
                    }
                }
                "${SandboxPaths.WORKSPACE}/inbox/$actual"
            }
        }
    }
}

/**
 * 图片、视频写进系统相册（Pictures/AgentReins、Movies/AgentReins）。
 * 用 MediaStore 的相对路径，应用自己插入的条目不需要存储权限，Android 10 及以上（本应用 minSdk 30）可用。
 */
class MediaStoreGallery(private val context: Context) : HubGalleryStore {
    override fun insert(name: String, mime: String, bytes: ByteArray): String {
        if (!HubAttachmentNames.isSafeSegment(name)) throw IOException("bad file name")
        val target = HubGalleryPlacement.target(mime) ?: throw IOException("not media")
        val collection = when (target.kind) {
            HubGalleryPlacement.Kind.Image -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            HubGalleryPlacement.Kind.Video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IOException("gallery insert failed")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IOException("gallery insert failed")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        val actual = displayName(uri) ?: name
        return target.location(actual)
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
}
