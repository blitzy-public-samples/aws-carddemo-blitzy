# =============================================================================
# CardDemo Development Environment - Terraform Variables
# =============================================================================
# Development environment infrastructure configuration for the CardDemo
# credit card management system migration from IBM mainframe COBOL to
# Java Spring Boot microservices architecture.
#
# Purpose: Minimal, cost-optimized infrastructure for isolated developer
# testing and validation of COBOL-to-Java conversion accuracy.
#
# Environment Characteristics:
# - Minimal cluster sizing (2-4 nodes, t3.medium instances)
# - Lower-tier database (db.t3.small, 50GB storage)
# - Disabled monitoring stack (use local logs and Spring Boot Actuator)
# - 3-day backup retention for basic point-in-time recovery
# - Isolated VPC (10.0.0.0/16) separate from test and production
#
# Use Cases:
# - Individual developer testing of Spring Boot REST API endpoints
# - React SPA component validation replacing BMS 3270 screens
# - Spring Batch job development and unit testing (COMBTRAN, POSTTRAN)
# - PostgreSQL schema validation with Flyway migrations
# - Local integration testing with mock external interfaces
# - Rapid iteration cycles with kubectl deployments
#
# Cost Optimization Strategy:
# - Minimize AWS spend for individual developers
# - Use lower-tier instance types (t3.medium vs prod m5.xlarge)
# - Reduce node counts (2 baseline vs prod 3-10)
# - Disable observability stack (Prometheus/Grafana)
# - Short backup retention (3 days vs prod 30 days)
# - Constrained autoscaling limits (max 4 nodes vs prod 10)
#
# Architectural Consistency:
# - Mirrors production architecture patterns for realistic testing
# - Same Kubernetes version (1.31) as test and production
# - Same database engine (PostgreSQL 16.x) as production
# - Same Spring Boot 3.4.5 application containers
# - Enables validation of production deployment patterns at small scale
# =============================================================================

# -----------------------------------------------------------------------------
# Environment Identification
# -----------------------------------------------------------------------------

# Environment name identifier for resource tagging and naming conventions
# Value: "dev" identifies this as development deployment environment
# Purpose: Enables isolated testing separate from "test" and "prod" environments
# Used for: Resource naming (carddemo-dev-eks), tags (Environment=dev), 
#           IAM role naming, security group naming, log group prefixes
environment = "dev"

# Project name for consistent resource naming across all infrastructure components
# Value: "carddemo" identifies all resources as part of CardDemo application
# Purpose: Consistent tagging and resource identification across AWS services
# Used for: Resource naming prefix, cost allocation tags, resource grouping
project_name = "carddemo"

# -----------------------------------------------------------------------------
# AWS Region Configuration
# -----------------------------------------------------------------------------

# Primary AWS region for development environment deployment
# Value: "us-east-1" (N. Virginia) for primary region deployment
# Purpose: Consistent region selection across dev, test, and prod environments
# Rationale: us-east-1 provides lowest latency for East Coast developers,
#            broadest AWS service availability, and lowest pricing tier
# Used for: EKS cluster placement, RDS instance region, VPC region,
#           S3 bucket region for Terraform state, CloudWatch Logs region
aws_region = "us-east-1"

# -----------------------------------------------------------------------------
# Network Configuration
# -----------------------------------------------------------------------------

# VPC CIDR block for development environment network isolation
# Value: "10.0.0.0/16" provides 65,536 private IP addresses
# Purpose: Development network isolation distinct from test (10.1.0.0/16) 
#          and production (10.2.0.0/16) environments
# Subnet Strategy:
#   - Public subnets: 10.0.1.0/24, 10.0.2.0/24 (for NAT gateways, load balancers)
#   - Private subnets: 10.0.10.0/24, 10.0.11.0/24 (for EKS nodes, RDS instances)
#   - Database subnets: 10.0.20.0/24, 10.0.21.0/24 (for RDS subnet group)
# Adequate capacity for:
#   - 2-4 EKS worker nodes (t3.medium instances)
#   - PostgreSQL RDS instance (db.t3.small)
#   - Kubernetes pod networking (AWS VPC CNI allocates IPs to pods)
#   - Future expansion within development constraints
vpc_cidr = "10.0.0.0/16"

