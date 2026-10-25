# Modules set a floor only; the root that calls them pins the exact version and carries the lock file.
terraform {
  required_providers {
    ncloud = {
      source  = "NaverCloudPlatform/ncloud"
      version = "~> 4.0"
    }
  }
}
