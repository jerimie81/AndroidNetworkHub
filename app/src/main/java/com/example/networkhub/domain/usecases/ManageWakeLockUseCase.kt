package com.example.networkhub.domain.usecases

import android.content.Context
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain use case that encapsulates all [PowerManager.WakeLock] lifecycle management.
 *
 * # Why a PARTIAL_WAKE_LOCK?
 *
 * When the user disables the Galaxy S8+ screen, the Android kernel throttles
 * CPU frequencies and suspends peripheral interfaces. Because FTP file transfer
 * requires active CPU cycles to process TCP/IP overhead and write to UFS/SD flash,
 * the service must prevent this hardware suspend.
 *
 * Two options exist:
 * 1. [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] — forces the AMOLED display on.
 *    STRICTLY CONTRAINDICATED for this application: AMOLED burn-in is irreversible
 *    and extreme battery drain renders hours-long transfers impractical.
 *
 * 2. [PowerManager.PARTIAL_WAKE_LOCK] — keeps the CPU active while allowing the
 *    display to turn off completely. This is the architecturally correct choice.
 *
 * # Memory Leak Warning
 *
 * Failing to call [release] when the server shuts down results in a wake lock
 * that persists indefinitely, draining the device battery to zero. The symmetric
 * [acquire]/[release] contract must be maintained at all call sites.
 */
@Singleton
class ManageWakeLockUseCase @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ManageWakeLockUseCase"

        /** Unique tag string for logcat and battery historian identification. */
        private const val WAKE_LOCK_TAG = "NetworkHub::CpuWakeLock"

        /**
         * 6-hour timeout as a safety net against runaway wake lock leaks.
         * In normal operation, [release] is called explicitly during service teardown.
         * This timeout prevents the pathological case where a crash prevents teardown.
         */
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L // 6 hours
    }

    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Acquires the [PowerManager.PARTIAL_WAKE_LOCK], preventing the CPU from
     * entering suspend mode while allowing the display to turn off.
     *
     * This method is idempotent — calling it when the lock is already held
     * simply logs a warning without acquiring a second reference, preventing
     * an unbalanced reference count that would require two [release] calls.
     *
     * The lock is acquired with a 6-hour safety timeout as a last-resort
     * defence against battery drain from uncaught exceptions preventing teardown.
     */
    fun acquire() {
        if (wakeLock?.isHeld == true) {
            Log.w(TAG, "WakeLock already held — ignoring duplicate acquire() call.")
            return
        }

        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).also { lock ->
            lock.setReferenceCounted(false) // Flat acquire/release semantics
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            Log.d(TAG, "PARTIAL_WAKE_LOCK acquired (timeout: ${WAKE_LOCK_TIMEOUT_MS}ms)")
        }
    }

    /**
     * Releases the [PowerManager.WakeLock], allowing the CPU to enter suspend
     * mode when other wake locks permit.
     *
     * This method is idempotent — calling it when no lock is held is a no-op.
     * Must be called symmetrically with [acquire] during service teardown.
     */
    fun release() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                lock.release()
                Log.d(TAG, "PARTIAL_WAKE_LOCK released.")
            } else {
                Log.w(TAG, "release() called but WakeLock was not held.")
            }
        }
        wakeLock = null
    }

    /** Returns true if the wake lock is currently held by this use case instance. */
    val isHeld: Boolean get() = wakeLock?.isHeld == true
}