# -----------------------------------------------------------------------------
# EKS Cluster Configuration
# -----------------------------------------------------------------------------

# Kubernetes version for EKS cluster
# Value: "1.31" matches Agent Action Plan section 0.5.4 requirement
# Purpose: Ensures version consistency with test and production environments
# Compatibility: Kubernetes 1.31.x supports all required features:
#                - StatefulSets for PostgreSQL (if using in-cluster DB)
#                - Horizontal Pod Autoscaler for backend scaling
#                - Ingress resources for frontend/backend routing
#                - ConfigMaps and Secrets for application configuration
# Upgrade Path: Compatible with Spring Boot 3.4.5 and React 18.3.x deployments
eks_cluster_version = "1.31"

# EKS worker node instance types
# Value: ["t3.medium"] - lower-tier compute for cost optimization
# Capacity: 2 vCPU, 4GB RAM per node as specified in requirements
# Purpose: Sufficient capacity for development testing while minimizing costs
# Workload Support:
#   - Spring Boot backend pods (512MB-1GB memory per pod, 0.5-1 CPU)
#   - React/Nginx frontend pods (256MB memory, 0.25 CPU)
#   - PostgreSQL StatefulSet if deployed in-cluster (1-2GB memory, 1 CPU)
#   - Spring Batch job pods during batch processing tests
# Cost Comparison: t3.medium ~$30/month vs test t3.large ~$60/month 
#                  vs prod m5.xlarge ~$140/month per node
eks_node_instance_types = ["t3.medium"]

# Desired number of EKS worker nodes at baseline
# Value: 2 nodes for minimal availability
# Purpose: Baseline minimal capacity supporting both application and database pods
# Deployment Distribution:
#   - Node 1: Backend Spring Boot pod, Frontend Nginx pod
#   - Node 2: PostgreSQL StatefulSet pod (if in-cluster), monitoring pods
# High Availability: 2 nodes provide basic HA with pod distribution across AZs
# Cost: 2 × t3.medium = ~$60/month baseline compute cost
eks_desired_nodes = 2

# Minimum number of EKS worker nodes (autoscaling lower bound)
# Value: 2 nodes minimum to ensure both application and database can run
# Purpose: Minimum availability threshold preventing scale-down below operational minimum
# Rationale: At least 2 nodes required for:
#            - Kubernetes control plane quorum
#            - Pod distribution across multiple AZs
#            - Basic high availability during node maintenance
#            - Simultaneous backend + database operation
eks_min_nodes = 2

# Maximum number of EKS worker nodes (autoscaling upper bound)
# Value: 4 nodes for limited autoscaling during development testing
# Purpose: Allows scale-out for batch job testing and load testing scenarios
# Scaling Triggers:
#   - CPU utilization > 70% on existing nodes
#   - Spring Batch jobs requiring additional compute capacity
#   - Developer load testing scenarios (validating transaction throughput)
# Constraint: Limited to 4 nodes (vs prod 10 nodes) to control development costs
# Cost Impact: Maximum 4 × t3.medium = ~$120/month during scale-out events
eks_max_nodes = 4

# -----------------------------------------------------------------------------
# RDS PostgreSQL Configuration
# -----------------------------------------------------------------------------

# RDS instance class for PostgreSQL database
# Value: "db.t3.small" - minimal-tier database instance
# Capacity: 2 vCPU, 2GB RAM as specified in requirements
# Purpose: Sufficient capacity for development data volumes and testing
# Workload Support:
#   - Development test data sets (accounts, cards, customers, transactions)
#   - Flyway schema migrations (V1__create_account_table.sql, etc.)
#   - Spring Boot JPA repository operations (CRUD testing)
#   - Spring Batch job processing (small-scale batch validation)
#   - Concurrent developer connections (5-10 active connections)
# Performance: Adequate for development workloads (<100 TPS, sub-second queries)
# Cost: db.t3.small ~$25/month vs test db.t3.medium ~$50/month 
#       vs prod db.m5.large ~$140/month
rds_instance_class = "db.t3.small"

