"""SQLite database for durable step tracking."""

import sqlite3
from datetime import datetime, timezone
from pathlib import Path

_SCHEMA = """
CREATE TABLE IF NOT EXISTS sessions (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at TEXT NOT NULL,
    ended_at   TEXT
);

CREATE TABLE IF NOT EXISTS readings (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp       TEXT NOT NULL,
    session_id      INTEGER NOT NULL REFERENCES sessions(id),
    raw_steps       INTEGER NOT NULL,
    raw_time_secs   INTEGER NOT NULL,
    speed           REAL NOT NULL,
    distance        REAL NOT NULL,
    delta_steps     INTEGER NOT NULL,
    delta_time_secs INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS step_intervals (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id   INTEGER NOT NULL REFERENCES sessions(id),
    period_start TEXT NOT NULL,
    period_end   TEXT NOT NULL,
    step_count   INTEGER NOT NULL,
    synced       INTEGER NOT NULL DEFAULT 0,
    synced_at    TEXT
);

CREATE INDEX IF NOT EXISTS idx_step_intervals_pending
    ON step_intervals(synced) WHERE synced = 0;

CREATE INDEX IF NOT EXISTS idx_readings_session
    ON readings(session_id);
"""


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


class TreadmillDB:
    def __init__(self, db_path: Path):
        self.db_path = db_path
        self._conn: sqlite3.Connection | None = None

    def open(self):
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(
            str(self.db_path), isolation_level=None, check_same_thread=False
        )
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA synchronous=NORMAL")
        self._conn.executescript(_SCHEMA)

    def close(self):
        if self._conn:
            self._conn.close()
            self._conn = None

    # --- Sessions ---

    def start_session(self) -> int:
        cur = self._conn.execute(
            "INSERT INTO sessions (started_at) VALUES (?)", (_now_iso(),)
        )
        return cur.lastrowid

    def end_session(self, session_id: int):
        self._conn.execute(
            "UPDATE sessions SET ended_at = ? WHERE id = ?",
            (_now_iso(), session_id),
        )

    # --- Readings ---

    def insert_reading(
        self,
        session_id: int,
        timestamp: str,
        raw_steps: int,
        raw_time_secs: int,
        speed: float,
        distance: float,
        delta_steps: int,
        delta_time_secs: int,
    ) -> int:
        cur = self._conn.execute(
            """INSERT INTO readings
               (timestamp, session_id, raw_steps, raw_time_secs, speed, distance,
                delta_steps, delta_time_secs)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                timestamp,
                session_id,
                raw_steps,
                raw_time_secs,
                speed,
                distance,
                delta_steps,
                delta_time_secs,
            ),
        )
        return cur.lastrowid

    def sum_steps_since(self, session_id: int, since: str) -> int:
        row = self._conn.execute(
            """SELECT COALESCE(SUM(delta_steps), 0) AS total
               FROM readings
               WHERE session_id = ? AND timestamp > ?""",
            (session_id, since),
        ).fetchone()
        return row["total"]

    # --- Step Intervals (upload queue) ---

    def enqueue_interval(
        self, session_id: int, period_start: str, period_end: str, step_count: int
    ) -> int:
        cur = self._conn.execute(
            """INSERT INTO step_intervals
               (session_id, period_start, period_end, step_count)
               VALUES (?, ?, ?, ?)""",
            (session_id, period_start, period_end, step_count),
        )
        return cur.lastrowid

    def get_today_intervals(self) -> list[dict]:
        """All intervals from today (synced + pending, step_count > 0)."""
        today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        rows = self._conn.execute(
            """SELECT id, session_id, period_start, period_end, step_count, synced
               FROM step_intervals
               WHERE period_start >= ? AND step_count > 0
               ORDER BY period_start""",
            (today,),
        ).fetchall()
        return [dict(r) for r in rows]

    def get_pending_intervals(self, limit: int = 50) -> list[dict]:
        rows = self._conn.execute(
            """SELECT id, session_id, period_start, period_end, step_count
               FROM step_intervals
               WHERE synced = 0 AND step_count > 0
               ORDER BY period_start
               LIMIT ?""",
            (limit,),
        ).fetchall()
        return [dict(r) for r in rows]

    def mark_synced(self, interval_id: int):
        self._conn.execute(
            "UPDATE step_intervals SET synced = 1, synced_at = ? WHERE id = ?",
            (_now_iso(), interval_id),
        )

    # --- Status ---

    def get_last_reading_any_session(self) -> dict | None:
        """Get the most recent reading across all sessions."""
        row = self._conn.execute(
            "SELECT * FROM readings ORDER BY id DESC LIMIT 1"
        ).fetchone()
        return dict(row) if row else None

    def get_active_session(self) -> dict | None:
        row = self._conn.execute(
            "SELECT * FROM sessions WHERE ended_at IS NULL ORDER BY id DESC LIMIT 1"
        ).fetchone()
        return dict(row) if row else None

    def get_last_reading(self, session_id: int) -> dict | None:
        row = self._conn.execute(
            """SELECT * FROM readings
               WHERE session_id = ?
               ORDER BY id DESC LIMIT 1""",
            (session_id,),
        ).fetchone()
        return dict(row) if row else None
