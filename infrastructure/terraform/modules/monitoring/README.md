# Monitoring Module

## Overview

This Terraform module provides comprehensive cloud-native observability for the CardDemo application, replacing traditional mainframe monitoring tools with modern monitoring and logging solutions. The module integrates Prometheus, Grafana, CloudWatch, and the ELK stack to deliver complete visibility into application performance, batch job execution, and infrastructure health.

### Purpose

- **Replace Mainframe Monitoring**: Modernize monitoring from JCL SYSOUT logs and mainframe transaction monitors to cloud-native observability tools
- **Real-Time Metrics**: Collect and visualize application metrics using Prometheus and Grafana
- **Centralized Logging**: Aggregate logs from Spring Boot applications and Kubernetes using CloudWatch and Elasticsearch
- **Proactive Alerting**: Monitor SLAs and trigger alerts via CloudWatch Alarms and SNS notifications
- **Performance Tracking**: Ensure sub-200ms transaction response times and 4-hour batch processing windows

### Components

- **Prometheus**: Metrics collection from Spring Boot /actuator/prometheus endpoints
- **Grafana**: Interactive dashboards for transaction monitoring, JVM metrics, and batch job tracking
- **CloudWatch**: AWS-native monitoring for logs, metrics, and alarms
- **ELK Stack**: Elasticsearch, Logstash, and Kibana for centralized log aggregation and search
- **SNS Topics**: Alert notification delivery to email and Slack

### Migration from Mainframe

| Mainframe Component | Cloud-Native Replacement |
|---------------------|--------------------------|
| JCL SYSOUT logs | CloudWatch Logs + Elasticsearch |
| CICS transaction monitoring | Prometheus metrics + Grafana dashboards |
| Batch job timing (JCL) | Spring Batch metrics + CloudWatch alarms |
| Mainframe SLA tracking | CloudWatch alarms with SNS notifications |
| VSAM I/O statistics | PostgreSQL query metrics via Prometheus |

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    EKS Cluster                          │
│  ┌──────────────────────────────────────────────────┐  │
│  │  Application Pods (Spring Boot)                   │  │
│  │  - Expose /actuator/prometheus metrics            │  │
│  │  - Send logs to stdout/stderr                     │  │
│  └──────────────────────────────────────────────────┘  │
│         │                           │                    │
│         │ metrics                   │ logs               │
│         ↓                           ↓                    │
│  ┌─────────────┐            ┌─────────────┐            │
│  │ Prometheus  │            │  Logstash   │            │
│  │  (scrape)   │            │ (shipping)  │            │
│  └─────────────┘            └─────────────┘            │
│         │                           │                    │
└─────────│───────────────────────────│────────────────────┘
          │                           │
          │                           ↓
          │                   ┌──────────────────┐
          │                   │  Elasticsearch   │
          │                   │   (indexing)     │
          │                   └──────────────────┘
          │                           │
          ↓                           ↓
   ┌─────────────┐            ┌─────────────┐
   │   Grafana   │            │   Kibana    │
   │ (visualize) │            │  (search)   │
   └─────────────┘            └─────────────┘
          │
          │ queries
          ↓
   ┌─────────────┐
   │ CloudWatch  │
   │  Metrics &  │
   │    Logs     │
   └─────────────┘
          │
          ↓
   ┌─────────────┐
   │  SNS Topic  │
   │  (alerts)   │
   └─────────────┘
```

## Features

### Metrics Collection
- **Prometheus Metrics**: Scrapes Spring Boot /actuator/prometheus endpoints every 15 seconds
- **Custom Metrics**: Transaction counts, response times, error rates, batch job durations
- **JVM Metrics**: Heap memory, garbage collection, thread counts, CPU utilization
- **Database Metrics**: PostgreSQL connection pool, query performance, transaction rates

### Log Aggregation
- **CloudWatch Logs**: Backend application logs, Spring Batch logs, EKS cluster logs
- **Elasticsearch**: Centralized log storage with full-text search capabilities
- **Logstash**: Log parsing, transformation, and shipping from Kubernetes to Elasticsearch
- **Kibana**: Interactive log visualization, saved searches, and analysis

### Dashboards
- **Grafana Dashboards**: Transaction monitoring, batch job tracking, JVM health, database performance
- **Kibana Dashboards**: Log analysis, error tracking, trace correlation

### Alerting
- **CloudWatch Alarms**: SLA monitoring (sub-200ms transactions, 4-hour batch windows)
- **SNS Notifications**: Email and Slack alerts for critical issues
- **Alert Types**: High error rates, slow transactions, failed batch jobs, resource exhaustion

### Security
- **IAM Roles for Service Accounts (IRSA)**: Secure AWS API access for Kubernetes pods
- **Encryption**: At-rest and in-transit encryption for Elasticsearch and CloudWatch logs
- **VPC Security Groups**: Network isolation for monitoring components
- **Secrets Management**: Grafana credentials stored in AWS Secrets Manager (recommended)

## Usage

### Basic Example

```hcl
module "monitoring" {
  source = "./modules/monitoring"

  eks_cluster_id       = module.eks.cluster_id
  eks_cluster_name     = module.eks.cluster_name
  vpc_id               = module.vpc.vpc_id
  private_subnet_ids   = module.vpc.private_subnet_ids
  
  enable_prometheus    = true
  enable_grafana       = true
  enable_cloudwatch    = true
  enable_elk_stack     = false  # Set true for production
  
  prometheus_retention_days = 15
  grafana_admin_password    = var.grafana_password  # Use secrets manager
  
  alert_email_endpoints = ["devops@example.com"]
  
  tags = {
    Environment = var.environment
    Project     = "CardDemo"
    ManagedBy   = "Terraform"
  }
}
```

### Production Example with ELK Stack

```hcl
module "monitoring" {
  source = "./modules/monitoring"

  eks_cluster_id       = module.eks.cluster_id
  eks_cluster_name     = module.eks.cluster_name
  vpc_id               = module.vpc.vpc_id
  private_subnet_ids   = module.vpc.private_subnet_ids
  
  enable_prometheus    = true
  enable_grafana       = true
  enable_cloudwatch    = true
  enable_elk_stack     = true
  
  prometheus_retention_days = 30
  prometheus_storage_size   = "100Gi"
  
  grafana_admin_password = data.aws_secretsmanager_secret_version.grafana_password.secret_string
  grafana_storage_size   = "20Gi"
  
  elasticsearch_version         = "7.17"
  elasticsearch_instance_count  = 3
  elasticsearch_instance_type   = "t3.medium.elasticsearch"
  elasticsearch_volume_size     = 200
  
  cloudwatch_log_retention_days = 30
  
  alert_email_endpoints = [
    "devops@example.com",
    "oncall@example.com"
  ]
  alert_slack_webhook = var.slack_webhook_url
  
  tags = {
    Environment = "production"
    Project     = "CardDemo"
    ManagedBy   = "Terraform"
    CostCenter  = "IT-Operations"
  }
}
```

### Development Example (Cost-Optimized)

```hcl
module "monitoring" {
  source = "./modules/monitoring"

  eks_cluster_id       = module.eks.cluster_id
  eks_cluster_name     = module.eks.cluster_name
  vpc_id               = module.vpc.vpc_id
  private_subnet_ids   = module.vpc.private_subnet_ids
  
  enable_prometheus    = true
  enable_grafana       = true
  enable_cloudwatch    = true
  enable_elk_stack     = false  # Disabled to reduce costs
  
  prometheus_retention_days = 7
  prometheus_storage_size   = "20Gi"
  
  grafana_admin_password = var.grafana_password
  grafana_storage_size   = "5Gi"
  
