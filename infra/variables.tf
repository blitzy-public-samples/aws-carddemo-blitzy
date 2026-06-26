###############################################################################
# Input variables
#
# Networking inputs (vpc_id, subnet_ids) have no defaults: the database is
# deployed into an EXISTING VPC/subnets that you supply per environment. All
# other inputs carry production-sane defaults that can be overridden via a
# *.tfvars file (see terraform.tfvars.example) or -var flags.
###############################################################################

variable "aws_region" {
  description = "AWS region in which to provision the database."
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment name (used in resource names and tags)."
  type        = string
  default     = "prod"
}

variable "name_prefix" {
  description = "Prefix applied to all resource names."
  type        = string
  default     = "carddemo"
}

# ---------------------------------------------------------------------------
# Networking (no defaults - must reference an existing VPC and private subnets)
# ---------------------------------------------------------------------------
variable "vpc_id" {
  description = "ID of the existing VPC the database and its security group live in."
  type        = string
}

variable "subnet_ids" {
  description = <<-EOT
    IDs of at least two subnets in DIFFERENT Availability Zones for the RDS DB
    subnet group. Use private subnets - the instance is not publicly accessible.
  EOT
  type        = list(string)

  validation {
    condition     = length(var.subnet_ids) >= 2
    error_message = "Provide at least two subnet IDs in different Availability Zones for RDS high availability."
  }
}

variable "allowed_cidr_blocks" {
  description = <<-EOT
    CIDR blocks permitted to reach the database on the PostgreSQL port (e.g. the
    application subnet/VPC CIDR). Keep this as tight as possible; the default is
    intentionally empty so no ingress is opened until you specify it.
  EOT
  type        = list(string)
  default     = []
}

variable "allowed_security_group_ids" {
  description = "Security group IDs (e.g. the application's SG) permitted to reach the database."
  type        = list(string)
  default     = []
}

# ---------------------------------------------------------------------------
# Engine / sizing
# ---------------------------------------------------------------------------
variable "engine_version" {
  description = <<-EOT
    PostgreSQL engine version. Defaults to the major version "16" (AWS selects
    the latest available 16.x minor). Pin a specific minor (e.g. "16.8") to make
    upgrades explicit and avoid plan drift.
  EOT
  type        = string
  default     = "16"
}

variable "parameter_group_family" {
  description = "RDS parameter-group family. Must match the engine major version."
  type        = string
  default     = "postgres16"
}

variable "instance_class" {
  description = "RDS instance class for the database."
  type        = string
  default     = "db.t3.medium"
}

variable "allocated_storage" {
  description = "Initial allocated storage in GiB."
  type        = number
  default     = 50
}

variable "max_allocated_storage" {
  description = "Upper bound (GiB) for RDS storage autoscaling. Set equal to allocated_storage to disable."
  type        = number
  default     = 200
}

variable "storage_type" {
  description = "RDS storage type (gp3 recommended for production)."
  type        = string
  default     = "gp3"
}

variable "multi_az" {
  description = "Deploy a standby in a second AZ for automatic failover (recommended for production)."
  type        = bool
  default     = true
}

# ---------------------------------------------------------------------------
# Database identity
# ---------------------------------------------------------------------------
variable "db_name" {
  description = "Initial database (schema) name. Mirrors the application default 'carddemo'."
  type        = string
  default     = "carddemo"
}

variable "db_username" {
  description = "Master username for the database. Mirrors the application default 'carddemo'."
  type        = string
  default     = "carddemo"
}

variable "db_port" {
  description = "TCP port the database listens on."
  type        = number
  default     = 5432
}

# ---------------------------------------------------------------------------
# Backups (Refine-PR directive D5: automated backups)
# ---------------------------------------------------------------------------
variable "backup_retention_period" {
  description = "Number of days to retain automated backups (1-35). Must be > 0 to keep automated backups enabled."
  type        = number
  default     = 14

  validation {
    condition     = var.backup_retention_period >= 1 && var.backup_retention_period <= 35
    error_message = "Automated backups must be retained between 1 and 35 days."
  }
}

variable "backup_window" {
  description = "Daily time range (UTC) for automated backups, format hh24:mi-hh24:mi."
  type        = string
  default     = "03:00-04:00"
}

variable "maintenance_window" {
  description = "Weekly time range (UTC) for maintenance, format ddd:hh24:mi-ddd:hh24:mi (must not overlap backup_window)."
  type        = string
  default     = "sun:04:30-sun:05:30"
}

variable "copy_tags_to_snapshot" {
  description = "Copy resource tags to automated and manual snapshots."
  type        = bool
  default     = true
}

variable "delete_automated_backups" {
  description = "Whether to delete automated backups when the instance is destroyed."
  type        = bool
  default     = false
}

# ---------------------------------------------------------------------------
# Security / durability
# ---------------------------------------------------------------------------
variable "storage_encrypted" {
  description = <<-EOT
    Enable RDS storage encryption at the infrastructure layer (KMS). This is a
    standard production database baseline and is distinct from the deferred
    application-level "encryption-at-rest" feature noted out of scope in AAP
    §0.2.2. Set to false only if your organization mandates a different posture.
  EOT
  type        = bool
  default     = true
}

variable "kms_key_id" {
  description = "Optional KMS key ARN for storage encryption. Empty uses the default aws/rds key."
  type        = string
  default     = ""
}

variable "deletion_protection" {
  description = "Prevent accidental deletion of the production database."
  type        = bool
  default     = true
}

variable "performance_insights_enabled" {
  description = "Enable RDS Performance Insights."
  type        = bool
  default     = true
}

variable "monitoring_interval" {
  description = "Enhanced monitoring granularity in seconds (0 disables; valid: 0,1,5,10,15,30,60)."
  type        = number
  default     = 60
}

# ---------------------------------------------------------------------------
# Application seed credentials (stored in Secrets Manager for the app to load)
# ---------------------------------------------------------------------------
variable "app_admin_password" {
  description = <<-EOT
    Optional explicit value for CARDDEMO_ADMIN_PASSWORD. Leave empty to have
    Terraform generate a strong random password and store it in Secrets Manager.
  EOT
  type        = string
  default     = ""
  sensitive   = true
}

variable "app_user_password" {
  description = <<-EOT
    Optional explicit value for CARDDEMO_USER_PASSWORD. Leave empty to have
    Terraform generate a strong random password and store it in Secrets Manager.
  EOT
  type        = string
  default     = ""
  sensitive   = true
}

variable "secret_recovery_window_days" {
  description = "Secrets Manager recovery window (days) before permanent deletion (0 = force delete immediately)."
  type        = number
  default     = 7
}

variable "tags" {
  description = "Additional tags merged onto all resources."
  type        = map(string)
  default     = {}
}
