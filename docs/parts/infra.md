# 인프라

클라우드 구성, 데이터 저장소, 배포, CI, 관측, 확장 조건을 다룹니다. 서버 두 개([API 서버](api-server.md), [실시간 게이트웨이](gateway.md))와 앱 빌드가 이 위에서 돕니다.

## 1. 환경

| 환경 | 목적 | 구성 |
| --- | --- | --- |
| 로컬 | 개발, 헤드리스 종단 시험, 앱 UI 테스트의 백엔드 | Docker Compose(4장) |
| 스테이징 | 알파 테스트, 부하 시험 | 운영과 같은 구성, 작은 크기 |
| 운영 | 공개 베타부터 | 2장 |

## 2. 운영 구성 요구사항

Supabase 같은 BaaS는 쓰지 않고, 클라우드의 기본 구성 요소(네트워크, 컨테이너 실행, 관리형 데이터베이스·캐시·스토리지) 위에 서버를 직접 구성합니다. 서버가 MLS 커밋 순서·우편함·푸시를 직접 처리해야 하고, 무엇을 얼마나 남길지도 우리가 정해야 하기 때문입니다.

공급자는 미결정입니다([개요](../kickoff.md) 10장). 국내 리전을 전제로, 공급자와 상관없이 필요한 조건만 적습니다.

| 구성 요소 | 요구사항 |
| --- | --- |
| PostgreSQL | 관리형, 16 이상, 저장 시 암호화, 시점 복구(PITR) 7일, 다중 영역 자동 장애 조치 |
| Redis | 관리형, 7 이상. pub/sub과 레이트 리밋만 쓰므로 영속화는 필요 없음 |
| 오브젝트 스토리지 | S3 호환. 버킷 `media`(수명 규칙 30일), `backups`. 공개 접근 차단, 서명된 URL로만 접근 |
| 컨테이너 실행 | API 2대 이상, 게이트웨이 2대 이상(운영 3대), 롤링 배포 |
| 로드 밸런서 | HTTPS, WebSocket, 유휴 제한 120초 이상. 도메인 `api.`, `rt.`, 초대 링크 |
| 비밀값 | 관리형 비밀 저장소(10장) |
| 초대 링크 도메인 | `/.well-known/apple-app-site-association`과 `/.well-known/assetlinks.json`을 API가 제공. `/i/<code>`는 앱이 없으면 스토어로 보냄 |
| 구성 코드 | Terraform. 콘솔에서 손으로 만들지 않음. PR에서 `terraform plan` 결과를 검토하고 머지한 뒤 적용. 상태 파일은 잠금이 되는 원격 저장소에 |
| 계정과 권한 | 스테이징과 운영은 클라우드 계정(또는 프로젝트)을 나눔. 사람은 SSO와 MFA로 들어오고, 운영 쓰기 권한은 최소 인원만. CI는 OIDC(5장) |
| 네트워크 | 데이터베이스·Redis·컨테이너는 사설 서브넷. 인터넷에 열린 것은 로드 밸런서뿐. 서버 접속은 SSH 대신 공급자의 세션 관리 기능으로, 기록이 남게 |
| 감사 기록 | 클라우드 콘솔·API 감사 로그와 데이터베이스 접근 기록(보존은 9장) |

CDN은 두지 않습니다. 미디어는 암호문이고, 서명된 URL로 스토리지에서 바로 받습니다.

**호스팅 위치:** 서버를 어느 나라에 두든 운영사에는 한국 법(통신비밀보호법의 보관·협조 의무, 개인정보 보호법)이 그대로 적용됩니다. 해외에 두면 개인정보 국외 이전 고지가 더해지고, 그 나라의 법이 하나 더 붙습니다. 그래서 위치는 지연과 운영으로 고르고, 보호는 종단간 암호화와 보관 최소화로 합니다. 2026년 10월에 검토한 해외 후보는 다음과 같습니다.

| 후보 | 그 나라의 통신 기록 보관 의무 | AWS·Azure·Google Cloud 리전 | 서울과 왕복 지연 |
| --- | --- | --- | --- |
| 말레이시아 | 일반 의무를 찾지 못함. 자국 사용자 800만 명 이상 메신저·SNS는 2026년부터 면허 체계 적용 | AWS, Azure | 69ms |
| 스위스 | 통신사는 6개월. 앱 사업자로 넓히는 VÜPF 개정은 2025년 의견수렴에서 반대가 많아 재검토 중 | 셋 다 있음(국내 업체 Exoscale도 있음) | 228ms |
| 루마니아 | 2014년 위헌 결정 뒤 없음. EU 차원의 보관 법안(메신저 포함)이 준비 중 | 없음 | 240~260ms(추정) |
| 아이슬란드 | 통신사업자 6개월(전자통신법 2022년 70호 89조) | 없음 | 260~280ms(추정) |
| 파나마 | 통신·인터넷 사업자 6개월, 법원 요청 시 6개월 연장(2009년 법률 51호) | 없음 | 200ms 이상(추정) |

