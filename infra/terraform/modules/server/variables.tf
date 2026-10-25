variable "name" {
  description = "Server name; the key, ACG and network interface are named after it."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,23}[a-z0-9]$", var.name))
    error_message = "Use 3 to 25 lowercase letters, digits and hyphens, starting with a letter, so that the derived names fit NCP's 30-character limit."
  }
}

variable "description" {
  description = "Server description shown in the console."
  type        = string
  default     = null
}

variable "vpc_no" {
  description = "ID of the VPC the ACG belongs to."
  type        = string
}

variable "subnet_no" {
  description = "ID of the subnet the server is placed in."
  type        = string
}

variable "image_name" {
  description = "Server image name as data.ncloud_server_image_numbers lists it, for example ubuntu-24.04-base."
  type        = string
}

variable "hypervisor" {
  description = "Hypervisor the image and spec belong to: KVM (3rd generation) or XEN (2nd generation)."
  type        = string
  default     = "KVM"

  validation {
    condition     = contains(["KVM", "XEN"], var.hypervisor)
    error_message = "hypervisor must be KVM or XEN."
  }
}

variable "spec_code" {
  description = "Server spec code as data.ncloud_server_specs lists it, for example c2-g3 (2 vCPU, 4 GB)."
  type        = string
}

variable "inbound_rules" {
  description = "Inbound ACG rules. Everything not listed is dropped."
  type = list(object({
    protocol    = string
    ip_block    = string
    port_range  = optional(string)
    description = optional(string)
  }))
  default = []

  validation {
    condition     = alltrue([for rule in var.inbound_rules : contains(["TCP", "UDP", "ICMP"], rule.protocol)])
    error_message = "protocol must be TCP, UDP or ICMP."
  }
}

variable "public_ip" {
  description = "Whether to allocate a public IP and attach it to the server."
  type        = bool
  default     = false
}

variable "init_script" {
  description = "Script run once at first boot (must start with a shebang). null for none."
  type        = string
  default     = null
}

variable "protect_termination" {
  description = "Refuse to return the server from the console or API until this is turned off."
  type        = bool
  default     = true
}
