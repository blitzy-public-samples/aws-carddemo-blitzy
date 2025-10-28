# ==============================================================================
# Terraform Backend Configuration
# ==============================================================================
# Purpose: Remote state management using AWS S3 for state storage and DynamoDB
#          for state locking, enabling secure team collaboration and preventing
#          concurrent state modifications during infrastructure provisioning.
#
# Migration Context: Replaces manual mainframe provisioning processes with
#                    declarative, version-controlled infrastructure as code.
#
# Security Features:
#   - Server-side encryption (AES-256) for state file at rest
#   - State file versioning enabled via S3 bucket configuration
#   - DynamoDB-based locking prevents concurrent modifications
#   - Remote state enables secure team collaboration without local state files
#
# Prerequisites:
#   - S3 bucket must be created before running terraform init
#   - DynamoDB table must be created with LockID as primary key (String)
#   - IAM permissions for S3 GetObject/PutObject and DynamoDB operations
#
# Usage:
#   terraform init    # Initialize backend and download providers
#   terraform plan    # Preview infrastructure changes
#   terraform apply   # Apply infrastructure changes
# ==============================================================================

terraform {
  # ------------------------------------------------------------------------------
  # Terraform Version Constraint
  # ------------------------------------------------------------------------------
  # Minimum version 1.0 ensures stable feature set for production deployments.
  # Current production version: 1.10.3 (as of project specification)
  # Using >= 1.0 allows patch and minor version upgrades while maintaining
  # compatibility with the terraform block and backend configuration syntax.
  # ------------------------------------------------------------------------------
  required_version = ">= 1.0"

  # ------------------------------------------------------------------------------
  # Required Provider Versions
  # ------------------------------------------------------------------------------
  # AWS Provider: Version ~> 5.0 ensures compatibility with latest AWS services
  # while allowing automatic patch version upgrades (5.x.x).
  # Current production version: 5.82.0 (as of project specification)
  #
  # The ~> constraint allows:
  #   - Patch version updates (5.82.x → 5.82.y) automatically
  #   - Minor version updates (5.82.x → 5.y.z) automatically
  #   - Blocks major version updates (5.x.x → 6.x.x) to prevent breaking changes
  # ------------------------------------------------------------------------------
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # ------------------------------------------------------------------------------
  # S3 Backend Configuration for Remote State Storage
  # ------------------------------------------------------------------------------
  # Remote state storage provides:
  #   1. Team Collaboration: Multiple team members can work with shared state
  #   2. State Locking: DynamoDB prevents concurrent modifications
  #   3. Encryption: State file encrypted at rest using AES-256
  #   4. Versioning: S3 bucket versioning enables state recovery
  #   5. Audit Trail: S3 access logs track all state file operations
  #
  # State File Security:
  #   - Contains sensitive data (database passwords, API keys)
  #   - Must never be committed to version control
  #   - Encrypted both at rest (S3 SSE) and in transit (HTTPS)
  #
  # State Locking Mechanism:
  #   - DynamoDB table stores lock information with LockID as primary key
  #   - Lock acquired before state modification operations (plan, apply)
  #   - Lock automatically released after operation completes
  #   - Manual lock release available via: terraform force-unlock <LOCK_ID>
  #
  # Configuration Values:
  #   - bucket: S3 bucket name for state storage (must be globally unique)
  #   - key: Path to state file within bucket (environment-specific recommended)
  #   - region: AWS region for S3 bucket and DynamoDB table
  #   - encrypt: Enable server-side encryption (AES-256)
  #   - dynamodb_table: DynamoDB table name for state locking
  #   - kms_key_id: (Optional) Custom KMS key for encryption
  #   - workspace_key_prefix: (Optional) Prefix for workspace state files
  #
  # Best Practices:
  #   1. Create separate S3 buckets per environment (dev/test/prod)
  #   2. Enable S3 bucket versioning for state file history
  #   3. Enable S3 bucket logging for audit trail
  #   4. Apply least-privilege IAM policies for backend access
  #   5. Use separate DynamoDB tables per environment
  #   6. Enable Point-in-Time Recovery (PITR) on DynamoDB table
  #   7. Set S3 lifecycle policies for old state versions
  #
  # Environment-Specific Configuration:
  #   Different environments should use separate backend configurations:
  #
  #   Development:
  #     bucket         = "carddemo-terraform-state-dev"
  #     key            = "dev/terraform.tfstate"
  #     dynamodb_table = "carddemo-terraform-locks-dev"
  #
  #   Test:
  #     bucket         = "carddemo-terraform-state-test"
  #     key            = "test/terraform.tfstate"
  #     dynamodb_table = "carddemo-terraform-locks-test"
  #
  #   Production:
  #     bucket         = "carddemo-terraform-state-prod"
  #     key            = "prod/terraform.tfstate"
  #     dynamodb_table = "carddemo-terraform-locks-prod"
  #
  # Backend Initialization:
  #   Option 1: Use -backend-config flag during init
  #     terraform init \
  #       -backend-config="bucket=carddemo-terraform-state-prod" \
  #       -backend-config="key=prod/terraform.tfstate" \
  #       -backend-config="region=us-east-1" \
  #       -backend-config="dynamodb_table=carddemo-terraform-locks-prod"
  #
  #   Option 2: Use backend config file
  #     Create backend-prod.hcl:
  #       bucket         = "carddemo-terraform-state-prod"
  #       key            = "prod/terraform.tfstate"
  #       region         = "us-east-1"
  #       dynamodb_table = "carddemo-terraform-locks-prod"
  #       encrypt        = true
  #     Run: terraform init -backend-config=backend-prod.hcl
  #
  #   Option 3: Set values directly in this file (less flexible)
  #
  # State Migration:
  #   To migrate from local to remote state:
  #     1. Ensure S3 bucket and DynamoDB table exist
  #     2. Add backend configuration to this file
  #     3. Run: terraform init -migrate-state
  #     4. Confirm migration when prompted
  #     5. Verify remote state: terraform state list
  #     6. Delete local terraform.tfstate file
  #
  # Disaster Recovery:
  #   State file recovery procedures:
  #     1. S3 Versioning: Restore previous state version from S3
  #     2. S3 Replication: Failover to replicated bucket in different region
  #     3. Backup Strategy: Regular state file backups to separate storage
  #     4. State Import: Rebuild state using terraform import for resources
  #
  # Troubleshooting:
  #   - Lock timeout errors: Check DynamoDB for stale locks, force-unlock if needed
  #   - Permission errors: Verify IAM policies allow S3 and DynamoDB operations
  #   - State conflicts: Use terraform state commands to resolve inconsistencies
  #   - Corruption: Restore from S3 versioning or backup
  # ------------------------------------------------------------------------------
  backend "s3" {
    # S3 bucket name for storing Terraform state files
    # Must be globally unique across all AWS accounts
    # Recommended naming: <project>-terraform-state-<environment>
    # Example: carddemo-terraform-state-prod
    # Note: This value should be provided via backend config file or -backend-config flag
    bucket = "carddemo-terraform-state"

    # Path to state file within the S3 bucket
    # Recommended structure: <environment>/terraform.tfstate
    # Allows multiple environments in same bucket if needed (though separate buckets preferred)
    # Example: prod/terraform.tfstate
    key = "terraform.tfstate"

    # AWS region where S3 bucket and DynamoDB table are located
    # Should match the primary region for infrastructure deployment
    # Must be a valid AWS region identifier
    region = "us-east-1"

    # Enable server-side encryption for state file at rest
    # Uses AES-256 encryption (SSE-S3)
    # For enhanced security, specify kms_key_id to use AWS KMS encryption (SSE-KMS)
    encrypt = true

    # DynamoDB table name for state locking and consistency checking
    # Table must have a primary key named "LockID" with type String
    # Prevents concurrent state modifications by multiple users/processes
    # Recommended naming: <project>-terraform-locks-<environment>
    # Example: carddemo-terraform-locks-prod
    dynamodb_table = "carddemo-terraform-locks"

    # Additional recommended backend configuration options (commented for flexibility):

    # Use AWS KMS for encryption instead of default AES-256 (more secure)
    # kms_key_id = "arn:aws:kms:us-east-1:ACCOUNT_ID:key/KEY_ID"

    # Workspace-based state file organization (for terraform workspaces)
    # workspace_key_prefix = "workspaces"
    # Resulting key: workspaces/<workspace_name>/terraform.tfstate

    # Enable DynamoDB encryption at rest
    # (Note: This must be configured on the DynamoDB table itself, not in backend config)

    # IAM role to assume for backend operations (useful for cross-account scenarios)
    # role_arn = "arn:aws:iam::ACCOUNT_ID:role/TerraformBackendRole"

    # External ID for assuming IAM role (additional security for cross-account access)
    # external_id = "unique-external-id"

    # Session name for assumed role (helpful for CloudTrail auditing)
    # session_name = "terraform-backend-session"

    # S3 bucket access logging (must be configured on the S3 bucket itself)
    # Logs all access to state files for security auditing

    # S3 bucket versioning (must be enabled on the S3 bucket itself)
    # Maintains history of all state file changes for recovery

    # S3 bucket lifecycle policies (configured on bucket, not in backend config)
    # Automatically archive or delete old state versions after retention period

    # Skip AWS credentials validation (useful for custom AWS configurations)
    # skip_credentials_validation = false

    # Skip AWS region validation (useful for custom AWS configurations)
    # skip_region_validation = false

    # Skip metadata API check (useful for environments without EC2 metadata service)
    # skip_metadata_api_check = false

    # Force path-style S3 URLs instead of virtual-hosted-style (legacy compatibility)
    # force_path_style = false
  }
}

