###############################################################################
# AWS CardDemo - managed Production PostgreSQL 16 (Refine-PR directive D5)
#
# Provisions:
#   * a PostgreSQL 16 RDS instance with AUTOMATED BACKUPS and ENFORCED TLS
#   * a private DB subnet group + a least-privilege security group
#   * a custom parameter group that forces SSL/TLS (rds.force_ssl = 1)
#   * strong, Terraform-generated credentials (no hardcoded secrets)
#   * an AWS Secrets Manager secret holding the connection + app seed
#     credentials as canonical Spring property keys, so the application's
#     `prod` profile can import them directly (see application-prod.yml, D7)
###############################################################################

locals {
  name = "${var.name_prefix}-${var.environment}"

  # Resolve credentials: use the explicit value when supplied, else the
  # Terraform-generated strong random password.
  db_master_password = random_password.db_master.result
  app_admin_password = var.app_admin_password != "" ? var.app_admin_password : random_password.app_admin.result
  app_user_password  = var.app_user_password != "" ? var.app_user_password : random_password.app_user.result

  # sslmode=require makes the JDBC client negotiate TLS, complementing the
  # server-side rds.force_ssl=1 enforcement below. Use verify-full plus the RDS
  # CA bundle for certificate validation in the strictest environments.
  jdbc_url = "jdbc:postgresql://${aws_db_instance.this.address}:${aws_db_instance.this.port}/${var.db_name}?sslmode=require"
}

# ---------------------------------------------------------------------------
# Credentials (generated; RDS master password excludes '/', '@', '"', spaces)
# ---------------------------------------------------------------------------
resource "random_password" "db_master" {
  length           = 24
  special          = true
  override_special = "!#$%*()-_=+[]{}:?"
}

resource "random_password" "app_admin" {
  length  = 20
  special = false
}

resource "random_password" "app_user" {
  length  = 20
  special = false
}

# ---------------------------------------------------------------------------
# Network placement: private subnet group + least-privilege security group
# ---------------------------------------------------------------------------
resource "aws_db_subnet_group" "this" {
  name       = "${local.name}-db-subnets"
  subnet_ids = var.subnet_ids
  tags       = merge(var.tags, { Name = "${local.name}-db-subnets" })
}

resource "aws_security_group" "db" {
  name_prefix = "${local.name}-rds-"
  description = "PostgreSQL ingress for CardDemo ${var.environment}"
  vpc_id      = var.vpc_id

  dynamic "ingress" {
    for_each = length(var.allowed_cidr_blocks) > 0 ? [1] : []
    content {
      description = "PostgreSQL from allowed CIDR blocks"
      from_port   = var.db_port
      to_port     = var.db_port
      protocol    = "tcp"
      cidr_blocks = var.allowed_cidr_blocks
    }
  }

  dynamic "ingress" {
    for_each = length(var.allowed_security_group_ids) > 0 ? [1] : []
    content {
      description     = "PostgreSQL from allowed security groups"
      from_port       = var.db_port
      to_port         = var.db_port
      protocol        = "tcp"
      security_groups = var.allowed_security_group_ids
    }
  }

  egress {
    description = "Allow all outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "${local.name}-rds" })

  lifecycle {
    create_before_destroy = true
  }
}

# ---------------------------------------------------------------------------
# Parameter group: ENFORCE TLS for every client connection (rds.force_ssl=1)
# ---------------------------------------------------------------------------
resource "aws_db_parameter_group" "this" {
  name_prefix = "${local.name}-pg16-"
  family      = var.parameter_group_family
  description = "CardDemo PostgreSQL 16 parameters (TLS enforced)"

  # rds.force_ssl is a dynamic parameter: applied without a reboot. With it set
  # the server rejects any non-SSL connection, guaranteeing data-in-transit
  # encryption regardless of client configuration.
  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "immediate"
  }

  # Log slow statements and connections for production observability.
  parameter {
    name         = "log_min_duration_statement"
    value        = "1000"
    apply_method = "immediate"
  }

  tags = merge(var.tags, { Name = "${local.name}-pg16" })

  lifecycle {
    create_before_destroy = true
  }
}

# ---------------------------------------------------------------------------
# Enhanced monitoring role (only when monitoring is enabled)
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "rds_monitoring_assume" {
  count = var.monitoring_interval > 0 ? 1 : 0

  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["monitoring.rds.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "rds_monitoring" {
  count              = var.monitoring_interval > 0 ? 1 : 0
  name_prefix        = "${local.name}-rds-mon-"
  assume_role_policy = data.aws_iam_policy_document.rds_monitoring_assume[0].json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "rds_monitoring" {
  count      = var.monitoring_interval > 0 ? 1 : 0
  role       = aws_iam_role.rds_monitoring[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

# ---------------------------------------------------------------------------
# The managed PostgreSQL 16 instance
# ---------------------------------------------------------------------------
resource "aws_db_instance" "this" {
  identifier     = "${local.name}-postgres"
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  allocated_storage     = var.allocated_storage
  max_allocated_storage = var.max_allocated_storage
  storage_type          = var.storage_type
  storage_encrypted     = var.storage_encrypted
  kms_key_id            = var.kms_key_id != "" ? var.kms_key_id : null

  db_name  = var.db_name
  username = var.db_username
  password = local.db_master_password
  port     = var.db_port

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.db.id]
  parameter_group_name   = aws_db_parameter_group.this.name
  publicly_accessible    = false
  multi_az               = var.multi_az

  # Automated backups (D5): a retention period > 0 keeps daily automated
  # backups + point-in-time recovery enabled within the configured window.
  backup_retention_period  = var.backup_retention_period
  backup_window            = var.backup_window
  maintenance_window       = var.maintenance_window
  copy_tags_to_snapshot    = var.copy_tags_to_snapshot
  delete_automated_backups = var.delete_automated_backups

  auto_minor_version_upgrade = true
  apply_immediately          = false

  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = false
  final_snapshot_identifier = "${local.name}-postgres-final"

  performance_insights_enabled = var.performance_insights_enabled
  monitoring_interval          = var.monitoring_interval
  monitoring_role_arn          = var.monitoring_interval > 0 ? aws_iam_role.rds_monitoring[0].arn : null

  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

  tags = merge(var.tags, { Name = "${local.name}-postgres" })
}

# ---------------------------------------------------------------------------
# Secrets Manager: the single secret the application's prod profile imports.
# Keys are canonical Spring property names so `spring.config.import:
# aws-secretsmanager:<name>` maps them straight onto the environment.
# ---------------------------------------------------------------------------
resource "aws_secretsmanager_secret" "db" {
  name                    = "${var.name_prefix}/${var.environment}/db"
  description             = "CardDemo ${var.environment} datasource + app seed credentials"
  recovery_window_in_days = var.secret_recovery_window_days
  tags                    = merge(var.tags, { Name = "${local.name}-db-secret" })
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({
    "spring.datasource.url"                 = local.jdbc_url
    "spring.datasource.username"            = var.db_username
    "spring.datasource.password"            = local.db_master_password
    "carddemo.security.seed.admin-password" = local.app_admin_password
    "carddemo.security.seed.user-password"  = local.app_user_password
  })
}
