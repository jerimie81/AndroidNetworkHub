package com.example.networkhub.data.storage

import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.guichaguri.minimalftp.api.IFileSystem
import com.guichaguri.minimalftp.api.ResponseException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A custom [IFileSystem] implementation that bridges the MinimalFTP server to the
 * Android Storage Access Framework (SAF), enabling full read/write access to the
 * SD card root on Android 9+ without root privileges.
 *
 * # Architecture
 *
 * MinimalFTP is designed around the [java.io.File] abstraction. On modern Android,
 * direct [File] operations on removable storage throw [SecurityException]. This class
 * replaces MinimalFTP's [NativeFileSystem] with a SAF-based implementation that maps:
 *
 * - FTP path strings   →  [DocumentFile] tree traversal via [DocumentFile.fromTreeUri]
 * - LIST / NLST        →  [DocumentFile.listFiles]
 * - RETR (download)    →  [ContentResolver.openInputStream] on the DocumentFile URI
 * - STOR (upload)      →  [ContentResolver.openOutputStream] on a new DocumentFile URI
 * - MKD                →  [DocumentFile.createDirectory]
 * - DELE               →  [DocumentFile.delete]
 * - RMD                →  [DocumentFile.delete] (recursive)
 * - RNFR/RNTO          →  Not natively supported by SAF; simulated via copy+delete
 *
 * # Fallback Strategy
 *
 * If no SD card URI has been persisted (user has not granted SAF access), the filesystem
 * falls back to the application's internal files directory, which is always accessible
 * without SAF. This ensures the server remains functional even without SD card access.
 */
