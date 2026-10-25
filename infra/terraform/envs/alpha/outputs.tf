output "vpc_no" {
  description = "ID of the alpha VPC."
  value       = module.vpc.vpc_no
}

output "server_public_ip" {
  description = "Address the api. and rt. DNS records point at."
  value       = module.server.public_ip
}

output "server_private_ip" {
  description = "Private address of the app server."
  value       = module.server.private_ip
}

output "bucket_names" {
  description = "Full names of the buckets, by short name."
  value       = { for name, bucket in module.bucket : name => bucket.name }
}

output "server_login_key_private_pem" {
  description = "SSH private key for the app server. `terraform output -raw server_login_key_private_pem` once, then store it outside git."
  value       = module.server.login_key_private_pem
  sensitive   = true
}
