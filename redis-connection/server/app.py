#!/usr/bin/env python3
"""Small web server for the Redis mTLS connection guide.

Serves index.html and runs the Jedis, Lettuce, StackExchange.Redis or Python
example against the Redis database the user describes in the browser.

    GET  /            the guide page
    GET  /api/health  {"ok": true, "frameworks": [...]}
    POST /api/test    run one example and return its output

Only the Python standard library is used. Settings (environment variables):

    PORT                   listen port (default 8080)
    TEST_TIMEOUT_SECONDS   kill a test after this long (default 60)
    MAX_CONCURRENT_TESTS   parallel tests allowed (default 4)
    ALLOWED_HOSTS          optional comma-separated list of Redis hosts users
                           may test, e.g. "db.example.com,*.corp.example.com".
                           Empty = any host.
    APP_DIR                project root (default: parent of this folder)
    PYTHON_BIN             interpreter that has `redis` installed
"""
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

APP_DIR = Path(os.environ.get("APP_DIR") or Path(__file__).resolve().parent.parent)
PORT = int(os.environ.get("PORT", "8080"))
TIMEOUT = int(os.environ.get("TEST_TIMEOUT_SECONDS", "60"))
MAX_CONCURRENT = int(os.environ.get("MAX_CONCURRENT_TESTS", "4"))
ALLOWED_HOSTS = [h.strip().lower() for h in os.environ.get("ALLOWED_HOSTS", "").split(",") if h.strip()]
PYTHON_BIN = os.environ.get("PYTHON_BIN") or (
    "/opt/venv/bin/python" if Path("/opt/venv/bin/python").exists() else sys.executable
)

MAX_BODY = 256 * 1024
MAX_PEM = 64 * 1024
MAX_OUTPUT = 64 * 1024
HOST_RE = re.compile(r"^[A-Za-z0-9._:\-]{1,253}$")

FRAMEWORKS = ("Jedis", "Lettuce", "StackExchange", "Python")
DISPLAY_COMMAND = {
    "Jedis": "java -cp <compiled classes + dependencies> Jedis_conn",
    "Lettuce": "java -cp <compiled classes + dependencies> Lettuce_conn",
    "StackExchange": "dotnet StackExchangeRedis.dll",
    "Python": "python python_conn.py",
}
slots = threading.BoundedSemaphore(MAX_CONCURRENT)


class UserError(Exception):
    """Bad input or an example that is not available; shown to the user."""


# ------------------------------------------------------------------ runners
def runner(framework):
    """Return (working_dir, argv) for a framework, or raise UserError."""
    if framework in ("Jedis", "Lettuce"):
        cwd = APP_DIR / framework
        cp_file = cwd / "classpath.txt"
        if not (cwd / "target" / "classes").is_dir() or not cp_file.is_file():
            raise UserError(f"{framework} is not built in this environment (run `mvn compile` first).")
        classpath = os.pathsep.join(["target/classes", cp_file.read_text().strip()])
        return cwd, ["java", "-cp", classpath, f"{framework}_conn"]
    if framework == "StackExchange":
        cwd = APP_DIR / "StackExchange"
        dll = cwd / "out" / "StackExchangeRedis.dll"
        if not dll.is_file():
            raise UserError("StackExchange is not built in this environment (run `dotnet publish -o out` first).")
        return cwd, ["dotnet", str(dll)]
    cwd = APP_DIR / "Python"
    return cwd, [PYTHON_BIN, "python_conn.py"]


# ------------------------------------------------------------------ validation
def clean_pem(value, label):
    """Certificates are optional: blank -> None. Anything given must be PEM."""
    if value is None or (isinstance(value, str) and not value.strip()):
        return None
    if not isinstance(value, str) or "-----BEGIN" not in value:
        raise UserError(f"{label} must be in PEM format (text starting with -----BEGIN).")
    if len(value) > MAX_PEM:
        raise UserError(f"{label} is too large.")
    return value.replace("\r\n", "\n").strip() + "\n"


def host_allowed(host):
    if not ALLOWED_HOSTS:
        return True
    host = host.lower()
    for pattern in ALLOWED_HOSTS:
        if pattern.startswith("*.") and host.endswith(pattern[1:]):
            return True
        if host == pattern:
            return True
    return False


def validate(body):
    framework = body.get("framework")
    if framework not in FRAMEWORKS:
        raise UserError("Choose a framework.")

    host = str(body.get("host") or "").strip()
    if not host or host.startswith("-") or not HOST_RE.match(host):
        raise UserError("REDIS_HOST is required and must be a valid host name or IP address.")
    if not host_allowed(host):
        raise UserError("This host is not allowed by the server's ALLOWED_HOSTS setting.")

    try:
        port = int(str(body.get("port") or "443").strip())
    except ValueError:
        raise UserError("REDIS_PORT must be a number.")
    if not 1 <= port <= 65535:
        raise UserError("REDIS_PORT must be between 1 and 65535.")

    username = str(body.get("username") or "").strip()
    password = body.get("password")
    password = password if isinstance(password, str) else ""
    for label, value in (("REDIS_USERNAME", username), ("REDIS_PASSWORD", password)):
        if len(value) > 1024 or "\x00" in value:
            raise UserError(f"{label} is not valid.")

    ca = clean_pem(body.get("ca_cert"), "REDIS_CA_CERT")
    cert = clean_pem(body.get("client_cert"), "REDIS_CLIENT_CERT")
    key = clean_pem(body.get("client_key"), "REDIS_CLIENT_KEY")
    if (cert is None) != (key is None):
        raise UserError("REDIS_CLIENT_CERT and REDIS_CLIENT_KEY must be provided together.")

    return {
        "framework": framework,
        "host": host,
        "port": str(port),
        "username": username,
        "password": password,
        "ca": ca,
        "cert": cert,
        "key": key,
    }


