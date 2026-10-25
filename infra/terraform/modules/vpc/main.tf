# One VPC with its subnets. Zone, CIDRs and the public/private split are the caller's; the network ACL is the
# VPC's default one, because traffic is filtered per server by ACGs (modules/server) and a second layer would
# only hide which rule dropped a packet.
resource "ncloud_vpc" "this" {
  name            = var.name
  ipv4_cidr_block = var.cidr
}

resource "ncloud_subnet" "public" {
  for_each = var.public_subnets

  vpc_no         = ncloud_vpc.this.id
  name           = each.key
  subnet         = each.value
  zone           = var.zone
  network_acl_no = ncloud_vpc.this.default_network_acl_no
  subnet_type    = "PUBLIC"
  usage_type     = "GEN"
}

resource "ncloud_subnet" "private" {
  for_each = var.private_subnets

  vpc_no         = ncloud_vpc.this.id
  name           = each.key
  subnet         = each.value
  zone           = var.zone
  network_acl_no = ncloud_vpc.this.default_network_acl_no
  subnet_type    = "PRIVATE"
  usage_type     = "GEN"
}
