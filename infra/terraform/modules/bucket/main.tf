# One private Object Storage bucket. The provider manages the bucket and its ACL only; lifecycle rules (infra.md
# §2 and §9) are not in it yet, so they are set through the S3 API when the first bucket needs one (M3), see
# infra/terraform/README.md.
resource "ncloud_objectstorage_bucket" "this" {
  bucket_name = var.name
}

# Explicit, so a change of NCP's default can never make a media or backup bucket public.
resource "ncloud_objectstorage_bucket_acl" "this" {
  bucket_name = ncloud_objectstorage_bucket.this.bucket_name
  rule        = "private"
}
