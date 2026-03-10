package com.example.networkhub.data.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges the Storage Access Framework (SAF) to the rest of the application,
 * providing persistent, reboots-safe access to the external SD card root.
 *
 * # The SAF Necessity
 *
 * Android 9 prohibits direct POSIX path writes to removable SD card roots.
 * Any attempt to write to `/storage/XXXX-XXXX/` via [java.io.FileOutputStream]
 * throws [java.lang.SecurityException] even with WRITE_EXTERNAL_STORAGE declared.
 *
 * The only compliant mechanism is to prompt the user to grant tree-level access
 * via [Intent.ACTION_OPEN_DOCUMENT_TREE], then persist the returned [Uri] with
 * [ContentResolver.takePersistableUriPermission]. This persisted URI survives
 * application restarts and can be used to instantiate [DocumentFile] trees.
 *
 * # Lifecycle Warning
 *
 * URI permissions obtained via [takePersistableUriPermission] are stored by the
 * OS in a system-managed grant table, NOT in application SharedPreferences.
 * We store the Uri string in SharedPreferences only as a lookup key; the actual
 * access grant is held by the OS. If the user uninstalls or uses a device-specific
 * "Clear Permissions" flow, the grant is revoked and a new SAF prompt is required.
 */
@Singleton
class SafStorageBridge @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "SafStorageBridge"
        private const val PREFS_NAME = "networkhub_storage_prefs"
        private const val KEY_SD_CARD_URI = "sd_card_root_uri"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns an [Intent] that launches the system-level directory picker.
     *
     * The caller (typically [MainActivity]) must use [startActivityForResult] with
     * this intent, then pass the result to [onDocumentTreeResult].
     *
     * This intent pauses the application lifecycle and presents the OS file picker.
     * The user must navigate to the SD card root and tap "Use this folder" / "Allow".
     */
    fun buildOpenDocumentTreeIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // On Android 8+, this hint pre-selects the external storage in the picker.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }

    /**
     * Processes the result from [Intent.ACTION_OPEN_DOCUMENT_TREE] and persists
     * both the access grant and the URI string.
     *
     * This must be called from [Activity.onActivityResult] when the result code
     * is [Activity.RESULT_OK]. Calling [takePersistableUriPermission] immediately
     * is critical — if deferred, the transient grant may be garbage collected
     * before it can be persisted.
     *
     * @param uri The [Uri] returned from the OS document tree picker.
     * @return true if the URI was successfully persisted, false on any error.
     */
    fun onDocumentTreeResult(uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            prefs.edit()
                .putString(KEY_SD_CARD_URI, uri.toString())
                .apply()

            Log.i(TAG, "SD card root URI persisted: $uri")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to take persistable permission for URI: $uri", e)
            false
        }
    }

    /**
     * Returns a [DocumentFile] tree rooted at the previously persisted SD card URI,
     * or null if no permission has been granted yet or the URI is no longer valid.
     *
     * The returned [DocumentFile] provides the entry point for all SAF traversal
     * operations — directory listing, file creation, and stream resolution.
     */
    fun getSdCardRootDocument(): DocumentFile? {
        val uriString = prefs.getString(KEY_SD_CARD_URI, null) ?: run {
            Log.w(TAG, "No SD card URI persisted — user must grant access first")
            return null
        }

        val uri = Uri.parse(uriString)

        // Verify the persisted grant is still valid before returning.
        val isGranted = context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri
                    && permission.isReadPermission
                    && permission.isWritePermission
        }

        if (!isGranted) {
            Log.w(TAG, "Persisted URI permission has been revoked — clearing stored URI")
            prefs.edit().remove(KEY_SD_CARD_URI).apply()
            return null
        }

        return DocumentFile.fromTreeUri(context, uri)?.also {
            Log.d(TAG, "SAF root DocumentFile resolved: ${it.uri}")
        }
    }

    /**
     * Opens an [java.io.OutputStream] for writing to the given [DocumentFile] URI.
     *
     * Used by [FtpVirtualFileSystem] to map FTP STOR commands to physical writes.
     *
     * @param uri The [Uri] of the target [DocumentFile].
     * @return An open [java.io.OutputStream], or null if the resolver fails.
     */
    fun openOutputStream(uri: Uri): java.io.OutputStream? {
        return try {
            context.contentResolver.openOutputStream(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open OutputStream for URI: $uri", e)
            null
        }
    }

    /**
     * Opens an [java.io.InputStream] for reading from the given [DocumentFile] URI.
     *
     * Used by [FtpVirtualFileSystem] to map FTP RETR commands to physical reads.
     *
     * @param uri The [Uri] of the source [DocumentFile].
     * @return An open [java.io.InputStream], or null if the resolver fails.
     */
    fun openInputStream(uri: Uri): java.io.InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open InputStream for URI: $uri", e)
            null
        }
    }

    /** Returns true if a valid, persisted SD card URI is available. */
    val hasSdCardAccess: Boolean get() = getSdCardRootDocument() != null
}
