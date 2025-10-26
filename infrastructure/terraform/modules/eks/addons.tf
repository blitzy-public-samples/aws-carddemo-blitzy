# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0

# ==============================================================================
# EKS Cluster Add-ons Configuration
# ==============================================================================
# This file manages essential EKS add-ons required for running the CardDemo
# modernized application workloads. These add-ons replace mainframe batch 
# processing infrastructure (JCL jobs like COMBTRAN.jcl, DALYREJS.jcl) with
# Kubernetes-native capabilities.
#
# Purpose:
# - VPC CNI: Provides pod networking for Spring Boot backend pods and Spring
#   Batch job pods that replace COBOL batch programs
# - CoreDNS: Enables DNS-based service discovery for microservices communication
# - kube-proxy: Handles network routing and load balancing for services
#
# Migration Context:
# - JCL batch jobs (CBACTJ01-04, CBTRNJ01-03, etc.) → Kubernetes CronJobs
# - CICS transaction processing → Kubernetes Deployments with multiple replicas
# - VSAM file processing → StatefulSets with persistent volumes
# ==============================================================================

# ------------------------------------------------------------------------------
# Data Sources for Add-on Version Compatibility
# ------------------------------------------------------------------------------
# Fetch the latest compatible versions of EKS add-ons for Kubernetes 1.31
# This ensures add-ons are always compatible with the cluster version

data "aws_eks_addon_version" "vpc_cni" {
  addon_name         = "vpc-cni"
  kubernetes_version = var.cluster_version
  most_recent        = true
}

data "aws_eks_addon_version" "coredns" {
  addon_name         = "coredns"
  kubernetes_version = var.cluster_version
  most_recent        = true
}

data "aws_eks_addon_version" "kube_proxy" {
  addon_name         = "kube-proxy"
  kubernetes_version = var.cluster_version
  most_recent        = true
}

# ------------------------------------------------------------------------------
# VPC CNI Add-on
# ------------------------------------------------------------------------------
# Amazon VPC CNI plugin for Kubernetes provides native VPC networking for pods
# This is critical for Spring Boot pods to communicate with PostgreSQL RDS and
# external payment network interfaces that replace mainframe file-based interfaces
#
# Configuration Notes:
# - WARM_ENI_TARGET: Pre-allocates ENIs for faster pod startup (critical for
#   Spring Batch jobs that need to start quickly to meet 4-hour batch windows)
# - WARM_IP_TARGET: Maintains pool of IPs for rapid pod scaling (supports
#   10,000 TPS throughput requirement)
# - Network Policy: Enables security policies equivalent to RACF controls

resource "aws_eks_addon" "vpc_cni" {
  count = var.enable_vpc_cni_addon ? 1 : 0

  cluster_name             = aws_eks_cluster.main.name
  addon_name               = "vpc-cni"
  addon_version            = data.aws_eks_addon_version.vpc_cni.version
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"
  preserve                 = true

  # Configuration values for VPC CNI plugin
  # These settings optimize IP address management and network performance
  configuration_values = jsonencode({
    env = {
      # Maintain 1 warm ENI per node for faster pod startup
      # Critical for Spring Batch jobs that must complete within 4-hour windows
      WARM_ENI_TARGET = "1"
      
      # Maintain 5 warm IPs per node for rapid scaling
      # Supports horizontal pod autoscaling (3-10 replicas) for backend services
      WARM_IP_TARGET = "5"
      
      # Enable network policy enforcement for security
      # Replaces RACF resource-level access controls with Kubernetes NetworkPolicies
      ENABLE_NETWORK_POLICY = "true"
      
      # Enable prefix delegation for better IP utilization
      # Allows more pods per node for batch processing workloads
      ENABLE_PREFIX_DELEGATION = "true"
    }
  })

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.cluster_name}-vpc-cni-addon"
      Purpose     = "Pod networking for Spring Boot backend and Spring Batch jobs"
      MigrationContext = "Replaces mainframe VTAM networking for CICS transactions"
      Component   = "eks-addon"
      Addon       = "vpc-cni"
    }
  )

  # Dependencies: Must wait for cluster and node groups to be created
  depends_on = [
    aws_eks_cluster.main,
    aws_eks_node_group.main
  ]
}

