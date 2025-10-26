#
# Terraform Monitoring Module - CardDemo Modernization Project
# 
# Purpose: Replace mainframe monitoring tools with cloud-native observability infrastructure
# 
# Mainframe Monitoring Replacement:
# - JCL SYSOUT logs → CloudWatch Logs + Elasticsearch
# - Mainframe transaction monitoring → Prometheus metrics + Grafana dashboards
# - Batch job timing (JCL) → Spring Batch metrics + CloudWatch alarms
# - CICS transaction response times → Prometheus histogram metrics
# - Mainframe SLA tracking → CloudWatch alarms with SNS notifications
# - Sub-200ms transaction response time monitoring replacing mainframe performance tracking
# - 4-hour batch window monitoring replacing JCL job execution timing
#

# Data sources for EKS cluster information
data "aws_eks_cluster" "cluster" {
  name = var.eks_cluster_name
}

data "aws_region" "current" {}

data "aws_caller_identity" "current" {}

# Data source for OIDC provider (required for IRSA)
data "aws_iam_openid_connect_provider" "eks_oidc" {
  url = data.aws_eks_cluster.cluster.identity[0].oidc[0].issuer
}

#
# 1. Kubernetes Namespaces
# Separate namespaces for monitoring and logging components
#

resource "kubernetes_namespace" "monitoring" {
  metadata {
    name = "monitoring"
    labels = {
      name        = "monitoring"
      environment = var.environment
      project     = "carddemo"
      managed_by  = "terraform"
    }
  }
}

resource "kubernetes_namespace" "logging" {
  count = var.enable_elk_stack ? 1 : 0

  metadata {
    name = "logging"
    labels = {
      name        = "logging"
      environment = var.environment
      project     = "carddemo"
      managed_by  = "terraform"
    }
  }
}

#
# 2. IAM Roles for Service Accounts (IRSA)
# Enables Kubernetes pods to authenticate with AWS services
#

# IAM Role for Prometheus Service Account
resource "aws_iam_role" "prometheus" {
  name = "${var.eks_cluster_name}-prometheus-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Federated = data.aws_iam_openid_connect_provider.eks_oidc.arn
        }
        Action = "sts:AssumeRoleWithWebIdentity"
        Condition = {
          StringEquals = {
            "${replace(data.aws_eks_cluster.cluster.identity[0].oidc[0].issuer, "https://", "")}:sub" = "system:serviceaccount:monitoring:prometheus"
            "${replace(data.aws_eks_cluster.cluster.identity[0].oidc[0].issuer, "https://", "")}:aud" = "sts.amazonaws.com"
          }
        }
      }
    ]
  })

  tags = merge(
    var.tags,
    {
      Name        = "${var.eks_cluster_name}-prometheus-role"
      Environment = var.environment
      Project     = "CardDemo"
      ManagedBy   = "Terraform"
      Purpose     = "Prometheus CloudWatch integration"
    }
  )
}

# IAM Policy for Prometheus - CloudWatch read permissions
resource "aws_iam_policy" "prometheus_cloudwatch" {
  name        = "${var.eks_cluster_name}-prometheus-cloudwatch-policy"
  description = "Policy for Prometheus to read CloudWatch metrics"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "cloudwatch:GetMetricData",
          "cloudwatch:GetMetricStatistics",
          "cloudwatch:ListMetrics",
          "ec2:DescribeInstances",
          "ec2:DescribeTags",
          "tag:GetResources"
        ]
        Resource = "*"
      }
    ]
  })

  tags = merge(
    var.tags,
    {
      Name        = "${var.eks_cluster_name}-prometheus-cloudwatch-policy"
      Environment = var.environment
      Project     = "CardDemo"
      ManagedBy   = "Terraform"
    }
  )
}

# Attach CloudWatch policy to Prometheus role
resource "aws_iam_role_policy_attachment" "prometheus_cloudwatch" {
  role       = aws_iam_role.prometheus.name
  policy_arn = aws_iam_policy.prometheus_cloudwatch.arn
}

# IAM Role for Grafana Service Account
resource "aws_iam_role" "grafana" {
  name = "${var.eks_cluster_name}-grafana-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Federated = data.aws_iam_openid_connect_provider.eks_oidc.arn
        }
        Action = "sts:AssumeRoleWithWebIdentity"
        Condition = {
          StringEquals = {
            "${replace(data.aws_eks_cluster.cluster.identity[0].oidc[0].issuer, "https://", "")}:sub" = "system:serviceaccount:monitoring:grafana"
            "${replace(data.aws_eks_cluster.cluster.identity[0].oidc[0].issuer, "https://", "")}:aud" = "sts.amazonaws.com"
          }
        }
      }
    ]
  })

  tags = merge(
    var.tags,
    {
      Name        = "${var.eks_cluster_name}-grafana-role"
      Environment = var.environment
      Project     = "CardDemo"
      ManagedBy   = "Terraform"
      Purpose     = "Grafana CloudWatch datasource integration"
    }
  )
}

