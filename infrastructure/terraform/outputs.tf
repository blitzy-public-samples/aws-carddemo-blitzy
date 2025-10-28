# =============================================================================
# Terraform Outputs for CardDemo Infrastructure
# =============================================================================
# This file exposes critical infrastructure attributes for downstream 
# configuration and integration with Kubernetes deployments and application 
# configurations. Part of the mainframe-to-cloud migration infrastructure.
#
# Conversion Note: Replaces manual mainframe infrastructure documentation with
# programmatically exposed infrastructure attributes for cloud-native deployment.
# =============================================================================

# -----------------------------------------------------------------------------
# VPC Outputs
# -----------------------------------------------------------------------------

output "vpc_id" {
  description = "The ID of the VPC where all CardDemo infrastructure is deployed. Used for security group rules and network policy configuration."
  value       = module.vpc.vpc_id
}

output "private_subnet_ids" {
  description = "List of private subnet IDs for EKS node placement and RDS database deployment. These subnets have no direct internet access and route through NAT gateways."
  value       = module.vpc.private_subnet_ids
}

output "public_subnet_ids" {
  description = "List of public subnet IDs for load balancers and NAT gateways. These subnets have direct internet gateway access for inbound traffic."
  value       = module.vpc.public_subnet_ids
}

output "vpc_cidr_block" {
  description = "The CIDR block of the VPC for network planning and security group configuration."
  value       = module.vpc.vpc_cidr_block
}

# -----------------------------------------------------------------------------
# EKS Cluster Outputs
# -----------------------------------------------------------------------------

output "eks_cluster_id" {
  description = "The name/ID of the EKS cluster. Used for cluster identification and tagging resources associated with the cluster."
  value       = module.eks.cluster_id
}

output "eks_cluster_endpoint" {
  description = "Endpoint for the EKS Kubernetes API server. Used for kubectl configuration and CI/CD pipeline integration."
  value       = module.eks.cluster_endpoint
}

output "eks_cluster_certificate_authority_data" {
  description = "Base64 encoded certificate data required to communicate with the EKS cluster. Used in kubectl configuration for authentication."
  value       = module.eks.cluster_certificate_authority_data
  sensitive   = true
}

output "eks_cluster_security_group_id" {
  description = "Security group ID attached to the EKS cluster control plane. Used for configuring network policies and worker node communication."
  value       = module.eks.cluster_security_group_id
}

output "eks_cluster_version" {
  description = "The Kubernetes version running on the EKS cluster."
  value       = module.eks.cluster_version
}

output "eks_node_group_id" {
  description = "The ID of the EKS node group for worker nodes."
  value       = module.eks.node_group_id
}

output "eks_node_role_arn" {
  description = "IAM role ARN for EKS worker nodes. Used for configuring pod-level IAM permissions via IRSA (IAM Roles for Service Accounts)."
  value       = module.eks.node_role_arn
}

# -----------------------------------------------------------------------------
# RDS Database Outputs
# -----------------------------------------------------------------------------

output "rds_endpoint" {
  description = "Connection endpoint for the PostgreSQL RDS instance. Format: hostname:port. Used in Spring Boot application.yml for database connection configuration."
  value       = module.rds.db_instance_endpoint
}

output "rds_address" {
  description = "Hostname of the RDS instance without port. Used for database connection configuration."
  value       = module.rds.db_instance_address
}

output "rds_port" {
  description = "Port number for the PostgreSQL database connection (typically 5432). Used in application configuration."
  value       = module.rds.db_instance_port
}

output "rds_database_name" {
  description = "Name of the default database created in the RDS instance. Used in Spring Boot application configuration for the CardDemo schema."
  value       = module.rds.db_instance_name
}

output "rds_instance_id" {
  description = "The RDS instance identifier for resource management and monitoring."
  value       = module.rds.db_instance_id
}

output "rds_security_group_id" {
  description = "Security group ID for the RDS instance. Used for configuring database access from EKS pods."
  value       = module.rds.db_security_group_id
}

# -----------------------------------------------------------------------------
# Monitoring Stack Outputs
# -----------------------------------------------------------------------------

output "monitoring_endpoints" {
  description = "Map of monitoring tool endpoints including Prometheus and Grafana. Used for observability dashboard access and alerting configuration."
  value = var.enable_monitoring ? {
    prometheus_endpoint = module.monitoring[0].prometheus_endpoint
    grafana_endpoint    = module.monitoring[0].grafana_endpoint
    alertmanager_endpoint = module.monitoring[0].alertmanager_endpoint
  } : {}
}

output "monitoring_namespace" {
  description = "Kubernetes namespace where monitoring stack is deployed (e.g., 'monitoring'). Used for kubectl commands and resource management."
  value       = var.enable_monitoring ? module.monitoring[0].namespace : null
}

# -----------------------------------------------------------------------------
# Configuration Summary Output
# -----------------------------------------------------------------------------

output "infrastructure_summary" {
  description = "Summary of deployed infrastructure for documentation and operations reference."
  value = {
    environment         = var.environment
    project_name        = var.project_name
    aws_region          = var.aws_region
    vpc_id              = module.vpc.vpc_id
    eks_cluster_name    = module.eks.cluster_id
    rds_instance        = module.rds.db_instance_id
    monitoring_enabled  = var.enable_monitoring
  }
}

# -----------------------------------------------------------------------------
# Application Configuration Output
# -----------------------------------------------------------------------------

output "application_config" {
  description = "Configuration values for CardDemo Spring Boot application deployment. Use these values in Kubernetes ConfigMaps and application.yml."
  value = {
    database_host     = module.rds.db_instance_address
    database_port     = module.rds.db_instance_port
    database_name     = module.rds.db_instance_name
    eks_cluster_endpoint = module.eks.cluster_endpoint
    aws_region        = var.aws_region
  }
}

# -----------------------------------------------------------------------------
# Kubectl Configuration Command Output
# -----------------------------------------------------------------------------

output "kubectl_config_command" {
  description = "AWS CLI command to configure kubectl for EKS cluster access. Run this command to update your kubeconfig."
  value       = "aws eks update-kubeconfig --region ${var.aws_region} --name ${module.eks.cluster_id}"
}
