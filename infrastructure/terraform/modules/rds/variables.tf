# Terraform Variables for RDS PostgreSQL Module
# Purpose: Define input variables for Amazon RDS PostgreSQL 16.x instance
#          provisioning to replace VSAM KSDS file storage from CardDemo mainframe
# Migration Context: 11 VSAM datasets (ACCTFILE, CARDFILE, CUSTFILE, TRANSACT,
#                    DALYTRAN, TCATBAL, DISCGRP, TRANCATG, TRANTYPE, USRSEC, XREFFILE)
#                    migrating to PostgreSQL tables with equivalent performance

# ============================================================================
# Database Instance Identification
# ============================================================================

variable "identifier" {
  description = "Unique identifier for the RDS instance. Used as the DB instance name in AWS console and CLI operations."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,62}$", var.identifier))
    error_message = "Identifier must start with a letter, contain only lowercase alphanumeric characters and hyphens, and be 1-63 characters long."
  }
}

variable "engine" {
  description = "Database engine type. Fixed to 'postgres' for PostgreSQL compatibility."
  type        = string
  default     = "postgres"

  validation {
    condition     = var.engine == "postgres"
    error_message = "Engine must be 'postgres' for this module."
  }
}

variable "engine_version" {
  description = "PostgreSQL engine version. Default 16.6 for PostgreSQL 16.x compatibility with modern OLTP workloads."
  type        = string
  default     = "16.6"

  validation {
    condition     = can(regex("^16\\.[0-9]+$", var.engine_version))
    error_message = "Engine version must be PostgreSQL 16.x series (e.g., 16.6, 16.7)."
  }
}

# ============================================================================
# Compute and Storage Configuration
# ============================================================================

variable "instance_class" {
  description = <<-EOT
    RDS instance class for compute and memory resources.
    Default: db.r6g.xlarge (4 vCPU, 32 GiB RAM) for production OLTP workloads.
    Sized to handle 10,000 TPS with sub-200ms response times matching VSAM KSDS performance.
    Recommended alternatives:
      - db.r6g.2xlarge: For higher throughput requirements
      - db.t3.large: For dev/test environments
  EOT
  type        = string
  default     = "db.r6g.xlarge"

  validation {
    condition     = can(regex("^db\\.(t3|r6g|r6i|m6g|m6i)\\.(micro|small|medium|large|xlarge|2xlarge|4xlarge|8xlarge|12xlarge|16xlarge)$", var.instance_class))
    error_message = "Instance class must be a valid RDS instance type."
  }
}

variable "allocated_storage" {
  description = <<-EOT
    Initial storage allocation in gigabytes for database and transaction logs.
    Minimum 100 GB recommended for production CardDemo dataset with growth capacity.
    Estimated VSAM dataset sizes: ~50 GB active data + 50 GB growth buffer.
  EOT
  type        = number
  default     = 100

  validation {
    condition     = var.allocated_storage >= 20 && var.allocated_storage <= 65536
    error_message = "Allocated storage must be between 20 GB and 65536 GB (64 TB)."
  }
}

variable "max_allocated_storage" {
  description = <<-EOT
    Maximum storage threshold for storage autoscaling.
    Set to enable automatic storage increase when free space drops below 10%.
    Example: Set to 500 to allow automatic growth from 100 GB to 500 GB.
    Set to 0 to disable autoscaling.
  EOT
  type        = number
  default     = 500

  validation {
    condition     = var.max_allocated_storage == 0 || var.max_allocated_storage >= var.allocated_storage
    error_message = "Max allocated storage must be 0 (disabled) or greater than allocated_storage."
  }
}

variable "storage_type" {
  description = <<-EOT
    Storage type for RDS instance.
    Default: gp3 (General Purpose SSD v3) for optimal price/performance.
    gp3 provides 3000 IOPS baseline with burst capability, sufficient for OLTP workloads.
    Alternative: io1/io2 for guaranteed high IOPS (>16000) if needed.
  EOT
  type        = string
  default     = "gp3"

  validation {
    condition     = contains(["gp2", "gp3", "io1", "io2"], var.storage_type)
    error_message = "Storage type must be one of: gp2, gp3, io1, io2."
  }
}

variable "storage_encrypted" {
  description = "Enable encryption at rest using AWS KMS. Required for production compliance and security best practices."
  type        = bool
  default     = true
}

