# Redis mTLS Connection Guide and Tester

A web page, packaged as a container, that helps you connect to a TLS-protected Redis database that requires client certificates (mTLS). Choose a client library, enter your connection details, and press **Test Connection** to see whether it works. The page also shows the complete setup instructions, expected output, full source code and troubleshooting help for each library.

| Library | Language | Version used |
|---|---|---|
| Jedis | Java 17 | 7.5.3 |
| Lettuce | Java 17 | 7.6.0.RELEASE |
| StackExchange.Redis | C# (.NET 7) | 3.3.1 |
| redis-py | Python 3.13.7 | latest at image build time |

The container does **not** include Redis. It connects over the network to your existing database.

## What you received

Either or both of these:

| File | Use it when |
|---|---|
| `redis-connection-guide-<version>-amd64.tar.gz` | You want to run the ready-made image. No build, no internet access needed. |
| `redis-connection-guide-source-<version>.zip` | You want to build the image yourself, or run the examples without Docker. |

Check the files against `SHA256SUMS.txt` before use:

```bash
shasum -a 256 -c SHA256SUMS.txt
```

## Option 1: Run with Docker (quickest)

Requires Docker or Podman (replace `docker` with `podman` if you use Podman).

```bash
docker load -i redis-connection-guide-<version>-amd64.tar.gz
docker run --rm -p 8080:8080 redis-connection-guide:<version>
```

Open <http://localhost:8080>.

To build from the source zip instead (needs internet access to Docker Hub, Maven Central, NuGet and PyPI):

```bash
unzip redis-connection-guide-source-<version>.zip -d redis-connection-guide
cd redis-connection-guide
docker build -t redis-connection-guide:<version> .
docker run --rm -p 8080:8080 redis-connection-guide:<version>
```

The image is built for `linux/amd64`. On an Apple Silicon Mac, Docker runs it through emulation, which works but is slower.

## Option 2: Deploy on OpenShift

Log in with `oc login` and select your project (`oc project <name>`).

**A. Load the ready-made image into your registry**

```bash
docker load -i redis-connection-guide-<version>-amd64.tar.gz
docker tag redis-connection-guide:<version> <your-registry>/<project>/redis-connection-guide:<version>
docker push <your-registry>/<project>/redis-connection-guide:<version>
```

Edit the `image:` line in `openshift/redis-connection-guide.yaml` (inside the source zip) to that image.

**B. Or build it on the cluster from the source zip** (the cluster needs internet access to the sources listed above)

```bash
unzip redis-connection-guide-source-<version>.zip -d redis-connection-guide
cd redis-connection-guide
oc new-build --name redis-connection-guide --binary --strategy=docker
oc start-build redis-connection-guide --from-dir=. --follow
```

**Then deploy and find the URL**

```bash
sed "s#<project>#$(oc project -q)#" openshift/redis-connection-guide.yaml | oc apply -f -
oc get route redis-connection-guide
```

Open the HTTPS address shown under `HOST/PORT`. The container runs as the random non-root user OpenShift assigns and listens on port 8080.

## How to use the page

1. Choose a framework from the dropdown. The **Best Practices** link next to it opens the official Redis client guidance.
2. In step 1, enter your connection details:

   | Field | Meaning | Default |
   |---|---|---|
   | `REDIS_HOST` | Host name of your database | none (required) |
   | `REDIS_PORT` | TLS port | `443` |
   | `REDIS_USERNAME` | ACL username; clear it to send none | `default` |
   | `REDIS_PASSWORD` | Database password; leave empty if none (`None` in Python, `null` in Java and .NET) | none |
   | `REDIS_CA_CERT` | CA certificate, PEM text or choose the file | none (required) |
   | `REDIS_CLIENT_CERT` | Client certificate, PEM text or choose the file | none (required) |
   | `REDIS_CLIENT_KEY` | Client private key, PEM text or choose the file | none (required) |

   Lettuce needs the private key in PKCS#8 format (`-----BEGIN PRIVATE KEY-----`).
3. Press **Test Connection** (the **Clear** button next to it empties every field and the output). The result and the full output, including any error message, appear in the box below the button. A successful run looks like:

   ```text
   Connecting to Redis at your-db.example.com:443...
   PING => PONG
   SET demo_key => OK
   GET demo_key => hello-from-java
   ```
4. If it fails, open the **Troubleshooting** section at the bottom of the same page. It lists the common errors for that library, how to check whether a certificate is invalid or expired, and diagnostic commands you can run.

Steps 2 to 6 of each guide also show how to run the same code yourself, outside the container.

## Settings (optional)

Set these as container environment variables.

| Variable | Default | Meaning |
|---|---|---|
| `TEST_TIMEOUT_SECONDS` | `60` | A test is stopped after this long |
| `MAX_CONCURRENT_TESTS` | `4` | Tests that may run at the same time |
| `ALLOWED_HOSTS` | any host | Comma-separated Redis hosts users may test; `*.example.com` wildcards allowed |

Example: `docker run --rm -p 8080:8080 -e ALLOWED_HOSTS="db.example.com" redis-connection-guide:<version>`

## Security

- Passwords and certificates you enter are sent to the container, written to a private temporary folder for that single test, and deleted right afterwards. They are not stored or logged, and the password is masked in the output.
- Use HTTPS. The OpenShift route provides it. Do not enter real credentials over plain HTTP, except on `localhost`.
- Anyone who can open the page can ask the container to connect to a host they name. Do not expose it to the internet. Restrict access with a network policy or an authenticating proxy, and set `ALLOWED_HOSTS`.
- Use test or non-production credentials where you can, and keep `client.key` private.

## Running without Docker

The source zip also contains `index.html` and one folder per library (`Jedis`, `Lettuce`, `StackExchange`, `Python`). Open `index.html` in a browser for the full instructions, build steps and source code. In this mode Test Connection is disabled, but you can run each example from the command line as described in the guide.

## Troubleshooting the container itself

- **The page does not load:** check that the container is running (`docker ps`) and that port 8080 is mapped.
- **Test Connection is greyed out:** you opened `index.html` as a file. Open the container's address instead.
- **Image fails to start on OpenShift with "exec format error":** the image was built for a different CPU type. Use the `amd64` image provided.
- **"The server is busy":** other tests are running. Wait a few seconds or raise `MAX_CONCURRENT_TESTS`.
- **Test times out:** the container cannot reach the database. Check the host, port, firewall and any egress network policy that applies to the pod.
