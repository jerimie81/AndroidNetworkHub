package com.example.networkhub.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.networkhub.data.storage.SafStorageBridge
import com.example.networkhub.domain.models.ServerStatus
import com.example.networkhub.service.HubForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for [MainActivity]. Bridges the [HubForegroundService] singleton
 * [StateFlow] to the Compose UI layer via [viewModelScope]-scoped observation.
 *
 * This ViewModel intentionally contains no business logic — it is a pure
 * presentation-layer adapter that transforms service state into UI-observable
 * state and translates UI events into service intents.
 *
 * # StateFlow vs LiveData
 *
 * [StateFlow] is preferred over [LiveData] in this architecture because it
 * integrates natively with Kotlin coroutines and Jetpack Compose's
 * [collectAsStateWithLifecycle] collector, providing lifecycle-safe collection
 * without requiring explicit [observe] calls.
 *
 * # Context in ViewModel
 *
 * [ApplicationContext] is injected (never activity context) to avoid leaking
 * the UI layer into the ViewModel's longer-lived scope.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val safStorageBridge: SafStorageBridge
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
    }

    /**
     * Observable server status stream. Collects from the singleton [HubForegroundService.statusFlow]
     * and re-emits it as a [StateFlow] scoped to [viewModelScope].
     *
     * Using [SharingStarted.WhileSubscribed(5000)] retains the upstream subscription
     * for 5 seconds after the last observer disappears, preventing unnecessary restarts
     * during short UI interruptions such as screen rotation.
     */
    val serverStatus: StateFlow<ServerStatus> = HubForegroundService.statusFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ServerStatus.Idle
        )

    /** Returns true if the service is in any active state (hotspot or FTP running). */
    val isRunning: Boolean get() = serverStatus.value.isActive

    /** Returns true if a valid SAF permission for the SD card has been persisted. */
    val hasSdCardAccess: Boolean get() = safStorageBridge.hasSdCardAccess

    // ── Service Control ───────────────────────────────────────────────────────

    /**
     * Dispatches an [Intent] to start the [HubForegroundService].
     *
     * On Android 8.0+ (API 26+), [Context.startForegroundService] must be used
     * instead of [Context.startService] when starting from a non-service context.
     * The service is required by Android to call [startForeground] within 5 seconds
     * of receiving this intent or the system throws [ForegroundServiceDidNotStartInTimeException].
     */
    fun startServer() {
        Log.i(TAG, "User requested server start")
        val intent = Intent(context, HubForegroundService::class.java).apply {
            action = HubForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * Dispatches a STOP action to [HubForegroundService], triggering symmetric teardown
     * of all subsystems and releasing all hardware locks.
     */
    fun stopServer() {
        Log.i(TAG, "User requested server stop")
        val intent = Intent(context, HubForegroundService::class.java).apply {
            action = HubForegroundService.ACTION_STOP
        }
        context.startService(intent)
    }

    // ── SAF Storage Access ────────────────────────────────────────────────────

    /**
     * Processes the result from the [Intent.ACTION_OPEN_DOCUMENT_TREE] picker.
     *
     * Delegates to [SafStorageBridge.onDocumentTreeResult] to persist the URI
     * permission grant immediately, as required before the transient grant expires.
     *
     * @param uri The tree [Uri] returned from [Activity.onActivityResult].
     * @return true if the permission was successfully persisted.
     */
    fun onSdCardUriResult(uri: Uri): Boolean {
        val success = safStorageBridge.onDocumentTreeResult(uri)
        Log.i(TAG, "SD card URI result processed — success: $success")
        return success
    }
}
