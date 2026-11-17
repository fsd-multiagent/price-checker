variable "location" {
  description = "Azure region. Must be in the EU (I-3)."
  type        = string
  default     = "westeurope"

  validation {
    condition = contains([
      "westeurope", "northeurope", "francecentral", "francesouth",
      "germanywestcentral", "germanynorth", "swedencentral", "swedensouth",
      "italynorth", "polandcentral", "spaincentral", "austriaeast",
    ], var.location)
    error_message = "location must be an EU Azure region (I-3)."
  }
}

variable "env" {
  description = "Environment name, used in names and the env tag (I-8). Only prod exists (ADR 0015)."
  type        = string
  default     = "prod"

  validation {
    condition     = var.env == "prod"
    error_message = "Only the prod environment exists (ADR 0015)."
  }
}

variable "vm_size" {
  description = "VM size for the single app host."
  type        = string
  default     = "Standard_B2s"
}

variable "dns_label" {
  description = "Public IP DNS label; Caddy obtains its certificate for <label>.<region>.cloudapp.azure.com."
  type        = string
  default     = "price-checker-prod"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,61}[a-z0-9]$", var.dns_label))
    error_message = "dns_label must be 3-63 lowercase letters, digits or hyphens, starting with a letter."
  }
}

variable "admin_ssh_public_key" {
  description = "OpenSSH public key for the break-glass admin user (owner only). Public key, not a secret."
  type        = string

  validation {
    condition     = can(regex("^(ssh-ed25519|ssh-rsa) ", var.admin_ssh_public_key))
    error_message = "admin_ssh_public_key must be an ssh-ed25519 or ssh-rsa public key (Azure rejects ECDSA admin keys)."
  }
}

variable "deploy_ssh_public_key" {
  description = "OpenSSH public key matching the DEPLOY_SSH_KEY GitHub secret; restricted by forced command (ADR 0018)."
  type        = string

  validation {
    condition     = can(regex("^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp256) ", var.deploy_ssh_public_key))
    error_message = "deploy_ssh_public_key must be an OpenSSH public key."
  }
}

variable "budget_amount" {
  description = "Monthly cost ceiling for the resource group, in the billing currency (I-8)."
  type        = number
  default     = 50

  validation {
    condition     = var.budget_amount > 0
    error_message = "budget_amount must be positive."
  }
}

variable "budget_contact_email" {
  description = "Address that receives budget alerts (I-8). Sensitive so plans and logs do not print it."
  type        = string
  sensitive   = true

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+$", var.budget_contact_email))
    error_message = "budget_contact_email must be an email address."
  }
}

variable "budget_start_date" {
  description = "First day of the budget period, RFC 3339 (must be the first of a month)."
  type        = string
  default     = "2026-12-01T00:00:00Z"

  validation {
    condition     = can(regex("^[0-9]{4}-[0-9]{2}-01T00:00:00Z$", var.budget_start_date))
    error_message = "budget_start_date must be the first of a month, as YYYY-MM-01T00:00:00Z."
  }
}