# RDS allocated storage capacity in GB
# Value: 50 GB as specified in requirements for minimal storage capacity
# Purpose: Sufficient for development test data sets without excessive provisioning
# Storage Utilization:
#   - Account table: ~5GB (50,000 test accounts)
#   - Card table: ~8GB (100,000 test cards)
#   - Customer table: ~10GB (50,000 test customers)
#   - Transaction table: ~15GB (1,000,000 test transactions)
#   - Reference tables: ~1GB (transaction types, categories, etc.)
#   - Indexes: ~8GB (B-tree indexes replicating VSAM key access)
#   - Overhead: ~3GB (PostgreSQL system catalogs, WAL, temp space)
# Growth Headroom: 50GB provides ~20GB free space for developer testing expansion
# Cost: 50GB GP3 SSD ~$5/month storage cost
rds_allocated_storage = 50

# RDS automated backup retention period in days
# Value: 3 days as specified in requirements for minimal backup retention
# Purpose: Enables point-in-time recovery for development environment without
#          incurring extended backup storage costs
# Recovery Scenarios:
#   - Accidental data deletion during development testing
#   - Schema migration rollback (failed Flyway migration)
#   - Restore to known good state after corrupt test data
# Cost Optimization: 3-day retention (vs test 7 days, prod 30 days) minimizes
#                    backup storage costs while providing basic recovery capability
# Backup Window: Automated daily backups during low-usage window (2:00-3:00 AM EST)
rds_backup_retention_days = 3

# -----------------------------------------------------------------------------
# Monitoring and Observability Configuration
# -----------------------------------------------------------------------------

# Enable Prometheus/Grafana monitoring stack
# Value: false to disable observability stack in development environment
# Purpose: Reduce infrastructure overhead and costs for developer environments
# Rationale:
#   - Developers use local logs and Spring Boot Actuator for debugging
#   - kubectl logs sufficient for container log access
#   - Spring Boot Actuator /health and /metrics endpoints for app monitoring
#   - CloudWatch Logs available for centralized log aggregation if needed
# Cost Savings: Disabling monitoring stack saves:
#               - Prometheus server pod resources (~1GB memory, 0.5 CPU)
#               - Grafana server pod resources (~512MB memory, 0.25 CPU)
#               - Persistent volume for Prometheus time-series data (~20GB)
#               - Estimated savings: ~$15-20/month per developer environment
# Production Note: Monitoring enabled in test (enable_monitoring = true) and
#                  production (enable_monitoring = true) for full observability
enable_monitoring = false

# -----------------------------------------------------------------------------
# Development Environment Configuration Summary
# -----------------------------------------------------------------------------
# Total Estimated Monthly Cost: ~$100-120
#   - EKS cluster control plane: $72/month (flat rate)
#   - EC2 instances: 2 × t3.medium = ~$60/month (baseline)
#   - RDS db.t3.small: ~$25/month
#   - RDS storage 50GB: ~$5/month
#   - NAT Gateway: ~$32/month (single AZ for dev)
#   - Data transfer: ~$5/month (minimal external traffic)
#   - Load balancers: ~$18/month (ALB for ingress)
#   - Backup storage: ~$2/month (3-day retention)
#
# Cost Comparison to Production:
#   - Development: ~$100-120/month (this configuration)
#   - Test: ~$200-250/month (larger instances, 7-day backups)
#   - Production: ~$800-1000/month (multi-AZ, large instances, 30-day backups)
#
# Validation Use Cases Enabled:
#   ✓ Spring Boot REST API endpoint testing (COACTUPC, COTRN02C conversions)
#   ✓ React SPA component rendering (BMS to React conversions)
#   ✓ Spring Batch job development (COMBTRAN, POSTTRAN JCL conversions)
#   ✓ PostgreSQL schema validation (VSAM to PostgreSQL migrations)
#   ✓ JPA repository operations (VSAM I/O to JPA conversions)
#   ✓ Spring Security authentication (RACF to Spring Security conversions)
#   ✓ Flyway migration testing (V1__create_account_table.sql execution)
#   ✓ Integration testing with mock external interfaces
#   ✓ Performance profiling with Spring Boot Actuator
#   ✓ kubectl deployment workflow validation
# =============================================================================
