# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

# =============================================================================
# EKS Cluster Module - Main Configuration
# =============================================================================
#
# Purpose: Provision AWS EKS cluster to replace IBM mainframe CICS transaction
#          processing environment. This cluster hosts:
#          - Spring Boot REST API pods (replacing CICS online transactions)
#          - Spring Batch jobs as Kubernetes CronJobs (replacing JCL batch jobs)
#          - PostgreSQL StatefulSet (replacing VSAM datasets)
#
# Mainframe Migration Context:
#   COBOL Source Files → Kubernetes Workloads Mapping:
#   - COMBTRAN.jcl (Combined transaction processing) → CronJob running Spring Batch
#   - DALYREJS.jcl (Daily reject processing) → CronJob running Spring Batch
#   - 15 CICS online programs (COSGN00C, COMEN01C, etc.) → REST API Deployments
#   - 11 batch programs (CBACT*, CBTRN*, etc.) → CronJob batch processing
#
# Performance Requirements:
#   - Sub-200ms transaction response times (matching mainframe performance)
#   - Support 10,000 TPS peak transaction volumes
#   - Complete batch processing within 4-hour overnight cycles
#
# =============================================================================

terraform {
  required_version = ">= 1.9.0"
  
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.82.0"
    }
  }
}

# =============================================================================
# Data Sources
# =============================================================================

# Retrieve current AWS account and region information
data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# Retrieve latest EKS-optimized AMI
data "aws_ami" "eks_worker" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["amazon-eks-node-${var.kubernetes_version}-v*"]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }
}

# =============================================================================
# IAM Role for EKS Cluster
# =============================================================================
#
# This role allows EKS service to manage AWS resources on behalf of the cluster.
# Replaces mainframe CICS region authorization to access system resources.
#

resource "aws_iam_role" "cluster" {
  name               = "${var.cluster_name}-cluster-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "eks.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })

  tags = merge(
    var.tags,
    {
      Name        = "${var.cluster_name}-cluster-role"
      Purpose     = "EKS Cluster IAM Role - CICS Replacement"
      Component   = "IAM"
      Environment = var.environment
    }
  )
}

# Attach required AWS managed policies for EKS cluster
resource "aws_iam_role_policy_attachment" "cluster_amazon_eks_cluster_policy" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
  role       = aws_iam_role.cluster.name
}

resource "aws_iam_role_policy_attachment" "cluster_amazon_eks_vpc_resource_controller" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSVPCResourceController"
  role       = aws_iam_role.cluster.name
}

# =============================================================================
# IAM Role for EKS Node Group
# =============================================================================
#
# This role allows EC2 instances in the node group to access AWS services.
# Replaces mainframe z/OS system authorization for batch job execution.
#

resource "aws_iam_role" "node_group" {
  name               = "${var.cluster_name}-node-group-role"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "ec2.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })

  tags = merge(
    var.tags,
    {
      Name        = "${var.cluster_name}-node-group-role"
      Purpose     = "EKS Node Group IAM Role - Spring Boot/Batch Workloads"
      Component   = "IAM"
      Environment = var.environment
    }
  )
}

# Attach required AWS managed policies for EKS worker nodes
resource "aws_iam_role_policy_attachment" "node_amazon_eks_worker_node_policy" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy"
  role       = aws_iam_role.node_group.name
}

resource "aws_iam_role_policy_attachment" "node_amazon_eks_cni_policy" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
  role       = aws_iam_role.node_group.name
}

resource "aws_iam_role_policy_attachment" "node_amazon_ec2_container_registry_readonly" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
  role       = aws_iam_role.node_group.name
}

# CloudWatch logging permissions for container logs
resource "aws_iam_role_policy_attachment" "node_cloudwatch_logs" {
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
  role       = aws_iam_role.node_group.name
}

# =============================================================================
# KMS Key for EKS Cluster Encryption
# =============================================================================
#
# Encryption at rest for Kubernetes secrets stored in etcd.
# Replaces mainframe dataset encryption (RACF encryption controls).
#

resource "aws_kms_key" "eks" {
  description             = "KMS key for EKS cluster ${var.cluster_name} secrets encryption"
  deletion_window_in_days = 10
  enable_key_rotation     = true

  tags = merge(
    var.tags,
    {
      Name        = "${var.cluster_name}-eks-encryption-key"
      Purpose     = "EKS Secrets Encryption"
      Component   = "Security"
      Environment = var.environment
    }
  )
}

resource "aws_kms_alias" "eks" {
  name          = "alias/${var.cluster_name}-eks"
  target_key_id = aws_kms_key.eks.key_id
}