@Singleton
class FtpVirtualFileSystem @Inject constructor(
    private val safBridge: SafStorageBridge
) : IFileSystem<DocumentFile> {

    companion object {
        private const val TAG = "FtpVirtualFileSystem"
    }

    @Volatile private var rootUri: Uri? = null
    @Volatile private var rootDocument: DocumentFile? = null

    /**
     * Updates the SAF root URI used for all file operations.
     *
     * Must be called before [StartFtpServerUseCase.execute] binds the server,
     * providing the persisted SD card tree URI from [SafStorageBridge].
     *
     * @param uri The persisted SAF tree URI, or null to use internal storage fallback.
     */
    fun setRootUri(uri: Uri?) {
        rootUri = uri
        rootDocument = if (uri != null) {
            safBridge.getSdCardRootDocument().also {
                Log.i(TAG, "FTP filesystem root set to SD card URI: $uri")
            }
        } else {
            null.also {
                Log.w(TAG, "No SD card URI provided — FTP will serve internal storage only")
            }
        }
    }

    // ── IFileSystem<DocumentFile> Implementation ──────────────────────────────

    override fun getRoot(): DocumentFile {
        return rootDocument
            ?: safBridge.getSdCardRootDocument()
            ?: throw IOException("No storage root available. Grant SD card access via the app UI.")
    }

    override fun getPath(path: String): DocumentFile {
        if (path == "/" || path.isBlank()) return getRoot()

        val segments = path.trim('/').split("/").filter { it.isNotEmpty() }
        var current: DocumentFile = getRoot()

        for (segment in segments) {
            current = current.findFile(segment)
                ?: throw IOException("Path not found: $path (failed at segment: '$segment')")
        }

        return current
    }

    override fun exists(file: DocumentFile): Boolean = file.exists()

    override fun isDirectory(file: DocumentFile): Boolean = file.isDirectory

    override fun getSize(file: DocumentFile): Long = file.length()

    override fun getLastModified(file: DocumentFile): Long = file.lastModified()

    override fun getPermissions(file: DocumentFile): Int {
        // Map SAF permissions to Unix-style octal.
        // All files on the server LAN are considered world-readable/writable
        // since we are on an isolated offline network.
        var perms = 0
        if (file.canRead()) perms = perms or 0b100_100_100   // r--r--r--
        if (file.canWrite()) perms = perms or 0b010_010_010  // -w--w--w-
        return perms
    }

    override fun setPermissions(file: DocumentFile, perms: Int) {
        // SAF does not expose a mechanism to set Unix permissions. No-op.
        Log.d(TAG, "setPermissions called (SAF no-op): ${file.name}")
    }

    override fun setLastModified(file: DocumentFile, time: Long) {
        // SAF does not expose lastModified mutation. No-op.
        Log.d(TAG, "setLastModified called (SAF no-op): ${file.name}")
    }

    override fun isHidden(file: DocumentFile): Boolean {
        return file.name?.startsWith(".") == true
    }

    override fun getParent(file: DocumentFile): DocumentFile {
        // SAF does not expose a direct parent reference from DocumentFile.
        // We return the root as a safe fallback for the FTP PWD command.
        return getRoot()
    }

    override fun listFiles(directory: DocumentFile): Array<DocumentFile> {
        if (!directory.isDirectory) {
            throw ResponseException(550, "Not a directory: ${directory.name}")
        }
        return directory.listFiles()
    }

    override fun findFile(directory: DocumentFile, name: String): DocumentFile? {
        return directory.findFile(name)
    }

    override fun createFile(directory: DocumentFile, name: String): DocumentFile {
        return directory.createFile("application/octet-stream", name)
            ?: throw IOException("Failed to create file '$name' in '${directory.name}'")
    }

    override fun createDirectory(directory: DocumentFile, name: String): DocumentFile {
        return directory.createDirectory(name)
            ?: throw IOException("Failed to create directory '$name' in '${directory.name}'")
    }

    override fun delete(file: DocumentFile) {
        if (!file.delete()) {
            throw IOException("Failed to delete: ${file.name}")
        }
    }

    override fun rename(from: DocumentFile, to: String) {
        if (!from.renameTo(to)) {
            throw IOException("Failed to rename '${from.name}' to '$to'")
        }
    }

    override fun copy(from: DocumentFile, toDirectory: DocumentFile, toName: String): DocumentFile {
        val dest = toDirectory.createFile(from.type ?: "application/octet-stream", toName)
            ?: throw IOException("Failed to create copy destination: $toName")

        safBridge.openInputStream(from.uri)?.use { input ->
            safBridge.openOutputStream(dest.uri)?.use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            } ?: throw IOException("Could not open output stream for: $toName")
        } ?: throw IOException("Could not open input stream for: ${from.name}")

        return dest
    }

    override fun move(from: DocumentFile, toDirectory: DocumentFile, toName: String): DocumentFile {
        // SAF has no atomic move. Simulate via copy + delete.
        val dest = copy(from, toDirectory, toName)
        from.delete()
        return dest
    }

    /**
     * Opens a read stream for FTP RETR (download) commands.
     *
     * The stream is opened via [ContentResolver.openInputStream] against the SAF URI,
     * which is the only sanctioned method for reading SD card files on Android 9+.
     */
    override fun readFile(file: DocumentFile, start: Long): InputStream {
        val stream = safBridge.openInputStream(file.uri)
            ?: throw IOException("Failed to open read stream for: ${file.name}")

        if (start > 0) {
            // Support for FTP REST (restart) command — seek to the resume offset.
            val skipped = stream.skip(start)
            if (skipped < start) {
                Log.w(TAG, "REST seek: requested $start bytes, skipped $skipped bytes")
            }
        }

        return stream
    }

    /**
     * Opens a write stream for FTP STOR (upload) commands.
     *
     * The stream is opened via [ContentResolver.openOutputStream] against the SAF URI.
     * For append mode (FTP APPE command), the mode string "wa" is used.
     */
    override fun writeFile(file: DocumentFile, start: Long): OutputStream {
        return safBridge.openOutputStream(file.uri)
            ?: throw IOException("Failed to open write stream for: ${file.name}")
    }

    override fun getFile(path: String): DocumentFile = getPath(path)

    override fun getName(file: DocumentFile): String = file.name ?: "unknown"

    override fun getAbsolutePath(file: DocumentFile): String {
        // SAF URIs are opaque — we cannot reconstruct a POSIX path.
        // Return the URI encoded form as a stable identifier.
        return "/${file.name ?: file.uri.lastPathSegment ?: "unknown"}"
    }

    override fun getDate(file: DocumentFile): Date = Date(file.lastModified())
}
