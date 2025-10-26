#
# Terraform Module: RDS PostgreSQL for CardDemo Application
#
# Purpose: Provisions Amazon RDS PostgreSQL 16.6 database instance to replace
#          mainframe VSAM KSDS datasets (11 total datasets including ACCTFILE,
#          CARDFILE, CUSTFILE, TRANSACT, DALYTRAN, TCATBAL, DISCGRP, TRANCATG,
#          TRANTYPE, USRSEC, XREFFILE) with cloud-native managed database.
#
# Key Features:
# - Multi-AZ deployment for 99.95% availability SLA
# - Custom parameter group optimized for OLTP workloads
# - VSAM-equivalent performance tuning (sub-10ms primary key lookups)
# - Encryption at rest and in transit
# - Enhanced monitoring and Performance Insights
# - Optional read replicas for reporting workloads
# - Automated backups with 7-day retention
#
# Conversion from COBOL/VSAM:
# - Replaces VSAM KSDS key-sequenced datasets with B-tree indexed tables
# - Maintains sub-200ms transaction response time requirement
# - Supports 10,000 TPS throughput requirement
# - Preserves COMP-3 precision using PostgreSQL NUMERIC types
#

terraform {
  required_version = ">= 1.10.0"
  
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.82.0"
    }
  }
}

#
# DB Subnet Group - Multi-AZ placement across private subnets
#
resource "aws_db_subnet_group" "main" {
  name        = "${var.identifier}-subnet-group"
  description = "Database subnet group for CardDemo RDS instance across multiple AZs"
  subnet_ids  = var.subnet_ids

  tags = merge(
    var.tags,
    {
      Name        = "${var.identifier}-subnet-group"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "terraform"
      Purpose     = "Multi-AZ subnet group for PostgreSQL database"
    }
  )
}

#
# DB Parameter Group - OLTP optimizations matching VSAM KSDS performance
#
resource "aws_db_parameter_group" "main" {
  name        = "${var.identifier}-params"
  family      = var.parameter_group_family
  description = "Custom parameter group optimized for OLTP workloads - VSAM KSDS replacement"

  # Buffer cache optimization - replaces VSAM buffer pools
  # Allocates 25% of instance memory to shared buffers for hot data caching
  parameter {
    name  = "shared_buffers"
    value = "{DBInstanceClassMemory/4}"
  }

  # Query planner optimization - 75% of instance memory for cache size estimation
  # Helps query planner make better index vs sequential scan decisions
  parameter {
    name  = "effective_cache_size"
    value = "{DBInstanceClassMemory*3/4}"
  }

  # Index creation and VACUUM operations - 2GB working memory
  # Critical for maintaining VSAM-equivalent index performance
  parameter {
    name  = "maintenance_work_mem"
    value = "2097152"  # 2GB in KB
  }

  # Checkpoint completion - smooth I/O over 90% of checkpoint interval
  # Prevents I/O spikes that could impact transaction response times
  parameter {
    name  = "checkpoint_completion_target"
    value = "0.9"
  }

  # Write-Ahead Log buffers - 16MB for transaction logging
  # Optimizes transaction commit performance
  parameter {
    name  = "wal_buffers"
    value = "2048"  # 16MB in 8KB pages
  }

  # Statistics target for query planner - 100 histogram buckets
  # Improves query plan accuracy for VSAM-equivalent key access patterns
  parameter {
    name  = "default_statistics_target"
    value = "100"
  }

  # Random page cost - 1.1 for SSD-backed gp3 storage
  # Lower than default (4.0) to favor index scans on SSD storage
  # Matches VSAM KSDS key access performance characteristics
  parameter {
    name  = "random_page_cost"
    value = "1.1"
  }

  # Effective I/O concurrency - 200 for gp3 storage
  # Enables parallel I/O operations for better throughput
  parameter {
    name  = "effective_io_concurrency"
    value = "200"
  }

  # Work memory for sort operations - 32MB per operation
  # Supports efficient sorting and hash joins for reporting queries
  parameter {
    name  = "work_mem"
    value = "32768"  # 32MB in KB
  }

  # Minimum WAL size - 2GB to prevent frequent checkpoints
  parameter {
    name  = "min_wal_size"
    value = "2048"  # 2GB in MB
  }

  # Maximum WAL size - 4GB for write-heavy workloads
  parameter {
    name  = "max_wal_size"
    value = "4096"  # 4GB in MB
  }

  # Force SSL connections - encryption in transit requirement
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  # Log connections for audit trail
  parameter {
    name  = "log_connections"
    value = "1"
  }

  # Log disconnections for audit trail
  parameter {
    name  = "log_disconnections"
    value = "1"
  }

  # Log duration of statements exceeding 1 second
  # Helps identify slow queries that need optimization
  parameter {
    name  = "log_min_duration_statement"
    value = "1000"  # milliseconds
  }

  # Timezone setting
  parameter {
    name  = "timezone"
    value = "UTC"
  }

  tags = merge(
    var.tags,
    {
      Name        = "${var.identifier}-params"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "terraform"
      Purpose     = "OLTP-optimized parameters for VSAM KSDS replacement"
    }
  )
}