  cloudwatch_log_retention_days = 3
  
  alert_email_endpoints = ["dev-team@example.com"]
  
  tags = {
    Environment = "development"
    Project     = "CardDemo"
    ManagedBy   = "Terraform"
  }
}
```

## Input Variables

| Variable | Type | Default | Required | Description |
|----------|------|---------|----------|-------------|
| `eks_cluster_id` | string | - | Yes | EKS cluster identifier for monitoring integration |
| `eks_cluster_name` | string | - | Yes | EKS cluster name used for resource naming |
| `vpc_id` | string | - | Yes | VPC ID where monitoring components will be deployed |
| `private_subnet_ids` | list(string) | - | Yes | Private subnet IDs for Elasticsearch deployment |
| `enable_prometheus` | bool | true | No | Enable Prometheus metrics collection |
| `enable_grafana` | bool | true | No | Enable Grafana dashboard deployment |
| `enable_cloudwatch` | bool | true | No | Enable CloudWatch integration for logs and metrics |
| `enable_elk_stack` | bool | false | No | Enable ELK stack (resource-intensive, recommended for production only) |
| `prometheus_retention_days` | number | 15 | No | Number of days to retain Prometheus metrics |
| `prometheus_storage_size` | string | "50Gi" | No | Persistent volume size for Prometheus data storage |
| `grafana_admin_password` | string | - | Yes | Grafana admin password (sensitive - use secrets manager) |
| `grafana_storage_size` | string | "10Gi" | No | Persistent volume size for Grafana dashboards |
| `elasticsearch_version` | string | "7.17" | No | Elasticsearch version to deploy |
| `elasticsearch_instance_count` | number | 3 | No | Number of Elasticsearch nodes for high availability |
| `elasticsearch_instance_type` | string | "t3.medium.elasticsearch" | No | AWS Elasticsearch instance type |
| `elasticsearch_volume_size` | number | 100 | No | EBS volume size per Elasticsearch node (GB) |
| `cloudwatch_log_retention_days` | number | 7 | No | CloudWatch Logs retention period (days) |
| `alert_email_endpoints` | list(string) | [] | No | Email addresses to receive alert notifications |
| `alert_slack_webhook` | string | "" | No | Slack webhook URL for alert notifications (optional) |
| `tags` | map(string) | {} | No | Tags to apply to all monitoring resources |

## Outputs

| Output | Description |
|--------|-------------|
| `prometheus_endpoint` | Prometheus server URL for metrics queries and API access |
| `grafana_endpoint` | Grafana dashboard URL for visualization access |
| `grafana_admin_password` | Grafana admin credentials (marked as sensitive) |
| `cloudwatch_log_group_backend` | CloudWatch log group name for backend application logs |
| `cloudwatch_log_group_batch` | CloudWatch log group name for Spring Batch job logs |
| `elasticsearch_endpoint` | Elasticsearch domain endpoint for log indexing |
| `kibana_endpoint` | Kibana dashboard URL for log search and analysis |
| `alert_sns_topic_arn` | SNS topic ARN for alert subscriptions and integrations |

## Monitoring Strategy

### Metrics Collection

**Prometheus Scraping:**
- Scrapes `/actuator/prometheus` endpoints from Spring Boot pods every 15 seconds
- Service discovery via Kubernetes annotations: `prometheus.io/scrape: "true"`
- Configures metric retention based on `prometheus_retention_days` variable
- Stores metrics in persistent volume to survive pod restarts

**CloudWatch Integration:**
- Spring Boot applications publish custom metrics to CloudWatch via AWS SDK
- Metrics include transaction counts, response times, batch job durations
- Enables AWS-native monitoring and integration with other AWS services
- Provides unified view of application and infrastructure metrics

### Log Aggregation

**Kubernetes Logs:**
- All application logs written to stdout/stderr (12-factor app principle)
- Kubernetes captures container logs automatically
- Logstash deployed as DaemonSet collects logs from all nodes
- Logs enriched with metadata (pod name, namespace, labels)

**Log Pipeline:**
1. Application → stdout/stderr (JSON format recommended)
2. Kubernetes → Container runtime captures logs
3. Logstash → Parses, transforms, and ships logs
4. Elasticsearch → Indexes logs for search and analysis
5. Kibana → Provides search interface and visualizations
6. CloudWatch Logs → AWS-native log storage and monitoring

**Structured Logging:**
- Applications should use JSON-formatted logs for easier parsing
- Include fields: timestamp, level, message, trace_id, user_id, transaction_id
- Example: `{"timestamp":"2024-01-15T10:30:45Z","level":"INFO","message":"Transaction processed","trace_id":"abc123","transaction_id":"TXN-456"}`

### Alerting

**Alert Routing:**
```
CloudWatch Alarm → SNS Topic → Email/Slack
                              → Lambda (for advanced processing)
                              → PagerDuty (via SNS subscription)
