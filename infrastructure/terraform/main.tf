#
# Root Terraform Configuration for CardDemo Modernization
# 
# Purpose: Orchestrate infrastructure provisioning for AWS deployment of modernized
#          CardDemo application, replacing manual mainframe provisioning with
#          declarative cloud infrastructure automation.
#
# Architecture Components:
#   - VPC: Network foundation with public/private subnets across multiple AZs
#   - EKS: Kubernetes cluster for containerized application deployment
#   - RDS: PostgreSQL 16.6 database replacing VSAM datasets
#   - Monitoring: Optional observability stack for metrics and logging
#
# Dependency Chain: VPC → EKS/RDS → Monitoring
#
# Migration Context: Replaces mainframe z/OS infrastructure with cloud-native AWS services
#

# Terraform version and provider requirements
terraform {
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.82.0"
    }
  }
}

# AWS Provider Configuration
# Configures AWS provider with region from variable and standardized tagging strategy
# Tags enable resource tracking, cost allocation, and governance policies
provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
      Application = "CardDemo"
      CostCenter  = "IT-Modernization"
      Migration   = "Mainframe-to-Cloud"
    }
  }
}

# Data source for AWS Availability Zones
# Dynamically discovers available AZs in the configured region for multi-AZ deployment
# Ensures high availability by distributing resources across failure domains
data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

# Data source for AWS account information
# Provides account ID and ARN for resource naming and IAM policy construction
data "aws_caller_identity" "current" {}

#
# VPC Module - Network Foundation
#
# Creates Virtual Private Cloud with public and private subnets across multiple
# availability zones. Replaces mainframe VTAM networking with cloud-native VPC.
#
# Public subnets: Internet-facing load balancers and NAT gateways
# Private subnets: EKS worker nodes and RDS database instances
#
module "vpc" {
  source = "./modules/vpc"

  # VPC Configuration
  cidr_block = var.vpc_cidr
  azs        = data.aws_availability_zones.available.names

  # Subnet Configuration
  # Private subnets for EKS nodes and RDS (no direct internet access)
  private_subnets = [
    cidrsubnet(var.vpc_cidr, 4, 0), # 10.0.0.0/20
    cidrsubnet(var.vpc_cidr, 4, 1), # 10.0.16.0/20
    cidrsubnet(var.vpc_cidr, 4, 2), # 10.0.32.0/20
  ]

  # Public subnets for load balancers and NAT gateways
  public_subnets = [
    cidrsubnet(var.vpc_cidr, 8, 100), # 10.0.100.0/24
    cidrsubnet(var.vpc_cidr, 8, 101), # 10.0.101.0/24
    cidrsubnet(var.vpc_cidr, 8, 102), # 10.0.102.0/24
  ]

  # Enable NAT Gateway for private subnet internet access (for package downloads)
  enable_nat_gateway = true
  single_nat_gateway = var.environment == "dev" ? true : false # Cost optimization for dev

  # Enable DNS hostnames for RDS endpoint resolution
  enable_dns_hostnames = true
  enable_dns_support   = true

  # Resource Tagging
  tags = {
    Name        = "${var.project_name}-${var.environment}-vpc"
    Terraform   = "true"
    Environment = var.environment
  }

  # Subnet-specific tags for Kubernetes integration
  public_subnet_tags = {
    "kubernetes.io/role/elb"                                    = "1"
    "kubernetes.io/cluster/${var.project_name}-${var.environment}" = "shared"
  }

  private_subnet_tags = {
    "kubernetes.io/role/internal-elb"                           = "1"
    "kubernetes.io/cluster/${var.project_name}-${var.environment}" = "shared"
  }
}

#
# EKS Module - Kubernetes Cluster
#
# Creates Amazon EKS cluster for containerized Spring Boot backend and React frontend.
# Replaces CICS transaction processing with container orchestration.
#
# Dependencies: VPC must be created first for subnet and security group references
#
module "eks" {
  source = "./modules/eks"

  # Explicit dependency on VPC module
  depends_on = [module.vpc]

  # Cluster Configuration
  cluster_name    = "${var.project_name}-${var.environment}"
  cluster_version = var.eks_cluster_version

  # Network Configuration
  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnet_ids

  # Node Group Configuration
  # Instance types selected for Spring Boot memory requirements and React build processes
  node_instance_types = var.eks_node_instance_types
  desired_size        = var.eks_desired_nodes
  min_size            = var.eks_min_nodes
  max_size            = var.eks_max_nodes

  # Enable IRSA (IAM Roles for Service Accounts) for pod-level IAM permissions
  enable_irsa = true

  # Cluster Addons
  cluster_addons = {
    coredns = {
      most_recent = true
    }
    kube-proxy = {
      most_recent = true
    }
    vpc-cni = {
      most_recent = true
    }
    aws-ebs-csi-driver = {
      most_recent = true
    }
  }

  # Cluster Logging
  # Enable control plane logging for audit trail and troubleshooting
  cluster_enabled_log_types = ["api", "audit", "authenticator", "controllerManager", "scheduler"]

  # Resource Tagging
  tags = {
    Name        = "${var.project_name}-${var.environment}-eks"
    Environment = var.environment
    Application = "CardDemo"
  }
}

