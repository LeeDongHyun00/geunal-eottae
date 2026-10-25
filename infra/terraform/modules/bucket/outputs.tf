output "name" {
  description = "Bucket name, which is also its ID."
  value       = ncloud_objectstorage_bucket.this.bucket_name
}

output "creation_date" {
  description = "When the bucket was created."
  value       = ncloud_objectstorage_bucket.this.creation_date
}
