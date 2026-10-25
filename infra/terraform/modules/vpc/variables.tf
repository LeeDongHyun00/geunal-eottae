variable "name" {
  description = "VPC name. NCP allows 3 to 30 lowercase letters, digits and hyphens, starting with a letter."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,28}[a-z0-9]$", var.name))
    error_message = "Use 3 to 30 lowercase letters, digits and hyphens, starting with a letter and ending with a letter or digit."
  }
}

variable "cidr" {
  description = "IPv4 CIDR of the VPC, /16 to /28 inside a private range."
  type        = string

  validation {
    condition     = can(cidrnetmask(var.cidr)) && tonumber(split("/", var.cidr)[1]) >= 16 && tonumber(split("/", var.cidr)[1]) <= 28
    error_message = "The VPC CIDR must be a valid IPv4 block between /16 and /28."
  }
}

variable "zone" {
  description = "Zone every subnet is placed in. One zone is enough until the managed, multi-zone setup (infra.md §6)."
  type        = string
  default     = "KR-2"
}

variable "public_subnets" {
  description = "Public subnets by name: servers here get a public IP route. CIDRs must sit inside var.cidr."
  type        = map(string)
  default     = {}
}

variable "private_subnets" {
  description = "Private subnets by name, for the database, cache and servers behind a load balancer later."
  type        = map(string)
  default     = {}
}