# IAM Policy for Grafana - CloudWatch read and dashboard permissions
resource "aws_iam_policy" "grafana_cloudwatch" {
  name        = "${var.eks_cluster_name}-grafana-cloudwatch-policy"
  description = "Policy for Grafana to read CloudWatch metrics and logs"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "cloudwatch:DescribeAlarmsForMetric",
          "cloudwatch:DescribeAlarmHistory",
          "cloudwatch:DescribeAlarms",
          "cloudwatch:ListMetrics",
          "cloudwatch:GetMetricData",
          "cloudwatch:GetMetricStatistics",
          "cloudwatch:GetInsightRuleReport",
          "logs:DescribeLogGroups",
          "logs:GetLogGroupFields",
          "logs:StartQuery",
          "logs:StopQuery",
          "logs:GetQueryResults",
          "logs:GetLogEvents",
          "logs:FilterLogEvents",
          "ec2:DescribeTags",
          "ec2:DescribeInstances",
          "ec2:DescribeRegions",
          "tag:GetResources"
        ]
        Resource = "*"
      }
    ]
  })

  tags = merge(
    var.tags,
    {
      Name        = "${var.eks_cluster_name}-grafana-cloudwatch-policy"
      Environment = var.environment
      Project     = "CardDemo"
      ManagedBy   = "Terraform"
    }
  )
}

# Attach CloudWatch policy to Grafana role
resource "aws_iam_role_policy_attachment" "grafana_cloudwatch" {
  role       = aws_iam_role.grafana.name
  policy_arn = aws_iam_policy.grafana_cloudwatch.arn
}

# IAM Role for Logstash Service Account
resource "aws_iam_role" "logstash" {
  count = var.enable_elk_stack ? 1 : 0
  name  = "${var.eks_cluster_name}-logstash-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Federated = data.aws_iam_openid_connect_provider.eks_oidc.arn
        }
        Action = "sts:AssumeRoleWithWebIdentity"
        Condition = {
          StringEquals = {
            "${replace(data.aws_eks_cluster.cluster.identity[0].oidc[0].issuer, "https://", "")}:sub" = "system:serviceaccount:logging:logstash"
            "${replace(data.aws_eks_cluster.cluster.identity[0].oidc[0].issuer, "https://", "")}:aud" = "sts.amazonaws.com"
          }
        }
      }
    ]
  })

  tags = merge(
    var.tags,
    {
      Name        = "${var.eks_cluster_name}-logstash-role"
      Environment = var.environment
      Project     = "CardDemo"
      ManagedBy   = "Terraform"
      Purpose     = "Logstash CloudWatch Logs integration"
    }
  )
}

# IAM Policy for Logstash - CloudWatch Logs write permissions
resource "aws_iam_policy" "logstash_cloudwatch" {
  count       = var.enable_elk_stack ? 1 : 0
  name        = "${var.eks_cluster_name}-logstash-cloudwatch-policy"
  description = "Policy for Logstash to write to CloudWatch Logs"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents",
          "logs:DescribeLogGroups",
          "logs:DescribeLogStreams"
        ]
        Resource = "arn:aws:logs:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:log-group:/aws/eks/carddemo/*"
      }
    ]
  })

  tags = merge(
    var.tags,
    {
      Name        = "${var.eks_cluster_name}-logstash-cloudwatch-policy"
      Environment = var.environment
      Project     = "CardDemo"
      ManagedBy   = "Terraform"
    }
  )
}

# Attach CloudWatch policy to Logstash role
resource "aws_iam_role_policy_attachment" "logstash_cloudwatch" {
  count      = var.enable_elk_stack ? 1 : 0
  role       = aws_iam_role.logstash[0].name
  policy_arn = aws_iam_policy.logstash_cloudwatch[0].arn
}

#
# 3. Prometheus Stack Deployment (Helm Chart)
# Replaces mainframe transaction monitoring with cloud-native metrics collection
#

