# =====================================================================
# Production Environment Terraform Variables
# CardDemo Credit Card Management System - Mainframe to Cloud Migration
# =====================================================================
#
# This file defines production-grade infrastructure configuration for
# live credit card transaction processing, replacing IBM z/OS mainframe
# CICS online transaction processing and JCL batch operations.
#
# Performance Requirements:
# - Sub-200ms response times for card authorization requests
# - 10,000 TPS transaction throughput capacity
# - 28 Spring Batch jobs completing within 4-hour overnight windows
#
# Source Workload References:
# - COMBTRAN.jcl: Daily transaction combination and sorting batch job
# - POSTTRAN.jcl: Transaction posting and category balance processing
# - All 28 JCL batch jobs requiring equivalent Spring Batch execution
#
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# Licensed under the Apache License, Version 2.0
# =====================================================================

# ---------------------------------------------------------------------
# Environment Identification
# ---------------------------------------------------------------------

# Environment name identifier for production deployment
environment = "prod"

# AWS region for primary production deployment
# Selected for proximity to business operations and data sovereignty
aws_region = "us-east-1"

# Project name for consistent resource naming across infrastructure
project_name = "carddemo"

# ---------------------------------------------------------------------
# Network Configuration
# ---------------------------------------------------------------------

# VPC CIDR block for production network isolation
# Distinct from dev (10.0.0.0/16) and test (10.1.0.0/16) environments
# Provides 65,536 IP addresses for production workloads
vpc_cidr = "10.2.0.0/16"

# Availability zones for high-availability deployment
# Multi-AZ configuration ensures resilience during AZ failures
availability_zones = [
  "us-east-1a",
  "us-east-1b",
  "us-east-1c"
]

# ---------------------------------------------------------------------
# EKS Cluster Configuration
# ---------------------------------------------------------------------

# Kubernetes cluster version matching Agent Action Plan requirement
# Section 0.5.4: Kubernetes 1.31.x for container orchestration
eks_cluster_version = "1.31"

# EKS worker node instance types for production-tier compute
# m5.xlarge: 4 vCPU, 16GB RAM per node as specified in requirements
# Enables horizontal scaling to support 10,000 TPS capacity
eks_node_instance_types = ["m5.xlarge"]

# Baseline high-availability node count for production workload
# Middle of 6-10 range provides redundancy and capacity headroom
eks_desired_nodes = 8

# Minimum node count ensuring high-availability threshold
# Maintains redundancy during node maintenance and rolling updates
eks_min_nodes = 6

# Maximum node count for peak load autoscaling
# Handles transaction processing surges and batch job execution
eks_max_nodes = 10

# Node disk size for container image storage and local volumes
# 100GB provides sufficient space for Spring Boot application images,
# temporary batch processing files, and Kubernetes system components
eks_node_disk_size = 100

# ---------------------------------------------------------------------
# RDS Database Configuration
# ---------------------------------------------------------------------

# PostgreSQL engine version matching Agent Action Plan requirement
# Section 0.5.4: PostgreSQL 16.x for VSAM data migration
rds_engine_version = "16.6"

# RDS instance class for production-tier database performance
# db.r5.large: 2 vCPU, 16GB RAM, memory-optimized for PostgreSQL
# Supports sub-10ms primary key lookups matching VSAM performance
rds_instance_class = "db.r5.large"

# Allocated storage capacity for enterprise credit card data
# 500GB supports:
# - 1M+ transaction records with historical data
# - 100K card master records
# - 50K account and customer records
# - Transaction category balances and reference data
rds_allocated_storage = 500

# Maximum storage for autoscaling (1TB total capacity)
# Allows growth without manual intervention while controlling costs
rds_max_allocated_storage = 1000

# Multi-AZ deployment for high availability and disaster recovery
# Automatic failover to standby replica in different AZ
# Meets zero-downtime deployment requirements
rds_multi_az = true

