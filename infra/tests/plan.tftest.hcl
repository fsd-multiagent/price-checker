# `terraform test` with a mocked azurerm provider: no Azure credentials and no
# real resources (I-2). Run names carry the acceptance criterion IDs (T-1).

mock_provider "azurerm" {
  mock_resource "azurerm_resource_group" {
    defaults = {
      id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-price-checker-prod"
    }
  }
  mock_resource "azurerm_subnet" {
    defaults = {
      id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-price-checker-prod/providers/Microsoft.Network/virtualNetworks/vnet-price-checker-prod/subnets/snet-app"
    }
  }
  mock_resource "azurerm_network_security_group" {
    defaults = {
      id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-price-checker-prod/providers/Microsoft.Network/networkSecurityGroups/nsg-price-checker-prod"
    }
  }
  mock_resource "azurerm_public_ip" {
    defaults = {
      id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-price-checker-prod/providers/Microsoft.Network/publicIPAddresses/pip-price-checker-prod"
    }
  }
  mock_resource "azurerm_network_interface" {
    defaults = {
      id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-price-checker-prod/providers/Microsoft.Network/networkInterfaces/nic-price-checker-prod"
    }
  }
}

variables {
  # Throwaway public keys; their private halves were deleted at generation.
  admin_ssh_public_key  = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHjH7x/KSvWaeOyWho15rzl5cwEBHosIXAxwMtAFFh2E admin@test"
  deploy_ssh_public_key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEDoX6uI6cQkpWI7ZJtARcOYJnGd2Bb2Q6Pv4nJn8VJx deploy@test"
  budget_contact_email  = "owner@example.com"
}

run "ac_3_1_1_one_linux_vm_in_eu_region" {
  command = plan

  assert {
    condition     = azurerm_linux_virtual_machine.app.location == "westeurope"
    error_message = "VM must default to westeurope."
  }

  assert {
    condition     = azurerm_linux_virtual_machine.app.disable_password_authentication
    error_message = "VM must not allow password authentication."
  }
}

run "ac_3_1_1_public_ip_has_dns_label" {
  command = plan

  assert {
    condition     = azurerm_public_ip.app.domain_name_label == "price-checker-prod"
    error_message = "Public IP must carry the DNS label price-checker-prod."
  }

  assert {
    condition     = azurerm_public_ip.app.allocation_method == "Static" && azurerm_public_ip.app.sku == "Standard"
    error_message = "Public IP must be static Standard SKU."
  }
}

run "ac_3_1_1_nsg_allows_only_80_and_443_and_no_ssh_from_internet" {
  command = plan

  assert {
    condition = toset([
      for r in azurerm_network_security_group.app.security_rule : r.destination_port_range
      if r.access == "Allow" && r.direction == "Inbound"
    ]) == toset(["80", "443"])
    error_message = "NSG must allow inbound 80 and 443 only."
  }

  assert {
    condition = length([
      for r in azurerm_network_security_group.app.security_rule : r
      if r.access == "Allow" && r.destination_port_range == "22"
    ]) == 0
    error_message = "NSG must have no standing rule allowing SSH (ADR 0018)."
  }

  assert {
    condition = length([
      for r in azurerm_network_security_group.app.security_rule : r
      if r.access == "Deny" && r.destination_port_range == "22" && r.source_address_prefix == "Internet"
    ]) == 1
    error_message = "NSG must explicitly deny SSH from the Internet."
  }
}

run "ac_3_1_2_vm_has_no_boot_diagnostics" {
  command = plan

  assert {
    condition     = length(azurerm_linux_virtual_machine.app.boot_diagnostics) == 0
    error_message = "VM must not enable boot diagnostics (AD-14)."
  }
}

run "i_8_resources_are_tagged" {
  command = plan

  assert {
    condition = alltrue([
      for t in [
        azurerm_resource_group.main.tags,
        azurerm_virtual_network.main.tags,
        azurerm_network_security_group.app.tags,
        azurerm_public_ip.app.tags,
        azurerm_network_interface.app.tags,
        azurerm_linux_virtual_machine.app.tags,
      ] : t["project"] == "price-checker" && t["env"] == "prod"
    ])
    error_message = "Every taggable resource must carry project=price-checker and env=prod (I-8)."
  }
}

run "i_3_rejects_non_eu_region" {
  command = plan

  variables {
    location = "eastus"
  }

  expect_failures = [var.location]
}

run "adr_0018_cloud_init_restricts_deploy_key_and_hardens_sshd" {
  command = plan

  assert {
    condition     = strcontains(base64decode(azurerm_linux_virtual_machine.app.custom_data), "restrict,command=\"/usr/local/bin/pc-dispatch\" ssh-ed25519 ")
    error_message = "The deploy key must be limited by restrict and the pc-dispatch forced command."
  }
  assert {
    condition = alltrue([for line in ["PasswordAuthentication no", "PermitRootLogin no", "AllowUsers pcadmin deploy", "AllowTcpForwarding no"] :
    strcontains(base64decode(azurerm_linux_virtual_machine.app.custom_data), line)])
    error_message = "sshd must be key-only, without root login, and limited to the admin and deploy users."
  }
  assert {
    condition     = strcontains(base64decode(azurerm_linux_virtual_machine.app.custom_data), "deploy|kill-switch) ;;")
    error_message = "pc-dispatch must allow only deploy and kill-switch."
  }
}

run "i_8_budget_alert_notifies_owner" {
  command = plan

  assert {
    condition     = azurerm_consumption_budget_resource_group.main.amount == var.budget_amount
    error_message = "The budget amount must be the owner's ceiling."
  }
  assert {
    condition     = length(azurerm_consumption_budget_resource_group.main.notification) == 2
    error_message = "The budget must have the Actual and Forecasted notifications."
  }
  assert {
    condition     = alltrue([for n in azurerm_consumption_budget_resource_group.main.notification : n.enabled && contains(n.contact_emails, var.budget_contact_email)])
    error_message = "Every budget notification must be enabled and mail the owner."
  }
}

run "i_8_rejects_budget_start_not_first_of_month" {
  command = plan

  variables {
    budget_start_date = "2026-12-15T00:00:00Z"
  }

  expect_failures = [var.budget_start_date]
}