variable "kms_key_id" {
  description = <<-EOT
    ARN of AWS KMS key for storage encryption.
    If not specified, uses AWS managed key (aws/rds).
    Custom CMK recommended for production environments to support key rotation and audit policies.
  EOT
  type        = string
  default     = null
}

# ============================================================================
# Network Configuration
# ============================================================================

variable "vpc_id" {
  description = "VPC ID where the RDS instance will be deployed. Must be the same VPC as the EKS cluster for backend application connectivity."
  type        = string

  validation {
    condition     = can(regex("^vpc-[a-z0-9]{8,}$", var.vpc_id))
    error_message = "VPC ID must be a valid AWS VPC identifier (vpc-xxxxxxxx)."
  }
}

variable "subnet_ids" {
  description = <<-EOT
    List of subnet IDs for DB subnet group spanning multiple availability zones.
    Minimum 2 subnets in different AZs required for multi-AZ deployment.
    Must be private subnets with no direct internet access for security.
  EOT
  type        = list(string)

  validation {
    condition     = length(var.subnet_ids) >= 2
    error_message = "At least 2 subnet IDs in different availability zones are required for high availability."
  }

  validation {
    condition     = alltrue([for s in var.subnet_ids : can(regex("^subnet-[a-z0-9]{8,}$", s))])
    error_message = "All subnet IDs must be valid AWS subnet identifiers (subnet-xxxxxxxx)."
  }
}

variable "security_group_ids" {
  description = <<-EOT
    List of VPC security group IDs to associate with the RDS instance.
    If empty, module creates a security group allowing PostgreSQL port 5432 from application tier.
    Custom security groups should allow ingress on port 5432 from EKS worker node security group.
  EOT
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for sg in var.security_group_ids : can(regex("^sg-[a-z0-9]{8,}$", sg))])
    error_message = "All security group IDs must be valid AWS security group identifiers (sg-xxxxxxxx)."
  }
}

variable "publicly_accessible" {
  description = <<-EOT
    Enable public accessibility for RDS instance.
    MUST be false for production environments. Only set to true for development/testing with proper IP whitelisting.
  EOT
  type        = bool
  default     = false
}

# ============================================================================
# Database Configuration
# ============================================================================

variable "database_name" {
  description = <<-EOT
    Initial database name created on instance provisioning.
    Default: carddemo (hosts 11 tables migrated from VSAM datasets).
    Must contain only alphanumeric characters and underscores.
  EOT
  type        = string
  default     = "carddemo"

  validation {
    condition     = can(regex("^[a-zA-Z][a-zA-Z0-9_]{0,62}$", var.database_name))
    error_message = "Database name must start with a letter and contain only alphanumeric characters and underscores (1-63 characters)."
  }
}

variable "master_username" {
  description = <<-EOT
    Master username for database administrator access.
    Used for initial setup, schema migrations (Flyway), and administrative operations.
    Recommended: postgres or carddemo_admin
  EOT
  type        = string

  validation {
    condition     = can(regex("^[a-zA-Z][a-zA-Z0-9_]{0,62}$", var.master_username)) && !contains(["rdsadmin", "admin", "root"], var.master_username)
    error_message = "Master username must start with a letter, contain alphanumeric characters/underscores, and not be a reserved name (rdsadmin, admin, root)."
  }
}

variable "master_password" {
  description = <<-EOT
    Master password for database administrator access.
    SENSITIVE: Store in AWS Secrets Manager or Terraform Cloud workspace variables.
    Requirements: 8-128 characters, no @, /, or spaces.
  EOT
  type        = string
  sensitive   = true

  validation {
    condition     = length(var.master_password) >= 8 && length(var.master_password) <= 128
    error_message = "Master password must be between 8 and 128 characters."
  }
}

variable "port" {
  description = "TCP port for PostgreSQL connections. Standard PostgreSQL port is 5432."
  type        = number
  default     = 5432

  validation {
    condition     = var.port >= 1150 && var.port <= 65535
    error_message = "Port must be between 1150 and 65535."
  }
}

# ============================================================================
# High Availability Configuration
# ============================================================================

variable "multi_az" {
  description = <<-EOT
    Enable multi-AZ deployment for high availability.
    Creates synchronous standby replica in different AZ with automatic failover (60-120 seconds).
    Required for production to achieve 99.95% availability SLA.
    Disable for dev/test to reduce costs by 50%.
  EOT
  type        = bool
  default     = true
}

# ============================================================================
# Backup Configuration
# ============================================================================

