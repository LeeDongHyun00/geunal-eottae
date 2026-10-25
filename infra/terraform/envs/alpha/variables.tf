variable "prefix" {
  description = "Prefix of every resource name. Bucket names are unique per region, so it also tells environments apart."
  type        = string
  default     = "geunal-alpha"
}

variable "region" {
  description = "NCP region code."
  type        = string
  default     = "KR"
}

variable "zone" {
  description = "Zone for the subnets and servers."
  type        = string
  default     = "KR-2"
}

variable "admin_cidr" {
  description = "Address allowed to SSH to the server, as a CIDR (a single address is /32). Set per operator at plan time."
  type        = string

  validation {
    condition     = can(cidrnetmask(var.admin_cidr)) && var.admin_cidr != "0.0.0.0/0"
    error_message = "admin_cidr must be a specific IPv4 CIDR, not 0.0.0.0/0."
  }
}

# The image name and spec code are what NCP lists at plan time, so M1 confirms them with the data sources
# (infra/terraform/README.md) before the first apply. The Micro server (1 vCPU, 1 GB, free for a year) is the M1
# target; M2 moves to 2 vCPU and 4 GB (infra.md §2).
variable "server_image_name" {
  description = "Server image name, as data.ncloud_server_image_numbers lists it."
  type        = string
  default     = "ubuntu-24.04-base"
}

variable "server_hypervisor" {
  description = "Hypervisor of the image and spec: KVM or XEN."
  type        = string
  default     = "KVM"
}

variable "server_spec_code" {
  description = "Server spec code, as data.ncloud_server_specs lists it. No default: the Micro code is confirmed in M1."
  type        = string
}

variable "buckets" {
  description = "Buckets to create, by short name; the prefix is added. M1 needs backups and access-logs (infra.md §9); the media and group-assets buckets come with M2 and M3."
  type        = set(string)
  default     = ["backups", "access-logs"]
}
