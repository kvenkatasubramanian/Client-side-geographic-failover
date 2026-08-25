import inspect
import json
import logging
import sys
import time
import warnings
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

import redis
from redis.multidb.client import MultiDBClient
from redis.multidb.config import DatabaseConfig, MultiDbConfig

try:
    from redis.event import EventDispatcher, EventListenerInterface
    from redis.multidb.event import ActiveDatabaseChanged
except ImportError:
    EventDispatcher = None
    EventListenerInterface = object
    ActiveDatabaseChanged = None


@dataclass
class TlsConfig:
    enabled: bool = True
    root_ca: str | None = None
    client_crt: str | None = None
    client_key: str | None = None


@dataclass
class RedisEndpoint:
    name: str
    host: str
    port: int
    weight: float
    username: str | None = None
    password: str | None = None


@dataclass
class AppConfig:
    tls: TlsConfig
    databases: list[RedisEndpoint]


class LogFailoverEventListener(EventListenerInterface):
    def __init__(self, endpoint_labels: dict[tuple[str, int], str]):
        self.endpoint_labels = endpoint_labels

    def listen(self, event: Any) -> None:
        old_database = describe_database(
            getattr(event, "old_database", None),
            self.endpoint_labels,
        )
        new_database = describe_database(
            getattr(event, "new_database", None),
            self.endpoint_labels,
        )

        print()
        print("=================================================")
        print("DATABASE SWITCH DETECTED")
        print(f"Time      : {datetime.now().isoformat()}")
        print(f"Switched  : {old_database} -> {new_database}")
        print("Reason    : Active database changed")
        print()
        print("Traffic switched automatically to healthy database endpoint.")
        print("Application continues running.")
        print("=================================================")
        print()


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    logging.getLogger("redis.multidb").setLevel(logging.ERROR)

    config_file = Path(sys.argv[1] if len(sys.argv) > 1 else "redis-config.json")

    try:
        config = load_config(config_file)
        validate_configuration(config)
    except Exception as exc:
        print()
        print("INVALID CONFIGURATION")
        print("----------------------------------------------")
        print(root_cause_message(exc))
        print("----------------------------------------------")
        return 1

    if not validate_all_endpoints(config):
        print()
        print("==============================================")
        print("STARTUP ABORTED")
        print("==============================================")
        print("One or more Redis endpoints are unavailable.")
        print("Application will NOT start.")
        print()
        return 1

    endpoint_labels = create_endpoint_labels(config.databases)
    event_dispatcher = create_event_dispatcher(endpoint_labels)
    database_configs = [
        DatabaseConfig(
            client_kwargs=create_client_kwargs(config.tls, endpoint),
            weight=endpoint.weight,
        )
        for endpoint in config.databases
    ]

    multidb_kwargs = build_multidb_kwargs(
        databases_config=database_configs,
        event_dispatcher=event_dispatcher,
    )
    with warnings.catch_warnings():
        warnings.filterwarnings(
            "ignore",
            message="MultiDBClient is an experimental.*",
            category=UserWarning,
        )
        client = MultiDBClient(MultiDbConfig(**multidb_kwargs))

    try:
        print()
        print("==============================================")
        print("APPLICATION STARTED")
        print("==============================================")
        print(f"Configured Databases: {len(config.databases)}")
        print(f"TLS Enabled: {config.tls.enabled}")
        print("==============================================")
        print()

        run_application_loop(client, endpoint_labels)
    except KeyboardInterrupt:
        print()
        print("Interrupted.")
    finally:
        close_client(client)
        print("Shutdown complete.")

    return 0


def load_config(config_file: Path) -> AppConfig:
    raw = json.loads(config_file.read_text(encoding="utf-8"))

    tls_raw = raw.get("tls") or {}
    tls = TlsConfig(
        enabled=tls_raw.get("enabled", True),
        root_ca=tls_raw.get("rootCa"),
        client_crt=tls_raw.get("clientCrt"),
        client_key=tls_raw.get("clientKey"),
    )

    databases = []
    for db in raw.get("databases") or []:
        databases.append(
            RedisEndpoint(
                name=db.get("name"),
                host=db.get("host"),
                port=int(db.get("port", 0)),
                username=blank_to_none(db.get("username")),
                password=blank_to_none(db.get("password")),
                weight=float(db.get("weight", 0)),
            )
        )

    return AppConfig(tls=tls, databases=databases)


def validate_configuration(config: AppConfig) -> None:
    if config.tls.enabled:
        if is_blank(config.tls.root_ca):
            raise ValueError("tls.rootCa is required when TLS is enabled")
        if is_blank(config.tls.client_crt):
            raise ValueError("tls.clientCrt is required when TLS is enabled")
        if is_blank(config.tls.client_key):
            raise ValueError("tls.clientKey is required when TLS is enabled")

    if not config.databases:
        raise ValueError("At least one database must be configured")

    for index, endpoint in enumerate(config.databases):
        if is_blank(endpoint.name):
            raise ValueError(f"Database entry {index}: name is required")
        if is_blank(endpoint.host):
            raise ValueError(f"Database {endpoint.name}: host is required")
        if endpoint.port <= 0 or endpoint.port > 65535:
            raise ValueError(f"Database {endpoint.name}: invalid port {endpoint.port}")
        if endpoint.weight <= 0:
            raise ValueError(f"Database {endpoint.name}: weight must be > 0")


def validate_all_endpoints(config: AppConfig) -> bool:
    all_healthy = True

    print()
    print("==============================================")
    print("REDIS PRE-FLIGHT CHECK")
    print("==============================================")
    print(f"Checking {len(config.databases)} configured endpoint(s)")
    print()

    for endpoint in config.databases:
        if not check_endpoint(config.tls, endpoint):
            all_healthy = False
        print()

    print("==============================================")
    if all_healthy:
        print("PRE-FLIGHT RESULT: ALL ENDPOINTS HEALTHY")
    else:
        print("PRE-FLIGHT RESULT: FAILURE")
    print("==============================================")

    return all_healthy