resource "helm_release" "prometheus" {
  count      = var.enable_prometheus ? 1 : 0
  name       = "prometheus"
  repository = "https://prometheus-community.github.io/helm-charts"
  chart      = "kube-prometheus-stack"
  version    = "56.0.0" # Compatible with Kubernetes 1.31
  namespace  = kubernetes_namespace.monitoring.metadata[0].name

  values = [
    yamlencode({
      # Prometheus server configuration
      prometheus = {
        prometheusSpec = {
          # Metrics retention replacing mainframe transaction history
          retention = "${var.prometheus_retention_days}d"
          
          # Persistent storage for metrics
          storageSpec = {
            volumeClaimTemplate = {
              spec = {
                accessModes = ["ReadWriteOnce"]
                resources = {
                  requests = {
                    storage = var.prometheus_storage_size
                  }
                }
                storageClassName = "gp3"
              }
            }
          }

          # Resource limits for production workload
          resources = {
            requests = {
              cpu    = "500m"
              memory = "2Gi"
            }
            limits = {
              cpu    = "2000m"
              memory = "4Gi"
            }
          }

          # Service monitor selector for automatic target discovery
          serviceMonitorSelectorNilUsesHelmValues = false
          podMonitorSelectorNilUsesHelmValues     = false

          # Additional scrape configs for Spring Boot actuator endpoints
          additionalScrapeConfigs = [
            {
              job_name = "spring-boot-actuator"
              kubernetes_sd_configs = [
                {
                  role = "pod"
                  namespaces = {
                    names = ["default", "carddemo"]
                  }
                }
              ]
              relabel_configs = [
                {
                  source_labels = ["__meta_kubernetes_pod_annotation_prometheus_io_scrape"]
                  action        = "keep"
                  regex         = "true"
                },
                {
                  source_labels = ["__meta_kubernetes_pod_annotation_prometheus_io_path"]
                  action        = "replace"
                  target_label  = "__metrics_path__"
                  regex         = "(.+)"
                },
                {
                  source_labels = ["__address__", "__meta_kubernetes_pod_annotation_prometheus_io_port"]
                  action        = "replace"
                  regex         = "([^:]+)(?::\\d+)?;(\\d+)"
                  replacement   = "$1:$2"
                  target_label  = "__address__"
                }
              ]
            }
          ]
        }

        # Service account with IRSA annotation
        serviceAccount = {
          create = true
          name   = "prometheus"
          annotations = {
            "eks.amazonaws.com/role-arn" = aws_iam_role.prometheus.arn
          }
        }
      }

      # Alert Manager configuration for SLA violations
      alertmanager = {
        alertmanagerSpec = {
          storage = {
            volumeClaimTemplate = {
              spec = {
                accessModes = ["ReadWriteOnce"]
                resources = {
                  requests = {
                    storage = "10Gi"
                  }
                }
                storageClassName = "gp3"
              }
            }
          }
          resources = {
            requests = {
              cpu    = "100m"
              memory = "128Mi"
            }
            limits = {
              cpu    = "200m"
              memory = "256Mi"
            }
          }
        }
      }

      # Node exporter for host-level metrics
      nodeExporter = {
        enabled = true
      }

      # Kube-state-metrics for Kubernetes object metrics
      kubeStateMetrics = {
        enabled = true
      }

      # Grafana disabled here (deployed separately for better control)
      grafana = {
        enabled = false
      }
    })
  ]

  depends_on = [
    kubernetes_namespace.monitoring
  ]
}

#
# 4. Grafana Deployment (Helm Chart)
# Replaces mainframe performance monitoring with interactive dashboards
#

