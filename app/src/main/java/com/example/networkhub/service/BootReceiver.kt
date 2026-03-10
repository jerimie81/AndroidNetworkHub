package com.example.networkhub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Optional [BroadcastReceiver] that restarts the [HubForegroundService] after
 * the device has completed booting.
 *
 * # Behaviour
 *
 * When the device boots, the Android OS broadcasts [Intent.ACTION_BOOT_COMPLETED]
 * to all registered receivers. This receiver intercepts it and dispatches an
 * [Intent] to restart the foreground service, restoring the NAS server without
 * requiring the user to manually open the application.
 *
 * This receiver is declared in the manifest with [RECEIVE_BOOT_COMPLETED] permission.
 * The [android:enabled] attribute allows it to be toggled via the application's
 * settings screen if the user prefers not to auto-start on boot.
 *
 * # API 26+ Requirement
 *
 * On Android 8.0+ (API 26), starting a background service from a BroadcastReceiver
 * is prohibited by background execution limits. [Context.startForegroundService] must
 * be used instead of [Context.startService] to comply with these restrictions.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED
            && intent.action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }

        Log.i(TAG, "Boot completed — restarting HubForegroundService")

        val serviceIntent = Intent(context, HubForegroundService::class.java).apply {
            action = HubForegroundService.ACTION_START
        }

        // On API 26+, startForegroundService is required from a receiver context.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
