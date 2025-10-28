# RDS PostgreSQL Module

## Overview

This Terraform module provisions an Amazon RDS PostgreSQL 16.6 database instance to replace the mainframe VSAM KSDS (Key-Sequenced Data Sets) file storage system. This module is a critical component of the CardDemo application migration from IBM z/OS mainframe to cloud-native architecture.

### Purpose

The RDS PostgreSQL database replaces 11 VSAM datasets that stored credit card management data on the mainframe:

| Original VSAM Dataset | Target PostgreSQL Table | Purpose |
|----------------------|-------------------------|---------|
| ACCTFILE | account | Account master records |
| CARDFILE | card | Credit card master records |
| CUSTFILE | customer | Customer master records |
| TRANSACT | transaction | Online transaction records |
| DALYTRAN | daily_transaction | Daily transaction batch records |
| TCATBAL | transaction_category_balance | Transaction category balances |
| DISCGRP | disclosure_group | Disclosure group reference data |
| TRANCATG | transaction_category | Transaction category codes |
| TRANTYPE | transaction_type | Transaction type codes |
| USRSEC | user_security | User security and authentication |
| XREFFILE | card_account_xref | Card-account cross-reference |

This migration maintains exact functional equivalence while providing cloud-native benefits including automated backups, high availability, scalability, and managed operations.

## Features

### High Availability
- **Multi-AZ Deployment**: Automatic synchronous replication to standby instance in separate Availability Zone
- **99.95% Availability SLA**: AWS-managed failover with 60-120 second recovery time
- **Automated Failover**: Transparent failover to standby without application code changes

### Backup and Recovery
- **Automated Daily Backups**: Configurable retention period (7-35 days)
- **Point-in-Time Recovery**: Restore to any second within the backup retention period
- **5-Minute Transaction Log Backups**: Minimal data loss (RPO = 5 minutes)
- **Manual Snapshot Support**: On-demand snapshots before major changes
- **Cross-Region Snapshot Copy**: Geographic redundancy for disaster recovery

### Performance Optimization
- **OLTP-Optimized Parameter Groups**: Custom PostgreSQL configurations matching VSAM KSDS access patterns
- **Sub-10ms Primary Key Lookups**: B-tree indexes replicating VSAM key access performance
- **Connection Pooling**: Support for high-concurrency workloads (10,000+ TPS)
- **Read Replicas**: Optional read replicas for reporting and analytics workloads
- **SSD-Backed Storage**: General Purpose (gp3) or Provisioned IOPS (io1) storage options

### Security
- **Encryption at Rest**: AWS KMS customer-managed keys for data encryption
- **Encryption in Transit**: SSL/TLS enforced for all connections
- **Private Subnet Placement**: No public internet accessibility
- **Security Group Restrictions**: Port 5432 access limited to application tier only
- **IAM Database Authentication**: Optional password-less authentication via IAM
- **AWS Secrets Manager Integration**: Automated credential rotation

### Monitoring and Observability
- **Enhanced Monitoring**: 60-second interval OS-level metrics (CPU, memory, I/O, swap)
- **Performance Insights**: Query-level performance analysis and slow query identification
- **CloudWatch Integration**: Automated metrics for CPU, memory, connections, latency, throughput
- **CloudWatch Alarms**: Configurable alerts for critical thresholds
- **Log Exports**: PostgreSQL and upgrade logs exported to CloudWatch Logs

### Operational Excellence
- **Automated Minor Version Upgrades**: Configurable automatic patching during maintenance windows
- **Storage Autoscaling**: Automatic storage expansion to prevent out-of-space conditions
- **Parameter Group Management**: Hot-reload for many configuration changes without downtime
- **Maintenance Windows**: Scheduled maintenance during low-traffic periods
- **Zero-Downtime Scaling**: Modify instance class with minimal application impact

## Usage

### Basic Example

```hcl
module "carddemo_rds" {
  source = "./modules/rds"

  # Database Configuration
  identifier        = "carddemo-postgres"
  engine_version    = "16.6"
  instance_class    = "db.r6g.xlarge"
  allocated_storage = 200
  storage_type      = "gp3"
  
  # Database Credentials
  database_name   = "carddemo"
  master_username = "carddemo_admin"
  
  # Network Configuration
  vpc_id             = module.vpc.vpc_id
  subnet_ids         = module.vpc.private_subnet_ids
  allowed_cidr_blocks = []
  allowed_security_groups = [module.eks.worker_security_group_id]
  
  # High Availability
  multi_az = true
  
  # Backup Configuration
  backup_retention_period = 7
  backup_window          = "03:00-04:00"
  maintenance_window     = "sun:04:00-sun:05:00"
  
  # Monitoring
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  performance_insights_enabled    = true
  monitoring_interval            = 60
  
  # Security
  storage_encrypted = true
  kms_key_id       = module.kms.key_arn
  
  # Tags
  tags = {
    Environment = "production"
    Project     = "CardDemo"
    ManagedBy   = "Terraform"
    CostCenter  = "IT-Infrastructure"
  }
}
```

### Production Example with Read Replica

```hcl
module "carddemo_rds_primary" {
  source = "./modules/rds"

  identifier        = "carddemo-postgres-primary"
  engine_version    = "16.6"
  instance_class    = "db.r6g.2xlarge"
  allocated_storage = 500
  storage_type      = "io1"
  iops             = 10000
  
  database_name   = "carddemo"
  master_username = "carddemo_admin"
  
  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnet_ids
  allowed_security_groups = [
    module.eks.worker_security_group_id,
    module.batch_runner.security_group_id
  ]
  
  multi_az                = true
  backup_retention_period = 14
  backup_window          = "03:00-04:00"
  maintenance_window     = "sun:04:00-sun:05:00"
  
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  performance_insights_enabled    = true
  performance_insights_retention_period = 7
  monitoring_interval            = 60
  
  storage_encrypted = true
  kms_key_id       = module.kms.key_arn
  
  # Storage autoscaling
  max_allocated_storage = 1000
  
  # Parameter group customization
  parameter_group_family = "postgres16"
  parameters = [
    {
      name  = "shared_buffers"
      value = "8GB"
    },
    {
      name  = "effective_cache_size"
      value = "24GB"
    },
    {
      name  = "work_mem"
      value = "64MB"
    },
    {
      name  = "maintenance_work_mem"
      value = "2GB"
    }
  ]
  
  tags = {
    Environment = "production"
    Project     = "CardDemo"
    Tier        = "primary"
  }
}

# Read Replica for Reporting
module "carddemo_rds_replica" {
  source = "./modules/rds"

  identifier         = "carddemo-postgres-replica"
  replicate_source_db = module.carddemo_rds_primary.db_instance_id
  
  instance_class = "db.r6g.xlarge"
  
  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnet_ids
  allowed_security_groups = [module.reporting.security_group_id]
  
  backup_retention_period = 0  # Backups managed by primary
  
  performance_insights_enabled = true
  monitoring_interval         = 60
  
  tags = {
    Environment = "production"
    Project     = "CardDemo"
    Tier        = "replica"
    Purpose     = "reporting"
  }
}
```

## Input Variables

| Name | Description | Type | Default | Required | Constraints |
|------|-------------|------|---------|----------|-------------|
| `identifier` | Unique identifier for the RDS instance | `string` | n/a | yes | Lowercase alphanumeric and hyphens only |
| `engine_version` | PostgreSQL engine version | `string` | `"16.6"` | no | Must be a valid PostgreSQL 16.x version |
| `instance_class` | RDS instance type | `string` | `"db.r6g.xlarge"` | no | See AWS RDS instance types |
| `allocated_storage` | Initial allocated storage in GB | `number` | `200` | no | Minimum 20 GB for gp3 |
| `max_allocated_storage` | Maximum storage for autoscaling (0 = disabled) | `number` | `0` | no | Must be greater than allocated_storage |
| `storage_type` | Storage type (gp3, gp2, io1) | `string` | `"gp3"` | no | gp3 recommended for production |
| `iops` | Provisioned IOPS (required for io1) | `number` | `null` | no | 1000-256000 for io1 |
| `storage_throughput` | Storage throughput in MB/s (gp3 only) | `number` | `null` | no | 125-1000 MB/s |
| `database_name` | Name of the initial database | `string` | `"carddemo"` | no | Alphanumeric and underscores only |
| `master_username` | Master database username | `string` | `"carddemo_admin"` | no | Cannot be 'postgres' or reserved words |
| `master_password` | Master password (leave null to auto-generate) | `string` | `null` | no | Min 8 characters, complexity required |
| `port` | Database port | `number` | `5432` | no | Default PostgreSQL port |
| `vpc_id` | VPC ID for security group creation | `string` | n/a | yes | Must be valid VPC ID |
| `subnet_ids` | List of subnet IDs for DB subnet group | `list(string)` | n/a | yes | Minimum 2 subnets in different AZs |
| `allowed_cidr_blocks` | CIDR blocks allowed to connect | `list(string)` | `[]` | no | Use allowed_security_groups instead |
| `allowed_security_groups` | Security group IDs allowed to connect | `list(string)` | `[]` | no | Recommended over CIDR blocks |
| `publicly_accessible` | Allow public internet access | `bool` | `false` | no | Must be false for production |
| `multi_az` | Enable multi-AZ deployment | `bool` | `true` | no | Strongly recommended for production |
| `availability_zone` | AZ for single-AZ deployment | `string` | `null` | no | Only if multi_az = false |
| `backup_retention_period` | Backup retention in days | `number` | `7` | no | 7-35 days; 0 disables backups |
| `backup_window` | Daily backup window (UTC) | `string` | `"03:00-04:00"` | no | Format: HH:MM-HH:MM |
| `maintenance_window` | Weekly maintenance window (UTC) | `string` | `"sun:04:00-sun:05:00"` | no | Format: ddd:HH:MM-ddd:HH:MM |
| `skip_final_snapshot` | Skip final snapshot on deletion | `bool` | `false` | no | Should be false for production |
| `final_snapshot_identifier` | Name of final snapshot | `string` | `null` | no | Auto-generated if null |
| `copy_tags_to_snapshot` | Copy tags to snapshots | `bool` | `true` | no | Recommended for governance |
| `storage_encrypted` | Enable encryption at rest | `bool` | `true` | no | Required for production |
| `kms_key_id` | KMS key ARN for encryption | `string` | `null` | no | Uses default aws/rds if null |
| `iam_database_authentication_enabled` | Enable IAM auth | `bool` | `false` | no | Optional passwordless auth |
| `enabled_cloudwatch_logs_exports` | Log types to export | `list(string)` | `["postgresql", "upgrade"]` | no | Available: postgresql, upgrade |
| `monitoring_interval` | Enhanced monitoring interval (seconds) | `number` | `60` | no | 0, 1, 5, 10, 15, 30, 60 |
| `monitoring_role_arn` | IAM role for enhanced monitoring | `string` | `null` | no | Auto-created if null and monitoring enabled |
| `performance_insights_enabled` | Enable Performance Insights | `bool` | `true` | no | Recommended for production |
| `performance_insights_retention_period` | Performance Insights retention (days) | `number` | `7` | no | 7 (free tier) or 731 (paid) |
| `performance_insights_kms_key_id` | KMS key for Performance Insights | `string` | `null` | no | Uses default key if null |
| `auto_minor_version_upgrade` | Enable automatic minor version upgrades | `bool` | `true` | no | Recommended for security patches |
| `allow_major_version_upgrade` | Allow major version upgrades | `bool` | `false` | no | Requires manual testing |
| `apply_immediately` | Apply changes immediately | `bool` | `false` | no | Use with caution in production |
| `deletion_protection` | Enable deletion protection | `bool` | `true` | no | Must be true for production |
| `replicate_source_db` | Source DB identifier for read replica | `string` | `null` | no | Only for read replicas |
| `snapshot_identifier` | Snapshot to restore from | `string` | `null` | no | For point-in-time recovery |
| `parameter_group_name` | Custom parameter group name | `string` | `null` | no | Auto-created if null |
| `parameter_group_family` | Parameter group family | `string` | `"postgres16"` | no | Must match engine version |
| `parameters` | Custom database parameters | `list(object)` | `[]` | no | See performance tuning section |
| `option_group_name` | Option group name | `string` | `null` | no | Rarely needed for PostgreSQL |
| `db_subnet_group_name` | Custom DB subnet group name | `string` | `null` | no | Auto-created if null |
| `security_group_name` | Custom security group name | `string` | `null` | no | Auto-created if null |
| `tags` | Resource tags | `map(string)` | `{}` | no | Standard tagging |