variable "backup_retention_period" {
  description = <<-EOT
    Number of days to retain automated backups (1-35 days).
    Default: 7 days for production compliance and point-in-time recovery.
    Minimum 7 days recommended for business continuity.
    Set to 0 to disable automated backups (NOT recommended for production).
  EOT
  type        = number
  default     = 7

  validation {
    condition     = var.backup_retention_period >= 0 && var.backup_retention_period <= 35
    error_message = "Backup retention period must be between 0 and 35 days."
  }
}

variable "backup_window" {
  description = <<-EOT
    Preferred UTC time window for automated backups (format: HH:MM-HH:MM).
    Must be at least 30 minutes and not overlap with maintenance_window.
    Example: '03:00-04:00' for 3-4 AM UTC (low-traffic period).
  EOT
  type        = string
  default     = "03:00-04:00"

  validation {
    condition     = can(regex("^([0-1][0-9]|2[0-3]):[0-5][0-9]-([0-1][0-9]|2[0-3]):[0-5][0-9]$", var.backup_window))
    error_message = "Backup window must be in HH:MM-HH:MM format (UTC 24-hour time)."
  }
}

variable "maintenance_window" {
  description = <<-EOT
    Preferred UTC time window for system maintenance (format: ddd:HH:MM-ddd:HH:MM).
    Must be at least 30 minutes and not overlap with backup_window.
    Example: 'Mon:04:00-Mon:05:00' for Monday 4-5 AM UTC.
  EOT
  type        = string
  default     = "Mon:04:00-Mon:05:00"

  validation {
    condition     = can(regex("^(Mon|Tue|Wed|Thu|Fri|Sat|Sun):([0-1][0-9]|2[0-3]):[0-5][0-9]-(Mon|Tue|Wed|Thu|Fri|Sat|Sun):([0-1][0-9]|2[0-3]):[0-5][0-9]$", var.maintenance_window))
    error_message = "Maintenance window must be in ddd:HH:MM-ddd:HH:MM format."
  }
}

variable "skip_final_snapshot" {
  description = <<-EOT
    Skip final snapshot on database deletion.
    MUST be false for production to prevent accidental data loss.
    Set to true only for temporary dev/test instances.
  EOT
  type        = bool
  default     = false
}

variable "final_snapshot_identifier" {
  description = <<-EOT
    Identifier for final snapshot on database deletion.
    Required when skip_final_snapshot is false.
    Automatically suffixed with timestamp if not provided.
  EOT
  type        = string
  default     = null
}

variable "copy_tags_to_snapshot" {
  description = "Copy all instance tags to automated and manual snapshots for consistent resource tracking."
  type        = bool
  default     = true
}

# ============================================================================
# Monitoring and Logging Configuration
# ============================================================================

variable "enabled_cloudwatch_logs_exports" {
  description = <<-EOT
    List of log types to export to CloudWatch Logs.
    Available: postgresql (general query logs), upgrade (version upgrade logs).
    Both recommended for production troubleshooting and audit compliance.
  EOT
  type        = list(string)
  default     = ["postgresql", "upgrade"]

  validation {
    condition     = alltrue([for log in var.enabled_cloudwatch_logs_exports : contains(["postgresql", "upgrade"], log)])
    error_message = "Log exports must be from: postgresql, upgrade."
  }
}

variable "monitoring_interval" {
  description = <<-EOT
    Interval in seconds for enhanced monitoring metrics collection.
    Valid values: 0 (disabled), 1, 5, 10, 15, 30, 60.
    Default: 60 seconds for production monitoring with reasonable cost.
    Set to 0 to disable enhanced monitoring.
  EOT
  type        = number
  default     = 60

  validation {
    condition     = contains([0, 1, 5, 10, 15, 30, 60], var.monitoring_interval)
    error_message = "Monitoring interval must be one of: 0, 1, 5, 10, 15, 30, 60."
  }
}

variable "monitoring_role_arn" {
  description = <<-EOT
    ARN of IAM role for enhanced monitoring.
    If not specified, module creates a role with AmazonRDSEnhancedMonitoringRole policy.
    Required when monitoring_interval > 0.
  EOT
  type        = string
  default     = null
}

variable "performance_insights_enabled" {
  description = <<-EOT
    Enable Performance Insights for query-level performance monitoring.
    Recommended for production to identify slow queries and optimize database performance.
    Provides SQL-level metrics, wait events, and execution plans.
  EOT
  type        = bool
  default     = true
}