# Backup retention period for regulatory compliance and recovery
# 30 days enables point-in-time recovery for audit and compliance
# Supports rollback capability per Agent Action Plan section 0.7.10
rds_backup_retention_days = 30

# Preferred backup window during lowest transaction volume period
# 06:00-07:00 UTC (01:00-02:00 EST) after batch processing completion
rds_backup_window = "06:00-07:00"

# Preferred maintenance window for automated patches and updates
# Sunday 07:00-08:00 UTC (02:00-03:00 EST Sunday) for minimal impact
rds_maintenance_window = "sun:07:00-sun:08:00"

# Deletion protection to prevent accidental database deletion
# Critical for production data protection and compliance
rds_deletion_protection = true

# Storage encryption for data-at-rest security compliance
# Meets financial industry data protection requirements
rds_storage_encrypted = true

# Performance Insights for database query performance monitoring
# Enables identification of slow queries and optimization opportunities
rds_performance_insights_enabled = true

# Performance Insights retention period (7 days)
# Sufficient for performance troubleshooting and trend analysis
rds_performance_insights_retention = 7

# ---------------------------------------------------------------------
# Monitoring and Observability Configuration
# ---------------------------------------------------------------------

# Enable comprehensive Prometheus and Grafana monitoring stack
# Provides production metrics, alerting, and SLA tracking for:
# - Transaction response times (target: sub-200ms)
# - Throughput metrics (target: 10,000 TPS)
# - Batch job execution times (target: 4-hour completion)
# - Database performance and connection pool metrics
# - Kubernetes cluster health and resource utilization
enable_monitoring = true

# CloudWatch log retention period for application and system logs
# 90 days balances operational visibility with cost management
cloudwatch_log_retention_days = 90

# ---------------------------------------------------------------------
# Application Configuration
# ---------------------------------------------------------------------

# Backend application replica count for high availability
# 6 replicas across 8 nodes provides redundancy and load distribution
backend_replica_count = 6

# Backend container resource requests and limits
# Requests: Guaranteed resources for stable operation
# Limits: Maximum resources preventing resource exhaustion
backend_cpu_request = "2000m"
backend_cpu_limit = "4000m"
backend_memory_request = "4Gi"
backend_memory_limit = "8Gi"

# Frontend application replica count for static content serving
# 3 replicas provide redundancy for React SPA delivery via Nginx
frontend_replica_count = 3

# Frontend container resource requests and limits
# Lower than backend as Nginx serves static files efficiently
frontend_cpu_request = "500m"
frontend_cpu_limit = "1000m"
frontend_memory_request = "512Mi"
frontend_memory_limit = "1Gi"

# Horizontal Pod Autoscaler configuration for backend services
# Target CPU utilization: 70% triggers scale-out to handle load spikes
# Maintains performance during peak transaction volumes
hpa_target_cpu_percentage = 70

# Horizontal Pod Autoscaler configuration for Spring Batch jobs
# Separate autoscaling for batch processing workload
# Ensures 4-hour completion window for 28 overnight jobs
batch_hpa_target_cpu_percentage = 80

# ---------------------------------------------------------------------
# Database Connection Pool Configuration
# ---------------------------------------------------------------------

# Maximum database connections per backend pod
# 6 pods × 50 connections = 300 total connections
# Well within RDS connection limit for db.r5.large (1000+)
db_connection_pool_max_size = 50

# Minimum idle connections maintained in pool
# Reduces connection establishment latency for transaction processing
db_connection_pool_min_idle = 10

# Connection timeout for database connection acquisition
# 30 seconds prevents indefinite blocking on connection exhaustion
db_connection_timeout_ms = 30000

# ---------------------------------------------------------------------
# Security Configuration
# ---------------------------------------------------------------------

# Enable AWS Secrets Manager for sensitive data management
# Stores database credentials, JWT secrets, API keys securely
enable_secrets_manager = true

# Enable AWS WAF for web application firewall protection
# Protects frontend and API endpoints from common web exploits
enable_waf = true

