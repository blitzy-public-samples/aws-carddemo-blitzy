###############################################################################
# AWS CardDemo - Terraform version & provider constraints
#
# Path-to-production Infrastructure as Code (Refine-PR directive D5): provision a
# managed Production PostgreSQL 16 database with automated backups and enforced
# TLS for the modernized CardDemo Spring Boot service.
#
# Pinning the Terraform core and provider versions keeps `terraform init` /
# `plan` / `apply` reproducible across engineers and CI.
###############################################################################
terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.40"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.5"
    }
  }

  # Remote state is intentionally left to the operator: uncomment and configure
  # an S3 backend (with DynamoDB state locking) per environment, e.g.
  #
  # backend "s3" {
  #   bucket         = "my-tf-state-bucket"
  #   key            = "carddemo/prod/postgres.tfstate"
  #   region         = "us-east-1"
  #   dynamodb_table = "my-tf-state-locks"
  #   encrypt        = true
  # }
}
