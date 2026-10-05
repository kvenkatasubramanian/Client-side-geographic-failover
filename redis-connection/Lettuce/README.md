# Lettuce Redis mTLS Example

This example connects to Redis using the Lettuce Java client, TLS client certificates, and Redis password authentication.

## Directory structure

```text
Lettuce/
├── pom.xml
├── certs/
│   ├── demo-ca.crt
│   ├── client.crt
│   └── client.key
└── src/
    └── main/
        └── java/
            └── Lettuce_conn.java
```

## Prerequisites

- Java 17 or later
- Apache Maven
- OpenShift CLI (`oc`)
- Access to the OpenShift cluster and the `redis-enterprise` namespace
- CA certificate, client certificate, and PKCS#8 client private key in the `certs` directory

Verify Java and Maven:

```bash
java -version
mvn -version
```

## Compile

From the `Lettuce` directory, run:

```bash
mvn clean compile
```

## Execute

Retrieve the Redis password from the OpenShift secret:

```bash
DB_PASS="$(
  oc -n redis-enterprise get secret redb-demo-db \
    -o jsonpath='{.data.password}' |
  base64 --decode
)"
```

Run the application:

```bash
REDIS_PASSWORD="$DB_PASS" mvn exec:java
```

To compile and execute with one command:

```bash
REDIS_PASSWORD="$DB_PASS" mvn clean compile exec:java
```

If the Redis database requires an ACL username, include it when running:

```bash
REDIS_USERNAME="default" \
REDIS_PASSWORD="$DB_PASS" \
mvn exec:java
```

## Optional configuration

The application supports the following environment variables:

| Variable | Purpose |
|---|---|
| `REDIS_HOST` | Redis hostname |
| `REDIS_PORT` | TLS port; defaults to `443` |
| `REDIS_USERNAME` | Optional Redis ACL username |
| `REDIS_PASSWORD` | Redis password |
| `REDIS_CA_CERT` | Path to the Redis CA certificate |
| `REDIS_CLIENT_CERT` | Path to the client certificate |
| `REDIS_CLIENT_KEY` | Path to the PKCS#8 client private key |

On success, the output should resemble:

```text
Pinging Redis at demo-db.redis-enterprise.example.com:443...
PING => PONG
SET => OK
GET demo_key => hello-from-lettuce
```
