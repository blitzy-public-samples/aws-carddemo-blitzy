#
# Terraform Variables for VPC Module
# CardDemo Application - Cloud Migration Infrastructure
#
# Purpose: Define configurable parameters for AWS VPC networking infrastructure
#          supporting the CardDemo mainframe-to-cloud migration
#
# This module creates a highly-available VPC with public and private subnets
# across multiple availability zones, replacing mainframe VTAM networking
#

# ------------------------------------------------------------------------------
# VPC Configuration
# ------------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "CIDR block for the VPC. Default provides 65,536 IP addresses for CardDemo application, backend services, frontend services, and database instances"
  type        = string
  default     = "10.0.0.0/16"

  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "VPC CIDR must be a valid IPv4 CIDR block"
  }
}

variable "project_name" {
  description = "Project name used for resource naming and tagging. Identifies all resources as part of CardDemo migration"
  type        = string
  default     = "carddemo"

  validation {
    condition     = length(var.project_name) > 0 && length(var.project_name) <= 32
    error_message = "Project name must be between 1 and 32 characters"
  }
}

variable "environment" {
  description = "Environment name (dev, test, prod) for resource tagging and naming. Enables environment-specific configurations"
  type        = string

  validation {
    condition     = contains(["dev", "test", "prod"], var.environment)
    error_message = "Environment must be one of: dev, test, prod"
  }
}

# ------------------------------------------------------------------------------
# Availability Zones and Subnet Configuration
# ------------------------------------------------------------------------------

variable "availability_zones" {
  description = "List of AWS availability zones for multi-AZ deployment. High availability requires minimum 2 AZs for CardDemo application resilience"
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) >= 2
    error_message = "At least 2 availability zones are required for high availability"
  }
}

variable "public_subnet_cidrs" {
  description = "List of CIDR blocks for public subnets (one per availability zone). Public subnets host load balancers, NAT gateways, and bastion hosts. Default: 10.0.1.0/24, 10.0.2.0/24, 10.0.3.0/24"
  type        = list(string)
  default     = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]

  validation {
    condition     = length(var.public_subnet_cidrs) >= 2
    error_message = "At least 2 public subnets are required for high availability"
  }

  validation {
    condition     = alltrue([for cidr in var.public_subnet_cidrs : can(cidrhost(cidr, 0))])
    error_message = "All public subnet CIDRs must be valid IPv4 CIDR blocks"
  }
}

variable "private_subnet_cidrs" {
  description = "List of CIDR blocks for private subnets (one per availability zone). Private subnets host EKS worker nodes, RDS database, backend application pods, and internal services. Default: 10.0.11.0/24, 10.0.12.0/24, 10.0.13.0/24"
  type        = list(string)
  default     = ["10.0.11.0/24", "10.0.12.0/24", "10.0.13.0/24"]

  validation {
    condition     = length(var.private_subnet_cidrs) >= 2
    error_message = "At least 2 private subnets are required for high availability"
  }

  validation {
    condition     = alltrue([for cidr in var.private_subnet_cidrs : can(cidrhost(cidr, 0))])
    error_message = "All private subnet CIDRs must be valid IPv4 CIDR blocks"
  }
}

# ------------------------------------------------------------------------------
# NAT Gateway Configuration
# ------------------------------------------------------------------------------

variable "enable_nat_gateway" {
  description = "Enable NAT gateways for private subnet outbound internet connectivity. Required for EKS worker nodes to pull container images and access AWS services"
  type        = bool
  default     = true
}

variable "single_nat_gateway" {
  description = "Use a single NAT gateway for all private subnets to reduce costs. Recommended for dev/test environments. Production should use false for high availability (one NAT per AZ)"
  type        = bool
  default     = false
}

# ------------------------------------------------------------------------------
# DNS Configuration
# ------------------------------------------------------------------------------

variable "enable_dns_hostnames" {
  description = "Enable DNS hostnames in the VPC. Required for EKS cluster and RDS instances to have resolvable DNS names"
  type        = bool
  default     = true
}

variable "enable_dns_support" {
  description = "Enable DNS resolution in the VPC. Required for internal service discovery and AWS service endpoints"
  type        = bool
  default     = true
}

# ------------------------------------------------------------------------------
# VPC Flow Logs Configuration
# ------------------------------------------------------------------------------

variable "enable_flow_logs" {
  description = "Enable VPC Flow Logs to CloudWatch for network traffic monitoring and security analysis. Replaces mainframe network monitoring capabilities"
  type        = bool
  default     = true
}

variable "flow_logs_retention_days" {
  description = "Number of days to retain VPC Flow Logs in CloudWatch. Default 30 days balances audit requirements with storage costs"
  type        = number
  default     = 30

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1827, 3653], var.flow_logs_retention_days)
    error_message = "Flow logs retention must be a valid CloudWatch Logs retention period"
  }
}

# ------------------------------------------------------------------------------
# VPC Endpoints Configuration
# ------------------------------------------------------------------------------

variable "enable_vpc_endpoints" {
  description = "Map of VPC endpoints to enable. VPC endpoints provide private connectivity to AWS services without traversing the internet, improving security and reducing data transfer costs. S3 endpoint required for EKS to pull images from ECR. ECR endpoints required for private image repository access. CloudWatch Logs endpoint required for application logging"
  type        = map(bool)
  default = {
    s3                 = true  # S3 Gateway endpoint for EKS image pulls
    ecr_api            = true  # ECR API Interface endpoint for container registry
    ecr_dkr            = true  # ECR Docker Interface endpoint for image layers
    logs               = true  # CloudWatch Logs Interface endpoint for application logs
    ec2                = false # EC2 Interface endpoint (optional)
    ec2messages        = false # EC2 Messages Interface endpoint (optional for SSM)
    ssm                = false # Systems Manager Interface endpoint (optional)
    ssmmessages        = false # SSM Messages Interface endpoint (optional)
    kms                = false # KMS Interface endpoint (optional for encryption)
    sts                = false # STS Interface endpoint (optional for IAM operations)
    elasticloadbalancing = false # ELB Interface endpoint (optional)
  }
}

# ------------------------------------------------------------------------------
# Resource Tagging
# ------------------------------------------------------------------------------

variable "common_tags" {
  description = "Common tags to apply to all VPC resources for cost allocation, resource management, and compliance tracking"
  type        = map(string)
  default = {
    ManagedBy   = "Terraform"
    Application = "CardDemo"
    Migration   = "Mainframe-to-Cloud"
  }
}

# ------------------------------------------------------------------------------
# Advanced Configuration
# ------------------------------------------------------------------------------

variable "enable_network_acls" {
  description = "Enable custom Network ACLs for subnet-level security. Default true for defense-in-depth security posture"
  type        = bool
  default     = true
}

variable "enable_ipv6" {
  description = "Enable IPv6 support for the VPC. Default false as CardDemo mainframe legacy does not use IPv6"
  type        = bool
  default     = false
}

variable "vpc_endpoint_security_group_ids" {
  description = "Optional list of security group IDs to attach to VPC endpoints. If empty, module creates default security group allowing inbound HTTPS from VPC CIDR"
  type        = list(string)
  default     = []
}

variable "create_database_subnet_group" {
  description = "Create a database subnet group from private subnets for RDS instance deployment"
  type        = bool
  default     = true
}

variable "database_subnet_group_name" {
  description = "Name for the database subnet group. If empty, defaults to '{project_name}-{environment}-db-subnet-group'"
  type        = string
  default     = ""
}
