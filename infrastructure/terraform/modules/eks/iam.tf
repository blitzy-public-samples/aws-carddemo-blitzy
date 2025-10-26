# Copyright Amazon.com, Inc. or its affiliates.
# All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License").
# You may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied. See the License for the specific
# language governing permissions and limitations under the License

################################################################################
# EKS IAM Roles and Policies
################################################################################
# Converted from: app/jcl/COMBTRAN.jcl, app/jcl/DALYREJS.jcl
# Original function: Mainframe batch job security context (RACF-secured)
# Conversion notes:
# - Replaces RACF mainframe security with AWS IAM cloud-native RBAC
# - EKS cluster role replaces CICS region security context
# - Worker node role replaces batch job execution security context
# - OIDC provider enables Kubernetes service account to IAM role mapping
# - Maintains principle of least privilege from mainframe security model
################################################################################

################################################################################
# EKS Cluster IAM Role
################################################################################
# This role replaces the RACF security context for the CICS transaction
# processing region. In cloud-native architecture, the EKS control plane
# needs permissions to manage AWS resources on behalf of the cluster.
################################################################################

resource "aws_iam_role" "cluster" {
  name               = "${var.cluster_name}-cluster-role"
  description        = "IAM role for EKS cluster control plane - replaces mainframe CICS region security"
  assume_role_policy = data.aws_iam_policy_document.cluster_assume_role_policy.json

  tags = merge(
    var.tags,
    {
      Name                     = "${var.cluster_name}-cluster-role"
      "eks:cluster-name"       = var.cluster_name
      Purpose                  = "EKS cluster control plane"
      MigrationSource          = "Mainframe RACF CICS security"
      SecurityContext          = "cluster-control-plane"
    }
  )
}

# Trust policy for EKS cluster role
# Allows the EKS service to assume this role
data "aws_iam_policy_document" "cluster_assume_role_policy" {
  statement {
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }

    actions = ["sts:AssumeRole"]
  }
}

# Attach AWS managed policy: AmazonEKSClusterPolicy
# This policy provides Kubernetes cluster management permissions
resource "aws_iam_role_policy_attachment" "cluster_policy" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
  role       = aws_iam_role.cluster.name
}

# Attach AWS managed policy: AmazonEKSVPCResourceController
# This policy allows the cluster to manage ENIs for pod networking
resource "aws_iam_role_policy_attachment" "cluster_vpc_resource_controller" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSVPCResourceController"
  role       = aws_iam_role.cluster.name
}

################################################################################
# EKS Worker Node IAM Role
################################################################################
# This role replaces the RACF security context for batch job execution
# (COMBTRAN.jcl, DALYREJS.jcl). Worker nodes run the containerized
# Spring Boot backend and batch processing workloads.
################################################################################

resource "aws_iam_role" "node_group" {
  name               = "${var.cluster_name}-node-group-role"
  description        = "IAM role for EKS worker nodes - replaces mainframe batch job security context"
  assume_role_policy = data.aws_iam_policy_document.node_assume_role_policy.json

  tags = merge(
    var.tags,
    {
      Name                     = "${var.cluster_name}-node-group-role"
      "eks:cluster-name"       = var.cluster_name
      Purpose                  = "EKS worker node instances"
      MigrationSource          = "Mainframe RACF batch job security (COMBTRAN, DALYREJS)"
      SecurityContext          = "worker-node-group"
    }
  )
}

# Trust policy for EKS worker node role
# Allows EC2 instances to assume this role
data "aws_iam_policy_document" "node_assume_role_policy" {
  statement {
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }

    actions = ["sts:AssumeRole"]
  }
}

# Attach AWS managed policy: AmazonEKSWorkerNodePolicy
# This policy allows worker nodes to connect to EKS cluster
resource "aws_iam_role_policy_attachment" "node_worker_policy" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy"
  role       = aws_iam_role.node_group.name
}

# Attach AWS managed policy: AmazonEKS_CNI_Policy
# This policy allows the VPC CNI plugin to manage network interfaces
resource "aws_iam_role_policy_attachment" "node_cni_policy" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
  role       = aws_iam_role.node_group.name
}

# Attach AWS managed policy: AmazonEC2ContainerRegistryReadOnly
# This policy allows worker nodes to pull container images from ECR
resource "aws_iam_role_policy_attachment" "node_ecr_policy" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
  role       = aws_iam_role.node_group.name
}