## Outputs

| Name | Description | Example Usage |
|------|-------------|---------------|
| `db_instance_id` | RDS instance identifier | Reference for read replicas |
| `db_instance_arn` | RDS instance ARN | IAM policy references |
| `db_instance_endpoint` | Connection endpoint (host:port) | `jdbc:postgresql://${output}/${db_name}` |
| `db_instance_address` | Connection hostname | Spring Boot datasource.url |
| `db_instance_port` | Connection port | Spring Boot datasource.port |
| `db_instance_name` | Database name | Spring Boot datasource.database |
| `db_instance_username` | Master username | Spring Boot datasource.username |
| `db_instance_resource_id` | Resource ID for Performance Insights | CloudWatch dashboard references |
| `db_instance_status` | Instance status | Monitoring and health checks |
| `db_instance_availability_zone` | Primary AZ location | Network planning |
| `db_instance_multi_az` | Multi-AZ configuration status | Verification |
| `db_subnet_group_id` | DB subnet group ID | Reference for other resources |
| `db_subnet_group_arn` | DB subnet group ARN | IAM policy references |
| `db_parameter_group_id` | Parameter group ID | Reference for modifications |
| `db_parameter_group_arn` | Parameter group ARN | IAM policy references |
| `db_security_group_id` | Security group ID | Additional ingress rules |
| `db_security_group_arn` | Security group ARN | IAM policy references |
| `monitoring_role_arn` | Enhanced monitoring IAM role ARN | Reference for other RDS instances |
| `kms_key_id` | Encryption KMS key ID | Reference for read replicas |
| `backup_retention_period` | Configured backup retention | Documentation |
| `backup_window` | Configured backup window | Maintenance planning |
| `maintenance_window` | Configured maintenance window | Change scheduling |
| `performance_insights_enabled` | Performance Insights status | Monitoring setup verification |
| `cloudwatch_log_groups` | CloudWatch log group names | Log aggregation configuration |
| `jdbc_connection_string` | Complete JDBC connection string | Spring Boot application.yml |
| `connection_parameters` | Map of connection parameters | Application configuration |

### Example: Spring Boot Configuration

Use the module outputs in your Spring Boot `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${module.carddemo_rds.db_instance_endpoint}/${module.carddemo_rds.db_instance_name}?ssl=true&sslmode=require
    username: ${module.carddemo_rds.db_instance_username}
    password: ${aws_secretsmanager_secret_version.db_password.secret_string}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

## Architecture

### Network Topology

```
┌─────────────────────────────────────────────────────────────────┐
│                         AWS Region                               │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │                      VPC (10.0.0.0/16)                     │ │
│  │                                                            │ │
│  │  ┌──────────────────────┐  ┌──────────────────────┐      │ │
│  │  │  Availability Zone A │  │  Availability Zone B │      │ │
│  │  │                      │  │                      │      │ │
│  │  │  ┌────────────────┐ │  │  ┌────────────────┐ │      │ │
│  │  │  │ Private Subnet │ │  │  │ Private Subnet │ │      │ │
│  │  │  │ 10.0.10.0/24   │ │  │  │ 10.0.11.0/24   │ │      │ │
│  │  │  │                │ │  │  │                │ │      │ │
│  │  │  │  EKS Worker    │ │  │  │  EKS Worker    │ │      │ │
│  │  │  │  Nodes         │ │  │  │  Nodes         │ │      │ │
│  │  │  │  [App Tier]    │ │  │  │  [App Tier]    │ │      │ │
│  │  │  └────────┬───────┘ │  │  └────────┬───────┘ │      │ │
│  │  │           │          │  │           │          │      │ │
│  │  │           │ Port     │  │           │ Port     │      │ │
│  │  │           │ 5432     │  │           │ 5432     │      │ │
│  │  │           ▼          │  │           ▼          │      │ │
│  │  │  ┌────────────────┐ │  │  ┌────────────────┐ │      │ │
│  │  │  │ Database       │ │  │  │ Database       │ │      │ │
│  │  │  │ Subnet         │ │  │  │ Subnet         │ │      │ │
│  │  │  │ 10.0.20.0/24   │ │  │  │ 10.0.21.0/24   │ │      │ │
│  │  │  │                │ │  │  │                │ │      │ │
│  │  │  │ ┌────────────┐ │ │  │  │ ┌────────────┐ │      │ │
│  │  │  │ │ RDS Primary│ │ │  │  │ │RDS Standby │ │      │ │
│  │  │  │ │ (Master)   │◄┼─┼──┼──┼▶│  (Multi-AZ)│ │      │ │
│  │  │  │ └────────────┘ │ │  │  │ └────────────┘ │      │ │
│  │  │  │                │ │  │  │                │ │      │ │
│  │  │  └────────────────┘ │  │  └────────────────┘ │      │ │
│  │  └──────────────────────┘  └──────────────────────┘      │ │
│  │                                                            │ │
│  │  [Security Group: carddemo-rds-sg]                        │ │
│  │  Ingress: Port 5432 from EKS Security Group               │ │
│  │  Egress: None required                                    │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                   │
│  [AWS KMS] ──Encryption at Rest──> [RDS Storage]                │
│  [CloudWatch] ◄──Metrics & Logs─── [RDS Instance]               │
│  [S3] ◄──Automated Backups────────┘                             │
└─────────────────────────────────────────────────────────────────┘
```

### High Availability Architecture

- **Primary Instance**: Active RDS instance in Availability Zone A processing all read and write operations
- **Standby Replica**: Synchronous replica in Availability Zone B receiving continuous replication
- **Automatic Failover**: AWS manages failover detection (60-120 seconds) with DNS update
- **DB Subnet Group**: Spans multiple AZs allowing failover to any configured zone
- **Connection String Stability**: Applications use single endpoint; AWS handles DNS failover

### Security Group Configuration

```hcl
# Ingress Rule
Type: PostgreSQL
Protocol: TCP
Port: 5432
Source: EKS Worker Security Group (sg-xxxxx)
Description: Allow PostgreSQL access from CardDemo application tier

# Egress Rules
No egress rules required (database is target, not source)
```

## VSAM-to-PostgreSQL Migration Mapping

### Dataset Structure Comparison

This section documents the transformation of VSAM KSDS file structures to PostgreSQL relational tables, maintaining functional equivalence while leveraging relational database capabilities.

#### ACCTFILE → account Table

**Original VSAM Structure:**
```
DEFINE CLUSTER (NAME(ACCTFILE) -
  INDEXED -
  KEYS(11,0) -
  RECORDSIZE(300,300) -
  FREESPACE(20,10))
```

**PostgreSQL Table:**
```sql
CREATE TABLE account (
    acct_id BIGINT PRIMARY KEY,                    -- VSAM Primary Key (11 bytes → 8 bytes numeric)
    acct_active_status CHAR(1) NOT NULL,
    acct_curr_bal NUMERIC(12,2) NOT NULL DEFAULT 0.00,
    acct_credit_limit NUMERIC(12,2) NOT NULL,
    acct_cash_credit_limit NUMERIC(12,2) NOT NULL,
    acct_open_date DATE NOT NULL,
    acct_expiration_date DATE,
    acct_reissue_date DATE,
    acct_curr_cyc_credit NUMERIC(12,2) DEFAULT 0.00,
    acct_curr_cyc_debit NUMERIC(12,2) DEFAULT 0.00,
    acct_addr_zip VARCHAR(10),
    acct_group_id VARCHAR(10),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 0                       -- Optimistic locking (replaces VSAM RBA check)
);

CREATE INDEX idx_account_status ON account(acct_active_status);
CREATE INDEX idx_account_group ON account(acct_group_id);
```

**Migration Notes:**
- VSAM COMP-3 fields → PostgreSQL NUMERIC with exact precision preservation
- Primary key maintains same logical length (11 digits)
- Added version column for JPA optimistic locking (replaces VSAM RBA checking)
- Added timestamps for audit trail (not present in VSAM)
- Indexes replicate VSAM alternate index performance

#### CARDFILE → card Table

**Original VSAM Structure:**
```
DEFINE CLUSTER (NAME(CARDFILE) -
  INDEXED -
  KEYS(16,0) -
  RECORDSIZE(150,150))
