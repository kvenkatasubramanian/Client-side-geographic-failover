# Jedis mTLS example

This project connects to Redis using Jedis with TLS, a custom CA certificate, a client certificate, and a client private key.

## Prerequisites

- Java 17 or later
- Maven
- OpenSSL available in `PATH`
- OpenShift CLI (`oc`) logged in if the Redis password is read from an OpenShift secret

Verify the tools:

```bash
java -version
mvn -version
openssl version
oc whoami
```

## Project layout

```text
Jedis/
├── pom.xml
├── certs/
│   ├── demo-ca.crt
│   ├── client.crt
│   └── client.key
└── src/
    └── main/
        └── java/
            └── Jedis_conn.java
```

Run all commands from the `Jedis` directory so the relative certificate paths resolve correctly.

## Compile

```bash
cd Jedis
mvn clean compile
```

A successful compilation ends with:

```text
BUILD SUCCESS
```

## Run

Read the Redis password from the OpenShift secret and start the application:

```bash
REDIS_HOST="demo-db.redis-enterprise.example.com" \
REDIS_PORT="443" \
REDIS_USERNAME="default" \
REDIS_PASSWORD="$(oc -n redis-enterprise get secret redb-demo-db \
  -o jsonpath='{.data.password}' | base64 --decode)" \
mvn exec:java
```

To supply the password directly:

```bash
REDIS_HOST="demo-db.redis-enterprise.example.com" \
REDIS_PORT="443" \
REDIS_USERNAME="default" \
REDIS_PASSWORD="your-password" \
mvn exec:java
```

Optional certificate-path overrides:

```bash
REDIS_CA_CERT="/path/to/demo-ca.crt" \
REDIS_CLIENT_CERT="/path/to/client.crt" \
REDIS_CLIENT_KEY="/path/to/client.key" \
REDIS_PASSWORD="your-password" \
mvn exec:java
```

Expected output:

```text
Connecting to Redis at demo-db.redis-enterprise.example.com:443...
PING => PONG
SET demo_key => OK
GET demo_key => hello-from-java
```

Keep `certs/client.key` private and do not commit it to source control.