# =============================================================================
# Security Group for EKS Cluster
# =============================================================================
#
# Additional security group for cluster-level security controls.
# Complements the default security group created by EKS.
#

resource "aws_security_group" "cluster" {
  name        = "${var.cluster_name}-cluster-sg"
  description = "Security group for EKS cluster ${var.cluster_name}"
  vpc_id      = var.vpc_id

  tags = merge(
    var.tags,
    {
      Name        = "${var.cluster_name}-cluster-sg"
      Purpose     = "EKS Cluster Security Group"
      Component   = "Networking"
      Environment = var.environment
    }
  )
}

# Allow HTTPS inbound from VPC CIDR (for API server access)
resource "aws_security_group_rule" "cluster_ingress_vpc_https" {
  description       = "Allow HTTPS inbound from VPC for Kubernetes API"
  type              = "ingress"
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = [var.vpc_cidr_block]
  security_group_id = aws_security_group.cluster.id
}

# Allow all outbound traffic
resource "aws_security_group_rule" "cluster_egress_all" {
  description       = "Allow all outbound traffic"
  type              = "egress"
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
  security_group_id = aws_security_group.cluster.id
}

# =============================================================================
# CloudWatch Log Group for EKS Cluster Logs
# =============================================================================
#
# Centralized logging for cluster control plane logs.
# Replaces mainframe system logs (SYSLOG, CICS logs).
#

resource "aws_cloudwatch_log_group" "eks_cluster" {
  name              = "/aws/eks/${var.cluster_name}/cluster"
  retention_in_days = var.cluster_log_retention_days

  tags = merge(
    var.tags,
    {
      Name        = "${var.cluster_name}-cluster-logs"
      Purpose     = "EKS Cluster Control Plane Logs"
      Component   = "Observability"
      Environment = var.environment
    }
  )
}

# =============================================================================
# Launch Template for Node Group
# =============================================================================
#
# Customizes EC2 instances launched as EKS worker nodes.
# Enables IMDSv2 for enhanced security and adds monitoring.
#

resource "aws_launch_template" "node_group" {
  name_prefix = "${var.cluster_name}-node-"
  description = "Launch template for EKS node group ${var.cluster_name}"

  # Use latest EKS-optimized AMI
  image_id = data.aws_ami.eks_worker.id

  # Instance metadata service configuration (IMDSv2)
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"  # Enforce IMDSv2
    http_put_response_hop_limit = 1
    instance_metadata_tags      = "enabled"
  }

  # Enable detailed monitoring
  monitoring {
    enabled = true
  }

  # Block device mapping for root volume
  block_device_mappings {
    device_name = "/dev/xvda"

    ebs {
      volume_size           = var.node_disk_size
      volume_type           = "gp3"
      iops                  = 3000
      throughput            = 125
      delete_on_termination = true
      encrypted             = true
      kms_key_id            = aws_kms_key.eks.arn
    }
  }

  # User data for node bootstrapping
  user_data = base64encode(templatefile("${path.module}/templates/userdata.sh.tpl", {
    cluster_name        = var.cluster_name
    cluster_endpoint    = aws_eks_cluster.main.endpoint
    cluster_ca          = aws_eks_cluster.main.certificate_authority[0].data
    bootstrap_arguments = var.bootstrap_extra_args
  }))

  # Tag instances created from this template
  tag_specifications {
    resource_type = "instance"

    tags = merge(
      var.tags,
      {
        Name        = "${var.cluster_name}-node"
        Purpose     = "EKS Worker Node - Spring Boot/Batch Execution"
        Component   = "Compute"
        Environment = var.environment
      }
    )
  }

  tag_specifications {
    resource_type = "volume"

    tags = merge(
      var.tags,
      {
        Name        = "${var.cluster_name}-node-volume"
        Purpose     = "EKS Worker Node Storage"
        Component   = "Storage"
        Environment = var.environment
      }
    )
  }

  tags = merge(
    var.tags,
    {
      Name        = "${var.cluster_name}-node-launch-template"
      Purpose     = "EKS Node Launch Template"
      Component   = "Compute"
      Environment = var.environment
    }
  )
}

