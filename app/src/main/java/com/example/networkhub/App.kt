package com.example.networkhub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point for Hilt dependency injection graph initialization.
 *
 * This class is the root of the Hilt component hierarchy. All singleton-scoped
 * bindings defined in the DI modules are instantiated lazily from here.
 *
 * It also bootstraps the notification channel required by the foreground service
 * on API 26+ — channel creation is idempotent and safe to call on every launch.
 */
@HiltAndroidApp
class App : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "hub_foreground_service_channel"
        const val NOTIFICATION_CHANNEL_NAME = "Network Hub Service"
        const val NOTIFICATION_CHANNEL_DESCRIPTION =
            "Persistent notification while the hotspot and FTP server are active"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Creates the notification channel for the foreground service.
     *
     * On Android 8.0+ (API 26+), notification channels are mandatory for any
     * notification to be displayed. This method is called at application startup
     * and is idempotent — creating an existing channel with the same ID performs
     * no operation, making it safe to call on every process start.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                // IMPORTANCE_LOW suppresses the alert sound; appropriate for a
                // persistent infrastructure notification that should not interrupt.
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = NOTIFICATION_CHANNEL_DESCRIPTION
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
