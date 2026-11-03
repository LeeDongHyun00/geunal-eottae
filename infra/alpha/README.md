# 알파 서버

M1 알파 서버(NCP Micro, 1 vCPU·1GB) 위에서 도는 구성입니다([인프라](../../docs/parts/infra.md) 2장). 서버 자체(VPC, ACG, 공인 IP)는 [Terraform](../terraform/README.md)이 만들고, 이 디렉터리는 그 위에 올리는 것을 둡니다.

알파 주소는 `https://api.geunaleottae.com`입니다(아래 "도메인과 DNS"). 앱의 서버 주소(안드로이드 `geunalApiBase`, iOS `GEUNAL_API_BASE`)와 App Link 호스트에 이 주소를 넣습니다.

| 파일 | 하는 일 |
| --- | --- |
| `compose.yml` | API·PostgreSQL·Valkey. 호스트 이름이 있으면 Caddy(TLS)도 띄움. 게이트웨이는 M2부터 |
| `Caddyfile` | `GEUNAL_API_HOST`의 Let's Encrypt 인증서를 받아 API로 넘김 |
| `bootstrap.sh` | 서버 준비: 스왑 2GB, 쓰지 않는 서비스 끄기, Docker와 로그 회전, 비밀값 파일, 매일 DB 덤프 타이머. 배포할 때마다 다시 돌아도 안전 |
| `db-dump.sh`, `geunal-db-dump.*` | 매일 04:00(서울) `pg_dump`, 서버에 7일 보관. 암호화한 사본을 `geunal-alpha-backups` 버킷에 7일 |
| `deploy.sh` | 운영자 PC에서 하는 배포(`make deploy-alpha`) |
| `fetch-secrets.py` | 배포할 때 Secret Manager에서 서버 밖에서 발급받은 키를 받아 root 전용 파일로 씀(아래 "비밀값") |
| `watch.sh` | 바깥에서 하는 기본 경보. `.github/workflows/alpha-watch.yml`이 15분마다 돌림(아래 "경보") |
| `bucket-lifecycle.sh` | 운영자 PC에서 한 번: `geunal-alpha-access-logs` 버킷에 92일 만료 규칙을 걺 |

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
- `/etc/geunal`(root 전용): 비밀값 파일들. 무엇이 어디서 오는지는 아래 "비밀값"에 있습니다.
  - PostgreSQL 볼륨을 둔 채 이 디렉터리를 지우면 새 비밀번호가 기존 DB와 맞지 않습니다.
- `/var/backups/geunal`: DB 덤프(7일).

## 비밀값

서버 밖에서 발급받는 키(카카오, Apple, Object Storage, 공공데이터포털)는 NCP Secret Manager의 비밀값 하나에 넣습니다. 서버에서 만드는 값(DB 비밀번호, JWT 서명 키)은 지금처럼 서버의 root 전용 파일에만 둡니다([인프라](../../docs/parts/infra.md) 10장).

| 어디 | 무엇 |
| --- | --- |
| Secret Manager 비밀값(JSON) | 아래 표의 키. 운영자가 콘솔에서 넣고 고침 |
| `/etc/geunal/postgres.env`, `api.env` | 처음 부트스트랩할 때 서버에서 만든 DB 비밀번호와 JWT 서명 키(JWK Set). 서버 밖으로 나가지 않음 |
| `/etc/geunal/secret-manager.env` | Secret Manager를 읽는 서버 전용 키와 비밀값 ID |
| `/etc/geunal/api.secrets.env`, `dump.env` | 배포할 때 `fetch-secrets.py`가 Secret Manager에서 받아 쓴 파일 |