# =============================================================================
# EKS Cluster Resource
# =============================================================================
#
# Core EKS cluster resource replacing mainframe CICS region.
# Hosts containerized Spring Boot applications and Spring Batch jobs.
#
# Mainframe to Cloud Mapping:
#   - CICS Transaction Server → EKS Cluster (Kubernetes control plane)
#   - CICS Regions → EKS Node Groups (worker nodes)
#   - CICS Programs (COBOL) → Kubernetes Deployments (Spring Boot containers)
#   - JCL Batch Jobs → Kubernetes CronJobs (Spring Batch containers)
#
# Performance Target:
#   - Maintain sub-200ms response times for card authorization requests
#   - Support 10,000 TPS throughput (matching mainframe capacity)
#   - Enable horizontal scaling (3-10 nodes) based on load
#

resource "aws_eks_cluster" "main" {
  name     = var.cluster_name
  version  = var.kubernetes_version
  role_arn = aws_iam_role.cluster.arn

  # VPC configuration for cluster networking
  vpc_config {
    subnet_ids = concat(
      var.private_subnet_ids,
      var.public_subnet_ids
    )

    endpoint_private_access = var.endpoint_private_access
    endpoint_public_access  = var.endpoint_public_access
    public_access_cidrs     = var.endpoint_public_access_cidrs

    security_group_ids = [aws_security_group.cluster.id]
  }

  # Enable control plane logging to CloudWatch
  # Equivalent to mainframe CICS transaction logging and system logs
  enabled_cluster_log_types = [
    "api",              # Kubernetes API server logs
    "audit",            # Kubernetes audit logs (RACF audit equivalent)
    "authenticator",    # IAM authenticator logs (RACF authentication equivalent)
    "controllerManager",# Controller manager logs
    "scheduler"         # Scheduler logs
  ]

  # Encryption configuration for Kubernetes secrets
  # Replaces mainframe dataset encryption (RACF encryption)
  encryption_config {
    provider {
      key_arn = aws_kms_key.eks.arn
    }
    resources = ["secrets"]
  }

  # Kubernetes API server access logging
  depends_on = [
    aws_iam_role_policy_attachment.cluster_amazon_eks_cluster_policy,
    aws_iam_role_policy_attachment.cluster_amazon_eks_vpc_resource_controller,
    aws_cloudwatch_log_group.eks_cluster
  ]

  # Cluster creation and deletion timeouts
  timeouts {
    create = "30m"
    delete = "15m"
  }

  tags = merge(
    var.tags,
    {
      Name                                        = var.cluster_name
      Purpose                                     = "CICS Transaction Processing Replacement"
      Component                                   = "Kubernetes"
      Environment                                 = var.environment
      ManagedBy                                   = "Terraform"
      "SourceSystem"                              = "IBM z/OS Mainframe"
      "TargetSystem"                              = "AWS EKS"
      "MigrationPhase"                            = "Technology Stack Migration"
      "kubernetes.io/cluster/${var.cluster_name}" = "owned"
    }
  )
}

# =============================================================================
# EKS Node Group Resource
# =============================================================================
#
# Managed node group for worker nodes running containerized workloads.
# Replaces mainframe LPAR (Logical Partition) resources.
#
# Node Group Configuration:
#   - Min: 3 nodes (high availability across 3 AZs)
#   - Max: 10 nodes (autoscaling for peak transaction volumes)
#   - Desired: 3 nodes (baseline capacity for normal operations)
#   - Instance Types: t3.large, t3.xlarge (burstable compute for variable workloads)
#   - Capacity Type: ON_DEMAND (consistent performance matching mainframe)
#
# Workload Types:
#   - Spring Boot REST API pods (CICS online transaction replacement)
#   - Spring Batch job pods (JCL batch job replacement)
#   - PostgreSQL StatefulSet (VSAM dataset replacement)
#

