# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

################################################################################
# EKS Cluster Variables
# 
# This file defines input variables for the EKS module that provisions a
# Kubernetes cluster to host the modernized CardDemo application.
#
# Context: This EKS cluster replaces the IBM z/OS mainframe CICS transaction
# processing environment. The cluster will run:
# - Spring Boot REST API pods (replacing COBOL online transaction programs)
# - Spring Batch job pods (replacing JCL batch jobs like COMBTRAN, DALYREJS)
# - React SPA frontend pods (replacing BMS 3270 terminal screens)
# - PostgreSQL database pods (replacing VSAM KSDS data files)
#
# Migration Note: These variables support the infrastructure requirements
# specified in the Agent Action Plan section 0.3.5 (Technology Stack Versions)
# and section 0.4 (Transformation Mapping).
################################################################################

################################################################################
# Cluster Identification Variables
################################################################################

variable "cluster_name" {
  description = "Name of the EKS cluster. This will be used as a prefix for all related resources. Recommended format: carddemo-<environment>"
  type        = string

  validation {
    condition     = can(regex("^[a-zA-Z][a-zA-Z0-9-]*$", var.cluster_name)) && length(var.cluster_name) <= 100
    error_message = "Cluster name must start with a letter, contain only alphanumeric characters and hyphens, and be 100 characters or less."
  }
}

variable "kubernetes_version" {
  description = "Kubernetes version for the EKS cluster. Default is 1.31 as specified in the migration plan (section 0.3.5). Must be a valid EKS-supported version."
  type        = string
  default     = "1.31"

  validation {
    condition     = can(regex("^1\\.(2[89]|3[0-9])$", var.kubernetes_version))
    error_message = "Kubernetes version must be 1.28 or higher and in format X.YY (e.g., 1.31)."
  }
}

variable "environment" {
  description = "Environment name for resource tagging and identification (dev, test, prod). Used to distinguish between deployment stages during migration."
  type        = string

  validation {
    condition     = contains(["dev", "test", "staging", "prod"], var.environment)
    error_message = "Environment must be one of: dev, test, staging, prod."
  }
}

################################################################################
# VPC and Networking Variables
################################################################################

variable "vpc_id" {
  description = "ID of the VPC where the EKS cluster will be deployed. Must be an existing VPC created by the VPC module with appropriate CIDR blocks for pod networking."
  type        = string

  validation {
    condition     = can(regex("^vpc-[a-f0-9]{8,}$", var.vpc_id))
    error_message = "VPC ID must be a valid AWS VPC identifier (vpc-xxxxxxxxx)."
  }
}

variable "private_subnet_ids" {
  description = <<-EOT
    List of private subnet IDs for EKS worker nodes. Worker nodes will be deployed across these subnets for high availability.
    Minimum 2 subnets in different availability zones are required for production deployments.
    These subnets will host:
    - Spring Boot backend pods (replacing COBOL CICS programs)
    - Spring Batch job pods (replacing JCL batch jobs)
    - PostgreSQL database pods (replacing VSAM datasets)
  EOT
  type        = list(string)

  validation {
    condition     = length(var.private_subnet_ids) >= 2
    error_message = "At least 2 private subnet IDs are required for high availability across multiple availability zones."
  }

  validation {
    condition     = alltrue([for s in var.private_subnet_ids : can(regex("^subnet-[a-f0-9]{8,}$", s))])
    error_message = "All private subnet IDs must be valid AWS subnet identifiers (subnet-xxxxxxxxx)."
  }
}

variable "public_subnet_ids" {
  description = <<-EOT
    List of public subnet IDs for load balancers and NAT gateways. These subnets are used for:
    - Application Load Balancer (ALB) for frontend and backend ingress
    - Network Load Balancer (NLB) if needed for external integrations
    Optional but recommended for production deployments with external access requirements.
  EOT
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for s in var.public_subnet_ids : can(regex("^subnet-[a-f0-9]{8,}$", s))])
    error_message = "All public subnet IDs must be valid AWS subnet identifiers (subnet-xxxxxxxxx)."
  }
}