```

**Alert Priority Levels:**
- **Critical**: Service down, failed batch jobs, database connection failures → Immediate notification
- **Warning**: High error rates, slow transactions, resource pressure → Team notification during business hours
- **Info**: Batch job completions, deployment events → Log only, no notification

## Grafana Dashboards

### Transaction Monitoring Dashboard

**Panels:**
- **Transaction Rate**: Requests per second over time (target: 10,000 TPS peak)
- **Response Time Distribution**: P50, P95, P99 percentiles (SLA: P95 < 200ms)
- **Error Rate**: HTTP 4xx and 5xx errors per minute
- **Transaction Types**: Breakdown by transaction category (authorization, payment, inquiry)
- **Throughput by Endpoint**: Top 10 most-used API endpoints

**Key Metrics:**
- `http_server_requests_seconds_count` - Total request count
- `http_server_requests_seconds_sum` - Total request duration
- `http_server_requests_seconds_max` - Maximum request duration
- `carddemo_transaction_total` - Custom transaction counter by type

**Alert Integration:**
- Visual indicators when response time exceeds 200ms threshold
- Red highlighting for error rates above 1%

### Batch Job Monitoring Dashboard

**Panels:**
- **Job Execution Timeline**: Gantt chart of batch jobs (CBACT*, CBTRN*, CBCUS* jobs)
- **Job Duration**: Time taken for each batch job (SLA: complete within 4-hour window)
- **Success/Failure Rate**: Percentage of successful vs. failed job executions
- **Records Processed**: Count of records processed per job
- **Job Schedule Adherence**: Actual vs. scheduled start times

**Key Metrics:**
- `spring_batch_job_seconds_max` - Job execution duration
- `spring_batch_job_active` - Currently running jobs
- `spring_batch_job_seconds_count` - Total job executions
- `spring_batch_step_seconds_max` - Step-level execution time
- `carddemo_batch_records_processed_total` - Custom record counter

**Replaces Mainframe:**
- JCL job timing reports
- Batch job completion notifications
- SYSOUT log analysis for job status

### JVM Metrics Dashboard

**Panels:**
- **Heap Memory Usage**: Used vs. max heap memory
- **Garbage Collection**: GC pause frequency and duration
- **Thread Count**: Active threads, peak threads, daemon threads
- **CPU Usage**: Process CPU utilization percentage
- **Class Loading**: Loaded/unloaded classes over time

**Key Metrics:**
- `jvm_memory_used_bytes` - Memory consumption by pool (heap, non-heap)
- `jvm_gc_pause_seconds_count` - GC pause count
- `jvm_gc_pause_seconds_sum` - Total GC pause time
- `jvm_threads_live` - Current thread count
- `process_cpu_usage` - Process CPU utilization

**Health Indicators:**
- Alert if heap usage exceeds 85% for 5 minutes
- Alert if GC pause time exceeds 1 second
- Alert if thread count grows unbounded

### Database Performance Dashboard

**Panels:**
- **Connection Pool**: Active connections, idle connections, pending requests
- **Query Performance**: Query execution time by operation type
- **Transaction Rate**: Database transactions per second
- **Slow Queries**: Queries exceeding 100ms threshold
- **Deadlocks/Errors**: Database error count over time

**Key Metrics:**
- `hikaricp_connections_active` - Active database connections
- `hikaricp_connections_pending` - Connection requests waiting
- `jdbc_connections_max` - Maximum connection pool size
- Custom metrics from Spring Data JPA repositories
- `carddemo_db_query_seconds` - Query execution time histogram

**Replaces Mainframe:**
- VSAM I/O statistics
- DB2 performance monitors
- File access timing reports

## CloudWatch Alarms

### Transaction Performance Alarms

**High Response Time Alarm:**
- **Metric**: Custom CloudWatch metric `CardDemo/TransactionResponseTime`
- **Threshold**: P95 response time > 200ms for 2 consecutive minutes
- **Action**: Trigger SNS notification to operations team
- **Recovery**: Auto-resolve when response time drops below 150ms

**Transaction Error Rate Alarm:**
- **Metric**: `CardDemo/TransactionErrors`
- **Threshold**: Error rate > 1% (100 errors per 10,000 requests) over 5 minutes
- **Action**: Send alert to development team
- **Severity**: Warning (escalate to critical if > 5%)

### Batch Job Alarms

**Batch Job Failure Alarm:**
- **Metric**: Custom metric `CardDemo/BatchJobFailure`
- **Threshold**: Any batch job failure (value = 1)
- **Action**: Immediate SNS notification with job name and error details
- **Recovery**: Manual acknowledgment required

**Batch Window Overrun Alarm:**
- **Metric**: Custom metric `CardDemo/BatchJobDuration`
- **Threshold**: Total batch processing time > 4 hours (14,400 seconds)
- **Action**: Alert operations team for immediate investigation
- **Context**: Mainframe constraint - batch must complete before business hours

### Infrastructure Alarms

**EKS Node High CPU Alarm:**
- **Metric**: CloudWatch Container Insights `node_cpu_utilization`
- **Threshold**: CPU > 80% for 5 consecutive minutes
- **Action**: Trigger auto-scaling if enabled, notify operations
- **Recovery**: Auto-resolve when CPU < 70%

**Pod Restart Alarm:**
- **Metric**: CloudWatch Container Insights `pod_restart_count`
- **Threshold**: > 5 restarts in 10 minutes for any pod
- **Action**: Alert development team with pod name and logs
- **Investigation**: Check application logs and resource limits

**Elasticsearch Disk Space Alarm:**
- **Metric**: CloudWatch `AWS/ES/FreeStorageSpace`
- **Threshold**: Free space < 10 GB
- **Action**: Alert operations team to scale storage
- **Prevention**: Reduce log retention or add nodes

### Application Alarms

**High Error Log Count:**
- **Metric**: CloudWatch Logs Metric Filter counting ERROR level logs
- **Threshold**: > 10 ERROR logs in 5 minutes
- **Action**: Send alert with log samples to development team
- **Pattern**: `[time, level=ERROR, ...]`

**Spring Security Authentication Failures:**
- **Metric**: Custom metric `CardDemo/AuthenticationFailures`
- **Threshold**: > 20 failed login attempts in 1 minute (potential brute force)
- **Action**: Alert security team, consider blocking IP
- **Context**: Replaces mainframe RACF security monitoring

## ELK Stack Configuration

### Elasticsearch Domain

**Cluster Configuration:**
- **Node Count**: 3 nodes for high availability (production), 1 node (development)
- **Instance Type**: `t3.medium.elasticsearch` (2 vCPU, 4 GB RAM) - adjustable
- **Storage**: 100 GB EBS per node (configurable via `elasticsearch_volume_size`)
- **Version**: 7.17 (latest 7.x series for stability)

**Security:**
- **VPC Deployment**: Elasticsearch deployed in private subnets
- **Encryption at Rest**: EBS volumes encrypted with AWS KMS
- **Encryption in Transit**: TLS 1.2 for all connections
- **Access Policy**: IAM-based access control via IRSA

**High Availability:**
- 3 master-eligible nodes across availability zones
- Automated snapshots to S3 (daily at 2 AM UTC)
- Zone-aware replica allocation

### Logstash Configuration

**Deployment:**
- Deployed as Kubernetes DaemonSet (one pod per node)
- Collects logs from all pods on the node
- Configurable filters and parsers via ConfigMap

**Pipeline Configuration:**
```ruby
input {
  # Collect logs from Kubernetes
  file {
    path => "/var/log/containers/*.log"
    type => "kubernetes"
    start_position => "beginning"
  }
}

filter {
  # Parse JSON logs from Spring Boot
  if [message] =~ /^\{.*\}$/ {
    json {
      source => "message"
    }
  }
  
  # Extract Kubernetes metadata
  kubernetes {
    metadata => ["pod", "namespace", "labels"]
  }
  
  # Parse timestamp
  date {
    match => ["timestamp", "ISO8601"]
    target => "@timestamp"
  }
  
  # Add custom fields
  mutate {
    add_field => {
      "application" => "carddemo"
      "environment" => "${ENVIRONMENT}"
    }
  }
}