def check_endpoint(tls: TlsConfig, endpoint: RedisEndpoint) -> bool:
    print(f"Checking: {endpoint.name} [{endpoint.host}:{endpoint.port}]")

    client = redis.Redis(**create_client_kwargs(tls, endpoint))
    start = time.monotonic()

    try:
        response = client.ping()
        elapsed_ms = int((time.monotonic() - start) * 1000)

        if response is True or str(response).upper() == "PONG":
            print("  STATUS : OK")
            print("  PING   : PONG")
            print(f"  TIME   : {elapsed_ms} ms")
            return True

        print("  STATUS : FAILED")
        print(f"  PING   : Unexpected response: {response}")
        return False

    except Exception as exc:
        print("  STATUS : FAILED")
        print(f"  REASON : {root_cause_message(exc)}")
        return False
    finally:
        client.close()


def create_client_kwargs(tls: TlsConfig, endpoint: RedisEndpoint) -> dict[str, Any]:
    kwargs: dict[str, Any] = {
        "host": endpoint.host,
        "port": endpoint.port,
        "socket_connect_timeout": 10,
        "socket_timeout": 10,
        "decode_responses": True,
    }

    if endpoint.username is not None:
        kwargs["username"] = endpoint.username
    if endpoint.password is not None:
        kwargs["password"] = endpoint.password

    if tls.enabled:
        kwargs.update(
            {
                "ssl": True,
                "ssl_ca_certs": tls.root_ca,
                "ssl_certfile": tls.client_crt,
                "ssl_keyfile": tls.client_key,
            }
        )
    else:
        kwargs["ssl"] = False

    return kwargs


def build_multidb_kwargs(
    databases_config: list[DatabaseConfig],
    event_dispatcher: Any,
) -> dict[str, Any]:
    candidate_kwargs = {
        "databases_config": databases_config,
        "event_dispatcher": event_dispatcher,
        "min_num_failures": 1,
        "failure_rate_threshold": 0.5,
        "failures_detection_window": 2,
        "failover_attempts": 10,
        "failover_delay": 2,
        "auto_fallback_interval": 10,
        "health_check_interval": 5,
        "health_check_probes": 3,
    }

    signature = inspect.signature(MultiDbConfig)
    supported = {
        name: value
        for name, value in candidate_kwargs.items()
        if value is not None and name in signature.parameters
    }

    return supported


def create_endpoint_labels(endpoints: list[RedisEndpoint]) -> dict[tuple[str, int], str]:
    return {
        (endpoint.host, endpoint.port): f"{endpoint.name} [{endpoint.host}:{endpoint.port}]"
        for endpoint in endpoints
    }


def create_event_dispatcher(endpoint_labels: dict[tuple[str, int], str]) -> Any:
    if EventDispatcher is None or ActiveDatabaseChanged is None:
        return None

    dispatcher = EventDispatcher()
    listener = LogFailoverEventListener(endpoint_labels)
    dispatcher.register_listeners({ActiveDatabaseChanged: [listener]})
    return dispatcher


def run_application_loop(
    client: MultiDBClient,
    endpoint_labels: dict[tuple[str, int], str],
) -> None:
    print("Press Ctrl+C to stop.")
    print()

    counter = 0
    last_endpoint = ""

    while True:
        try:
            key = "failover:test"
            value = f"value-{counter}"

            client.set(key, value)
            result = client.get(key)
            current_endpoint = describe_active_database(client, endpoint_labels)

            if current_endpoint != last_endpoint:
                last_endpoint = current_endpoint

                print("---------------------------------------------")
                print(f"ACTIVE DATABASE : {current_endpoint}")
                print("---------------------------------------------")

            print(
                f"{datetime.now().isoformat()} | {counter} | "
                f"{current_endpoint} | {result}"
            )
            counter += 1
        except Exception as exc:
            print()
            print("*********** OPERATION FAILED ***********")
            print(datetime.now().isoformat())
            print(f"Reason: {root_cause_message(exc)}")
            print("***************************************")
            print()

        time.sleep(1)


def close_client(client: MultiDBClient) -> None:
    close = getattr(client, "close", None)
    if callable(close):
        close()


def describe_active_database(
    client: MultiDBClient,
    endpoint_labels: dict[tuple[str, int], str],
) -> str:
    command_executor = getattr(client, "command_executor", None)
    active_database = getattr(command_executor, "active_database", None)

    return describe_database(active_database, endpoint_labels)


def describe_database(
    database: Any,
    endpoint_labels: dict[tuple[str, int], str],
) -> str:
    if database is None:
        return "unknown"

    redis_client = getattr(database, "client", None)
    connection_pool = getattr(redis_client, "connection_pool", None)
    connection_kwargs = getattr(connection_pool, "connection_kwargs", {}) or {}

    host = connection_kwargs.get("host")
    port = connection_kwargs.get("port")

    if host is None or port is None:
        return "unknown"

    try:
        endpoint_key = (str(host), int(port))
    except (TypeError, ValueError):
        return f"{host}:{port}"

    return endpoint_labels.get(endpoint_key, f"{host}:{port}")


def blank_to_none(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def is_blank(value: str | None) -> bool:
    return value is None or value.strip() == ""


def root_cause_message(exc: BaseException) -> str:
    root = exc
    while root.__cause__ is not None:
        root = root.__cause__
    return f"{root.__class__.__name__}: {root}"


if __name__ == "__main__":
    raise SystemExit(main())
