# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

###############################################################################
# EKS Cluster Security Groups Configuration
# 
# This configuration defines security groups and rules for the EKS cluster
# hosting the modernized CardDemo application (migrated from IBM mainframe).
# 
# Original System: CICS online transaction processing + JCL batch jobs
# Target System: Spring Boot microservices on EKS with Spring Batch
# 
# Security Requirements:
# - Support 10,000 TPS transaction processing (migrated from CICS)
# - Enable Spring Batch job execution (migrated from JCL jobs like COMBTRAN, DALYREJS)
# - Secure cluster control plane and worker node communication
# - Enable pod-to-pod networking for microservices
# - Support ingress controller for external API access
###############################################################################

###############################################################################
# Cluster Security Group
# Primary security group for the EKS control plane
###############################################################################

resource "aws_security_group" "cluster" {
  name_prefix = "${var.cluster_name}-cluster-sg-"
  description = "Security group for EKS cluster control plane - CardDemo mainframe migration"
  vpc_id      = var.vpc_id

  tags = merge(
    var.common_tags,
    {
      Name                                        = "${var.cluster_name}-cluster-sg"
      "kubernetes.io/cluster/${var.cluster_name}" = "owned"
      Component                                   = "eks-control-plane"
      MigratedFrom                                = "IBM-CICS-Transaction-Server"
      Purpose                                     = "EKS cluster control plane security"
    }
  )

  lifecycle {
    create_before_destroy = true
  }
}

###############################################################################
# Node Security Group
# Primary security group for EKS worker nodes
###############################################################################

resource "aws_security_group" "node" {
  name_prefix = "${var.cluster_name}-node-sg-"
  description = "Security group for EKS worker nodes - hosts Spring Boot services migrated from COBOL"
  vpc_id      = var.vpc_id

  tags = merge(
    var.common_tags,
    {
      Name                                        = "${var.cluster_name}-node-sg"
      "kubernetes.io/cluster/${var.cluster_name}" = "owned"
      Component                                   = "eks-worker-nodes"
      MigratedFrom                                = "IBM-zOS-Mainframe"
      Purpose                                     = "EKS worker node security"
    }
  )

  lifecycle {
    create_before_destroy = true
  }
}

###############################################################################
# Cluster to Node Communication Rules
# Enable control plane to communicate with worker nodes
###############################################################################

# Allow cluster to communicate with nodes on HTTPS (443)
resource "aws_security_group_rule" "cluster_to_node_https" {
  description              = "Allow cluster control plane to communicate with worker nodes on HTTPS (API server)"
  type                     = "ingress"
  from_port                = 443
  to_port                  = 443
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.cluster.id
  security_group_id        = aws_security_group.node.id
}

# Allow cluster to communicate with nodes on kubelet port (10250)
resource "aws_security_group_rule" "cluster_to_node_kubelet" {
  description              = "Allow cluster control plane to communicate with kubelet on worker nodes"
  type                     = "ingress"
  from_port                = 10250
  to_port                  = 10250
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.cluster.id
  security_group_id        = aws_security_group.node.id
}

# Allow cluster to communicate with nodes on ephemeral ports (for pod logs, exec, port-forward)
resource "aws_security_group_rule" "cluster_to_node_ephemeral" {
  description              = "Allow cluster control plane to communicate with nodes on ephemeral ports"
  type                     = "ingress"
  from_port                = 1025
  to_port                  = 65535
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.cluster.id
  security_group_id        = aws_security_group.node.id
}

###############################################################################
# Node to Cluster Communication Rules
# Enable worker nodes to communicate with control plane
###############################################################################

# Allow nodes to communicate with cluster API server
resource "aws_security_group_rule" "node_to_cluster_api" {
  description              = "Allow worker nodes to communicate with cluster API server"
  type                     = "ingress"
  from_port                = 443
  to_port                  = 443
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.node.id
  security_group_id        = aws_security_group.cluster.id
}

###############################################################################
# Node to Node Communication Rules
# Enable pod-to-pod networking and service discovery
###############################################################################