resource "helm_release" "grafana" {
  count      = var.enable_grafana ? 1 : 0
  name       = "grafana"
  repository = "https://grafana.github.io/helm-charts"
  chart      = "grafana"
  version    = "7.3.0"
  namespace  = kubernetes_namespace.monitoring.metadata[0].name

  values = [
    yamlencode({
      # Admin credentials
      adminPassword = var.grafana_admin_password

      # Persistent storage for dashboards
      persistence = {
        enabled          = true
        storageClassName = "gp3"
        size             = var.grafana_storage_size
        accessModes      = ["ReadWriteOnce"]
      }

      # Service configuration - LoadBalancer for external access
      service = {
        type = "LoadBalancer"
        port = 80
        annotations = {
          "service.beta.kubernetes.io/aws-load-balancer-type" = "nlb"
        }
      }

      # Service account with IRSA annotation
      serviceAccount = {
        create = true
        name   = "grafana"
        annotations = {
          "eks.amazonaws.com/role-arn" = aws_iam_role.grafana.arn
        }
      }

      # Resource limits
      resources = {
        requests = {
          cpu    = "250m"
          memory = "512Mi"
        }
        limits = {
          cpu    = "1000m"
          memory = "1Gi"
        }
      }

      # Datasources configuration
      datasources = {
        "datasources.yaml" = {
          apiVersion = 1
          datasources = [
            {
              name      = "Prometheus"
              type      = "prometheus"
              access    = "proxy"
              url       = var.enable_prometheus ? "http://prometheus-kube-prometheus-prometheus.monitoring.svc.cluster.local:9090" : ""
              isDefault = true
            },
            {
              name   = "CloudWatch"
              type   = "cloudwatch"
              access = "proxy"
              jsonData = {
                authType      = "default" # Uses IRSA
                defaultRegion = data.aws_region.current.name
              }
            }
          ]
        }
      }

      # Dashboard providers configuration
      dashboardProviders = {
        "dashboardproviders.yaml" = {
          apiVersion = 1
          providers = [
            {
              name            = "default"
              orgId           = 1
              folder          = ""
              type            = "file"
              disableDeletion = false
              editable        = true
              options = {
                path = "/var/lib/grafana/dashboards/default"
              }
            }
          ]
        }
      }

      # Pre-configured dashboards for CardDemo monitoring
      dashboards = {
        default = {
          # Transaction monitoring dashboard - replaces mainframe transaction tracking
          carddemo-transactions = {
            json = jsonencode({
              title = "CardDemo - Transaction Monitoring"
              panels = [
                {
                  title = "Transaction Response Time (P95) - SLA: <200ms"
                  type  = "graph"
                  targets = [
                    {
                      expr = "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application=\"carddemo-backend\"}[5m])) by (le))"
                    }
                  ]
                },
                {
                  title = "Transaction Throughput (TPS)"
                  type  = "graph"
                  targets = [
                    {
                      expr = "sum(rate(http_server_requests_seconds_count{application=\"carddemo-backend\"}[5m]))"
                    }
                  ]
                },
                {
                  title = "Error Rate"
                  type  = "graph"
                  targets = [
                    {
                      expr = "sum(rate(http_server_requests_seconds_count{application=\"carddemo-backend\",status=~\"5..\"}[5m])) / sum(rate(http_server_requests_seconds_count{application=\"carddemo-backend\"}[5m])) * 100"
                    }
                  ]
                }
              ]
            })
          }

          # Batch job monitoring dashboard - replaces JCL job timing
          carddemo-batch = {
            json = jsonencode({
              title = "CardDemo - Batch Job Monitoring"
              panels = [
                {
                  title = "Batch Job Duration - SLA: <4 hours"
                  type  = "graph"
                  targets = [
                    {
                      expr = "spring_batch_job_duration_seconds{application=\"carddemo-backend\"}"
                    }
                  ]
                },
                {
                  title = "Batch Job Success Rate"
                  type  = "stat"
                  targets = [
                    {
                      expr = "sum(spring_batch_job_status{application=\"carddemo-backend\",status=\"COMPLETED\"}) / sum(spring_batch_job_status{application=\"carddemo-backend\"}) * 100"
                    }
                  ]
                }
              ]
            })
          }

          # JVM monitoring dashboard
          carddemo-jvm = {
            json = jsonencode({
              title = "CardDemo - JVM Metrics"
              panels = [
                {
                  title = "Heap Memory Usage"
                  type  = "graph"
                  targets = [
                    {
                      expr = "jvm_memory_used_bytes{application=\"carddemo-backend\",area=\"heap\"}"
                    }
                  ]
                },
                {
                  title = "GC Pause Time"
                  type  = "graph"
                  targets = [
                    {
                      expr = "rate(jvm_gc_pause_seconds_sum{application=\"carddemo-backend\"}[5m])"
                    }
                  ]
                }
              ]
            })
          }
        }
      }
    })
  ]

  depends_on = [
    kubernetes_namespace.monitoring,
    helm_release.prometheus
  ]
}

#
# 5. CloudWatch Log Groups
# Replaces mainframe JCL SYSOUT logs with cloud-native log aggregation
#

resource "aws_cloudwatch_log_group" "backend" {
  count             = var.enable_cloudwatch ? 1 : 0
  name              = "/aws/eks/carddemo/backend"
  retention_in_days = var.cloudwatch_log_retention_days

  tags = merge(
    var.tags,
    {
      Name        = "carddemo-backend-logs"
      Environment = var.environment
      Project     = "CardDemo"
      ManagedBy   = "Terraform"
      Purpose     = "Backend application logs - replaces mainframe CICS transaction logs"
    }
  )
}

resource "aws_cloudwatch_log_group" "batch" {
  count             = var.enable_cloudwatch ? 1 : 0
  name              = "/aws/eks/carddemo/batch"
  retention_in_days = var.cloudwatch_log_retention_days

  tags = merge(
    var.tags,
    {
      Name        = "carddemo-batch-logs"
      Environment = var.environment
      Project     = "CardDemo"
      ManagedBy   = "Terraform"
      Purpose     = "Spring Batch job logs - replaces mainframe JCL SYSOUT"
    }
  )
}

resource "aws_cloudwatch_log_group" "cluster" {
  count             = var.enable_cloudwatch ? 1 : 0
  name              = "/aws/eks/carddemo/cluster"
  retention_in_days = var.cloudwatch_log_retention_days

  tags = merge(
    var.tags,
    {
      Name        = "carddemo-cluster-logs"
      Environment = var.environment
      Project     = "CardDemo"
      ManagedBy   = "Terraform"
      Purpose     = "EKS cluster logs"
    }
  )
}

