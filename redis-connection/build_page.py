#!/usr/bin/env python3
"""Generate index.html: a single, self-contained page with a framework dropdown.

Source files are read from the Jedis/, Lettuce/, StackExchange/ and Python/
folders and embedded verbatim, so re-run this script after changing any code:

    python3 build_page.py
"""
import json
from pathlib import Path

import guide_content as gc

ROOT = Path(__file__).parent

PASSWORD_FROM_OC = (
    "REDIS_PASSWORD=\"$(oc -n redis-enterprise get secret redb-demo-db \\\n"
    "  -o jsonpath='{.data.password}' | base64 --decode)\""
)


# "@@CONN@@" is replaced in the browser by REDIS_HOST / REDIS_PORT / REDIS_USERNAME lines that
# follow what the user typed in the connection form (page_template.html, connLines()).
def run_cmd(tool_cmd, via_oc=True):
    pw = PASSWORD_FROM_OC if via_oc else 'REDIS_PASSWORD="your-password"'
    return "@@CONN@@" + pw + " \\\n" + tool_cmd


def overrides_cmd(tool_cmd):
    return (
        "@@CONN@@"
        'REDIS_CA_CERT="/path/to/ca.crt" \\\n'
        'REDIS_CLIENT_CERT="/path/to/client.crt" \\\n'
        'REDIS_CLIENT_KEY="/path/to/client.key" \\\n'
        'REDIS_PASSWORD="your-password" \\\n' + tool_cmd
    )


def tree(folder, files):
    lines = [f"{folder}/"]
    for i, f in enumerate(files):
        lines.append(("├── " if i < len(files) - 1 else "└── ") + f)
    return "\n".join(lines)


CERTS_NOTE = (
    "Certificates are optional. If your database requires TLS client certificates (mTLS), create a "
    "<code>certs/</code> folder next to the project and copy your files into it: the CA certificate "
    "(<code>ca.crt</code>), the client certificate (<code>client.crt</code>) and the client private key "
    "(<code>client.key</code>), then pass their paths in <code>REDIS_CA_CERT</code>, "
    "<code>REDIS_CLIENT_CERT</code> and <code>REDIS_CLIENT_KEY</code>. Without any certificate the "
    "example connects without TLS."
)

COMMON_INPUTS = [
    ["REDIS_HOST", "Yes", "(none)", "Hostname of the Redis database."],
    ["REDIS_PORT", "No", "443", "Port of the database."],
    ["REDIS_USERNAME", "No", "(none)", "ACL username, e.g. <code>default</code>. (The .NET example uses <code>default</code> if you leave it out.)"],
    ["REDIS_PASSWORD", "No", "(none)", "Database password. Can be read from the OpenShift secret."],
    ["REDIS_CA_CERT", "No", "(none)", "Path to the CA certificate. Supplying any certificate turns TLS on."],
    ["REDIS_CLIENT_CERT", "No", "(none)", "Path to the client certificate. Must be set together with <code>REDIS_CLIENT_KEY</code>."],
    ["REDIS_CLIENT_KEY", "No", "(none)", "Path to the client private key. Must be set together with <code>REDIS_CLIENT_CERT</code>."],
    ["REDIS_TLS / REDIS_USE_SSL", "No", "(auto)", "<code>REDIS_TLS=true|false</code> forces TLS on or off in the Java (Jedis) and .NET examples; <code>REDIS_USE_SSL=1</code> turns it on in the Python example. Lettuce enables TLS only when a certificate is supplied."],
]