#
# Security Group - PostgreSQL port 5432 access control
#
resource "aws_security_group" "rds" {
  name        = "${var.identifier}-rds-sg"
  description = "Security group for CardDemo RDS PostgreSQL instance - allows port 5432 from application tier"
  vpc_id      = var.vpc_id

  # Ingress rule - Allow PostgreSQL port 5432 from application security groups
  ingress {
    description     = "PostgreSQL access from Spring Boot backend pods"
    from_port       = var.port
    to_port         = var.port
    protocol        = "tcp"
    security_groups = var.allowed_security_group_ids
  }

  # Egress rule - Allow all outbound traffic
  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(
    var.tags,
    {
      Name        = "${var.identifier}-rds-sg"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "terraform"
      Purpose     = "RDS PostgreSQL access control"
    }
  )
}

#
# IAM Role for Enhanced Monitoring
#
resource "aws_iam_role" "rds_monitoring" {
  name        = "${var.identifier}-rds-monitoring-role"
  description = "IAM role for RDS Enhanced Monitoring - CloudWatch metrics collection"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "monitoring.rds.amazonaws.com"
        }
        Action = "sts:AssumeRole"
      }
    ]
  })

  tags = merge(
    var.tags,
    {
      Name        = "${var.identifier}-monitoring-role"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "terraform"
      Purpose     = "RDS enhanced monitoring"
    }
  )
}

#
# IAM Role Policy Attachment - Enhanced Monitoring
#
resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  role       = aws_iam_role.rds_monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

#
# Main RDS PostgreSQL Instance - Replaces VSAM KSDS datasets
#
resource "aws_db_instance" "main" {
  # Instance identification
  identifier = var.identifier

  # Engine configuration - PostgreSQL 16.6
  engine         = var.engine
  engine_version = var.engine_version

  # Instance sizing - db.r6g.xlarge for production OLTP workloads
  # 4 vCPUs, 32 GiB RAM - sized to handle 10,000 TPS throughput
  instance_class = var.instance_class

  # Storage configuration
  allocated_storage     = var.allocated_storage      # Initial storage allocation
  max_allocated_storage = var.max_allocated_storage  # Enable storage autoscaling
  storage_type          = var.storage_type           # gp3 for performance
  storage_encrypted     = var.storage_encrypted      # Encryption at rest
  kms_key_id            = var.kms_key_id             # Customer-managed encryption key
  iops                  = var.storage_type == "gp3" || var.storage_type == "io1" ? var.iops : null
  storage_throughput    = var.storage_type == "gp3" ? var.storage_throughput : null

  # Database configuration
  db_name  = var.database_name   # Database name: carddemo
  username = var.master_username # Master username
  password = var.master_password # Master password (marked sensitive)
  port     = var.port            # PostgreSQL port: 5432

  # High availability configuration
  multi_az               = var.multi_az                  # Multi-AZ deployment for HA
  availability_zone      = var.multi_az ? null : var.availability_zone  # Single-AZ only
  db_subnet_group_name   = aws_db_subnet_group.main.name # Subnet group
  vpc_security_group_ids = concat([aws_security_group.rds.id], var.additional_security_group_ids)

  # Performance and tuning
  parameter_group_name = aws_db_parameter_group.main.name # Custom parameter group

  # Backup configuration
  backup_retention_period   = var.backup_retention_period   # 7-day retention
  backup_window             = var.backup_window             # Preferred backup window
  maintenance_window        = var.maintenance_window        # Maintenance window
  skip_final_snapshot       = var.skip_final_snapshot       # Take final snapshot on deletion
  final_snapshot_identifier = var.skip_final_snapshot ? null : "${var.identifier}-final-snapshot-${formatdate("YYYY-MM-DD-hhmm", timestamp())}"
  copy_tags_to_snapshot     = true                          # Copy tags to snapshots

  # Monitoring and logging
  enabled_cloudwatch_logs_exports = var.enabled_cloudwatch_logs_exports  # ["postgresql", "upgrade"]
  monitoring_interval             = var.monitoring_interval              # 60 seconds
  monitoring_role_arn             = var.monitoring_interval > 0 ? aws_iam_role.rds_monitoring.arn : null

  # Performance Insights - Query-level performance monitoring
  performance_insights_enabled          = var.performance_insights_enabled
  performance_insights_retention_period = var.performance_insights_enabled ? var.performance_insights_retention_period : null
  performance_insights_kms_key_id       = var.performance_insights_enabled && var.performance_insights_kms_key_id != null ? var.performance_insights_kms_key_id : null

  # Maintenance and upgrade configuration
  auto_minor_version_upgrade = var.auto_minor_version_upgrade  # Enable automatic minor version upgrades
  apply_immediately          = var.apply_immediately           # Apply changes immediately or during maintenance window

  # Deletion protection - prevent accidental deletion in production
  deletion_protection = var.deletion_protection

  # Public accessibility - always false for security (private subnets only)
  publicly_accessible = false

  # IAM database authentication (optional)
  iam_database_authentication_enabled = var.iam_database_authentication_enabled

  # Character set
  character_set_name = var.character_set_name

  # Option group
  option_group_name = var.option_group_name

  # Tags
  tags = merge(
    var.tags,
    {
      Name           = var.identifier
      Environment    = var.environment
      Project        = var.project_name
      ManagedBy      = "terraform"
      DatabaseEngine = "PostgreSQL 16.6"
      Purpose        = "VSAM Replacement for 11 KSDS datasets"
      Datasets       = "ACCTFILE,CARDFILE,CUSTFILE,TRANSACT,DALYTRAN,TCATBAL,DISCGRP,TRANCATG,TRANTYPE,USRSEC,XREFFILE"
    }
  )

  # Dependencies - ensure subnet group, parameter group, security group, and IAM role exist first
  depends_on = [
    aws_db_subnet_group.main,
    aws_db_parameter_group.main,
    aws_security_group.rds,
    aws_iam_role_policy_attachment.rds_monitoring
  ]

  # Lifecycle rules
  lifecycle {
    ignore_changes = [
      password,  # Ignore password changes (managed via Secrets Manager)
      final_snapshot_identifier  # Ignore final snapshot identifier changes
    ]
  }
}

