# Client-side Geographic Failover

This repository contains Java examples demonstrating **client-side geographic failover** using both the **Lettuce** and **Jedis** Redis client libraries.

The examples show how an application can automatically switch between multiple geographically distributed Redis Enterprise databases when the active database becomes unavailable, and automatically fail back to the preferred database when it recovers.

The implementations are intended as reference examples for learning, testing, and demonstrating client-side geographic failover.

---

# Repository Structure

```text
Client-side-geographic-failover/
│
├── README.md
│
├── lettuce-multidb-demo/
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradle.properties
│   ├── redis-config.json
│   └── src/
│       └── main/
│           └── java/
│               └── LettuceMultiDbTlsExample.java
│
└── jedis-multidb-demo/
    ├── build.gradle
    ├── settings.gradle
    ├── gradle.properties
    ├── redis-config.json
    └── src/
        └── main/
            └── java/
                └── JedisMultiDbTlsExample.java
```

---

# Projects

## Lettuce Example

Demonstrates client-side geographic failover using the Lettuce `MultiDbClient`.

Features include:

- Multiple Redis Enterprise databases
- Weighted database selection
- Automatic failover
- Automatic failback
- Mutual TLS (mTLS)
- Startup pre-flight health validation
- Continuous Redis operations (`SET` / `GET`)
- Failover event logging
- Graceful shutdown

---

## Jedis Example

Demonstrates client-side geographic failover using the Jedis `MultiDbClient`.

Features include:

- Multiple Redis Enterprise databases
- Weighted database selection
- Automatic failover
- Automatic failback
- Mutual TLS (mTLS)
- Startup pre-flight health validation
- Continuous Redis operations (`SET` / `GET`)
- Failover event logging
- Graceful shutdown

Jedis implements client-side failover using `MultiDbClient`, circuit breakers, retry policies, configurable health checks, and background recovery detection.

---

# What the Examples Do

Both examples follow the same workflow.

1. Load the Redis configuration from `redis-config.json`.
2. Configure mutual TLS using the supplied certificates.
3. Validate every configured Redis database before startup.
4. Exit immediately if any configured database is unreachable.
5. Create the MultiDb client.
6. Start issuing Redis commands continuously.
7. Automatically fail over when the active database becomes unavailable.
8. Automatically fail back when the preferred database recovers.
9. Log all database switch events.

---

# Prerequisites

- Java 17 or later
- Gradle
- Network connectivity to all Redis Enterprise databases
- Redis Enterprise databases configured for client-side geographic failover
- Mutual TLS certificates

Required certificate files:

```text
rootca.crt
client.crt
client.key
```

---

# Configuration

Both projects use the same configuration format.

Configuration is stored in:

```text
redis-config.json
```

Example:

```json
{
  "tls": {
    "rootCa": "certs/rootca.crt",
    "clientCrt": "certs/client.crt",
    "clientKey": "certs/client.key"
  },

  "databases": [
    {
      "name": "primary",
      "host": "redis-east.example.com",
      "port": 443,
      "username": "default",
      "password": "password",
      "weight": 100.0
    },
    {
      "name": "secondary-west",
      "host": "redis-west.example.com",
      "port": 443,
      "username": "default",
      "password": "password",
      "weight": 50.0
    }
  ]
}
```

---

# Configuration Parameters

| Parameter | Description |
|-----------|-------------|
| name | Friendly database name |
| host | Redis Enterprise hostname |
| port | Redis port (typically 443 for TLS) |
| username | Redis username |
| password | Redis password |
| weight | Database selection priority |

Higher weight means higher priority during database selection.

Only one database is active at a time. Weight determines which healthy database should be preferred; it does **not** distribute traffic across databases.

---

# Startup Validation

Before creating the MultiDb client, every configured database is validated.

Each validation performs:

- DNS resolution
- TCP connectivity
- TLS handshake
- Authentication
- Redis `PING`

If any database fails validation, the application exits with a non-zero status.

Example:

```text
REDIS PRE-FLIGHT CHECK

Checking: primary
STATUS : OK

Checking: secondary-west
STATUS : OK

Checking: secondary-europe
STATUS : FAILED
REASON : Connection refused

STARTUP ABORTED
```

---

# Building

## Lettuce

```bash
cd lettuce-multidb-demo
gradle clean build
```

## Jedis

```bash
cd jedis-multidb-demo
gradle clean build
```

---

# Running

## Lettuce

```bash
gradle run \
  -PmainClass=LettuceMultiDbTlsExample
```

or

```bash
gradle run \
  -PmainClass=LettuceMultiDbTlsExample \
  --args="redis-config.json"
```

---

## Jedis

```bash
gradle run \
  -PmainClass=JedisMultiDbTlsExample
```

or

```bash
gradle run \
  -PmainClass=JedisMultiDbTlsExample \
  --args="redis-config.json"
```

---

# Expected Output

Successful startup:

```text
REDIS PRE-FLIGHT CHECK

Checking: primary
STATUS : OK

Checking: secondary-west
STATUS : OK

PRE-FLIGHT RESULT: ALL ENDPOINTS HEALTHY

APPLICATION STARTED

ACTIVE DATABASE : primary
```

When failover occurs:

```text
DATABASE SWITCH DETECTED

Time      : 2026-08-11T10:45:01
From      : primary
To        : secondary-west
Reason    : Connection failure
```

---

# Security Notes

- Do not commit production credentials to source control.
- Protect `redis-config.json` if it contains passwords.
- Consider using environment variables or a secrets manager for production deployments.
- Store client certificates securely.

---

# Notes

- Both examples are designed for demonstration and testing purposes.
- The configuration supports one or more Redis databases.
- The same `redis-config.json` format can be used by both implementations.
- The examples are intended to illustrate client-side geographic failover behavior and can be extended for production environments.