#
# RDS Module - PostgreSQL Database
#
# Creates Amazon RDS PostgreSQL 16.6 instance replacing VSAM datasets.
# Implements relational database for account, card, customer, and transaction data.
#
# Dependencies: VPC must be created first for subnet group and security group configuration
#
module "rds" {
  source = "./modules/rds"

  # Explicit dependency on VPC module
  depends_on = [module.vpc]

  # Database Instance Configuration
  identifier     = "${var.project_name}-${var.environment}-postgres"
  engine         = "postgres"
  engine_version = "16.6" # Verified PostgreSQL version from Agent Action Plan

  # Instance Sizing
  instance_class    = var.rds_instance_class
  allocated_storage = var.rds_allocated_storage
  storage_encrypted = true
  storage_type      = "gp3" # General Purpose SSD (gp3) for cost-effective performance

  # Network Configuration
  vpc_id             = module.vpc.vpc_id
  subnet_ids         = module.vpc.private_subnet_ids
  security_group_ids = [module.vpc.database_security_group_id]

  # Multi-AZ deployment for high availability (production only)
  multi_az = var.environment == "prod" ? true : false

  # Database Configuration
  database_name = var.rds_database_name
  port          = 5432

  # Credentials Management
  # Database master username and password managed through AWS Secrets Manager
  # Referenced by Spring Boot application via Kubernetes secrets
  manage_master_user_password = true

  # Backup Configuration
  # Aligns with mainframe backup requirements for data protection
  backup_retention_period = var.rds_backup_retention_days
  backup_window           = "03:00-04:00" # UTC, during low-traffic period
  maintenance_window      = "sun:04:00-sun:05:00"

  # Backup replication to secondary region for disaster recovery (production only)
  backup_replication_enabled = var.environment == "prod" ? true : false

  # Performance Insights for query performance monitoring
  performance_insights_enabled          = true
  performance_insights_retention_period = 7

  # Enable enhanced monitoring for RDS metrics
  monitoring_interval = 60
  monitoring_role_arn = module.vpc.rds_monitoring_role_arn

  # Deletion Protection
  # Prevent accidental deletion in production environment
  deletion_protection = var.environment == "prod" ? true : false
  skip_final_snapshot = var.environment == "dev" ? true : false

  # Parameter Group for PostgreSQL tuning
  # Optimized for OLTP workload matching CICS transaction patterns
  parameter_group_family = "postgres16"
  parameters = [
    {
      name  = "max_connections"
      value = "200" # Support 10,000 TPS with connection pooling
    },
    {
      name  = "shared_buffers"
      value = "{DBInstanceClassMemory/32768}" # 25% of instance memory
    },
    {
      name  = "effective_cache_size"
      value = "{DBInstanceClassMemory/16384}" # 75% of instance memory
    },
    {
      name  = "maintenance_work_mem"
      value = "2097152" # 2GB for index creation and vacuuming
    },
    {
      name  = "random_page_cost"
      value = "1.1" # SSD optimization
    },
    {
      name  = "effective_io_concurrency"
      value = "200" # SSD optimization
    },
    {
      name  = "work_mem"
      value = "10485" # 10MB per operation
    },
    {
      name  = "log_statement"
      value = "mod" # Log data-modifying statements for audit trail
    },
    {
      name  = "log_min_duration_statement"
      value = "1000" # Log queries slower than 1 second
    }
  ]

  # Resource Tagging
  tags = {
    Name        = "${var.project_name}-${var.environment}-rds"
    Environment = var.environment
    Application = "CardDemo"
    Engine      = "PostgreSQL"
    Version     = "16.6"
  }
}

#
# Monitoring Module - Observability Stack (Conditional)
#
# Deploys Prometheus, Grafana, and ELK stack for application monitoring and logging.
# Replaces mainframe monitoring tools with cloud-native observability.
#
# Conditional deployment based on var.enable_monitoring flag (enabled for non-dev environments)
# Dependencies: EKS cluster must be operational for monitoring stack deployment
#
module "monitoring" {
  source = "./modules/monitoring"

  # Conditional deployment
  count = var.enable_monitoring ? 1 : 0

  # Explicit dependency on EKS module
  depends_on = [module.eks]

  # Cluster Configuration
  eks_cluster_id       = module.eks.cluster_id
  eks_cluster_endpoint = module.eks.cluster_endpoint

  # Monitoring Stack Configuration
  namespace = "monitoring"

  # Prometheus Configuration
  enable_prometheus = true
  prometheus_retention = "30d"

  # Grafana Configuration
  enable_grafana          = true
  grafana_admin_password  = var.grafana_admin_password # Managed in Terraform Cloud/AWS Secrets Manager
  grafana_ingress_enabled = true
  grafana_ingress_host    = "grafana.${var.project_name}-${var.environment}.${var.domain_name}"

  # ELK Stack Configuration (Elasticsearch, Logstash, Kibana)
  enable_elasticsearch = true
  elasticsearch_storage_size = "100Gi"
  elasticsearch_replicas     = var.environment == "prod" ? 3 : 1

  # Alerting Configuration
  enable_alertmanager = true
  alertmanager_slack_webhook = var.alertmanager_slack_webhook

  # Resource Tagging
  tags = {
    Name        = "${var.project_name}-${var.environment}-monitoring"
    Environment = var.environment
    Application = "CardDemo"
    Component   = "Observability"
  }
}
