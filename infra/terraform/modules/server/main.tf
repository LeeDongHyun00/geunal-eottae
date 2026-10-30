# One VM with its own login key, ACG, network interface and optional public IP and init script. Each server gets
# its own ACG so that opening a port for one of them never opens it for another.

# NCP generates the key pair; the private key is only ever in the state (see the output). There is no way to
# register a public key of our own, so the state bucket has to stay private (envs/*/backend.tf).
resource "ncloud_login_key" "this" {
  key_name = "${var.name}-key"
}

resource "ncloud_access_control_group" "this" {
  name        = "${var.name}-acg"
  description = "Ports open on ${var.name}"
  vpc_no      = var.vpc_no
}

# A single rule resource per ACG: the provider overwrites rather than merges several (its docs warn about this).
resource "ncloud_access_control_group_rule" "this" {
  access_control_group_no = ncloud_access_control_group.this.id

  dynamic "inbound" {
    for_each = var.inbound_rules
    content {
      protocol    = inbound.value.protocol
      ip_block    = inbound.value.ip_block
      port_range  = inbound.value.port_range
      description = inbound.value.description
    }
  }

  # Package updates, image pulls, Let's Encrypt, DNS and NTP. Narrowing egress is a later hardening step.
  outbound {
    protocol    = "TCP"
    ip_block    = "0.0.0.0/0"
    port_range  = "1-65535"
    description = "all TCP"
  }
  outbound {
    protocol    = "UDP"
    ip_block    = "0.0.0.0/0"
    port_range  = "1-65535"
    description = "all UDP"
  }
  outbound {
    protocol    = "ICMP"
    ip_block    = "0.0.0.0/0"
    description = "ICMP"
  }
}

resource "ncloud_init_script" "this" {
  count = var.init_script == null ? 0 : 1

  name    = "${var.name}-init"
  content = var.init_script
}

# The image is resolved by name so the root reads as "ubuntu-24.04-base", not as a number that means nothing in a
# review. The lookup pins the hypervisor too, because NCP publishes most images for both XEN and KVM.
data "ncloud_server_image_numbers" "this" {
  server_image_name = var.image_name

  filter {
    name   = "hypervisor_type"
    values = [var.hypervisor]
  }
}

resource "ncloud_network_interface" "this" {
  name                  = "${var.name}-nic0"
  subnet_no             = var.subnet_no
  access_control_groups = [ncloud_access_control_group.this.id]
}

resource "ncloud_server" "this" {
  name                          = var.name
  description                   = var.description
  subnet_no                     = var.subnet_no
  server_image_number           = data.ncloud_server_image_numbers.this.image_number_list[0].server_image_number
  server_spec_code              = var.spec_code
  fee_system_type_code          = var.fee_system_type_code
  login_key_name                = ncloud_login_key.this.key_name
  init_script_no                = var.init_script == null ? null : ncloud_init_script.this[0].id
  is_protect_server_termination = var.protect_termination

  network_interface {
    network_interface_no = ncloud_network_interface.this.id
    order                = 0
  }
}

resource "ncloud_public_ip" "this" {
  count = var.public_ip ? 1 : 0

  server_instance_no = ncloud_server.this.id
  description        = "Public IP of ${var.name}"
}