output {
  # Send to Elasticsearch
  elasticsearch {
    hosts => ["${ELASTICSEARCH_ENDPOINT}"]
    index => "carddemo-logs-%{+YYYY.MM.dd}"
    user => "${ES_USER}"
    password => "${ES_PASSWORD}"
  }
  
  # Also send to CloudWatch for AWS-native monitoring
  cloudwatch_logs {
    log_group_name => "/aws/carddemo/application"
    log_stream_name => "%{[kubernetes][pod][name]}"
    region => "${AWS_REGION}"
  }
}
```

**Resource Limits:**
- CPU: 200m request, 500m limit
- Memory: 512Mi request, 1Gi limit

### Kibana Configuration

**Access:**
- Accessed via Elasticsearch domain endpoint
- Authentication via AWS IAM (Cognito integration optional)
- Deployed within VPC for security

**Index Patterns:**
- `carddemo-logs-*` - Application and batch job logs
- `carddemo-metrics-*` - Custom metrics logged to Elasticsearch
- `carddemo-audit-*` - Audit trail logs (user actions, security events)

**Saved Searches:**
- **Error Logs**: `level:ERROR` - All ERROR level logs
- **Failed Transactions**: `transaction.status:FAILED` - Failed transaction logs
- **Batch Job Logs**: `logger:com.carddemo.batch.*` - Spring Batch logs
- **Slow Queries**: `query_time_ms:>100` - Database queries exceeding 100ms
- **Authentication Failures**: `event:authentication_failure` - Security events

**Visualizations:**
- **Log Volume Over Time**: Bar chart of log count per hour
- **Error Distribution**: Pie chart of errors by type
- **Top Error Messages**: Table of most frequent error messages
- **Transaction Flow**: Sankey diagram of transaction processing stages

**Dashboards:**
- **Application Health**: Overview of errors, warnings, and key metrics
- **Batch Job Analysis**: Batch job logs, errors, and timing
- **Security Audit**: Authentication events, authorization failures
- **Performance Investigation**: Slow queries, high response times

### Log Retention

**Elasticsearch:**
- **Production**: 30 days (configurable)
- **Development**: 7 days to reduce costs
- Automated deletion via Index Lifecycle Management (ILM)
- Old indices moved to cold storage (S3) if needed

**CloudWatch Logs:**
- **Production**: 30 days (adjustable via `cloudwatch_log_retention_days`)
- **Development**: 3-7 days
- Automatically deleted after retention period

## Performance Monitoring

### Transaction Response Times

**Metric Collection:**
- Spring Boot Actuator exposes histogram metrics: `http_server_requests_seconds`
- Metrics include tags: method, uri, status, outcome
- Percentiles calculated: P50, P90, P95, P99, P99.9

**SLA Monitoring:**
- **Target**: P95 response time < 200ms (replaces mainframe response time SLA)
- **Measurement**: Prometheus PromQL query: `histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))`
- **Alerting**: CloudWatch alarm triggers if P95 > 200ms for 2 consecutive minutes

**Dashboard Visualization:**
- Line graph showing P50, P95, P99 over time
- Heatmap showing response time distribution
- Breakdown by endpoint to identify slow operations

### Batch Processing Windows

**Metric Collection:**
- Spring Batch exposes job execution metrics via Actuator
- Custom metrics for job start time, end time, duration, record count
- Metrics tagged with job name, status (completed, failed)

**SLA Monitoring:**
- **Target**: All batch jobs complete within 4-hour window (02:00-06:00 UTC)
- **Measurement**: Sum of all batch job durations must be < 14,400 seconds
- **Alerting**: Alert if any single job exceeds expected duration or total window overruns

**Dashboard Visualization:**
- Gantt chart showing job execution timeline
- Table showing job name, start time, end time, duration, status
- Historical trend of batch completion times

**Replaces Mainframe:**
- JCL job timing from JES2 job log
- Batch window monitoring from operations console

### Throughput Metrics

**Metric Collection:**
- Transaction counter: `carddemo_transaction_total` (tagged by type, status)
- Rate calculation: `rate(carddemo_transaction_total[1m])` for transactions per second
- Custom metrics for card authorizations, payments, inquiries

**SLA Monitoring:**
- **Target**: Handle peak load of 10,000 TPS without degradation
- **Measurement**: Sustained TPS during load testing and production peaks
- **Capacity Planning**: Monitor TPS trends to predict scaling needs

**Dashboard Visualization:**
- Line graph of TPS over time (5-minute average)
- Stacked area chart showing transaction types
- Comparison of actual vs. target TPS

**Replaces Mainframe:**
- CICS transaction rate monitoring (CEML, CMF data)
- Mainframe capacity planning reports

### Database Query Performance

**Metric Collection:**
- HikariCP connection pool metrics (active, idle, pending connections)
- Custom metrics for query execution time (via Spring Data JPA or interceptors)
- Slow query logging (queries > 100ms logged to Elasticsearch)

**SLA Monitoring:**
- **Target**: P95 query time < 10ms for primary key lookups (matching VSAM performance)
- **Target**: P95 query time < 50ms for indexed range scans
- **Measurement**: Histogram metrics: `carddemo_db_query_seconds`

**Dashboard Visualization:**
- Query time percentiles (P50, P95, P99)
- Connection pool utilization over time
- Top 10 slowest queries with execution counts

**Optimization:**
- Ensure B-tree indexes on all foreign keys and query predicates
- Use PostgreSQL EXPLAIN ANALYZE to validate query plans
- Monitor index hit ratios (target > 99%)

**Replaces Mainframe:**
- VSAM I/O statistics and key access times
- DB2 performance monitoring (if VSAM is replaced with DB2)

## Security Considerations

### IAM Roles for Service Accounts (IRSA)

**Purpose:**
- Provides secure access to AWS services from Kubernetes pods without embedding credentials
- Uses OpenID Connect (OIDC) provider for EKS cluster

**Implementation:**
- Prometheus, Grafana, and Logstash pods assume IAM roles via service accounts
- Roles have least-privilege policies (read CloudWatch metrics, write logs to S3, etc.)

**Required Permissions:**

**Prometheus CloudWatch Exporter Role:**
- `cloudwatch:ListMetrics`
- `cloudwatch:GetMetricStatistics`
- `cloudwatch:GetMetricData`

**Grafana CloudWatch Data Source Role:**
- `cloudwatch:DescribeAlarms`
- `cloudwatch:GetMetricData`
- `logs:DescribeLogGroups`
- `logs:GetLogEvents`

**Logstash CloudWatch Logs Role:**
- `logs:CreateLogGroup`
- `logs:CreateLogStream`
- `logs:PutLogEvents`

### Encryption

**Elasticsearch Encryption:**
- **At Rest**: EBS volumes encrypted using AWS KMS (default or custom CMK)
- **In Transit**: Node-to-node encryption enabled (TLS 1.2)
- **Client Connections**: HTTPS required for all Kibana and API access

**CloudWatch Logs Encryption:**
- Log groups encrypted with KMS key (configurable)
- Key policy allows CloudWatch service to use key for encryption/decryption

**Secrets Management:**
- **Grafana Admin Password**: Store in AWS Secrets Manager, reference via data source in Terraform
- **Elasticsearch Credentials**: Managed by AWS (IAM-based auth recommended)
- **Slack Webhook URL**: Store in AWS Secrets Manager or Parameter Store

### VPC Security Groups

**Prometheus Security Group:**
- Allow inbound: Port 9090 from Grafana, kubectl exec (operator access)
- Allow outbound: Port 443 to AWS APIs, pod CIDR for scraping metrics

**Grafana Security Group:**
- Allow inbound: Port 3000 from ALB/NLB (user access), kubectl exec
- Allow outbound: Port 9090 to Prometheus, port 443 to CloudWatch APIs

**Elasticsearch Security Group:**
- Allow inbound: Port 443 from Logstash pods, Kibana, kubectl exec
- Allow outbound: None (Elasticsearch doesn't initiate connections)

**Logstash Security Group:**
- Allow inbound: Port 5044 (Beats input, if used)
- Allow outbound: Port 443 to Elasticsearch, CloudWatch APIs

### Access Control

**Grafana:**
- Admin credentials required for dashboard editing
- Viewer role for read-only access (create via Grafana API)
- Consider LDAP/OAuth integration for enterprise auth

**Kibana:**
- Access controlled via Elasticsearch IAM policy
- Consider Cognito integration for user authentication
- Read-only roles for developers, read-write for operations

**Prometheus:**
- No built-in authentication (deploy behind reverse proxy if external access needed)
- Use Kubernetes RBAC to restrict kubectl access
- Grafana queries Prometheus (no direct user access)

### Compliance

**Audit Logging:**
- Enable CloudWatch API logging for all monitoring resource access
- Enable S3 access logging for Elasticsearch automated snapshots
- Log all Grafana user actions (dashboard views, edits, data source queries)

**Data Retention:**
- Configure retention periods to meet compliance requirements
- Default: 7 days (dev), 30 days (prod), adjustable
- Export logs to S3 for long-term archival if required

## Cost Optimization

### Development Environment

**Reduce Costs:**
1. **Disable ELK Stack**: Set `enable_elk_stack = false` (saves $300-500/month)
   - Use CloudWatch Logs only for development and testing
   - Enable ELK stack only in staging and production

2. **Reduce Elasticsearch Instance Count**: Use 1 node instead of 3 (saves ~$200/month)
   - Acceptable for non-production environments
   - No high availability, but sufficient for testing

3. **Use Smaller Elasticsearch Instances**: `t3.small.elasticsearch` instead of `t3.medium.elasticsearch`
   - Saves 50% on instance costs
   - Sufficient for low log volume

4. **Reduce Prometheus Retention**: Set `prometheus_retention_days = 7` instead of 30
   - Reduces storage requirements by 75%
   - Sufficient for short-term troubleshooting

5. **Reduce CloudWatch Log Retention**: Set `cloudwatch_log_retention_days = 3` instead of 30
   - CloudWatch charges based on storage and ingestion
   - Short retention acceptable for development

6. **Use Smaller Persistent Volumes**:
   - Prometheus: `prometheus_storage_size = "20Gi"` instead of 50Gi
   - Grafana: `grafana_storage_size = "5Gi"` instead of 10Gi

### Production Environment

**Optimize Costs While Maintaining Reliability:**

1. **Right-Size Elasticsearch Instances**:
   - Monitor CPU and memory utilization
   - Start with `t3.medium.elasticsearch`, scale up only if needed
   - Use reserved instances for 30-50% savings if long-term commitment

2. **Optimize Log Retention**:
   - Balance between operational needs and costs
   - Production: 30 days in Elasticsearch, 90 days in S3 (cold storage)
   - Development: 7 days in Elasticsearch, no archival

3. **Use CloudWatch Logs Insights Instead of Elasticsearch for Some Use Cases**:
   - CloudWatch Logs Insights provides SQL-like querying
   - Can handle many troubleshooting scenarios without full ELK stack
   - Pay per query instead of persistent cluster

4. **Enable Auto-Scaling for Elasticsearch** (if using OpenSearch instead of managed Elasticsearch):
   - Scale up during business hours, scale down at night
   - Not available for AWS Elasticsearch, but consider OpenSearch on EKS

5. **Monitor Prometheus Cardinality**:
   - High cardinality (many unique label combinations) increases storage
   - Review metric labels, avoid high-cardinality labels (user IDs, transaction IDs)
   - Use recording rules to pre-aggregate high-cardinality metrics

6. **Compress CloudWatch Logs**:
   - CloudWatch automatically compresses logs, no action needed
   - Ensure applications don't log excessively (avoid DEBUG logs in production)

### Cost Monitoring

**Track Monitoring Costs:**
- Tag all monitoring resources with `CostCenter` and `Project` tags
- Use AWS Cost Explorer to track monthly costs by tag
- Set up budget alerts for monitoring infrastructure

**Expected Monthly Costs (us-east-1):**

**Development Environment:**
- Prometheus/Grafana (EKS pods): ~$0 (covered by EKS node costs)
- CloudWatch Logs (5 GB/day): ~$5/month
- Total: ~$5/month (excluding ELK stack)

**Production Environment (with ELK):**
- Elasticsearch (3x t3.medium, 300 GB): ~$400/month
- CloudWatch Logs (50 GB/day): ~$50/month
- CloudWatch Metrics (custom metrics): ~$10/month
- CloudWatch Alarms (20 alarms): ~$10/month
- Prometheus/Grafana (EKS pods): ~$0 (covered by EKS)
- Data transfer (outbound): ~$20/month
- Total: ~$490/month

## High Availability

### Prometheus

**Current Configuration:**
- Single Prometheus instance with persistent volume
- Sufficient for most use cases, data survives pod restarts

**High Availability Options (Optional):**
1. **Prometheus Operator with Multiple Replicas**:
   - Deploy 2+ Prometheus instances scraping same targets
   - Grafana queries all instances (HA query mode)
   - Requires more storage (data duplicated)

2. **Thanos or Cortex**:
   - Long-term storage in S3
   - Global query view across multiple Prometheus instances
   - More complex setup, consider for large-scale deployments

**Recommendation**: Single instance sufficient for CardDemo, upgrade to Thanos if scaling to multiple clusters.

### Grafana

**Current Configuration:**
- Single Grafana instance with persistent volume for dashboards
- Dashboards backed up via provisioning (stored in Git repository)

**High Availability Options (Optional):**
1. **Multiple Grafana Replicas**:
   - Deploy 2+ Grafana pods behind load balancer
   - Requires external database (PostgreSQL or MySQL) instead of SQLite
   - Session affinity or shared session storage

**Recommendation**: Single instance sufficient, dashboards stored in Git for disaster recovery.

### Elasticsearch

**Configuration:**
- 3-node cluster deployed across availability zones
- Each node is master-eligible and data node
- Automated snapshots to S3 daily at 2 AM UTC

**Disaster Recovery:**
- **RPO** (Recovery Point Objective): 24 hours (daily snapshots)
- **RTO** (Recovery Time Objective): 1-2 hours (restore from snapshot)

**Restore Procedure:**
1. Create new Elasticsearch domain (or restore existing)
2. Restore snapshot from S3: `POST /_snapshot/s3-repository/snapshot-name/_restore`
3. Verify data integrity and index health
4. Update Logstash and Kibana endpoints

### CloudWatch

**Availability:**
- CloudWatch is a managed AWS service with built-in high availability
- Multi-AZ by default, no configuration needed
- 99.99% SLA from AWS

**Data Durability:**
- CloudWatch Logs automatically replicated across availability zones
- Metrics stored for 15 months (automatic retention)

## Prerequisites

### EKS Cluster Requirements

1. **EKS Cluster with OIDC Provider Enabled**:
   - Required for IAM Roles for Service Accounts (IRSA)
   - Enable via: `eksctl utils associate-iam-oidc-provider --cluster <cluster-name> --approve`

2. **Kubernetes Version**: 1.28 or later

3. **Cluster Addons**:
   - CoreDNS
   - kube-proxy
   - Amazon VPC CNI

4. **Metrics Server** (for HPA):
   - Required for Horizontal Pod Autoscaler
   - Install via: `kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml`

### Networking Requirements

1. **VPC with Private Subnets**:
   - Private subnets for Elasticsearch deployment
   - Subnets must have NAT gateway access for outbound internet (AWS API calls)

2. **VPC Endpoints (Recommended for Cost Optimization)**:
   - `com.amazonaws.<region>.s3` - S3 gateway endpoint (free)
   - `com.amazonaws.<region>.logs` - CloudWatch Logs endpoint
   - `com.amazonaws.<region>.monitoring` - CloudWatch endpoint

3. **Security Groups**:
   - EKS cluster security group allowing pod-to-pod communication
   - Additional security groups created by this module

### Terraform Requirements

1. **Terraform Version**: >= 1.9.0

2. **Terraform Providers**:
   ```hcl
   required_providers {
     aws = {
       source  = "hashicorp/aws"
       version = "~> 5.82"
     }
     kubernetes = {
       source  = "hashicorp/kubernetes"
       version = "~> 2.35"
     }
     helm = {
       source  = "hashicorp/helm"
       version = "~> 2.17"
     }
   }
   ```

3. **Provider Configuration**:
   - AWS provider configured with appropriate credentials and region
   - Kubernetes provider configured with EKS cluster credentials
   - Helm provider configured with Kubernetes context

### IAM Permissions

**Terraform Execution Role Requires:**
- `iam:CreateRole`, `iam:AttachRolePolicy`, `iam:GetRole` - Create IRSA roles
- `es:CreateElasticsearchDomain`, `es:DescribeElasticsearchDomain` - Create Elasticsearch
- `logs:CreateLogGroup`, `logs:PutRetentionPolicy` - Create CloudWatch log groups
- `sns:CreateTopic`, `sns:Subscribe` - Create SNS topics for alerts
- `cloudwatch:PutMetricAlarm` - Create CloudWatch alarms
- `eks:DescribeCluster` - Read EKS cluster information
- `ec2:CreateSecurityGroup`, `ec2:AuthorizeSecurityGroupIngress` - Network security

### Application Requirements

1. **Spring Boot Applications Must**:
   - Expose `/actuator/prometheus` endpoint (spring-boot-starter-actuator dependency)
   - Include Micrometer Prometheus registry dependency
   - Annotate Kubernetes pods with `prometheus.io/scrape: "true"`

2. **Logging Requirements**:
   - Applications log to stdout/stderr (not files)
   - Use structured logging (JSON format recommended)
   - Include trace_id in logs for correlation

3. **Custom Metrics**:
   - Applications should register custom metrics for business KPIs
   - Use Micrometer API: `meterRegistry.counter("carddemo.transaction.total").increment()`

## Dependencies

### Module Dependencies

This module depends on outputs from other Terraform modules:

1. **VPC Module** (Required):
   - `vpc_id` - VPC where monitoring components are deployed
   - `private_subnet_ids` - Subnets for Elasticsearch domain

2. **EKS Module** (Required):
   - `cluster_id` - EKS cluster identifier
   - `cluster_name` - EKS cluster name
   - `cluster_oidc_issuer_url` - For creating IRSA roles
   - `cluster_endpoint` - Kubernetes API endpoint
   - `cluster_certificate_authority_data` - For kubectl/helm authentication

### Apply Order

1. **First**: Apply VPC and EKS modules
2. **Second**: Ensure EKS cluster is fully operational (nodes ready)
3. **Third**: Apply monitoring module

**Terraform Command:**
```bash
# Apply VPC and EKS first
terraform apply -target=module.vpc -target=module.eks

