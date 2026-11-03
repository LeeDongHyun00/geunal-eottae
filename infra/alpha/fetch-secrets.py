#!/usr/bin/env python3
"""Fetches the alpha server's externally issued secrets from NCP Secret Manager into root-only env files.

The provider keys and storage keys are entered once in the Secret Manager console (infra/alpha/README.md), so the
operator never edits files on the server and every read shows up in Cloud Activity Tracer (docs/parts/infra.md §10).
bootstrap.sh runs this on every deploy when /etc/geunal/secret-manager.env exists. The secret's active value is a
JSON object of GEUNAL_* names; GEUNAL_DUMP_* go to dump.env for db-dump.sh and the rest to api.secrets.env, which
compose.yml loads after api.env so a key moved to Secret Manager wins over a line left there by hand.

Values are never printed: on success only the names are, and on failure only the HTTP status and NCP's error code.
"""

import base64
import hashlib
import hmac
import json
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.request

ETC = os.environ.get("GEUNAL_SECRETS_DIR", "/etc/geunal")
ENDPOINT = os.environ.get("GEUNAL_SECRET_MANAGER_URL", "https://secretmanager.apigw.ntruss.com")
# Only the app's own names: a value cannot reach the JVM or Spring through JAVA_TOOL_OPTIONS or SPRING_* this way.
NAME = re.compile(r"^GEUNAL_[A-Z0-9_]+$")
DUMP_PREFIX = "GEUNAL_DUMP_"
# The first line of every file this writes, so a later run can tell its own dump.env from one written by hand.
MARKER = "# Written by fetch-secrets.py from Secret Manager; the next deploy replaces this file.\n"


def fail(message):
    print(f"fetch-secrets: {message}", file=sys.stderr)
    sys.exit(1)


def read_settings(path):
    settings = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    settings[key.strip()] = value.strip().strip("'\"")
    except FileNotFoundError:
        fail(f"{path} is missing")
    missing = [k for k in ("NCP_ACCESS_KEY", "NCP_SECRET_KEY", "GEUNAL_SECRET_ID") if not settings.get(k)]
    if missing:
        fail(f"{path} lacks {', '.join(missing)}")
    return settings


def fetch(settings):
    secret_id = settings["GEUNAL_SECRET_ID"]
    if not re.fullmatch(r"[A-Za-z0-9_-]+", secret_id):
        fail("GEUNAL_SECRET_ID has characters a secret ID never has")
    uri = f"/api/v1/secrets/{secret_id}/values"
    timestamp = str(int(time.time() * 1000))
    # NCP API Gateway signature v2: HMAC-SHA256 over "METHOD URI\nTIMESTAMP\nACCESS_KEY", base64.
    message = f"GET {uri}\n{timestamp}\n{settings['NCP_ACCESS_KEY']}".encode()
    signature = base64.b64encode(hmac.new(settings["NCP_SECRET_KEY"].encode(), message, hashlib.sha256).digest())
    request = urllib.request.Request(
        ENDPOINT + uri,
        headers={
            "x-ncp-apigw-timestamp": timestamp,
            "x-ncp-iam-access-key": settings["NCP_ACCESS_KEY"],
            "x-ncp-apigw-signature-v2": signature.decode(),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            body = json.load(response)
    except urllib.error.HTTPError as error:
        code = "?"
        try:
            detail = json.load(error)
            code = detail.get("error", {}).get("errorCode") or detail.get("code") or "?"
        except (ValueError, AttributeError):
            pass
        fail(f"Secret Manager answered {error.code} ({code})")
    except (urllib.error.URLError, TimeoutError) as error:
        fail(f"Secret Manager could not be reached: {error.reason if hasattr(error, 'reason') else error}")
    try:
        active = body["data"]["decryptedSecretChain"]["active"]
        values = json.loads(active)
    except (KeyError, TypeError, ValueError):
        fail("the answer has no active value that is a JSON object")
    if not isinstance(values, dict) or not values:
        fail("the active value is not a JSON object with at least one key")
    return values


def env_line(name, value):
    if not NAME.match(name):
        fail(f"{name} is not a GEUNAL_* name; nothing was written")
    if isinstance(value, bool) or not isinstance(value, (str, int)):
        fail(f"{name} is not a string; nothing was written")
    value = str(value)
    # Single quotes keep compose and bash from expanding $ in a key. A PEM's line breaks become \n, which the API
    # reads back (GEUNAL_AUTH_APPLE_PRIVATE_KEY); a quote inside a value has no safe form here.
    value = value.replace("\r\n", "\n").replace("\n", "\\n")
    if "'" in value:
        fail(f"{name} contains a single quote; nothing was written")
    return f"{name}='{value}'\n"


def write(path, lines):
    # The file appears whole or not at all, root-only from the first byte.
    directory = os.path.dirname(path)
    fd, temporary = tempfile.mkstemp(dir=directory, prefix=".secrets-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(MARKER)
            f.writelines(lines)
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    except BaseException:
        os.unlink(temporary)
        raise


def written_here(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.readline() == MARKER
    except FileNotFoundError:
        return False


def main():
    os.umask(0o077)
    values = fetch(read_settings(os.path.join(ETC, "secret-manager.env")))
    # Every line is checked before any file changes, so a bad key leaves the last good files in place.
    lines = {name: env_line(name, value) for name, value in sorted(values.items())}
    api = [line for name, line in lines.items() if not name.startswith(DUMP_PREFIX)]
    dump = [line for name, line in lines.items() if name.startswith(DUMP_PREFIX)]
    write(os.path.join(ETC, "api.secrets.env"), api)
    dump_path = os.path.join(ETC, "dump.env")
    if dump:
        write(dump_path, dump)
    elif written_here(dump_path):
        # The dump keys were removed from Secret Manager: the dump stays on the server again rather than going on
        # with the old key and recipient. A dump.env from before Secret Manager, without the marker, stays.
        os.remove(dump_path)
        print("fetch-secrets: removed dump.env, since Secret Manager has no GEUNAL_DUMP_* keys", file=sys.stderr)
    names = ", ".join(sorted(values)) or "nothing"
    print(f"fetch-secrets: wrote {names}", file=sys.stderr)


if __name__ == "__main__":
    main()