# ------------------------------------------------------------------------------
# CoreDNS Add-on
# ------------------------------------------------------------------------------
# CoreDNS provides DNS-based service discovery within the Kubernetes cluster
# Essential for Spring Boot services to discover PostgreSQL, Redis, and other
# backend services by DNS name instead of hardcoded IPs
#
# Migration Context:
# - Replaces mainframe CICS program-to-program LINK/XCTL with REST API calls
# - Services discover each other via DNS (e.g., "account-service.carddemo.svc")
# - Enables zero-downtime deployments with service abstraction

resource "aws_eks_addon" "coredns" {
  count = var.enable_coredns_addon ? 1 : 0

  cluster_name             = aws_eks_cluster.main.name
  addon_name               = "coredns"
  addon_version            = data.aws_eks_addon_version.coredns.version
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"
  preserve                 = true

  # CoreDNS configuration for high availability and performance
  configuration_values = jsonencode({
    replicaCount = var.coredns_replica_count
    
    # Resource limits to ensure CoreDNS can handle high query volumes
    # CardDemo requires 10,000 TPS which generates significant DNS queries
    resources = {
      limits = {
        cpu    = "200m"
        memory = "256Mi"
      }
      requests = {
        cpu    = "100m"
        memory = "128Mi"
      }
    }
    
    # Pod disruption budget to maintain availability during node updates
    podDisruptionBudget = {
      enabled = true
      minAvailable = 1
    }
    
    # Enable Prometheus metrics for monitoring DNS performance
    prometheus = {
      enabled = true
      port    = 9153
    }
  })

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.cluster_name}-coredns-addon"
      Purpose     = "DNS-based service discovery for microservices"
      MigrationContext = "Replaces CICS program name resolution with DNS"
      Component   = "eks-addon"
      Addon       = "coredns"
    }
  )

  # Dependencies: CoreDNS needs VPC CNI for pod networking and node groups
  depends_on = [
    aws_eks_cluster.main,
    aws_eks_node_group.main,
    aws_eks_addon.vpc_cni
  ]
}

# ------------------------------------------------------------------------------
# kube-proxy Add-on
# ------------------------------------------------------------------------------
# kube-proxy maintains network rules on nodes and enables Service abstraction
# Handles load balancing across multiple pod replicas (3-10 backend replicas)
# and network routing for ClusterIP, NodePort, and LoadBalancer services
#
# Migration Context:
# - Replaces CICS transaction routing and load balancing
# - Distributes incoming card authorization requests across multiple backend pods
# - Maintains sub-200ms response time requirement through efficient routing
# - Supports 10,000 TPS throughput with iptables-based load balancing

resource "aws_eks_addon" "kube_proxy" {
  count = var.enable_kube_proxy_addon ? 1 : 0

  cluster_name             = aws_eks_cluster.main.name
  addon_name               = "kube-proxy"
  addon_version            = data.aws_eks_addon_version.kube_proxy.version
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"
  preserve                 = true

  # kube-proxy configuration for high-performance networking
  configuration_values = jsonencode({
    # Use iptables mode for compatibility and performance
    # iptables provides sub-millisecond routing latency required for
    # maintaining <200ms response time SLA
    mode = "iptables"
    
    # Connection tracking settings for high-throughput workloads
    conntrack = {
      # Maximum tracked connections (supports 10,000 TPS requirement)
      maxPerCore = 131072
      
      # Connection timeout settings
      tcpEstablishedTimeout = "24h"
      tcpCloseWaitTimeout   = "1h"
    }
    
    # IP tables sync period for service updates
    # Lower value provides faster service discovery but higher CPU usage
    iptablesSyncPeriod = "30s"
    
    # Enable metrics for monitoring network performance
    metricsBindAddress = "0.0.0.0:10249"
  })

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.cluster_name}-kube-proxy-addon"
      Purpose     = "Network proxy and load balancing for services"
      MigrationContext = "Replaces CICS transaction routing and load balancing"
      Component   = "eks-addon"
      Addon       = "kube-proxy"
      Performance = "Supports 10K TPS with sub-200ms latency"
    }
  )

  # Dependencies: kube-proxy needs cluster created but can run before nodes
  depends_on = [
    aws_eks_cluster.main
  ]
}

# ------------------------------------------------------------------------------
# AWS EBS CSI Driver Add-on (Optional)
# ------------------------------------------------------------------------------
# Amazon EBS CSI driver enables EBS volumes as persistent storage for pods
# Required for StatefulSets running PostgreSQL or for Spring Batch jobs that
# need persistent work directories (replaces VSAM files and GDG datasets)
#
# Migration Context:
# - Replaces VSAM KSDS datasets with EBS-backed persistent volumes
# - Provides storage for PostgreSQL StatefulSet (if running in-cluster)
# - Enables Spring Batch restart capability with persistent job repositories