```

**PostgreSQL Table:**
```sql
CREATE TABLE card (
    card_num VARCHAR(16) PRIMARY KEY,              -- VSAM Primary Key (16-char card number)
    card_acct_id BIGINT NOT NULL REFERENCES account(acct_id),
    card_cardmember_id BIGINT NOT NULL,
    card_status CHAR(1) NOT NULL,
    card_embossed_name VARCHAR(50),
    card_expiration_date DATE NOT NULL,
    card_active_date DATE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 0
);

CREATE INDEX idx_card_acct ON card(card_acct_id);
CREATE INDEX idx_card_status ON card(card_status);
```

**Migration Notes:**
- Foreign key constraint enforces referential integrity (not available in VSAM)
- Card number remains VARCHAR for leading zeros preservation
- Indexes on frequently queried fields (acct_id, status)

#### CUSTFILE → customer Table

**Original VSAM Structure:**
```
DEFINE CLUSTER (NAME(CUSTFILE) -
  INDEXED -
  KEYS(9,0) -
  RECORDSIZE(500,500))
```

**PostgreSQL Table:**
```sql
CREATE TABLE customer (
    cust_id BIGINT PRIMARY KEY,
    cust_first_name VARCHAR(25) NOT NULL,
    cust_middle_name VARCHAR(25),
    cust_last_name VARCHAR(25) NOT NULL,
    cust_addr_line_1 VARCHAR(50),
    cust_addr_line_2 VARCHAR(50),
    cust_addr_line_3 VARCHAR(50),
    cust_addr_state_cd VARCHAR(2),
    cust_addr_country_cd VARCHAR(3),
    cust_addr_zip VARCHAR(10),
    cust_phone_num_1 VARCHAR(15),
    cust_phone_num_2 VARCHAR(15),
    cust_ssn VARCHAR(9),
    cust_govt_issued_id VARCHAR(20),
    cust_dob_yyyy_mm_dd DATE,
    cust_fico_credit_score INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 0
);

CREATE INDEX idx_customer_ssn ON customer(cust_ssn);
CREATE INDEX idx_customer_name ON customer(cust_last_name, cust_first_name);
```

**Migration Notes:**
- Large 500-byte VSAM record normalized into discrete fields
- Composite index on name for search performance
- SSN index for lookup operations (with appropriate security controls)

#### TRANSACT → transaction Table

**Original VSAM Structure:**
```
DEFINE CLUSTER (NAME(TRANSACT) -
  INDEXED -
  KEYS(16,0) -
  RECORDSIZE(350,350))
```

**PostgreSQL Table:**
```sql
CREATE TABLE transaction (
    trans_id VARCHAR(16) PRIMARY KEY,
    trans_card_num VARCHAR(16) NOT NULL REFERENCES card(card_num),
    trans_type_cd VARCHAR(2) NOT NULL,
    trans_cat_cd INTEGER NOT NULL,
    trans_source VARCHAR(10),
    trans_desc VARCHAR(100),
    trans_amt NUMERIC(12,2) NOT NULL,
    trans_merchant_id VARCHAR(9),
    trans_merchant_name VARCHAR(50),
    trans_merchant_city VARCHAR(50),
    trans_merchant_zip VARCHAR(10),
    trans_orig_ts TIMESTAMP NOT NULL,
    trans_proc_ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 0
);

CREATE INDEX idx_transaction_card ON transaction(trans_card_num);
CREATE INDEX idx_transaction_date ON transaction(trans_orig_ts);
CREATE INDEX idx_transaction_type ON transaction(trans_type_cd);
```

**Migration Notes:**
- High-volume table requiring optimized indexes for query performance
- Date/time index critical for transaction history queries
- Foreign key to card table ensures referential integrity
- NUMERIC(12,2) for currency maintains COBOL COMP-3 precision

#### Reference Tables

**TRANTYPE → transaction_type Table:**
```sql
CREATE TABLE transaction_type (
    trans_type_cd VARCHAR(2) PRIMARY KEY,
    trans_type_desc VARCHAR(50) NOT NULL
);
```

**TRANCATG → transaction_category Table:**
```sql
CREATE TABLE transaction_category (
    trans_cat_cd INTEGER PRIMARY KEY,
    trans_cat_desc VARCHAR(50) NOT NULL
);
```

**DISCGRP → disclosure_group Table:**
```sql
CREATE TABLE disclosure_group (
    disc_group_id VARCHAR(10) PRIMARY KEY,
    disc_group_desc VARCHAR(100)
);
```

**USRSEC → user_security Table:**
```sql
CREATE TABLE user_security (
    user_id VARCHAR(8) PRIMARY KEY,
    user_pwd_hash VARCHAR(100) NOT NULL,           -- BCrypt hash (COBOL had plain text)
    user_first_name VARCHAR(25),
    user_last_name VARCHAR(25),
    user_type CHAR(1) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_ts TIMESTAMP,
    version INTEGER DEFAULT 0
);

CREATE INDEX idx_user_type ON user_security(user_type);
```

**Migration Notes:**
- Passwords migrated from plain text to BCrypt hashed values
- Added last_login_ts for security auditing

### Data Type Conversion Rules

| COBOL/VSAM Type | PostgreSQL Type | Notes |
|-----------------|-----------------|-------|
| PIC 9(n) | BIGINT or INTEGER | Depends on size: n≤9 → INTEGER, n>9 → BIGINT |
| PIC S9(n)V99 COMP-3 | NUMERIC(n+2, 2) | Maintains exact precision for currency |
| PIC X(n) | VARCHAR(n) | Character data, trimmed on read |
| PIC 9(8) (date CCYYMMDD) | DATE | Converted to ISO format YYYY-MM-DD |
| PIC X(10) (date YYYY-MM-DD) | DATE | Direct mapping |
| COMP-3 (packed decimal) | NUMERIC | Scale and precision preserved exactly |
| 88-level conditions | CHAR(1) + CHECK constraint | Enumerated values |
| OCCURS n TIMES | Array or separate table | Depends on data model |

### Index Strategy

**Primary Key Indexes:**
- All VSAM primary keys (KEYS parameter) become PostgreSQL PRIMARY KEY constraints
- B-tree indexes automatically created for primary keys
- Performance target: sub-10ms primary key lookups (matching VSAM KSDS)

**Alternate Index Replication:**
```sql
-- Replicate VSAM alternate indexes for account lookup by status
CREATE INDEX idx_account_status ON account(acct_active_status);

-- Replicate VSAM alternate index for card lookup by account
CREATE INDEX idx_card_acct ON card(card_acct_id);

-- Composite index for transaction date range queries (common in batch jobs)
CREATE INDEX idx_transaction_card_date ON transaction(trans_card_num, trans_orig_ts);
```

**Performance Validation:**
```sql
-- Verify index usage with EXPLAIN ANALYZE
EXPLAIN ANALYZE SELECT * FROM account WHERE acct_id = 123456789;
-- Expected: Index Scan using account_pkey, execution time < 10ms