# ------------------------------------------------------------------ execution
def run_test(req):
    cwd, argv = runner(req["framework"])
    workdir = tempfile.mkdtemp(prefix="redis-test-")
    try:
        os.chmod(workdir, 0o700)
        # Only the certificates the user supplied are written and passed on. With none, the
        # examples connect without TLS.
        cert_env = {}
        for env_name, filename, content in (
            ("REDIS_CA_CERT", "ca.crt", req["ca"]),
            ("REDIS_CLIENT_CERT", "client.crt", req["cert"]),
            ("REDIS_CLIENT_KEY", "client.key", req["key"]),
        ):
            if content is None:
                continue
            path = os.path.join(workdir, filename)
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w") as handle:
                handle.write(content)
            cert_env[env_name] = path

        # A clean environment: the examples must not see the server's own variables.
        env = {
            "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
            "HOME": workdir,
            "TMPDIR": workdir,
            "LANG": "C.UTF-8",
            "PYTHONUNBUFFERED": "1",
            "PYTHONDONTWRITEBYTECODE": "1",
            "DOTNET_CLI_HOME": workdir,
            "DOTNET_NOLOGO": "1",
            "DOTNET_CLI_TELEMETRY_OPTOUT": "1",
            "REDIS_HOST": req["host"],
            "REDIS_PORT": req["port"],
            **cert_env,
        }
        for passthrough in ("JAVA_HOME", "DOTNET_ROOT"):
            if os.environ.get(passthrough):
                env[passthrough] = os.environ[passthrough]
        # An empty value must mean "not set" (None / null in the examples).
        if req["username"]:
            env["REDIS_USERNAME"] = req["username"]
        if req["password"]:
            env["REDIS_PASSWORD"] = req["password"]

        started = time.monotonic()
        timed_out = False
        try:
            proc = subprocess.Popen(
                argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True,
            )
        except FileNotFoundError as exc:
            raise UserError(f"Could not start {argv[0]} on the server: {exc}")
        try:
            raw, _ = proc.communicate(timeout=TIMEOUT)
        except subprocess.TimeoutExpired:
            timed_out = True
            os.killpg(proc.pid, signal.SIGKILL)
            raw, _ = proc.communicate()
        duration = time.monotonic() - started

        text = raw.decode("utf-8", errors="replace")
        if len(text) > MAX_OUTPUT:
            text = text[:MAX_OUTPUT] + "\n... output truncated ..."
        if req["password"]:
            text = text.replace(req["password"], "********")
        text = text.replace(workdir, "<uploaded-certs>")
        if timed_out:
            text += f"\n[Timed out after {TIMEOUT} seconds - the database did not answer. Check host, port and network.]"
        return {
            "ok": proc.returncode == 0 and not timed_out,
            "exit_code": proc.returncode,
            "timed_out": timed_out,
            "duration_s": round(duration, 2),
            "command": DISPLAY_COMMAND[req["framework"]],
            "output": text,
        }
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


# ------------------------------------------------------------------ HTTP
class Handler(BaseHTTPRequestHandler):
    server_version = "RedisGuide"

    def log_message(self, fmt, *args):  # request lines only; never bodies
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _json(self, status, payload):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path in ("/", "/index.html"):
            page = APP_DIR / "index.html"
            if not page.is_file():
                return self._json(404, {"error": "index.html not found"})
            data = page.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(data)
        elif path == "/api/health":
            self._json(200, {"ok": True, "frameworks": list(FRAMEWORKS), "timeout_s": TIMEOUT})
        else:
            self._json(404, {"error": "Not found"})

    def do_POST(self):
        if self.path.split("?", 1)[0] != "/api/test":
            return self._json(404, {"error": "Not found"})
        try:
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > MAX_BODY:
                raise UserError("Request is empty or too large.")
            try:
                body = json.loads(self.rfile.read(length))
            except ValueError:
                raise UserError("Request is not valid JSON.")
            if not isinstance(body, dict):
                raise UserError("Request is not valid.")
            req = validate(body)
            if not slots.acquire(blocking=False):
                return self._json(429, {"error": "The server is busy running other tests. Try again in a few seconds."})
            try:
                result = run_test(req)
            finally:
                slots.release()
            self._json(200, result)
        except UserError as exc:
            self._json(400, {"error": str(exc)})
        except Exception as exc:  # never leak a stack trace to the browser
            sys.stderr.write(f"internal error: {exc!r}\n")
            self._json(500, {"error": "Internal server error."})


if __name__ == "__main__":
    print(f"Serving {APP_DIR} on port {PORT} (timeout {TIMEOUT}s, {MAX_CONCURRENT} concurrent tests)", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
