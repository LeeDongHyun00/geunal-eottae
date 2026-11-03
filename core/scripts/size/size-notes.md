# Size probe (paused 2026-10-26)

- Local branch `size-probe` = claude/core-m1-restore (#47, 3a92798) + local claude/core-m1-openssl (#32 + main merge).
- arm64-v8a stripped: 8.483 MB (8894704 bytes); budget 8 MB → need about -0.5 MB plus headroom for 3e.
- Build one ABI: `CARGO_INCREMENTAL=0 core/scripts/size/build-arm64.sh` (from core/); per-crate sizes: `python3 -I core/scripts/size/crates.py core/target/size/probe/nm.txt 45 40`.
- Biggest: [C t] 1.43 MB (SQLite+OpenSSL+ring asm), core 795 KB, geunal_ffi 412 KB, rustls 352 KB, mls_rs 319 KB,
  geunal_sync 265 KB. Single symbols: ring ecp_nistz256_precomputed 148 KB, Envelope serde visit_map (ciborium) 76.5 KB,
  OpenSSL nid_objs 58.6 KB, Archive serde visit_map 53 KB.
- Ideas to measure: serde derive bloat on CBOR enums (Envelope, Archive, Outgoing, CommitIntent), rustls without tls12/logging,
  ring P-256 table (rustls needs P-256 for ECDSA certs?), duplicate RustCrypto generations (mls-rs-crypto-rustcrypto old vs
  geunal-crypto new: digest/chacha20/sec1/rand_core), p384 in mls provider, opt-level "s" with an Argon2 timing check.