EXPLAIN ANALYZE SELECT * FROM transaction 
WHERE trans_card_num = '4111111111111111' 
AND trans_orig_ts >= '2024-01-01' 
AND trans_orig_ts < '2024-02-01';
-- Expected: Index Scan using idx_transaction_card_date, execution time < 50ms
```

## Performance Tuning

### PostgreSQL Parameter Group Configuration

The custom parameter group is optimized for OLTP workloads matching VSAM KSDS access patterns. The goal is to maintain sub-200ms transaction response times and support 10,000+ TPS throughput.

#### Memory Configuration

```hcl
parameters = [
  {
    name  = "shared_buffers"
    value = "8GB"  # 25% of instance RAM for db.r6g.xlarge (32 GB)
  },
  {
    name  = "effective_cache_size"
    value = "24GB"  # 75% of instance RAM (query planner estimation)
  },
  {
    name  = "work_mem"
    value = "64MB"  # Per-operation memory for sorts and hash joins
  },
  {
    name  = "maintenance_work_mem"
    value = "2GB"  # Memory for VACUUM, CREATE INDEX
  }
]
```

**Rationale:**
- `shared_buffers` = 8GB provides hot data caching equivalent to VSAM buffer pools
- `effective_cache_size` = 24GB helps query planner make optimal index decisions
- `work_mem` = 64MB allows efficient in-memory sorts for transaction queries
- `maintenance_work_mem` = 2GB speeds up index creation and vacuum operations

#### Checkpoint and WAL Configuration

```hcl
parameters = [
  {
    name  = "checkpoint_completion_target"
    value = "0.9"  # Spread checkpoint I/O over 90% of checkpoint interval
  },
  {
    name  = "wal_buffers"
    value = "16MB"  # Write-ahead log buffer size
  },
  {
    name  = "min_wal_size"
    value = "2GB"  # Minimum WAL size to retain
  },
  {
    name  = "max_wal_size"
    value = "8GB"  # Trigger checkpoint if WAL exceeds this
  }
]
```

**Rationale:**
- Smooth checkpoint I/O prevents performance spikes during writes
- Adequate WAL sizing supports high transaction throughput
- Matches mainframe transaction log management patterns

#### Query Planning and Execution

```hcl
parameters = [
  {
    name  = "random_page_cost"
    value = "1.1"  # SSD-optimized (default 4.0 for spinning disks)
  },
  {
    name  = "effective_io_concurrency"
    value = "200"  # Parallel I/O operations for SSDs
  },
  {
    name  = "default_statistics_target"
    value = "100"  # Histogram buckets for query planning
  },
  {
    name  = "max_parallel_workers_per_gather"
    value = "4"  # Parallel query workers
  }
]
```

**Rationale:**
- `random_page_cost` = 1.1 tells planner that random access on SSD is nearly as fast as sequential
- Optimizes index usage decisions (prefer index scans over sequential scans)
- Matches VSAM KSDS random access performance characteristics

#### Connection and Resource Limits

```hcl
parameters = [
  {
    name  = "max_connections"
    value = "200"  # Maximum concurrent connections
  },
  {
    name  = "statement_timeout"
    value = "300000"  # 5 minutes (300,000 ms)
  },
  {
    name  = "idle_in_transaction_session_timeout"
    value = "600000"  # 10 minutes (600,000 ms)
  }
]
```

**Rationale:**
- `max_connections` = 200 supports high-concurrency application tier (10-20 connections per pod, 10 pods)
- Statement timeout prevents runaway queries
- Idle transaction timeout prevents connection pool exhaustion

#### Logging and Monitoring

```hcl
parameters = [
  {
    name  = "log_min_duration_statement"
    value = "1000"  # Log queries taking > 1 second
  },
  {
    name  = "log_connections"
    value = "1"  # Log connection attempts
  },
  {
    name  = "log_disconnections"
    value = "1"  # Log disconnections
  },
  {
    name  = "log_lock_waits"
    value = "1"  # Log lock waits
  },
  {
    name  = "log_statement"
    value = "ddl"  # Log all DDL statements (CREATE, ALTER, DROP)
  }
]
```

**Rationale:**
- Captures slow queries for optimization
- Connection logging for security auditing
- DDL logging for change tracking

#### SSL/TLS Enforcement

```hcl
parameters = [
  {
    name  = "rds.force_ssl"
    value = "1"  # Enforce SSL for all connections
  },
  {
    name  = "ssl_min_protocol_version"
    value = "TLSv1.2"  # Minimum TLS version
  }
]
```

**Rationale:**
- Enforces encryption in transit for all database connections
- Meets compliance requirements for sensitive financial data

### Instance Sizing Recommendations

| Environment | Instance Class | vCPU | RAM | Network | Storage | Est. Monthly Cost |
|-------------|----------------|------|-----|---------|---------|-------------------|
| Development | db.t3.large | 2 | 8 GB | Up to 5 Gbps | gp3 100 GB | ~$150 |
| Test/Staging | db.r6g.large | 2 | 16 GB | Up to 10 Gbps | gp3 200 GB | ~$250 |
| Production | db.r6g.xlarge | 4 | 32 GB | Up to 10 Gbps | gp3 500 GB | ~$600 |
| High-Load Production | db.r6g.2xlarge | 8 | 64 GB | Up to 10 Gbps | io1 1TB 10K IOPS | ~$1,800 |

**Sizing Criteria:**
- **Development**: Single-AZ, minimal monitoring, sufficient for developer testing
- **Test/Staging**: Single-AZ, full monitoring, production-like for integration testing
- **Production**: Multi-AZ, full monitoring, sized for 10,000 TPS peak load
- **High-Load**: Multi-AZ, provisioned IOPS, sized for sustained peak with headroom

### Performance Benchmarking

**Target Performance Metrics (Must Match or Exceed Mainframe):**

| Metric | Mainframe VSAM | PostgreSQL Target | Validation Method |
|--------|----------------|-------------------|-------------------|
| Primary Key Lookup | < 10ms | < 10ms | `EXPLAIN ANALYZE SELECT * FROM account WHERE acct_id = ?` |
| Transaction Insert | < 20ms | < 20ms | `EXPLAIN ANALYZE INSERT INTO transaction VALUES (...)` |
| Account Balance Update | < 50ms | < 50ms | `EXPLAIN ANALYZE UPDATE account SET acct_curr_bal = ? WHERE acct_id = ?` |
| Transaction History Query | < 100ms | < 100ms | `EXPLAIN ANALYZE SELECT * FROM transaction WHERE trans_card_num = ? AND trans_orig_ts BETWEEN ? AND ?` |
| Peak Throughput | 10,000 TPS | 10,000+ TPS | JMeter load test, 5-minute sustained load |
| Batch Processing | 4-hour window | 4-hour window | Spring Batch job execution monitoring |

**Performance Testing Procedure:**
1. Load production-equivalent data volume (50K accounts, 100K cards, 1M transactions)
2. Execute JMeter test scripts simulating transaction mix (70% reads, 30% writes)
3. Monitor CloudWatch metrics during test (CPUUtilization, WriteLatency, ReadLatency)
4. Analyze Performance Insights for query optimization opportunities
5. Adjust parameter group settings and retest until targets met

## Security Considerations

### Encryption

#### Encryption at Rest

```hcl
module "carddemo_rds" {
  source = "./modules/rds"
  
  # Enable encryption with customer-managed KMS key
  storage_encrypted = true
  kms_key_id       = module.kms.key_arn
  
  # Also encrypt Performance Insights data
  performance_insights_enabled    = true
  performance_insights_kms_key_id = module.kms.key_arn
}
```

**Security Benefits:**
- All database storage volumes encrypted with AES-256
- Automated backups encrypted with same KMS key
- Snapshots encrypted (cannot create unencrypted snapshot from encrypted DB)
- Read replicas inherit encryption from source
- Performance Insights query data encrypted at rest

**KMS Key Policy Requirements:**
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "Allow RDS to use the key",
      "Effect": "Allow",
      "Principal": {
        "Service": "rds.amazonaws.com"
      },
      "Action": [
        "kms:Decrypt",
        "kms:GenerateDataKey",
        "kms:CreateGrant"
      ],
      "Resource": "*",
      "Condition": {
        "StringEquals": {
          "kms:ViaService": "rds.us-east-1.amazonaws.com"
        }
      }
    }
  ]
}
```

#### Encryption in Transit

```hcl
parameters = [
  {
    name  = "rds.force_ssl"
    value = "1"  # Require SSL for all connections
  },
  {
    name  = "ssl_min_protocol_version"
    value = "TLSv1.2"  # Minimum TLS 1.2
  }
]
```

**Application Configuration:**
```yaml
# Spring Boot application.yml
spring:
  datasource:
    url: jdbc:postgresql://${rds_endpoint}/${db_name}?ssl=true&sslmode=require&sslrootcert=/path/to/rds-ca-bundle.pem
```

**SSL Certificate Verification:**
- Download RDS CA bundle: https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
- Configure application to verify server certificate
- Prevents man-in-the-middle attacks

### Network Security

#### Security Group Configuration

```hcl
# Restrict access to application tier only
module "carddemo_rds" {
  source = "./modules/rds"
  
  vpc_id                  = module.vpc.vpc_id
  subnet_ids              = module.vpc.private_subnet_ids
  allowed_security_groups = [
    module.eks.worker_security_group_id,      # EKS application pods
    module.batch_runner.security_group_id,    # Batch processing instances
    module.bastion.security_group_id          # Bastion host for admin access
  ]
  
  # Do NOT allow CIDR blocks (more restrictive with security groups)
  allowed_cidr_blocks = []
  
  # Never expose to public internet
  publicly_accessible = false
}
```

**Security Group Rules Created:**
```
Ingress:
  Type: PostgreSQL (TCP)
  Port: 5432
  Source: sg-eks-workers (EKS application tier)
  
  Type: PostgreSQL (TCP)
  Port: 5432
  Source: sg-batch-runner (batch processing)
  
  Type: PostgreSQL (TCP)
  Port: 5432
  Source: sg-bastion (administrative access)

Egress:
  None (database does not initiate outbound connections)
```

#### Subnet Placement

```hcl
# Place RDS in private subnets with no internet gateway route
subnet_ids = module.vpc.private_subnet_ids  # e.g., 10.0.20.0/24, 10.0.21.0/24

# Private subnets route table:
# 10.0.0.0/16 → local (VPC internal only)
# No 0.0.0.0/0 → igw-xxx route (no internet access)
```

**Network Isolation:**
- RDS instances in private subnets with no public IP
- No internet gateway route in subnet route table
- VPC endpoint for Systems Manager if bastion-less access needed
- All traffic stays within VPC

### Access Control

#### IAM Database Authentication (Optional)

```hcl
module "carddemo_rds" {
  source = "./modules/rds"
  
  iam_database_authentication_enabled = true
}
```

**IAM Policy for Application:**
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "rds-db:connect"
      ],
      "Resource": "arn:aws:rds-db:us-east-1:123456789012:dbuser:db-ABCDEFGHIJK/app_user"
    }
  ]
}
```

**Connection with IAM Auth:**
```bash
# Generate auth token (valid 15 minutes)
TOKEN=$(aws rds generate-db-auth-token \
  --hostname $DB_ENDPOINT \
  --port 5432 \
  --username app_user \
  --region us-east-1)

# Connect using token as password
psql "host=$DB_ENDPOINT port=5432 dbname=carddemo user=app_user password=$TOKEN sslmode=require"
```

#### AWS Secrets Manager Integration

```hcl
# Store master password in Secrets Manager
resource "aws_secretsmanager_secret" "db_password" {
  name                    = "carddemo/rds/master-password"
  recovery_window_in_days = 7
  
  tags = {
    Name        = "CardDemo RDS Master Password"
    Environment = "production"
  }
}

resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id     = aws_secretsmanager_secret.db_password.id
  secret_string = random_password.master.result
}

# Application retrieves password from Secrets Manager
resource "aws_iam_policy" "app_secrets_access" {
  name = "carddemo-secrets-access"
  
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue"
        ]
        Resource = aws_secretsmanager_secret.db_password.arn
      }
    ]
  })
}
```

**Credential Rotation:**
- Enable automatic rotation via Secrets Manager rotation Lambda
- 30-day rotation schedule recommended
- Application automatically receives new credentials via Secrets Manager API

### Audit Logging

#### Database Activity Logging

```hcl
parameters = [
  {
    name  = "log_connections"
    value = "1"  # Log all connection attempts
  },
  {
    name  = "log_disconnections"
    value = "1"  # Log all disconnections
  },
  {
    name  = "log_statement"
    value = "ddl"  # Log CREATE, ALTER, DROP statements
  },
  {
    name  = "log_min_duration_statement"
    value = "1000"  # Log slow queries (> 1 second)
  }
]

# Export logs to CloudWatch
enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
```

#### CloudWatch Logs Analysis

```bash
# Query failed connection attempts
aws logs filter-log-events \
  --log-group-name /aws/rds/instance/carddemo-postgres/postgresql \
  --filter-pattern "FATAL" \
  --start-time $(date -d '1 hour ago' +%s)000

# Query DDL changes
aws logs filter-log-events \
  --log-group-name /aws/rds/instance/carddemo-postgres/postgresql \
  --filter-pattern "CREATE OR ALTER OR DROP" \
  --start-time $(date -d '7 days ago' +%s)000
