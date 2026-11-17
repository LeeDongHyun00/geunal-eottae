// A rough load probe for the gateway: serves a JWKS on :8099 and opens N device sockets, each joining its own device
// topic and sending a heartbeat every 30 seconds. Used on 2026-11-04 to size the alpha container (idle and per-device
// memory, .claude/handoff/realtime.md); a starting point for the M3 load test, not a CI tool.
//
// Run the gateway image with JWKS_URL=http://<this host>:8099/jwks and TRUSTED_PROXIES set to the range this probe
// connects from (a Docker network's, say): each device sends its own X-Forwarded-For, so the per-address connection
// limit (30 a minute) counts devices one by one. The JWKS key is new on every run, so restart the gateway between runs.
//
// Usage: node load-probe.mjs <gateway ws base> <count> <seconds>
import http from "node:http";
import crypto from "node:crypto";

const [base = "ws://127.0.0.1:4000", count = "100", seconds = "60"] = process.argv.slice(2);
const { publicKey, privateKey } = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" });
const jwk = { ...publicKey.export({ format: "jwk" }), kid: "load", alg: "ES256", use: "sig" };
http.createServer((_, res) => { res.setHeader("content-type", "application/json"); res.end(JSON.stringify({ keys: [jwk] })); })
  .listen(8099, "0.0.0.0");

const b64 = (b) => Buffer.from(b).toString("base64url");
function token(did) {
  const h = b64(JSON.stringify({ alg: "ES256", kid: "load" }));
  const p = b64(JSON.stringify({ sub: crypto.randomUUID(), did, exp: Math.floor(Date.now() / 1000) + 3600 }));
  const sig = crypto.sign("sha256", Buffer.from(`${h}.${p}`), { key: privateKey, dsaEncoding: "ieee-p1363" });
  return `${h}.${p}.${b64(sig)}`;
}

// A minimal WebSocket client over http's upgrade, so each device can carry its own X-Forwarded-For (the gateway
// trusts the Docker bridge in this run) and the per-address connection limit counts each device on its own.
function frame(text) {
  const data = Buffer.from(text), mask = crypto.randomBytes(4);
  const head = data.length < 126 ? Buffer.from([0x81, 0x80 | data.length]) : Buffer.from([0x81, 0xfe, data.length >> 8, data.length & 255]);
  const body = Buffer.from(data.map((b, i) => b ^ mask[i % 4]));
  return Buffer.concat([head, mask, body]);
}
const url = new URL(base.replace("ws", "http"));
let joined = 0, failed = 0;
for (let i = 0; i < Number(count); i++) {
  const did = crypto.randomUUID();
  const req = http.request({
    host: url.hostname, port: url.port, path: `/socket/websocket?vsn=2.0.0&token=${token(did)}`,
    headers: { Connection: "Upgrade", Upgrade: "websocket", "Sec-WebSocket-Version": "13",
      "Sec-WebSocket-Key": crypto.randomBytes(16).toString("base64"), "X-Forwarded-For": `10.${i >> 16 & 255}.${i >> 8 & 255}.${i & 255}` },
  });
  req.on("upgrade", (_res, sock) => {
    let ref = 1;
    sock.write(frame(JSON.stringify(["1", "1", `device:${did}`, "phx_join", {}])));
    sock.once("data", (d) => { if (d.toString().includes('"ok"')) joined++; });
    setInterval(() => sock.write(frame(JSON.stringify([null, String(++ref), "phoenix", "heartbeat", {}]))), 30_000);
    sock.on("error", () => failed++);
  });
  req.on("response", () => failed++);
  req.on("error", () => failed++);
  req.end();
  await new Promise((r) => setTimeout(r, 2));
}
setTimeout(() => { console.log(JSON.stringify({ joined, failed })); }, 5_000);
setTimeout(() => process.exit(0), Number(seconds) * 1000);