# Allow nodes to communicate with each other on all ports (for pod networking)
resource "aws_security_group_rule" "node_to_node_all" {
  description              = "Allow worker nodes to communicate with each other (pod-to-pod networking)"
  type                     = "ingress"
  from_port                = 0
  to_port                  = 65535
  protocol                 = "-1"
  source_security_group_id = aws_security_group.node.id
  security_group_id        = aws_security_group.node.id
}

# Allow nodes to communicate with each other on DNS port (53) - explicit rule for CoreDNS
resource "aws_security_group_rule" "node_to_node_dns_tcp" {
  description              = "Allow worker nodes to communicate with CoreDNS on TCP"
  type                     = "ingress"
  from_port                = 53
  to_port                  = 53
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.node.id
  security_group_id        = aws_security_group.node.id
}

resource "aws_security_group_rule" "node_to_node_dns_udp" {
  description              = "Allow worker nodes to communicate with CoreDNS on UDP"
  type                     = "ingress"
  from_port                = 53
  to_port                  = 53
  protocol                 = "udp"
  source_security_group_id = aws_security_group.node.id
  security_group_id        = aws_security_group.node.id
}

###############################################################################
# Node Egress Rules
# Enable worker nodes to access external resources
###############################################################################

# Allow nodes to egress to internet on HTTPS (for pulling container images from ECR, Docker Hub)
resource "aws_security_group_rule" "node_egress_https" {
  description       = "Allow worker nodes to pull container images and access AWS services"
  type              = "egress"
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = ["0.0.0.0/0"]
  security_group_id = aws_security_group.node.id
}

# Allow nodes to egress to internet on HTTP (for some image registries)
resource "aws_security_group_rule" "node_egress_http" {
  description       = "Allow worker nodes to access HTTP endpoints"
  type              = "egress"
  from_port         = 80
  to_port           = 80
  protocol          = "tcp"
  cidr_blocks       = ["0.0.0.0/0"]
  security_group_id = aws_security_group.node.id
}

# Allow nodes to egress to internet on NTP port (123) for time synchronization
resource "aws_security_group_rule" "node_egress_ntp" {
  description       = "Allow worker nodes to synchronize time with NTP servers"
  type              = "egress"
  from_port         = 123
  to_port           = 123
  protocol          = "udp"
  cidr_blocks       = ["0.0.0.0/0"]
  security_group_id = aws_security_group.node.id
}

# Allow nodes to egress to internet on DNS port (53)
resource "aws_security_group_rule" "node_egress_dns_tcp" {
  description       = "Allow worker nodes to perform DNS resolution on TCP"
  type              = "egress"
  from_port         = 53
  to_port           = 53
  protocol          = "tcp"
  cidr_blocks       = ["0.0.0.0/0"]
  security_group_id = aws_security_group.node.id
}

resource "aws_security_group_rule" "node_egress_dns_udp" {
  description       = "Allow worker nodes to perform DNS resolution on UDP"
  type              = "egress"
  from_port         = 53
  to_port           = 53
  protocol          = "udp"
  cidr_blocks       = ["0.0.0.0/0"]
  security_group_id = aws_security_group.node.id
}

# Allow nodes to egress to PostgreSQL database (port 5432) - for Spring Boot data access
resource "aws_security_group_rule" "node_egress_postgres" {
  description       = "Allow worker nodes to communicate with PostgreSQL database (replaces VSAM)"
  type              = "egress"
  from_port         = 5432
  to_port           = 5432
  protocol          = "tcp"
  cidr_blocks       = var.database_cidr_blocks
  security_group_id = aws_security_group.node.id
}

# Allow all egress from nodes (alternative comprehensive rule)
resource "aws_security_group_rule" "node_egress_all" {
  description       = "Allow all egress traffic from worker nodes"
  type              = "egress"
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  cidr_blocks       = ["0.0.0.0/0"]
  security_group_id = aws_security_group.node.id
}

###############################################################################
# External Access Rules (Conditional)
# Allow external access to cluster API server if public access is enabled
###############################################################################

# Allow external HTTPS access to cluster API server (conditional based on public access)
resource "aws_security_group_rule" "cluster_api_external_access" {
  count = var.cluster_endpoint_public_access ? 1 : 0

  description       = "Allow external HTTPS access to cluster API server"
  type              = "ingress"
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = var.cluster_endpoint_public_access_cidrs
  security_group_id = aws_security_group.cluster.id
}

