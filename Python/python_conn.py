"""Redis TLS client for the local demo in this repo.

Example:
  DB_PASS=$(oc -n redis-enterprise get secret redb-demo-db -o jsonpath='{.data.password}' | base64 --decode)
  REDIS_HOST=demo-db.redis-enterprise.example.com \
  REDIS_PORT=443 \
  REDIS_PASSWORD="$DB_PASS" \
  python python_conn.py
"""

import os

import redis


BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_HOST = "demo-db.redis-enterprise.example.com"
DEFAULT_PORT = 443


def _existing_path(value, *parts):
    if value:
        return value
    return os.path.join(BASE_DIR, *parts)


def build_redis_client():
    host = os.environ.get("REDIS_HOST", DEFAULT_HOST)
    port = int(os.environ.get("REDIS_PORT", str(DEFAULT_PORT)))
    username = os.environ.get("REDIS_USERNAME") or None
    password = os.environ.get("REDIS_PASSWORD")

    ca_cert = _existing_path(os.environ.get("REDIS_CA_CERT"), "certs", "demo-ca.crt")
    client_cert = _existing_path(
        os.environ.get("REDIS_CLIENT_CERT"), "certs", "client.crt"
    )
    client_key = _existing_path(os.environ.get("REDIS_CLIENT_KEY"), "certs", "client.key")

    config = {
        "host": host,
        "port": port,
        "decode_responses": True,
        "ssl": True,
        "ssl_cert_reqs": "required",
    }

    if username:
        config["username"] = username
    if password is not None:
        config["password"] = password

    for key, value in (
        ("ssl_ca_certs", ca_cert),
        ("ssl_certfile", client_cert),
        ("ssl_keyfile", client_key),
    ):
        if os.path.exists(value):
            config[key] = value

    return redis.Redis(**config)


def run_demo(client):
    print(
        f"Pinging Redis at {client.connection_pool.connection_kwargs['host']}:{client.connection_pool.connection_kwargs['port']}..."
    )
    print(client.ping())

    key = "demo_key"
    value = "hello-from-python"
    client.set(key, value)
    print(f"SET {key}={value}")
    print(f"GET {key} => {client.get(key)}")


if __name__ == "__main__":
    try:
        run_demo(build_redis_client())
    except Exception as exc:  # pragma: no cover - runtime error should be visible to the user
        print(f"Connection failed: {exc}")
        raise SystemExit(1)
