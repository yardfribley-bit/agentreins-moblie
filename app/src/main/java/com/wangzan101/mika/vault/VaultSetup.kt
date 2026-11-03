package com.wangzan101.mika.vault

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore

/**
 * 第一次把知识库放在这台手机上。不假设用户已经有哪个库、也不写死任何人的库名。
 * [PLAIN_FOLDER] 只是还没有库时，在「文档」里新建的文件夹名。
 */
object VaultSetup {
    const val OBSIDIAN_PACKAGE = "md.obsidian"
    const val PLAIN_FOLDER = "AgentReins知识库"

    fun installed(pm: PackageManager): Boolean =
        runCatching { pm.getPackageInfo(OBSIDIAN_PACKAGE, 0) }.isSuccess

    fun storeIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$OBSIDIAN_PACKAGE"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun storeWebIntent(): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("https://play.google.com/store/apps/details?id=$OBSIDIAN_PACKAGE"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 系统文件夹选择器尽量停在刚建好的那个文件夹。 */
    fun plainFolderDocumentId(): String = "primary:Documents/$PLAIN_FOLDER"

    fun plainFolderInitialUri(): Uri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        plainFolderDocumentId(),
    )

    /** 在共享「文档」里建出文件夹，好让选择器能指到它。失败也不打断选择。 */
    fun ensurePlainFolder(context: Context) {
        runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "开始.md")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/$PLAIN_FOLDER/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return
            resolver.openOutputStream(uri)?.use { out ->
                out.write("在 AgentReins 里选择这个文件夹，它就是知识库。\n".toByteArray(Charsets.UTF_8))
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }
    }

    fun openStore(context: Context) {
        val market = storeIntent()
        val intent = if (market.resolveActivity(context.packageManager) != null) market else storeWebIntent()
        runCatching { context.startActivity(intent) }
    }
}
