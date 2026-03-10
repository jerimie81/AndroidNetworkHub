package com.example.networkhub.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.networkhub.domain.models.HotspotInfo
import com.example.networkhub.domain.models.ServerStatus
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

// ── StatusCard ────────────────────────────────────────────────────────────────

/**
 * Composable card that renders the current [ServerStatus] as a colour-coded
 * status indicator with a descriptive label. The colour transitions provide
 * immediate visual feedback across the service lifecycle states.
 */
@Composable
fun StatusCard(
    status: ServerStatus,
    modifier: Modifier = Modifier
) {
    val (backgroundColor, iconTint, label) = when (status) {
        is ServerStatus.Idle           -> Triple(Color(0xFF2C2C2E), Color(0xFF8E8E93), "Idle — Server Offline")
        is ServerStatus.HotspotStarting -> Triple(Color(0xFF1C3A5E), Color(0xFF64B5F6), "Starting Hotspot…")
        is ServerStatus.HotspotReady   -> Triple(Color(0xFF1A3A2A), Color(0xFF81C784), "Hotspot Active")
        is ServerStatus.ServerStarting -> Triple(Color(0xFF1A3A2A), Color(0xFFFFB74D), "Binding FTP Server…")
        is ServerStatus.Running        -> Triple(Color(0xFF1A3A2A), Color(0xFF4CAF50), "● Running")
        is ServerStatus.Error          -> Triple(Color(0xFF3E1A1A), Color(0xFFEF5350), "Error")
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = when (status) {
                    is ServerStatus.Running -> Icons.Filled.Wifi
                    is ServerStatus.Error   -> Icons.Filled.ErrorOutline
                    else                   -> Icons.Filled.HourglassTop
                },
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(32.dp)
            )
            Column {
                Text(
                    text = label,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = iconTint
                )
                if (status is ServerStatus.Error) {
                    Text(
                        text = status.message,
                        fontSize = 13.sp,
                        color = Color(0xFFEF9A9A),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (status is ServerStatus.Running) {
                    Text(
                        text = "${status.connectedClients} client(s) connected",
                        fontSize = 13.sp,
                        color = Color(0xFFA5D6A7)
                    )
                }
            }
        }
    }
}

// ── CredentialsDisplay ────────────────────────────────────────────────────────

/**
 * Composable panel that renders the system-generated hotspot SSID and WPA2 password,
 * the FTP server URL, and a scannable QR code encoding the Wi-Fi credentials.
 *
 * The QR code is encoded in the ZXing WIFI: URI scheme:
 *   WIFI:S:<SSID>;T:WPA;P:<PASSWORD>;;
 *
 * Any modern Android or iOS camera application can decode this QR code and
 * automatically join the hotspot without manual credential entry.
 *
 * @param info The [HotspotInfo] containing the system-generated credentials.
 */
@Composable
fun CredentialsDisplay(
    info: HotspotInfo,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Connection Details",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            // SSID row
            CredentialRow(
                icon = Icons.Filled.Wifi,
                label = "Network Name (SSID)",
                value = info.ssid
            )

            // Password row
            CredentialRow(
                icon = Icons.Filled.Lock,
                label = "WPA2 Password",
                value = info.password
            )

            // FTP URL row
            CredentialRow(
                icon = Icons.Filled.Storage,
                label = "FTP Server Address",
                value = info.toFtpUrl()
            )

            Divider(color = Color(0xFF3A3A3C), thickness = 1.dp)

            // QR Code
            Text(
                text = "Scan to Connect",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF8E8E93)
            )

            val qrBitmap = remember(info.ssid, info.password) {
                generateQrCode(info.toWifiQrCodeString(), size = 320)
            }

            if (qrBitmap != null) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Wi-Fi QR Code for ${info.ssid}",
                        modifier = Modifier
                            .size(200.dp)
                            .background(Color.White, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CredentialRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color(0xFF64B5F6), modifier = Modifier.size(20.dp))
        Column {
            Text(text = label, fontSize = 11.sp, color = Color(0xFF8E8E93))
            Text(
                text = value,
                fontSize = 15.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}

/**
 * Generates a [Bitmap] QR code from the given [content] string using the ZXing library.
 *
 * @param content The string to encode (e.g. WIFI:S:MySSID;T:WPA;P:MyPassword;;)
 * @param size The width and height in pixels of the output bitmap.
 * @return A square [Bitmap] containing the QR code, or null if encoding fails.
 */
fun generateQrCode(content: String, size: Int = 512): Bitmap? {
    return try {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bits[x, y]) Color.Black.toArgb() else Color.White.toArgb())
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