| 키 | 없으면 |
| --- | --- |
| `GEUNAL_AUTH_KAKAO_APP_KEY`, `GEUNAL_AUTH_KAKAO_ADMIN_KEY` | 카카오 로그인이 404 |
| `GEUNAL_AUTH_APPLE_KEY_ID`, `GEUNAL_AUTH_APPLE_PRIVATE_KEY`(.p8 PEM) | Apple 로그인이 404. .p8을 읽지 못하면 서버가 시작하지 않음 |
| `GEUNAL_S3_ACCESS_KEY`, `GEUNAL_S3_SECRET_KEY` | 백업 요청이 실패(부트스트랩이 경고) |
| `GEUNAL_DUMP_AGE_RECIPIENT`, `GEUNAL_DUMP_S3_ACCESS_KEY`, `GEUNAL_DUMP_S3_SECRET_KEY` | 덤프가 서버에만 남음(아래 "서버 밖 DB 덤프") |
| `GEUNAL_HOLIDAYS_SERVICE_KEY`(공공데이터포털 "한국천문연구원_특일 정보"의 일반 인증키 Decoding 값) | 공휴일 매일 동기화가 아무것도 하지 않음. `GET /holidays`가 빈 `years`를 주고, 기기는 앱에 든 표를 씀 |

- 값이 없으면 그 기능만 꺼지고 서버는 뜹니다. Apple 팀 ID와 반환 주소, 저장소 주소와 버킷은 비밀이 아니어서 `compose.yml`에 있습니다. `GEUNAL_AUTH_STUB`은 넣지 않습니다.
- `fetch-secrets.py`는 `GEUNAL_`로 시작하는 이름만 받습니다. `GEUNAL_DUMP_`는 `dump.env`로, 나머지는 `api.secrets.env`로 갑니다. 값은 출력하지 않고 받은 이름만 출력합니다.
- 받다가 실패하거나 맞지 않는 키가 있으면 파일을 바꾸지 않고 배포를 멈춥니다.

**처음 한 번**

1. **서브 계정:** 서버 전용 서브 계정을 만들어 `NCP_SECRETMANAGER_USER`(조회만) 정책만 주고, API 접근 키를 발급합니다. 이 정책은 계정의 모든 비밀값을 읽을 수 있으므로, 이 NCP 계정에는 그날어때 알파의 비밀값만 둡니다.
2. **비밀값:** 콘솔 › Security › Secret Manager › Secret에서 비밀값을 하나 만들고 위 표의 키를 JSON으로 넣습니다. 보호 키는 서비스가 관리하는 키를 고르고, 자동 교체는 켜지 않습니다. 교체 대상 키를 하나 고르라고 하면 아무 키나 고릅니다. 만든 뒤 비밀값 ID를 적어 둡니다.
   - JSON 문자열 안의 줄바꿈은 `\n`으로 적습니다. 그래서 `.p8`은 `"-----BEGIN PRIVATE KEY-----\nMIG...\n-----END PRIVATE KEY-----"`처럼 한 줄이 됩니다.
3. **서버:** 아래 세 줄로 `/etc/geunal/secret-manager.env`를 만듭니다. 예: `ssh root@<서버 주소> 'umask 077; cat >/etc/geunal/secret-manager.env'`로 붙여 넣고 Ctrl-D.

```bash
NCP_ACCESS_KEY=...
NCP_SECRET_KEY=...
GEUNAL_SECRET_ID=...
```

4. **배포:** `make deploy-alpha`. 부트스트랩이 값을 받아 쓰고 받은 키 이름을 출력합니다.
5. **정리:** 예전에 `api.env`에 손으로 넣은 제공자·저장소 키 줄이 있으면 지웁니다. `api.secrets.env`가 뒤에 읽혀 Secret Manager의 값이 이기지만, 같은 키를 두 곳에 두지 않으려는 것입니다. `dump.env`는 받아 쓸 때 통째로 바뀝니다. Secret Manager에서 `GEUNAL_DUMP_` 키를 모두 지우면, 이 스크립트가 썼던 `dump.env`도 지워져 덤프가 다시 서버에만 남습니다.

**키를 바꿀 때:** 콘솔에서 값을 고친 뒤 `make deploy-alpha`를 돌립니다. 값이 바뀐 컨테이너만 다시 만들어집니다.

**얻는 것과 남는 것:**
- 운영자가 서버에 들어가 파일을 고치지 않고 콘솔에서 키를 넣습니다. 읽을 때마다 Cloud Activity Tracer에 기록이 남습니다. 서버가 위험해지면 서버 전용 조회 키 하나만 끊으면 됩니다.
- 받은 값은 여전히 서버 디스크에 있습니다(받아 쓴 파일, Docker가 컨테이너 설정에 남기는 환경 변수). 디스크에서도 없애려면 앱이 파일로 비밀값을 읽어야 해서 알파 범위 밖입니다.
- 비용은 비밀값 하나에 시간당 0.6원(월 약 440원)입니다.