################################################################################
# Worker Node Configuration Variables
################################################################################

variable "node_instance_types" {
  description = <<-EOT
    List of EC2 instance types for EKS worker nodes. Multiple instance types enable EKS to optimize cost and availability.
    Default types (t3.large, t3.xlarge) provide:
    - t3.large: 2 vCPUs, 8 GiB RAM - suitable for most Spring Boot pods
    - t3.xlarge: 4 vCPUs, 16 GiB RAM - suitable for Spring Batch jobs and database pods
    Performance requirement: Must meet sub-200ms transaction response time SLA (Agent Action Plan section 0.7.7).
  EOT
  type        = list(string)
  default     = ["t3.large", "t3.xlarge"]

  validation {
    condition     = length(var.node_instance_types) > 0
    error_message = "At least one instance type must be specified for worker nodes."
  }
}

variable "node_disk_size" {
  description = <<-EOT
    Disk size in GB for worker node root volumes. Stores:
    - Container images for Spring Boot applications and Spring Batch jobs
    - Container logs and temporary storage
    - kubelet data and system files
    Minimum 50 GB recommended, default 100 GB provides buffer for multiple application versions during deployment.
  EOT
  type        = number
  default     = 100

  validation {
    condition     = var.node_disk_size >= 50 && var.node_disk_size <= 1000
    error_message = "Node disk size must be between 50 and 1000 GB."
  }
}

################################################################################
# Node Group Autoscaling Variables
################################################################################

variable "node_min_size" {
  description = <<-EOT
    Minimum number of worker nodes in the EKS node group.
    Default is 3 to ensure high availability across multiple availability zones.
    Migration context: Minimum capacity must support:
    - At least 3 backend pod replicas (replacing CICS regions)
    - At least 1 frontend pod replica (serving React SPA)
    - At least 1 database pod (PostgreSQL replacing VSAM)
  EOT
  type        = number
  default     = 3

  validation {
    condition     = var.node_min_size >= 1 && var.node_min_size <= var.node_max_size
    error_message = "Node min size must be at least 1 and not exceed node_max_size."
  }
}

variable "node_max_size" {
  description = <<-EOT
    Maximum number of worker nodes in the EKS node group for horizontal autoscaling.
    Default is 10 as specified in Agent Action Plan section 0.3.1 (Target Design).
    Autoscaling triggers:
    - CPU utilization > 70% (from Horizontal Pod Autoscaler)
    - Memory pressure
    - Pending pod scheduling requests
    Performance requirement: Must scale to handle 10,000 TPS peak transaction volumes.
  EOT
  type        = number
  default     = 10

  validation {
    condition     = var.node_max_size >= var.node_min_size && var.node_max_size <= 100
    error_message = "Node max size must be at least equal to node_min_size and not exceed 100."
  }
}

variable "node_desired_size" {
  description = <<-EOT
    Desired number of worker nodes at cluster launch.
    Default is 3 to match minimum size for initial deployment.
    The autoscaler will adjust this number based on workload demands between min and max size.
    Deployment strategy: Start with desired capacity, scale up during load testing, scale down during off-peak hours.
  EOT
  type        = number
  default     = 3

  validation {
    condition     = var.node_desired_size >= var.node_min_size && var.node_desired_size <= var.node_max_size
    error_message = "Node desired size must be between node_min_size and node_max_size."
  }
}

################################################################################
# Cluster Access Configuration Variables
################################################################################

variable "cluster_endpoint_private_access" {
  description = <<-EOT
    Enable private access to the Kubernetes API server endpoint from within the VPC.
    Recommended: true for production security.
    When enabled, worker nodes and pods can communicate with the API server using private networking without traversing the internet.
    Required for: kubectl commands from within VPC, CI/CD pipelines running in VPC.
  EOT
  type        = bool
  default     = true
}

variable "cluster_endpoint_public_access" {
  description = <<-EOT
    Enable public access to the Kubernetes API server endpoint from the internet.
    Recommended: true for development/testing, false for production with bastion host access.
    When enabled, administrators can use kubectl from outside the VPC (subject to security group rules).
    Security note: Even when enabled, access is restricted by IAM authentication and security groups.
  EOT
  type        = bool
  default     = true
}