#
# 6. CloudWatch Metric Filters and Alarms
# Replaces mainframe SLA tracking with automated alerting
#

# Metric filter for ERROR level logs
resource "aws_cloudwatch_log_metric_filter" "error_logs" {
  count          = var.enable_cloudwatch ? 1 : 0
  name           = "carddemo-error-count"
  log_group_name = aws_cloudwatch_log_group.backend[0].name
  pattern        = "[time, request_id, level = ERROR*, ...]"

  metric_transformation {
    name      = "ErrorCount"
    namespace = var.cloudwatch_metrics_namespace
    value     = "1"
    unit      = "Count"
  }
}

# Metric filter for transaction response times
resource "aws_cloudwatch_log_metric_filter" "slow_transactions" {
  count          = var.enable_cloudwatch ? 1 : 0
  name           = "carddemo-slow-transactions"
  log_group_name = aws_cloudwatch_log_group.backend[0].name
  pattern        = "[time, request_id, level, class, method, duration > 200, ...]"

  metric_transformation {
    name      = "SlowTransactionCount"
    namespace = var.cloudwatch_metrics_namespace
    value     = "1"
    unit      = "Count"
  }
}

# Alarm for high error rate - critical for transaction processing
resource "aws_cloudwatch_metric_alarm" "high_error_rate" {
  count               = var.enable_cloudwatch ? 1 : 0
  alarm_name          = "carddemo-high-error-rate-${var.environment}"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "ErrorCount"
  namespace           = var.cloudwatch_metrics_namespace
  period              = 300 # 5 minutes
  statistic           = "Sum"
  threshold           = 10
  alarm_description   = "Alert when ERROR log count exceeds 10 in 5 minutes - indicates backend processing issues"
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.critical_alerts.arn]
  ok_actions    = [aws_sns_topic.critical_alerts.arn]

  tags = merge(
    var.tags,
    {
      Name        = "carddemo-high-error-rate"
      Environment = var.environment
      Project     = "CardDemo"
      Severity    = "Critical"
    }
  )
}

# Alarm for slow transactions - SLA monitoring (sub-200ms requirement)
resource "aws_cloudwatch_metric_alarm" "slow_transactions" {
  count               = var.enable_cloudwatch ? 1 : 0
  alarm_name          = "carddemo-slow-transactions-${var.environment}"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "SlowTransactionCount"
  namespace           = var.cloudwatch_metrics_namespace
  period              = 300 # 5 minutes
  statistic           = "Sum"
  threshold           = 5
  alarm_description   = "Alert when transaction response time exceeds 200ms SLA - replaces mainframe response time monitoring"
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.warning_alerts.arn]
  ok_actions    = [aws_sns_topic.warning_alerts.arn]

  tags = merge(
    var.tags,
    {
      Name        = "carddemo-slow-transactions"
      Environment = var.environment
      Project     = "CardDemo"
      Severity    = "Warning"
      Purpose     = "SLA monitoring - sub-200ms transaction response time"
    }
  )
}

# Alarm for EKS node CPU utilization
resource "aws_cloudwatch_metric_alarm" "node_cpu_high" {
  count               = var.enable_cloudwatch ? 1 : 0
  alarm_name          = "carddemo-node-cpu-high-${var.environment}"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "node_cpu_utilization"
  namespace           = "ContainerInsights"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "Alert when EKS node CPU exceeds 80% for 5 minutes"
  treat_missing_data  = "notBreaching"

  dimensions = {
    ClusterName = var.eks_cluster_name
  }

  alarm_actions = [aws_sns_topic.warning_alerts.arn]

  tags = merge(
    var.tags,
    {
      Name        = "carddemo-node-cpu-high"
      Environment = var.environment
      Project     = "CardDemo"
      Severity    = "Warning"
    }
  )
}

# Alarm for pod restart count
resource "aws_cloudwatch_metric_alarm" "pod_restarts" {
  count               = var.enable_cloudwatch ? 1 : 0
  alarm_name          = "carddemo-pod-restarts-${var.environment}"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "pod_number_of_container_restarts"
  namespace           = "ContainerInsights"
  period              = 300
  statistic           = "Sum"
  threshold           = 3
  alarm_description   = "Alert when pod restarts exceed 3 in 5 minutes - indicates application instability"
  treat_missing_data  = "notBreaching"

  dimensions = {
    ClusterName = var.eks_cluster_name
    Namespace   = "carddemo"
  }

  alarm_actions = [aws_sns_topic.critical_alerts.arn]

  tags = merge(
    var.tags,
    {
      Name        = "carddemo-pod-restarts"
      Environment = var.environment
      Project     = "CardDemo"
      Severity    = "Critical"
    }
  )
}

#
# 7. AWS Elasticsearch Domain (ELK Stack)
# Replaces mainframe log aggregation with searchable log repository
#

