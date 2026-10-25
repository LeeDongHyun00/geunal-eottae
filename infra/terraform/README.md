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

M1에서 확인할 것: NCP Object Storage가 잠금 파일의 조건부 PUT(`If-None-Match`)을 받는지. `terraform plan`이 돌 때 버킷에 `alpha/terraform.tfstate.tflock`이 생기고 끝나면 사라져야 합니다. 받지 않으면 잠금을 끄고 CI의 concurrency 그룹만으로 직렬화합니다.

## M1에서 적용하기

```sh
export NCLOUD_ACCESS_KEY=... NCLOUD_SECRET_KEY=...          # 배포용 Sub Account 키
export AWS_ACCESS_KEY_ID="$NCLOUD_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$NCLOUD_SECRET_KEY"
terraform="$(scripts/tool.sh terraform)"
"$terraform" -chdir=infra/terraform/envs/alpha init
"$terraform" -chdir=infra/terraform/envs/alpha plan -var admin_cidr=<내 IP>/32 -var server_spec_code=<Micro 스펙 코드>
```

이미지 이름과 스펙 코드는 계정이 있어야 조회되므로 M0에는 Micro 코드를 넣지 않았습니다. 다음 데이터 소스로 확인한 뒤 변수로 넘기거나 `variables.tf`의 기본값을 바꿉니다.

```hcl
data "ncloud_server_specs" "all" { output_file = "specs.json" }
data "ncloud_server_image_numbers" "all" { output_file = "images.json" }
```

PR에서는 `plan` 결과를 검토하고, 머지한 뒤 적용합니다(인프라 2장). 적용 워크플로는 M1에서 더합니다.

## 아직 없는 것

- 버킷 수명 규칙(`media-chat` 31일, `access-logs` 92일 등): 제공자 4.0.7에 리소스가 없습니다. M3에서 S3 API로 넣습니다(후보: `aws` 제공자를 NCP 엔드포인트에 붙여 `aws_s3_bucket_lifecycle_configuration`을 쓰거나, 적용 워크플로의 한 단계로 `PutBucketLifecycleConfiguration` 호출).
- Secret Manager, Global DNS, Certificate Manager, 로드 밸런서, 관리형 DB·캐시: 각 단계에서 모듈을 더합니다(인프라 11장).
