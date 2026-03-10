package com.example.networkhub.domain.models

/**
 * Sealed state machine representing every possible lifecycle state of the
 * Network Hub service. The UI layer observes a [kotlinx.coroutines.flow.StateFlow]
 * of this type and renders itself reactively based on transitions.
 *
 * States progress linearly under normal operation:
 *   IDLE → HOTSPOT_STARTING → HOTSPOT_READY → SERVER_STARTING → RUNNING
 *
 * Any state may transition to ERROR. ERROR can transition back to IDLE
 * after the user acknowledges and dismisses the error.
 */
sealed class ServerStatus {

    /** No service is active. This is the initial state on first launch. */
    object Idle : ServerStatus()

    /** The [WifiManager.startLocalOnlyHotspot] call has been dispatched;
     *  awaiting the [LocalOnlyHotspotCallback.onStarted] callback. */
    object HotspotStarting : ServerStatus()

    /**
     * The hotspot SoftAP is active and the [HotspotInfo] credentials are available.
     * The FTP server bind is being initiated on a background IO dispatcher.
     *
     * @param info The system-generated SSID and password for this session.
     */
    data class HotspotReady(val info: HotspotInfo) : ServerStatus()

    /**
     * The FTP server socket listener is being bound to port 2121 and the mDNS
     * service registration is in progress.
     *
     * @param info Hotspot credentials, retained for UI display continuity.
     */
    data class ServerStarting(val info: HotspotInfo) : ServerStatus()

    /**
     * All subsystems are fully operational:
     * - LocalOnlyHotspot SoftAP is broadcasting
     * - MinimalFTP server is listening on [HotspotInfo.ftpPort]
     * - NsdManager has registered the _ftp._tcp mDNS service record
     * - PARTIAL_WAKE_LOCK is held
     * - MulticastLock is held
     *
     * @param info Hotspot credentials for UI display and QR code generation.
     * @param connectedClients Live count of authenticated FTP sessions.
     */
    data class Running(
        val info: HotspotInfo,
        val connectedClients: Int = 0
    ) : ServerStatus()

    /**
     * A non-recoverable error has occurred. The service has been torn down
     * and all locks/reservations have been released.
     *
     * @param message Human-readable description of the failure, suitable for display.
     * @param cause   The underlying exception, if available, for debug logging.
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : ServerStatus()

    // ── Convenience helpers ──────────────────────────────────────────────────

    /** Returns true if the server is in any active (non-idle, non-error) state. */
    val isActive: Boolean get() = this is HotspotStarting
            || this is HotspotReady
            || this is ServerStarting
            || this is Running

    /** Returns the [HotspotInfo] if currently available, null otherwise. */
    val hotspotInfo: HotspotInfo?
        get() = when (this) {
            is HotspotReady    -> info
            is ServerStarting  -> info
            is Running         -> info
            else               -> null
        }
}
