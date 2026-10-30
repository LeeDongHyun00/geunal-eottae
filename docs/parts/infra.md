# 인프라

클라우드 구성, 데이터 저장소, 배포, CI, 관측, 확장 조건을 다룹니다. 서버 두 개([API 서버](api-server.md), [실시간 게이트웨이](gateway.md))와 앱 빌드가 이 위에서 돕니다.

## 1. 환경

| 환경 | 목적 | 구성 |
| --- | --- | --- |
| 로컬 | 개발, 헤드리스 종단 시험, 앱 UI 테스트의 백엔드 | Docker Compose(4장) |
| 알파 | 내부·친구 테스트(M1~M3) | VM 한 대에 Docker Compose. M1은 무료 Micro, M2부터 2 vCPU·4GB(2장 끝) |
| 스테이징 | 배포 확인, 부하 시험 | M2부터 무료 Micro. 공개 베타 전 부하 시험은 운영 구성을 잠깐 띄워서 |
| 운영 | 공개 베타부터 | VM 한 대(2 vCPU·4GB) + WAL 시점 복구. 6장 조건을 넘으면 2장 표의 관리형 구성으로 |

## 2. 운영 구성 요구사항

Supabase 같은 BaaS는 쓰지 않고, 클라우드의 기본 구성 요소(네트워크, 컨테이너 실행, 관리형 데이터베이스·캐시·스토리지) 위에 서버를 직접 구성합니다. 서버가 MLS 커밋 순서·우편함·푸시를 직접 처리해야 하고, 무엇을 얼마나 남길지도 우리가 정해야 하기 때문입니다.

공급자는 네이버 클라우드(NCP) 한국 리전입니다. 아래 표는 관리형으로 옮긴 뒤의 목표 구성입니다. 서버비를 매출로 감당할 수 있을 때까지(6장 조건)는 출시 뒤에도 VM 한 대로 운영합니다(이 장 끝).

| 구성 요소 | 요구사항 | NCP 상품 |
| --- | --- | --- |
| PostgreSQL | 관리형, 16 이상, 저장 시 암호화, 시점 복구(PITR) 7일, 다중 영역 자동 장애 조치 | Cloud DB for PostgreSQL(16·17 지원). 고가용성은 서버 2대로 과금 |
| Redis | 관리형 Redis 호환. pub/sub과 레이트 리밋만 쓰므로 영속화는 필요 없음 | Cloud DB for Cache(Valkey 기반) |
| 오브젝트 스토리지 | S3 호환. 버킷 `media-chat`(수명 규칙 31일), `media-appt`(366일), `media-personal`(366일), `group-assets`, `backups`, `access-logs`(92일). 공개 접근 차단, 서명된 URL로만 접근 | Object Storage(수명 주기 정책) |
| 컨테이너 실행 | API 2대 이상, 게이트웨이 2대 이상(운영 3대), 롤링 배포 | VM에서 Docker. NCP에는 관리형 컨테이너 실행 서비스가 없고, 쿠버네티스(NKS)는 서버 종류가 늘 때 검토 |
| 로드 밸런서 | HTTPS, WebSocket, 유휴 제한 120초 이상. 도메인 `api.`, `rt.`, 초대 링크 | API는 Application LB, 게이트웨이는 Network Proxy LB(유휴 시간 1~3600초) |
| 비밀값 | 관리형 비밀 저장소(10장) | Secret Manager |
| 초대 링크 도메인 | `/.well-known/apple-app-site-association`과 `/.well-known/assetlinks.json`을 API가 제공. `assetlinks.json`은 M1부터 필요함(안드로이드 Apple 웹 로그인이 App Link `/auth/apple`로 돌아옴). `/i/<code>`는 앱이 없으면 스토어로 보냄 | Global DNS, Certificate Manager(무료 인증서) |
| 구성 코드 | Terraform. 콘솔에서 손으로 만들지 않음. PR에서 `terraform plan` 결과를 검토하고 머지한 뒤 적용. 상태 파일은 잠금이 되는 원격 저장소에 | 공식 제공자 `NaverCloudPlatform/ncloud`. 상태는 Object Storage 버킷 `geunal-tfstate`에 `s3` 백엔드와 잠금 파일로 둠. 이 버킷만 첫 적용 전에 손으로 만듦([infra/terraform](../../infra/terraform/README.md)) |
| 계정과 권한 | 스테이징과 운영을 나눔. 사람은 MFA로 들어오고, 운영 쓰기 권한은 최소 인원만. CI는 배포 전용 키(5장) | VPC로 나눔(리전당 3개 무료), Sub Account로 권한 분리. 운영을 별도 계정으로 옮길지는 공개 베타 전에 정함 |
| 네트워크 | 데이터베이스·Redis·컨테이너는 사설 서브넷. 인터넷에 열린 것은 로드 밸런서뿐. 서버 접속은 기록이 남는 방식으로 | 사설 서브넷 + NAT Gateway, ACG. 서버 접속은 배스천 한 대를 거치고, ACG로 관리자 IP만 SSH 허용. SSL VPN(VPC)은 월 12만 원부터라 ISMS 대상이 될 때 검토 |
| 감사 기록 | 클라우드 콘솔·API 감사 로그와 데이터베이스 접근 기록(보존은 9장) | Cloud Activity Tracer(기본 무료) |

