package com.example.networkhub.domain.models

/**
 * Immutable data class representing the system-generated Wi-Fi credentials
 * returned by the LocalOnlyHotspot API.
 *
 * On Android 9, [WifiManager.startLocalOnlyHotspot] generates a random SSID and
 * WPA2 Pre-Shared Key via a UUID substring. These values cannot be customised
 * by standard applications — they must be read from the reservation and surfaced
 * to the user so they can onboard client devices.
 *
 * @param ssid The system-generated network name (e.g. "DIRECT-xy-AndroidShare")
 * @param password The system-generated WPA2 pre-shared key
 * @param gatewayIp The gateway address assigned to the hotspot interface
 *                  (typically 192.168.43.1 on Galaxy S8+)
 * @param ftpPort The port on which the embedded FTP server is listening
 */
data class HotspotInfo(
    val ssid: String,
    val password: String,
    val gatewayIp: String = "192.168.43.1",
    val ftpPort: Int = 2121
) {
    /**
     * Returns a Wi-Fi QR code string conforming to the ZXing WIFI: URI scheme.
     *
     * Format: WIFI:S:<SSID>;T:WPA;P:<PASSWORD>;;
     *
     * This string can be encoded into a QR code and scanned by any modern
     * Android or iOS camera app to join the hotspot without manual credential entry.
     */
    fun toWifiQrCodeString(): String = "WIFI:S:$ssid;T:WPA;P:$password;;"

    /**
     * Returns a human-readable FTP URL for the server, using the known gateway address.
     *
     * Example: ftp://192.168.43.1:2121
     */
    fun toFtpUrl(): String = "ftp://$gatewayIp:$ftpPort"
}
