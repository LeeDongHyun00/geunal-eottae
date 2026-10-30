# 알파 서버

M1 알파 서버(NCP Micro, 1 vCPU·1GB) 위에서 도는 구성입니다([인프라](../../docs/parts/infra.md) 2장). 서버 자체(VPC, ACG, 공인 IP)는 [Terraform](../terraform/README.md)이 만들고, 이 디렉터리는 그 위에 올리는 것을 둡니다.

지금 알파 주소는 `https://geunaleottae.duckdns.org`입니다. 정식 도메인을 정하기 전까지 DuckDNS의 이름을 씁니다(아래 "DuckDNS를 쓰는 동안"). 앱의 서버 주소(안드로이드 `geunalApiBase`, iOS `GEUNAL_API_BASE`)와 App Link 호스트에 이 주소를 넣습니다.

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

## DuckDNS를 쓰는 동안

이 이름을 가리키게 할 수 있는 사람은 유효한 인증서를 받아 API를 흉내 낼 수 있습니다. 일정 내용은 종단간 암호화돼 있고 제공자 토큰은 기기 키에 묶여 있지만, 앱의 액세스·리프레시 토큰과 메타데이터는 보입니다.

- DuckDNS에는 운영자의 GitHub 계정으로 로그인합니다. 그 계정의 2단계 인증이 이 이름을 지킵니다.
- 서버 IP가 고정이라 DuckDNS 토큰은 쓰지 않습니다. 토큰만 있으면 IP와 TXT 레코드를 바꿔 인증서까지 받을 수 있으니 어디에도 두지 않습니다. 샜다면 DuckDNS의 Account 메뉴에서 다시 만듭니다.
- CAA 레코드를 걸 수 없고 `duckdns.org`는 DNSSEC 서명이 없습니다. 인증서 투명성 로그로 모르는 인증서가 나오는지 봅니다.
- 일부 보안 제품과 회사·학교 DNS 필터는 duckdns 하위 도메인을 막습니다. 테스터가 연결하지 못하면 모바일 데이터로 확인합니다.
- 정식 도메인으로 옮긴 뒤에도, 이 주소가 들어간 빌드가 남아 있는 동안은 이름을 지우지 않습니다. 다른 사람이 같은 이름을 등록하면 옛 빌드의 토큰이 그쪽으로 갑니다.

## 아직 없는 것

- 덤프를 `geunal-alpha-backups` 버킷에도 올리기: 서버 전용 Object Storage 키가 필요합니다.
- 디스크 여유: 10GB 가운데 85%를 씁니다(스왑 2GB, containerd 이미지 저장소 1.2GB). API 이미지를 더 키우기 전에 블록 스토리지를 더하거나 Docker를 이전 저장 방식(overlay2)으로 돌려야 할 수 있습니다.
- Secret Manager: 알파 동안은 위 파일로 두고, 테스터를 받기 전에 옮깁니다.
- 이미지 레지스트리와 `main` 머지 뒤 자동 배포, 기본 경보.