###############################################################################
# Ingress Controller Rules
# Support for AWS Load Balancer Controller and NGINX Ingress
###############################################################################

# Allow ingress traffic on HTTP (80) for ingress controller
resource "aws_security_group_rule" "node_ingress_http" {
  description       = "Allow HTTP traffic to ingress controller (for CardDemo React SPA)"
  type              = "ingress"
  from_port         = 80
  to_port           = 80
  protocol          = "tcp"
  cidr_blocks       = var.ingress_cidr_blocks
  security_group_id = aws_security_group.node.id
}

# Allow ingress traffic on HTTPS (443) for ingress controller
resource "aws_security_group_rule" "node_ingress_https" {
  description       = "Allow HTTPS traffic to ingress controller (for CardDemo REST API)"
  type              = "ingress"
  from_port         = 443
  to_port           = 443
  protocol          = "tcp"
  cidr_blocks       = var.ingress_cidr_blocks
  security_group_id = aws_security_group.node.id
}

# Allow ingress traffic on backend service port (8080) for Spring Boot
resource "aws_security_group_rule" "node_ingress_backend" {
  description       = "Allow traffic to Spring Boot backend service port (replaces CICS listener)"
  type              = "ingress"
  from_port         = 8080
  to_port           = 8080
  protocol          = "tcp"
  cidr_blocks       = var.vpc_cidr_blocks
  security_group_id = aws_security_group.node.id
}

###############################################################################
# Pod Networking Rules (AWS VPC CNI)
# Support for AWS VPC CNI plugin pod networking
###############################################################################

# Allow pod-to-pod communication using VPC CNI on all ports
resource "aws_security_group_rule" "pod_to_pod_all" {
  description       = "Allow pod-to-pod communication within VPC CNI network"
  type              = "ingress"
  from_port         = 0
  to_port           = 65535
  protocol          = "-1"
  cidr_blocks       = var.vpc_cidr_blocks
  security_group_id = aws_security_group.node.id
}

###############################################################################
# Additional Security Group for PostgreSQL Database Access
# Dedicated security group for RDS PostgreSQL (replaces VSAM file access)
###############################################################################

resource "aws_security_group" "database_access" {
  name_prefix = "${var.cluster_name}-db-access-sg-"
  description = "Security group for EKS nodes to access PostgreSQL database (replaces VSAM)"
  vpc_id      = var.vpc_id

  tags = merge(
    var.common_tags,
    {
      Name         = "${var.cluster_name}-db-access-sg"
      Component    = "database-access"
      MigratedFrom = "VSAM-KSDS-File-Access"
      Purpose      = "PostgreSQL database access from EKS nodes"
    }
  )

  lifecycle {
    create_before_destroy = true
  }
}

# Allow nodes to access PostgreSQL database from database access security group
resource "aws_security_group_rule" "node_to_database" {
  description              = "Allow EKS nodes to access PostgreSQL database (replaces VSAM I/O)"
  type                     = "egress"
  from_port                = 5432
  to_port                  = 5432
  protocol                 = "tcp"
  source_security_group_id = aws_security_group.database_access.id
  security_group_id        = aws_security_group.node.id
}

###############################################################################
# Outputs
###############################################################################

output "cluster_security_group_id" {
  description = "Security group ID for the EKS cluster control plane"
  value       = aws_security_group.cluster.id
}

output "node_security_group_id" {
  description = "Security group ID for the EKS worker nodes"
  value       = aws_security_group.node.id
}

output "database_access_security_group_id" {
  description = "Security group ID for database access from EKS nodes"
  value       = aws_security_group.database_access.id
}

output "cluster_security_group_arn" {
  description = "ARN of the cluster security group"
  value       = aws_security_group.cluster.arn
}

output "node_security_group_arn" {
  description = "ARN of the node security group"
  value       = aws_security_group.node.arn
}

output "database_access_security_group_arn" {
  description = "ARN of the database access security group"
  value       = aws_security_group.database_access.arn
}
