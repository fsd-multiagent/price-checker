# Terraform 1.x and azurerm 4.x (architecture spine). State lives in a remote
# azurerm backend with blob-lease locking (I-4); the storage account, container
# and key are supplied at init time with -backend-config (partial config), and
# authentication is OIDC only (I-5, S-10).
terraform {
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
  }

  backend "azurerm" {
    use_oidc         = true
    use_azuread_auth = true
  }
}
