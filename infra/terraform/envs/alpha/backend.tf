# State lives in NCP Object Storage through the S3-compatible API, in a bucket of its own: it holds the login key's
# private key and every address, so it is no e2e or media bucket. Locking is the backend's lock file (a conditional
# PUT next to the state), which needs no second service; CI serialises applies with a concurrency group as well.
#
# The bucket `geunal-tfstate` is the one resource made outside Terraform (once, by hand, in M1): the state has to
# exist before the first apply can record anything. The backend reads AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY,
# which M1 sets from the NCP API key pair; nothing here is a credential.
terraform {
  backend "s3" {
    bucket = "geunal-tfstate"
    key    = "alpha/terraform.tfstate"
    region = "kr-standard"

    endpoints = {
      s3 = "https://kr.object.ncloudstorage.com"
    }

    use_path_style = true
    use_lockfile   = true

    # NCP has no STS, IAM or instance metadata behind this endpoint, and its region name is not an AWS one.
    skip_credentials_validation = true
    skip_region_validation      = true
    skip_requesting_account_id  = true
    skip_metadata_api_check     = true
    skip_s3_checksum            = true
  }
}
