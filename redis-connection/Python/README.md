# Python Redis mTLS example

This Python application connects to Redis using TLS, a custom CA certificate, a client certificate, and a client private key.

## Prerequisites

- Python 3.9 or later
- `pip`
- OpenShift CLI (`oc`) logged in if the Redis password is read from an OpenShift secret

Verify the tools:

```bash
python3 --version
python3 -m pip --version
oc whoami
```

## Project layout

```text
Python/
├── python_conn.py
└── certs/
    ├── demo-ca.crt
    ├── client.crt
    └── client.key
```

The default certificate paths are resolved relative to `python_conn.py`.

## Create the environment

Create and activate a virtual environment:

```bash
cd Python
python3 -m venv .venv
source .venv/bin/activate
```

Install the Redis Python client:

```bash
python -m pip install --upgrade pip
python -m pip install redis
```

## Run

Read the Redis password from the OpenShift secret and start the application:

```bash
REDIS_HOST="demo-db.redis-enterprise.example.com" \
REDIS_PORT="443" \
REDIS_USERNAME="default" \
REDIS_PASSWORD="$(oc -n redis-enterprise get secret redb-demo-db \
  -o jsonpath='{.data.password}' | base64 --decode)" \
python python_conn.py
```

To supply the password directly:

```bash
REDIS_HOST="demo-db.redis-enterprise.example.com" \
REDIS_PORT="443" \
REDIS_USERNAME="default" \
REDIS_PASSWORD="your-password" \
python python_conn.py
```

Optional certificate-path overrides:

```bash
REDIS_CA_CERT="/path/to/demo-ca.crt" \
REDIS_CLIENT_CERT="/path/to/client.crt" \
REDIS_CLIENT_KEY="/path/to/client.key" \
REDIS_PASSWORD="your-password" \
python python_conn.py
```

Expected output:

```text
Pinging Redis at demo-db.redis-enterprise.example.com:443...
True
SET demo_key=hello-from-python
GET demo_key => hello-from-python
```

## Deactivate the environment

```bash
deactivate
```

Keep `certs/client.key` private and do not commit it or the `.venv` directory to source control.
