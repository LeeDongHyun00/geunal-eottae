# Terraform

NCP 자원을 코드로 둡니다([인프라](../../docs/parts/infra.md) 2장). M0에서는 뼈대만 두고 `make lint-terraform`으로 형식과 유효성을 확인합니다. 적용(`plan`, `apply`)은 NCP 계정과 배포용 키가 생기는 M1부터 합니다.

## 구조

```
infra/terraform/
  modules/
    vpc/      # VPC 하나와 공개·사설 서브넷
    server/   # VM 하나: 로그인 키, ACG와 규칙, 네트워크 인터페이스, 공인 IP, 초기화 스크립트
    bucket/   # 비공개 Object Storage 버킷 하나
  envs/
    alpha/    # 알파 환경 루트. 모듈을 엮고 제공자·백엔드를 정함. 잠금 파일(.terraform.lock.hcl)은 여기에만
```

- 모듈은 제공자 버전의 하한만 두고(`~> 4.0`), 루트가 정확한 버전(`4.0.7`)과 Terraform 버전(`1.17.0`)을 고정합니다.
- Terraform 바이너리는 `scripts/tool.sh terraform`이 체크섬을 확인해 `.tools/`에 둡니다. CI도 같은 것을 씁니다.
- 환경을 더할 때(스테이징, 운영)는 `envs/<환경>/`을 만들고 백엔드의 `key`만 바꿉니다. `make lint-terraform`은 `envs/*`를 모두 검사합니다.

## 상태 파일

- 위치: NCP Object Storage 버킷 `geunal-tfstate`, 키 `<환경>/terraform.tfstate`. S3 호환 API를 쓰므로 Terraform의 `s3` 백엔드로 붙습니다(`envs/alpha/backend.tf`).
- 잠금: 백엔드의 잠금 파일(`use_lockfile`). 상태 옆에 `.tflock`을 조건부 PUT으로 만들어, 별도 서비스 없이 동시 적용을 막습니다. CI는 그와 별개로 concurrency 그룹으로 적용을 한 줄로 세웁니다.
- 버킷 `geunal-tfstate`는 Terraform 밖에서 한 번 손으로 만듭니다(첫 적용 전에 상태를 둘 곳이 있어야 하므로, "콘솔에서 만들지 않음" 규칙의 유일한 예외). 비공개로 두고, 버전 관리를 켤 수 있으면 켭니다.
- 백엔드 자격 증명은 `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` 환경 변수입니다. NCP의 API 키 쌍을 그대로 넣습니다. 제공자는 `NCLOUD_ACCESS_KEY`, `NCLOUD_SECRET_KEY`를 읽습니다. 네 값 모두 저장소와 로그에 넣지 않습니다.
- 상태에는 서버 로그인 키의 개인키가 들어 있습니다(NCP가 키 쌍을 만들어 주는 방식이라 피할 수 없음). 그래서 상태 버킷은 비공개이고, 민감한 출력은 `sensitive`로 표시합니다.

M1에서 확인한 것(2026년 10월):
- NCP Object Storage는 조건부 PUT(`If-None-Match: *`)을 받습니다. 없는 키에는 200, 있는 키에는 412를 돌려주고, `plan` 동안 `alpha/terraform.tfstate.tflock`이 생겼다가 끝나면 사라집니다.
- 다만 Terraform 1.17의 AWS SDK는 `skip_s3_checksum`을 켜도 잠금 파일을 CRC32 트레일러가 붙은 스트리밍 업로드(`STREAMING-UNSIGNED-PAYLOAD-TRAILER`)로 보내고, NCP는 이를 403으로 거부합니다. 그래서 `AWS_REQUEST_CHECKSUM_CALCULATION=when_required`와 `AWS_RESPONSE_CHECKSUM_VALIDATION=when_required`를 함께 줍니다(아래 명령, CI도 같음).
- 버킷 버전 관리(`PUT ?versioning`)는 배포용 Sub Account 키로 403이 납니다. 상태 버킷은 버전 관리 없이 씁니다.

## M1에서 적용하기

배포용 Sub Account `geunal-deploy`에는 `NCP_VPC_MANAGER`, `NCP_VPC_SERVER_MANAGER`, `NCP_OBJECT_STORAGE_MANAGER`만 붙입니다. 키 쌍은 저장소 밖의 파일(예: `~/.ncloud/geunal-deploy.env`, 권한 600)에 `NCLOUD_*`과 같은 값의 `AWS_*`로 둡니다.

```sh
set -a; . ~/.ncloud/geunal-deploy.env; set +a                 # NCLOUD_* 와 AWS_* 네 값
export AWS_REQUEST_CHECKSUM_CALCULATION=when_required AWS_RESPONSE_CHECKSUM_VALIDATION=when_required
terraform="$(scripts/tool.sh terraform)"
"$terraform" -chdir=infra/terraform/envs/alpha init -lockfile=readonly
"$terraform" -chdir=infra/terraform/envs/alpha plan -var admin_cidr=<내 IP>/32 -out=alpha.tfplan
```

- 스펙과 이미지는 2026년 10월에 데이터 소스(`ncloud_server_specs`, `ncloud_server_image_numbers`)로 확인한 값이 기본값입니다: `mi1-g3`(KVM Micro, 1 vCPU·1GB), `ubuntu-24.04-base`(KVM).
- Micro는 시간 요금제로 만들 수 없어서(`MICRO servers cannot be created on a time plan`) 월 요금제(`FXSUM`)로 만듭니다. 무료 1년 동안은 월 요금제로 0원이 청구됩니다. M2에서 2 vCPU 서버로 옮길 때는 `server_fee_system_type_code`를 `MTRAT`(시간)으로 바꿉니다.
- 로그인 키는 SSH 키가 아닙니다. NCP는 이 키로 관리자(root) 비밀번호를 암호화해 줄 뿐이고, 서버의 SSH는 비밀번호로 엽니다. 그래서 첫 로그인은 콘솔의 "관리자 비밀번호 확인"으로 비밀번호를 받아 운영자 공개키를 넣은 뒤(`ssh-copy-id`, 비밀번호 프롬프트는 120초 안에 답해야 함), 비밀번호 로그인을 끕니다. 끄는 설정은 `/etc/ssh/sshd_config.d/00-geunal.conf`(`PasswordAuthentication no`, `KbdInteractiveAuthentication no`, `PermitRootLogin prohibit-password`)에 둡니다. sshd는 먼저 읽은 값을 쓰므로, 비밀번호를 켜는 `50-cloud-init.conf`보다 앞서야 합니다.
- Micro의 기본 디스크는 10GB이고 OS가 4GB를 씁니다. 스왑은 없습니다. compose를 올리기 전에 스왑 2GB(인프라 2장)를 만들고, 디스크가 모자라면 블록 스토리지를 붙입니다.

PR에서는 `plan` 결과를 검토하고, 머지한 뒤 적용합니다(인프라 2장). 적용 워크플로는 M1에서 더합니다.

## 아직 없는 것

- 버킷 수명 규칙(`media-chat` 31일, `access-logs` 92일 등): 제공자 4.0.7에 리소스가 없습니다. M3에서 S3 API로 넣습니다(후보: `aws` 제공자를 NCP 엔드포인트에 붙여 `aws_s3_bucket_lifecycle_configuration`을 쓰거나, 적용 워크플로의 한 단계로 `PutBucketLifecycleConfiguration` 호출).
- Secret Manager, Global DNS, Certificate Manager, 로드 밸런서, 관리형 DB·캐시: 각 단계에서 모듈을 더합니다(인프라 11장).