# Wait for EKS nodes to be ready
kubectl get nodes

# Apply monitoring module
terraform apply -target=module.monitoring
```

### Inter-Module Communication

**Data Flow:**
- EKS pods expose metrics → Prometheus scrapes → Grafana visualizes
- EKS pods log to stdout → Logstash collects → Elasticsearch indexes → Kibana displays
- Spring Boot apps publish metrics → CloudWatch → CloudWatch Alarms → SNS → Email/Slack

## Troubleshooting

### Prometheus Not Scraping Metrics

**Symptoms:**
- Grafana dashboards show "No data"
- Prometheus targets page shows targets in "DOWN" state

**Diagnosis:**
1. Check Prometheus target status: `kubectl port-forward svc/prometheus 9090:9090`, open http://localhost:9090/targets
2. Verify pod annotations:
   ```bash
   kubectl get pods -n carddemo -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.metadata.annotations.prometheus\.io/scrape}{"\n"}{end}'
   ```
3. Check network connectivity from Prometheus pod to application pods:
   ```bash
   kubectl exec -it prometheus-<pod-id> -- wget -O- http://<app-pod-ip>:8080/actuator/prometheus
   ```

**Solutions:**
- Add annotation to application deployment: `prometheus.io/scrape: "true"`
- Add annotation for custom port: `prometheus.io/port: "8080"`
- Add annotation for custom path: `prometheus.io/path: "/actuator/prometheus"`
- Verify Spring Boot Actuator dependency is included in pom.xml
- Check application logs for errors in metrics endpoint

### Grafana Cannot Access CloudWatch

**Symptoms:**
- CloudWatch data source shows "Error" status in Grafana
- Dashboards with CloudWatch queries display errors

**Diagnosis:**
1. Check Grafana logs: `kubectl logs -n monitoring grafana-<pod-id>`
2. Verify IRSA configuration:
   ```bash
   kubectl get sa grafana -n monitoring -o yaml
   # Should show annotation: eks.amazonaws.com/role-arn
   ```
3. Check IAM role permissions:
   ```bash
   aws iam get-role --role-name <grafana-role-name>
   aws iam list-attached-role-policies --role-name <grafana-role-name>
   ```

**Solutions:**
- Ensure Grafana service account is annotated with IAM role ARN
- Verify IAM role trust policy allows OIDC provider: `sts:AssumeRoleWithWebIdentity`
- Add required CloudWatch permissions to IAM role policy
- Check EKS cluster OIDC provider is configured correctly
- Restart Grafana pod to refresh credentials

### Elasticsearch Connection Timeout

**Symptoms:**
- Logstash cannot connect to Elasticsearch
- Kibana shows "Elasticsearch cluster unavailable"

**Diagnosis:**
1. Check Elasticsearch domain status: `aws es describe-elasticsearch-domain --domain-name <domain-name>`
2. Verify security group rules:
   ```bash
   aws ec2 describe-security-groups --group-ids <elasticsearch-sg-id>
   ```
3. Test connectivity from Logstash pod:
   ```bash
   kubectl exec -it logstash-<pod-id> -- curl -v https://<elasticsearch-endpoint>
   ```
4. Check Elasticsearch logs in CloudWatch: `/aws/elasticsearch/<domain-name>/application-logs`

**Solutions:**
- Verify Elasticsearch is deployed in VPC (not public endpoint)
- Check security group allows inbound port 443 from Logstash pods
- Verify VPC endpoint configuration if using VPC endpoints
- Ensure IAM role attached to Logstash allows es:ESHttp* actions
- Check Elasticsearch domain is in "Active" state (not "Processing" or "Failed")

### Logs Not Appearing in Kibana

**Symptoms:**
- Kibana index pattern shows no documents
- Elasticsearch indices not created

**Diagnosis:**
1. Check Logstash logs: `kubectl logs -n monitoring logstash-<pod-id>`
2. Verify Logstash is receiving logs:
   ```bash
   # Check log files Logstash is reading
   kubectl exec -it logstash-<pod-id> -- ls -la /var/log/containers/
   ```
3. Check Elasticsearch indices:
   ```bash
   curl -XGET "https://<elasticsearch-endpoint>/_cat/indices?v"
   ```
4. Verify index template:
   ```bash
   curl -XGET "https://<elasticsearch-endpoint>/_index_template/carddemo-logs"
   ```

**Solutions:**
- Verify Logstash DaemonSet is running on all nodes: `kubectl get pods -n monitoring -l app=logstash -o wide`
- Check Logstash configuration in ConfigMap for errors
- Ensure application pods are logging to stdout/stderr (not files)
- Verify Elasticsearch index pattern matches Logstash output configuration
- Create index pattern in Kibana: "carddemo-logs-*"
- Check for JSON parsing errors in Logstash logs

### CloudWatch Alarms Not Firing

**Symptoms:**
- Metrics exceed threshold but no SNS notification received
- Alarm state shows "INSUFFICIENT_DATA"

**Diagnosis:**
1. Check alarm state: `aws cloudwatch describe-alarms --alarm-names <alarm-name>`
2. Verify metric exists:
   ```bash
   aws cloudwatch get-metric-statistics \
     --namespace CardDemo \
     --metric-name TransactionResponseTime \
     --start-time 2024-01-15T00:00:00Z \
     --end-time 2024-01-15T23:59:59Z \
     --period 300 \
     --statistics Average
   ```
3. Check SNS topic subscriptions: `aws sns list-subscriptions-by-topic --topic-arn <topic-arn>`
4. Check SNS topic policy allows CloudWatch to publish

**Solutions:**
- Verify application is publishing custom metrics to CloudWatch
- Check metric dimensions match alarm configuration
- Confirm SNS topic subscription (check email for confirmation link)
- Verify SNS topic policy allows cloudwatch.amazonaws.com to publish
- Test SNS topic: `aws sns publish --topic-arn <topic-arn> --message "Test alert"`
- Check spam folder for SNS email notifications

### High Prometheus Storage Usage

**Symptoms:**
- Prometheus pod restarting due to disk full
- "No space left on device" errors in Prometheus logs

**Diagnosis:**
1. Check persistent volume usage:
   ```bash
   kubectl exec -it prometheus-<pod-id> -- df -h /prometheus
   ```
2. Check metric cardinality:
   ```bash
   # Port forward Prometheus
   kubectl port-forward svc/prometheus 9090:9090
   # Query high cardinality metrics
   curl http://localhost:9090/api/v1/label/__name__/values | jq .
   ```

**Solutions:**
- Increase `prometheus_storage_size` variable (requires PVC expansion)
- Reduce `prometheus_retention_days` to decrease storage requirements
- Identify high-cardinality metrics (labels with many unique values)
- Add recording rules to pre-aggregate high-cardinality metrics
- Exclude unnecessary metrics via Prometheus relabel_configs
- Consider implementing Thanos or Cortex for long-term storage in S3

### Grafana Dashboards Lost After Pod Restart

**Symptoms:**
- Dashboards disappear after Grafana pod is restarted or redeployed
- Need to manually recreate dashboards

**Diagnosis:**
1. Check if Grafana has persistent volume:
   ```bash
   kubectl get pvc -n monitoring
   kubectl describe pod grafana-<pod-id> | grep -A5 Volumes
   ```
2. Verify dashboard provisioning:
   ```bash
   kubectl exec -it grafana-<pod-id> -- ls /etc/grafana/provisioning/dashboards/
   ```

**Solutions:**
- Ensure Grafana deployment uses persistent volume for /var/lib/grafana
- Use dashboard provisioning to store dashboards as code:
  - Store dashboard JSON in Git repository
  - Mount dashboards via ConfigMap
  - Configure provisioning in Grafana configuration
- Export important dashboards: Grafana UI → Dashboard Settings → JSON Model → Save to file
- Consider Grafana database backend (PostgreSQL/MySQL) instead of SQLite for multi-replica setup

### Elasticsearch Cluster Yellow/Red Status

**Symptoms:**
- Kibana shows cluster health as "yellow" or "red"
- Some indices have unassigned shards

**Diagnosis:**
1. Check cluster health:
   ```bash
   curl -XGET "https://<elasticsearch-endpoint>/_cluster/health?pretty"
   ```
2. Identify unassigned shards:
   ```bash
   curl -XGET "https://<elasticsearch-endpoint>/_cat/shards?v&h=index,shard,prirep,state,unassigned.reason"
   ```
3. Check node count:
   ```bash
   curl -XGET "https://<elasticsearch-endpoint>/_cat/nodes?v"
   ```

**Solutions:**
- **Yellow Status**: Replica shards not assigned (common with single-node cluster)
  - If development: Accept yellow status (no replicas needed)
  - If production: Increase `elasticsearch_instance_count` to 2 or 3
- **Red Status**: Primary shards unavailable (data loss possible)
  - Check Elasticsearch logs in CloudWatch for errors
  - Restore from snapshot if data is lost
  - Scale up storage if disk full
- **Unassigned Shards**: Allocation issues
  - Check disk space on nodes
  - Manually reallocate: `POST /_cluster/reroute`
  - Adjust replica settings: `PUT /index-name/_settings {"number_of_replicas": 1}`

## Maintenance

### Regular Maintenance Tasks

**Weekly:**
- Review Grafana dashboards for anomalies
- Check CloudWatch Logs for ERROR/WARN patterns
- Verify Prometheus scrape success rate > 99%
- Review alert history in SNS/CloudWatch

**Monthly:**
- Update Prometheus and Grafana Helm charts to latest versions
- Review Elasticsearch disk usage and scale storage if > 80%
- Audit alert thresholds based on actual application behavior
- Clean up old CloudWatch log streams (auto-deleted based on retention policy)
- Export and backup Grafana dashboards to Git repository

**Quarterly:**
- Review and optimize Prometheus recording rules
- Analyze slow queries in Kibana and optimize database indexes
- Update Elasticsearch version (stay on latest 7.x or migrate to 8.x)
- Review monitoring costs and optimization opportunities
- Update alert contact lists (email, Slack)

### Upgrading Components

**Prometheus Upgrade:**
```bash
# Update Helm chart
helm repo update
helm upgrade prometheus prometheus-community/prometheus \
  --namespace monitoring \
  --values prometheus-values.yaml

