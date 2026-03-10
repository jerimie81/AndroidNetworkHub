package com.example.networkhub.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implements Multicast DNS (mDNS) service registration via [NsdManager], enabling
 * automatic discovery of the FTP server by clients on the local-only hotspot network.
 *
 * # Why mDNS?
 *
 * While the hotspot gateway is predictably 192.168.43.1 on most Samsung devices,
 * hardcoding this IP is an architectural anti-pattern. [NsdManager] broadcasts
 * the service record over UDP port 5353, causing the server to appear automatically
 * in the network discovery panel of Windows, macOS, and Linux clients without
 * requiring any manual IP configuration.
 *
 * # Android 9 Multicast Gotcha
 *
 * On Android 9 and Galaxy S8+ firmware, the Wi-Fi driver drops incoming multicast
 * packets when the screen is off to conserve battery. Without a [WifiManager.MulticastLock],
 * mDNS announcements are silently discarded, making the server invisible to clients.
 *
 * The [MulticastLock] must be acquired before starting mDNS and held for the entire
 * server lifetime. [setReferenceCounted(true)] ensures predictable acquire/release
 * semantics across the application lifecycle.
 *
 * @param context Application context for system service resolution.
 */
@Singleton
class NsdBroadcaster @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "NsdBroadcaster"
        const val SERVICE_NAME = "Galaxy S8 NAS"
        const val SERVICE_TYPE = "_ftp._tcp."      // Standard mDNS service type for FTP
        const val MULTICAST_LOCK_TAG = "NetworkHub::FtpMdnsLock"
    }

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var isRegistered: Boolean = false

    /**
     * Acquires the [WifiManager.MulticastLock] and registers the FTP service record
     * with [NsdManager], broadcasting it over UDP port 5353.
     *
     * Must be called AFTER the FTP server socket is bound and ready to accept
     * connections on [port]. Calling before the server is listening may cause
     * clients to connect and immediately fail.
     *
     * @param port The port on which the MinimalFTP server is listening (default: 2121).
     */
    fun register(port: Int = 2121) {
        acquireMulticastLock()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        nsdManager.registerService(
            serviceInfo,
            NsdManager.PROTOCOL_DNS_SD,
            registrationListener
        )

        Log.i(TAG, "mDNS service registration requested: $SERVICE_NAME ($SERVICE_TYPE:$port)")
    }

    /**
     * Unregisters the mDNS service record and releases the [WifiManager.MulticastLock].
     *
     * Must be called during service teardown to remove the service record from the
     * local network. Failing to unregister leaves stale records that resolve to a
     * dead server until the record TTL expires (typically 75 minutes).
     */
    fun unregister() {
        if (isRegistered) {
            try {
                nsdManager.unregisterService(registrationListener)
                Log.i(TAG, "mDNS service unregistered")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Attempted to unregister a service that was not registered: ${e.message}")
            }
            isRegistered = false
        }
        releaseMulticastLock()
    }

    // ── MulticastLock Management ──────────────────────────────────────────────

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) {
            Log.w(TAG, "MulticastLock already held — ignoring duplicate acquire()")
            return
        }

        multicastLock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            // setReferenceCounted(true) ensures the lock is released only when the
            // reference count drops to zero, matching the number of acquire() calls.
            setReferenceCounted(true)
            acquire()
        }
        Log.d(TAG, "MulticastLock acquired")
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                Log.d(TAG, "MulticastLock released")
            }
        }
        multicastLock = null
    }

    // ── NsdManager Registration Listener ─────────────────────────────────────

    private val registrationListener = object : NsdManager.RegistrationListener {

        override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
            // The system may rename the service to avoid conflicts (e.g. "Galaxy S8 NAS (2)").
            val actualName = serviceInfo.serviceName
            isRegistered = true
            Log.i(TAG, "mDNS service successfully registered as: $actualName")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "mDNS registration failed — error code: $errorCode")
            isRegistered = false
            // Release the multicast lock since registration failed.
            releaseMulticastLock()
        }

        override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
            isRegistered = false
            Log.i(TAG, "mDNS service unregistered: ${serviceInfo.serviceName}")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e(TAG, "mDNS unregistration failed — error code: $errorCode")
        }
    }
}