# Attach AWS managed policy: AmazonSSMManagedInstanceCore
# This policy allows Systems Manager access for operational management
resource "aws_iam_role_policy_attachment" "node_ssm_policy" {
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
  role       = aws_iam_role.node_group.name
}

################################################################################
# OIDC Provider for IAM Roles for Service Accounts (IRSA)
################################################################################
# This enables Kubernetes service accounts to assume IAM roles, providing
# fine-grained security controls equivalent to mainframe RACF resource profiles.
# Replaces RACF user-to-resource authorization with K8s SA-to-IAM-role mapping.
################################################################################

# Data source to fetch TLS certificate from EKS cluster OIDC endpoint
data "tls_certificate" "cluster" {
  count = var.enable_irsa ? 1 : 0
  url   = var.cluster_oidc_issuer_url
}

# Create OIDC provider for the EKS cluster
# This allows Kubernetes service accounts to authenticate with AWS IAM
resource "aws_iam_openid_connect_provider" "cluster" {
  count = var.enable_irsa ? 1 : 0

  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.cluster[0].certificates[0].sha1_fingerprint]
  url             = var.cluster_oidc_issuer_url

  tags = merge(
    var.tags,
    {
      Name                     = "${var.cluster_name}-oidc-provider"
      "eks:cluster-name"       = var.cluster_name
      Purpose                  = "IRSA OIDC provider"
      MigrationSource          = "Mainframe RACF resource profiles"
      SecurityContext          = "service-account-federation"
    }
  )
}

################################################################################
# IAM Policy Document for Service Account Trust Relationships
################################################################################
# This policy document template can be used by service-specific IAM roles
# to establish trust with Kubernetes service accounts. This replaces the
# mainframe pattern of RACF PERMIT commands for resource authorization.
################################################################################