# Security group for Elasticsearch domain
resource "aws_security_group" "elasticsearch" {
  count       = var.enable_elk_stack ? 1 : 0
  name        = "${var.eks_cluster_name}-elasticsearch-sg"
  description = "Security group for Elasticsearch domain"
  vpc_id      = var.vpc_id

  ingress {
    description = "HTTPS from EKS worker nodes"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.selected.cidr_block]
  }

  ingress {
    description = "Elasticsearch API from monitoring namespace"
    from_port   = 9200
    to_port     = 9200
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.selected.cidr_block]
  }

  egress {
    description = "All outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(
    var.tags,
    {
      Name        = "${var.eks_cluster_name}-elasticsearch-sg"
      Environment = var.environment
      Project     = "CardDemo"
      ManagedBy   = "Terraform"
    }
  )
}

# VPC data source for CIDR block
data "aws_vpc" "selected" {
  count = var.enable_elk_stack ? 1 : 0
  id    = var.vpc_id
}

# Elasticsearch domain configuration
resource "aws_elasticsearch_domain" "carddemo" {
  count                 = var.enable_elk_stack ? 1 : 0
  domain_name           = "${var.eks_cluster_name}-carddemo-logs"
  elasticsearch_version = var.elasticsearch_version

  # Cluster configuration for high availability
  cluster_config {
    instance_type            = var.elasticsearch_instance_type
    instance_count           = var.elasticsearch_instance_count
    dedicated_master_enabled = var.elasticsearch_instance_count >= 3 ? true : false
    dedicated_master_type    = var.elasticsearch_instance_count >= 3 ? "t3.small.elasticsearch" : null
    dedicated_master_count   = var.elasticsearch_instance_count >= 3 ? 3 : null
    zone_awareness_enabled   = var.elasticsearch_instance_count >= 2 ? true : false

    dynamic "zone_awareness_config" {
      for_each = var.elasticsearch_instance_count >= 2 ? [1] : []
      content {
        availability_zone_count = min(var.elasticsearch_instance_count, 3)
      }
    }
  }

  # EBS volume configuration for persistent storage
  ebs_options {
    ebs_enabled = true
    volume_type = "gp3"
    volume_size = var.elasticsearch_volume_size
  }

  # VPC configuration
  vpc_options {
    subnet_ids         = var.elasticsearch_instance_count == 1 ? [var.private_subnet_ids[0]] : slice(var.private_subnet_ids, 0, min(length(var.private_subnet_ids), 3))
    security_group_ids = [aws_security_group.elasticsearch[0].id]
  }

  # Encryption at rest
  encrypt_at_rest {
    enabled = true
  }

  # Node-to-node encryption
  node_to_node_encryption {
    enabled = true
  }

  # Domain endpoint options
  domain_endpoint_options {
    enforce_https       = true
    tls_security_policy = "Policy-Min-TLS-1-2-2019-07"
  }

  # Advanced security options
  advanced_security_options {
    enabled                        = true
    internal_user_database_enabled = true
    master_user_options {
      master_user_name     = "admin"
      master_user_password = var.grafana_admin_password # Reuse same password for simplicity
    }
  }

  # Access policy
  access_policies = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          AWS = "*"
        }
        Action   = "es:*"
        Resource = "arn:aws:es:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:domain/${var.eks_cluster_name}-carddemo-logs/*"
        Condition = {
          IpAddress = {
            "aws:SourceIp" = [data.aws_vpc.selected[0].cidr_block]
          }
        }
      }
    ]
  })

  tags = merge(
    var.tags,
    {
      Name        = "${var.eks_cluster_name}-carddemo-elasticsearch"
      Environment = var.environment
      Project     = "CardDemo"
      ManagedBy   = "Terraform"
      Purpose     = "Centralized log aggregation - replaces mainframe log files"
    }
  )

  depends_on = [
    aws_security_group.elasticsearch
  ]
}

#
# 8. Logstash ConfigMap and Deployment
# Replaces mainframe log shipping with cloud-native log pipeline
#

