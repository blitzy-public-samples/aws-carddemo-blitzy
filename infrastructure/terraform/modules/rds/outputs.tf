# =============================================================================
# Terraform Outputs for RDS PostgreSQL Module
# =============================================================================
# Purpose: Expose RDS database connection details, security identifiers, 
#          monitoring endpoints, and resource ARNs for integration with:
#          - Spring Boot application configuration (application.yml)
#          - Kubernetes ConfigMaps and Secrets
#          - Monitoring dashboards (CloudWatch, Grafana)
#          - Backup automation scripts
#          - Infrastructure documentation
#
# Module: infrastructure/terraform/modules/rds
# Target: AWS RDS PostgreSQL 16.6 replacing VSAM KSDS file storage
# =============================================================================

# -----------------------------------------------------------------------------
# Database Instance Identification
# -----------------------------------------------------------------------------

output "db_instance_id" {
  description = "RDS instance identifier for AWS API calls and resource identification. Use in CloudFormation StackSets, Terraform data sources, and AWS CLI commands."
  value       = aws_db_instance.main.id
}

output "db_instance_arn" {
  description = "Amazon Resource Name (ARN) of RDS instance for IAM policy attachments, resource tagging, and cross-account access. Required for AWS Backup plans, CloudWatch alarms, and EventBridge rules."
  value       = aws_db_instance.main.arn
}

output "db_instance_resource_id" {
  description = "RDS resource ID (dbi-xxxxx format) for CloudWatch metrics namespace and Performance Insights queries. Use in CloudWatch dashboard widgets and metric filters."
  value       = aws_db_instance.main.resource_id
}

# -----------------------------------------------------------------------------
# Database Connection Details
# -----------------------------------------------------------------------------

output "db_instance_endpoint" {
  description = "RDS instance endpoint in 'hostname:port' format for JDBC connection strings. Use in Spring Boot application.yml: spring.datasource.url=jdbc:postgresql://$${db_instance_endpoint}/$${db_instance_name}"
  value       = aws_db_instance.main.endpoint
}

output "db_instance_address" {
  description = "RDS instance hostname (without port) for connection strings requiring separate host and port parameters. Use in Kubernetes ConfigMap for SPRING_DATASOURCE_HOST environment variable."
  value       = aws_db_instance.main.address
}

output "db_instance_port" {
  description = "PostgreSQL port number (default 5432) for connection configuration. Use in Kubernetes ConfigMap for SPRING_DATASOURCE_PORT and security group ingress rules."
  value       = aws_db_instance.main.port
}

output "db_instance_name" {
  description = "Database name containing 11 migrated VSAM dataset tables (account, card, customer, transaction, etc.). Use in Spring Boot application.yml: spring.datasource.url jdbc database path and Flyway migrations."
  value       = aws_db_instance.main.db_name
}

output "db_instance_username" {
  description = "Master username for database authentication. Store in Kubernetes Secret (not ConfigMap) and reference in Spring Boot via SPRING_DATASOURCE_USERNAME environment variable. Rotate credentials via AWS Secrets Manager."
  value       = aws_db_instance.main.username
  sensitive   = true
}

# -----------------------------------------------------------------------------
# Network and Security Configuration
# -----------------------------------------------------------------------------

output "db_security_group_id" {
  description = "Security group ID controlling network access to RDS instance. Update ingress rules to allow PostgreSQL port 5432 from EKS worker node security group. Reference in Terraform aws_security_group_rule resources for application tier access."
  value       = aws_security_group.rds.id
}

output "db_subnet_group_id" {
  description = "DB subnet group ID for VPC placement validation. Verify subnet group spans multiple Availability Zones for multi-AZ deployment."
  value       = aws_db_subnet_group.main.id
}

output "db_subnet_group_name" {
  description = "DB subnet group name for network topology documentation and Terraform data source lookups."
  value       = aws_db_subnet_group.main.name
}

# -----------------------------------------------------------------------------
# Parameter Group Configuration
# -----------------------------------------------------------------------------

output "db_parameter_group_id" {
  description = "DB parameter group ID for OLTP performance tuning verification. Contains custom parameters (shared_buffers, effective_cache_size, work_mem) optimized for VSAM KSDS replacement workload."
  value       = aws_db_parameter_group.main.id
}

output "db_parameter_group_name" {
  description = "DB parameter group name for parameter change tracking and CloudFormation stack updates. Review parameter modifications via AWS CLI: aws rds describe-db-parameters --db-parameter-group-name <name>"
  value       = aws_db_parameter_group.main.name
}

# -----------------------------------------------------------------------------
# High Availability and Disaster Recovery
# -----------------------------------------------------------------------------

output "db_instance_availability_zone" {
  description = "Primary Availability Zone hosting RDS instance for disaster recovery planning. In multi-AZ deployment, standby replica runs in different AZ. Use for network latency optimization and AZ-specific failure analysis."
  value       = aws_db_instance.main.availability_zone
}

output "db_instance_multi_az" {
  description = "Boolean indicating multi-AZ deployment status for high availability validation. true = synchronous replication to standby in different AZ with automatic failover (60-120 seconds). Required for production 99.95% SLA."
  value       = aws_db_instance.main.multi_az
}