variable "performance_insights_retention_period" {
  description = <<-EOT
    Number of days to retain Performance Insights data (7 or 731 days).
    Default: 7 days (free tier). 731 days (2 years) incurs additional cost.
  EOT
  type        = number
  default     = 7

  validation {
    condition     = contains([7, 731], var.performance_insights_retention_period)
    error_message = "Performance Insights retention must be 7 or 731 days."
  }
}

variable "performance_insights_kms_key_id" {
  description = "ARN of KMS key for encrypting Performance Insights data. Uses default key if not specified."
  type        = string
  default     = null
}

# ============================================================================
# Parameter Group Configuration
# ============================================================================

variable "parameter_group_family" {
  description = "DB parameter group family. Must match engine version (postgres16 for PostgreSQL 16.x)."
  type        = string
  default     = "postgres16"

  validation {
    condition     = can(regex("^postgres1[0-9]$", var.parameter_group_family))
    error_message = "Parameter group family must be in format postgres16, postgres15, etc."
  }
}

variable "parameters" {
  description = <<-EOT
    Map of custom database parameters optimized for OLTP workloads.
    Default parameters tuned to match VSAM KSDS key access performance:
      - shared_buffers: Buffer cache size (25% of instance memory)
      - effective_cache_size: Query planner memory estimate (75% of instance memory)
      - maintenance_work_mem: Memory for maintenance operations (2 GB)
      - checkpoint_completion_target: Smooth I/O during checkpoints (0.9)
      - wal_buffers: Write-ahead log buffer (16 MB)
      - default_statistics_target: Statistics accuracy (100)
      - random_page_cost: SSD-optimized cost estimate (1.1)
      - effective_io_concurrency: Parallel I/O operations (200)
      - work_mem: Per-operation sort/hash memory (32 MB)
      - min_wal_size: Minimum WAL size (2 GB)
      - max_wal_size: Maximum WAL size before checkpoint (4 GB)
  EOT
  type = map(object({
    value        = string
    apply_method = string
  }))
  default = {
    shared_buffers = {
      value        = "{DBInstanceClassMemory/4}"
      apply_method = "pending-reboot"
    }
    effective_cache_size = {
      value        = "{DBInstanceClassMemory*3/4}"
      apply_method = "immediate"
    }
    maintenance_work_mem = {
      value        = "2097152"  # 2 GB in KB
      apply_method = "immediate"
    }
    checkpoint_completion_target = {
      value        = "0.9"
      apply_method = "immediate"
    }
    wal_buffers = {
      value        = "16384"  # 16 MB in KB
      apply_method = "pending-reboot"
    }
    default_statistics_target = {
      value        = "100"
      apply_method = "immediate"
    }
    random_page_cost = {
      value        = "1.1"
      apply_method = "immediate"
    }
    effective_io_concurrency = {
      value        = "200"
      apply_method = "immediate"
    }
    work_mem = {
      value        = "32768"  # 32 MB in KB
      apply_method = "immediate"
    }
    min_wal_size = {
      value        = "2048"  # 2 GB in MB
      apply_method = "immediate"
    }
    max_wal_size = {
      value        = "4096"  # 4 GB in MB
      apply_method = "immediate"
    }
    "rds.force_ssl" = {
      value        = "1"
      apply_method = "immediate"
    }
  }
}

# ============================================================================
# Read Replica Configuration
# ============================================================================

variable "create_read_replica" {
  description = <<-EOT
    Create a read replica for read-heavy reporting workloads.
    Read replica provides eventually-consistent copy for offloading SELECT queries.
    Set to true when reporting workload justifies additional cost.
  EOT
  type        = bool
  default     = false
}

variable "read_replica_identifier" {
  description = "Identifier for read replica instance. Required when create_read_replica is true."
  type        = string
  default     = null
}

variable "read_replica_instance_class" {
  description = <<-EOT
    Instance class for read replica. Can be different from primary instance.
    Smaller instance acceptable if reporting workload is lighter than OLTP workload.
  EOT
  type        = string
  default     = null
}

# ============================================================================
# Upgrade and Maintenance Configuration
# ============================================================================

variable "auto_minor_version_upgrade" {
  description = <<-EOT
    Enable automatic minor version upgrades during maintenance window.
    Recommended for production to receive security patches and bug fixes.
    Minor version upgrades do not require schema changes.
  EOT
  type        = bool
  default     = true
}