```

### Compliance Considerations

**PCI DSS Requirements:**
- Encryption at rest (Requirement 3.4): ✓ KMS encryption
- Encryption in transit (Requirement 4.1): ✓ TLS 1.2 enforcement
- Access control (Requirement 7): ✓ Security groups, IAM
- Audit logging (Requirement 10): ✓ CloudWatch Logs
- Network segmentation (Requirement 1): ✓ Private subnets, security groups

**SOC 2 Type II:**
- Change tracking (DDL logging): ✓ log_statement = 'ddl'
- Access monitoring (connection logs): ✓ log_connections = 1
- Encryption controls: ✓ KMS with customer-managed keys

## High Availability Architecture

### Multi-AZ Deployment

```hcl
module "carddemo_rds" {
  source = "./modules/rds"
  
  multi_az = true  # Enable multi-AZ for 99.95% availability
  
  # Subnet group must span multiple AZs
  subnet_ids = [
    "subnet-abc123",  # us-east-1a
    "subnet-def456",  # us-east-1b
    "subnet-ghi789"   # us-east-1c (optional 3rd AZ)
  ]
}
```

**Architecture Components:**

1. **Primary Instance**: 
   - Active database processing all read and write operations
   - Located in primary Availability Zone (e.g., us-east-1a)
   - Synchronously replicates to standby

2. **Standby Replica**:
   - Passive replica in different Availability Zone (e.g., us-east-1b)
   - Receives synchronous replication from primary
   - Not accessible for read queries (use read replica for that)
   - Automatically promoted during failover

3. **Automatic Failover**:
   - AWS detects primary failure (instance failure, AZ failure, storage failure)
   - Failover initiated automatically (no manual intervention)
   - DNS record updated to point to standby instance
   - Failover completion: 60-120 seconds typically

**Connection String Handling:**

```yaml
# Application uses single endpoint - AWS handles DNS failover
spring:
  datasource:
    url: jdbc:postgresql://carddemo-postgres.c123456789.us-east-1.rds.amazonaws.com:5432/carddemo
    
# Application connection pool handles transient errors during failover
    hikari:
      connection-timeout: 30000
      validation-timeout: 5000
      max-lifetime: 1800000  # Close connections after 30 min (before AWS 8-hour timeout)
```

**Failover Testing:**

```bash
# Test failover using AWS CLI
aws rds reboot-db-instance \
  --db-instance-identifier carddemo-postgres \
  --force-failover

# Monitor failover event
aws rds describe-events \
  --source-identifier carddemo-postgres \
  --source-type db-instance \
  --duration 60
```

### Backup Strategy

#### Automated Backups

```hcl
module "carddemo_rds" {
  source = "./modules/rds"
  
  backup_retention_period = 7   # 7-35 days (7 for dev, 14-35 for prod)
  backup_window          = "03:00-04:00"  # UTC, during low-traffic period
  
  # Prevent accidental deletion
  skip_final_snapshot        = false
  final_snapshot_identifier  = "carddemo-postgres-final-snapshot-${timestamp()}"
  copy_tags_to_snapshot      = true
  deletion_protection        = true
}
```

**Backup Behavior:**
- **Daily Automated Snapshots**: Full snapshot during backup window
- **Transaction Log Backups**: Every 5 minutes to S3
- **Retention**: Snapshots retained for specified period, then automatically deleted
- **Storage**: Stored in S3 (separate from instance storage, survives AZ failure)

#### Point-in-Time Recovery (PITR)

```bash
# Restore to specific timestamp (any second within retention period)
aws rds restore-db-instance-to-point-in-time \
  --source-db-instance-identifier carddemo-postgres \
  --target-db-instance-identifier carddemo-postgres-restored-2024-01-15-10-30 \
  --restore-time 2024-01-15T10:30:00Z \
  --vpc-security-group-ids sg-xxxxx \
  --db-subnet-group-name carddemo-db-subnet-group

# Restore to latest restorable time (most recent transaction log)
aws rds restore-db-instance-to-point-in-time \
  --source-db-instance-identifier carddemo-postgres \
  --target-db-instance-identifier carddemo-postgres-latest \
  --use-latest-restorable-time \
  --vpc-security-group-ids sg-xxxxx
```

#### Manual Snapshots

```bash
# Create manual snapshot before major changes
aws rds create-db-snapshot \
  --db-instance-identifier carddemo-postgres \
  --db-snapshot-identifier carddemo-postgres-before-schema-migration-2024-01-15

# Manual snapshots retained until explicitly deleted
# Recommended before: schema migrations, major version upgrades, config changes
```

### Disaster Recovery

#### Cross-Region Replication

```hcl
# Primary region (us-east-1)
module "carddemo_rds_primary" {
  source = "./modules/rds"
  
  providers = {
    aws = aws.primary
  }
  
  identifier = "carddemo-postgres-primary"
  # ... other configuration
}

# Copy automated snapshots to DR region (us-west-2)
resource "aws_db_snapshot_copy" "dr_snapshot" {
  provider = aws.dr
  
  source_db_snapshot_identifier = module.carddemo_rds_primary.latest_snapshot_arn
  target_db_snapshot_identifier = "carddemo-postgres-dr-copy-${formatdate("YYYY-MM-DD", timestamp())}"
  
  kms_key_id = module.kms_dr.key_arn  # Re-encrypt with DR region KMS key
  
  tags = {
    Purpose = "disaster-recovery"
    Region  = "us-west-2"
  }
}

