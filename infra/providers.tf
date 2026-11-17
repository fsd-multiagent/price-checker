# Subscription, tenant and client IDs come from ARM_SUBSCRIPTION_ID,
# ARM_TENANT_ID and ARM_CLIENT_ID; the token comes from the GitHub OIDC
# exchange (I-5). No client secret is ever configured (S-10).
provider "azurerm" {
  features {}

  use_oidc = true
}
