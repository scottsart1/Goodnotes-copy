package com.inkwell.notes.export.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** [FolderNode] over a Storage Access Framework document (Drive, Files, SD card, USB...). */
internal class DocumentFolderNode(private val context: Context, private val doc: DocumentFile) : FolderNode {
    override val name: String get() = guard { doc.name.orEmpty() }
    override val isDirectory: Boolean get() = guard { doc.isDirectory }

    override fun children(): List<FolderNode> = guard { doc.listFiles().map { DocumentFolderNode(context, it) } }

    override fun createDirectory(name: String): FolderNode? = guard { doc.createDirectory(name)?.let { DocumentFolderNode(context, it) } }

    override fun createFile(mimeType: String, name: String): FolderNode? = guard {
        // A MIME type matching the extension makes providers keep the name exactly as given.
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: mimeType.substringBefore(';').trim()
        doc.createFile(mime, name)?.let { DocumentFolderNode(context, it) }
    }

    override fun openOutput(): OutputStream = guard {
        context.contentResolver.openOutputStream(doc.uri, "w") ?: throw IOException("The sync folder refused the file.")
    }

    override fun openInput(): InputStream = guard {
        context.contentResolver.openInputStream(doc.uri) ?: throw IOException("The sync folder refused the file.")
    }

    override fun delete(): Boolean = guard { doc.delete() }

    override fun renameTo(name: String): Boolean = guard { runCatching { doc.renameTo(name) }.getOrDefault(false) }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw FolderRevokedException(e)
    } catch (e: FileNotFoundException) {
        throw FolderUnavailableException(e)
    } catch (e: IllegalArgumentException) {
        throw FolderUnavailableException(e)
    } catch (e: IllegalStateException) {
        throw FolderUnavailableException(e)
    }
}

/** Opens the user's chosen tree, telling a revoked permission apart from a temporarily missing folder. */
internal class SafFolderAccess(private val context: Context) : FolderAccess {
    override fun root(treeUri: String): FolderNode {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: throw FolderRevokedException()
        if (!hasWritePermission(context, uri)) throw FolderRevokedException()
        val doc = try {
            DocumentFile.fromTreeUri(context, uri)
        } catch (e: SecurityException) {
            throw FolderRevokedException(e)
        } catch (e: IllegalArgumentException) {
            throw FolderRevokedException(e)
        } ?: throw FolderRevokedException()
        val ok = try {
            doc.exists() && doc.isDirectory
        } catch (e: SecurityException) {
            throw FolderRevokedException(e)
        }
        if (!ok) throw FolderUnavailableException()
        return DocumentFolderNode(context, doc)
    }

    companion object {
        fun hasWritePermission(context: Context, uri: Uri): Boolean =
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

        const val RW_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
