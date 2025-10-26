# =============================================================================
# Terraform Variables for CardDemo Modernization Infrastructure
# =============================================================================
# This file defines all input variables for the AWS infrastructure required
# to deploy the modernized CardDemo credit card management system.
#
# Migration Context: Replaces manual mainframe provisioning with declarative
# cloud infrastructure automation for COBOL-to-Java Spring Boot application.
#
# Technology Stack:
# - Backend: Spring Boot 3.4.5 on EKS (Kubernetes 1.31)
# - Frontend: React 18.3 SPA
# - Database: PostgreSQL 16.6 on RDS
# - Infrastructure: AWS EKS, RDS, VPC
# =============================================================================

# -----------------------------------------------------------------------------
# AWS Region Configuration
# -----------------------------------------------------------------------------

variable "aws_region" {
  description = "AWS region for infrastructure deployment. Determines data center location for CardDemo application resources."
  type        = string
  default     = "us-east-1"

  validation {
    condition     = can(regex("^[a-z]{2}-[a-z]+-[0-9]{1}$", var.aws_region))
    error_message = "AWS region must be a valid region identifier (e.g., us-east-1, us-west-2, eu-west-1)."
  }
}

# -----------------------------------------------------------------------------
# Environment and Project Identification
# -----------------------------------------------------------------------------

variable "environment" {
  description = "Environment name for resource tagging and naming (dev, test, prod). Maps to mainframe LPAR equivalents."
  type        = string

  validation {
    condition     = contains(["dev", "test", "prod"], var.environment)
    error_message = "Environment must be one of: dev, test, prod."
  }
}

variable "project_name" {
  description = "Project name used for resource naming and tagging. Identifies CardDemo application resources."
  type        = string
  default     = "carddemo"

  validation {
    condition     = can(regex("^[a-z0-9-]+$", var.project_name))
    error_message = "Project name must contain only lowercase letters, numbers, and hyphens."
  }
}

variable "tags" {
  description = "Additional tags to apply to all resources for cost allocation and resource management."
  type        = map(string)
  default     = {}
}

# -----------------------------------------------------------------------------
# VPC Network Configuration
# -----------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "CIDR block for VPC network. Defines IP address space for CardDemo application infrastructure."
  type        = string
  default     = "10.0.0.0/16"

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "VPC CIDR must be a valid IPv4 CIDR block."
  }
}

variable "vpc_private_subnets" {
  description = "List of CIDR blocks for private subnets (EKS nodes, RDS instances). Minimum 2 for high availability."
  type        = list(string)
  default     = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]

  validation {
    condition     = length(var.vpc_private_subnets) >= 2
    error_message = "At least 2 private subnets required for high availability across availability zones."
  }
}

variable "vpc_public_subnets" {
  description = "List of CIDR blocks for public subnets (load balancers, NAT gateways). Minimum 2 for high availability."
  type        = list(string)
  default     = ["10.0.101.0/24", "10.0.102.0/24", "10.0.103.0/24"]

  validation {
    condition     = length(var.vpc_public_subnets) >= 2
    error_message = "At least 2 public subnets required for high availability across availability zones."
  }
}

variable "enable_nat_gateway" {
  description = "Enable NAT Gateway for private subnet internet access. Required for EKS nodes to pull container images."
  type        = bool
  default     = true
}

variable "single_nat_gateway" {
  description = "Use single NAT Gateway (cost optimization for dev/test). Set false for production high availability."
  type        = bool
  default     = false
}

# -----------------------------------------------------------------------------
# EKS Cluster Configuration
# -----------------------------------------------------------------------------

variable "eks_cluster_version" {
  description = "Kubernetes version for EKS cluster. Use 1.31 or compatible version for latest features and security patches."
  type        = string
  default     = "1.31"

  validation {
    condition     = can(regex("^1\\.(2[89]|3[0-9])$", var.eks_cluster_version))
    error_message = "EKS cluster version must be 1.28 or higher for Spring Boot 3.4 compatibility."
  }
}

