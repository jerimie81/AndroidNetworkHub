package com.example.networkhub.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.networkhub.App
import com.example.networkhub.R
import com.example.networkhub.data.network.NsdBroadcaster
import com.example.networkhub.domain.models.ServerStatus
import com.example.networkhub.domain.usecases.ManageWakeLockUseCase
import com.example.networkhub.domain.usecases.StartFtpServerUseCase
import com.example.networkhub.domain.usecases.StartHotspotUseCase
import com.example.networkhub.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The central orchestrator of the Network Hub. This foreground service owns the
 * complete lifecycle of all hardware resources and network subsystems.
 *
 * # Why a Foreground Service?
 *
 * Standard background threads and [AsyncTask] are aggressively terminated by the
 * Android 9 memory manager when the user navigates away, triggering App Standby or
 * Doze mode. By invoking [startForeground], the service requests explicit immunity
 * from the OOM killer. The mandatory persistent notification anchors this immunity
 * and informs the user that resources are actively in use.
 *
 * # Subsystem Startup Sequence (onStartCommand)
 *
 * The following order is critical and must not be reordered:
 * 1. Acquire PARTIAL_WAKE_LOCK — prevents CPU suspend during all subsequent operations
 * 2. Acquire MulticastLock    — prevents mDNS packet filtering
 * 3. Start LocalOnlyHotspot   — establishes the SoftAP and obtains credentials
 * 4. Start FTP Server         — binds port 2121 AFTER the hotspot is confirmed live
 * 5. Register mDNS service    — announces the server AFTER the socket is bound
 *
 * # Teardown (onDestroy)
 *
 * All operations are reversed symmetrically to prevent resource leaks:
 * Unregister mDNS → Stop FTP → Close Hotspot → Release Locks
 *
 * An unbalanced PARTIAL_WAKE_LOCK or MulticastLock that survives service death
 * drains the battery to zero over a matter of hours.
 */
@AndroidEntryPoint
class HubForegroundService : Service() {

    companion object {
        private const val TAG = "HubForegroundService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.networkhub.action.START"
        const val ACTION_STOP  = "com.example.networkhub.action.STOP"

        // Shared StateFlow exposed as a singleton so the ViewModel can observe
        // service state without binding (bindService is not required for FGS).
        private val _statusFlow = MutableStateFlow<ServerStatus>(ServerStatus.Idle)
        val statusFlow: StateFlow<ServerStatus> = _statusFlow.asStateFlow()
    }

    @Inject lateinit var startHotspotUseCase: StartHotspotUseCase
    @Inject lateinit var startFtpServerUseCase: StartFtpServerUseCase
    @Inject lateinit var wakeLockUseCase: ManageWakeLockUseCase
    @Inject lateinit var nsdBroadcaster: NsdBroadcaster

    // SupervisorJob ensures that a failure in one child coroutine does not
    // cancel the entire scope, enabling independent error handling per subsystem.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Service Lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HubForegroundService created")
        startForeground(NOTIFICATION_ID, buildNotification("Initialising…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAllSubsystems()
            ACTION_STOP  -> stopSelf()
        }
        // START_STICKY ensures the OS restarts the service after a process kill,
        // re-delivering the last intent to resume the server automatically.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "HubForegroundService destroying — executing symmetric teardown")
        teardownAllSubsystems()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Subsystem Startup ─────────────────────────────────────────────────────

    private fun startAllSubsystems() {
        serviceScope.launch {
            try {
                // Step 1: Wake Lock — must be first to protect subsequent operations.
                wakeLockUseCase.acquire()
                Log.i(TAG, "[1/5] PARTIAL_WAKE_LOCK acquired")

                // Step 2: MulticastLock — must precede hotspot to avoid a race condition
                // where mDNS packets could be filtered before the lock is taken.
                nsdBroadcaster.register(StartFtpServerUseCase.FTP_PORT) // acquires lock internally
                Log.i(TAG, "[2/5] MulticastLock acquired and mDNS registration initiated")

                // Step 3: Hotspot — SoftAP activation. Await the onStarted callback.
                _statusFlow.value = ServerStatus.HotspotStarting
                updateNotification("Starting hotspot…")

                startHotspotUseCase.execute().collect { hotspotInfo ->
                    Log.i(TAG, "[3/5] Hotspot active — SSID: ${hotspotInfo.ssid}")
                    _statusFlow.value = ServerStatus.HotspotReady(hotspotInfo)
                    updateNotification("Hotspot active — starting FTP server…")

                    // Step 4: FTP Server — bind AFTER hotspot is confirmed.
                    _statusFlow.value = ServerStatus.ServerStarting(hotspotInfo)
                    Log.i(TAG, "[4/5] Binding FTP server on port ${StartFtpServerUseCase.FTP_PORT}…")

                    // Launch FTP in a separate coroutine; listenSync() is blocking.
                    launch {
                        startFtpServerUseCase.execute(rootUri = null)
                    }

                    // Step 5: Brief delay to allow socket bind, then update state.
                    kotlinx.coroutines.delay(500)
                    _statusFlow.value = ServerStatus.Running(hotspotInfo)
                    Log.i(TAG, "[5/5] All subsystems operational.")
                    updateNotification("Running — ${hotspotInfo.toFtpUrl()}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Subsystem startup failed: ${e.message}", e)
                val errorMsg = e.message ?: "An unknown error occurred during server startup"
                _statusFlow.value = ServerStatus.Error(errorMsg, e)
                updateNotification("Error: $errorMsg")
                teardownAllSubsystems()
            }
        }
    }

    // ── Symmetric Teardown ────────────────────────────────────────────────────

    /**
     * Reverses all subsystem operations in the inverse of the startup order.
     * Called both from [onDestroy] and from the error recovery path.
     *
     * Execution order (reverse of startup):
     * mDNS unregister → FTP stop → Hotspot stop → MulticastLock release → WakeLock release
     */
    private fun teardownAllSubsystems() {
        try { nsdBroadcaster.unregister() } catch (e: Exception) {
            Log.e(TAG, "Error unregistering mDNS: ${e.message}", e)
        }
        try { startFtpServerUseCase.stop() } catch (e: Exception) {
            Log.e(TAG, "Error stopping FTP server: ${e.message}", e)
        }
        try { startHotspotUseCase.stop() } catch (e: Exception) {
            Log.e(TAG, "Error stopping hotspot: ${e.message}", e)
        }
        try { wakeLockUseCase.release() } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock: ${e.message}", e)
        }

        _statusFlow.value = ServerStatus.Idle
        Log.i(TAG, "Teardown complete — all subsystems deactivated")
    }

    // ── Notification Management ───────────────────────────────────────────────

    private fun buildNotification(contentText: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HubForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, App.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Network Hub")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_hub_notification)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                R.drawable.ic_hub_notification,
                "Stop",
                stopIntent
            )
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }
}
