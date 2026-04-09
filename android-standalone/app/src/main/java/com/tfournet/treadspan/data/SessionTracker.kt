package com.tfournet.treadspan.data

import android.util.Log
import com.tfournet.treadspan.ble.TreadmillReading
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

private const val TAG = "SessionTracker"
private const val GAP_THRESHOLD_MS = 20 * 60 * 1000L   // 20 minutes of inactivity ends a session
private const val INTERVAL_SECS = 60                    // 1-minute aggregation window
private const val DISTANCE_WRAP = 2.56                  // byte[10] wraps at 2.56 km

/**
 * Mirrors Python Collector: detects session boundaries, computes per-reading deltas,
 * and flushes 5-minute step intervals to the Room database.
 *
 * Thread-safe: all state is guarded by a Mutex since BLE callbacks
 * and CompanionDeviceService may call onReading concurrently.
 */
class SessionTracker(
    private val dao: TreadmillDao,
    private val onWalkingStopped: (suspend () -> Unit)? = null,
) {

    private val mutex = Mutex()
    private var sessionId: Long? = null
    private var prev: TreadmillReading? = null
    private var prevWallMs: Long? = null
    private var distanceOverflow: Double = 0.0
    private var intervalStart: Instant? = null
    private var intervalSteps: Int = 0
    private var wasWalking: Boolean = false

    suspend fun onReading(reading: TreadmillReading) = mutex.withLock {
        val now = Instant.now()
        var newSession = false

        val prevReading = prev
        if (prevReading != null) {
            when {
                reading.steps < prevReading.steps -> {
                    Log.i(TAG, "Steps decreased (${prevReading.steps} → ${reading.steps}): new session")
                    newSession = true
                }
                reading.timeSecs < prevReading.timeSecs -> {
                    Log.i(TAG, "Time decreased (${prevReading.timeSecs} → ${reading.timeSecs}): new session")
                    newSession = true
                }
                prevWallMs != null && (now.toEpochMilli() - prevWallMs!!) > GAP_THRESHOLD_MS -> {
                    Log.i(TAG, "Gap > 10min since last reading: new session")
                    newSession = true
                }
            }
            if (newSession) endSession(now)
        }

        if (sessionId == null) startSession(now)

        // Distance overflow: byte[10] wraps at 2.56
        var distance = reading.distance + distanceOverflow
        if (prevReading != null && !newSession && reading.distance < prevReading.distance) {
            if (reading.steps >= prevReading.steps) {
                distanceOverflow += DISTANCE_WRAP
                distance = reading.distance + distanceOverflow
                Log.d(TAG, "Distance overflow, accumulator now %.2f".format(distanceOverflow))
            }
        }

        // Compute deltas
        val deltaSteps: Int
        val deltaTime: Int
        if (prevReading != null && !newSession) {
            deltaSteps = maxOf(0, reading.steps - prevReading.steps)
            deltaTime  = maxOf(0, reading.timeSecs - prevReading.timeSecs)
        } else {
            // First reading in session — use as baseline, don't count cumulative as new.
            // If we have a prior DB reading with <= current values, take the difference.
            // Otherwise (first ever reading, or treadmill was reset), baseline is 0.
            val lastDb = dao.getLastReadingAnySession()
            if (lastDb != null
                && reading.steps >= lastDb.rawSteps
                && reading.timeSecs >= lastDb.rawTimeSecs
            ) {
                deltaSteps = maxOf(0, reading.steps - lastDb.rawSteps)
                deltaTime  = maxOf(0, reading.timeSecs - lastDb.rawTimeSecs)
                Log.i(TAG, "Resuming from previous session (last rawSteps=${lastDb.rawSteps}), delta=$deltaSteps")
            } else {
                // First reading ever, or treadmill was power-cycled — use as baseline
                deltaSteps = 0
                deltaTime  = 0
                Log.i(TAG, "Baseline reading: steps=${reading.steps}, time=${reading.timeSecs} (not counted)")
            }
        }

        dao.insertReading(
            ReadingEntity(
                sessionId    = sessionId!!,
                timestamp    = now.toString(),
                rawSteps     = reading.steps,
                rawTimeSecs  = reading.timeSecs,
                speed        = reading.speed,
                distance     = distance,
                deltaSteps   = deltaSteps,
                deltaTimeSecs = deltaTime,
            )
        )

        if (deltaSteps > 0) {
            Log.i(TAG, "Steps +$deltaSteps (total ${reading.steps}) | Speed %.1f | Time %d:%02d | Dist %.2f"
                .format(reading.speed, reading.timeSecs / 60, reading.timeSecs % 60, distance))
        }

        // Accumulate for interval aggregation
        intervalSteps += deltaSteps
        if (intervalStart == null) intervalStart = now

        val elapsedSecs = now.epochSecond - intervalStart!!.epochSecond
        if (elapsedSecs >= INTERVAL_SECS && intervalSteps > 0) {
            flushInterval(now)
        }

        // Detect walking → stopped transition: flush + trigger sync
        val isWalking = reading.speed > 0
        if (wasWalking && !isWalking) {
            Log.i(TAG, "Walking stopped — flushing interval and triggering sync")
            if (intervalSteps > 0 && intervalStart != null) {
                flushInterval(now)
            }
            onWalkingStopped?.invoke()
        }
        wasWalking = isWalking

        prev        = reading
        prevWallMs  = now.toEpochMilli()
    }

    /** Flush any accumulated steps — call on shutdown or BLE disconnect. */
    suspend fun flush() = mutex.withLock {
        if (intervalSteps > 0 && intervalStart != null) {
            flushInterval(Instant.now())
        }
    }

    private suspend fun startSession(now: Instant) {
        sessionId        = dao.startSession(SessionEntity(startedAt = now.toString()))
        distanceOverflow = 0.0
        intervalStart    = now
        intervalSteps    = 0
        Log.i(TAG, "Session $sessionId started")
    }

    private suspend fun endSession(now: Instant) {
        val sid = sessionId ?: return
        if (intervalSteps > 0 && intervalStart != null) flushInterval(now)
        dao.endSession(sid, now.toString())
        Log.i(TAG, "Session $sid ended")
        sessionId        = null
        prev             = null
        prevWallMs       = null
        distanceOverflow = 0.0
    }

    private suspend fun flushInterval(now: Instant) {
        val start = intervalStart ?: return
        val steps = intervalSteps
        dao.enqueueInterval(
            StepIntervalEntity(
                sessionId   = sessionId!!,
                periodStart = start.toString(),
                periodEnd   = now.toString(),
                stepCount   = steps,
            )
        )
        Log.i(TAG, "Queued interval: $steps steps [$start → $now]")
        intervalStart = now
        intervalSteps = 0
    }
}
