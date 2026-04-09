"""Configuration model with TOML file + env var support."""

import tomllib
from dataclasses import dataclass, field
from pathlib import Path

_DEFAULT_CONFIG_PATH = Path("~/.config/treadmill/config.toml").expanduser()
_DEFAULT_DB_PATH = Path("~/.local/share/treadmill/treadmill.db").expanduser()


@dataclass
class BLEConfig:
    device_name: str = "SPERAX_RM01"
    address: str | None = None
    poll_interval_secs: float = 30.0
    scan_timeout_secs: float = 10.0
    adapter_reset_after_failures: int = 3
    max_backoff_secs: float = 300.0


@dataclass
class DBConfig:
    path: Path = field(default_factory=lambda: _DEFAULT_DB_PATH)


@dataclass
class APIConfig:
    host: str = "0.0.0.0"
    port: int = 8080


@dataclass
class IntervalConfig:
    aggregate_every_secs: float = 300.0  # 5 minutes


@dataclass
class Config:
    ble: BLEConfig = field(default_factory=BLEConfig)
    db: DBConfig = field(default_factory=DBConfig)
    api: APIConfig = field(default_factory=APIConfig)
    intervals: IntervalConfig = field(default_factory=IntervalConfig)


def load_config(path: Path = _DEFAULT_CONFIG_PATH) -> Config:
    """Load config from TOML file, falling back to defaults for missing keys."""
    config = Config()
    if path.exists():
        with open(path, "rb") as f:
            data = tomllib.load(f)
        if "ble" in data:
            for k, v in data["ble"].items():
                if hasattr(config.ble, k):
                    setattr(config.ble, k, v)
        if "db" in data:
            if "path" in data["db"]:
                config.db.path = Path(data["db"]["path"]).expanduser()
        if "api" in data:
            for k, v in data["api"].items():
                if hasattr(config.api, k):
                    setattr(config.api, k, v)
        if "intervals" in data:
            for k, v in data["intervals"].items():
                if hasattr(config.intervals, k):
                    setattr(config.intervals, k, v)
    return config