variable "eks_cluster_name" {
  description = "Name of the EKS cluster. Derived from project_name and environment if not specified."
  type        = string
  default     = ""
}

variable "eks_node_instance_types" {
  description = "EC2 instance types for EKS worker nodes. Sized for Spring Boot application (4GB RAM minimum per pod). Must support 10,000 TPS throughput requirement."
  type        = list(string)
  default     = ["t3.large", "t3.xlarge"]

  validation {
    condition     = length(var.eks_node_instance_types) > 0
    error_message = "At least one instance type must be specified for EKS worker nodes."
  }
}

variable "eks_desired_nodes" {
  description = "Desired number of EKS worker nodes for steady-state operation. Minimum 3 for production high availability."
  type        = number
  default     = 3

  validation {
    condition     = var.eks_desired_nodes >= 1
    error_message = "Desired nodes must be at least 1."
  }
}

variable "eks_min_nodes" {
  description = "Minimum number of EKS worker nodes for autoscaling. Must be at least 1 for application availability."
  type        = number
  default     = 3

  validation {
    condition     = var.eks_min_nodes >= 1
    error_message = "Minimum nodes must be at least 1 for application availability."
  }
}

variable "eks_max_nodes" {
  description = "Maximum number of EKS worker nodes for autoscaling. Set based on peak load requirements (10,000 TPS target)."
  type        = number
  default     = 10

  validation {
    condition     = var.eks_max_nodes >= var.eks_min_nodes
    error_message = "Maximum nodes must be greater than or equal to minimum nodes."
  }
}

variable "eks_node_disk_size" {
  description = "Disk size in GB for EKS worker nodes. Sized for container images and logs."
  type        = number
  default     = 50

  validation {
    condition     = var.eks_node_disk_size >= 20
    error_message = "Node disk size must be at least 20 GB for container images and system overhead."
  }
}

variable "eks_enable_cluster_autoscaler" {
  description = "Enable Kubernetes Cluster Autoscaler for automatic node scaling based on pod resource requests."
  type        = bool
  default     = true
}

variable "eks_enable_metrics_server" {
  description = "Enable Kubernetes Metrics Server for horizontal pod autoscaling and resource monitoring."
  type        = bool
  default     = true
}

# -----------------------------------------------------------------------------
# RDS PostgreSQL Database Configuration
# -----------------------------------------------------------------------------

variable "rds_engine_version" {
  description = "PostgreSQL engine version for RDS instance. Use 16.6 for latest features and performance (replaces VSAM)."
  type        = string
  default     = "16.6"

  validation {
    condition     = can(regex("^16\\.[0-9]+$", var.rds_engine_version))
    error_message = "RDS engine version must be PostgreSQL 16.x for CardDemo migration compatibility."
  }
}

variable "rds_instance_class" {
  description = "RDS instance class for database sizing. Sized for VSAM data volume and transaction throughput (10,000 TPS target)."
  type        = string
  default     = "db.r6g.xlarge"

  validation {
    condition     = can(regex("^db\\.[a-z0-9]+\\.[a-z0-9]+$", var.rds_instance_class))
    error_message = "RDS instance class must be a valid DB instance type (e.g., db.r6g.xlarge, db.m6g.large)."
  }
}

variable "rds_allocated_storage" {
  description = "Initial allocated storage in GB for RDS instance. Sized for migrated VSAM dataset volume (11 VSAM files)."
  type        = number
  default     = 100

  validation {
    condition     = var.rds_allocated_storage >= 20 && var.rds_allocated_storage <= 65536
    error_message = "RDS allocated storage must be between 20 GB and 65,536 GB."
  }
}