- 지연은 Azure 리전 간 왕복 중앙값(2026년 7월 자료)입니다. 국내 리전 간은 8ms, 도쿄는 30ms입니다. 리전이 없는 나라는 가까운 리전 값으로 어림했습니다.
- 대형 클라우드 리전이 없는 곳에서는 관리형 PostgreSQL·Redis와 다중 영역 장애 조치를 쓸 수 없어서 위 요구사항을 맞추지 못합니다.
- 근거: [스위스](https://www.inside-it.ch/vuepf-revision-muss-strafrunde-drehen-20260211), [루마니아](https://privacy.apti.ro/decizia-curtii-constitutionale-date-trafic/), [EU](https://www.heise.de/en/news/Data-Retention-Commission-to-present-proposal-by-mid-2026-11101430.html), [아이슬란드](https://pub.norden.org/temanord2024-532/8-iceland.html), [파나마](https://www.organojudicial.gob.pa/uploads/wp_repo/blogs.dir/cendoj/novedades_normativas/ley51_2009.pdf), [말레이시아](https://www.mcmc.gov.my/skmmgovmy/media/General/pdf2/MEDIA-STATEMENT_Internet-Messaging-and-Social-Media-Service-Providers-Deemed-Registered-Effective-1-January-2026_15-Dec-2025.pdf), [지연](https://learn.microsoft.com/en-us/azure/networking/azure-network-latency)

## 3. 컨테이너 이미지

| 이미지 | 기반 | 메모 |
| --- | --- | --- |
| `api` | JRE 21 슬림 이미지 | Spring Boot 계층 jar, 루트가 아닌 사용자, 읽기 전용 파일 시스템, `/actuator/health` |
| `gateway` | Debian 슬림 + `mix release` | 루트가 아닌 사용자, 상태 확인 엔드포인트 |

- 태그는 git 커밋 SHA로 붙이고, 한 번 올린 태그는 바꾸지 않습니다.
- CI에서 이미지 취약점 검사(Trivy)를 돌리고, 심각도 높음 이상이면 배포를 막습니다.
- 운영의 PostgreSQL·Redis·오브젝트 스토리지는 컨테이너로 돌리지 않고 관리형을 씁니다. 상태가 있는 서비스를 직접 운영하지 않기 위해서입니다.

## 4. 로컬 실행

MinIO는 2025년 10월부터 커뮤니티 이미지를 내지 않아서, 로컬 S3는 SeaweedFS로 합니다. API 테스트(Testcontainers)도 같은 이미지를 씁니다. 아래 파일은 `docker compose config`로 문법을 확인했습니다.

```yaml
# infra/docker-compose.yml — everything the apps and the core's end-to-end test need on one machine.
name: geunal

services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: geunal
      POSTGRES_USER: geunal
      POSTGRES_PASSWORD: geunal-local
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U geunal"]
      interval: 5s
      retries: 20

  redis:
    image: redis:7
    ports: ["6379:6379"]

  # MinIO stopped publishing community images in 2025; SeaweedFS serves the S3 API locally. Pin the tag in M0.
  s3:
    image: chrislusf/seaweedfs
    command: server -s3 -dir=/data
    ports: ["8333:8333"]

  api:
    build: ../server/api
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/geunal
      SPRING_DATASOURCE_USERNAME: geunal
      SPRING_DATASOURCE_PASSWORD: geunal-local
      SPRING_DATA_REDIS_HOST: redis
      GEUNAL_S3_ENDPOINT: http://s3:8333
      GEUNAL_AUTH_STUB: "true"   # M0: sign in without Kakao or Apple
    ports: ["8080:8080"]
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_started }
      s3: { condition: service_started }

  gateway:
    build: ../server/gateway
    environment:
      JWKS_URL: http://api:8080/.well-known/jwks.json
      REDIS_URL: redis://redis:6379
    ports: ["4000:4000"]
    depends_on:
      api: { condition: service_started }
```

- `GEUNAL_AUTH_STUB`은 로컬과 CI에서만 켭니다. 운영 이미지에서는 이 설정이 있으면 API가 시작하지 않습니다.
- 헤드리스 종단 시험은 이 구성에 Rust 테스트 클라이언트 3개를 붙여 돌립니다([Rust 코어](rust-core.md) 10장).

## 5. CI/CD

파트마다 워크플로를 따로 두고, 바뀐 경로에 해당하는 것만 돌립니다.

| 워크플로 | 경로 | 러너 | 단계 |
| --- | --- | --- | --- |
| `core` | `core/**` | Ubuntu, macOS | fmt, clippy, 테스트, 안드로이드 타깃 빌드(Ubuntu), XCFramework 빌드(macOS), 크기 기록, 벡터 게시 |
| `ios` | `apps/ios/**`, 코어 산출물 | macOS | 빌드, 단위 테스트, 시뮬레이터 UI 테스트, M1부터 TestFlight |
| `android` | `apps/android/**`, 코어 산출물 | Ubuntu | 빌드, 단위 테스트, 에뮬레이터 테스트, M1부터 내부 테스트 트랙 |
| `api` | `server/api/**`, `core/vectors/**` | Ubuntu | ktlint, 테스트(Testcontainers), 이미지 빌드·검사 |
| `gateway` | `server/gateway/**` | Ubuntu(Redis 서비스) | format, credo, 테스트, 이미지 빌드·검사 |
| `e2e` | 위 전부 | Ubuntu | compose 띄우기, 헤드리스 종단 시험 |
| `deploy` | `main` 머지 | Ubuntu | 스테이징 자동 배포, 운영은 수동 승인 |

배포 순서:
1. **마이그레이션:** Flyway를 별도 작업으로 먼저 돌립니다. 마이그레이션은 이전 버전 API와 함께 돌아가는 형태만 씁니다(추가 먼저, 제거는 다음 배포).
2. **API:** 롤링으로 바꿉니다.
3. **게이트웨이:** 순차 종료로 바꿉니다([실시간 게이트웨이](gateway.md) 6장).

클라우드 인증은 장기 키 대신 GitHub Actions의 OIDC 연동을 씁니다.

## 6. 확장 조건

지금 넣지 않는 것과, 넣는 조건입니다.

| 구성 | 넣는 조건 | 그때 할 일 |
| --- | --- | --- |
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
| 미디어(30일) | 약 4.5TB | 하루 150GB |
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
| 계정·기기·세션·키 패키지 | PostgreSQL | 탈퇴까지 | 즉시 삭제 |
| 로그(암호문) | PostgreSQL 파티션 | 30일 | 작성한 항목 즉시 삭제 |
| 상태 블롭 | PostgreSQL | 최신값 하나 | 즉시 삭제 |
| 미디어 | 오브젝트 스토리지 | 30일 | 삭제 큐에 넣어 10분 안에 삭제 |
| 백업 | 오브젝트 스토리지 | 최근 3개 | 삭제 큐 |
| 신고 증거 | PostgreSQL | 처리 후 기간은 정책으로 정함 | 신고자 연결만 끊김 |
| 접속 로그(로그인·토큰 갱신 시각, 사용자·기기 ID, 접속 IP) | 로그 저장소 | 3개월. 통신비밀보호법 시행령 제41조의 최소 기간을 그대로 씀 | 기간 만료로 삭제 |
| 관리자 접근 기록(클라우드 감사 로그, DB 접근 기록) | 감사 로그 저장소 | 2년. 개인정보의 안전성 확보조치 기준 제8조(정보주체 5만 명 이상) | 기간 만료로 삭제 |
| DB 시점 복구 | 관리형 | 7일 | 7일 뒤 사라짐. 처리방침에 명시 |

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

## 11. 완료 조건

| 단계 | 완료 조건 |
| --- | --- |
| M0 | 로컬 compose, 파트별 CI, `e2e` 워크플로 녹색 |
| M1 | Terraform으로 만든 스테이징 환경, 비밀값 저장소, 이미지 검사, 마이그레이션 작업, 접속 로그 3개월 자동 삭제 |
| M2 | 푸시 키 연결, 모니터링과 경보 |
| M3 | 오브젝트 스토리지 수명 규칙, 게이트웨이 부하 시험 |
| M4 | 운영 환경(스테이징과 다른 계정, Terraform), 시점 복구 연습 1회, 장애 대응 절차 |
