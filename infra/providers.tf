###############################################################################
# AWS provider configuration
#
# Credentials and region are resolved from the standard AWS provider chain
# (environment variables, shared config/credentials files, or an assumed IAM
# role in CI). No credentials are ever hardcoded here (AAP §0.7.2: no hardcoded
# secrets).
###############################################################################
provider "aws" {
  region = var.aws_region

  # Stamp every created resource with consistent ownership/cost-allocation tags.
  default_tags {
    tags = {
      Project     = "aws-carddemo"
      Component   = "database"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