CDN은 두지 않습니다. 미디어는 암호문이고, 서명된 URL로 스토리지에서 바로 받습니다.

**호스팅 위치:** 국내(NCP 한국 리전)로 정했습니다. 서버를 어느 나라에 두든 운영사에는 한국 법(통신비밀보호법의 보관·협조 의무, 개인정보 보호법)이 그대로 적용됩니다. 해외에 두면 개인정보 국외 이전 고지가 더해지고, 그 나라의 법이 하나 더 붙습니다. 그래서 위치는 지연과 운영으로 고르고, 보호는 종단간 암호화와 보관 최소화로 합니다. 2026년 10월에 검토한 해외 후보는 다음과 같습니다.

| 후보 | 그 나라의 통신 기록 보관 의무 | AWS·Azure·Google Cloud 리전 | 서울과 왕복 지연 |
| --- | --- | --- | --- |
| 말레이시아 | 일반 의무를 찾지 못함. 자국 사용자 800만 명 이상 메신저·SNS는 2026년부터 면허 체계 적용 | AWS, Azure | 69ms |
| 스위스 | 통신사는 6개월. 앱 사업자로 넓히는 VÜPF 개정은 2025년 의견수렴에서 반대가 많아 재검토 중 | 셋 다 있음(국내 업체 Exoscale도 있음) | 228ms |
| 루마니아 | 2014년 위헌 결정 뒤 없음. EU 차원의 보관 법안(메신저 포함)이 준비 중 | 없음 | 240~260ms(추정) |
| 아이슬란드 | 통신사업자 6개월(전자통신법 2022년 70호 89조) | 없음 | 260~280ms(추정) |
| 파나마 | 통신·인터넷 사업자 6개월, 법원 요청 시 6개월 연장(2009년 법률 51호) | 없음 | 200ms 이상(추정) |

