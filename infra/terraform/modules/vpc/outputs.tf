output "vpc_no" {
  description = "ID of the VPC."
  value       = ncloud_vpc.this.id
}

output "cidr" {
  description = "IPv4 CIDR of the VPC."
  value       = ncloud_vpc.this.ipv4_cidr_block
}

output "default_network_acl_no" {
  description = "ID of the network ACL NCP created with the VPC."
  value       = ncloud_vpc.this.default_network_acl_no
}

output "default_access_control_group_no" {
  description = "ID of the ACG NCP created with the VPC."
  value       = ncloud_vpc.this.default_access_control_group_no
}

output "public_subnet_nos" {
  description = "Public subnet IDs by name."
  value       = { for name, subnet in ncloud_subnet.public : name => subnet.id }
}

output "private_subnet_nos" {
  description = "Private subnet IDs by name."
  value       = { for name, subnet in ncloud_subnet.private : name => subnet.id }
}
