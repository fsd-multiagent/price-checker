output "fqdn" {
  description = "Public DNS name Caddy serves and obtains its certificate for."
  value       = azurerm_public_ip.app.fqdn
}

output "public_ip" {
  description = "Static public IP of the VM."
  value       = azurerm_public_ip.app.ip_address
}

output "resource_group_name" {
  description = "Resource group the deploy workflows use for the just-in-time SSH rule (ADR 0018)."
  value       = azurerm_resource_group.main.name
}

output "nsg_name" {
  description = "NSG the deploy workflows add and remove the jit-ssh-* rule on (ADR 0018)."
  value       = azurerm_network_security_group.app.name
}