# Verify
kubectl rollout status deployment/prometheus -n monitoring
```

**Grafana Upgrade:**
```bash
# Export dashboards first (backup)
./scripts/export-grafana-dashboards.sh

# Upgrade Helm chart
helm upgrade grafana grafana/grafana \
  --namespace monitoring \
  --values grafana-values.yaml

# Import dashboards if needed
```

**Elasticsearch Upgrade:**
```bash
# Upgrade via Terraform (updates aws_elasticsearch_domain)
# Elasticsearch performs in-place upgrade with rolling restart

terraform plan -target=module.monitoring.aws_elasticsearch_domain.main
terraform apply -target=module.monitoring.aws_elasticsearch_domain.main

# Monitor upgrade progress
aws es describe-elasticsearch-domain --domain-name <domain-name>
```

**Important**: Always test upgrades in development environment before production.

### Backup and Restore

**Grafana Dashboards:**
- Export dashboards via Grafana API or UI
- Store JSON files in Git repository under `infrastructure/monitoring/dashboards/`
- Automate export with cron job or CI/CD pipeline

**Elasticsearch Snapshots:**
- Automated daily snapshots to S3 (configured by this module)
- Manual snapshot: `PUT /_snapshot/s3-repository/snapshot-name?wait_for_completion=true`
- List snapshots: `GET /_snapshot/s3-repository/_all`
- Restore: `POST /_snapshot/s3-repository/snapshot-name/_restore`

**Prometheus Data:**
- Persistent volume preserves data across pod restarts
- For long-term backup, use Thanos or Cortex (writes to S3)
- Manual backup: Copy /prometheus directory from pod to local storage

**CloudWatch:**
- CloudWatch data is automatically backed up by AWS (durable storage)
- No manual backup required
- Export to S3 for long-term archival: `aws logs create-export-task`

### Scaling

**Scale Prometheus Storage:**
```bash
# Edit PVC (if storage class supports expansion)
kubectl edit pvc prometheus-storage -n monitoring
# Update storage size