#
# Read Replica - Optional, for reporting and analytics workloads
#
resource "aws_db_instance" "read_replica" {
  count = var.create_read_replica ? 1 : 0

  # Replica identification
  identifier = var.read_replica_identifier != null ? var.read_replica_identifier : "${var.identifier}-replica"

  # Replication source
  replicate_source_db = aws_db_instance.main.identifier

  # Instance sizing - same as primary for consistency
  instance_class = var.read_replica_instance_class != null ? var.read_replica_instance_class : var.instance_class

  # Storage configuration - inherited from primary but can override
  storage_type       = var.storage_type
  storage_encrypted  = var.storage_encrypted
  kms_key_id         = var.kms_key_id
  iops               = var.storage_type == "gp3" || var.storage_type == "io1" ? var.iops : null
  storage_throughput = var.storage_type == "gp3" ? var.storage_throughput : null

  # High availability - typically single-AZ for cost optimization
  # Read replicas can be promoted to standalone instances if needed
  multi_az = var.read_replica_multi_az

  # Network configuration - can be in different AZ for geographic distribution
  availability_zone      = var.read_replica_availability_zone
  vpc_security_group_ids = concat([aws_security_group.rds.id], var.additional_security_group_ids)

  # Public accessibility - always false for security
  publicly_accessible = false

  # Monitoring and logging
  enabled_cloudwatch_logs_exports = var.enabled_cloudwatch_logs_exports
  monitoring_interval             = var.monitoring_interval
  monitoring_role_arn             = var.monitoring_interval > 0 ? aws_iam_role.rds_monitoring.arn : null

  # Performance Insights
  performance_insights_enabled          = var.performance_insights_enabled
  performance_insights_retention_period = var.performance_insights_enabled ? var.performance_insights_retention_period : null
  performance_insights_kms_key_id       = var.performance_insights_enabled && var.performance_insights_kms_key_id != null ? var.performance_insights_kms_key_id : null

  # Maintenance configuration
  auto_minor_version_upgrade = var.auto_minor_version_upgrade
  apply_immediately          = var.apply_immediately

  # Backup configuration - read replicas don't need separate backups
  backup_retention_period = 0  # Backups not supported on read replicas
  skip_final_snapshot     = true

  # IAM database authentication
  iam_database_authentication_enabled = var.iam_database_authentication_enabled

  # Option group
  option_group_name = var.option_group_name

  # Tags
  tags = merge(
    var.tags,
    {
      Name           = var.read_replica_identifier != null ? var.read_replica_identifier : "${var.identifier}-replica"
      Environment    = var.environment
      Project        = var.project_name
      ManagedBy      = "terraform"
      DatabaseEngine = "PostgreSQL 16.6"
      Purpose        = "Read replica for reporting and analytics workloads"
      ReplicaOf      = var.identifier
    }
  )

  # Dependencies
  depends_on = [
    aws_db_instance.main
  ]
}

#
# CloudWatch Log Groups - For PostgreSQL logs
#
resource "aws_cloudwatch_log_group" "postgresql" {
  name              = "/aws/rds/instance/${var.identifier}/postgresql"
  retention_in_days = var.cloudwatch_logs_retention_days

  tags = merge(
    var.tags,
    {
      Name        = "${var.identifier}-postgresql-logs"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "terraform"
      Purpose     = "PostgreSQL application logs"
    }
  )
}

resource "aws_cloudwatch_log_group" "upgrade" {
  name              = "/aws/rds/instance/${var.identifier}/upgrade"
  retention_in_days = var.cloudwatch_logs_retention_days

  tags = merge(
    var.tags,
    {
      Name        = "${var.identifier}-upgrade-logs"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "terraform"
      Purpose     = "PostgreSQL upgrade logs"
    }
  )
}