- 지연은 Azure 리전 간 왕복 중앙값(2026년 7월 자료)입니다. 국내 리전 간은 8ms, 도쿄는 30ms입니다. 리전이 없는 나라는 가까운 리전 값으로 어림했습니다.
- 대형 클라우드 리전이 없는 곳에서는 관리형 PostgreSQL·Redis와 다중 영역 장애 조치를 쓸 수 없어서 위 요구사항을 맞추지 못합니다.
- 해외 검토의 근거: [스위스](https://www.inside-it.ch/vuepf-revision-muss-strafrunde-drehen-20260211), [루마니아](https://privacy.apti.ro/decizia-curtii-constitutionale-date-trafic/), [EU](https://www.heise.de/en/news/Data-Retention-Commission-to-present-proposal-by-mid-2026-11101430.html), [아이슬란드](https://pub.norden.org/temanord2024-532/8-iceland.html), [파나마](https://www.organojudicial.gob.pa/uploads/wp_repo/blogs.dir/cendoj/novedades_normativas/ley51_2009.pdf), [말레이시아](https://www.mcmc.gov.my/skmmgovmy/media/General/pdf2/MEDIA-STATEMENT_Internet-Messaging-and-Social-Media-Service-Providers-Deemed-Registered-Effective-1-January-2026_15-Dec-2025.pdf), [지연](https://learn.microsoft.com/en-us/azure/networking/azure-network-latency)

**단계별 구성과 비용:** 2026년 10월 NCP 공식 요금표(한국 리전, 부가세 별도, 한 달 720시간)로 계산했습니다.

| 단계 | 구성 | 월 비용 |
| --- | --- | --- |
| M0 | 로컬 compose만 | 0원 |
| M1 | 무료 Micro 서버(1 vCPU·1GB) 한 대에 API·PostgreSQL·Valkey를 Compose로. 게이트웨이는 아직 띄우지 않음 | 약 4천~1만 원(공인 IP, 디스크가 따로 과금되면 50GB에 5,760원) |
| M2·M3 | High CPU 2 vCPU·4GB 한 대에 API·게이트웨이·PostgreSQL·Valkey와 Caddy(TLS). Micro는 스테이징 | 약 8.8만 원 |
| M4 출시 이후 | M2·M3 구성 그대로에 WAL 시점 복구를 더함. 사용자 1천 명까지 충분 | 약 9만 원 |
| 관리형 최소 | 관리형 PostgreSQL 단일(2 vCPU·4GB), 관리형 캐시 1.5GB, API·게이트웨이 VM 각 1대, 로드 밸런서 2개, NAT | 약 41만 원 |
| 관리형 표준 | 위 표 그대로: PostgreSQL 고가용성(2대), API·게이트웨이 각 2대, 스테이징 유지 | 약 75만 원 |

단가(시간당): 서버 High CPU 2 vCPU·4GB 92원, Micro 15원(무료 기간 밖), Cloud DB for PostgreSQL 같은 사양 대당 158원, Cloud DB for Cache 1.5GB 79원, 로드 밸런서(Small) 26원, NAT Gateway 56원, 공인 IP 5.6원, Secret Manager 비밀값 하나에 0.6원. 용량 요금: 블록 스토리지 GB·월 115.2원, DB 백업 GB·월 100원, Object Storage GB·월 28원, 인터넷 전송 GB당 100원(서버는 월 20GB까지 무료).

**크레딧으로 버티는 기간:**
- 받을 수 있는 것: 그린루키 20만 원(제휴 학교 재학생, 신청일부터 1년), 신규 가입 10만 원(발급일부터 3개월, 그린루키와 중복 가능), Micro 서버 한 대 1년 무료(결제 수단을 등록한 때부터). 크레딧은 무료 상품에는 쓰이지 않습니다.
- M1 한 달과 M2·M3 약 3개월을 크레딧 30만 원 안에서 돌릴 수 있습니다(1만 + 8.8만 × 3 ≈ 27.4만 원).
- 크레딧이 끝나면 월 약 9만 원을 직접 냅니다. Micro 무료 1년이 끝나면 스테이징을 반납하거나 월 약 1.7만 원(서버 10,850원 + 디스크)을 더 냅니다.
- 관리형 구성(월 41만~75만 원)은 크레딧으로 한 달도 버티지 못하므로, 매출이 이를 넘을 때 옮깁니다(6장).
- 무료 기간과 크레딧 기한은 신청한 때부터 흐릅니다. 결제 수단 등록과 그린루키 신청은 M1 배포 직전에, 신규 가입 크레딧은 유료 서버를 띄우는 M2 시작 때 신청합니다(신청 기한이 따로 있는지는 확인 필요).

**VM 한 대 구성이 위 요구사항과 다른 점:**
- PostgreSQL·Valkey를 같은 VM의 컨테이너로 돌립니다. 장애 조치가 없어서 VM이 멈추면 서비스도 멈춥니다.
- 백업: M1은 매일 `pg_dump`를 `backups`에 올리고 7일 남깁니다(최대 하루치 손실, 테스터에게 알림). M2부터는 WAL-G로 WAL을 `backups`에 계속 올리고 매일 기본 백업을 떠서 7일 안의 시점으로 복구합니다.
- 로드 밸런서와 NAT 없이, VM의 공인 IP에서 Caddy가 TLS(Let's Encrypt)를 끝내고 `api.`와 `rt.`로 나눕니다. ACG는 443·80과 관리자 IP의 SSH만 엽니다.
- Micro(1GB)에서는 JVM 힙 256MB, PostgreSQL `shared_buffers` 128MB, 스왑 2GB로 맞춥니다. 메모리가 모자라면 M2 구성을 앞당깁니다.

## 3. 컨테이너 이미지

| 이미지 | 기반 | 메모 |
| --- | --- | --- |
| `api` | JRE 21 슬림 이미지 | Spring Boot 계층 jar, 루트가 아닌 사용자, 읽기 전용 파일 시스템, `/actuator/health` |
| `gateway` | Debian 슬림 + `mix release` | 루트가 아닌 사용자, 상태 확인 엔드포인트 |

- 태그는 git 커밋 SHA로 붙이고, 한 번 올린 태그는 바꾸지 않습니다.
- CI에서 이미지 취약점 검사(Trivy)를 돌리고, 심각도 높음 이상이면 배포를 막습니다.
- 운영의 PostgreSQL·Redis·오브젝트 스토리지는 컨테이너로 돌리지 않고 관리형을 씁니다. 상태가 있는 서비스를 직접 운영하지 않기 위해서입니다. 관리형으로 옮기기 전까지(6장 조건)는 비용 때문에 예외입니다(2장 끝).

## 4. 로컬 실행

MinIO는 2025년 10월부터 커뮤니티 이미지를 내지 않아서, 로컬 S3는 SeaweedFS로 합니다. 아래는 `infra/docker-compose.yml` 그대로입니다. 둘이 다르면 저장소의 파일이 기준입니다. `make up`이 API·게이트웨이 이미지를 지금 코드로 빌드한 뒤 띄우고, `make down`이 멈추면서 데이터를 지웁니다.

```yaml
# infra/docker-compose.yml — everything the apps and the core's end-to-end test need on one machine.
# `make up` builds the api and gateway images and starts this file; `make down` stops it and drops its data. Ports are
# published on loopback only, because the credentials here are fixed.
name: geunal

services:
  postgres:
    image: postgres:16.15
    environment:
      POSTGRES_DB: geunal
      POSTGRES_USER: geunal
      POSTGRES_PASSWORD: geunal-local
    ports: ["127.0.0.1:5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U geunal -d geunal"]
      interval: 5s
      retries: 20

  redis:
    image: redis:7.4.11
    ports: ["127.0.0.1:6379:6379"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      retries: 20

  # MinIO stopped publishing community images in 2025; SeaweedFS serves the S3 API locally.
  s3:
    image: chrislusf/seaweedfs:4.48
    command: server -s3 -dir=/data
    ports: ["127.0.0.1:8333:8333"]

  # These two images come from make image-api and make image-gateway rather than a compose build: the API image
  # packages a boot jar built outside it, and cloud sessions need scripts/docker-build.sh to reach package archives.
  api:
    image: geunal/api:local
    pull_policy: never
    read_only: true
    tmpfs: [/tmp]
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/geunal
      SPRING_DATASOURCE_USERNAME: geunal
      SPRING_DATASOURCE_PASSWORD: geunal-local
      SPRING_DATA_REDIS_HOST: redis
      GEUNAL_S3_ENDPOINT: http://s3:8333
      GEUNAL_AUTH_STUB: "true" # M0: sign in without Kakao or Apple
    ports: ["127.0.0.1:8080:8080"]
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_healthy }
      s3: { condition: service_started }

  gateway:
    image: geunal/gateway:local
    pull_policy: never
    read_only: true
    tmpfs: [/tmp]
    environment:
      JWKS_URL: http://api:8080/.well-known/jwks.json
      REDIS_URL: redis://redis:6379
    ports: ["127.0.0.1:4000:4000"]
    depends_on:
      api: { condition: service_started }
      redis: { condition: service_healthy }
```

- **태그 고정:** 모든 이미지의 태그를 고정합니다. API 테스트(Testcontainers)와 게이트웨이 CI도 같은 PostgreSQL·Redis 태그를 쓰고, 첨부를 다루는 API 테스트(M3)도 같은 SeaweedFS 이미지를 씁니다. 태그는 Dependabot(`docker-compose`)이 올리고, PostgreSQL·Redis 메이저는 서버의 결정이기도 해서 손으로 올립니다.
- **빌드 대신 이미지:** `api`와 `gateway`는 `make image-api`, `make image-gateway`가 만든 `geunal/*:local`을 씁니다(`pull_policy: never`). API 이미지는 Gradle이 밖에서 만든 boot jar를 담고, 클라우드 세션은 `scripts/docker-build.sh`를 거쳐야 패키지 저장소에 닿아서, compose의 `build:`로는 둘 다 할 수 없습니다.
- **루프백 포트:** 계정과 암호가 고정값이라 모든 포트를 이 기기(`127.0.0.1`)에만 엽니다.
- **읽기 전용 루트:** `api`와 `gateway`는 루트 파일 시스템을 읽기 전용으로 두고(`read_only: true`) `/tmp`만 tmpfs로 씁니다. 3장의 API 이미지 조건을 로컬에서도 확인하고, 게이트웨이도 같게 둡니다.
- **상태 확인:** Redis에도 healthcheck를 두어 `api`와 `gateway`는 Redis가 준비된 뒤 뜹니다. 두 이미지에는 자체 `HEALTHCHECK`가 있어, `make up`(`--wait`)은 상태 확인이 있는 서비스가 모두 healthy가 될 때까지 기다립니다.
- `GEUNAL_AUTH_STUB`은 로컬과 CI에서만 켭니다. 운영 이미지에서는 이 설정이 있으면 API가 시작하지 않습니다.
- 헤드리스 종단 시험은 이 구성에 Rust 테스트 클라이언트 3개를 붙여 돌립니다([Rust 코어](rust-core.md) 10장).
- 알파 서버는 이 구성에서 SeaweedFS를 빼고(NCP Object Storage를 씀) Caddy를 더한 `infra/alpha/compose.yml`로 돌립니다.

## 5. CI/CD

파트마다 워크플로를 따로 두고, 바뀐 경로에 해당하는 것만 돌립니다.

| 워크플로 | 경로 | 러너 | 단계 |
| --- | --- | --- | --- |
| `core` | `core/**` | Ubuntu, macOS | fmt, clippy, 테스트, 안드로이드 타깃 빌드(Ubuntu), XCFramework 빌드(macOS), 크기 기록, 벡터 게시 |
| `ios` | `apps/ios/**`, 코어 산출물 | macOS | 빌드, 단위 테스트, 시뮬레이터 UI 테스트, M1부터 TestFlight |
| `android` | `apps/android/**`, 코어 산출물 | Ubuntu | 빌드, 단위 테스트, 에뮬레이터 테스트, M1부터 내부 테스트 트랙 |
| `api` | `server/api/**`, `core/vectors/**` | Ubuntu | ktlint, 테스트(Testcontainers), 이미지 빌드·검사 |
| `gateway` | `server/gateway/**` | Ubuntu(Redis 서비스) | format, credo, 테스트, 이미지 빌드·검사 |
| `e2e` | `core/**`, `server/**`, `infra/docker-compose.yml` | Ubuntu | compose 띄우기, 헤드리스 종단 시험 |
| `terraform` | `infra/terraform/**` | Ubuntu | `fmt`, 루트마다 `init`(백엔드 없이)·`validate`. M1부터 PR에 `plan`, 머지 뒤 `apply` |
| `deploy` | `main` 머지 | Ubuntu | 스테이징 자동 배포, 운영은 수동 승인 |

배포 순서:
1. **마이그레이션:** Flyway를 별도 작업으로 먼저 돌립니다. 마이그레이션은 이전 버전 API와 함께 돌아가는 형태만 씁니다(추가 먼저, 제거는 다음 배포).
2. **API:** 롤링으로 바꿉니다.
3. **게이트웨이:** 순차 종료로 바꿉니다([실시간 게이트웨이](gateway.md) 6장).

클라우드 인증: NCP에서 GitHub Actions OIDC 연동을 찾지 못했고, Terraform 제공자도 API 키(`NCLOUD_ACCESS_KEY`, `NCLOUD_SECRET_KEY`)로 인증합니다. 그래서 배포 전용 Sub Account의 키를 최소 권한으로 만들어 GitHub 비밀값에 두고 분기마다 교체합니다.

## 6. 확장 조건

지금 넣지 않는 것과, 넣는 조건입니다.

| 구성 | 넣는 조건 | 그때 할 일 |
| --- | --- | --- |
| VM 키우기(4 vCPU·8GB) | CPU 60% 또는 메모리 80%가 일주일 넘게 지속 | 서버 타입 변경(재시작 몇 분) |
| 관리형 PostgreSQL·캐시와 이중화 | 월 순매출이 관리형 최소 비용(약 41만 원)을 넘거나, 사용자 5천 명, 또는 VM 장애로 1시간 넘게 멈춘 일이 생김 | 2장 표의 구성으로 Terraform 이전. 점검 공지 뒤 쓰기를 잠깐 멈추고 덤프·복원으로 옮김 |
| 게이트웨이 노드 추가 | 노드당 동시 연결 4만 초과 | 노드 추가(공유 상태 없음) |
| API 인스턴스 추가 | CPU 60% 지속 또는 p95 500ms 초과 | 인스턴스 추가 |
| PostgreSQL 읽기 복제본 | 따라잡기 조회가 주 DB 부하의 절반 이상 | `GET /groups/{id}/log`를 복제본으로 |
| Redis 클러스터 | 초당 PUBLISH 5만 초과 | 기기 채널을 해시로 분산 |
| Kafka | 같은 이벤트를 다시 읽어야 하는 소비자가 셋 이상이거나 초당 메시지가 만 건대 | 커밋 뒤 이벤트를 Kafka로, 게이트웨이·푸시를 소비자로 |
| ScyllaDB | 30일 우편함이 수 TB를 넘거나 초당 쓰기가 만 건 이상 | 로그 테이블을 옮김. 삭제가 툼스톤으로 남는 점을 처리방침에 반영 |

## 7. 용량 추정

가정: 일 사용자 100만 명, 1인당 그룹 3개, 하루 일정 수정 10번, 채팅 30개, 사진 0.5장(300KB), 앱 사용 30분.

| 항목 | 추정 | 근거 |
| --- | --- | --- |
| 메시지 쓰기 | 평균 초당 약 350건 | 하루 3천만 건 |
| 로그 보관(30일) | 약 230GB | 메시지당 254바이트(실측) × 30일 |
| 가용 시간 상태 | 약 1.1GB(고정) | 100만 × 3그룹 × 376바이트(실측). 덮어쓰기라 늘지 않음 |
| 상태 쓰기 | 평균 초당 350건 이하 | 디바운스로 더 줄어듦 |
| 동시 연결 | 평균 약 2만, 피크 약 6만 | 100만 × 30분 ÷ 1,440분, 피크 3배 |
| 채팅 사진(30일) | 약 4.5TB | 하루 150GB |
| 개인 일정 첨부(1년) | 약 20TB | 1인 평균 20MB 가정(파일당 10MB, 일정당 3개) |
| 백업 | 수 TB | 1인당 수 MB × 3개 |

이 규모까지는 PostgreSQL 한 대(복제본 포함)와 Redis 하나로 충분하다는 것이 6장 조건의 근거입니다.

## 8. 관측과 경보

- **지표:** OpenTelemetry로 모아 Prometheus·Grafana(또는 공급자의 관리형)로 봅니다.
- **로그:** JSON 구조 로그만 남깁니다. 본문·암호문·토큰은 남기지 않습니다.
- **경보:**
  - API 5xx 비율이 5분간 1% 초과
  - API p95가 500ms 초과
  - 409·410 비율이 평소의 3배
  - 푸시 실패율 5% 초과
  - 게이트웨이 연결 수가 10분 안에 30% 넘게 감소
  - Redis 연결 끊김
  - DB 저장 공간 80% 초과
  - 파티션 작업 실패

## 9. 데이터 보존과 삭제

| 데이터 | 위치 | 보존 | 계정 삭제 시 |
| --- | --- | --- | --- |
| 계정·기기·세션·키 패키지 | PostgreSQL | 탈퇴까지 | 즉시 삭제. 계정 행은 식별 정보를 지운 자리표시자로 남김(로그의 작성자 자리) |
| 로그(암호문) | PostgreSQL 파티션 | 30일 | 지우지 않고 30일 파티션과 함께 사라짐. 남은 멤버의 그룹 상태가 갈라지지 않게([공통 계약](protocol.md) 13장) |
| 상태 블롭 | PostgreSQL | 최신값 하나 | 즉시 삭제 |
| 채팅 첨부 | `media-chat` | 30일 | 삭제 큐에 넣어 10분 안에 삭제 |
| 약속 첨부 | `media-appt` | 1년 | 같음 |
| 개인 일정 첨부 | `media-personal` | 올린 날부터 1년(그 전에 지우면 바로) | 같음 |
| 그룹 커버·프로필 사진 | `group-assets` | 바뀌면 7일 뒤 삭제 | 내 프로필 사진은 삭제, 커버는 그룹과 함께 |
| 백업 | 오브젝트 스토리지 | 최근 3개 | 삭제 큐 |
| 신고 증거 | PostgreSQL | 처리 후 기간은 정책으로 정함 | 신고자 연결만 끊김 |
| 접속 로그(로그인·토큰 갱신 시각, 사용자·기기 ID, 접속 IP) | `access-logs`(수명 규칙 92일) | 3개월. 통신비밀보호법 시행령 제41조의 최소 기간을 그대로 씀 | 기간 만료로 삭제 |
| 관리자 접근 기록(클라우드 감사 로그, DB 접근 기록) | 감사 로그 저장소 | 2년. 개인정보의 안전성 확보조치 기준 제8조(정보주체 5만 명 이상) | 기간 만료로 삭제 |
| DB 시점 복구(WAL·기본 백업) | `backups`(관리형으로 옮긴 뒤에는 관리형) | 7일 | 7일 뒤 사라짐. 처리방침에 명시 |
| 구독 상태 | PostgreSQL | 탈퇴까지 | 즉시 삭제. 스토어 구독 해지는 사용자가 스토어에서(앱이 안내) |
| 알파 통계 | PostgreSQL | 공개 베타 전에 마이그레이션으로 테이블째 삭제 | 즉시 삭제 |

## 10. 비밀값

모두 관리형 비밀 저장소에 두고, 저장소(git)에는 넣지 않습니다.

| 비밀값 | 쓰는 곳 | 교체 |
| --- | --- | --- |
| APNs 인증 키(.p8) | API 푸시 | 노출 시 |
| FCM 서비스 계정 | API 푸시 | 연 1회 |
| 카카오 앱 키 | API, 앱 | 노출 시 |
| Apple 로그인 키(Services ID) | API | 연 1회 |
| JWT 서명 키(ES256) | API 발급, 게이트웨이 검증(JWKS) | 분기 1회, `kid`로 겹쳐 교체 |
| 신고 검토 키 | 공개키는 앱 설정, 개인키는 오프라인 보관 | 노출 시 |
| DB·Redis·스토리지 자격 증명 | API, 게이트웨이 | 분기 1회 |
| 특일 정보 API 키 | API | 만료 시 |
| App Store Connect API 키, Play 업로드 키 | CI | 연 1회 |
| App Store Server API 키, Play 서비스 계정(구독 검증) | API | 연 1회 |
| NCP 배포용 Sub Account 키 | CI(Terraform, 배포) | 분기 1회 |

**비밀이 아닌 식별자:** 위 비밀값과 함께 쓰는 값입니다. 대부분 앱 바이너리와 `assetlinks.json`으로 어차피 드러나고, 드러나지 않는 콘솔 쪽 ID(Play Console 앱 ID, 카카오 앱 ID, SKU)도 콘솔 권한 없이는 쓸 수 없어서 여기 적습니다. 2026년 10월 30일에 콘솔에서 만들거나 확인했습니다.

| 항목 | 값 | 쓰는 곳 |
| --- | --- | --- |
| Apple 팀 ID | `BT6XQAVMKW` | iOS 서명(`DEVELOPMENT_TEAM`), Apple client secret의 `iss` |
| iOS 번들 ID | `app.geunal.ios`. Sign in with Apple의 primary App ID | Apple 아이덴티티 토큰의 `aud`, 카카오 iOS 플랫폼 |
| Apple 서비스 ID | `app.geunal.signin` | 안드로이드 Apple 웹 로그인의 `client_id`(Gradle 속성 `geunalAppleServiceId`), 아이덴티티 토큰의 `aud` |
| App Store Connect 앱 | Apple ID `6828892670`, SKU `geunal-ios` | TestFlight, App Store 링크 |
| 안드로이드 패키지 | `app.geunal`. Play Console 앱 ID `4975396628668320964` | `assetlinks.json`, 카카오 Android 플랫폼 |
| 카카오 앱 | ID `1630200` | 콘솔 주소. 네이티브 앱 키는 위 표의 카카오 앱 키 |

**Play 앱 서명 인증서:** Play가 앱을 만들면서 키 항목 두 개를 만들었습니다. 사용 중인 앱 서명 키와, 이전 앱 서명 키 목록의 "양자 컴퓨팅 대비(베타)" 항목입니다. 뒤의 항목에는 기존 키와 양자 내성 키가 함께 있습니다. 기기가 어느 인증서를 보는지는 첫 내부 테스트 설치에서 확인합니다. 그때까지 `assetlinks.json`에는 Play Console이 주는 스니펫대로 사용 중인 키만 넣고, 카카오에는 세 인증서를 모두 등록해 둡니다.

| 인증서 | SHA-256 | 카카오 키 해시(SHA-1의 Base64) |
| --- | --- | --- |
| 사용 중인 앱 서명 키 | `DE:B8:C0:15:7E:58:20:68:36:81:A1:8D:74:0B:2D:17:6B:FC:AA:67:48:68:9E:5B:25:4B:2A:53:9C:52:1E:B8` | `9YZSlCgb3re5mv2JYPLralM208Y=` |
| 양자 컴퓨팅 대비 항목의 기존 키 | `B2:68:52:E7:81:4C:98:D4:7A:A8:9B:9E:4B:D3:C5:F5:D0:17:4F:93:07:E4:19:C2:B3:65:D2:0D:83:B5:F7:56` | `LlWqBXcpOnm3IrBdbiFoc0lOjYQ=` |
| 같은 항목의 양자 내성 키 | `8B:F4:2D:D7:47:39:95:0B:F9:19:CC:85:74:F7:5F:4F:F1:6F:B1:33:80:92:CF:AD:29:BB:73:5C:EB:6A:37:B1` | `z9R88P9Q/yIk1PXjfk75xSVpIYg=` |

- **디버그 빌드:** 빌드한 PC의 디버그 키(`~/.android/debug.keystore`)로 서명됩니다. 그래서 디버그 빌드로 카카오 로그인을 시험하는 PC마다 그 키 해시를 카카오 디벨로퍼스 › 앱 › 플랫폼 키 › 네이티브 앱 키에 더합니다. 키 해시는 `keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore -storepass android | openssl sha1 -binary | openssl base64`로 구합니다. CI는 더하지 않습니다. 러너마다 디버그 키가 새로 생기고, CI의 테스트는 카카오 서버에 묻지 않습니다.
- **업로드 키로 서명한 릴리스 빌드:** Play를 거치지 않고 설치해 시험할 때만 업로드 키의 키 해시도 더합니다.
- **도메인을 정한 뒤 콘솔에 등록할 것:** Apple 서비스 ID의 웹 인증 구성(도메인, 반환 주소 `https://<API 호스트>/auth/apple/callback`), App ID의 Sign in with Apple 서버 간 알림 주소(동의 철회 알림 등), 카카오 연결 끊기 알림 주소입니다. 두 알림의 경로는 백엔드가 [API 서버](api-server.md) 7장에 정합니다.

## 11. 완료 조건

| 단계 | 완료 조건 |
| --- | --- |
| M0 | 로컬 compose, 파트별 CI, `e2e` 워크플로 녹색 |
| M1 | Terraform으로 만든 알파 서버(무료 Micro), 비밀값 저장소, 이미지 검사, 마이그레이션 작업, 매일 DB 덤프, 접속 로그 3개월 자동 삭제 |
| M2 | 알파 서버 증설(2 vCPU·4GB), 스테이징(Micro), 푸시 키 연결, 모니터링과 경보 |
| M3 | 버킷별 수명 규칙, 게이트웨이 부하 시험 |
| M4 | 운영 VM(Terraform), WAL 시점 복구 연습 1회, 장애 대응 절차, 월 비용 경보 |
| M5 | 6장 확장 조건 감시, Micro 무료 기간 끝 처리, 서비스를 끝내면 자원 반납([직군별 할 일](../plan.md) M5) |
