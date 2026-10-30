# Alpha environment (infra.md §1 and §2): one VPC, one VM running the compose stack from infra/alpha, and the
# buckets the stage needs. M0 only validates this root; M1 applies it once the NCP account and key exist.

module "vpc" {
  source = "../../modules/vpc"

  name = "${var.prefix}-vpc"
  cidr = "10.10.0.0/16"
  zone = var.zone

  public_subnets = {
    "${var.prefix}-public" = "10.10.1.0/24"
  }

  # Empty until the managed database and cache (infra.md §6) need a subnet without an internet route.
  private_subnets = {}
}

module "server" {
  source = "../../modules/server"

  name        = "${var.prefix}-app"
  description = "API, PostgreSQL and Valkey under Compose, Caddy for TLS"
  vpc_no      = module.vpc.vpc_no
  subnet_no   = module.vpc.public_subnet_nos["${var.prefix}-public"]
  image_name  = var.server_image_name
  hypervisor  = var.server_hypervisor
  spec_code   = var.server_spec_code
  public_ip   = true

  # NCP refuses an hourly plan for Micro specs; the free year (infra.md §2) is billed as a monthly plan at zero.
  fee_system_type_code = var.server_fee_system_type_code

  # Caddy answers 80 (ACME, redirect) and 443; SSH only from the operator's address (infra.md §2).
  inbound_rules = [
    { protocol = "TCP", ip_block = "0.0.0.0/0", port_range = "80", description = "HTTP" },
    { protocol = "TCP", ip_block = "0.0.0.0/0", port_range = "443", description = "HTTPS" },
    { protocol = "TCP", ip_block = var.admin_cidr, port_range = "22", description = "SSH from the operator" },
  ]
}

module "bucket" {
  source   = "../../modules/bucket"
  for_each = var.buckets

  name = "${var.prefix}-${each.key}"
}