variable "cluster_endpoint_public_access_cidrs" {
  description = <<-EOT
    List of CIDR blocks allowed to access the public Kubernetes API server endpoint.
    Default ["0.0.0.0/0"] allows access from any IP address (restricted by IAM authentication).
    Production recommendation: Restrict to specific IP ranges (corporate network, VPN, bastion hosts).
    Example: ["203.0.113.0/24", "198.51.100.0/24"] for specific office networks.
  EOT
  type        = list(string)
  default     = ["0.0.0.0/0"]

  validation {
    condition     = alltrue([for cidr in var.cluster_endpoint_public_access_cidrs : can(cidrhost(cidr, 0))])
    error_message = "All entries must be valid CIDR blocks (e.g., 10.0.0.0/16)."
  }
}

################################################################################
# IAM and Security Variables
################################################################################

variable "enable_irsa" {
  description = <<-EOT
    Enable IAM Roles for Service Accounts (IRSA) using OIDC provider.
    Recommended: true for production security best practices.
    When enabled, Kubernetes service accounts can assume IAM roles to access AWS services without embedding credentials.
    Use cases:
    - Spring Boot pods accessing AWS Secrets Manager for database credentials
    - Spring Batch jobs writing to S3 for backup/archive
    - Application pods publishing metrics to CloudWatch
    Migration context: Replaces mainframe RACF security with cloud-native IAM-based security.
  EOT
  type        = bool
  default     = true
}

variable "create_cluster_security_group" {
  description = <<-EOT
    Create a dedicated security group for the EKS cluster control plane.
    When true, module creates and manages security group rules for cluster-to-node communication.
    When false, you must provide an existing security group ID via cluster_security_group_id variable.
    Recommended: true for most deployments to ensure proper isolation.
  EOT
  type        = bool
  default     = true
}

variable "cluster_security_group_id" {
  description = <<-EOT
    Existing security group ID to attach to the EKS cluster control plane.
    Only used when create_cluster_security_group is false.
    Must allow ingress from worker nodes on port 443 (HTTPS) and egress to worker nodes on port 10250 (kubelet).
  EOT
  type        = string
  default     = ""
}

################################################################################
# Cluster Add-ons Configuration Variables
################################################################################

variable "cluster_addons" {
  description = <<-EOT
    Map of EKS cluster add-ons to enable and configure. Supported add-ons:
    - vpc-cni: Amazon VPC CNI plugin for pod networking (required)
    - coredns: CoreDNS for cluster DNS and service discovery (required)
    - kube-proxy: Network proxy for service load balancing (required)
    - aws-ebs-csi-driver: EBS CSI driver for persistent volume support (optional)
    Each add-on can specify version, configuration values, and resolve_conflicts behavior.
    Default: Enables essential add-ons (vpc-cni, coredns, kube-proxy) with recommended settings.
  EOT
  type        = map(any)
  default = {
    vpc-cni = {
      most_recent = true
      configuration_values = jsonencode({
        env = {
          ENABLE_PREFIX_DELEGATION = "true"
          WARM_ENI_TARGET          = "1"
          WARM_IP_TARGET           = "10"
        }
      })
    }
    coredns = {
      most_recent = true
    }
    kube-proxy = {
      most_recent = true
    }
  }
}

variable "enable_cluster_encryption" {
  description = <<-EOT
    Enable encryption of Kubernetes secrets at rest using AWS KMS.
    Recommended: true for production deployments handling sensitive data.
    When enabled, all Kubernetes secrets are encrypted using a customer-managed KMS key.
    Security context: Protects sensitive data like database credentials, JWT signing keys, and API tokens.
    Compliance: Required for PCI-DSS and SOC 2 compliance (relevant for credit card processing system).
  EOT
  type        = bool
  default     = true
}

