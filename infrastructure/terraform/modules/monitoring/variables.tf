# ==============================================================================
# Terraform Variables for Monitoring Module
# ==============================================================================
# Purpose: Define input variables for monitoring and observability infrastructure
# Components: Prometheus, Grafana, CloudWatch, ELK Stack
# Migration Context: Replaces mainframe monitoring tools (JCL SYSOUT logs,
#                    transaction monitoring) with cloud-native observability
# ==============================================================================

# ------------------------------------------------------------------------------
# EKS Cluster Configuration Variables
# ------------------------------------------------------------------------------

variable "eks_cluster_id" {
  description = "The ID of the EKS cluster to monitor. Used for integrating monitoring components with the Kubernetes cluster."
  type        = string

  validation {
    condition     = length(var.eks_cluster_id) > 0
    error_message = "EKS cluster ID must not be empty."
  }
}

variable "eks_cluster_name" {
  description = "The name of the EKS cluster. Used for resource naming and tagging of monitoring components."
  type        = string

  validation {
    condition     = length(var.eks_cluster_name) > 0 && can(regex("^[a-zA-Z][a-zA-Z0-9-]*$", var.eks_cluster_name))
    error_message = "EKS cluster name must start with a letter and contain only alphanumeric characters and hyphens."
  }
}

# ------------------------------------------------------------------------------
# Networking Configuration Variables
# ------------------------------------------------------------------------------

variable "vpc_id" {
  description = "The VPC ID where monitoring infrastructure components will be deployed. Used for security group and Elasticsearch domain configuration."
  type        = string

  validation {
    condition     = can(regex("^vpc-[a-z0-9]+$", var.vpc_id))
    error_message = "VPC ID must be a valid AWS VPC identifier (vpc-xxxxxxxxx)."
  }
}

variable "private_subnet_ids" {
  description = "List of private subnet IDs for deploying monitoring infrastructure. Elasticsearch domain nodes will be distributed across these subnets for high availability."
  type        = list(string)

  validation {
    condition     = length(var.private_subnet_ids) >= 2
    error_message = "At least 2 private subnet IDs are required for high availability deployment."
  }

  validation {
    condition     = alltrue([for id in var.private_subnet_ids : can(regex("^subnet-[a-z0-9]+$", id))])
    error_message = "All subnet IDs must be valid AWS subnet identifiers (subnet-xxxxxxxxx)."
  }
}

# ------------------------------------------------------------------------------
# Feature Toggle Variables
# ------------------------------------------------------------------------------

variable "enable_prometheus" {
  description = "Enable Prometheus metrics collection from Spring Boot /actuator/prometheus endpoints. Replaces mainframe transaction monitoring."
  type        = bool
  default     = true
}

variable "enable_grafana" {
  description = "Enable Grafana dashboards for visualization of metrics. Provides transaction monitoring, batch job tracking, and JVM metrics replacing mainframe performance monitoring."
  type        = bool
  default     = true
}

variable "enable_cloudwatch" {
  description = "Enable CloudWatch integration for AWS-native monitoring. Provides log aggregation and metric collection for application logs and EKS cluster metrics."
  type        = bool
  default     = true
}

variable "enable_elk_stack" {
  description = "Enable ELK stack (Elasticsearch, Logstash, Kibana) for centralized logging. Resource-intensive component, recommended to disable in dev/test environments for cost savings. Replaces mainframe JCL SYSOUT logs."
  type        = bool
  default     = false
}

# ------------------------------------------------------------------------------
# Prometheus Configuration Variables
# ------------------------------------------------------------------------------

variable "prometheus_retention_days" {
  description = "Number of days to retain Prometheus metrics. Default 15 days provides balance between historical analysis and storage costs. Replaces mainframe metrics retention policies."
  type        = number
  default     = 15

  validation {
    condition     = var.prometheus_retention_days >= 1 && var.prometheus_retention_days <= 365
    error_message = "Prometheus retention days must be between 1 and 365 days."
  }
}

variable "prometheus_storage_size" {
  description = "Persistent volume size for Prometheus metrics storage. Format: number + unit (Gi for gibibytes). Default 50Gi supports approximately 15 days of metrics for typical CardDemo workload."
  type        = string
  default     = "50Gi"

  validation {
    condition     = can(regex("^[0-9]+Gi$", var.prometheus_storage_size))
    error_message = "Prometheus storage size must be in format '<number>Gi' (e.g., '50Gi')."
  }
}

# ------------------------------------------------------------------------------
# Grafana Configuration Variables
# ------------------------------------------------------------------------------

variable "grafana_admin_password" {
  description = "Admin password for Grafana dashboard access. REQUIRED. Should be stored in AWS Secrets Manager and passed as variable. Minimum 8 characters required for security."
  type        = string
  sensitive   = true

  validation {
    condition     = length(var.grafana_admin_password) >= 8
    error_message = "Grafana admin password must be at least 8 characters long."
  }
}

variable "grafana_storage_size" {
  description = "Persistent volume size for Grafana dashboard storage. Format: number + unit (Gi for gibibytes). Default 10Gi is sufficient for dashboard configurations."
  type        = string
  default     = "10Gi"

  validation {
    condition     = can(regex("^[0-9]+Gi$", var.grafana_storage_size))
    error_message = "Grafana storage size must be in format '<number>Gi' (e.g., '10Gi')."
  }
}

# ------------------------------------------------------------------------------
# Elasticsearch Configuration Variables
# ------------------------------------------------------------------------------