# Restart Prometheus pod to recognize new size
kubectl delete pod prometheus-<pod-id> -n monitoring
```

**Scale Elasticsearch:**
```bash
# Update Terraform variable
elasticsearch_instance_count = 5  # Increase from 3 to 5

# Apply
terraform apply -target=module.monitoring.aws_elasticsearch_domain.main

# Elasticsearch automatically redistributes shards
```

**Scale Logstash:**
- Logstash runs as DaemonSet (one pod per node)
- Scales automatically when EKS nodes are added
- If needed, deploy as Deployment with multiple replicas instead

## Integration with Application

### Spring Boot Configuration

**pom.xml Dependencies:**
```xml
<dependencies>
  <!-- Spring Boot Actuator for metrics -->
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
  </dependency>
  
  <!-- Micrometer Prometheus registry -->
  <dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
  </dependency>
  
  <!-- CloudWatch metrics (optional) -->
  <dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-cloudwatch2</artifactId>
  </dependency>
</dependencies>
```

**application.yml Configuration:**
```yaml
# Actuator configuration
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  endpoint:
    health:
      show-details: always
    prometheus:
      enabled: true
  metrics:
    export:
      prometheus:
        enabled: true
      cloudwatch:
        namespace: CardDemo
        batch-size: 20
        enabled: ${CLOUDWATCH_METRICS_ENABLED:false}
    tags:
      application: ${spring.application.name}
      environment: ${ENVIRONMENT:dev}

