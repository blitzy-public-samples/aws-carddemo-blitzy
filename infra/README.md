# CardDemo Infrastructure as Code (Terraform)

Path-to-production infrastructure for the modernized **AWS CardDemo** Spring Boot
service. This Terraform root module provisions a **managed Production PostgreSQL
16** database (Amazon RDS) with **automated backups** and **enforced TLS**, plus
the supporting networking and an **AWS Secrets Manager** secret that the
application's `prod` profile imports at startup.

> Scope note: This directory is *infrastructure only*. It is not part of the
> Maven build and has no effect on the application's unit/integration tests or
> coverage. It satisfies Refine-PR directive **D5** (managed Production
> PostgreSQL 16 with automated backups and TLS) and is cohesive with the secrets
> management (**D6**) and `application-prod.yml` (**D7**) deliverables.

## What it creates

| Resource | Purpose |
| --- | --- |
| `aws_db_instance` (PostgreSQL **16**) | The managed production database. Not publicly accessible; optional Multi-AZ standby. |
| `aws_db_parameter_group` | Sets **`rds.force_ssl = 1`** so the server **rejects any non-TLS connection** (data-in-transit encryption). |
| `aws_db_subnet_group` | Places the instance across your private subnets (multi-AZ). |
| `aws_security_group` | Least-privilege PostgreSQL ingress from the CIDRs / security groups you allow. |
| `aws_secretsmanager_secret` (+ version) | Holds the datasource + app seed credentials as canonical Spring property keys. |
| `random_password` ×3 | Generates the DB master password and the two app seed passwords (no hardcoded secrets). |
| `aws_iam_role` (+ attachment) | Enhanced-monitoring role (created only when monitoring is enabled). |

### Automated backups (D5)

`backup_retention_period` defaults to **14 days** (valid range 1–35). A non-zero
retention keeps RDS automated daily backups **and** point-in-time recovery
enabled, taken during `backup_window`. Tags are copied to snapshots and a final
snapshot is taken on destroy (`skip_final_snapshot = false`).

### Enforced TLS (D5)

Two complementary controls:

1. **Server-side:** the parameter group sets `rds.force_ssl = 1`, so PostgreSQL
   refuses non-SSL connections.
2. **Client-side:** the JDBC URL stored in the secret ends with `?sslmode=require`.
   For certificate validation use `sslmode=verify-full` together with the
   [RDS CA bundle](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html).

## How the application consumes it (D6 / D7)

The secret is created with **canonical Spring property keys**:

```json
{
  "spring.datasource.url": "jdbc:postgresql://<host>:5432/carddemo?sslmode=require",
  "spring.datasource.username": "carddemo",
  "spring.datasource.password": "********",
  "carddemo.security.seed.admin-password": "********",
  "carddemo.security.seed.user-password": "********"
}
```

`src/main/resources/application-prod.yml` imports it via
`spring.config.import: aws-secretsmanager:<db_secret_name>`, so these values flow
straight into the running application — replacing the default seed identities and
the local datasource defaults. Reference the secret by the `db_secret_name`
(or `db_secret_arn`) output below.

## Usage

Prerequisites: Terraform >= 1.5, AWS credentials available to the provider chain
(env vars, shared config, or an assumed CI role), and an existing VPC with at
least two private subnets in different AZs.

```bash
cd infra
cp terraform.tfvars.example terraform.tfvars   # then edit vpc_id, subnet_ids, CIDRs
terraform init
terraform fmt -recursive
terraform validate
terraform plan -out tf.plan
terraform apply tf.plan
```

Retrieve the values the app needs:

```bash
terraform output db_secret_name
terraform output db_instance_endpoint
```

## Key inputs

See `variables.tf` for the full list. Common ones:

| Variable | Default | Notes |
| --- | --- | --- |
| `vpc_id` | _(required)_ | Existing VPC ID. |
| `subnet_ids` | _(required)_ | ≥ 2 private subnets in different AZs. |
| `allowed_cidr_blocks` | `[]` | App-tier CIDRs allowed to reach 5432. |
| `engine_version` | `"16"` | Pin a minor (e.g. `"16.8"`) to control upgrades. |
| `instance_class` | `db.t3.medium` | Size per workload. |
| `backup_retention_period` | `14` | Automated-backup retention (days). |
| `multi_az` | `true` | Standby for automatic failover. |
| `storage_encrypted` | `true` | Infra-layer KMS encryption (distinct from the deferred app-level encryption-at-rest in AAP §0.2.2). |
| `deletion_protection` | `true` | Guard against accidental deletion. |

## Outputs

`db_instance_endpoint`, `db_instance_address`, `db_instance_port`, `db_name`,
`db_subnet_group_name`, `db_security_group_id`, `db_parameter_group_name`,
`backup_retention_period`, `db_secret_arn`, `db_secret_name`.

Plaintext credentials are intentionally **not** exposed as outputs; they live
only in Secrets Manager.

## Notes

- State and `*.tfvars` are git-ignored (see `infra/.gitignore`). Configure a
  remote S3 backend (commented in `versions.tf`) for shared/CI use.
- The secret uses a stable name (`<name_prefix>/<environment>/db`). Secrets
  Manager reserves a deleted name for `secret_recovery_window_days`; set that to
  `0` in non-production if you need to recreate immediately.
