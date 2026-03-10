package com.example.networkhub.data.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.networkhub.domain.models.HotspotInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete implementation of the LocalOnlyHotspot controller.
 *
 * This class is the sole point of interaction with [WifiManager.startLocalOnlyHotspot].
 * It wraps the callback-based Android API in a Kotlin [callbackFlow], converting the
 * asynchronous [LocalOnlyHotspotCallback] events into a reactive cold flow that the
 * domain layer can collect with structured concurrency.
 *
 * # Critical Architecture Note — SSID/Password Constraints
 *
 * On Android 9 (API 28), [startLocalOnlyHotspot] generates a random SSID and WPA2
 * pre-shared key internally. Standard applications CANNOT define custom SSIDs or
 * passwords; attempts via reflection reliably throw [SecurityException] due to Google's
 * non-SDK interface blacklist introduced in Android Pie. The credentials must therefore
 * be extracted from [LocalOnlyHotspotReservation.wifiConfiguration] in [onStarted] and
 * surfaced directly to the UI layer for display.
 *
 * # Hotspot Multiplexing
 *
 * The Android OS multiplexes hotspot reservations. If another application holds an active
 * reservation, the SoftAP remains live until ALL reservations call [close]. This class
 * tracks the reservation reference to ensure [stopHotspot] calls [close] precisely once,
 * releasing our portion of the multiplexed reservation without affecting others.
 */
@Singleton
class HotspotManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "HotspotManagerImpl"
        private const val DEFAULT_GATEWAY = "192.168.43.1"
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    @Volatile
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null

    /**
     * Requests activation of a LocalOnlyHotspot SoftAP and returns a [Flow] that
     * emits a single [HotspotInfo] value upon successful activation.
     *
     * The flow uses [callbackFlow] to bridge the Android callback API into
     * coroutine-compatible reactive streams. The flow terminates (closes) after
     * emitting the [HotspotInfo] — the caller is responsible for collecting once
     * and then awaiting [stopHotspot] for teardown.
     *
     * @return A cold [Flow] that emits [HotspotInfo] on hotspot activation success.
     * @throws HotspotStartException (via flow error) if [onFailed] is received.
     */
    fun startHotspot(): Flow<HotspotInfo> = callbackFlow {

        val callback = object : WifiManager.LocalOnlyHotspotCallback() {

            /**
             * Called when the SoftAP is successfully activated.
             *
             * The [LocalOnlyHotspotReservation] must be stored to call [close] later.
             * [wifiConfiguration] contains the system-generated credentials that must
             * be displayed to the user — they cannot be read any other way.
             */
            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                hotspotReservation = reservation

                val config = reservation.wifiConfiguration
                if (config == null) {
                    Log.e(TAG, "onStarted: WifiConfiguration was null — unexpected state")
                    close(HotspotStartException("WifiConfiguration returned null from reservation"))
                    return
                }

                val info = HotspotInfo(
                    ssid = config.SSID ?: "Unknown",
                    password = config.preSharedKey ?: "Unknown",
                    gatewayIp = DEFAULT_GATEWAY,
                    ftpPort = 2121
                )

                Log.i(TAG, "Hotspot started — SSID: ${info.ssid}, Gateway: ${info.gatewayIp}")
                trySend(info)
                // Close the flow after emitting credentials; lifecycle is now managed
                // externally by the service via stopHotspot().
                close()
            }

            /** Called by the OS when the SoftAP is stopped externally (e.g. by Settings). */
            override fun onStopped() {
                Log.w(TAG, "Hotspot stopped externally by the OS")
                hotspotReservation = null
                close()
            }

            /**
             * Called when the hotspot could not be started.
             *
             * @param reason One of [ERROR_NO_CHANNEL], [ERROR_GENERIC], [ERROR_INCOMPATIBLE_MODE],
             *               [ERROR_TETHERING_DISALLOWED].
             */
            override fun onFailed(reason: Int) {
                val msg = "Hotspot start failed with reason code: $reason (${reasonToString(reason)})"
                Log.e(TAG, msg)
                close(HotspotStartException(msg))
            }
        }

        // The callback must be dispatched on the main thread as required by the API.
        wifiManager.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))

        // When the flow collector is cancelled (e.g. service teardown), close the reservation.
        awaitClose {
            Log.d(TAG, "callbackFlow closing — releasing hotspot reservation if held")
            hotspotReservation?.close()
            hotspotReservation = null
        }
    }

    /**
     * Explicitly closes the [LocalOnlyHotspotReservation], releasing this application's
     * claim on the SoftAP. If no other application holds a reservation, the hotspot
     * hardware is deactivated.
     *
     * This method is idempotent — calling it when no reservation is held is a no-op.
     */
    fun stopHotspot() {
        hotspotReservation?.close()
        hotspotReservation = null
        Log.i(TAG, "Hotspot reservation closed")
    }

    /** Returns true if an active hotspot reservation is currently held. */
    val isActive: Boolean get() = hotspotReservation != null

    private fun reasonToString(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
            "ERROR_NO_CHANNEL (no channel available for SoftAP)"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC ->
            "ERROR_GENERIC"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "ERROR_INCOMPATIBLE_MODE (STA+AP not supported simultaneously)"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "ERROR_TETHERING_DISALLOWED (carrier policy)"
        else -> "UNKNOWN($reason)"
    }
}

/** Thrown when the LocalOnlyHotspot [onFailed] callback is received from the OS. */
class HotspotStartException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