# Logstash pipeline configuration
resource "kubernetes_config_map" "logstash_pipeline" {
  count = var.enable_elk_stack ? 1 : 0

  metadata {
    name      = "logstash-pipeline"
    namespace = kubernetes_namespace.logging[0].metadata[0].name
  }

  data = {
    "logstash.conf" = <<-EOT
      input {
        # Read logs from Kubernetes pods
        file {
          path => "/var/log/containers/*.log"
          start_position => "beginning"
          sincedb_path => "/tmp/sincedb"
          codec => json
        }
      }

      filter {
        # Parse JSON logs from Spring Boot applications
        if [kubernetes][labels][app] == "carddemo-backend" {
          json {
            source => "message"
            target => "app"
          }

          # Extract fields from structured logs
          mutate {
            add_field => {
              "log_level" => "%{[app][level]}"
              "logger_name" => "%{[app][logger]}"
              "thread_name" => "%{[app][thread]}"
              "trace_id" => "%{[app][traceId]}"
              "span_id" => "%{[app][spanId]}"
            }
          }

          # Parse timestamp
          date {
            match => [ "[app][timestamp]", "ISO8601" ]
            target => "@timestamp"
          }

          # Add custom fields for CardDemo monitoring
          mutate {
            add_field => {
              "application" => "carddemo"
              "environment" => "${var.environment}"
              "cluster" => "${var.eks_cluster_name}"
            }
          }

          # Extract transaction metrics from logs
          if [app][message] =~ /Transaction completed/ {
            grok {
              match => { "[app][message]" => "Transaction completed in %{NUMBER:transaction_duration:float}ms" }
            }
          }

          # Tag slow transactions (>200ms SLA)
          if [transaction_duration] and [transaction_duration] > 200 {
            mutate {
              add_tag => ["slow_transaction", "sla_violation"]
            }
          }

          # Tag error logs
          if [log_level] == "ERROR" {
            mutate {
              add_tag => ["error", "needs_attention"]
            }
          }
        }

        # Parse Spring Batch job logs
        if [kubernetes][labels][app] == "carddemo-batch" {
          json {
            source => "message"
            target => "batch"
          }

          mutate {
            add_field => {
              "job_name" => "%{[batch][jobName]}"
              "job_status" => "%{[batch][status]}"
              "job_duration" => "%{[batch][duration]}"
            }
          }

          # Tag long-running batch jobs (>4 hour SLA)
          if [job_duration] and [job_duration] > 14400 {
            mutate {
              add_tag => ["batch_sla_violation", "exceeds_4_hour_window"]
            }
          }
        }
      }

      output {
        # Send to Elasticsearch for indexing and search
        elasticsearch {
          hosts => ["${var.enable_elk_stack ? aws_elasticsearch_domain.carddemo[0].endpoint : ""}"]
          index => "carddemo-logs-%{+YYYY.MM.dd}"
          user => "admin"
          password => "${var.grafana_admin_password}"
          ssl => true
          ssl_certificate_verification => true
        }

        # Also send to CloudWatch Logs for AWS-native monitoring
        cloudwatch_logs {
          log_group_name => "/aws/eks/carddemo/logstash"
          log_stream_name => "%{[kubernetes][pod_name]}-%{[kubernetes][container_name]}"
          region => "${data.aws_region.current.name}"
        }

        # Debug output (disabled in production)
        # stdout { codec => rubydebug }
      }
    EOT
  }
}

# Logstash service account
resource "kubernetes_service_account" "logstash" {
  count = var.enable_elk_stack ? 1 : 0

  metadata {
    name      = "logstash"
    namespace = kubernetes_namespace.logging[0].metadata[0].name
    annotations = {
      "eks.amazonaws.com/role-arn" = aws_iam_role.logstash[0].arn
    }
  }
}

# Logstash deployment
resource "kubernetes_deployment" "logstash" {
  count = var.enable_elk_stack ? 1 : 0

  metadata {
    name      = "logstash"
    namespace = kubernetes_namespace.logging[0].metadata[0].name
    labels = {
      app = "logstash"
    }
  }

  spec {
    replicas = 2 # High availability

    selector {
      match_labels = {
        app = "logstash"
      }
    }

    template {
      metadata {
        labels = {
          app = "logstash"
        }
      }

      spec {
        service_account_name = kubernetes_service_account.logstash[0].metadata[0].name

        container {
          name  = "logstash"
          image = "docker.elastic.co/logstash/logstash:7.17.0"

          port {
            name           = "http"
            container_port = 9600
            protocol       = "TCP"
          }

          resources {
            requests = {
              cpu    = "500m"
              memory = "1Gi"
            }
            limits = {
              cpu    = "1000m"
              memory = "2Gi"
            }
          }

          env {
            name  = "ELASTICSEARCH_ENDPOINT"
            value = var.enable_elk_stack ? "https://${aws_elasticsearch_domain.carddemo[0].endpoint}" : ""
          }

          env {
            name  = "ELASTICSEARCH_USER"
            value = "admin"
          }

          env {
            name  = "ELASTICSEARCH_PASSWORD"
            value = var.grafana_admin_password
          }

          env {
            name  = "AWS_REGION"
            value = data.aws_region.current.name
          }

          volume_mount {
            name       = "pipeline-config"
            mount_path = "/usr/share/logstash/pipeline"
          }

          volume_mount {
            name       = "varlog"
            mount_path = "/var/log"
            read_only  = true
          }

          liveness_probe {
            http_get {
              path = "/"
              port = 9600
            }
            initial_delay_seconds = 60
            period_seconds        = 10
            timeout_seconds       = 5
            failure_threshold     = 3
          }

          readiness_probe {
            http_get {
              path = "/"
              port = 9600
            }
            initial_delay_seconds = 30
            period_seconds        = 5
            timeout_seconds       = 3
            failure_threshold     = 3
          }
        }

        volume {
          name = "pipeline-config"
          config_map {
            name = kubernetes_config_map.logstash_pipeline[0].metadata[0].name
          }
        }

        volume {
          name = "varlog"
          host_path {
            path = "/var/log"
          }
        }
      }
    }
  }

  depends_on = [
    aws_elasticsearch_domain.carddemo,
    kubernetes_config_map.logstash_pipeline,
    kubernetes_service_account.logstash
  ]
}

