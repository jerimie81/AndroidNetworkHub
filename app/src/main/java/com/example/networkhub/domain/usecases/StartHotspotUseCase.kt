package com.example.networkhub.domain.usecases

import com.example.networkhub.data.network.HotspotManagerImpl
import com.example.networkhub.domain.models.HotspotInfo
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Domain use case that encapsulates all business logic for starting and stopping
 * the LocalOnlyHotspot SoftAP.
 *
 * This class is framework-independent (no Android imports) by design, maintaining
 * Clean Architecture separation. It delegates to [HotspotManagerImpl] for the
 * actual platform API calls, while this class owns the orchestration logic.
 *
 * Dependency injection is provided by Hilt via [@Inject] constructor.
 */
class StartHotspotUseCase @Inject constructor(
    private val hotspotManager: HotspotManagerImpl
) {

    /**
     * Initiates the LocalOnlyHotspot activation and returns a [Flow] that emits
     * [HotspotInfo] once the [WifiManager.LocalOnlyHotspotCallback.onStarted]
     * callback delivers the system-generated credentials.
     *
     * The flow is cold — the hotspot request is dispatched only upon collection.
     * If the hotspot fails to start (e.g. another app holds an incompatible
     * tethering reservation), the flow terminates with an exception.
     *
     * @return A single-emission [Flow] of [HotspotInfo] on success.
     * @throws HotspotStartException if [onFailed] is received from the OS.
     */
    fun execute(): Flow<HotspotInfo> = hotspotManager.startHotspot()

    /**
     * Tears down the LocalOnlyHotspot by calling [LocalOnlyHotspotReservation.close].
     *
     * This method is idempotent — calling it when no hotspot is active is a no-op.
     * It must be called during service teardown to release the hardware lock and
     * prevent the SoftAP from persisting as a zombie reservation.
     */
    fun stop() = hotspotManager.stopHotspot()
}
