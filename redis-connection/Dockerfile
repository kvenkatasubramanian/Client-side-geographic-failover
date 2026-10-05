# syntax=docker/dockerfile:1
#
# Redis mTLS connection guide + test runner (Jedis, Lettuce, StackExchange.Redis, redis-py).
#
# Versions:
#   Java 17            -> maven.compiler.release in Jedis/pom.xml and Lettuce/pom.xml
#   Jedis 7.5.3, Lettuce 7.6.0.RELEASE -> the pom.xml files
#   .NET 7 (net7.0), StackExchange.Redis 3.3.1 -> StackExchange/StackExchangeRedis.csproj
#   Python 3.13.7, redis-py (latest) -> this file
# The Redis database is NOT part of this image; it is reached over the network.
#
# The compile stages run on the machine doing the build ($BUILDPLATFORM), so they are never
# CPU-emulated (the .NET build tools crash under emulation). Their output (Java bytecode, .NET
# DLLs, jar files) is platform independent, so the final stage can target another CPU, e.g.
#   docker build --platform linux/amd64 -t redis-connection-guide .

# ---------------------------------------------------------------- build: Java
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-17 AS java-build
WORKDIR /build
COPY Jedis/ Jedis/
COPY Lettuce/ Lettuce/
# Dependencies go to /opt/m2, which any user can read (OpenShift runs a random user id).
RUN for d in Jedis Lettuce; do \
      (cd "$d" && mvn -q -B -Dmaven.repo.local=/opt/m2 compile dependency:build-classpath \
         -Dmdep.outputFile=classpath.txt) || exit 1; \
    done

# ---------------------------------------------------------------- build: .NET
FROM --platform=$BUILDPLATFORM mcr.microsoft.com/dotnet/sdk:7.0-bookworm-slim AS dotnet-build
ENV DOTNET_NOLOGO=1 DOTNET_CLI_TELEMETRY_OPTOUT=1
WORKDIR /build
COPY StackExchange/ StackExchange/
RUN cd StackExchange && dotnet publish -c Release -o /out

# ---------------------------------------------------------------- runtimes copied into the final image
FROM eclipse-temurin:17-jre AS jre
FROM python:3.13.7-slim-bookworm AS python

# ---------------------------------------------------------------- final image
FROM mcr.microsoft.com/dotnet/runtime:7.0-bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive \
    DOTNET_NOLOGO=1 \
    DOTNET_CLI_TELEMETRY_OPTOUT=1 \
    JAVA_HOME=/opt/java/openjdk
ENV PATH=/opt/java/openjdk/bin:$PATH

COPY --from=jre /opt/java/openjdk /opt/java/openjdk
COPY --from=python /usr/local /usr/local

# OpenSSL: the Jedis and .NET examples call it at runtime. The other libraries are needed by Python.
RUN apt-get update \
 && apt-get install -y --no-install-recommends openssl ca-certificates libffi8 libbz2-1.0 liblzma5 libsqlite3-0 libexpat1 \
 && rm -rf /var/lib/apt/lists/* \
 && ldconfig \
 && python3 --version

# The Python example needs the redis client library (README: `pip install redis`).
RUN python3 -m venv /opt/venv \
 && /opt/venv/bin/pip install --no-cache-dir redis \
 && /opt/venv/bin/pip list 2>/dev/null | grep -i '^redis '

WORKDIR /app
COPY Python/ Python/
COPY server/ server/
COPY index.html index.html
COPY --from=java-build /opt/m2 /opt/m2
COPY --from=java-build /build/Jedis/target/classes Jedis/target/classes
COPY --from=java-build /build/Jedis/classpath.txt Jedis/classpath.txt
COPY --from=java-build /build/Lettuce/target/classes Lettuce/target/classes
COPY --from=java-build /build/Lettuce/classpath.txt Lettuce/classpath.txt
COPY --from=dotnet-build /out StackExchange/out

ENV PORT=8080 APP_DIR=/app
EXPOSE 8080
USER 1001

CMD ["python3", "/app/server/app.py"]
