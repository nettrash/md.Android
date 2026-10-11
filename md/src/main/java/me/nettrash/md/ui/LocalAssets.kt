/*
 * LocalAssets.kt
 * md (Android)
 *
 * Reading a file a document refers to by a relative path — an image for the
 * TextPack export, the `cover:` of an EPUB — from beside the document, with
 * the containment every md port enforces: the resolved file must lie inside
 * the document's own folder, so a `../…` that climbs out is "not found", never
 * a read.
 *
 * What "beside" can mean on Android is limited by the access md already holds,
 * and no new prompt is ever raised for it:
 *
 *  - a `file://` document: its folder on disk, canonicalised (so a symlink out
 *    of the folder is caught as well);
 *  - a document reached through a tree grant — a book article: its folder in
 *    that tree, walked one name at a time through the documents provider
 *    (from 1.6; before, such a document's references were simply not found);
 *  - any other `content://` document, opened through the picker one file at a
 *    time: nothing — md cannot list its siblings, so the reference stays as
 *    the author wrote it, the same honest outcome an unsaved document gets.
 */

package me.nettrash.md.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

internal object LocalAssets {

    /** The bytes of [relativePath] beside [docUri], or null — also null when
     *  there are more than [limit] of them, found out by reading at most one
     *  byte past it (the EPUB cover's cap), never the whole file. */
    fun read(context: Context, docUri: Uri?, relativePath: String, limit: Int? = null): ByteArray? {
        val uri = docUri ?: return null
        return when (uri.scheme) {
            "file" -> readFile(uri, relativePath, limit)
            "content" -> readFromTree(context, uri, relativePath, limit)
            else -> null
        }
    }

    /** All of [input], or — with a [limit] — null when it holds more than
     *  [limit] bytes, having read no more than one byte past it. */
    fun readBounded(input: InputStream, limit: Int?): ByteArray? {
        if (limit == null) return input.readBytes()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() <= limit) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit + 1L - out.size()).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return if (out.size() > limit) null else out.toByteArray()
    }

    private fun readFile(uri: Uri, relativePath: String, limit: Int?): ByteArray? {
        val folder = File(uri.path ?: return null).parentFile ?: return null
        val base = runCatching { folder.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { File(base, relativePath).canonicalFile }.getOrNull() ?: return null
        if (candidate.path != base.path && !candidate.path.startsWith(base.path + File.separator)) return null
        return candidate.takeIf { it.isFile }?.let { runCatching { it.inputStream().use { input -> readBounded(input, limit) } }.getOrNull() }
    }

    /** Walk [relativePath] from the folder of a tree-derived [docUri], one
     *  child lookup per name; never above the document's folder. */
    private fun readFromTree(context: Context, docUri: Uri, relativePath: String, limit: Int?): ByteArray? = runCatching {
        val resolver = context.contentResolver
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(docUri) }.getOrNull() ?: return null
        val tree = DocumentsContract.buildTreeDocumentUri(docUri.authority, treeId)
        val ids = DocumentsContract.findDocumentPath(resolver, docUri)?.path ?: return null
        if (ids.size < 2) return null
        val stack = ids.dropLast(1).toMutableList()   // the tree root … the document's folder
        val folderDepth = stack.size
        var isFile = false
        for (segment in relativePath.split('/')) {
            when (segment) {
                "", "." -> continue
                ".." -> {
                    if (stack.size <= folderDepth) return null
                    stack.removeAt(stack.lastIndex)
                    isFile = false
                }
                else -> {
                    if (isFile) return null
                    val (childId, childIsDir) = child(context, tree, stack.last(), segment) ?: return null
                    stack.add(childId)
                    isFile = !childIsDir
                }
            }
        }
        if (stack.size <= folderDepth || !isFile) return null
        val target = DocumentsContract.buildDocumentUriUsingTree(tree, stack.last())
        resolver.openInputStream(target)?.use { readBounded(it, limit) }
    }.getOrNull()

    /** The child of [parentId] named exactly [name]: its id and whether it is a folder. */
    private fun child(context: Context, tree: Uri, parentId: String, name: String): Pair<String, Boolean>? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        context.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    return c.getString(0) to (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                }
            }
        }
        return null
    }
}
