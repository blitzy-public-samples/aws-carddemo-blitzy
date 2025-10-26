#
# Terraform Output Values for Monitoring Module
# 
# Purpose: Expose monitoring infrastructure endpoints, credentials, and resource identifiers
#          for integration with other Terraform modules and application configuration.
#
# Migration Context: Replaces mainframe monitoring tools with cloud-native observability
#                    - Mainframe transaction monitoring → Prometheus metrics
#                    - JCL SYSOUT logs → CloudWatch Logs + Elasticsearch
#                    - Batch job timing → Spring Batch metrics + CloudWatch Alarms
#

# =============================================================================
# Prometheus Outputs
# =============================================================================

output "prometheus_endpoint" {
  description = "Prometheus server URL for metrics querying and API access. Use this endpoint to query metrics from Spring Boot /actuator/prometheus endpoints and set up custom metric queries."
  value       = var.enable_prometheus ? "http://${helm_release.prometheus[0].name}-server.${kubernetes_namespace.monitoring.metadata[0].name}.svc.cluster.local" : null
}

output "prometheus_service_account_role_arn" {
  description = "IAM role ARN for Prometheus service account in EKS. Use this ARN for IRSA (IAM Roles for Service Accounts) annotations to grant Prometheus access to CloudWatch metrics."
  value       = var.enable_prometheus ? aws_iam_role.prometheus_service_account[0].arn : null
}

output "prometheus_namespace" {
  description = "Kubernetes namespace where Prometheus is deployed. Use this namespace for service monitor configurations and network policies."
  value       = kubernetes_namespace.monitoring.metadata[0].name
}

# =============================================================================
# Grafana Outputs
# =============================================================================

output "grafana_endpoint" {
  description = "Grafana dashboard URL for visualization access. Access CardDemo monitoring dashboards including transaction metrics, batch job monitoring, JVM metrics, and database performance."
  value       = var.enable_grafana ? "http://${helm_release.grafana[0].name}.${kubernetes_namespace.monitoring.metadata[0].name}.svc.cluster.local" : null
}

output "grafana_admin_password" {
  description = "Grafana admin password for authentication. SENSITIVE: Store securely in secrets management system. Use to access Grafana dashboards and configure datasources."
  value       = var.enable_grafana ? var.grafana_admin_password : null
  sensitive   = true
}

output "grafana_service_account_role_arn" {
  description = "IAM role ARN for Grafana service account. Use this ARN for IRSA annotations to grant Grafana access to CloudWatch as a datasource."
  value       = var.enable_grafana ? aws_iam_role.grafana_service_account[0].arn : null
}

output "grafana_namespace" {
  description = "Kubernetes namespace where Grafana is deployed. Use this namespace for Grafana service configurations and network policies."
  value       = kubernetes_namespace.monitoring.metadata[0].name
}

# =============================================================================
# CloudWatch Outputs
# =============================================================================

output "cloudwatch_log_group_backend" {
  description = "CloudWatch log group name for backend application logs. Spring Boot applications write logs to stdout/stderr which are collected into this log group. Replaces mainframe SYSOUT logs."
  value       = var.enable_cloudwatch ? aws_cloudwatch_log_group.backend[0].name : null
}

output "cloudwatch_log_group_batch" {
  description = "CloudWatch log group name for Spring Batch job logs. Batch job execution logs are captured here for monitoring 4-hour batch window SLA. Replaces JCL job logs."
  value       = var.enable_cloudwatch ? aws_cloudwatch_log_group.batch[0].name : null
}

output "cloudwatch_log_group_cluster" {
  description = "CloudWatch log group name for EKS cluster logs. Kubernetes control plane logs including API server, scheduler, and controller manager logs."
  value       = var.enable_cloudwatch ? aws_cloudwatch_log_group.cluster[0].name : null
}

output "cloudwatch_metrics_namespace" {
  description = "Custom CloudWatch metrics namespace for CardDemo application metrics. Publish custom business metrics (transaction counts, response times, error rates) to this namespace."
  value       = var.enable_cloudwatch ? var.cloudwatch_metrics_namespace : "CardDemo"
}

# =============================================================================
# Elasticsearch Outputs
# =============================================================================

output "elasticsearch_endpoint" {
  description = "Elasticsearch domain endpoint for log indexing and search. Logstash ships logs from Kubernetes to this endpoint for centralized log aggregation. Available when enable_elk_stack is true."
  value       = var.enable_elk_stack ? aws_elasticsearch_domain.elk[0].endpoint : null
}