variable "rds_max_allocated_storage" {
  description = "Maximum storage in GB for RDS autoscaling. Set to 0 to disable storage autoscaling."
  type        = number
  default     = 500

  validation {
    condition     = var.rds_max_allocated_storage == 0 || var.rds_max_allocated_storage >= var.rds_allocated_storage
    error_message = "Maximum allocated storage must be 0 (disabled) or greater than initial allocated storage."
  }
}

variable "rds_backup_retention_days" {
  description = "Number of days to retain automated RDS backups. Minimum 7 for production (replaces mainframe backup procedures)."
  type        = number
  default     = 7

  validation {
    condition     = var.rds_backup_retention_days >= 0 && var.rds_backup_retention_days <= 35
    error_message = "Backup retention must be between 0 (disabled) and 35 days."
  }
}

variable "rds_backup_window" {
  description = "Preferred backup window in UTC (format: HH:MM-HH:MM). Should align with low-traffic period."
  type        = string
  default     = "03:00-04:00"

  validation {
    condition     = can(regex("^([0-1][0-9]|2[0-3]):[0-5][0-9]-([0-1][0-9]|2[0-3]):[0-5][0-9]$", var.rds_backup_window))
    error_message = "Backup window must be in format HH:MM-HH:MM (24-hour UTC time)."
  }
}

variable "rds_maintenance_window" {
  description = "Preferred maintenance window in UTC (format: day:HH:MM-day:HH:MM). Should align with maintenance windows from JCL batch schedule."
  type        = string
  default     = "sun:04:00-sun:05:00"

  validation {
    condition     = can(regex("^(mon|tue|wed|thu|fri|sat|sun):[0-2][0-9]:[0-5][0-9]-(mon|tue|wed|thu|fri|sat|sun):[0-2][0-9]:[0-5][0-9]$", var.rds_maintenance_window))
    error_message = "Maintenance window must be in format day:HH:MM-day:HH:MM (e.g., sun:04:00-sun:05:00)."
  }
}

variable "rds_multi_az" {
  description = "Enable Multi-AZ deployment for RDS high availability. Recommended for production to ensure database resilience."
  type        = bool
  default     = false
}

variable "rds_storage_encrypted" {
  description = "Enable encryption at rest for RDS storage. Required for production to meet security compliance requirements."
  type        = bool
  default     = true
}

variable "rds_performance_insights_enabled" {
  description = "Enable RDS Performance Insights for database performance monitoring and troubleshooting."
  type        = bool
  default     = true
}

variable "rds_performance_insights_retention" {
  description = "Number of days to retain Performance Insights data. 7 (free tier) or 731 (2 years, paid)."
  type        = number
  default     = 7

  validation {
    condition     = var.rds_performance_insights_retention == 7 || var.rds_performance_insights_retention == 731
    error_message = "Performance Insights retention must be 7 days (free) or 731 days (paid)."
  }
}

variable "rds_database_name" {
  description = "Name of the initial database to create in RDS instance for CardDemo application."
  type        = string
  default     = "carddemo"

  validation {
    condition     = can(regex("^[a-zA-Z][a-zA-Z0-9_]*$", var.rds_database_name))
    error_message = "Database name must start with a letter and contain only letters, numbers, and underscores."
  }
}

variable "rds_master_username" {
  description = "Master username for RDS database. Used for administrative access and Flyway migrations."
  type        = string
  default     = "carddemo_admin"

  validation {
    condition     = can(regex("^[a-zA-Z][a-zA-Z0-9_]*$", var.rds_master_username))
    error_message = "Master username must start with a letter and contain only letters, numbers, and underscores."
  }
}

variable "rds_port" {
  description = "Port number for PostgreSQL database connections."
  type        = number
  default     = 5432

  validation {
    condition     = var.rds_port >= 1150 && var.rds_port <= 65535
    error_message = "RDS port must be between 1150 and 65535."
  }
}

variable "rds_deletion_protection" {
  description = "Enable deletion protection for RDS instance. Strongly recommended for production to prevent accidental deletion."
  type        = bool
  default     = false
}