variable "elasticsearch_version" {
  description = "Elasticsearch version for ELK stack deployment. Default '7.17' is the latest 7.x release with long-term support. Must be compatible with Logstash and Kibana versions."
  type        = string
  default     = "7.17"

  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+$", var.elasticsearch_version))
    error_message = "Elasticsearch version must be in format 'major.minor' (e.g., '7.17')."
  }
}

variable "elasticsearch_instance_count" {
  description = "Number of Elasticsearch instances for the domain. Default 3 provides high availability across multiple availability zones. Use 1 for dev/test to reduce costs."
  type        = number
  default     = 3

  validation {
    condition     = var.elasticsearch_instance_count >= 1 && var.elasticsearch_instance_count <= 10
    error_message = "Elasticsearch instance count must be between 1 and 10."
  }
}

variable "elasticsearch_instance_type" {
  description = "EC2 instance type for Elasticsearch nodes. Default 't3.medium.elasticsearch' provides 2 vCPU, 4GB RAM suitable for moderate log volumes. Scale up for higher log ingestion rates."
  type        = string
  default     = "t3.medium.elasticsearch"

  validation {
    condition     = can(regex("^[a-z0-9]+\\.[a-z0-9]+\\.elasticsearch$", var.elasticsearch_instance_type))
    error_message = "Elasticsearch instance type must be a valid AWS Elasticsearch instance type (e.g., 't3.medium.elasticsearch')."
  }
}

variable "elasticsearch_volume_size" {
  description = "EBS volume size per Elasticsearch node in GB. Default 100GB per node provides storage for approximately 30 days of application logs. Increase for longer retention."
  type        = number
  default     = 100

  validation {
    condition     = var.elasticsearch_volume_size >= 10 && var.elasticsearch_volume_size <= 1500
    error_message = "Elasticsearch volume size must be between 10 and 1500 GB per node."
  }
}

variable "kibana_instance_type" {
  description = "EC2 instance type for Kibana node. Default 't3.small.elasticsearch' provides 2 vCPU, 2GB RAM sufficient for dashboard and search operations."
  type        = string
  default     = "t3.small.elasticsearch"

  validation {
    condition     = can(regex("^[a-z0-9]+\\.[a-z0-9]+\\.elasticsearch$", var.kibana_instance_type))
    error_message = "Kibana instance type must be a valid AWS Elasticsearch instance type (e.g., 't3.small.elasticsearch')."
  }
}

# ------------------------------------------------------------------------------
# CloudWatch Configuration Variables
# ------------------------------------------------------------------------------

variable "cloudwatch_log_retention_days" {
  description = "Number of days to retain CloudWatch logs for backend application, Spring Batch jobs, and EKS cluster logs. Default 7 days balances troubleshooting needs with cost. Replaces mainframe log retention policies."
  type        = number
  default     = 7

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1827, 3653], var.cloudwatch_log_retention_days)
    error_message = "CloudWatch log retention days must be one of the valid values: 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1827, or 3653 days."
  }
}

variable "cloudwatch_metrics_namespace" {
  description = "CloudWatch custom metrics namespace for CardDemo application metrics. Default 'CardDemo' groups all application metrics for transaction counts, response times, and batch job durations."
  type        = string
  default     = "CardDemo"

  validation {
    condition     = length(var.cloudwatch_metrics_namespace) > 0 && can(regex("^[a-zA-Z0-9_/-]+$", var.cloudwatch_metrics_namespace))
    error_message = "CloudWatch metrics namespace must contain only alphanumeric characters, underscores, hyphens, and forward slashes."
  }
}

# ------------------------------------------------------------------------------
# Alerting Configuration Variables
# ------------------------------------------------------------------------------

variable "alert_email_endpoints" {
  description = "List of email addresses for receiving CloudWatch alarm notifications. SNS topic subscriptions will be created for each email. Example: ['devops@example.com', 'oncall@example.com']"
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for email in var.alert_email_endpoints : can(regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$", email))])
    error_message = "All alert email endpoints must be valid email addresses."
  }
}

variable "alert_slack_webhook" {
  description = "Optional Slack webhook URL for sending alert notifications to Slack channel. Leave empty to disable Slack integration. Example: 'https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXXXXXX'"
  type        = string
  default     = ""

  validation {
    condition     = var.alert_slack_webhook == "" || can(regex("^https://hooks\\.slack\\.com/services/[A-Z0-9]+/[A-Z0-9]+/[A-Za-z0-9]+$", var.alert_slack_webhook))
    error_message = "Slack webhook URL must be empty or a valid Slack webhook URL format."
  }
}

# ------------------------------------------------------------------------------
# General Configuration Variables
# ------------------------------------------------------------------------------

variable "environment" {
  description = "Environment name for deployment (dev, test, prod). Used for resource naming, tagging, and environment-specific configurations. Affects monitoring component sizing and retention policies."
  type        = string

  validation {
    condition     = contains(["dev", "test", "staging", "prod"], var.environment)
    error_message = "Environment must be one of: dev, test, staging, prod."
  }
}

variable "tags" {
  description = "Map of tags to apply to all monitoring resources. Should include Environment, Project, ManagedBy. Example: { Environment = 'prod', Project = 'CardDemo', ManagedBy = 'Terraform' }"
  type        = map(string)
  default     = {}

  validation {
    condition     = alltrue([for key, value in var.tags : length(key) > 0 && length(value) > 0])
    error_message = "All tag keys and values must be non-empty strings."
  }
}