FRAMEWORKS = {
    "Jedis": {
        "label": "Jedis (Java)",
        "summary": "Java client using Jedis with optional TLS and mutual TLS (a custom CA, a client certificate and a client key).",
        "folder": "Jedis",
        "prereqs": [
            ("Java 17 or later", "java -version"),
            ("Apache Maven", "mvn -version"),
            ("OpenSSL in PATH", "openssl version"),
            ("OpenShift CLI (oc), logged in, only if the password is read from a secret", "oc whoami"),
        ],
        "layout": tree("Jedis", ["pom.xml", "certs/  (ca.crt, client.crt, client.key)", "src/main/java/Jedis_conn.java"]),
        "setup_cmd": "cd Jedis",
        "build_title": "Compile",
        "build_cmd": "mvn clean compile",
        "build_ok": "BUILD SUCCESS",
        "run_secret": run_cmd("mvn exec:java"),
        "run_direct": run_cmd("mvn exec:java", via_oc=False),
        "run_override": overrides_cmd("mvn exec:java"),
        "output": (
            "Connecting to Redis at demo-db.redis-enterprise.example.com:443...\n"
            "PING => PONG\n"
            "SET demo_key => OK\n"
            "GET demo_key => hello-from-java"
        ),
        "notes": ["Run all commands from the <code>Jedis</code> directory so the relative certificate paths resolve."],
        "files": [
            ("pom.xml", "Jedis/pom.xml", "xml"),
            ("Jedis_conn.java", "Jedis/src/main/java/Jedis_conn.java", "java"),
        ],
    },
    "Lettuce": {
        "label": "Lettuce (Java)",
        "summary": "Java client using Lettuce with optional TLS client certificates and Redis password authentication.",
        "folder": "Lettuce",
        "prereqs": [
            ("Java 17 or later", "java -version"),
            ("Apache Maven", "mvn -version"),
            ("OpenShift CLI (oc), logged in, only if the password is read from a secret", "oc whoami"),
            ("A PKCS#8 client private key in certs/", None),
        ],
        "layout": tree("Lettuce", ["pom.xml", "certs/  (ca.crt, client.crt, client.key)", "src/main/java/Lettuce_conn.java"]),
        "setup_cmd": "cd Lettuce",
        "build_title": "Compile",
        "build_cmd": "mvn clean compile",
        "build_ok": "BUILD SUCCESS",
        "run_secret": run_cmd("mvn exec:java"),
        "run_direct": run_cmd("mvn exec:java", via_oc=False),
        "run_override": overrides_cmd("mvn exec:java"),
        "output": (
            "Pinging Redis at demo-db.redis-enterprise.example.com:443...\n"
            "PING => PONG\n"
            "SET => OK\n"
            "GET demo_key => hello-from-lettuce"
        ),
        "notes": [
            "Lettuce needs the client private key in <strong>PKCS#8</strong> format.",
            "To compile and run in one step: <code>mvn clean compile exec:java</code>.",
        ],
        "files": [
            ("pom.xml", "Lettuce/pom.xml", "xml"),
            ("Lettuce_conn.java", "Lettuce/src/main/java/Lettuce_conn.java", "java"),
        ],
    },
    "StackExchange": {
        "label": "StackExchange.Redis (C# / .NET)",
        "summary": ".NET console app using StackExchange.Redis with optional TLS and mutual TLS (a custom CA, a client certificate and a client key).",
        "folder": "StackExchange",
        "prereqs": [
            (".NET SDK 7.0 or later", "dotnet --version"),
            ("OpenSSL in PATH (used at runtime to build a temporary PKCS#12 file)", "openssl version"),
            ("OpenShift CLI (oc), logged in, only if the password is read from a secret", "oc whoami"),
        ],
        "layout": tree("StackExchange", ["StackExchangeRedis.csproj", "Program.cs", "certs/  (ca.crt, client.crt, client.key)"]),
        "setup_cmd": "cd StackExchange",
        "build_title": "Restore and build",
        "build_cmd": "dotnet restore\ndotnet build",
        "build_ok": "Build succeeded.",
        "run_secret": run_cmd("dotnet run"),
        "run_direct": run_cmd("dotnet run", via_oc=False),
        "run_override": overrides_cmd("dotnet run"),
        "output": (
            "Connecting to Redis at demo-db.redis-enterprise.example.com:443...\n"
            "PING => PONG (12.34 ms)\n"
            "SET demo_key => OK\n"
            "GET demo_key => hello-from-csharp"
        ),
        "notes": [
            "The app calls OpenSSL to convert the PEM certificate and key into a temporary PKCS#12 file, which is deleted after it is loaded.",
            "The PING latency (<code>12.34 ms</code>) will differ on your network.",
        ],
        "files": [
            ("StackExchangeRedis.csproj", "StackExchange/StackExchangeRedis.csproj", "xml"),
            ("Program.cs", "StackExchange/Program.cs", "csharp"),
        ],
    },
    "Python": {
        "label": "Python (redis-py)",
        "summary": "Python script using redis-py with optional TLS and mutual TLS (a custom CA, a client certificate and a client key).",
        "folder": "Python",
        "prereqs": [
            ("Python 3.9 or later (the container image uses Python 3.13.7)", "python3 --version"),
            ("pip", "python3 -m pip --version"),
            ("OpenShift CLI (oc), logged in, only if the password is read from a secret", "oc whoami"),
        ],
        "layout": tree("Python", ["python_conn.py", "certs/  (ca.crt, client.crt, client.key)"]),
        "setup_cmd": "cd Python\npython3 -m venv .venv\nsource .venv/bin/activate",
        "build_title": "Install the Redis client",
        "build_cmd": "python -m pip install --upgrade pip\npython -m pip install redis",
        "build_ok": None,
        "run_secret": run_cmd("python python_conn.py"),
        "run_direct": run_cmd("python python_conn.py", via_oc=False),
        "run_override": overrides_cmd("python python_conn.py"),
        "output": (
            "Pinging Redis at demo-db.redis-enterprise.example.com:443...\n"
            "True\n"
            "SET demo_key=hello-from-python\n"
            "GET demo_key => hello-from-python"
        ),
        "notes": [
            "Default certificate paths are resolved relative to <code>python_conn.py</code>.",
            "When you are done, leave the virtual environment with <code>deactivate</code>.",
        ],
        "files": [("python_conn.py", "Python/python_conn.py", "python")],
    },
}

NULL_WORD = {"Jedis": "null", "Lettuce": "null", "StackExchange": "null", "Python": "None"}

data = {}
for key, fw in FRAMEWORKS.items():
    fw = dict(fw)
    fw["null_word"] = NULL_WORD[key]
    fw["files"] = [
        {"name": n, "path": p, "lang": l, "code": (ROOT / p).read_text()}
        for n, p, l in fw["files"]
    ]
    data[key] = fw

payload = json.dumps(
    {
        "frameworks": data,
        "inputs": COMMON_INPUTS,
        "certs_note": CERTS_NOTE,
        "troubleshooting": {
            "checklist": gc.CHECKLIST,
            "general_errors": gc.GENERAL_ERRORS,
            "diagnostics": gc.DIAGNOSTICS,
            "framework_errors": gc.FW_ERRORS,
        },
    },
    ensure_ascii=False,
)
payload = payload.replace("</", "<\\/")

html = (ROOT / "page_template.html").read_text().replace("/*__DATA__*/null", payload)
(ROOT / "index.html").write_text(html)
print(f"Wrote index.html ({len(html) // 1024} KB)")
