package com.example.networkhub.di

import android.content.Context
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing singleton system service instances for all network-related
 * dependencies across the application component hierarchy.
 *
 * Both [WifiManager] and [NsdManager] are expensive to obtain via [Context.getSystemService]
 * and should be held as application-scoped singletons. Providing them via Hilt ensures
 * a single instance is shared across all injection sites without manual singleton management.
 *
 * # WifiManager — Application Context Requirement
 *
 * [WifiManager] must be obtained from the APPLICATION context, not an activity context.
 * Obtaining it from an activity context results in a memory leak where the WifiManager
 * holds a reference to the (possibly destroyed) activity. This is enforced here by
 * exclusively accepting [@ApplicationContext].
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideWifiManager(
        @ApplicationContext context: Context
    ): WifiManager {
        // applicationContext is mandatory for WifiManager to avoid activity context leaks.
        return context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    @Provides
    @Singleton
    fun provideNsdManager(
        @ApplicationContext context: Context
    ): NsdManager {
        return context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }
}