# Automate snapshot copy with Lambda (triggered daily)
# Lambda function copies latest snapshot to DR region after daily backup completes
```

#### Recovery Time Objective (RTO) and Recovery Point Objective (RPO)

| Scenario | RTO Target | RPO Target | Recovery Procedure |
|----------|-----------|------------|-------------------|
| AZ Failure (Multi-AZ) | 1-2 minutes | 0 seconds | Automatic failover to standby |
| Regional Failure | 1-2 hours | 5 minutes | Restore from snapshot in DR region |
| Data Corruption | 1-2 hours | 5 minutes | Point-in-time recovery to pre-corruption timestamp |
| Accidental Deletion | 2-4 hours | 5 minutes | Restore from latest snapshot |

**DR Runbook - Regional Failure:**

1. **Detect Regional Outage**: CloudWatch alarms trigger for primary region unavailability
2. **Initiate DR Failover**: Execute DR runbook (manual decision for regional failover)
3. **Restore from Snapshot**:
   ```bash
   aws rds restore-db-instance-from-db-snapshot \
     --region us-west-2 \
     --db-instance-identifier carddemo-postgres-dr \
     --db-snapshot-identifier carddemo-postgres-dr-copy-2024-01-15 \
     --db-instance-class db.r6g.xlarge \
     --vpc-security-group-ids sg-dr-xxxxx \
     --db-subnet-group-name carddemo-db-subnet-group-dr \
     --multi-az \
     --publicly-accessible false
   ```
4. **Update Application Configuration**: Point applications to DR region endpoint
5. **Validate Data Integrity**: Run validation queries, compare record counts
6. **Resume Operations**: Applications connect to DR database
7. **Failback Planning**: When primary region recovers, plan failback migration

## Monitoring and Alerting

### CloudWatch Metrics

#### Key Performance Metrics

```hcl
# Module automatically exports these CloudWatch metrics:
# - CPUUtilization (%)
# - DatabaseConnections (count)
# - FreeableMemory (bytes)
# - FreeStorageSpace (bytes)
# - ReadLatency (seconds)
# - WriteLatency (seconds)
# - ReadThroughput (bytes/second)
# - WriteThroughput (bytes/second)
# - ReadIOPS (count/second)
# - WriteIOPS (count/second)
# - NetworkReceiveThroughput (bytes/second)
# - NetworkTransmitThroughput (bytes/second)
```

**Accessing Metrics:**
```bash
# Get CPU utilization for last hour
aws cloudwatch get-metric-statistics \
  --namespace AWS/RDS \
  --metric-name CPUUtilization \
  --dimensions Name=DBInstanceIdentifier,Value=carddemo-postgres \
  --start-time $(date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 300 \
  --statistics Average,Maximum

# Get database connections
aws cloudwatch get-metric-statistics \
  --namespace AWS/RDS \
  --metric-name DatabaseConnections \
  --dimensions Name=DBInstanceIdentifier,Value=carddemo-postgres \
  --start-time $(date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 60 \
  --statistics Average,Maximum
```

### CloudWatch Alarms

#### Critical Alarms (PagerDuty/Ops Team)

```hcl
# High CPU utilization alarm
resource "aws_cloudwatch_metric_alarm" "rds_cpu_high" {
  alarm_name          = "carddemo-rds-cpu-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = "2"
  metric_name        = "CPUUtilization"
  namespace          = "AWS/RDS"
  period             = "300"
  statistic          = "Average"
  threshold          = "80"
  alarm_description  = "RDS CPU utilization is above 80%"
  alarm_actions      = [aws_sns_topic.critical_alerts.arn]
  
  dimensions = {
    DBInstanceIdentifier = module.carddemo_rds.db_instance_id
  }
}

# Low free storage space alarm
resource "aws_cloudwatch_metric_alarm" "rds_storage_low" {
  alarm_name          = "carddemo-rds-storage-low"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = "1"
  metric_name        = "FreeStorageSpace"
  namespace          = "AWS/RDS"
  period             = "300"
  statistic          = "Average"
  threshold          = "10737418240"  # 10 GB in bytes
  alarm_description  = "RDS free storage space is below 10 GB"
  alarm_actions      = [aws_sns_topic.critical_alerts.arn]
  
  dimensions = {
    DBInstanceIdentifier = module.carddemo_rds.db_instance_id
  }
}

# High database connections alarm
resource "aws_cloudwatch_metric_alarm" "rds_connections_high" {
  alarm_name          = "carddemo-rds-connections-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = "2"
  metric_name        = "DatabaseConnections"
  namespace          = "AWS/RDS"
  period             = "60"
  statistic          = "Average"
  threshold          = "160"  # 80% of max_connections=200
  alarm_description  = "RDS connection count exceeds 80% of max"
  alarm_actions      = [aws_sns_topic.critical_alerts.arn]
  
  dimensions = {
    DBInstanceIdentifier = module.carddemo_rds.db_instance_id
  }
}

# High write latency alarm
resource "aws_cloudwatch_metric_alarm" "rds_write_latency_high" {
  alarm_name          = "carddemo-rds-write-latency-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = "3"
  metric_name        = "WriteLatency"
  namespace          = "AWS/RDS"
  period             = "60"
  statistic          = "Average"
  threshold          = "0.05"  # 50ms
  alarm_description  = "RDS write latency exceeds 50ms"
  alarm_actions      = [aws_sns_topic.critical_alerts.arn]
  
  dimensions = {
    DBInstanceIdentifier = module.carddemo_rds.db_instance_id
  }
}
```

#### Warning Alarms (Email/Slack)

```hcl
# Moderate CPU utilization warning
resource "aws_cloudwatch_metric_alarm" "rds_cpu_warning" {
  alarm_name          = "carddemo-rds-cpu-warning"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = "3"
  metric_name        = "CPUUtilization"
  namespace          = "AWS/RDS"
  period             = "300"
  statistic          = "Average"
  threshold          = "60"
  alarm_description  = "RDS CPU utilization is above 60%"
  alarm_actions      = [aws_sns_topic.warning_alerts.arn]
  
  dimensions = {
    DBInstanceIdentifier = module.carddemo_rds.db_instance_id
  }
}

# Low free memory warning
resource "aws_cloudwatch_metric_alarm" "rds_memory_low" {
  alarm_name          = "carddemo-rds-memory-low"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = "2"
  metric_name        = "FreeableMemory"
  namespace          = "AWS/RDS"
  period             = "300"
  statistic          = "Average"
  threshold          = "1073741824"  # 1 GB in bytes
  alarm_description  = "RDS freeable memory is below 1 GB"
  alarm_actions      = [aws_sns_topic.warning_alerts.arn]
  
  dimensions = {
    DBInstanceIdentifier = module.carddemo_rds.db_instance_id
  }
}
```

### Performance Insights

#### Enabling Performance Insights

```hcl
module "carddemo_rds" {
  source = "./modules/rds"
  
  performance_insights_enabled            = true
  performance_insights_retention_period   = 7     # 7 days (free tier) or 731 days (paid)
  performance_insights_kms_key_id        = module.kms.key_arn
}
```

#### Using Performance Insights

**AWS Console:**
1. Navigate to RDS → carddemo-postgres → Performance Insights
2. View top SQL statements by load (average active sessions)
3. Analyze wait events (CPU, I/O, lock, buffer contention)
4. Identify slow queries for optimization

**CLI Analysis:**
```bash
# Get top SQL statements by load
aws pi get-resource-metrics \
  --service-type RDS \
  --identifier db-ABCDEFGHIJK \
  --start-time $(date -u -d '1 hour ago' +%s) \
  --end-time $(date -u +%s) \
  --period-in-seconds 300 \
  --metric-queries file://query-top-sql.json

# query-top-sql.json:
{
  "Metric": "db.load.avg",
  "GroupBy": {
    "Group": "db.sql",
    "Limit": 10
  }
}
```

**Common Performance Issues:**

| Symptom | Performance Insights View | Resolution |
|---------|---------------------------|------------|
| High CPU | Top SQL by CPU wait events | Optimize queries, add indexes, scale instance |
| Slow queries | Top SQL by execution time | Add indexes, rewrite queries, increase work_mem |
| Connection exhaustion | Database connections metric | Increase max_connections, fix connection leaks |
| Lock contention | Lock wait events | Optimize transaction boundaries, reduce lock hold time |
| I/O bottleneck | I/O wait events | Increase IOPS (switch to io1), optimize queries |

### Enhanced Monitoring

```hcl
module "carddemo_rds" {
  source = "./modules/rds"
  
  monitoring_interval = 60  # Collect OS metrics every 60 seconds
  monitoring_role_arn = aws_iam_role.rds_monitoring.arn  # Auto-created if null
}
```

**Enhanced Monitoring Metrics (OS-level):**
- CPU utilization (detailed breakdown: system, user, wait, steal)
- Memory (active, cached, buffers, free, swap usage)
- Disk I/O (read/write IOPS, throughput, queue depth, await time)
- Network (receive/transmit bytes, packets, errors)
- Process list (top processes by CPU and memory)

**Viewing Enhanced Monitoring:**
```bash
# CloudWatch Logs Insights query for OS metrics
aws logs start-query \
  --log-group-name RDSOSMetrics \
  --start-time $(date -u -d '1 hour ago' +%s) \
  --end-time $(date -u +%s) \
  --query-string 'fields @timestamp, instanceID, cpuUtilization.total, memory.free
                   | filter instanceID = "db-ABCDEFGHIJK"
                   | sort @timestamp desc'
```

### CloudWatch Logs

#### Log Groups

```hcl
enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

# Creates log groups:
# /aws/rds/instance/carddemo-postgres/postgresql
# /aws/rds/instance/carddemo-postgres/upgrade
```

#### Querying Logs

**PostgreSQL Error Log Analysis:**
```bash
# CloudWatch Logs Insights query for errors
aws logs start-query \
  --log-group-name /aws/rds/instance/carddemo-postgres/postgresql \
  --start-time $(date -u -d '1 day ago' +%s) \
  --end-time $(date -u +%s) \
  --query-string 'fields @timestamp, @message
                   | filter @message like /ERROR/
                   | stats count() by @message
                   | sort count desc'

# Slow query log analysis
aws logs start-query \
  --log-group-name /aws/rds/instance/carddemo-postgres/postgresql \
  --start-time $(date -u -d '1 day ago' +%s) \
  --end-time $(date -u +%s) \
  --query-string 'fields @timestamp, @message
                   | filter @message like /duration:/
                   | parse @message "duration: * ms" as duration
                   | filter duration > 1000
                   | sort duration desc'
```

### Grafana Dashboard (Optional)

**Example Dashboard Configuration:**
```json
{
  "dashboard": {
    "title": "CardDemo RDS Performance",
    "panels": [
      {
        "title": "CPU Utilization",
        "type": "graph",
        "datasource": "CloudWatch",
        "targets": [{
          "namespace": "AWS/RDS",
          "metricName": "CPUUtilization",
          "dimensions": {"DBInstanceIdentifier": "carddemo-postgres"},
          "statistics": ["Average"],
          "period": 300
        }]
      },
      {
        "title": "Database Connections",
        "type": "graph",
        "datasource": "CloudWatch",
        "targets": [{
          "namespace": "AWS/RDS",
          "metricName": "DatabaseConnections",
          "dimensions": {"DBInstanceIdentifier": "carddemo-postgres"},
          "statistics": ["Average", "Maximum"],
          "period": 60
        }]
      },
      {
        "title": "Read/Write Latency",
        "type": "graph",
        "datasource": "CloudWatch",
        "targets": [
          {
            "namespace": "AWS/RDS",
            "metricName": "ReadLatency",
            "dimensions": {"DBInstanceIdentifier": "carddemo-postgres"},
            "statistics": ["Average"],
            "period": 60
          },
          {
            "namespace": "AWS/RDS",
            "metricName": "WriteLatency",
            "dimensions": {"DBInstanceIdentifier": "carddemo-postgres"},
            "statistics": ["Average"],
            "period": 60
          }
        ]
      }
    ]
  }
}
```

## Cost Optimization

### Instance Sizing Strategy

#### Right-Sizing Recommendations

**Oversized Indicators:**
- CPU utilization consistently < 40%
- Memory utilization consistently < 60%
- Low IOPS utilization (< 50% of provisioned)

**Undersized Indicators:**
- CPU utilization consistently > 80%
- Freeable memory < 2 GB
- High read/write latency (> 50ms)
- Connection errors due to max_connections limit

**Sizing Workflow:**
1. Monitor production workload for 2-4 weeks
2. Analyze CloudWatch metrics (CPU, memory, IOPS, connections)
3. Identify peak usage periods and 95th percentile resource consumption
4. Size instance to handle 95th percentile with 20% headroom
5. Schedule scaling during maintenance window

#### Instance Class Comparison

| Instance Class | vCPU | RAM (GB) | Network (Gbps) | $/hour (us-east-1) | $/month (730h) | Best For |
|----------------|------|----------|----------------|-------------------|----------------|----------|
| db.t3.medium | 2 | 4 | Up to 5 | $0.068 | $50 | Development |
| db.t3.large | 2 | 8 | Up to 5 | $0.136 | $99 | Development/Test |
| db.t3.xlarge | 4 | 16 | Up to 5 | $0.272 | $199 | Staging |
| db.r6g.large | 2 | 16 | Up to 10 | $0.288 | $210 | Small Production |
| db.r6g.xlarge | 4 | 32 | Up to 10 | $0.576 | $420 | Production |
| db.r6g.2xlarge | 8 | 64 | Up to 10 | $1.152 | $841 | High-Load Production |
| db.r6g.4xlarge | 16 | 128 | Up to 10 | $2.304 | $1,682 | Very High-Load |

**Cost Savings:**
- **t3 vs r6g**: T3 instances are 30-50% cheaper but have burstable CPU (not suitable for sustained loads)
- **ARM-based (r6g)**: Graviton2 instances (r6g) are 20% cheaper than x86 (r5) with similar performance
- **Single-AZ**: Single-AZ deployment saves 50% on compute cost (not recommended for production)

### Storage Cost Optimization

#### Storage Type Comparison

| Storage Type | $/GB-month | IOPS (included) | Throughput | Use Case |
|--------------|-----------|-----------------|------------|----------|
| gp3 | $0.08 | 3,000 (baseline) | 125 MB/s | General purpose (recommended) |
| gp2 | $0.10 | 3 IOPS/GB | Varies | Legacy (use gp3 instead) |
| io1 | $0.125 + $0.065/IOPS | Custom (100-64,000) | Custom | High IOPS required |
| io2 | $0.125 + $0.080/IOPS | Custom (100-256,000) | Custom | Extreme IOPS required |

**Storage Cost Examples:**
```
500 GB gp3:  500 GB × $0.08 = $40/month
500 GB io1 with 10,000 IOPS: (500 × $0.125) + (10,000 × $0.065) = $62.50 + $650 = $712.50/month
```

**Recommendation:** Use gp3 for most workloads. Only use io1/io2 if you need > 16,000 IOPS.

#### Storage Autoscaling

```hcl
module "carddemo_rds" {
  source = "./modules/rds"
  
  allocated_storage     = 200   # Initial size
  max_allocated_storage = 1000  # Max size (0 to disable autoscaling)
}
```

**Benefits:**
- Automatic expansion when storage reaches 90% utilization
- Prevents out-of-space emergencies
- Pay only for storage actually used

**Cost Impact:**
- Autoscaling charges standard storage rates for expanded capacity
- No additional fees for autoscaling feature itself

### Backup Cost Optimization

```hcl
# Backup storage is free up to 100% of allocated database storage
# Additional backup storage charged at $0.095/GB-month

# Example: 500 GB database with 7-day retention
# Free backup storage: 500 GB
# If backups grow to 800 GB: (800 - 500) × $0.095 = $28.50/month additional

# Optimize backup retention
backup_retention_period = 7  # 7 days for dev/test
backup_retention_period = 14  # 14 days for production (balance cost vs recovery needs)
```

**Backup Cost Strategies:**
- Keep retention at 7-14 days (longer retention increases backup storage costs)
- Delete manual snapshots after validation (manual snapshots charged at full backup rate)
- Use cross-region snapshot copy sparingly (data transfer + storage costs)

### Reserved Instances

#### Reserved Instance Pricing

| Term | Payment Option | db.r6g.xlarge Discount | db.r6g.2xlarge Discount |
|------|----------------|----------------------|------------------------|
| On-Demand | N/A | $0.576/hour ($420/mo) | $1.152/hour ($841/mo) |
| 1-Year | No Upfront | 30% off ($0.403/hour) | 30% off ($0.806/hour) |
| 1-Year | Partial Upfront | 33% off ($0.386/hour) | 33% off ($0.772/hour) |
| 1-Year | All Upfront | 35% off ($0.374/hour) | 35% off ($0.749/hour) |
| 3-Year | All Upfront | 60% off ($0.230/hour) | 60% off ($0.461/hour) |

**Cost Savings Example (db.r6g.xlarge, 1-year partial upfront):**
```
On-Demand: $420/month × 12 = $5,040/year
Reserved: $0.386/hour × 730 hours/month × 12 months = $3,383/year
Savings: $5,040 - $3,383 = $1,657/year (33%)
```

**Recommendation:**
- Purchase 1-year reserved instances for production after workload stabilizes (2-3 months post-migration)
- Use partial upfront payment (good discount without full cash commitment)
- Keep 20-30% capacity as on-demand for scaling flexibility

### Multi-AZ Cost Impact

```hcl
multi_az = true  # Doubles compute cost, not storage cost

# Cost example (db.r6g.xlarge):
# Single-AZ: $420/month (compute) + $40/month (500 GB storage) = $460/month
# Multi-AZ: $840/month (compute) + $40/month (storage) = $880/month
# Multi-AZ premium: $420/month for 99.95% availability vs 99.5%
```

**Cost Optimization:**
- Use Single-AZ for dev/test environments (50% savings)
- Use Multi-AZ for production (justified by availability requirements)
- Consider cross-region read replica instead of Multi-AZ for DR-only scenarios (same cost, more flexibility)

### Read Replica Cost Optimization

```hcl
# Read replica costs same as primary instance (instance + storage)
# Use only when read workload justifies cost

# Cost-benefit analysis:
# If read replica offloads 40% of queries from primary, may avoid scaling primary from xlarge to 2xlarge
# Read replica (r6g.large): $210/month
# Scaling primary (xlarge → 2xlarge): +$421/month
# Net savings: $211/month
```

**When to Use Read Replicas:**
- Reporting queries impacting production performance
- Geographic distribution (serve reads from multiple regions)
- Read-heavy workload (> 70% reads) justifies horizontal scaling

**When NOT to Use:**
- Write-heavy workload (replicas don't help writes)
- Simple read queries already fast (< 10ms)
- Dev/test environments

## Operational Runbooks

### Common Operations

#### Connecting to Database

**Using psql:**
```bash
# Install PostgreSQL client
sudo yum install postgresql15  # Amazon Linux 2
sudo apt-get install postgresql-client-16  # Ubuntu

# Connect to database
psql -h carddemo-postgres.c123456789.us-east-1.rds.amazonaws.com \
     -p 5432 \
     -U carddemo_admin \
     -d carddemo

# Connection with SSL
psql "host=carddemo-postgres.c123456789.us-east-1.rds.amazonaws.com port=5432 dbname=carddemo user=carddemo_admin sslmode=require"

# Verify connection
carddemo=> SELECT version();
carddemo=> SELECT current_database(), current_user, inet_server_addr(), inet_server_port();
```

**Using PgAdmin:**
1. Download RDS CA certificate bundle
2. Configure connection with SSL mode = require
3. Import CA certificate in PgAdmin SSL settings

#### Running Database Migrations

**Flyway Migration:**
```bash
# From backend directory
cd backend

# Run Flyway migrations
mvn flyway:migrate \
  -Dflyway.url=jdbc:postgresql://carddemo-postgres.c123456789.us-east-1.rds.amazonaws.com:5432/carddemo \
  -Dflyway.user=carddemo_admin \
  -Dflyway.password=$DB_PASSWORD

# Verify migration history
mvn flyway:info

# Rollback (if supported by migration scripts)
mvn flyway:undo
```

**Manual Migration:**
```sql
-- Connect to database
psql -h <endpoint> -U carddemo_admin -d carddemo

-- Begin transaction
BEGIN;

-- Execute DDL changes
CREATE TABLE new_table (...);
ALTER TABLE existing_table ADD COLUMN new_column VARCHAR(50);

-- Verify changes
\d new_table
\d existing_table

-- Commit if successful
COMMIT;

-- Or rollback if errors
ROLLBACK;
```

#### Creating Manual Snapshots

```bash
# Before major changes (schema migration, bulk data update, version upgrade)
aws rds create-db-snapshot \
  --db-instance-identifier carddemo-postgres \
  --db-snapshot-identifier carddemo-postgres-pre-migration-$(date +%Y%m%d-%H%M%S) \
  --tags Key=Purpose,Value=pre-migration Key=Date,Value=$(date +%Y-%m-%d)

# Wait for snapshot completion
aws rds wait db-snapshot-completed \
  --db-snapshot-identifier carddemo-postgres-pre-migration-20240115-103000

# Verify snapshot
aws rds describe-db-snapshots \
  --db-snapshot-identifier carddemo-postgres-pre-migration-20240115-103000
```

#### Restoring from Snapshot

```bash
# Restore to new instance (safer than in-place restore)
aws rds restore-db-instance-from-db-snapshot \
  --db-instance-identifier carddemo-postgres-restored-20240115 \
  --db-snapshot-identifier carddemo-postgres-pre-migration-20240115-103000 \
  --db-instance-class db.r6g.xlarge \
  --vpc-security-group-ids sg-xxxxx \
  --db-subnet-group-name carddemo-db-subnet-group \
  --multi-az \
  --publicly-accessible false \
  --tags Key=Purpose,Value=restoration Key=SourceSnapshot,Value=pre-migration-20240115

# Wait for instance availability
aws rds wait db-instance-available \
  --db-instance-identifier carddemo-postgres-restored-20240115

# Validate restored data
psql -h <restored-endpoint> -U carddemo_admin -d carddemo -c "SELECT COUNT(*) FROM account;"

# If validation passes, update application to use restored instance
# Or rename instances (requires downtime):
# 1. Rename production instance to carddemo-postgres-old
# 2. Rename restored instance to carddemo-postgres
```

#### Modifying Parameter Groups

```bash
# Create custom parameter group
aws rds create-db-parameter-group \
  --db-parameter-group-name carddemo-postgres16-custom-v2 \
  --db-parameter-group-family postgres16 \
  --description "CardDemo PostgreSQL 16 parameters v2"

# Modify parameters
aws rds modify-db-parameter-group \
  --db-parameter-group-name carddemo-postgres16-custom-v2 \
  --parameters \
    "ParameterName=shared_buffers,ParameterValue=10GB,ApplyMethod=pending-reboot" \
    "ParameterName=work_mem,ParameterValue=128MB,ApplyMethod=immediate" \
    "ParameterName=maintenance_work_mem,ParameterValue=2GB,ApplyMethod=immediate"

# Apply parameter group to instance (requires reboot for some parameters)
aws rds modify-db-instance \
  --db-instance-identifier carddemo-postgres \
  --db-parameter-group-name carddemo-postgres16-custom-v2 \
  --apply-immediately

# Reboot instance to apply pending-reboot parameters
aws rds reboot-db-instance \
  --db-instance-identifier carddemo-postgres

# Verify parameters
psql -h <endpoint> -U carddemo_admin -d carddemo -c "SHOW shared_buffers; SHOW work_mem;"
```

#### Scaling Instance Class

```bash
# Modify instance class (can be done with minimal downtime)
aws rds modify-db-instance \
  --db-instance-identifier carddemo-postgres \
  --db-instance-class db.r6g.2xlarge \
  --apply-immediately  # Or schedule during maintenance window

# Monitor modification progress
aws rds describe-db-instances \
  --db-instance-identifier carddemo-postgres \
  --query 'DBInstances[0].[DBInstanceStatus,PendingModifiedValues]'

# Typical downtime: 1-5 minutes for instance class change
# Connection strings remain the same
```

#### Enabling Read Replica

```bash
# Create read replica
aws rds create-db-instance-read-replica \
  --db-instance-identifier carddemo-postgres-replica-1 \
  --source-db-instance-identifier carddemo-postgres \
  --db-instance-class db.r6g.large \
  --availability-zone us-east-1b \
  --publicly-accessible false

# Wait for replica availability
aws rds wait db-instance-available \
  --db-instance-identifier carddemo-postgres-replica-1

# Get replica endpoint
aws rds describe-db-instances \
  --db-instance-identifier carddemo-postgres-replica-1 \
  --query 'DBInstances[0].Endpoint.Address'

# Configure application to use replica for read queries
# Spring Boot example:
# datasource.readOnly.url=jdbc:postgresql://<replica-endpoint>:5432/carddemo
```

### Troubleshooting Guide

#### Issue: Connection Timeout

**Symptoms:**
```
psql: error: could not connect to server: Connection timed out
```

**Diagnosis:**
```bash
# 1. Check instance status
aws rds describe-db-instances \
  --db-instance-identifier carddemo-postgres \
  --query 'DBInstances[0].DBInstanceStatus'

# 2. Verify security group allows your IP
aws ec2 describe-security-groups \
  --group-ids sg-xxxxx \
  --query 'SecurityGroups[0].IpPermissions'

# 3. Test network connectivity
nc -zv carddemo-postgres.c123456789.us-east-1.rds.amazonaws.com 5432
telnet carddemo-postgres.c123456789.us-east-1.rds.amazonaws.com 5432

# 4. Check VPC route tables
aws ec2 describe-route-tables \
  --filters "Name=association.subnet-id,Values=subnet-xxxxx"
```

**Resolution:**
- Add your IP/security group to RDS security group ingress rules
- Verify network ACLs allow port 5432
- Check VPC peering or VPN connectivity if connecting from on-premises
- Ensure RDS instance is in "available" state

#### Issue: High CPU Utilization

**Symptoms:**
- CloudWatch alarm: CPU > 80%
- Slow application response times

**Diagnosis:**
```sql
-- Connect to database
psql -h <endpoint> -U carddemo_admin -d carddemo

-- Find long-running queries
SELECT pid, now() - pg_stat_activity.query_start AS duration, query, state
FROM pg_stat_activity
WHERE state != 'idle' AND now() - pg_stat_activity.query_start > interval '1 second'
ORDER BY duration DESC;

-- Find queries consuming most CPU (requires pg_stat_statements extension)
SELECT query, calls, total_exec_time, mean_exec_time, stddev_exec_time, rows
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 10;

-- Check for missing indexes
SELECT schemaname, tablename, attname, n_distinct, correlation
FROM pg_stats
WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
  AND n_distinct > 100
ORDER BY abs(correlation) DESC;
```

**Resolution:**
- Optimize slow queries (add indexes, rewrite queries)
- Kill long-running queries: `SELECT pg_terminate_backend(pid);`
- Increase instance class if consistently high CPU
- Enable connection pooling in application (HikariCP)
- Review Performance Insights for query-level bottlenecks

#### Issue: Storage Full

**Symptoms:**
- CloudWatch alarm: FreeStorageSpace < 10 GB
- Write errors in application logs

**Diagnosis:**
```sql
-- Check table sizes
SELECT schemaname, tablename, 
       pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size
FROM pg_tables
WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;

-- Check database size
SELECT pg_database.datname, pg_size_pretty(pg_database_size(pg_database.datname)) AS size
FROM pg_database
ORDER BY pg_database_size(pg_database.datname) DESC;

-- Check for bloat (dead tuples)
SELECT schemaname, tablename, n_dead_tup, n_live_tup, 
       round((n_dead_tup * 100.0 / NULLIF(n_live_tup + n_dead_tup, 0)), 2) AS dead_pct
FROM pg_stat_user_tables
WHERE n_dead_tup > 10000
ORDER BY n_dead_tup DESC;
```

**Resolution:**
- Enable storage autoscaling: `max_allocated_storage = 1000`
- Manually increase storage: `aws rds modify-db-instance --allocated-storage 500`
- Run VACUUM to reclaim dead tuple space: `VACUUM FULL VERBOSE table_name;`
- Archive old transaction data to separate table/database
- Clean up large logs in CloudWatch Logs

#### Issue: Replication Lag (Read Replica)

**Symptoms:**
- Stale data in reporting queries
- CloudWatch metric: ReplicaLag > 60 seconds

**Diagnosis:**
```sql
-- On primary instance
SELECT client_addr, state, sent_lsn, write_lsn, flush_lsn, replay_lsn, 
       sync_state, sync_priority
FROM pg_stat_replication;

-- Calculate replication lag (bytes)
SELECT client_addr, 
       pg_wal_lsn_diff(sent_lsn, replay_lsn) AS lag_bytes,
       state
FROM pg_stat_replication;

-- On replica instance
SELECT now() - pg_last_xact_replay_timestamp() AS replication_lag;
```

**Resolution:**
- Check network connectivity between primary and replica
- Upgrade replica instance class (higher network bandwidth)
- Reduce write load on primary (batch updates, optimize transactions)
- Check for long-running queries on replica (blocking replay): `SELECT * FROM pg_stat_activity WHERE state != 'idle';`
- Consider using logical replication instead of physical for specific use cases

## Prerequisites

Before deploying this RDS module, ensure the following resources and permissions are available:

### VPC and Networking

```hcl
# VPC with private subnets in at least 2 Availability Zones
module "vpc" {
  source = "./modules/vpc"
  
  cidr_block = "10.0.0.0/16"
  
  private_subnets = [
    "10.0.20.0/24",  # us-east-1a (database subnet)
    "10.0.21.0/24",  # us-east-1b (database subnet)
    "10.0.22.0/24"   # us-east-1c (database subnet, optional)
  ]
  
  enable_nat_gateway = false  # Not required for RDS (no internet access needed)
  enable_dns_hostnames = true
  enable_dns_support = true
}
```

**Requirements:**
- VPC with minimum 2 private subnets in different Availability Zones
- Each subnet must have sufficient IP addresses (minimum /28, recommended /24)
- DNS resolution and DNS hostnames enabled in VPC
- No internet gateway route required in database subnets (private only)

### EKS Cluster Security Group

```hcl
# EKS cluster must exist to obtain worker node security group
module "eks" {
  source = "./modules/eks"
  
  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnet_ids
  
  # ...other EKS configuration
}

# Output worker security group for RDS module
output "worker_security_group_id" {
  value = module.eks.worker_security_group_id
}
```

### KMS Key for Encryption

```hcl
# Create KMS key for RDS encryption at rest
module "kms" {
  source = "./modules/kms"
  
  description = "CardDemo RDS encryption key"
  key_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "Enable IAM User Permissions"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::123456789012:root"
        }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "Allow RDS to use the key"
        Effect = "Allow"
        Principal = {
          Service = "rds.amazonaws.com"
        }
        Action = [
          "kms:Decrypt",
          "kms:GenerateDataKey",
          "kms:CreateGrant"
        ]
        Resource = "*"
      }
    ]
  })
  
  tags = {
    Purpose = "RDS encryption"
  }
}
```

**Note:** If KMS key is not provided, RDS will use AWS-managed default key (`aws/rds`), which is acceptable for non-production environments.

### IAM Permissions

**Terraform Execution Role requires:**
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "rds:CreateDBInstance",
        "rds:CreateDBSubnetGroup",
        "rds:CreateDBParameterGroup",
        "rds:ModifyDBInstance",
        "rds:DescribeDBInstances",
        "rds:DescribeDBSubnetGroups",
        "rds:DescribeDBParameterGroups",
        "rds:DeleteDBInstance",
        "rds:DeleteDBSubnetGroup",
        "rds:DeleteDBParameterGroup",
        "rds:AddTagsToResource",
        "rds:ListTagsForResource",
        "rds:CreateDBSnapshot",
        "rds:DescribeDBSnapshots"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "ec2:DescribeSecurityGroups",
        "ec2:CreateSecurityGroup",
        "ec2:DeleteSecurityGroup",
        "ec2:AuthorizeSecurityGroupIngress",
        "ec2:RevokeSecurityGroupIngress",
        "ec2:DescribeSubnets",
        "ec2:DescribeVpcs"
      ],
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "kms:DescribeKey",
        "kms:CreateGrant"
      ],
      "Resource": "arn:aws:kms:us-east-1:123456789012:key/*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "iam:CreateRole",
        "iam:AttachRolePolicy",
        "iam:PassRole"
      ],
      "Resource": "arn:aws:iam::123456789012:role/rds-monitoring-role"
    },
    {
      "Effect": "Allow",
      "Action": [
        "logs:CreateLogGroup",
        "logs:DescribeLogGroups"
      ],
      "Resource": "*"
    }
  ]
}
```

