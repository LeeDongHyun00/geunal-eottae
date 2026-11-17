# 실시간(게이트웨이) 인수인계

갱신: 2026-11-17, 이전 세션 https://claude.ai/code/session_0132zdS72drDTVz5N8nhS98C

## 지금 상태

- M2 실시간의 코드 몫(`state`·`membership` 즉시 전달, 알파 배포 준비)은 끝났습니다. 마지막으로 머지된 내 PR은 #195입니다.
  - #188: 신뢰 프록시(`TRUSTED_PROXIES`) 뒤에서는 X-Forwarded-For의 마지막 홉으로 접속 제한을 셉니다. 접속당 수신 프레임은 분당 30개까지 받습니다. prod 로그는 info이고, 채널 join과 기기가 보낸 이벤트는 남기지 않습니다.
  - #190: 그룹 이벤트의 순서 보존, 여러 기기로의 전달, 모르는 필드 통과를 시험합니다. 이벤트 형식은 계약 벡터(`core/vectors/realtime_events.json`)로 시험합니다.
  - #195: 릴리스를 interactive 모드로 띄워 유휴 메모리를 반으로 줄였습니다.
- 알파 배포 설정은 조정 세션의 #197(infra/alpha)로 머지됐습니다. 바깥 감시는 #215, Caddy 2.11.9와 HTTP/2는 #219입니다.
- **배포 전입니다.** 2026-11-17에 `https://rt.geunaleottae.com/healthz`는 TLS 단계에서 거절됐습니다. DNS는 API와 같은 서버를 가리키고, API는 200입니다. 사용자가 맥에서 `GEUNAL_RT_HOST`를 주어 `make deploy-alpha`를 돌려야 Caddy가 인증서를 받고 게이트웨이가 뜹니다.
- `make lint-gateway`와 `make test-gateway`(60개)는 #195 시점에 녹색이었습니다. 그 뒤 `server/gateway`를 바꾼 커밋은 없습니다.

## Micro(1 vCPU·1GB) 설정 근거