data "aws_eks_addon_version" "ebs_csi_driver" {
  count = var.enable_ebs_csi_driver_addon ? 1 : 0

  addon_name         = "aws-ebs-csi-driver"
  kubernetes_version = var.cluster_version
  most_recent        = true
}

resource "aws_eks_addon" "ebs_csi_driver" {
  count = var.enable_ebs_csi_driver_addon ? 1 : 0

  cluster_name             = aws_eks_cluster.main.name
  addon_name               = "aws-ebs-csi-driver"
  addon_version            = data.aws_eks_addon_version.ebs_csi_driver[0].version
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"
  preserve                 = true
  service_account_role_arn = var.ebs_csi_driver_role_arn

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.cluster_name}-ebs-csi-driver-addon"
      Purpose     = "EBS volume provisioning for persistent storage"
      MigrationContext = "Replaces VSAM datasets with cloud-native persistent volumes"
      Component   = "eks-addon"
      Addon       = "ebs-csi-driver"
    }
  )

  depends_on = [
    aws_eks_cluster.main,
    aws_eks_node_group.main
  ]
}

# ------------------------------------------------------------------------------
# Outputs
# ------------------------------------------------------------------------------
# Export add-on information for use in other modules

output "vpc_cni_addon_version" {
  description = "Version of VPC CNI addon installed"
  value       = var.enable_vpc_cni_addon ? aws_eks_addon.vpc_cni[0].addon_version : null
}

output "coredns_addon_version" {
  description = "Version of CoreDNS addon installed"
  value       = var.enable_coredns_addon ? aws_eks_addon.coredns[0].addon_version : null
}

output "kube_proxy_addon_version" {
  description = "Version of kube-proxy addon installed"
  value       = var.enable_kube_proxy_addon ? aws_eks_addon.kube_proxy[0].addon_version : null
}

output "ebs_csi_driver_addon_version" {
  description = "Version of EBS CSI driver addon installed"
  value       = var.enable_ebs_csi_driver_addon ? aws_eks_addon.ebs_csi_driver[0].addon_version : null
}

output "addons_status" {
  description = "Status of all EKS addons"
  value = {
    vpc_cni = var.enable_vpc_cni_addon ? {
      enabled = true
      version = aws_eks_addon.vpc_cni[0].addon_version
      status  = aws_eks_addon.vpc_cni[0].status
    } : { enabled = false }
    
    coredns = var.enable_coredns_addon ? {
      enabled = true
      version = aws_eks_addon.coredns[0].addon_version
      status  = aws_eks_addon.coredns[0].status
    } : { enabled = false }
    
    kube_proxy = var.enable_kube_proxy_addon ? {
      enabled = true
      version = aws_eks_addon.kube_proxy[0].addon_version
      status  = aws_eks_addon.kube_proxy[0].status
    } : { enabled = false }
    
    ebs_csi_driver = var.enable_ebs_csi_driver_addon ? {
      enabled = true
      version = aws_eks_addon.ebs_csi_driver[0].addon_version
      status  = aws_eks_addon.ebs_csi_driver[0].status
    } : { enabled = false }
  }
}

# ------------------------------------------------------------------------------
# Lifecycle Management
# ------------------------------------------------------------------------------
# Add-on lifecycle considerations for production deployment:
#
# 1. Version Updates:
#    - Always test add-on updates in non-production environments first
#    - Monitor cluster health after updates (check pod networking, DNS resolution)
#    - Validate batch job completion times remain within 4-hour windows
#
# 2. Conflict Resolution:
#    - OVERWRITE strategy replaces existing configuration
#    - Use PRESERVE strategy if custom add-on configurations are manually applied
#    - Review add-on configuration drift before applying updates
#
# 3. Dependencies:
#    - VPC CNI must be installed before CoreDNS
#    - Node groups must exist before add-ons are fully functional
#    - EBS CSI driver requires IAM role with proper EBS permissions
#
# 4. Monitoring:
#    - Track add-on health via AWS Console or CLI
#    - Monitor pod startup times (should be <30 seconds for backend pods)
#    - Validate DNS resolution latency (<10ms for service discovery)
#    - Check network throughput supports 10,000 TPS requirement
#
# 5. Rollback:
#    - Preserve previous add-on versions for quick rollback
#    - Test rollback procedures in staging environment
#    - Document add-on configuration for disaster recovery
# ------------------------------------------------------------------------------