### S3 Bucket for Automated Backups

**Note:** S3 bucket for RDS automated backups is managed by AWS automatically. No manual S3 bucket creation required. AWS stores automated backups and transaction logs in AWS-managed S3 buckets.

### Terraform State Backend

```hcl
# Configure S3 backend for Terraform state
terraform {
  backend "s3" {
    bucket         = "carddemo-terraform-state"
    key            = "rds/terraform.tfstate"
    region         = "us-east-1"
    encrypt        = true
    dynamodb_table = "carddemo-terraform-locks"
  }
}
```

## Contributing

Contributions to improve this module are welcome. Please follow these guidelines:

### Development Workflow

1. **Fork the repository**
2. **Create a feature branch**: `git checkout -b feature/improve-monitoring`
3. **Make changes with tests**
4. **Test locally**: `terraform init && terraform plan`
5. **Submit pull request** with description of changes

### Module Standards

- Follow Terraform best practices and style guide
- All variables must have descriptions and appropriate types
- All outputs must have descriptions
- Include example usage in README
- Document breaking changes in PR description
- Maintain backward compatibility when possible

### Testing Requirements

```bash
# Validate Terraform syntax
terraform validate

# Check formatting
terraform fmt -check -recursive

# Run tflint
tflint --init
tflint

# Test module with Terratest (if applicable)
cd test
go test -v -timeout 30m
```

### Versioning

This module follows semantic versioning (SemVer):
- **MAJOR**: Breaking changes
- **MINOR**: New features (backward compatible)
- **PATCH**: Bug fixes (backward compatible)

## License

This Terraform module is part of the CardDemo application migration project.

Copyright 2024 CardDemo Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this module except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

---

## Additional Resources

- [AWS RDS PostgreSQL Documentation](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_PostgreSQL.html)
- [PostgreSQL 16 Release Notes](https://www.postgresql.org/docs/16/release-16.html)
- [RDS Best Practices](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_BestPractices.html)
- [PostgreSQL Performance Optimization](https://www.postgresql.org/docs/16/performance-tips.html)
- [Terraform AWS RDS Module](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/db_instance)
- [CardDemo Migration Documentation](../../README.md)

---

**Last Updated**: January 2024  
**Module Version**: 1.0.0  
**Maintainer**: CardDemo Infrastructure Team