이미지를 `--memory 200m --cpus 1`, 읽기 전용 루트로 Valkey와 함께 띄웠습니다. 가상 기기는 JWT, join, 30초 하트비트를 보냅니다. 값은 cgroup 사용량입니다(#195 본문).

| 상태 | embedded(이전) | interactive(#195) |
| --- | --- | --- |
| 유휴 | 116MB(부팅 중 최대 123MB) | 55MB |
| 기기 100개 | 124MB | 63MB |
| 기기 1,000개 | 약 175MB | 114MB |

- 연결 하나에 약 60KB입니다. compose에는 `mem_limit`·`memswap_limit` 160m과 `oom_score_adj: 500`을 둡니다. 스왑을 막아야 서버 스왑 2GB로 밀려나 다른 서비스까지 느려지지 않습니다.
- `ERL_AFLAGS=+sbwt none +sbwtdcpu none +sbwtdio none`: 쉬는 스케줄러가 CPU를 붙잡고 기다리지 않습니다. 스케줄러 수는 vCPU와 cgroup CPU 상한을 따르므로 `+S`는 필요 없습니다. 메모리 영향은 1MB 이하였습니다.
- 상한에 닿으면 커널이 beam을 죽이고, `restart: unless-stopped`로 다시 떠 healthy로 돌아옵니다(상한 90m, 기기 1,000개로 확인). 기기는 백오프로 다시 붙어 API로 따라잡습니다.
- embedded 모드는 상한 110m에서 부팅 중에 죽기를 반복했습니다.
- 이미지는 186MB(gzip 44MB)이고 그중 릴리스 계층이 31MB입니다. 기반 이미지가 같으면 태그 둘을 남겨도 약 220MB입니다.
- 측정에 쓴 스크립트는 브랜치 `claude/realtime-load-probe`의 `server/gateway/scripts/load-probe.mjs`에 있습니다(머지하지 않음).

## 열린 PR과 브랜치

| PR·브랜치 | 상태 | 남은 일 |
| --- | --- | --- |
| `claude/realtime-load-probe` | 푸시만 함 | M3 부하 시험 때 다듬어 PR로 올리거나 지웁니다 |
| 그 밖의 `claude/realtime-*` | 모두 머지됨 | 지워도 됩니다 |

## 다음 할 일

1. **배포 뒤 확인:** 사용자가 배포하면 `/healthz` 200, 기기 접속과 join을 확인합니다.
   - 연결 하나를 3시간 넘게 붙여 두고 끊기지 않는지도 봅니다(#197 리뷰 댓글). rt 사이트의 `request_body max_size 16KB`가 WebSocket에 걸린다면 하트비트가 16KB 쌓이는 약 2시간마다 끊깁니다. 걸리면 rt 사이트에서 그 블록을 뺍니다.
   - #219로 HTTP/2가 켜졌습니다. 코어(tungstenite)는 HTTP/1.1 업그레이드로 붙지만, HTTP/2 WebSocket(확장 CONNECT)을 쓰는 클라이언트가 생기면 같은 확인을 다시 합니다.
2. **계약 7장 토큰 헤더(사용자 확인 뒤):** 접속 토큰을 URL 쿼리에서 `Sec-WebSocket-Protocol` 헤더(`phoenix, base64url.bearer.phx.<base64url(token)>`)로 옮깁니다.
   - 한 PR에서 `docs/parts/protocol.md` 7장을 고치고, 게이트웨이가 두 방식을 모두 받게 합니다(Phoenix `connect_info: [:auth_token]`). 벡터는 바뀌지 않습니다.
   - 배포 뒤 코어가 헤더로 옮기고, 그다음 쿼리를 끊습니다. 확인 전에는 protocol.md를 고치지 않습니다.
3. **M3 부하 시험**(노드당 5만 연결, p99 200ms, 순차 종료, [gateway.md](../../docs/parts/gateway.md) 10장): 사용자 결정으로 M3와 함께 보류입니다.

## 기다리는 결정

| 결정 | 누가 | 어디에 적혀 있나 |
| --- | --- | --- |
| 토큰을 쿼리에서 헤더로 옮기기(계약 7장) | 사용자 | 조정 세션이 확인을 요청함(2026-11-04) |
| M3 재개와 부하 시험 | 사용자 | 2026-11-04 결정으로 보류 |

## 알아 둘 것

- **코어와의 약속(코어 세션 동의, 2026-11-04):** 토큰 헤더는 위 2번 순서로 합니다. 코어는 응답 서브프로토콜이 `phoenix`인지 확인합니다. 연결 규칙에 관한 합의가 하나 더 있고, 새 조정 세션에 따로 넘겼습니다.
- **Caddy 뒤 접속 제한:** compose 네트워크의 고정 대역(`172.30.0.0/24`)을 `TRUSTED_PROXIES`로 줍니다(#197). 비우면 모든 기기가 Caddy 주소 하나로 세어집니다. 값이 잘못되면 게이트웨이가 부팅하지 않습니다.
- **프레임 상한:** `GatewayWeb.DeviceTransport`가 `DeviceSocket`을 감싸서 셉니다. Phoenix 소켓의 전송 콜백은 덮어쓸 수 없기 때문입니다.
- **시험 함정:** `assert_push`는 선택적 수신이라 순서를 보지 못합니다. 순서는 메시지함에서 차례로 꺼내 비교합니다(#190).
- **로컬 측정:** 주소당 접속 제한(분당 30) 때문에, 한 주소에서 기기 여럿을 붙이려면 신뢰 대역에서 X-Forwarded-For를 기기마다 다르게 줍니다. `pkill -f`에 자기 명령줄과 겹치는 패턴을 주면 그 셸도 죽습니다.
- **사용량:** 무거운 빌드와 시험은 조정 세션이 정한 순서를 따릅니다.
