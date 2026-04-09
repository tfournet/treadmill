"""HTTPS API for the Android companion app to consume step intervals."""

import json
import logging
import ssl
from pathlib import Path

from aiohttp import web

from .config import APIConfig
from .db import TreadmillDB

log = logging.getLogger("treadmill.api")


def create_app(db: TreadmillDB, ble_manager) -> web.Application:
    app = web.Application()
    app["db"] = db
    app["ble"] = ble_manager

    app.router.add_get("/api/intervals/pending", handle_pending)
    app.router.add_post("/api/intervals/{id}/synced", handle_synced)
    app.router.add_get("/api/status", handle_status)

    return app


async def handle_pending(request: web.Request) -> web.Response:
    db: TreadmillDB = request.app["db"]
    limit = int(request.query.get("limit", "50"))
    intervals = db.get_pending_intervals(limit)
    return web.json_response(intervals)


async def handle_synced(request: web.Request) -> web.Response:
    db: TreadmillDB = request.app["db"]
    interval_id = int(request.match_info["id"])
    db.mark_synced(interval_id)
    return web.json_response({"ok": True})


async def handle_status(request: web.Request) -> web.Response:
    db: TreadmillDB = request.app["db"]
    ble = request.app["ble"]

    session = db.get_active_session()
    last_reading = None
    if session:
        last_reading = db.get_last_reading(session["id"])

    return web.json_response({
        "ble_state": ble.state.value,
        "active_session": session,
        "last_reading": last_reading,
    }, dumps=lambda obj: json.dumps(obj, default=str))


async def run_api(app: web.Application, config: APIConfig):
    runner = web.AppRunner(app)
    await runner.setup()

    ssl_ctx = None
    if config.tls_cert and config.tls_key:
        cert = Path(config.tls_cert).expanduser()
        key = Path(config.tls_key).expanduser()
        if cert.exists() and key.exists():
            ssl_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ssl_ctx.load_cert_chain(cert, key)
            log.info("TLS enabled with cert %s", cert)

    scheme = "https" if ssl_ctx else "http"
    site = web.TCPSite(runner, config.host, config.port, ssl_context=ssl_ctx)
    log.info("API listening on %s://%s:%d", scheme, config.host, config.port)
    await site.start()
    try:
        while True:
            await __import__("asyncio").sleep(3600)
    finally:
        await runner.cleanup()