resource "aws_eks_node_group" "main" {
  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "${var.cluster_name}-node-group"
  node_role_arn   = aws_iam_role.node_group.arn
  subnet_ids      = var.private_subnet_ids

  # Autoscaling configuration
  # Min 3 nodes ensures high availability across availability zones
  # Max 10 nodes supports peak transaction volumes (10,000 TPS)
  scaling_config {
    desired_size = var.node_group_desired_size
    min_size     = var.node_group_min_size
    max_size     = var.node_group_max_size
  }

  # Rolling update configuration
  # Max unavailable 25% ensures service continuity during updates
  # Equivalent to mainframe rolling IPL (Initial Program Load) procedures
  update_config {
    max_unavailable_percentage = 25
  }

  # Instance type configuration
  # t3.large: 2 vCPUs, 8 GB RAM - For moderate workloads
  # t3.xlarge: 4 vCPUs, 16 GB RAM - For intensive batch processing
  instance_types = var.node_instance_types

  # Capacity type: ON_DEMAND for consistent performance
  # Ensures predictable transaction response times (sub-200ms SLA)
  capacity_type = "ON_DEMAND"

  # Disk size for container images, logs, and ephemeral storage
  # 100 GB accommodates multiple container images and Spring Batch temporary files
  disk_size = var.node_disk_size

  # Launch template for additional node customization
  launch_template {
    id      = aws_launch_template.node_group.id
    version = "$Latest"
  }

  # Node labels for workload scheduling
  # Used by Kubernetes scheduler to place pods on appropriate nodes
  labels = {
    Environment   = var.environment
    NodeGroup     = "${var.cluster_name}-node-group"
    WorkloadType  = "mixed"
    Purpose       = "spring-boot-batch"
  }

  # Taint configuration (none by default - nodes accept all workloads)
  # Can be customized to dedicate nodes for specific workload types

  # Node group dependencies
  depends_on = [
    aws_iam_role_policy_attachment.node_amazon_eks_worker_node_policy,
    aws_iam_role_policy_attachment.node_amazon_eks_cni_policy,
    aws_iam_role_policy_attachment.node_amazon_ec2_container_registry_readonly,
    aws_iam_role_policy_attachment.node_cloudwatch_logs,
    aws_eks_cluster.main
  ]

  # Lifecycle management
  lifecycle {
    create_before_destroy = true
    ignore_changes        = [scaling_config[0].desired_size]
  }

  tags = merge(
    var.tags,
    {
      Name        = "${var.cluster_name}-node-group"
      Purpose     = "Spring Boot REST API and Spring Batch Workloads"
      Component   = "Compute"
      Environment = var.environment
      "Workloads" = "CICS-Programs-Replacement,JCL-Batch-Jobs-Replacement"
      
      # References to original mainframe batch jobs being replaced
      "ReplacesJCL" = "COMBTRAN.jcl,DALYREJS.jcl,CBACTJ*.jcl,CBTRNJ*.jcl"
      "ReplacesCICS" = "15-Online-Programs,11-Batch-Programs"
    }
  )
}

# =============================================================================
# EKS Add-ons
# =============================================================================
#
# Essential add-ons for cluster functionality.
# Equivalent to mainframe system components and utilities.
#

# VPC CNI add-on for pod networking
resource "aws_eks_addon" "vpc_cni" {
  cluster_name             = aws_eks_cluster.main.name
  addon_name               = "vpc-cni"
  addon_version            = var.vpc_cni_version
  resolve_conflicts_on_update = "PRESERVE"

  tags = merge(
    var.tags,
    {
      Name        = "${var.cluster_name}-vpc-cni"
      Purpose     = "Pod Networking"
      Component   = "Networking"
      Environment = var.environment
    }
  )
}

# CoreDNS add-on for service discovery
resource "aws_eks_addon" "coredns" {
  cluster_name             = aws_eks_cluster.main.name
  addon_name               = "coredns"
  addon_version            = var.coredns_version
  resolve_conflicts_on_update = "PRESERVE"

  depends_on = [aws_eks_node_group.main]

  tags = merge(
    var.tags,
    {
      Name        = "${var.cluster_name}-coredns"
      Purpose     = "Service Discovery"
      Component   = "Networking"
      Environment = var.environment
    }
  )
}

# kube-proxy add-on for service networking
resource "aws_eks_addon" "kube_proxy" {
  cluster_name             = aws_eks_cluster.main.name
  addon_name               = "kube-proxy"
  addon_version            = var.kube_proxy_version
  resolve_conflicts_on_update = "PRESERVE"

  tags = merge(
    var.tags,
    {
      Name        = "${var.cluster_name}-kube-proxy"
      Purpose     = "Service Proxy"
      Component   = "Networking"
      Environment = var.environment
    }
  )
}

# EBS CSI driver for persistent storage
resource "aws_eks_addon" "ebs_csi_driver" {
  cluster_name             = aws_eks_cluster.main.name
  addon_name               = "aws-ebs-csi-driver"
  addon_version            = var.ebs_csi_driver_version
  resolve_conflicts_on_update = "PRESERVE"

  service_account_role_arn = aws_iam_role.ebs_csi_driver.arn

  tags = merge(
    var.tags,
    {
      Name        = "${var.cluster_name}-ebs-csi-driver"
      Purpose     = "Persistent Volume Provisioning"
      Component   = "Storage"
      Environment = var.environment
    }
  )
}

# =============================================================================
# IAM Role for EBS CSI Driver
# =============================================================================
#
# Service account role for EBS CSI driver to manage EBS volumes.
# Required for PostgreSQL StatefulSet persistent volumes (VSAM replacement).
#