# Logging configuration
logging:
  pattern:
    console: '{"timestamp":"%d{ISO8601}","level":"%level","thread":"%thread","logger":"%logger{36}","message":"%msg","trace_id":"%X{trace_id}","user_id":"%X{user_id}"}%n'
  level:
    root: INFO
    com.carddemo: DEBUG
```

### Kubernetes Deployment Annotations

**Backend Deployment YAML:**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: carddemo-backend
  namespace: carddemo
spec:
  replicas: 3
  template:
    metadata:
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      serviceAccountName: carddemo-backend-sa  # IRSA
      containers:
      - name: backend
        image: carddemo/backend:latest
        ports:
        - containerPort: 8080
          name: http
        env:
        - name: MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE
          value: "health,info,prometheus"
        - name: CLOUDWATCH_METRICS_ENABLED
          value: "true"
        - name: ENVIRONMENT
          value: "production"
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 60
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
```

### Custom Metrics Example

**Java Code:**
```java
package com.carddemo.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

@Service
public class TransactionService {
    
    private final Counter transactionCounter;
    private final Timer transactionTimer;
    
    public TransactionService(MeterRegistry meterRegistry) {
        // Register custom counter
        this.transactionCounter = Counter.builder("carddemo.transaction.total")
            .description("Total number of transactions processed")
            .tag("type", "authorization")
            .register(meterRegistry);
        
        // Register custom timer (histogram)
        this.transactionTimer = Timer.builder("carddemo.transaction.duration")
            .description("Transaction processing time")
            .tag("operation", "authorize")
            .publishPercentiles(0.5, 0.95, 0.99)  // P50, P95, P99
            .register(meterRegistry);
    }
    
    public void processTransaction(Transaction transaction) {
        transactionTimer.record(() -> {
            // Business logic
            doProcessTransaction(transaction);
            
            // Increment counter
            transactionCounter.increment();
        });
    }
}
```

### Structured Logging Example

**Java Code with SLF4J and Logback:**
```java
package com.carddemo.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {
    
    private static final Logger logger = LoggerFactory.getLogger(TransactionController.class);
    
    @PostMapping
    public ResponseEntity<TransactionDto> createTransaction(@RequestBody TransactionDto dto) {
        // Add trace_id to MDC for log correlation
        MDC.put("trace_id", UUID.randomUUID().toString());
        MDC.put("user_id", SecurityContextHolder.getContext().getAuthentication().getName());
        MDC.put("transaction_id", dto.getTransactionId());
        
        try {
            logger.info("Processing transaction: type={}, amount={}", dto.getType(), dto.getAmount());
            
            TransactionDto result = transactionService.process(dto);
            
            logger.info("Transaction processed successfully");
            return ResponseEntity.ok(result);
            
        } catch (Exception e) {
            logger.error("Transaction processing failed: error={}", e.getMessage(), e);
            throw e;
        } finally {
            MDC.clear();
        }
    }
}
```

**Logback Configuration (logback-spring.xml):**
```xml
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <includeContext>true</includeContext>
      <includeMdc>true</includeMdc>
      <fieldNames>
        <timestamp>timestamp</timestamp>
        <message>message</message>
        <logger>logger</logger>
        <thread>thread</thread>
        <level>level</level>
      </fieldNames>
    </encoder>
  </appender>
  
  <root level="INFO">
    <appender-ref ref="STDOUT"/>
  </root>
</configuration>
```

## Migration from Mainframe

### Monitoring Capability Mapping

| Mainframe Monitoring | Cloud-Native Replacement | Notes |
|---------------------|--------------------------|-------|
| JCL SYSOUT logs | CloudWatch Logs + Elasticsearch | Structured JSON logs instead of flat text |
| JES2 job log | Spring Batch metrics + CloudWatch | Job status, duration, record counts |
| CICS transaction stats (CEML) | Prometheus metrics | Transaction rate, response time, error rate |
| CICS monitoring facility (CMF) | Prometheus + Grafana | Real-time dashboards instead of periodic reports |
| VSAM I/O statistics | PostgreSQL metrics | Query performance, connection pool stats |
| Mainframe console messages | CloudWatch Alarms + SNS | Proactive alerts instead of reactive monitoring |
| RACF audit logs | CloudWatch Logs + Elasticsearch | Authentication/authorization events |
| Batch job timing reports | Grafana dashboards | Visual timeline of batch job execution |
| System abend dumps | Application logs + stack traces | Structured error logs with full context |
| Performance monitoring (RMF) | CloudWatch Container Insights | CPU, memory, disk, network metrics |

### Key Differences

**Mainframe Monitoring:**
- Reactive: Review logs after issues occur
- Batch reports: Generated periodically (daily, weekly)
- Text-based: SYSOUT logs in flat files
- Manual analysis: Operators review reports and take action
- Isolated: Different tools for different components (CICS, JES2, DB2)

**Cloud-Native Monitoring:**
- Proactive: Alerts trigger immediately when thresholds exceeded
- Real-time dashboards: Live metrics updated every 15 seconds
- Structured logs: JSON format with searchable fields
- Automated alerting: CloudWatch Alarms trigger SNS notifications
- Unified: Single pane of glass (Grafana) for all metrics

### Training Considerations

**For Operations Team:**
- Learn Grafana dashboard navigation and customization
- Understand Prometheus query language (PromQL) for custom queries
- Learn Kibana log search syntax for troubleshooting
- Understand CloudWatch Alarms and SNS subscriptions
- Shift from reviewing batch reports to monitoring real-time dashboards

**For Development Team:**
- Instrument code with Micrometer metrics
- Use structured logging (JSON format)
- Include trace_id in logs for correlation
- Understand Spring Boot Actuator endpoints
- Design dashboards for key business metrics

**Documentation:**
- Create runbooks for common alert scenarios
- Document dashboard usage and interpretation
- Provide Kibana query examples for troubleshooting
- Map mainframe monitoring concepts to cloud equivalents

### Performance Baselines

**Establish Baselines from Mainframe:**
- Transaction response times (target: maintain sub-200ms from mainframe)
- Batch job durations (target: match or improve 4-hour window)
- Transaction throughput (target: match or exceed 10,000 TPS)
- Error rates (target: maintain current low error rates)

**Continuous Comparison:**
- Run both systems in parallel during migration
- Compare metrics side-by-side in Grafana
- Identify and resolve performance regressions
- Validate SLA compliance before cutover

---

## Additional Resources

### Official Documentation

- [Prometheus Documentation](https://prometheus.io/docs/)
- [Grafana Documentation](https://grafana.com/docs/grafana/latest/)
- [AWS Elasticsearch Documentation](https://docs.aws.amazon.com/elasticsearch-service/)
- [CloudWatch Documentation](https://docs.aws.amazon.com/cloudwatch/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Micrometer Documentation](https://micrometer.io/docs)

### Helm Charts

- [Prometheus Community Helm Charts](https://github.com/prometheus-community/helm-charts)
- [Grafana Helm Chart](https://github.com/grafana/helm-charts)
- [Elastic Stack Helm Charts](https://github.com/elastic/helm-charts)

### Related Modules

- [VPC Module](../vpc/README.md) - Network infrastructure for monitoring components
- [EKS Module](../eks/README.md) - Kubernetes cluster configuration
- [RDS Module](../rds/README.md) - Database monitoring integration

### Support

For issues or questions related to this monitoring module:
1. Check the [Troubleshooting](#troubleshooting) section above
2. Review [GitHub Issues](https://github.com/your-org/carddemo/issues)
3. Contact DevOps team: devops@example.com
4. Join Slack channel: #carddemo-monitoring

---

**Module Version:** 1.0.0  
**Last Updated:** 2024-01-15  
**Maintained By:** DevOps Team
