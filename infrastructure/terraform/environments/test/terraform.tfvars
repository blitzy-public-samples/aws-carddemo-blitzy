# =============================================================================
# CardDemo Test Environment Terraform Variables
# =============================================================================
# Purpose: Test environment infrastructure configuration for comprehensive QA
#          testing including parallel testing comparing COBOL and Java outputs
# Environment: Test/QA - Pre-production validation environment
# Last Updated: 2025
# =============================================================================

# -----------------------------------------------------------------------------
# Environment Identification
# -----------------------------------------------------------------------------
# Identifies this deployment as the test environment for QA validation,
# parallel testing, and pre-production verification workflows
environment = "test"

# -----------------------------------------------------------------------------
# Project Metadata
# -----------------------------------------------------------------------------
# Project identifier used for resource naming and tagging across all
# infrastructure components
project_name = "carddemo"

# -----------------------------------------------------------------------------
# AWS Region Configuration
# -----------------------------------------------------------------------------
# Primary AWS region for test environment deployment
# US East 1 (N. Virginia) selected for proximity to external interfaces and
# regulatory reporting systems during integration testing
aws_region = "us-east-1"

# -----------------------------------------------------------------------------
# VPC Network Configuration
# -----------------------------------------------------------------------------
# Test environment VPC CIDR block - distinct from dev (10.0.0.0/16) and
# prod (10.2.0.0/16) to enable network-level isolation and prevent conflicts
# during parallel testing scenarios
vpc_cidr = "10.1.0.0/16"

# -----------------------------------------------------------------------------
# EKS Cluster Configuration
# -----------------------------------------------------------------------------
# Kubernetes cluster version aligning with Agent Action Plan requirement
# for Kubernetes 1.31 to ensure consistency across all environments
eks_cluster_version = "1.31"

# EKS worker node instance types for test environment
# t3.large instances provide mid-tier compute capacity (2 vCPU, 8GB RAM per node)
# suitable for cost-effective QA testing while supporting production-equivalent
# load testing at 10,000 TPS
eks_node_instance_types = ["t3.large"]

# EKS node group scaling configuration for test environment
# Baseline capacity: 4 nodes to support parallel testing infrastructure
# (backend pods, frontend pods, monitoring stack, test harness)
eks_desired_nodes = 4

# Minimum nodes: 3 for high availability during extended QA testing cycles
# Ensures continued operation even during node maintenance or failures
eks_min_nodes = 3

# Maximum nodes: 6 for load testing scale-out scenarios
# Enables horizontal scaling during performance validation testing at
# production-equivalent volumes (10,000 TPS transaction throughput testing)
eks_max_nodes = 6

# -----------------------------------------------------------------------------
# RDS Database Configuration
# -----------------------------------------------------------------------------
# PostgreSQL instance class for test environment database
# db.t3.medium provides mid-tier database capacity (2 vCPU, 4GB RAM)
# sufficient for comprehensive testing including:
# - Parallel COBOL vs Java output comparison testing
# - Batch job validation within 4-hour processing windows
# - Transaction response time verification under 200ms at peak load
rds_instance_class = "db.t3.medium"

# Database storage allocation for test environment
# 100GB standard storage configured to support:
# - Full test data volumes migrated from VSAM datasets
# - Transaction history for comprehensive regression testing
# - Batch processing validation with daily/monthly data cycles
rds_allocated_storage = 100

# Database backup retention period for test environment
# 7-day retention provides one-week backup window for:
# - Rollback capability during extended testing cycles
# - Point-in-time recovery for test data corruption scenarios
# - Parallel testing data consistency validation
rds_backup_retention_days = 7

# -----------------------------------------------------------------------------
# Monitoring and Observability Configuration
# -----------------------------------------------------------------------------
# Enable Prometheus and Grafana monitoring stack for test environment
# Required for:
# - Performance testing validation (transaction response times, throughput)
# - Batch job execution monitoring (4-hour window compliance)
# - Resource utilization tracking during load testing
# - Parallel testing metrics collection and comparison
# - Integration testing observability with external interfaces
enable_monitoring = true

# =============================================================================
# Test Environment Capacity Planning Summary
# =============================================================================
# 
# Compute Capacity:
# - EKS Cluster: 3-6 nodes of t3.large (6-12 vCPU, 24-48GB RAM total)
# - Sufficient for: Backend pods (3+ replicas), Frontend pods (2+ replicas),
#   PostgreSQL StatefulSet, Monitoring stack, Test harness infrastructure
#
# Database Capacity:
# - RDS Instance: db.t3.medium (2 vCPU, 4GB RAM, 100GB storage)
# - Sufficient for: 50,000 accounts, 100,000 cards, 1,000,000 transactions
# - Supports: Parallel testing, batch job validation, integration testing
#
# Performance Testing Targets:
# - Transaction Throughput: 10,000 TPS load testing validation
# - Response Time: Sub-200ms verification at peak load
# - Batch Processing: 4-hour window compliance validation
# - Concurrent Users: Support for QA team parallel testing activities
#
# Testing Scenarios Supported:
# - Parallel Testing: COBOL vs Java output comparison with identical inputs
# - Load Testing: Production-equivalent volume testing (10,000 TPS)
# - Integration Testing: External interface validation (payment networks,
#   bank core systems, regulatory reporting systems)
# - Regression Testing: Comprehensive test suite execution across all
#   26 COBOL programs converted to Java services
# - Batch Testing: All 28 JCL jobs converted to Spring Batch validation
# - Security Testing: RACF to Spring Security migration validation
# - Performance Testing: Sub-200ms response time and 4-hour batch window
#   compliance verification
#
# Cost Optimization:
# - Mid-tier instance types balance cost-effectiveness with testing capacity
# - Autoscaling (3-6 nodes) enables cost control during idle periods
# - Standard storage allocation (100GB) avoids over-provisioning
# - 7-day backup retention provides adequate rollback capability without
#   excessive storage costs
#
# Pre-Production Validation:
# - Test environment serves as final validation gate before production cutover
# - Infrastructure sizing mirrors production topology at reduced scale
# - Configuration parity with production ensures accurate validation
# - Monitoring stack enables performance validation against production SLAs
# =============================================================================
