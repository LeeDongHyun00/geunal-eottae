"""Open core/vectors/backups.json with independent code: the backup key by HKDF over the AMK, the STREAM chunks by
hand (XChaCha20-Poly1305 nonce = prefix (19) ‖ counter BE32 ‖ last flag), and a small CBOR reader, then compare with
the JSON form of the archive.

Usage: python3 -I verify_backup_vectors.py <backups.json>
"""
import json
import struct
import sys

sys.path.insert(0, sys.argv[0].rsplit("/", 1)[0])
from verify_recovery_vectors import hchacha20, hkdf_expand  # noqa: E402

from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305

CHUNK = 64 * 1024
TAG = 16


def xchacha_open(key, nonce24, aad, sealed):
    return ChaCha20Poly1305(hchacha20(key, nonce24[:16])).decrypt(b"\0\0\0\0" + nonce24[16:], sealed, aad)


def stream_open(key, aad, obj):
    prefix, body = obj[:19], obj[19:]
    out, counter, pos = b"", 0, 0
    while True:
        last = len(body) - pos <= CHUNK + TAG
        piece = body[pos:] if last else body[pos:pos + CHUNK + TAG]
        nonce = prefix + struct.pack(">I", counter) + (b"\x01" if last else b"\x00")
        out += xchacha_open(key, nonce, aad, piece)
        if last:
            return out
        pos += CHUNK + TAG
        counter += 1


def cbor(data, i=0):
    head = data[i]; major, info = head >> 5, head & 31; i += 1
    if info < 24:
        arg = info
    elif info in (24, 25, 26, 27):
        n = 1 << (info - 24); arg = int.from_bytes(data[i:i + n], "big"); i += n
    else:
        raise ValueError("indefinite or reserved length")
    if major == 0:
        return arg, i
    if major == 1:
        return -1 - arg, i
    if major == 2:
        return data[i:i + arg], i + arg
    if major == 3:
        return data[i:i + arg].decode("utf-8"), i + arg
    if major == 4:
        items = []
        for _ in range(arg):
            v, i = cbor(data, i); items.append(v)
        return items, i
    if major == 5:
        m = {}
        for _ in range(arg):
            k, i = cbor(data, i); v, i = cbor(data, i); m[k] = v
        return m, i
    if major == 7:
        return {20: False, 21: True, 22: None}[info], i
    raise ValueError("unsupported CBOR major type %d" % major)


def main(path):
    d = json.load(open(path, encoding="utf-8"))
    backup_key = hkdf_expand(bytes.fromhex(d["amk"]), b"geunal backup key v1", 32)
    failures = 0
    for n, case in enumerate(d["archives"]):
        obj = bytes.fromhex(case["object"])
        ok_prefix = obj[:19].hex() == case["nonce_prefix"]
        plain = stream_open(backup_key, d["aad"].encode(), obj)
        ok_cbor = plain.hex() == case["cbor"]
        decoded, end = cbor(plain)
        ok_json = end == len(plain) and decoded == case["archive"]
        for label, ok in (("nonce prefix", ok_prefix), ("plaintext is the CBOR", ok_cbor), ("CBOR is the JSON", ok_json)):
            failures += not ok
            print(("ok  " if ok else "FAIL"), f"archive {n} v{case['archive_version']}:", label)
        try:
            stream_open(backup_key, b"geunal other v1", obj)
            failures += 1; print("FAIL another AAD opened it")
        except Exception:
            print("ok   another AAD does not open it")
    print("failures:", failures)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
