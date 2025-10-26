# ==============================================================================
# Terraform Output Definitions for EKS Module
# ==============================================================================
# Converted from mainframe batch infrastructure (COMBTRAN.jcl, DALYREJS.jcl)
# to cloud-native Kubernetes infrastructure outputs
#
# Purpose: Export critical EKS cluster information for use by:
# - Other Terraform modules (VPC, RDS, monitoring)
# - Deployment scripts (kubectl configuration, CI/CD pipelines)
# - Service account IAM role configuration (OIDC provider integration)
# - Security group rule additions
# - Cluster monitoring and management tools
#
# Copyright Amazon.com, Inc. or its affiliates.
# Licensed under the Apache License, Version 2.0
# ==============================================================================

# ------------------------------------------------------------------------------
# Cluster Identification Outputs
# ------------------------------------------------------------------------------

output "cluster_id" {
  description = "The unique identifier of the EKS cluster for reference by other AWS resources and Terraform modules"
  value       = aws_eks_cluster.main.id
}

output "cluster_name" {
  description = "The name of the EKS cluster for kubectl configuration and AWS CLI commands"
  value       = aws_eks_cluster.main.name
}

output "cluster_arn" {
  description = "The Amazon Resource Name (ARN) of the EKS cluster for IAM policies and resource tagging"
  value       = aws_eks_cluster.main.arn
}

# ------------------------------------------------------------------------------
# Cluster API Access Outputs
# ------------------------------------------------------------------------------

output "cluster_endpoint" {
  description = "The HTTPS endpoint URL for the Kubernetes API server - used for kubectl and CI/CD pipeline access"
  value       = aws_eks_cluster.main.endpoint
}

output "cluster_certificate_authority_data" {
  description = "Base64 encoded certificate data required for authenticating to the Kubernetes API server"
  value       = aws_eks_cluster.main.certificate_authority[0].data
  sensitive   = true
}

# ------------------------------------------------------------------------------
# Cluster Version Information
# ------------------------------------------------------------------------------

output "cluster_version" {
  description = "The Kubernetes version running on the EKS cluster (e.g., 1.31)"
  value       = aws_eks_cluster.main.version
}

output "cluster_platform_version" {
  description = "The platform version of the EKS cluster - used for tracking EKS-specific updates and features"
  value       = aws_eks_cluster.main.platform_version
}

# ------------------------------------------------------------------------------
# OIDC Provider Outputs for Service Account IAM Roles
# ------------------------------------------------------------------------------

output "cluster_oidc_issuer_url" {
  description = "The OpenID Connect identity provider URL - required for configuring IAM roles for Kubernetes service accounts (IRSA)"
  value       = aws_eks_cluster.main.identity[0].oidc[0].issuer
}

output "oidc_provider_arn" {
  description = "The ARN of the OIDC identity provider - used in IAM role trust policies for service account integration"
  value       = try(aws_iam_openid_connect_provider.cluster[0].arn, "")
}

# ------------------------------------------------------------------------------
# Cluster Security Group Outputs
# ------------------------------------------------------------------------------

output "cluster_security_group_id" {
  description = "The security group ID attached to the EKS cluster control plane - used for adding custom ingress/egress rules"
  value       = aws_eks_cluster.main.vpc_config[0].cluster_security_group_id
}

output "cluster_primary_security_group_id" {
  description = "The cluster primary security group ID that was created by EKS for the cluster - automatically applied to all nodes"
  value       = aws_eks_cluster.main.vpc_config[0].security_group_ids[0]
}

# ------------------------------------------------------------------------------
# Cluster IAM Role Output
# ------------------------------------------------------------------------------

output "cluster_iam_role_arn" {
  description = "The ARN of the IAM role used by the EKS cluster for AWS API calls - includes permissions for ELB, EC2, and ECR"
  value       = aws_eks_cluster.main.role_arn
}

output "cluster_iam_role_name" {
  description = "The name of the IAM role used by the EKS cluster - useful for policy attachments and role assumption"
  value       = aws_iam_role.cluster.name
}

# ------------------------------------------------------------------------------
# Node Group Outputs
# ------------------------------------------------------------------------------

output "node_group_id" {
  description = "The identifier of the EKS managed node group for monitoring and management operations"
  value       = try(aws_eks_node_group.main[0].id, "")
}

output "node_group_arn" {
  description = "The ARN of the EKS managed node group for resource tagging and IAM policies"
  value       = try(aws_eks_node_group.main[0].arn, "")
}

output "node_group_status" {
  description = "The current status of the node group (CREATING, ACTIVE, UPDATING, DELETING) - used for health monitoring"
  value       = try(aws_eks_node_group.main[0].status, "")
}

# ------------------------------------------------------------------------------
# Node Security Group and IAM Outputs
# ------------------------------------------------------------------------------

output "node_security_group_id" {
  description = "The security group ID for EKS worker nodes - used for adding application-specific security rules"
  value       = try(aws_security_group.node[0].id, "")
}

output "node_iam_role_arn" {
  description = "The ARN of the IAM role used by EKS worker nodes - includes permissions for ECR, EKS, and CloudWatch"
  value       = try(aws_iam_role.node[0].arn, "")
}

output "node_iam_role_name" {
  description = "The name of the IAM role used by worker nodes - useful for additional policy attachments"
  value       = try(aws_iam_role.node[0].name, "")
}

# ------------------------------------------------------------------------------
# Cluster Logging Configuration
# ------------------------------------------------------------------------------

output "cluster_enabled_log_types" {
  description = "List of enabled control plane logging types (api, audit, authenticator, controllerManager, scheduler)"
  value       = aws_eks_cluster.main.enabled_cluster_log_types
}

# ------------------------------------------------------------------------------
# Additional Cluster Configuration Outputs
# ------------------------------------------------------------------------------

output "cluster_vpc_config" {
  description = "VPC configuration for the EKS cluster including subnet IDs and endpoint access settings"
  value = {
    subnet_ids              = aws_eks_cluster.main.vpc_config[0].subnet_ids
    endpoint_private_access = aws_eks_cluster.main.vpc_config[0].endpoint_private_access
    endpoint_public_access  = aws_eks_cluster.main.vpc_config[0].endpoint_public_access
    public_access_cidrs     = aws_eks_cluster.main.vpc_config[0].public_access_cidrs
  }
}

output "cluster_addons" {
  description = "Map of EKS cluster addons including vpc-cni, kube-proxy, and coredns versions"
  value = {
    vpc_cni     = try(aws_eks_addon.vpc_cni[0].addon_version, "")
    kube_proxy  = try(aws_eks_addon.kube_proxy[0].addon_version, "")
    coredns     = try(aws_eks_addon.coredns[0].addon_version, "")
  }
}

# ------------------------------------------------------------------------------
# Node Group Scaling Configuration
# ------------------------------------------------------------------------------

output "node_group_scaling_config" {
  description = "Auto-scaling configuration for the node group including desired, min, and max sizes"
  value = try({
    desired_size = aws_eks_node_group.main[0].scaling_config[0].desired_size
    max_size     = aws_eks_node_group.main[0].scaling_config[0].max_size
    min_size     = aws_eks_node_group.main[0].scaling_config[0].min_size
  }, {})
}

output "node_group_instance_types" {
  description = "List of EC2 instance types used by the managed node group"
  value       = try(aws_eks_node_group.main[0].instance_types, [])
}

# ------------------------------------------------------------------------------
# Tags Output
# ------------------------------------------------------------------------------

output "cluster_tags" {
  description = "Map of tags applied to the EKS cluster for cost allocation and resource management"
  value       = aws_eks_cluster.main.tags
}