# Example trust policy document for service accounts
# This can be referenced by application-specific IAM roles
data "aws_iam_policy_document" "service_account_assume_role_policy" {
  count = var.enable_irsa ? 1 : 0

  statement {
    effect = "Allow"

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.cluster[0].arn]
    }

    actions = ["sts:AssumeRoleWithWebIdentity"]

    condition {
      test     = "StringEquals"
      variable = "${replace(var.cluster_oidc_issuer_url, "https://", "")}:sub"
      values   = ["system:serviceaccount:${var.service_account_namespace}:${var.service_account_name}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${replace(var.cluster_oidc_issuer_url, "https://", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

################################################################################
# IAM Role for Backend Application Service Account
################################################################################
# This role allows the Spring Boot backend pods to access AWS services
# (RDS, S3, Secrets Manager) via Kubernetes service account.
# Replaces mainframe CICS transaction security context.
################################################################################

resource "aws_iam_role" "backend_service_account" {
  count              = var.enable_irsa && var.create_backend_irsa_role ? 1 : 0
  name               = "${var.cluster_name}-backend-sa-role"
  description        = "IAM role for backend service account - replaces CICS transaction security"
  assume_role_policy = data.aws_iam_policy_document.backend_sa_assume_role_policy[0].json

  tags = merge(
    var.tags,
    {
      Name                     = "${var.cluster_name}-backend-sa-role"
      "eks:cluster-name"       = var.cluster_name
      Purpose                  = "Backend application service account"
      MigrationSource          = "Mainframe CICS transaction security"
      SecurityContext          = "backend-workload"
    }
  )
}

# Trust policy for backend service account
data "aws_iam_policy_document" "backend_sa_assume_role_policy" {
  count = var.enable_irsa && var.create_backend_irsa_role ? 1 : 0

  statement {
    effect = "Allow"

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.cluster[0].arn]
    }

    actions = ["sts:AssumeRoleWithWebIdentity"]

    condition {
      test     = "StringEquals"
      variable = "${replace(var.cluster_oidc_issuer_url, "https://", "")}:sub"
      values   = ["system:serviceaccount:carddemo:backend-sa"]
    }

    condition {
      test     = "StringEquals"
      variable = "${replace(var.cluster_oidc_issuer_url, "https://", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

# Inline policy for backend service account - RDS access
resource "aws_iam_role_policy" "backend_rds_access" {
  count  = var.enable_irsa && var.create_backend_irsa_role ? 1 : 0
  name   = "backend-rds-access"
  role   = aws_iam_role.backend_service_account[0].id
  policy = data.aws_iam_policy_document.backend_rds_access[0].json
}

data "aws_iam_policy_document" "backend_rds_access" {
  count = var.enable_irsa && var.create_backend_irsa_role ? 1 : 0

  statement {
    effect = "Allow"
    actions = [
      "rds:DescribeDBInstances",
      "rds:DescribeDBClusters",
      "rds:ListTagsForResource"
    ]
    resources = ["*"]
  }

  statement {
    effect = "Allow"
    actions = [
      "rds-db:connect"
    ]
    resources = [
      "arn:aws:rds-db:${var.aws_region}:${var.aws_account_id}:dbuser:*/carddemo_app"
    ]
  }
}

# Inline policy for backend service account - Secrets Manager access
resource "aws_iam_role_policy" "backend_secrets_access" {
  count  = var.enable_irsa && var.create_backend_irsa_role ? 1 : 0
  name   = "backend-secrets-access"
  role   = aws_iam_role.backend_service_account[0].id
  policy = data.aws_iam_policy_document.backend_secrets_access[0].json
}

data "aws_iam_policy_document" "backend_secrets_access" {
  count = var.enable_irsa && var.create_backend_irsa_role ? 1 : 0

  statement {
    effect = "Allow"
    actions = [
      "secretsmanager:GetSecretValue",
      "secretsmanager:DescribeSecret"
    ]
    resources = [
      "arn:aws:secretsmanager:${var.aws_region}:${var.aws_account_id}:secret:carddemo/*"
    ]
  }
}

# Inline policy for backend service account - CloudWatch Logs
resource "aws_iam_role_policy" "backend_cloudwatch_logs" {
  count  = var.enable_irsa && var.create_backend_irsa_role ? 1 : 0
  name   = "backend-cloudwatch-logs"
  role   = aws_iam_role.backend_service_account[0].id
  policy = data.aws_iam_policy_document.backend_cloudwatch_logs[0].json
}

data "aws_iam_policy_document" "backend_cloudwatch_logs" {
  count = var.enable_irsa && var.create_backend_irsa_role ? 1 : 0

  statement {
    effect = "Allow"
    actions = [
      "logs:CreateLogGroup",
      "logs:CreateLogStream",
      "logs:PutLogEvents",
      "logs:DescribeLogStreams"
    ]
    resources = [
      "arn:aws:logs:${var.aws_region}:${var.aws_account_id}:log-group:/aws/eks/${var.cluster_name}/backend:*"
    ]
  }
}

################################################################################
# IAM Role for Batch Job Service Account
################################################################################
# This role allows Spring Batch job pods to access AWS services.
# Directly replaces mainframe JCL batch job security (COMBTRAN, DALYREJS).
# Batch jobs need access to S3 for file processing and RDS for data updates.
################################################################################

resource "aws_iam_role" "batch_service_account" {
  count              = var.enable_irsa && var.create_batch_irsa_role ? 1 : 0
  name               = "${var.cluster_name}-batch-sa-role"
  description        = "IAM role for batch job service account - replaces JCL batch security (COMBTRAN, DALYREJS)"
  assume_role_policy = data.aws_iam_policy_document.batch_sa_assume_role_policy[0].json

  tags = merge(
    var.tags,
    {
      Name                     = "${var.cluster_name}-batch-sa-role"
      "eks:cluster-name"       = var.cluster_name
      Purpose                  = "Batch processing service account"
      MigrationSource          = "Mainframe JCL batch jobs (COMBTRAN, DALYREJS)"
      SecurityContext          = "batch-workload"
    }
  )
}

# Trust policy for batch service account
data "aws_iam_policy_document" "batch_sa_assume_role_policy" {
  count = var.enable_irsa && var.create_batch_irsa_role ? 1 : 0

  statement {
    effect = "Allow"

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.cluster[0].arn]
    }

    actions = ["sts:AssumeRoleWithWebIdentity"]

    condition {
      test     = "StringEquals"
      variable = "${replace(var.cluster_oidc_issuer_url, "https://", "")}:sub"
      values   = ["system:serviceaccount:carddemo:batch-sa"]
    }

    condition {
      test     = "StringEquals"
      variable = "${replace(var.cluster_oidc_issuer_url, "https://", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

# Inline policy for batch service account - S3 access
resource "aws_iam_role_policy" "batch_s3_access" {
  count  = var.enable_irsa && var.create_batch_irsa_role ? 1 : 0
  name   = "batch-s3-access"
  role   = aws_iam_role.batch_service_account[0].id
  policy = data.aws_iam_policy_document.batch_s3_access[0].json
}

data "aws_iam_policy_document" "batch_s3_access" {
  count = var.enable_irsa && var.create_batch_irsa_role ? 1 : 0

  statement {
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:ListBucket"
    ]
    resources = [
      "arn:aws:s3:::${var.batch_data_bucket}",
      "arn:aws:s3:::${var.batch_data_bucket}/*"
    ]
  }
}

# Inline policy for batch service account - RDS access
resource "aws_iam_role_policy" "batch_rds_access" {
  count  = var.enable_irsa && var.create_batch_irsa_role ? 1 : 0
  name   = "batch-rds-access"
  role   = aws_iam_role.batch_service_account[0].id
  policy = data.aws_iam_policy_document.batch_rds_access[0].json
}

data "aws_iam_policy_document" "batch_rds_access" {
  count = var.enable_irsa && var.create_batch_irsa_role ? 1 : 0

  statement {
    effect = "Allow"
    actions = [
      "rds:DescribeDBInstances",
      "rds:DescribeDBClusters"
    ]
    resources = ["*"]
  }

  statement {
    effect = "Allow"
    actions = [
      "rds-db:connect"
    ]
    resources = [
      "arn:aws:rds-db:${var.aws_region}:${var.aws_account_id}:dbuser:*/carddemo_batch"
    ]
  }
}

# Inline policy for batch service account - CloudWatch Logs
resource "aws_iam_role_policy" "batch_cloudwatch_logs" {
  count  = var.enable_irsa && var.create_batch_irsa_role ? 1 : 0
  name   = "batch-cloudwatch-logs"
  role   = aws_iam_role.batch_service_account[0].id
  policy = data.aws_iam_policy_document.batch_cloudwatch_logs[0].json
}

data "aws_iam_policy_document" "batch_cloudwatch_logs" {
  count = var.enable_irsa && var.create_batch_irsa_role ? 1 : 0

  statement {
    effect = "Allow"
    actions = [
      "logs:CreateLogGroup",
      "logs:CreateLogStream",
      "logs:PutLogEvents",
      "logs:DescribeLogStreams"
    ]
    resources = [
      "arn:aws:logs:${var.aws_region}:${var.aws_account_id}:log-group:/aws/eks/${var.cluster_name}/batch:*"
    ]
  }
}

################################################################################
# Outputs
################################################################################
# These outputs expose IAM role ARNs for use by Kubernetes deployments
# and other Terraform modules. Replaces mainframe RACF profile references.
################################################################################

# Export cluster IAM role ARN
output "cluster_iam_role_arn" {
  description = "ARN of the EKS cluster IAM role"
  value       = aws_iam_role.cluster.arn
}

# Export cluster IAM role name
output "cluster_iam_role_name" {
  description = "Name of the EKS cluster IAM role"
  value       = aws_iam_role.cluster.name
}

# Export node group IAM role ARN
output "node_iam_role_arn" {
  description = "ARN of the EKS node group IAM role"
  value       = aws_iam_role.node_group.arn
}

# Export node group IAM role name
output "node_iam_role_name" {
  description = "Name of the EKS node group IAM role"
  value       = aws_iam_role.node_group.name
}

# Export OIDC provider ARN
output "oidc_provider_arn" {
  description = "ARN of the OIDC provider for IRSA"
  value       = var.enable_irsa ? aws_iam_openid_connect_provider.cluster[0].arn : null
}

# Export OIDC provider URL
output "oidc_provider_url" {
  description = "URL of the OIDC provider"
  value       = var.enable_irsa ? aws_iam_openid_connect_provider.cluster[0].url : null
}

# Export backend service account IAM role ARN
output "backend_service_account_role_arn" {
  description = "ARN of the backend service account IAM role"
  value       = var.enable_irsa && var.create_backend_irsa_role ? aws_iam_role.backend_service_account[0].arn : null
}

# Export backend service account IAM role name
output "backend_service_account_role_name" {
  description = "Name of the backend service account IAM role"
  value       = var.enable_irsa && var.create_backend_irsa_role ? aws_iam_role.backend_service_account[0].name : null
}

# Export batch service account IAM role ARN
output "batch_service_account_role_arn" {
  description = "ARN of the batch service account IAM role - replaces JCL batch security"
  value       = var.enable_irsa && var.create_batch_irsa_role ? aws_iam_role.batch_service_account[0].arn : null
}

# Export batch service account IAM role name
output "batch_service_account_role_name" {
  description = "Name of the batch service account IAM role"
  value       = var.enable_irsa && var.create_batch_irsa_role ? aws_iam_role.batch_service_account[0].name : null
}