variable "cluster_encryption_kms_key_id" {
  description = <<-EOT
    ARN of the AWS KMS key to use for cluster encryption.
    Only used when enable_cluster_encryption is true.
    If not provided, the module will create a new KMS key for cluster encryption.
    Format: arn:aws:kms:region:account-id:key/key-id
  EOT
  type        = string
  default     = ""
}

################################################################################
# Cluster Logging Configuration Variables
################################################################################

variable "cluster_enabled_log_types" {
  description = <<-EOT
    List of control plane logging types to enable in CloudWatch Logs. Available log types:
    - api: Kubernetes API server logs (kubectl requests, API calls)
    - audit: Kubernetes audit logs (who did what and when)
    - authenticator: IAM authenticator logs (authentication requests)
    - controllerManager: Controller manager logs (pod scheduling, service reconciliation)
    - scheduler: Scheduler logs (pod placement decisions)
    Default: All log types enabled for comprehensive observability during migration.
    Cost consideration: CloudWatch Logs charges apply - consider disabling in dev/test environments.
  EOT
  type        = list(string)
  default     = ["api", "audit", "authenticator", "controllerManager", "scheduler"]

  validation {
    condition = alltrue([
      for log_type in var.cluster_enabled_log_types :
      contains(["api", "audit", "authenticator", "controllerManager", "scheduler"], log_type)
    ])
    error_message = "Cluster log types must be one or more of: api, audit, authenticator, controllerManager, scheduler."
  }
}

variable "cluster_log_retention_days" {
  description = <<-EOT
    Number of days to retain cluster logs in CloudWatch Logs.
    Default: 90 days for compliance with audit retention requirements.
    Common values: 7 (dev), 30 (test), 90 (prod), 365 (compliance)
    Set to 0 for indefinite retention (not recommended due to cost).
  EOT
  type        = number
  default     = 90

  validation {
    condition     = contains([0, 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1827, 3653], var.cluster_log_retention_days)
    error_message = "Log retention days must be a valid CloudWatch Logs retention period."
  }
}

################################################################################
# Resource Tagging Variables
################################################################################

variable "tags" {
  description = <<-EOT
    Map of tags to apply to all resources created by this module.
    Tags are used for:
    - Cost allocation and tracking by project, environment, and team
    - Resource organization and filtering in AWS Console
    - Compliance and governance (e.g., data classification, owner)
    Recommended tags:
    - Project: carddemo-migration
    - ManagedBy: terraform
    - CostCenter: <your-cost-center>
    - DataClassification: confidential (credit card data)
    Migration context tags automatically added:
    - ReplacesMainframeSystem: CICS
    - MigrationPhase: infrastructure
  EOT
  type        = map(string)
  default     = {}
}

################################################################################
# Advanced Node Group Configuration Variables
################################################################################

variable "node_labels" {
  description = <<-EOT
    Map of Kubernetes labels to apply to worker nodes.
    Labels are used for:
    - Pod scheduling with nodeSelector (e.g., workload-type=backend)
    - Organizational grouping (e.g., environment=prod)
    - Monitoring and observability (e.g., node-role=application)
    Example labels for CardDemo migration:
    - workload-type: backend, frontend, batch, database
    - app-tier: web, application, data
  EOT
  type        = map(string)
  default     = {}
}

variable "node_taints" {
  description = <<-EOT
    List of Kubernetes taints to apply to worker nodes.
    Taints prevent pods from scheduling unless they have matching tolerations.
    Use cases:
    - Dedicate nodes to specific workloads (e.g., batch processing only)
    - Isolate nodes for testing or troubleshooting
    - Separate production and non-production workloads
    Format: List of objects with key, value, and effect (NoSchedule, PreferNoSchedule, NoExecute)
    Example: [{ key = "workload-type", value = "batch", effect = "NoSchedule" }]
  EOT
  type = list(object({
    key    = string
    value  = string
    effect = string
  }))
  default = []

  validation {
    condition = alltrue([
      for taint in var.node_taints :
      contains(["NoSchedule", "PreferNoSchedule", "NoExecute"], taint.effect)
    ])
    error_message = "Taint effect must be one of: NoSchedule, PreferNoSchedule, NoExecute."
  }
}

