# StackExchange.Redis mTLS example

This .NET console application connects to Redis using StackExchange.Redis with TLS, a custom CA certificate, a client certificate, and a client private key.

## Prerequisites

- .NET SDK 7.0 or later
- OpenSSL available in `PATH`
- OpenShift CLI (`oc`) logged in if the Redis password is read from an OpenShift secret

Verify the tools:

```bash
dotnet --version
openssl version
oc whoami
```

## Project layout

```text
StackExchangeRedis/
├── StackExchangeRedis.csproj
├── Program.cs
└── certs/
    ├── demo-ca.crt
    ├── client.crt
    └── client.key
```

Run all commands from the `StackExchangeRedis` directory so the relative certificate paths resolve correctly.

## Restore and compile

```bash
cd StackExchangeRedis
dotnet restore
dotnet build
```

A successful compilation ends with:

```text
Build succeeded.
```

## Run

Read the Redis password from the OpenShift secret and start the application:

```bash
REDIS_HOST="demo-db.redis-enterprise.example.com" \
REDIS_PORT="443" \
REDIS_USERNAME="default" \
REDIS_PASSWORD="$(oc -n redis-enterprise get secret redb-demo-db \
  -o jsonpath='{.data.password}' | base64 --decode)" \
dotnet run
```

To supply the password directly:

```bash
REDIS_HOST="demo-db.redis-enterprise.example.com" \
REDIS_PORT="443" \
REDIS_USERNAME="default" \
REDIS_PASSWORD="your-password" \
dotnet run
```

Optional certificate-path overrides:

```bash
REDIS_CA_CERT="/path/to/demo-ca.crt" \
REDIS_CLIENT_CERT="/path/to/client.crt" \
REDIS_CLIENT_KEY="/path/to/client.key" \
REDIS_PASSWORD="your-password" \
dotnet run
```

Expected output:

```text
Connecting to Redis at demo-db.redis-enterprise.example.com:443...
PING => PONG (12.34 ms)
SET demo_key => OK
GET demo_key => hello-from-csharp
```

The application uses OpenSSL at runtime to convert the PEM client certificate and private key into a temporary PKCS#12 certificate. The temporary file is deleted after it is loaded.

Keep `certs/client.key` private and do not commit it to source control.