data "aws_iam_policy_document" "ebs_csi_driver_assume_role" {
  statement {
    effect = "Allow"

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.eks.arn]
    }

    actions = ["sts:AssumeRoleWithWebIdentity"]

    condition {
      test     = "StringEquals"
      variable = "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:sub"
      values   = ["system:serviceaccount:kube-system:ebs-csi-controller-sa"]
    }

    condition {
      test     = "StringEquals"
      variable = "${replace(aws_iam_openid_connect_provider.eks.url, "https://", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ebs_csi_driver" {
  name               = "${var.cluster_name}-ebs-csi-driver-role"
  assume_role_policy = data.aws_iam_policy_document.ebs_csi_driver_assume_role.json

  tags = merge(
    var.tags,
    {
      Name        = "${var.cluster_name}-ebs-csi-driver-role"
      Purpose     = "EBS CSI Driver Service Account Role"
      Component   = "Storage"
      Environment = var.environment
    }
  )
}

resource "aws_iam_role_policy_attachment" "ebs_csi_driver" {
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
  role       = aws_iam_role.ebs_csi_driver.name
}

# =============================================================================
# OIDC Provider for EKS
# =============================================================================
#
# OpenID Connect provider for EKS cluster to enable IAM roles for service accounts.
# Required for workload identity and fine-grained permissions.
#

data "tls_certificate" "eks" {
  url = aws_eks_cluster.main.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "eks" {
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.eks.certificates[0].sha1_fingerprint]
  url             = aws_eks_cluster.main.identity[0].oidc[0].issuer

  tags = merge(
    var.tags,
    {
      Name        = "${var.cluster_name}-oidc-provider"
      Purpose     = "EKS OIDC Provider"
      Component   = "Security"
      Environment = var.environment
    }
  )
}

# =============================================================================
# Outputs
# =============================================================================
#
# Outputs expose cluster information for use by other Terraform modules
# and for configuring kubectl access.
#

output "cluster_id" {
  description = "EKS cluster ID"
  value       = aws_eks_cluster.main.id
}

output "cluster_name" {
  description = "EKS cluster name"
  value       = aws_eks_cluster.main.name
}

output "cluster_endpoint" {
  description = "EKS cluster API endpoint"
  value       = aws_eks_cluster.main.endpoint
}

output "cluster_version" {
  description = "EKS cluster Kubernetes version"
  value       = aws_eks_cluster.main.version
}

output "cluster_security_group_id" {
  description = "Security group ID attached to the EKS cluster"
  value       = aws_security_group.cluster.id
}

output "cluster_iam_role_arn" {
  description = "IAM role ARN of the EKS cluster"
  value       = aws_iam_role.cluster.arn
}

output "cluster_certificate_authority_data" {
  description = "Base64 encoded certificate data for cluster authentication"
  value       = aws_eks_cluster.main.certificate_authority[0].data
  sensitive   = true
}

output "cluster_oidc_issuer_url" {
  description = "OIDC issuer URL for the EKS cluster"
  value       = aws_eks_cluster.main.identity[0].oidc[0].issuer
}

output "oidc_provider_arn" {
  description = "ARN of the OIDC provider for the EKS cluster"
  value       = aws_iam_openid_connect_provider.eks.arn
}

output "node_group_id" {
  description = "EKS node group ID"
  value       = aws_eks_node_group.main.id
}

output "node_group_arn" {
  description = "ARN of the EKS node group"
  value       = aws_eks_node_group.main.arn
}

output "node_group_role_arn" {
  description = "IAM role ARN of the EKS node group"
  value       = aws_iam_role.node_group.arn
}

output "node_security_group_id" {
  description = "Security group ID attached to the EKS nodes"
  value       = aws_eks_cluster.main.vpc_config[0].cluster_security_group_id
}

output "cluster_primary_security_group_id" {
  description = "Primary security group ID created by EKS for the cluster"
  value       = aws_eks_cluster.main.vpc_config[0].cluster_security_group_id
}

output "kms_key_id" {
  description = "KMS key ID used for cluster encryption"
  value       = aws_kms_key.eks.key_id
}

output "kms_key_arn" {
  description = "KMS key ARN used for cluster encryption"
  value       = aws_kms_key.eks.arn
}

output "cloudwatch_log_group_name" {
  description = "CloudWatch log group name for cluster logs"
  value       = aws_cloudwatch_log_group.eks_cluster.name
}

output "cloudwatch_log_group_arn" {
  description = "CloudWatch log group ARN for cluster logs"
  value       = aws_cloudwatch_log_group.eks_cluster.arn
}