variable "enable_node_group_launch_template" {
  description = <<-EOT
    Enable custom launch template for worker nodes.
    When true, creates a launch template with:
    - Custom user data for node initialization
    - IMDSv2 enforcement for enhanced security
    - Detailed monitoring enabled
    - Custom EBS volume configuration
    Recommended: true for production deployments requiring customization.
  EOT
  type        = bool
  default     = true
}

variable "node_ami_type" {
  description = <<-EOT
    AMI type for worker nodes. Supported values:
    - AL2_x86_64: Amazon Linux 2 (default, recommended for most workloads)
    - AL2_x86_64_GPU: Amazon Linux 2 with GPU support (for ML workloads)
    - AL2_ARM_64: Amazon Linux 2 on ARM-based Graviton instances
    - BOTTLEROCKET_x86_64: Bottlerocket OS (minimal, security-focused)
    - BOTTLEROCKET_ARM_64: Bottlerocket OS on ARM
    Default: AL2_x86_64 for standard x86 workloads (Spring Boot, Spring Batch, PostgreSQL).
  EOT
  type        = string
  default     = "AL2_x86_64"

  validation {
    condition = contains([
      "AL2_x86_64",
      "AL2_x86_64_GPU",
      "AL2_ARM_64",
      "BOTTLEROCKET_x86_64",
      "BOTTLEROCKET_ARM_64"
    ], var.node_ami_type)
    error_message = "Node AMI type must be a valid EKS-supported AMI type."
  }
}

variable "node_capacity_type" {
  description = <<-EOT
    Capacity type for worker nodes. Options:
    - ON_DEMAND: Standard on-demand EC2 instances (default)
    - SPOT: Spot instances for cost savings (can be interrupted)
    Recommendation:
    - Production: ON_DEMAND for critical workloads requiring guaranteed capacity
    - Dev/Test: SPOT for cost optimization (acceptable interruption risk)
    - Hybrid: Mix of both using multiple node groups
    Performance requirement: Must support 10,000 TPS peak load without interruption.
  EOT
  type        = string
  default     = "ON_DEMAND"

  validation {
    condition     = contains(["ON_DEMAND", "SPOT"], var.node_capacity_type)
    error_message = "Node capacity type must be either ON_DEMAND or SPOT."
  }
}

################################################################################
# Cluster Update Configuration Variables
################################################################################

variable "node_update_config" {
  description = <<-EOT
    Update configuration for managed node group rolling updates.
    Controls how many nodes can be unavailable during updates.
    Options:
    - max_unavailable: Maximum number of nodes unavailable during update
    - max_unavailable_percentage: Maximum percentage of nodes unavailable during update
    Default: 1 node or 33% (whichever is greater) to ensure availability during updates.
    Zero-downtime requirement: Ensures Spring Boot pods remain available during node updates.
  EOT
  type = object({
    max_unavailable            = optional(number, 1)
    max_unavailable_percentage = optional(number, 33)
  })
  default = {
    max_unavailable            = 1
    max_unavailable_percentage = 33
  }
}

################################################################################
# Migration-Specific Variables
################################################################################

variable "migration_context" {
  description = <<-EOT
    Map of migration-specific metadata for documentation and tracking purposes.
    This variable is not used to configure resources but provides context about the migration.
    Recommended keys:
    - source_system: Name of mainframe system being replaced (e.g., "IBM z/OS CICS")
    - target_system: Name of new system (e.g., "AWS EKS with Spring Boot")
    - migration_phase: Current migration phase (e.g., "infrastructure", "application", "cutover")
    - project_code: Project tracking code
    - business_owner: Business stakeholder
    - technical_owner: Technical lead
  EOT
  type        = map(string)
  default = {
    source_system  = "IBM z/OS CICS Transaction Processing"
    target_system  = "AWS EKS Kubernetes Cluster"
    migration_type = "Mainframe to Cloud - COBOL to Java Spring Boot"
    project_name   = "CardDemo Modernization"
  }
}