# SSL/TLS certificate for HTTPS endpoints
# Placeholder for ACM certificate ARN (configured in AWS Console)
ssl_certificate_arn = ""

# Enable VPC Flow Logs for network traffic analysis and security audit
enable_vpc_flow_logs = true

# ---------------------------------------------------------------------
# Backup and Disaster Recovery Configuration
# ---------------------------------------------------------------------

# Enable automated EBS volume snapshots for EKS node persistent storage
enable_ebs_snapshots = true

# EBS snapshot retention period (30 days)
ebs_snapshot_retention_days = 30

# Enable cross-region backup replication for disaster recovery
# Replicates RDS backups to secondary region (us-west-2)
enable_cross_region_backup = true
backup_replication_region = "us-west-2"

# ---------------------------------------------------------------------
# Cost Optimization Configuration
# ---------------------------------------------------------------------

# Resource tagging for cost allocation and management
tags = {
  Environment     = "prod"
  Project         = "carddemo"
  ManagedBy       = "terraform"
  CostCenter      = "credit-card-operations"
  BusinessUnit    = "financial-services"
  DataClass       = "confidential"
  Compliance      = "pci-dss"
  BackupPolicy    = "daily"
  DisasterRecovery = "enabled"
  MaintenanceWindow = "sunday-02:00-03:00-est"
}

# Enable EKS cluster autoscaler for cost-effective node management
# Scales down during low-traffic periods, scales up during peaks
enable_cluster_autoscaler = true

# Enable AWS Cost Explorer integration for spend visibility
enable_cost_explorer_tags = true

# ---------------------------------------------------------------------
# Batch Processing Configuration
# ---------------------------------------------------------------------

# Spring Batch job execution configuration
# Supports 28 JCL batch jobs migrated to Spring Batch framework

# Maximum concurrent batch job executions
# Allows parallel execution of independent jobs (e.g., COMBTRAN, POSTTRAN)
batch_max_concurrent_jobs = 5

# Batch job execution timeout (4 hours in seconds)
# Ensures jobs complete within overnight processing window (02:00-06:00)
batch_job_timeout_seconds = 14400

# Batch chunk size for Spring Batch ItemReader/Writer
# 1000 records per chunk balances memory usage and commit frequency
batch_chunk_size = 1000

# Batch job retry attempts for transient failures
# 3 retries with exponential backoff for resilience
batch_job_retry_attempts = 3

# ---------------------------------------------------------------------
# Integration Configuration
# ---------------------------------------------------------------------

# External system integration endpoints (placeholder for production values)
# These would be configured with actual production endpoints

# Payment network interface endpoint for ISO 8583 message processing
payment_network_endpoint = ""

# Bank core system file exchange location for fixed-width file interfaces
bank_core_file_exchange_bucket = ""

# Regulatory reporting system S3 bucket for monthly statement files
regulatory_reporting_bucket = ""

# ---------------------------------------------------------------------
# Performance Tuning Configuration
# ---------------------------------------------------------------------

# JVM heap size for Spring Boot backend pods
# 6GB heap with 8GB memory limit provides headroom for non-heap memory
jvm_heap_size = "6g"

# JVM garbage collector selection for low-latency transaction processing
# G1GC balances throughput and pause times for sub-200ms response target
jvm_gc_type = "G1GC"

# PostgreSQL shared buffers (25% of RDS instance memory = 4GB)
# Optimizes query performance for high-transaction workload
postgres_shared_buffers = "4GB"

# PostgreSQL effective cache size (75% of RDS instance memory = 12GB)
# Informs query planner for optimal execution plans
postgres_effective_cache_size = "12GB"

# PostgreSQL max connections limit
# 500 connections supports 6 backend pods with 50 connections each + headroom
postgres_max_connections = 500

# ---------------------------------------------------------------------
# End of Production Environment Configuration
# ---------------------------------------------------------------------
