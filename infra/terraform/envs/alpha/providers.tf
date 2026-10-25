# Credentials come from NCLOUD_ACCESS_KEY and NCLOUD_SECRET_KEY in the environment (a GitHub secret in CI, the
# deploy Sub Account's key pair, infra.md §5 and §10). They are never written into a file in this repository.
provider "ncloud" {
  region      = var.region
  site        = "public"
  support_vpc = true
}
