package com.example.networkhub.di

import android.content.Context
import com.example.networkhub.data.storage.FtpVirtualFileSystem
import com.example.networkhub.data.storage.SafStorageBridge
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing the MinimalFTP server configuration and storage layer bindings.
 *
 * The [FtpVirtualFileSystem] is a singleton because the MinimalFTP server holds a
 * reference to its IFileSystem for the lifetime of the server instance. Creating a
 * new VFS on each injection would orphan the server's internal reference.
 */
@Module
@InstallIn(SingletonComponent::class)
object ServerModule {

    @Provides
    @Singleton
    fun provideFtpVirtualFileSystem(
        safStorageBridge: SafStorageBridge
    ): FtpVirtualFileSystem {
        return FtpVirtualFileSystem(safStorageBridge)
    }
}

/**
 * Hilt module providing storage abstraction layer bindings.
 *
 * [SafStorageBridge] is a singleton to ensure the [SharedPreferences]-backed URI
 * persistence is shared across all injection sites. Multiple instances would create
 * independent preference caches with stale-read risks.
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideSafStorageBridge(
        @ApplicationContext context: Context
    ): SafStorageBridge {
        return SafStorageBridge(context)
    }
}