## 서버 밖 DB 덤프

디스크나 VM을 잃어도 하루치 넘게 잃지 않도록, 매일 덤프를 `geunal-alpha-backups` 버킷의 `db/geunal-<요일 번호>.dump.age`에도 올립니다([인프라](../../docs/parts/infra.md) 2장 백업). 요일마다 같은 이름을 덮어쓰므로 버킷에는 7일치가 남습니다.

- 올리기 전에 운영자의 [age](https://age-encryption.org) 공개 키로 암호화합니다. 개인 키는 서버에 두지 않으므로 서버나 버킷 키를 빼앗겨도 덤프를 읽지 못합니다.
- API 키와 다른, 서버 전용 Object Storage 키를 씁니다. 이 키에는 `geunal-alpha-backups`에 쓰는 권한만 줍니다.
- 다음 세 키를 Secret Manager 비밀값에 넣습니다(위 "비밀값"). 배포할 때 `/etc/geunal/dump.env`(root 전용)로 받아 씁니다. 이 파일이 없으면 덤프는 서버에만 남고 매번 경고가 남습니다.
  - `GEUNAL_DUMP_AGE_RECIPIENT`: 운영자 PC에서 `age-keygen`으로 만든 키의 공개 키(`age1...`)
  - `GEUNAL_DUMP_S3_ACCESS_KEY`, `GEUNAL_DUMP_S3_SECRET_KEY`: 서버 전용 Object Storage 키

- 복원: 덤프를 내려받아 운영자 PC에서 `age -d -i <개인 키> geunal-N.dump.age > geunal.dump`로 풀고 `pg_restore`로 넣습니다.
- 확인: `journalctl -u geunal-db-dump`에 `Uploaded db/...`가 남습니다.

## 접속 로그 버킷의 만료

API가 접속 로그를 `geunal-alpha-access-logs`에 올립니다([API 서버](../../docs/parts/api-server.md) 10장). 접속 로그는 3개월 보관이 법적 요건이고, 그 뒤에는 남기지 않도록 92일 만료 규칙을 겁니다([인프라](../../docs/parts/infra.md) 1장).

- NCP Terraform 제공자가 수명 규칙을 다루지 못해 `bucket-lifecycle.sh`가 S3 API(`PutBucketLifecycleConfiguration`)로 겁니다. 다시 돌려도 같은 규칙으로 바뀝니다.
- 버킷 설정을 바꿀 수 있는 키가 필요해 운영자 PC에서 돌립니다. 서버의 쓰기 전용 키로는 되지 않습니다.

```bash
GEUNAL_LIFECYCLE_S3_ACCESS_KEY=... GEUNAL_LIFECYCLE_S3_SECRET_KEY=... infra/alpha/bucket-lifecycle.sh
```

- 첫 로그가 92일이 되기 전(2027년 2월 초)에 걸어야 합니다. 걸고 나면 스크립트가 버킷이 가진 규칙을 출력합니다.

## 경보

`.github/workflows/alpha-watch.yml`이 15분마다 GitHub의 러너에서 `watch.sh`를 돌립니다. NCP 밖에서 보므로 테스터의 휴대폰이 보는 것과 같습니다. M2의 모니터링([인프라](../../docs/parts/infra.md) 8장, 11장) 전까지 쓰는 최소한의 경보입니다.

| 검사 | 실패로 보는 것 |
| --- | --- |
| `https://api.geunaleottae.com/actuator/health` | 약 2분 동안 네 번 시도해 모두 200 `UP`이 아님. 배포 중 재시작으로는 울리지 않음 |
| 디스크 | 남은 공간이 1GB보다 적으면 상태 검사가 `DOWN`(503)이 되어 위 검사로 드러남(`compose.yml`) |
| TLS 인증서 | 남은 기간이 14일보다 짧음. Caddy가 수명의 3분의 1이 남았을 때 갱신하므로, 갱신이 며칠째 실패한다는 뜻 |

- 실패하면 `alpha-alert` 라벨이 붙은 이슈를 하나 열고, 이슈 첫 줄에서 저장소 주인을 불러 GitHub 알림이 가게 합니다. 문제가 이어지는 동안에는 더 쓰지 않고, 모든 검사가 통과하면 한 줄 남기고 닫습니다.
- 워크플로 실행은 문제를 알리는 동안에도 녹색입니다. 예약 실행이 실패하면 15분마다 메일이 가기 때문입니다. 실행이 빨갛다면 경보 자체가 고장 난 것입니다.
- 비밀값을 쓰지 않습니다. 토큰은 이 저장소의 이슈만 다룹니다.

**경보가 오면 확인할 것(서버에서)**

- `cd /opt/geunal && docker compose ps`: 어느 컨테이너가 멈췄거나 unhealthy인지 봅니다.
- `docker compose logs --tail 200 api`(또는 `caddy`): 시작 실패나 인증서 갱신 오류를 봅니다.
- 503이면 디스크부터 봅니다: `df -h /`, `docker system df`. 쓰지 않는 이미지는 `docker image prune`으로 지웁니다. 덤프는 7일치만 남습니다.
- 다시 띄우기: `docker compose up -d`.

**아직 보지 않는 것:** 매일 덤프가 버킷에 올라갔는지는 이 경보가 보지 못합니다. 서버 안의 일이라 바깥에서 볼 방법이 없기 때문입니다. 지금은 `journalctl -u geunal-db-dump`로 확인하고, M2의 모니터링에서 다룹니다.

## 도메인과 DNS

`geunaleottae.com`은 Cloudflare Registrar에서 샀고(2026-10-31, 2027-10-30까지), DNS도 Cloudflare의 무료 요금제에 있습니다. 이 이름을 다른 곳으로 돌릴 수 있는 사람은 유효한 인증서를 받아 API를 흉내 낼 수 있으므로([위협 모델](../../docs/security/threat-model.md) 3.7) 다음을 지킵니다.

- **API 레코드는 프록시 없이 DNS만(회색 구름)입니다.** 프록시를 켜면 Cloudflare가 TLS를 풀어 앱의 요청(토큰, 접속 IP, 메타데이터)을 보고, 개인정보가 국외 망을 거칩니다. 프록시는 약관 같은 정적 페이지에만 씁니다.
- CAA로 Let's Encrypt만 인증서를 내게 하고, DNSSEC를 켭니다. Cloudflare의 Universal SSL이 켜져 있으면 Cloudflare가 자기 인증 기관들(DigiCert, Google, SSL.com, Comodo)을 CAA에 자동으로 더합니다. 그래서 프록시하는 레코드가 없는 동안은 Universal SSL을 꺼 둡니다. 정적 페이지를 프록시할 때 다시 켭니다.
- 암호화 모드는 Full (strict)입니다. 프록시하는 레코드가 생기면 Cloudflare가 우리 서버의 인증서를 확인합니다.
- Cloudflare의 인증서 투명성 감시를 켜 두어, 이 도메인으로 인증서가 나오면 메일이 옵니다.
- 2026-10-31 기준으로 위 설정을 모두 해 두었습니다. CAA에는 `letsencrypt.org`만 있고, `.com` 레지스트리에 DS가 올라 있습니다.
- Cloudflare 계정의 2단계 인증이 이 이름을 지킵니다. 자동 갱신을 켜 둡니다. 만료되면 다른 사람이 등록해 앱의 요청을 받을 수 있습니다.
- 처음 며칠 쓴 DuckDNS 이름(`geunaleottae.duckdns.org`)은 그 주소로 만든 빌드가 없어서 지워도 됩니다.

## 아직 없는 것

- 디스크 여유: 10GB 가운데 85%를 씁니다(스왑 2GB, containerd 이미지 저장소 1.2GB). API 이미지를 더 키우기 전에 블록 스토리지를 더하거나 Docker를 이전 저장 방식(overlay2)으로 돌려야 할 수 있습니다.
- 이미지 레지스트리와 `main` 머지 뒤 자동 배포.
