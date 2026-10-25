variable "name" {
  description = "Bucket name, unique across the region: 3 to 63 lowercase letters, digits, periods and hyphens."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.name)) && !can(regex("[.][.]", var.name))
    error_message = "Use 3 to 63 lowercase letters, digits, periods and hyphens, starting and ending with a letter or digit, without consecutive periods."
  }
}