output "elasticsearch_arn" {
  description = "ARN of Elasticsearch domain for IAM policy references and resource tagging."
  value       = var.enable_elk_stack ? aws_elasticsearch_domain.elk[0].arn : null
}

output "kibana_endpoint" {
  description = "Kibana dashboard URL for log visualization and analysis. Access Kibana to search application logs, create visualizations, and analyze log patterns. Available when enable_elk_stack is true."
  value       = var.enable_elk_stack ? "https://${aws_elasticsearch_domain.elk[0].endpoint}/_plugin/kibana/" : null
}

output "elk_namespace" {
  description = "Kubernetes namespace where ELK stack components (Logstash) are deployed. Use this namespace for Logstash configurations and network policies."
  value       = var.enable_elk_stack ? kubernetes_namespace.logging[0].metadata[0].name : null
}

# =============================================================================
# Logstash Outputs
# =============================================================================

output "logstash_service_account_role_arn" {
  description = "IAM role ARN for Logstash service account. Use this ARN for IRSA annotations to grant Logstash access to CloudWatch Logs for log shipping."
  value       = var.enable_elk_stack ? aws_iam_role.logstash_service_account[0].arn : null
}

# =============================================================================
# Alerting Outputs
# =============================================================================

output "alert_sns_topic_arn" {
  description = "SNS topic ARN for critical alerts. CloudWatch Alarms publish notifications to this topic for SLA violations (transaction response time >200ms, batch job failures). Subscribe email addresses or integrate with incident management systems."
  value       = aws_sns_topic.alerts.arn
}

output "alert_sns_topic_name" {
  description = "SNS topic name for alert subscriptions. Use this name to create additional SNS subscriptions for email, SMS, or Lambda function notifications."
  value       = aws_sns_topic.alerts.name
}

# =============================================================================
# Security Group Outputs
# =============================================================================

output "monitoring_security_group_id" {
  description = "Security group ID for monitoring components. This security group controls network access to Prometheus, Grafana, and Elasticsearch. Use this ID to configure ingress rules for application access."
  value       = aws_security_group.monitoring.id
}

# =============================================================================
# Summary Output
# =============================================================================

output "monitoring_summary" {
  description = "Summary of all monitoring endpoints and configuration for quick reference and application integration."
  value = {
    prometheus = {
      enabled              = var.enable_prometheus
      endpoint             = var.enable_prometheus ? "http://${helm_release.prometheus[0].name}-server.${kubernetes_namespace.monitoring.metadata[0].name}.svc.cluster.local" : null
      service_account_role = var.enable_prometheus ? aws_iam_role.prometheus_service_account[0].arn : null
      namespace            = kubernetes_namespace.monitoring.metadata[0].name
    }
    grafana = {
      enabled              = var.enable_grafana
      endpoint             = var.enable_grafana ? "http://${helm_release.grafana[0].name}.${kubernetes_namespace.monitoring.metadata[0].name}.svc.cluster.local" : null
      service_account_role = var.enable_grafana ? aws_iam_role.grafana_service_account[0].arn : null
      namespace            = kubernetes_namespace.monitoring.metadata[0].name
    }
    cloudwatch = {
      enabled           = var.enable_cloudwatch
      log_group_backend = var.enable_cloudwatch ? aws_cloudwatch_log_group.backend[0].name : null
      log_group_batch   = var.enable_cloudwatch ? aws_cloudwatch_log_group.batch[0].name : null
      log_group_cluster = var.enable_cloudwatch ? aws_cloudwatch_log_group.cluster[0].name : null
      metrics_namespace = var.enable_cloudwatch ? var.cloudwatch_metrics_namespace : "CardDemo"
    }
    elk = {
      enabled                  = var.enable_elk_stack
      elasticsearch_endpoint   = var.enable_elk_stack ? aws_elasticsearch_domain.elk[0].endpoint : null
      kibana_endpoint          = var.enable_elk_stack ? "https://${aws_elasticsearch_domain.elk[0].endpoint}/_plugin/kibana/" : null
      logstash_service_account = var.enable_elk_stack ? aws_iam_role.logstash_service_account[0].arn : null
      namespace                = var.enable_elk_stack ? kubernetes_namespace.logging[0].metadata[0].name : null
    }
    alerting = {
      sns_topic_arn  = aws_sns_topic.alerts.arn
      sns_topic_name = aws_sns_topic.alerts.name
    }
    security = {
      monitoring_security_group_id = aws_security_group.monitoring.id
    }
  }
}
