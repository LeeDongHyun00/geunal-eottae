"""Recompute core/vectors/recovery.json and login_nonce.json with independent code (Python cryptography, hashlib,
a hand-written BIP-39 packer and HChaCha20), so the contract's labels and parameters are checked against something
other than the core's own implementation.

Usage: python3 -I verify_recovery_vectors.py <recovery.json> <login_nonce.json> <upstream korean.txt>
"""
import hashlib
import hmac
import json
import struct
import sys
import unicodedata

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.kdf.argon2 import Argon2id
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat


def hkdf_expand(prk, info, length):
    out, block, counter = b"", b"", 1
    while len(out) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        out += block
        counter += 1
    return out[:length]


def rotl(v, c):
    return ((v << c) & 0xFFFFFFFF) | (v >> (32 - c))


def quarter(s, a, b, c, d):
    s[a] = (s[a] + s[b]) & 0xFFFFFFFF; s[d] = rotl(s[d] ^ s[a], 16)
    s[c] = (s[c] + s[d]) & 0xFFFFFFFF; s[b] = rotl(s[b] ^ s[c], 12)
    s[a] = (s[a] + s[b]) & 0xFFFFFFFF; s[d] = rotl(s[d] ^ s[a], 8)
    s[c] = (s[c] + s[d]) & 0xFFFFFFFF; s[b] = rotl(s[b] ^ s[c], 7)


def hchacha20(key, nonce16):
    s = list(struct.unpack("<4I", b"expand 32-byte k")) + list(struct.unpack("<8I", key)) + list(struct.unpack("<4I", nonce16))
    for _ in range(10):
        quarter(s, 0, 4, 8, 12); quarter(s, 1, 5, 9, 13); quarter(s, 2, 6, 10, 14); quarter(s, 3, 7, 11, 15)
        quarter(s, 0, 5, 10, 15); quarter(s, 1, 6, 11, 12); quarter(s, 2, 7, 8, 13); quarter(s, 3, 4, 9, 14)
    return struct.pack("<8I", *(s[0:4] + s[12:16]))


def xchacha_seal(key, nonce24, aad, plaintext):
    subkey = hchacha20(key, nonce24[:16])
    return nonce24 + ChaCha20Poly1305(subkey).encrypt(b"\0\0\0\0" + nonce24[16:], plaintext, aad)


def words_of(entropy, wordlist):
    checksum = hashlib.sha256(entropy).digest()[0] >> 4
    bits = int.from_bytes(entropy, "big") << 4 | checksum
    return [wordlist[(bits >> (11 * (11 - i))) & 0x7FF] for i in range(12)]


def entropy_of(words, wordlist):
    index = {w: i for i, w in enumerate(wordlist)}
    bits = 0
    for w in words:
        bits = bits << 11 | index[unicodedata.normalize("NFC", w)]
    entropy, checksum = (bits >> 4).to_bytes(16, "big"), bits & 0xF
    return entropy, checksum == hashlib.sha256(entropy).digest()[0] >> 4


def main(recovery_path, login_path, list_path):
    rec = json.load(open(recovery_path, encoding="utf-8"))
    login = json.load(open(login_path, encoding="utf-8"))
    upstream = open(list_path, "rb").read()
    digest = hashlib.sha256(upstream).hexdigest()
    if digest != rec["word_list"]["sha256"]:
        # The core keeps the list in NFC; the vector names the upstream file, which is NFKD.
        nfkd = "".join(unicodedata.normalize("NFKD", w) + "\n" for w in upstream.decode("utf-8").split())
        digest = hashlib.sha256(nfkd.encode("utf-8")).hexdigest()
    assert digest == rec["word_list"]["sha256"], "word list hash"
    wordlist = [unicodedata.normalize("NFC", w) for w in upstream.decode("utf-8").split()]
    a = rec["argon2id"]
    info = {k: v.encode() for k, v in rec["hkdf_info"].items()}
    failures = 0

    def check(label, got, want):
        nonlocal failures
        ok = got == want
        failures += not ok
        print(("ok  " if ok else "FAIL"), label)

    for n, case in enumerate(rec["codes"]):
        e = bytes.fromhex(case["entropy"])
        check(f"code {n} words", words_of(e, wordlist), [unicodedata.normalize("NFC", w) for w in case["words"]])
        root = Argon2id(salt=a["salt"].encode(), length=a["output_len"], iterations=a["iterations"],
                        lanes=a["parallelism"], memory_cost=a["memory_kib"]).derive(e)
        check(f"code {n} root", root.hex(), case["root"])
        wrap_key = hkdf_expand(root, info["wrap_key"], 32)
        check(f"code {n} wrap_key", wrap_key.hex(), case["wrap_key"])
        check(f"code {n} recovery_id", hkdf_expand(root, info["recovery_id"], 16).hex(), case["recovery_id"])
        sk = Ed25519PrivateKey.from_private_bytes(hkdf_expand(root, info["signing_seed"], 32))
        pub = sk.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)
        check(f"code {n} recovery_public_key", pub.hex(), case["recovery_public_key"])
        nonce = case["nonce"].encode()
        for name, label in (("rotate", b"geunal recovery rotate v1"), ("recover", b"geunal recover v1"),
                            ("delete", b"geunal delete v1")):
            check(f"code {n} {name}_signature", sk.sign(label + nonce).hex(), case[f"{name}_signature"])
        sealed = xchacha_seal(wrap_key, bytes.fromhex(case["wrap_nonce"]), rec["amk_aad"].encode(),
                              bytes.fromhex(case["amk"]))
        check(f"code {n} wrapped_amk", sealed.hex(), case["wrapped_amk"])

    for n, case in enumerate(rec["invalid_codes"]):
        words = [unicodedata.normalize("NFC", w) for w in case["words"]]
        if case["error"] == "checksum":
            check(f"invalid {n} checksum fails", entropy_of(words, wordlist)[1], False)
        elif case["error"] in ("unknown_word", "word_count"):
            bad = len(words) != 12 or any(w not in wordlist for w in words)
            check(f"invalid {n} {case['error']}", bad, True)
        else:
            print("skip", n, case["error"])

    for n, case in enumerate(login.get("cases", login if isinstance(login, list) else [])):
        key = bytes.fromhex(case["signature_key"])
        got = hashlib.sha256(b"geunal login v1" + case["nonce"].encode() + key).hexdigest()
        check(f"login {n} provider_nonce", got, case["provider_nonce"])
    print("failures:", failures)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:4]))
