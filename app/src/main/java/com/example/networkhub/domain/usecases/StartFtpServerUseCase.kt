package com.example.networkhub.domain.usecases

import android.net.Uri
import com.example.networkhub.data.storage.FtpVirtualFileSystem
import com.guichaguri.minimalftp.FTPServer
import com.guichaguri.minimalftp.api.IFileSystem
import com.guichaguri.minimalftp.impl.NoOpAuthenticator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain use case that manages the complete lifecycle of the embedded
 * MinimalFTP server instance.
 *
 * Architecture notes:
 *
 * - Port 2121 is selected deliberately to avoid port 21, which requires root
 *   privileges to bind on a Linux kernel environment.
 * - The server is bound to the hotspot gateway interface (192.168.43.1) rather
 *   than 0.0.0.0/localhost to restrict access to LAN clients only.
 * - [NoOpAuthenticator] permits anonymous access over the isolated offline LAN.
 *   For environments requiring authentication, substitute [UserAuthenticator].
 * - The server runs on [Dispatchers.IO] to prevent blocking the main thread
 *   during socket accept() calls and file I/O.
 *
 * @param ftpVirtualFileSystem The SAF-backed virtual filesystem that translates
 *   FTP path commands into Android DocumentFile operations.
 */
@Singleton
class StartFtpServerUseCase @Inject constructor(
    private val ftpVirtualFileSystem: FtpVirtualFileSystem
) {

    companion object {
        const val FTP_PORT = 2121
        const val GATEWAY_IP = "192.168.43.1"
    }

    private var ftpServer: FTPServer? = null

    /**
     * Starts the FTP server on port [FTP_PORT] bound to the hotspot gateway interface.
     *
     * This suspend function offloads the blocking [FTPServer.listenSync] call to the
     * IO dispatcher. The server runs until [stop] is called or an exception is thrown.
     *
     * @param rootUri The persisted SAF tree URI representing the SD card root,
     *   or null to fall back to app-internal storage.
     * @throws IllegalStateException if the server is already running.
     */
    suspend fun execute(rootUri: Uri? = null) = withContext(Dispatchers.IO) {
        check(ftpServer == null || !ftpServer!!.isRunning) {
            "FTP server is already running. Call stop() before restarting."
        }

        // Bind the SAF-backed virtual filesystem, pointing it at the persisted
        // SD card root URI if available, otherwise fall back to internal storage.
        ftpVirtualFileSystem.setRootUri(rootUri)

        val server = FTPServer(NoOpAuthenticator()).apply {
            // Register our custom SAF-backed filesystem instead of the default
            // NativeFileSystem which cannot access SD card roots on Android 9+.
            addFileSystem("*", ftpVirtualFileSystem as IFileSystem<Any>)

            // Bind exclusively to the hotspot gateway interface to prevent
            // exposure on other network interfaces (e.g. mobile data).
            options.passiveAddress = InetAddress.getByName(GATEWAY_IP)
            options.passivePorts = intArrayOf(2200, 2300) // Ephemeral passive ports
        }

        ftpServer = server

        // listenSync is a blocking call — it runs until the server is shutdown.
        // We are intentionally on Dispatchers.IO so this is acceptable.
        try {
            server.listenSync(FTP_PORT)
        } catch (e: Exception) {
            ftpServer = null
            throw e
        }
    }

    /**
     * Gracefully shuts down the FTP server, closing all active client sessions
     * and releasing the bound socket. This method is thread-safe and idempotent.
     */
    fun stop() {
        ftpServer?.shutdown()
        ftpServer = null
    }

    /** Returns true if the server socket is currently bound and accepting connections. */
    val isRunning: Boolean get() = ftpServer?.isRunning == true
}