variable "apply_immediately" {
  description = <<-EOT
    Apply database modifications immediately rather than during maintenance window.
    Use with caution: immediate changes may cause brief downtime.
    Set to false for production to schedule changes during maintenance window.
  EOT
  type        = bool
  default     = false
}

variable "deletion_protection" {
  description = <<-EOT
    Enable deletion protection to prevent accidental database deletion.
    MUST be true for production databases.
    Must be disabled before database can be deleted.
  EOT
  type        = bool
  default     = true
}

variable "allow_major_version_upgrade" {
  description = <<-EOT
    Allow major version upgrades (e.g., PostgreSQL 16 to 17).
    Requires careful testing and may require application changes.
    Set to true only when planning major version upgrade.
  EOT
  type        = bool
  default     = false
}

# ============================================================================
# Tagging and Metadata Configuration
# ============================================================================

variable "tags" {
  description = <<-EOT
    Map of tags to apply to all resources created by this module.
    Recommended tags: Environment, Project, ManagedBy, CostCenter, Owner.
    Tags are copied to snapshots if copy_tags_to_snapshot is true.
  EOT
  type        = map(string)
  default     = {}
}

variable "environment" {
  description = <<-EOT
    Environment name for resource identification and tagging.
    Examples: production, staging, development, test.
    Used in resource naming and CloudWatch metric namespaces.
  EOT
  type        = string

  validation {
    condition     = contains(["production", "prod", "staging", "stage", "development", "dev", "test"], var.environment)
    error_message = "Environment must be one of: production, prod, staging, stage, development, dev, test."
  }
}

variable "project_name" {
  description = "Project name for resource identification. Default: carddemo (CardDemo mainframe migration project)."
  type        = string
  default     = "carddemo"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,62}$", var.project_name))
    error_message = "Project name must start with a letter, contain only lowercase alphanumeric characters and hyphens."
  }
}

# ============================================================================
# IAM Database Authentication Configuration
# ============================================================================

variable "iam_database_authentication_enabled" {
  description = <<-EOT
    Enable IAM database authentication for passwordless connections.
    Allows applications to use IAM roles instead of database passwords.
    Recommended for enhanced security and credential management.
  EOT
  type        = bool
  default     = false
}

# ============================================================================
# Snapshot Configuration
# ============================================================================

variable "snapshot_identifier" {
  description = <<-EOT
    Snapshot ID to restore from when creating database instance.
    Use for database cloning or disaster recovery scenarios.
    Leave null for new database creation.
  EOT
  type        = string
  default     = null
}

# ============================================================================
# CloudWatch Alarms Configuration
# ============================================================================

variable "create_cloudwatch_alarms" {
  description = <<-EOT
    Create CloudWatch alarms for critical RDS metrics.
    Monitors CPU, storage, connections, and replication lag.
    Recommended for production monitoring and alerting.
  EOT
  type        = bool
  default     = true
}

variable "alarm_cpu_threshold_percent" {
  description = "CPU utilization threshold percentage for CloudWatch alarm. Default: 80%."
  type        = number
  default     = 80

  validation {
    condition     = var.alarm_cpu_threshold_percent > 0 && var.alarm_cpu_threshold_percent <= 100
    error_message = "CPU threshold must be between 1 and 100 percent."
  }
}

variable "alarm_disk_queue_depth" {
  description = "Disk queue depth threshold for CloudWatch alarm. Default: 64 operations."
  type        = number
  default     = 64
}

variable "alarm_free_storage_space_threshold_bytes" {
  description = "Free storage space threshold in bytes for CloudWatch alarm. Default: 10 GB (10737418240 bytes)."
  type        = number
  default     = 10737418240  # 10 GB
}

variable "alarm_evaluation_periods" {
  description = "Number of evaluation periods for CloudWatch alarms. Default: 2 consecutive periods."
  type        = number
  default     = 2

  validation {
    condition     = var.alarm_evaluation_periods >= 1
    error_message = "Alarm evaluation periods must be at least 1."
  }
}

variable "alarm_actions" {
  description = <<-EOT
    List of SNS topic ARNs to notify when CloudWatch alarms trigger.
    Configure SNS topics for email, SMS, or PagerDuty integration.
  EOT
  type        = list(string)
  default     = []
}

variable "ok_actions" {
  description = "List of SNS topic ARNs to notify when CloudWatch alarms return to OK state."
  type        = list(string)
  default     = []
}