# ==============================================================================
# Backend Configuration Notes for DevOps Teams
# ==============================================================================
#
# Initial Setup Checklist:
# [ ] 1. Create S3 bucket with versioning enabled
# [ ] 2. Enable S3 bucket encryption (AES-256 or KMS)
# [ ] 3. Configure S3 bucket access logging to separate logging bucket
# [ ] 4. Apply S3 bucket policy restricting access to authorized IAM principals
# [ ] 5. Enable S3 bucket replication to DR region (production only)
# [ ] 6. Create DynamoDB table with LockID as primary key (String type)
# [ ] 7. Enable DynamoDB Point-in-Time Recovery (PITR)
# [ ] 8. Enable DynamoDB encryption at rest
# [ ] 9. Apply least-privilege IAM policies for Terraform execution role
# [ ] 10. Test backend initialization: terraform init
# [ ] 11. Verify state locking: run concurrent terraform plans
# [ ] 12. Document backend configuration in runbook
# [ ] 13. Set up state file backup automation
# [ ] 14. Configure CloudWatch alarms for DynamoDB lock table
#
# IAM Policy Requirements (Minimum Permissions):
# {
#   "Version": "2012-10-17",
#   "Statement": [
#     {
#       "Effect": "Allow",
#       "Action": [
#         "s3:ListBucket"
#       ],
#       "Resource": "arn:aws:s3:::carddemo-terraform-state"
#     },
#     {
#       "Effect": "Allow",
#       "Action": [
#         "s3:GetObject",
#         "s3:PutObject",
#         "s3:DeleteObject"
#       ],
#       "Resource": "arn:aws:s3:::carddemo-terraform-state/*"
#     },
#     {
#       "Effect": "Allow",
#       "Action": [
#         "dynamodb:DescribeTable",
#         "dynamodb:GetItem",
#         "dynamodb:PutItem",
#         "dynamodb:DeleteItem"
#       ],
#       "Resource": "arn:aws:dynamodb:*:*:table/carddemo-terraform-locks"
#     }
#   ]
# }
#
# AWS CLI Commands for Backend Setup:
#
# Create S3 bucket:
#   aws s3api create-bucket \
#     --bucket carddemo-terraform-state-prod \
#     --region us-east-1
#
# Enable S3 versioning:
#   aws s3api put-bucket-versioning \
#     --bucket carddemo-terraform-state-prod \
#     --versioning-configuration Status=Enabled
#
# Enable S3 encryption:
#   aws s3api put-bucket-encryption \
#     --bucket carddemo-terraform-state-prod \
#     --server-side-encryption-configuration '{
#       "Rules": [{
#         "ApplyServerSideEncryptionByDefault": {
#           "SSEAlgorithm": "AES256"
#         }
#       }]
#     }'
#
# Block public access:
#   aws s3api put-public-access-block \
#     --bucket carddemo-terraform-state-prod \
#     --public-access-block-configuration \
#       BlockPublicAcls=true,IgnorePublicAcls=true,\
#       BlockPublicPolicy=true,RestrictPublicBuckets=true
#
# Create DynamoDB table:
#   aws dynamodb create-table \
#     --table-name carddemo-terraform-locks-prod \
#     --attribute-definitions AttributeName=LockID,AttributeType=S \
#     --key-schema AttributeName=LockID,KeyType=HASH \
#     --billing-mode PAY_PER_REQUEST \
#     --region us-east-1
#
# Enable DynamoDB PITR:
#   aws dynamodb update-continuous-backups \
#     --table-name carddemo-terraform-locks-prod \
#     --point-in-time-recovery-specification \
#       PointInTimeRecoveryEnabled=true
#
# Mainframe Migration Context:
#   This backend configuration replaces manual mainframe infrastructure
#   provisioning processes used in the legacy CardDemo COBOL application.
#   Key improvements over mainframe approach:
#     - Declarative infrastructure definition vs. manual JCL job submissions
#     - Version-controlled infrastructure changes vs. undocumented manual changes
#     - Automated state tracking vs. manual configuration documentation
#     - Team collaboration via remote state vs. single-operator mainframe access
#     - Audit trail via S3 access logs vs. limited mainframe audit capabilities
#     - Disaster recovery via S3 versioning vs. tape backup procedures
#
# ==============================================================================
