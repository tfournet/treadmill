"""Session detection, delta computation, and interval aggregation.

The treadmill reports cumulative steps/time within a session. This module:
- Detects session boundaries (step counter reset, time reset, long gaps)
- Computes per-reading deltas
- Aggregates steps into 5-minute intervals for the upload queue
"""

import asyncio
import logging
from datetime import datetime, timezone

from .config import Config
from .db import TreadmillDB
from .protocol import TreadmillReading

log = logging.getLogger("treadmill.collector")


class Collector:
    def __init__(self, config: Config, db: TreadmillDB):
        self.config = config
        self.db = db
        self._session_id: int | None = None
        self._prev: TreadmillReading | None = None
        self._prev_wall: datetime | None = None
        self._distance_overflow: float = 0.0
        self._interval_start: datetime | None = None
        self._interval_steps: int = 0

    async def on_reading(self, reading: TreadmillReading):
        """Called by BLEManager for each decoded frame."""
        now = datetime.now(timezone.utc)
        new_session = False

        if self._prev is not None:
            if reading.steps < self._prev.steps:
                log.info("Steps decreased (%d → %d): new session", self._prev.steps, reading.steps)
                new_session = True
            elif reading.time_secs < self._prev.time_secs:
                log.info("Time decreased (%d → %d): new session", self._prev.time_secs, reading.time_secs)
                new_session = True
            elif self._prev_wall and (now - self._prev_wall).total_seconds() > 600:
                log.info("Gap > 10min since last reading: new session")
                new_session = True

            if new_session:
                await self._end_session()

        if self._session_id is None:
            await self._start_session(now)

        # Distance overflow: byte[10] wraps at 2.56
        distance = reading.distance + self._distance_overflow
        if self._prev and not new_session and reading.distance < self._prev.distance:
            if reading.steps >= self._prev.steps:
                self._distance_overflow += 2.56
                distance = reading.distance + self._distance_overflow
                log.debug("Distance overflow, accumulator now %.2f", self._distance_overflow)

        # Compute deltas
        if self._prev and not new_session:
            delta_steps = max(0, reading.steps - self._prev.steps)
            delta_time = max(0, reading.time_secs - self._prev.time_secs)
        else:
            # First reading in session — check if treadmill's cumulative values
            # overlap with the previous session (service restart without treadmill
            # power cycle). If so, only count the difference as new steps.
            last_db = await asyncio.to_thread(self.db.get_last_reading_any_session)
            if last_db and reading.steps >= last_db["raw_steps"] and reading.time_secs >= last_db["raw_time_secs"]:
                delta_steps = max(0, reading.steps - last_db["raw_steps"])
                delta_time = max(0, reading.time_secs - last_db["raw_time_secs"])
                log.info(
                    "Resuming from previous session (last raw_steps=%d), delta=%d",
                    last_db["raw_steps"], delta_steps,
                )
            else:
                delta_steps = 0
                delta_time = 0
                log.info("Baseline reading: steps=%d, time=%d (not counted)", reading.steps, reading.time_secs)

        now_iso = now.isoformat()

        await asyncio.to_thread(
            self.db.insert_reading,
            session_id=self._session_id,
            timestamp=now_iso,
            raw_steps=reading.steps,
            raw_time_secs=reading.time_secs,
            speed=reading.speed,
            distance=distance,
            delta_steps=delta_steps,
            delta_time_secs=delta_time,
        )

        if delta_steps > 0:
            log.info(
                "Steps +%d (total %d) | Speed %.1f | Time %d:%02d | Dist %.2f",
                delta_steps, reading.steps,
                reading.speed,
                reading.time_secs // 60, reading.time_secs % 60,
                distance,
            )

        # Accumulate for interval aggregation
        self._interval_steps += delta_steps
        if self._interval_start is None:
            self._interval_start = now

        elapsed = (now - self._interval_start).total_seconds()
        if elapsed >= self.config.intervals.aggregate_every_secs and self._interval_steps > 0:
            await self._flush_interval(now)

        self._prev = reading
        self._prev_wall = now

    async def flush(self):
        """Flush any accumulated steps (call on shutdown)."""
        if self._interval_steps > 0 and self._interval_start:
            await self._flush_interval(datetime.now(timezone.utc))

    async def _start_session(self, now: datetime):
        self._session_id = await asyncio.to_thread(self.db.start_session)
        self._distance_overflow = 0.0
        self._interval_start = now
        self._interval_steps = 0
        log.info("Session %d started", self._session_id)

    async def _end_session(self):
        if self._session_id is not None:
            if self._interval_steps > 0 and self._interval_start:
                await self._flush_interval(datetime.now(timezone.utc))
            await asyncio.to_thread(self.db.end_session, self._session_id)
            log.info("Session %d ended", self._session_id)
        self._session_id = None
        self._prev = None
        self._prev_wall = None
        self._distance_overflow = 0.0

    async def _flush_interval(self, now: datetime):
        period_start = self._interval_start.isoformat()
        period_end = now.isoformat()
        steps = self._interval_steps

        await asyncio.to_thread(
            self.db.enqueue_interval,
            session_id=self._session_id,
            period_start=period_start,
            period_end=period_end,
            step_count=steps,
        )
        log.info("Queued interval: %d steps [%s → %s]", steps, period_start, period_end)

        self._interval_start = now
        self._interval_steps = 0