variable "rds_skip_final_snapshot" {
  description = "Skip final snapshot when deleting RDS instance. Set to false for production to ensure data backup before deletion."
  type        = bool
  default     = false
}

# -----------------------------------------------------------------------------
# Monitoring and Observability Configuration
# -----------------------------------------------------------------------------

variable "enable_monitoring" {
  description = "Enable monitoring stack (Prometheus, Grafana) for observability. Replaces mainframe monitoring tools."
  type        = bool
  default     = true
}

variable "monitoring_retention_days" {
  description = "Number of days to retain monitoring metrics and logs."
  type        = number
  default     = 30

  validation {
    condition     = var.monitoring_retention_days >= 1 && var.monitoring_retention_days <= 365
    error_message = "Monitoring retention must be between 1 and 365 days."
  }
}

variable "enable_cloudwatch_logs" {
  description = "Enable CloudWatch Logs for centralized log aggregation from EKS and RDS."
  type        = bool
  default     = true
}

variable "cloudwatch_log_retention_days" {
  description = "Number of days to retain CloudWatch Logs."
  type        = number
  default     = 30

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1827, 3653], var.cloudwatch_log_retention_days)
    error_message = "CloudWatch log retention must be a valid retention period (1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1827, or 3653 days)."
  }
}

# -----------------------------------------------------------------------------
# Application Configuration
# -----------------------------------------------------------------------------

variable "backend_replicas" {
  description = "Number of Spring Boot backend pod replicas for initial deployment. Autoscaler will adjust based on load (target: 10,000 TPS)."
  type        = number
  default     = 3

  validation {
    condition     = var.backend_replicas >= 1
    error_message = "Backend replicas must be at least 1 for application availability."
  }
}

variable "backend_cpu_request" {
  description = "CPU request for backend pods in millicores (e.g., 1000 = 1 CPU core). Based on Spring Boot application requirements."
  type        = string
  default     = "1000m"
}

variable "backend_memory_request" {
  description = "Memory request for backend pods (e.g., 2Gi = 2 GB). Spring Boot 3.4.5 with JVM requires minimum 2GB for optimal performance."
  type        = string
  default     = "2Gi"
}

variable "backend_cpu_limit" {
  description = "CPU limit for backend pods in millicores. Prevents resource exhaustion on shared nodes."
  type        = string
  default     = "2000m"
}

variable "backend_memory_limit" {
  description = "Memory limit for backend pods. Prevents OOM kills while allowing JVM heap sizing."
  type        = string
  default     = "4Gi"
}

# -----------------------------------------------------------------------------
# Security Configuration
# -----------------------------------------------------------------------------

variable "enable_pod_security_policy" {
  description = "Enable Kubernetes Pod Security Policy for enhanced container security."
  type        = bool
  default     = true
}

variable "enable_network_policy" {
  description = "Enable Kubernetes Network Policy for network segmentation and security."
  type        = bool
  default     = true
}

variable "allowed_cidr_blocks" {
  description = "List of CIDR blocks allowed to access the application (e.g., corporate network, VPN). Empty list allows all."
  type        = list(string)
  default     = []
}

variable "enable_secrets_encryption" {
  description = "Enable encryption of Kubernetes secrets using AWS KMS."
  type        = bool
  default     = true
}

# -----------------------------------------------------------------------------
# Cost Optimization Configuration
# -----------------------------------------------------------------------------

variable "enable_spot_instances" {
  description = "Enable Spot Instances for EKS worker nodes to reduce costs. Not recommended for production without proper handling."
  type        = bool
  default     = false
}

variable "spot_instance_max_price" {
  description = "Maximum price for Spot Instances as percentage of On-Demand price (e.g., 0.7 = 70%). Only used if enable_spot_instances is true."
  type        = string
  default     = ""
}

# =============================================================================
# End of Variables Definition
# =============================================================================
