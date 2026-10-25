# The root pins exact versions (CLAUDE.md, version pinning). Terraform itself is the one scripts/tool.sh installs, so
# a run here and in CI uses the same binary; .terraform.lock.hcl holds the provider checksums for Linux and macOS
# (make lock-terraform regenerates it after a bump).
terraform {
  required_version = "1.17.0"

  required_providers {
    ncloud = {
      source  = "NaverCloudPlatform/ncloud"
      version = "4.0.7"
    }
  }
}
