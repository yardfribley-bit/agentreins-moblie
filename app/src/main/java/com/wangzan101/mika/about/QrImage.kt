package com.wangzan101.mika.about

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.wangzan101.mika.R

/**
 * 作者微信二维码。
 *
 * TODO: `res/drawable-nodpi/author_wechat_qr.jpg` 仍是上游作者的二维码，必须换成微信号 sjtdyt 自己的码，
 * 才能重新打开内测群扫码。在换成那张图之前 [SHOW_QR] 保持 false：关于页不展示这张图，也不写入相册。
 */
object QrImage {
    /** 换成 sjtdyt 的二维码后再改为 true。为 false 时界面不画码，[saveToPictures] 直接拒绝。 */
    val SHOW_QR: Boolean = false

    val RES = R.drawable.author_wechat_qr
    private const val FILE_NAME = "agentreins-author-wechat.jpg"

    /**
     * 存进相册的 Pictures/agentreins-moblie。经 MediaStore 写自己新建的图片不要存储权限(安卓 10 起如此,最低支持安卓 11)。
     * 写完前标成待定,别的 App 看不到半张图;失败时删掉那一条。
     * [SHOW_QR] 为 false 时不写:打包里的图还不是 sjtdyt 的码。
     */
    fun saveToPictures(context: Context): Boolean {
        if (!SHOW_QR) return false
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/agentreins-moblie")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return false
        return runCatching {
            resolver.openOutputStream(uri)!!.use { out -> context.resources.openRawResource(RES).use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
