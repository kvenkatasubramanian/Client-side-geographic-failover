r"""Redis client for the local demo in this repo (with optional TLS support).

Examples:
  # With TLS certificates (mutual TLS):
    REDIS_HOST="localhost" \
    REDIS_PORT="443" \
    REDIS_USERNAME="default" \
    REDIS_CA_CERT="certs/ca.crt" \
    REDIS_CLIENT_CERT="certs/client.crt" \
    REDIS_CLIENT_KEY="certs/client.key" \
    REDIS_PASSWORD="optional-password" \
    python python_conn.py

  # Plain TCP (no encryption):
  REDIS_HOST=localhost \
  REDIS_PORT=6379 \
  REDIS_PASSWORD="optional-password" \
  python python_conn.py

  # Minimum required (no password, no TLS):
  REDIS_HOST=localhost \
  REDIS_PORT=6379 \
  python python_conn.py
"""

import inspect
import os
import ssl

import redis
from redis.connection import SSLConnection


DEFAULT_PORT = 443


def _relax_strict_x509(config):
    if os.environ.get("REDIS_SSL_STRICT") == "1":
        return
    strict = getattr(ssl, "VERIFY_X509_STRICT", None)
    supported = "ssl_exclude_verify_flags" in inspect.signature(SSLConnection.__init__).parameters
    if strict is not None and supported:
        config["ssl_exclude_verify_flags"] = [strict]


def build_redis_client():
    host = os.environ.get("REDIS_HOST")
    port = int(os.environ.get("REDIS_PORT", str(DEFAULT_PORT)))
    username = os.environ.get("REDIS_USERNAME") or None
    password = os.environ.get("REDIS_PASSWORD")

    # Certificates are optional and only used when explicitly provided.
    ca_cert = os.environ.get("REDIS_CA_CERT")
    client_cert = os.environ.get("REDIS_CLIENT_CERT")
    client_key = os.environ.get("REDIS_CLIENT_KEY")

    config = {
        "host": host,
        "port": port,
        "decode_responses": True,
    }

    if username:
        config["username"] = username
    if password is not None:
        config["password"] = password

    # Check if SSL should be enabled
    use_ssl = os.environ.get("REDIS_USE_SSL", "").lower() == "1"
    certs_available = any(
        value and os.path.exists(value) for value in (ca_cert, client_cert, client_key)
    )

    if use_ssl or certs_available:
        config["ssl"] = True
        
        # Only require client certs if they're actually provided
        if certs_available:
            config["ssl_cert_reqs"] = "required"
        else:
            # Server-only verification (no client certs required)
            config["ssl_cert_reqs"] = "none"

        for key, value in (
            ("ssl_ca_certs", ca_cert),
            ("ssl_certfile", client_cert),
            ("ssl_keyfile", client_key),
        ):
            if value and os.path.exists(value):
                config[key] = value

        _relax_strict_x509(config)

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
