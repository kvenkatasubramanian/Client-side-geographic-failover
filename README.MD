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


# Sample Output
```text
==============================================
REDIS PRE-FLIGHT CHECK
==============================================
Checking 4 configured endpoint(s)

Checking: primary [demo-db.redis-enterprise.example.com:443]
  STATUS : OK
  PING   : PONG
  TIME   : 593 ms

Checking: secondary-1 [test-db.redis-enterprise.example.com:443]
  STATUS : OK
  PING   : PONG
  TIME   : 395 ms

Checking: secondary-1 [test-db2.redis-enterprise.example.com:443]
  STATUS : OK
  PING   : PONG
  TIME   : 275 ms

Checking: secondary-1 [test-db3.redis-enterprise.example.com:443]
  STATUS : OK
  PING   : PONG
  TIME   : 278 ms

==============================================
PRE-FLIGHT RESULT: ALL ENDPOINTS HEALTHY
==============================================

==============================================
APPLICATION STARTED
==============================================
Current Database: test-db2.redis-enterprise.example.com:443
Configured Databases: 4
==============================================

Press Ctrl+C to stop.

---------------------------------------------
ACTIVE DATABASE : test-db2.redis-enterprise.example.com:443
---------------------------------------------
2026-08-11T20:42:16.439692 | 0 | test-db2.redis-enterprise.example.com:443 | value-0
2026-08-11T20:42:17.535134 | 1 | test-db2.redis-enterprise.example.com:443 | value-1
2026-08-11T20:42:18.628081 | 2 | test-db2.redis-enterprise.example.com:443 | value-2
2026-08-11T20:42:19.726821 | 3 | test-db2.redis-enterprise.example.com:443 | value-3
2026-08-11T20:42:20.810633 | 4 | test-db2.redis-enterprise.example.com:443 | value-4
2026-08-11T20:42:21.901991 | 5 | test-db2.redis-enterprise.example.com:443 | value-5
2026-08-11T20:42:22.989051 | 6 | test-db2.redis-enterprise.example.com:443 | value-6
2026-08-11T20:42:24.077128 | 7 | test-db2.redis-enterprise.example.com:443 | value-7

=================================================
DATABASE SWITCH DETECTED
Time      : 2026-08-11T20:42:26.359698
Database  : database:test-db.redis-enterprise.example.com:443
Endpoint  : test-db.redis-enterprise.example.com:443
Reason    : Endpoint health check failed

Traffic switched automatically to healthy database endpoint.
Application continues running.
=================================================

[main] ERROR redis.clients.jedis.mcf.MultiDbConnectionProvider - 2026-08-11T20:42:36.120347-05:00[America/Chicago]: CircuitBreaker 'database:test-db2.redis-enterprise.example.com:443' recorded an error: 'redis.clients.jedis.exceptions.JedisConnectionException: java.net.SocketTimeoutException: Read timed out'. Elapsed time: 11041 ms
[main] ERROR redis.clients.jedis.mcf.MultiDbConnectionProvider - 2026-08-11T20:42:36.125591-05:00[America/Chicago]: Retry 'database:test-db2.redis-enterprise.example.com:443' recorded a failed retry attempt. Number of retry attempts: '1'. Giving up. Last exception was: 'redis.clients.jedis.exceptions.JedisConnectionException: java.net.SocketTimeoutException: Read timed out'.

*********** OPERATION FAILED ***********
2026-08-11T20:42:36.126142
Reason: SocketTimeoutException: Read timed out
***************************************

---------------------------------------------
ACTIVE DATABASE : test-db.redis-enterprise.example.com:443
---------------------------------------------
2026-08-11T20:42:37.442029 | 8 | test-db.redis-enterprise.example.com:443 | value-8
2026-08-11T20:42:38.532311 | 9 | test-db.redis-enterprise.example.com:443 | value-9
2026-08-11T20:42:39.618789 | 10 | test-db.redis-enterprise.example.com:443 | value-10
2026-08-11T20:42:40.718194 | 11 | test-db.redis-enterprise.example.com:443 | value-11
2026-08-11T20:42:41.805923 | 12 | test-db.redis-enterprise.example.com:443 | value-12
2026-08-11T20:42:42.901188 | 13 | test-db.redis-enterprise.example.com:443 | value-13
2026-08-11T20:42:43.990615 | 14 | test-db.redis-enterprise.example.com:443 | value-14
2026-08-11T20:42:45.080844 | 15 | test-db.redis-enterprise.example.com:443 | value-15
2026-08-11T20:42:46.161797 | 16 | test-db.redis-enterprise.example.com:443 | value-16
2026-08-11T20:42:47.249985 | 17 | test-db.redis-enterprise.example.com:443 | value-17
2026-08-11T20:42:48.340021 | 18 | test-db.redis-enterprise.example.com:443 | value-18
2026-08-11T20:42:49.426021 | 19 | test-db.redis-enterprise.example.com:443 | value-19
2026-08-11T20:42:50.568320 | 20 | test-db.redis-enterprise.example.com:443 | value-20
2026-08-11T20:42:51.655424 | 21 | test-db.redis-enterprise.example.com:443 | value-21
2026-08-11T20:42:52.745223 | 22 | test-db.redis-enterprise.example.com:443 | value-22
2026-08-11T20:42:53.831838 | 23 | test-db.redis-enterprise.example.com:443 | value-23
2026-08-11T20:42:54.919925 | 24 | test-db.redis-enterprise.example.com:443 | value-24
2026-08-11T20:42:56.006772 | 25 | test-db.redis-enterprise.example.com:443 | value-25

=================================================
DATABASE SWITCH DETECTED
Time      : 2026-08-11T20:42:56.427320
Database  : database:test-db2.redis-enterprise.example.com:443
Endpoint  : test-db2.redis-enterprise.example.com:443
Reason    : Endpoint became unhealthy

Traffic switched automatically to healthy database endpoint.
Application continues running.
=================================================

---------------------------------------------
ACTIVE DATABASE : test-db2.redis-enterprise.example.com:443
---------------------------------------------
2026-08-11T20:42:57.094477 | 26 | test-db2.redis-enterprise.example.com:443 | value-26
2026-08-11T20:42:58.175681 | 27 | test-db2.redis-enterprise.example.com:443 | value-27
2026-08-11T20:42:59.265437 | 28 | test-db2.redis-enterprise.example.com:443 | value-28
2026-08-11T20:43:00.364701 | 29 | test-db2.redis-enterprise.example.com:443 | value-29
2026-08-11T20:43:01.461520 | 30 | test-db2.redis-enterprise.example.com:443 | value-30
2026-08-11T20:43:02.548710 | 31 | test-db2.redis-enterprise.example.com:443 | value-31
2026-08-11T20:43:03.634602 | 32 | test-db2.redis-enterprise.example.com:443 | value-32
2026-08-11T20:43:04.719558 | 33 | test-db2.redis-enterprise.example.com:443 | value-33

```
