# =============================================================================
# VPC Module Outputs
# =============================================================================
# Terraform output definitions for the VPC module, exposing networking resources
# to dependent modules (EKS cluster, RDS database, application load balancers).
#
# Part of CardDemo mainframe-to-cloud migration project
# Replaces mainframe VTAM networking with cloud-native AWS VPC infrastructure
# =============================================================================

# -----------------------------------------------------------------------------
# VPC Outputs
# -----------------------------------------------------------------------------

output "vpc_id" {
  description = "The ID of the VPC for use by dependent modules (EKS cluster, RDS database)"
  value       = aws_vpc.main.id
}

output "vpc_cidr_block" {
  description = "The CIDR block of the VPC for network planning and security group rules"
  value       = aws_vpc.main.cidr_block
}

output "vpc_arn" {
  description = "The ARN of the VPC for IAM policies and resource tagging"
  value       = aws_vpc.main.arn
}

# -----------------------------------------------------------------------------
# Subnet Outputs
# -----------------------------------------------------------------------------

output "public_subnet_ids" {
  description = "List of public subnet IDs for load balancers and internet-facing resources"
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "List of private subnet IDs for EKS worker nodes, RDS databases, and backend application pods"
  value       = aws_subnet.private[*].id
}

output "public_subnet_cidrs" {
  description = "List of public subnet CIDR blocks for network security planning"
  value       = aws_subnet.public[*].cidr_block
}

output "private_subnet_cidrs" {
  description = "List of private subnet CIDR blocks for network security planning"
  value       = aws_subnet.private[*].cidr_block
}

# -----------------------------------------------------------------------------
# Availability Zone Outputs
# -----------------------------------------------------------------------------

output "availability_zones" {
  description = "List of availability zones where subnets are deployed for high availability configuration"
  value       = var.availability_zones
}

output "public_subnet_availability_zones" {
  description = "Map of public subnet IDs to their availability zones"
  value       = { for subnet in aws_subnet.public : subnet.id => subnet.availability_zone }
}

output "private_subnet_availability_zones" {
  description = "Map of private subnet IDs to their availability zones"
  value       = { for subnet in aws_subnet.private : subnet.id => subnet.availability_zone }
}

# -----------------------------------------------------------------------------
# Gateway Outputs
# -----------------------------------------------------------------------------

output "internet_gateway_id" {
  description = "The ID of the Internet Gateway for public subnet internet access"
  value       = aws_internet_gateway.main.id
}

output "nat_gateway_ids" {
  description = "List of NAT Gateway IDs for private subnet outbound internet connectivity"
  value       = aws_nat_gateway.main[*].id
}

output "nat_gateway_public_ips" {
  description = "List of Elastic IP addresses associated with NAT Gateways for firewall whitelist configuration"
  value       = aws_eip.nat[*].public_ip
}

# -----------------------------------------------------------------------------
# Route Table Outputs
# -----------------------------------------------------------------------------

output "public_route_table_ids" {
  description = "List of public route table IDs for custom route configuration"
  value       = aws_route_table.public[*].id
}

output "private_route_table_ids" {
  description = "List of private route table IDs for custom route configuration"
  value       = aws_route_table.private[*].id
}

# -----------------------------------------------------------------------------
# VPC Endpoint Outputs
# -----------------------------------------------------------------------------

output "vpc_endpoint_s3_id" {
  description = "The ID of the S3 VPC endpoint for private S3 access without internet gateway (ECR image layers, backups)"
  value       = try(aws_vpc_endpoint.s3[0].id, null)
}

output "vpc_endpoint_ecr_api_id" {
  description = "The ID of the ECR API VPC endpoint for private Docker registry API access (image pulls)"
  value       = try(aws_vpc_endpoint.ecr_api[0].id, null)
}

output "vpc_endpoint_ecr_dkr_id" {
  description = "The ID of the ECR Docker VPC endpoint for private Docker image download (EKS worker nodes)"
  value       = try(aws_vpc_endpoint.ecr_dkr[0].id, null)
}

output "vpc_endpoint_logs_id" {
  description = "The ID of the CloudWatch Logs VPC endpoint for private log streaming from application pods"
  value       = try(aws_vpc_endpoint.logs[0].id, null)
}

# -----------------------------------------------------------------------------
# Network Security Outputs
# -----------------------------------------------------------------------------

output "default_security_group_id" {
  description = "The ID of the VPC default security group for reference (should not be used directly)"
  value       = aws_vpc.main.default_security_group_id
}

output "default_network_acl_id" {
  description = "The ID of the VPC default network ACL"
  value       = aws_vpc.main.default_network_acl_id
}

# -----------------------------------------------------------------------------
# Flow Log Outputs
# -----------------------------------------------------------------------------

output "flow_log_id" {
  description = "The ID of the VPC Flow Log for network traffic monitoring and security analysis"
  value       = try(aws_flow_log.main[0].id, null)
}

output "flow_log_cloudwatch_log_group" {
  description = "The CloudWatch Log Group name for VPC Flow Logs"
  value       = try(aws_cloudwatch_log_group.flow_log[0].name, null)
}

# -----------------------------------------------------------------------------
# Summary Outputs for Dependent Modules
# -----------------------------------------------------------------------------

output "vpc_summary" {
  description = "Summary of VPC configuration for documentation and troubleshooting"
  value = {
    vpc_id              = aws_vpc.main.id
    vpc_cidr            = aws_vpc.main.cidr_block
    availability_zones  = var.availability_zones
    public_subnet_count = length(aws_subnet.public)
    private_subnet_count = length(aws_subnet.private)
    nat_gateway_count   = length(aws_nat_gateway.main)
    dns_hostnames_enabled = var.enable_dns_hostnames
    dns_support_enabled = var.enable_dns_support
  }
}

output "networking_config" {
  description = "Consolidated networking configuration for EKS cluster module input"
  value = {
    vpc_id             = aws_vpc.main.id
    vpc_cidr_block     = aws_vpc.main.cidr_block
    private_subnet_ids = aws_subnet.private[*].id
    public_subnet_ids  = aws_subnet.public[*].id
  }
}