#
# 9. SNS Topics for Alerting
# Replaces mainframe operator notifications with automated alerts
#

# SNS topic for critical alerts
resource "aws_sns_topic" "critical_alerts" {
  name              = "${var.eks_cluster_name}-carddemo-critical-alerts"
  display_name      = "CardDemo Critical Alerts"
  kms_master_key_id = "alias/aws/sns" # Use AWS managed key

  tags = merge(
    var.tags,
    {
      Name        = "${var.eks_cluster_name}-critical-alerts"
      Environment = var.environment
      Project     = "CardDemo"
      ManagedBy   = "Terraform"
      Severity    = "Critical"
      Purpose     = "Critical alerts requiring immediate attention"
    }
  )
}

# SNS topic for warning alerts
resource "aws_sns_topic" "warning_alerts" {
  name              = "${var.eks_cluster_name}-carddemo-warning-alerts"
  display_name      = "CardDemo Warning Alerts"
  kms_master_key_id = "alias/aws/sns"

  tags = merge(
    var.tags,
    {
      Name        = "${var.eks_cluster_name}-warning-alerts"
      Environment = var.environment
      Project     = "CardDemo"
      ManagedBy   = "Terraform"
      Severity    = "Warning"
      Purpose     = "Warning alerts for monitoring and investigation"
    }
  )
}

# Email subscriptions for critical alerts
resource "aws_sns_topic_subscription" "critical_email" {
  count     = length(var.alert_email_endpoints)
  topic_arn = aws_sns_topic.critical_alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email_endpoints[count.index]
}

# Email subscriptions for warning alerts
resource "aws_sns_topic_subscription" "warning_email" {
  count     = length(var.alert_email_endpoints)
  topic_arn = aws_sns_topic.warning_alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email_endpoints[count.index]
}

# Slack webhook subscription for critical alerts (optional)
resource "aws_sns_topic_subscription" "critical_slack" {
  count     = var.alert_slack_webhook != "" ? 1 : 0
  topic_arn = aws_sns_topic.critical_alerts.arn
  protocol  = "https"
  endpoint  = var.alert_slack_webhook
}

# SNS topic policy allowing CloudWatch alarms to publish
resource "aws_sns_topic_policy" "critical_alerts" {
  arn = aws_sns_topic.critical_alerts.arn

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "cloudwatch.amazonaws.com"
        }
        Action   = "SNS:Publish"
        Resource = aws_sns_topic.critical_alerts.arn
        Condition = {
          StringEquals = {
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
        }
      }
    ]
  })
}

resource "aws_sns_topic_policy" "warning_alerts" {
  arn = aws_sns_topic.warning_alerts.arn

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "cloudwatch.amazonaws.com"
        }
        Action   = "SNS:Publish"
        Resource = aws_sns_topic.warning_alerts.arn
        Condition = {
          StringEquals = {
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
        }
      }
    ]
  })
}

#
# 10. Security Group for Monitoring Components
# Network isolation and access control for observability infrastructure
#

resource "aws_security_group" "monitoring" {
  name        = "${var.eks_cluster_name}-monitoring-sg"
  description = "Security group for monitoring components (Prometheus, Grafana)"
  vpc_id      = var.vpc_id

  ingress {
    description = "Prometheus metrics from EKS cluster"
    from_port   = 9090
    to_port     = 9090
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.selected[0].cidr_block]
  }

  ingress {
    description = "Grafana dashboard access"
    from_port   = 3000
    to_port     = 3000
    protocol    = "tcp"
    cidr_blocks = [data.aws_vpc.selected[0].cidr_block]
  }

  egress {
    description = "All outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(
    var.tags,
    {
      Name        = "${var.eks_cluster_name}-monitoring-sg"
      Environment = var.environment
      Project     = "CardDemo"
      ManagedBy   = "Terraform"
    }
  )
}

# Data source for VPC CIDR (for non-ELK deployments)
data "aws_vpc" "monitoring" {
  count = var.enable_elk_stack ? 0 : 1
  id    = var.vpc_id
}

