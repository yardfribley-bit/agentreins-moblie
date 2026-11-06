package com.wangzan101.mika.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.wangzan101.mika.core.vault.VaultFiles
import com.wangzan101.mika.core.vault.VaultPaths
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 用用户选出的目录树读写 Markdown。只沿着相对路径往下走,不接受 `..`。
 */
class SafVaultFiles(private val context: Context, private val tree: () -> Uri?) : VaultFiles {
    private val resolver get() = context.contentResolver

    override fun listNotes(root: String, limit: Int): List<Pair<String, String>> {
        val tree = tree() ?: return emptyList()
        val base = VaultPaths.normalizeRoot(root)
        val dir = find(tree, base.split("/"), create = false) ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        walk(tree, dir, base, out, limit, 0)
        return out
    }

    override fun listNames(dir: String): List<String> {
        val tree = tree() ?: return emptyList()
        val parts = dir.trim('/').split("/").filter { it.isNotBlank() }
        if (parts.any { it == ".." || it == "." }) return emptyList()
        val parent = find(tree, parts, create = false) ?: return emptyList()
        return children(tree, parent).map { it.name }
    }

    override fun read(path: String): String? {
        if (!safeFile(path)) return null
        val tree = tree() ?: return null
        val doc = find(tree, path.split("/"), create = false) ?: return null
        return readDoc(doc)
    }

    override fun write(path: String, text: String) {
        if (!safeFile(path)) error("outside vault")
        val tree = tree() ?: error("no vault")
        val doc = find(tree, path.split("/"), create = true) ?: error("could not create $path")
        resolver.openOutputStream(doc, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("could not write")
    }

    private fun safeFile(path: String): Boolean {
        val parts = path.split("/")
        return (path.endsWith(".md") || path.endsWith(".md.txt") || path.matches(Regex(".*\\.md \\(\\d+\\)\\.txt"))) && parts.none { it.isBlank() || it == "." || it == ".." }
    }

    private fun walk(tree: Uri, dir: Uri, relative: String, out: MutableList<Pair<String, String>>, limit: Int, depth: Int) {
        if (out.size >= limit || depth > 8) return
        for (child in children(tree, dir)) {
            if (out.size >= limit) return
            val path = "$relative/${child.name}"
            if (child.dir) {
                if (child.name == "runs" && relative.endsWith("/raw")) continue
                walk(tree, child.uri, path, out, limit, depth + 1)
            } else if (child.name.endsWith(".md") || child.name.endsWith(".md.txt") || child.name.matches(Regex(".*\\.md \\(\\d+\\)\\.txt"))) {
                readDoc(child.uri)?.let { out += path to it }
            }
        }
    }

    private data class Child(val name: String, val uri: Uri, val dir: Boolean)

    private fun children(tree: Uri, parent: Uri): List<Child> {
        val id = DocumentsContract.getDocumentId(parent)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val out = ArrayList<Child>()
        resolver.query(uri, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        ), null, null, null)?.use { c ->
            val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                if (name == "." || name == "..") continue
                val doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(idCol))
                val dir = c.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR
                out += Child(name, doc, dir)
            }
        }
        return out
    }

    private fun find(tree: Uri, segments: List<String>, create: Boolean): Uri? {
        var parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        segments.forEachIndexed { index, name ->
            if (name.isBlank() || name == "." || name == "..") return null
            val existing = children(tree, parent).firstOrNull { it.name == name }
            val last = index == segments.lastIndex
            parent = when {
                existing != null -> existing.uri
                !create -> return null
                last -> {
                    DocumentsContract.createDocument(resolver, parent, "text/markdown", name)
                        ?: DocumentsContract.createDocument(resolver, parent, "text/plain", name)
                        ?: return null
                }
                else -> DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name) ?: return null
            }
        }
        return parent
    }

    private fun readDoc(uri: Uri): String? = resolver.openInputStream(uri)?.use { input ->
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            val buf = CharArray(4096)
            val text = StringBuilder()
            while (text.length < 48_000) {
                val n = reader.read(buf, 0, minOf(buf.size, 48_000 - text.length))
                if (n < 0) break
                if (n == 0) continue
                text.append(buf, 0, n)
            }
            text.toString()
        }
    }
}
