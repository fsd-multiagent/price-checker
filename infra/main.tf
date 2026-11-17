# Production: one Linux VM in an EU region behind an NSG that allows only 80
# and 443 (I-3, ADR 0015). SSH has no standing allow rule; deploy workflows
# open a just-in-time rule for the runner's /32 (ADR 0018).
#
# Deliberately absent (AD-14, ADR 0017, S-9): NSG flow logs, Network Watcher,
# diagnostic settings and VM boot diagnostics, since they would hold client IPs.

locals {
  name = "price-checker-${var.env}"

  tags = {
    project = "price-checker"
    env     = var.env
  }

  admin_username = "pcadmin"
}

resource "azurerm_resource_group" "main" {
  name     = "rg-${local.name}"
  location = var.location
  tags     = local.tags
}

resource "azurerm_virtual_network" "main" {
  name                = "vnet-${local.name}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  address_space       = ["10.20.0.0/16"]
  tags                = local.tags
}

resource "azurerm_subnet" "app" {
  name                 = "snet-app"
  resource_group_name  = azurerm_resource_group.main.name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = ["10.20.1.0/24"]
}

# Rules are inline so that `terraform plan` shows any leaked `jit-ssh-*` rule
# as drift to remove (ADR 0018).
resource "azurerm_network_security_group" "app" {
  name                = "nsg-${local.name}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags

  security_rule {
    name                       = "allow-http-redirect"
    description                = "Port 80 for the HTTPS redirect and ACME HTTP-01 (S-13)."
    priority                   = 200
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "80"
    source_address_prefix      = "Internet"
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "allow-https"
    description                = "HTTPS served by Caddy (S-13)."
    priority                   = 210
    direction                  = "Inbound"
    access                     = "Allow"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "443"
    source_address_prefix      = "Internet"
    destination_address_prefix = "*"
  }

  security_rule {
    name                       = "deny-ssh-internet"
    description                = "Explicit default: SSH only through a just-in-time rule at a lower priority number (ADR 0018)."
    priority                   = 4000
    direction                  = "Inbound"
    access                     = "Deny"
    protocol                   = "Tcp"
    source_port_range          = "*"
    destination_port_range     = "22"
    source_address_prefix      = "Internet"
    destination_address_prefix = "*"
  }
}

resource "azurerm_subnet_network_security_group_association" "app" {
  subnet_id                 = azurerm_subnet.app.id
  network_security_group_id = azurerm_network_security_group.app.id
}

resource "azurerm_public_ip" "app" {
  name                = "pip-${local.name}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  allocation_method   = "Static"
  sku                 = "Standard"
  domain_name_label   = var.dns_label
  tags                = local.tags
}

resource "azurerm_network_interface" "app" {
  name                = "nic-${local.name}"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  tags                = local.tags

  ip_configuration {
    name                          = "primary"
    subnet_id                     = azurerm_subnet.app.id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = azurerm_public_ip.app.id
  }
}

# No boot diagnostics block on purpose (AC 3.1.2, AD-14); CI greps for one.
resource "azurerm_linux_virtual_machine" "app" {
  name                            = "vm-${local.name}"
  location                        = azurerm_resource_group.main.location
  resource_group_name             = azurerm_resource_group.main.name
  size                            = var.vm_size
  admin_username                  = local.admin_username
  disable_password_authentication = true
  network_interface_ids           = [azurerm_network_interface.app.id]
  tags                            = local.tags

  custom_data = base64encode(templatefile("${path.module}/cloud-init.yaml.tftpl", {
    admin_username        = local.admin_username
    deploy_ssh_public_key = trimspace(var.deploy_ssh_public_key)
  }))

  admin_ssh_key {
    username   = local.admin_username
    public_key = trimspace(var.admin_ssh_public_key)
  }

  os_disk {
    name                 = "osdisk-${local.name}"
    caching              = "ReadWrite"
    storage_account_type = "StandardSSD_LRS"
    disk_size_gb         = 30
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "ubuntu-24_04-lts"
    sku       = "server"
    version   = "latest" # Azure Marketplace image version, not a container image tag (I-6 does not apply).
  }

  lifecycle {
    # Changing cloud-init would recreate the VM; roll it out deliberately.
    ignore_changes = [custom_data]
  }
}

resource "azurerm_consumption_budget_resource_group" "main" {
  name              = "budget-${local.name}"
  resource_group_id = azurerm_resource_group.main.id
  amount            = var.budget_amount
  time_grain        = "Monthly"

  time_period {
    start_date = var.budget_start_date
  }

  notification {
    enabled        = true
    operator       = "GreaterThanOrEqualTo"
    threshold      = 80
    threshold_type = "Actual"
    contact_emails = [var.budget_contact_email]
  }

  notification {
    enabled        = true
    operator       = "GreaterThanOrEqualTo"
    threshold      = 100
    threshold_type = "Forecasted"
    contact_emails = [var.budget_contact_email]
  }

  lifecycle {
    ignore_changes = [time_period]
  }
}
