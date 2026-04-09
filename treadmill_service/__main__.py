"""Entry point: python -m treadmill_service"""

import asyncio
import logging
import os
import signal

from .ble import BLEManager
from .collector import Collector
from .config import load_config
from .db import TreadmillDB

log = logging.getLogger("treadmill")


def setup_logging():
    running_under_systemd = "JOURNAL_STREAM" in os.environ
    fmt = "%(levelname)s %(name)s: %(message)s" if running_under_systemd else \
          "%(asctime)s %(levelname)s %(name)s: %(message)s"
    logging.basicConfig(level=logging.INFO, format=fmt)


async def main():
    setup_logging()
    config = load_config()

    log.info("Treadmill service starting")
    log.info("DB: %s", config.db.path)
    log.info("BLE target: %s", config.ble.device_name)
    log.info("Poll interval: %.0fs", config.ble.poll_interval_secs)
    log.info("Interval aggregation: %.0fs", config.intervals.aggregate_every_secs)
    log.info("API: %s:%d", config.api.host, config.api.port)

    db = TreadmillDB(config.db.path)
    db.open()

    collector = Collector(config, db)
    ble = BLEManager(config.ble, on_reading=collector.on_reading)

    # Import API lazily to keep aiohttp optional during early development
    from .api import create_app, run_api

    app = create_app(db, ble)

    shutdown = asyncio.Event()

    def on_signal():
        log.info("Shutdown signal received")
        shutdown.set()

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, on_signal)

    async with asyncio.TaskGroup() as tg:
        ble_task = tg.create_task(ble.run(), name="ble")
        api_task = tg.create_task(run_api(app, config.api), name="api")

        await shutdown.wait()
        log.info("Flushing collector...")
        await collector.flush()
        ble_task.cancel()
        api_task.cancel()

    db.close()
    log.info("Treadmill service stopped")


if __name__ == "__main__":
    asyncio.run(main())
