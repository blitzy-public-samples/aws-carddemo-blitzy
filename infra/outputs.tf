###############################################################################
# Outputs
#
# These feed the application's prod deployment: point the prod profile's
# `spring.config.import` at `db_secret_name` (or `db_secret_arn`). The plaintext
# connection values are NOT output (they live only in Secrets Manager).
###############################################################################

output "db_instance_identifier" {
  description = "RDS instance identifier."
  value       = aws_db_instance.this.identifier
}

output "db_instance_address" {
  description = "DNS address (host) of the database."
  value       = aws_db_instance.this.address
}

output "db_instance_port" {
  description = "Port the database listens on."
  value       = aws_db_instance.this.port
}

output "db_instance_endpoint" {
  description = "Combined host:port endpoint of the database."
  value       = aws_db_instance.this.endpoint
}

output "db_name" {
  description = "Initial database (schema) name."
  value       = aws_db_instance.this.db_name
}

output "db_subnet_group_name" {
  description = "Name of the DB subnet group."
  value       = aws_db_subnet_group.this.name
}

output "db_security_group_id" {
  description = "ID of the database security group."
  value       = aws_security_group.db.id
}

output "db_parameter_group_name" {
  description = "Name of the TLS-enforcing parameter group."
  value       = aws_db_parameter_group.this.name
}

output "backup_retention_period" {
  description = "Configured automated-backup retention in days."
  value       = aws_db_instance.this.backup_retention_period
}

output "db_secret_arn" {
  description = "ARN of the Secrets Manager secret holding datasource + seed credentials."
  value       = aws_secretsmanager_secret.db.arn
}

output "db_secret_name" {
  description = "Name of the Secrets Manager secret (use in spring.config.import: aws-secretsmanager:<name>)."
  value       = aws_secretsmanager_secret.db.name
}
