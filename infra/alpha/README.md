# 알파 서버

M1 알파 서버(NCP Micro, 1 vCPU·1GB) 위에서 도는 구성입니다([인프라](../../docs/parts/infra.md) 2장). 서버 자체(VPC, ACG, 공인 IP)는 [Terraform](../terraform/README.md)이 만들고, 이 디렉터리는 그 위에 올리는 것을 둡니다.

알파 주소는 `https://api.geunaleottae.com`입니다(아래 "도메인과 DNS"). 앱의 서버 주소(안드로이드 `geunalApiBase`, iOS `GEUNAL_API_BASE`)와 App Link 호스트에 이 주소를 넣습니다.

| 파일 | 하는 일 |
| --- | --- |
| `compose.yml` | API·PostgreSQL·Valkey. 호스트 이름이 있으면 Caddy(TLS)도 띄움. 게이트웨이는 M2부터 |
| `Caddyfile` | `GEUNAL_API_HOST`의 Let's Encrypt 인증서를 받아 API로 넘김 |
| `bootstrap.sh` | 서버 준비: 스왑 2GB, 쓰지 않는 서비스 끄기, Docker와 로그 회전, 비밀값 파일, 매일 DB 덤프 타이머. 배포할 때마다 다시 돌아도 안전 |
| `db-dump.sh`, `geunal-db-dump.*` | 매일 04:00(서울) `pg_dump`, 7일 보관 |
| `deploy.sh` | 운영자 PC에서 하는 배포(`make deploy-alpha`) |

## 배포

```bash
GEUNAL_ALPHA_SSH=root@<서버 주소> GEUNAL_ALPHA_SSH_KEY=~/.ssh/<키> GEUNAL_API_HOST=<호스트 이름> make deploy-alpha
```

- ACG가 SSH를 운영자 주소(`admin_cidr`)에만 열어 두어서, CI가 아니라 운영자 PC에서 돌립니다.
- API 이미지 태그는 `server/api`를 마지막으로 바꾼 커밋입니다([인프라](../../docs/parts/infra.md) 3장). 서버에 그 태그가 있으면 빌드와 전송을 건너뜁니다. 레지스트리 없이 SSH로 보냅니다.
- 서버가 amd64라서, 이미지를 arm64로 만드는 Apple 실리콘 Mac에서는 스크립트가 멈춥니다.
- `GEUNAL_API_HOST`가 없으면 Caddy를 띄우지 않습니다. 그러면 API는 서버의 루프백(`127.0.0.1:8080`)에만 열리고, `ssh -L 8080:127.0.0.1:8080 …`로 확인합니다.
- 되돌리기: 서버의 `/opt/geunal/.env`에서 `GEUNAL_API_TAG`를 이전 태그로 바꾸고 `docker compose up -d`를 실행합니다. 직전 태그 하나는 남겨 둡니다.

## 서버에 있는 것

- `/opt/geunal`: 이 디렉터리의 복사본과 `.env`(이미지 태그, 호스트 이름).
- `/etc/geunal`(root 전용): `postgres.env`(DB 비밀번호), `api.env`(DB 비밀번호, JWT 서명 키의 JWK Set).
  - 처음 부트스트랩할 때 서버에서 만들어서 서버 밖으로 나가지 않습니다.
  - M1의 제공자 키(카카오, Apple)도 `api.env`에 줄로 더합니다. 부트스트랩은 없는 줄만 더하므로 손으로 넣은 줄은 남습니다.
  - PostgreSQL 볼륨을 둔 채 이 디렉터리를 지우면 새 비밀번호가 기존 DB와 맞지 않습니다.
- `/var/backups/geunal`: DB 덤프(7일).

## 도메인과 DNS

`geunaleottae.com`은 Cloudflare Registrar에서 샀고(2026-10-31, 2027-10-30까지), DNS도 Cloudflare의 무료 요금제에 있습니다. 이 이름을 다른 곳으로 돌릴 수 있는 사람은 유효한 인증서를 받아 API를 흉내 낼 수 있으므로([위협 모델](../../docs/security/threat-model.md) 3.7) 다음을 지킵니다.

- **API 레코드는 프록시 없이 DNS만(회색 구름)입니다.** 프록시를 켜면 Cloudflare가 TLS를 풀어 앱의 요청(토큰, 접속 IP, 메타데이터)을 보고, 개인정보가 국외 망을 거칩니다. 프록시는 약관 같은 정적 페이지에만 씁니다.
- CAA로 Let's Encrypt만 인증서를 내게 하고, DNSSEC를 켭니다. Cloudflare의 Universal SSL이 켜져 있으면 Cloudflare가 자기 인증 기관들(DigiCert, Google, SSL.com, Comodo)을 CAA에 자동으로 더합니다. 프록시하는 레코드가 없는 동안은 Universal SSL을 꺼서 Let's Encrypt만 남깁니다.
- Cloudflare 계정의 2단계 인증이 이 이름을 지킵니다. 자동 갱신을 켜 둡니다. 만료되면 다른 사람이 등록해 앱의 요청을 받을 수 있습니다.
- 처음 며칠 쓴 DuckDNS 이름(`geunaleottae.duckdns.org`)은 그 주소로 만든 빌드가 없어서 지워도 됩니다.

## 아직 없는 것

- 덤프를 `geunal-alpha-backups` 버킷에도 올리기: 서버 전용 Object Storage 키가 필요합니다.
- 디스크 여유: 10GB 가운데 85%를 씁니다(스왑 2GB, containerd 이미지 저장소 1.2GB). API 이미지를 더 키우기 전에 블록 스토리지를 더하거나 Docker를 이전 저장 방식(overlay2)으로 돌려야 할 수 있습니다.
- Secret Manager: 알파 동안은 위 파일로 두고, 테스터를 받기 전에 옮깁니다.
- 이미지 레지스트리와 `main` 머지 뒤 자동 배포, 기본 경보.
