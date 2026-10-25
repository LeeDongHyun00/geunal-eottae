output "server_no" {
  description = "ID of the server instance."
  value       = ncloud_server.this.id
}

output "private_ip" {
  description = "Private IP of the first network interface."
  value       = ncloud_server.this.private_ip
}

output "public_ip" {
  description = "Public IP, or null when none was requested."
  value       = var.public_ip ? ncloud_public_ip.this[0].public_ip : null
}

output "access_control_group_no" {
  description = "ID of the server's ACG, for rules on other ACGs that refer to this server."
  value       = ncloud_access_control_group.this.id
}

output "login_key_private_pem" {
  description = "Private key NCP generated for the server. Read it once with `terraform output -raw` and keep it out of git and logs."
  value       = ncloud_login_key.this.private_key
  sensitive   = true
}
