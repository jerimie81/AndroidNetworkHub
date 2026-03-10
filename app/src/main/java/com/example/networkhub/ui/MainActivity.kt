package com.example.networkhub.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.networkhub.domain.models.ServerStatus
import com.example.networkhub.ui.components.CredentialsDisplay
import com.example.networkhub.ui.components.StatusCard
import dagger.hilt.android.AndroidEntryPoint

/**
 * Primary entry point of the application. Handles:
 *
 * - Runtime permission requests for ACCESS_FINE_LOCATION (mandatory for hotspot on API 28)
 *   and WRITE_EXTERNAL_STORAGE (legacy storage fallback)
 * - SAF document tree picker launch for SD card access grants
 * - Rendering the reactive Compose UI driven by [MainViewModel.serverStatus]
 * - Dispatching start/stop intents to [HubForegroundService] via [MainViewModel]
 *
 * The UI layer observes only state — all business logic is owned by the ViewModel
 * and delegated to the domain/data layers below.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_SD_CARD_URI = 1001
    }

    private val viewModel: MainViewModel by viewModels()

    // ── Permission Launcher ───────────────────────────────────────────────────

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.i(TAG, "All required permissions granted")
        } else {
            Toast.makeText(
                this,
                "Location permission is required to start the hotspot on Android 9.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Activity Lifecycle ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        setContent {
            NetworkHubTheme {
                NetworkHubScreen(
                    viewModel = viewModel,
                    onStartClicked = { viewModel.startServer() },
                    onStopClicked  = { viewModel.stopServer() },
                    onGrantSdCard  = { launchSdCardPicker() }
                )
            }
        }
    }

    // ── Permission Handling ───────────────────────────────────────────────────

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        // WRITE_EXTERNAL_STORAGE is only relevant up to API 28.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        // POST_NOTIFICATIONS required on Android 13+ (API 33+).
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            locationPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ── SAF Document Tree Picker ──────────────────────────────────────────────

    private fun launchSdCardPicker() {
        // Build the intent via SafStorageBridge (not inline) to keep the Activity
        // layer free of SAF implementation details.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_SD_CARD_URI)
    }

    @Deprecated("Using onActivityResult for SAF compatibility with API 28 target")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_SD_CARD_URI && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: run {
                Toast.makeText(this, "SD card access was not granted.", Toast.LENGTH_SHORT).show()
                return
            }
            val success = viewModel.onSdCardUriResult(uri)
            val message = if (success) "SD card access granted successfully." else "Failed to persist SD card access."
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}

// ── Compose Screen ────────────────────────────────────────────────────────────

@Composable
fun NetworkHubScreen(
    viewModel: MainViewModel,
    onStartClicked: () -> Unit,
    onStopClicked: () -> Unit,
    onGrantSdCard: () -> Unit
) {
    val status by viewModel.serverStatus.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {

        // ── Header ────────────────────────────────────────────────────────────
        Text(
            text = "Network Hub",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Galaxy S8+ Offline NAS Server",
            fontSize = 15.sp,
            color = Color(0xFF8E8E93)
        )

        // ── Status Card ───────────────────────────────────────────────────────
        StatusCard(status = status)

        // ── Credentials (visible when hotspot/server is active) ───────────────
        val info = status.hotspotInfo
        if (info != null) {
            CredentialsDisplay(info = info)
        }

        // ── Control Buttons ───────────────────────────────────────────────────
        if (!status.isActive) {
            Button(
                onClick = onStartClicked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF32D74B))
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text("Start Network Hub", color = Color.Black, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Button(
                onClick = onStopClicked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A))
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Stop Network Hub", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }

        // ── SD Card Access Grant ──────────────────────────────────────────────
        OutlinedButton(
            onClick = onGrantSdCard,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF64B5F6))
        ) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (viewModel.hasSdCardAccess) "SD Card: Granted ✓" else "Grant SD Card Access",
                fontWeight = FontWeight.Medium
            )
        }

        // ── Info Footer ───────────────────────────────────────────────────────
        InfoSection()
    }
}

@Composable
private fun InfoSection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("How to Connect", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(
                text = "1. Start the Hub and wait for the SSID and password to appear.\n" +
                       "2. On your PC or laptop, scan the QR code or manually connect to the shown Wi-Fi network.\n" +
                       "3. Open an FTP client (e.g. FileZilla) and connect to the shown FTP address.\n" +
                       "4. The server is now accessible — no internet connection is required.",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = Color(0xFF8E8E93)
            )
        }
    }
}

// ── Theme ─────────────────────────────────────────────────────────────────────

@Composable
fun NetworkHubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF32D74B),
            background = Color(0xFF000000),
            surface = Color(0xFF1C1C1E)
        ),
        content = content
    )
}