output "backup_retention_period" {
  description = "Automated backup retention period in days (7-35) for point-in-time recovery. Longer retention supports historical data restoration and compliance requirements. Use in backup documentation and RTO/RPO calculations."
  value       = aws_db_instance.main.backup_retention_period
}

output "backup_window" {
  description = "Daily backup window in UTC format (HH:MM-HH:MM) for maintenance scheduling. Schedule application deployments and schema migrations outside backup window to avoid I/O contention. Display in operations calendar."
  value       = aws_db_instance.main.backup_window
}

output "maintenance_window" {
  description = "Weekly maintenance window in UTC format (ddd:HH:MM-ddd:HH:MM) for minor version upgrades and system patches. Schedule during low-traffic period. AWS may perform emergency maintenance outside this window for security patches."
  value       = aws_db_instance.main.maintenance_window
}

# -----------------------------------------------------------------------------
# Monitoring and Observability
# -----------------------------------------------------------------------------

output "monitoring_role_arn" {
  description = "IAM role ARN for RDS enhanced monitoring. Grants CloudWatch Logs permissions for OS-level metrics (CPU, memory, swap, disk I/O) at 60-second intervals. Reference in CloudWatch dashboard IAM policies."
  value       = aws_iam_role.rds_monitoring.arn
}

output "performance_insights_enabled" {
  description = "Boolean indicating Performance Insights activation status for query-level analysis. When true, access Performance Insights console to identify slow queries, lock contention, and I/O bottlenecks. Use for VSAM-to-PostgreSQL performance validation."
  value       = aws_db_instance.main.performance_insights_enabled
}

output "cloudwatch_log_groups" {
  description = "List of CloudWatch log group names for PostgreSQL log aggregation. Includes 'postgresql' (query logs, errors, slow queries) and 'upgrade' (version upgrade logs). Configure log retention policies and export to S3 for compliance. Use in Fluent Bit/Fluentd log shipping configuration."
  value = [
    "/aws/rds/instance/${aws_db_instance.main.identifier}/postgresql",
    "/aws/rds/instance/${aws_db_instance.main.identifier}/upgrade"
  ]
}

# -----------------------------------------------------------------------------
# Encryption Configuration
# -----------------------------------------------------------------------------

output "kms_key_id" {
  description = "AWS KMS key ID (or ARN) used for encryption at rest validation. Verify encryption status and key rotation policy. Reference in AWS Backup vault encryption configuration and cross-region snapshot copy operations."
  value       = aws_db_instance.main.kms_key_id
}

# -----------------------------------------------------------------------------
# Read Replica Configuration (Conditional)
# -----------------------------------------------------------------------------

output "read_replica_endpoint" {
  description = "Read replica endpoint in 'hostname:port' format for reporting workload distribution (conditionally created). Use in Spring Boot @Transactional(readOnly=true) datasource configuration to offload SELECT queries from primary instance. Configure with lower connection pool size than primary."
  value       = var.create_read_replica ? aws_db_instance.read_replica[0].endpoint : null
}

# -----------------------------------------------------------------------------
# Integration Examples
# -----------------------------------------------------------------------------
# 
# Spring Boot application.yml configuration:
# ---
# spring:
#   datasource:
#     url: jdbc:postgresql://${db_instance_endpoint}/${db_instance_name}
#     username: ${db_instance_username}  # From Kubernetes Secret
#     password: ${db_instance_password}  # From Kubernetes Secret
#     driver-class-name: org.postgresql.Driver
#   jpa:
#     database-platform: org.hibernate.dialect.PostgreSQLDialect
#     hibernate:
#       ddl-auto: validate  # Flyway manages schema
#   flyway:
#     enabled: true
#     url: jdbc:postgresql://${db_instance_endpoint}/${db_instance_name}
#     user: ${db_instance_username}
#     password: ${db_instance_password}
#     baseline-on-migrate: true
# ---
#
# Kubernetes Secret (created from Terraform outputs):
# ---
# apiVersion: v1
# kind: Secret
# metadata:
#   name: postgres-credentials
#   namespace: carddemo
# type: Opaque
# stringData:
#   SPRING_DATASOURCE_URL: "jdbc:postgresql://${db_instance_endpoint}/${db_instance_name}"
#   SPRING_DATASOURCE_USERNAME: "${db_instance_username}"
#   SPRING_DATASOURCE_PASSWORD: "${db_instance_password}"  # Injected via external secret manager
# ---
#
# CloudWatch Dashboard JSON snippet:
# ---
# {
#   "type": "metric",
#   "properties": {
#     "metrics": [
#       [ "AWS/RDS", "CPUUtilization", { "stat": "Average", "label": "CPU %" } ],
#       [ ".", "DatabaseConnections", { "stat": "Sum", "label": "Connections" } ],
#       [ ".", "ReadLatency", { "stat": "Average", "label": "Read Latency (ms)" } ],
#       [ ".", "WriteLatency", { "stat": "Average", "label": "Write Latency (ms)" } ]
#     ],
#     "view": "timeSeries",
#     "stacked": false,
#     "region": "us-east-1",
#     "title": "RDS PostgreSQL - ${db_instance_id}",
#     "period": 300
#   }
# }
# ---
