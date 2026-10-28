package com.example.wishpchatroom.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The best estimate of the server's clock. Countdowns and "is this whisper hidden yet" decisions use
 * it, so a wrong device clock does not show a wrong countdown. The security rules always judge by
 * the server's own time; this only affects what is displayed.
 */
interface ServerClock {
    fun now(): Long

    /** True when the offset has not been measured recently. */
    fun needsCalibration(): Boolean

    /**
     * Records a server timestamp that was read right after a round trip that started at
     * [localBefore] and ended at [localAfter] (device clock). The server time is assumed to be
     * in the middle of that interval.
     */
    fun calibrate(serverMillis: Long, localBefore: Long, localAfter: Long)
}

@Singleton
class DeviceServerClock @Inject constructor() : ServerClock {
    @Volatile private var offset = 0L
    @Volatile private var calibratedAt = 0L

    override fun now(): Long = System.currentTimeMillis() + offset

    override fun needsCalibration(): Boolean =
        calibratedAt == 0L || System.currentTimeMillis() - calibratedAt > RECALIBRATE_MS

    override fun calibrate(serverMillis: Long, localBefore: Long, localAfter: Long) {
        // Only trust round trips that were quick: a slow one hides a large error in the midpoint.
        if (localAfter - localBefore > MAX_ROUND_TRIP_MS) return
        offset = serverMillis - (localBefore + localAfter) / 2
        calibratedAt = System.currentTimeMillis()
    }

    private companion object {
        const val RECALIBRATE_MS = 10 * 60 * 1000L
        const val MAX_ROUND_TRIP_MS = 3_000L
    }
}
