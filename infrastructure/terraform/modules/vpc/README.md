# Terraform VPC Module

## Overview

This Terraform module provisions AWS VPC (Virtual Private Cloud) networking infrastructure for the CardDemo application migration from IBM mainframe environment to modern cloud-native architecture. It replaces the legacy mainframe VTAM (Virtual Telecommunications Access Method) networking with AWS cloud networking services.

The module creates a production-grade, highly available VPC infrastructure designed to support the modernized CardDemo credit card management system, including:
- Spring Boot backend application pods running in Kubernetes
- React frontend application served via Nginx
- PostgreSQL database instances
- Supporting cloud-native services and infrastructure

## Features

- **Multi-AZ Subnets**: Public and private subnets across multiple Availability Zones for high availability and fault tolerance
- **NAT Gateways**: Configurable NAT gateway deployment (single or per-AZ) for secure outbound internet access from private subnets
- **VPC Endpoints**: Private connectivity to AWS services (S3, ECR, CloudWatch) without traversing the public internet
- **VPC Flow Logs**: Network traffic logging to CloudWatch Logs for security monitoring and troubleshooting
- **DNS Resolution**: Enabled DNS hostnames and DNS support for service discovery within the VPC
- **Internet Gateway**: Managed internet gateway for public subnet connectivity
- **Route Tables**: Separate route tables for public and private subnets with appropriate routing rules
- **Network ACLs**: Default network ACL configuration with customizable rules for additional security layers
- **Elastic IPs**: Automatic allocation of Elastic IP addresses for NAT gateways
- **CIDR Block Management**: Configurable VPC and subnet CIDR blocks with sensible defaults

## Prerequisites

Before using this module, ensure you have:

1. **AWS Provider Configuration**: Configure the AWS provider in your root Terraform configuration:
   ```hcl
   provider "aws" {
     region = "us-east-1"  # or your preferred region
   }
   ```

2. **Required IAM Permissions**: The IAM user or role executing Terraform must have permissions to:
   - Create and manage VPC resources (VPC, Subnets, Route Tables, Internet Gateway)
   - Create and manage NAT Gateways and Elastic IPs
   - Create and manage VPC Endpoints
   - Create and manage CloudWatch Log Groups
   - Create and manage IAM roles and policies (for VPC Flow Logs)

3. **Terraform Version**: This module requires Terraform >= 1.0

4. **AWS Provider Version**: This module requires AWS Provider >= 4.0

## Usage Example

### Basic Usage (Development Environment)

```hcl
module "vpc" {
  source = "./modules/vpc"

  vpc_name             = "carddemo-dev-vpc"
  vpc_cidr             = "10.0.0.0/16"
  availability_zones   = ["us-east-1a", "us-east-1b"]
  public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24"]
  private_subnet_cidrs = ["10.0.11.0/24", "10.0.12.0/24"]
  
  enable_nat_gateway     = true
  single_nat_gateway     = true  # Cost optimization for dev
  enable_dns_hostnames   = true
  enable_dns_support     = true
  enable_flow_logs       = true
  
  tags = {
    Environment = "development"
    Project     = "CardDemo"
    ManagedBy   = "Terraform"
  }
}
```

### Production Usage (High Availability)

```hcl
module "vpc" {
  source = "./modules/vpc"

  vpc_name             = "carddemo-prod-vpc"
  vpc_cidr             = "10.0.0.0/16"
  availability_zones   = ["us-east-1a", "us-east-1b", "us-east-1c"]
  public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  private_subnet_cidrs = ["10.0.11.0/24", "10.0.12.0/24", "10.0.13.0/24"]
  
  enable_nat_gateway     = true
  single_nat_gateway     = false  # NAT gateway per AZ for HA
  enable_dns_hostnames   = true
  enable_dns_support     = true
  enable_flow_logs       = true
  
  # Enable VPC endpoints for cost optimization and security
  enable_s3_endpoint       = true
  enable_ecr_endpoint      = true
  enable_logs_endpoint     = true
  
  tags = {
    Environment = "production"
    Project     = "CardDemo"
    ManagedBy   = "Terraform"
    CostCenter  = "Engineering"
  }
}
```

### With EKS Integration

```hcl
module "vpc" {
  source = "./modules/vpc"

  vpc_name             = "carddemo-eks-vpc"
  vpc_cidr             = "10.0.0.0/16"
  availability_zones   = ["us-east-1a", "us-east-1b", "us-east-1c"]
  public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  private_subnet_cidrs = ["10.0.11.0/24", "10.0.12.0/24", "10.0.13.0/24"]
  
  enable_nat_gateway   = true
  single_nat_gateway   = false
  
  # EKS-specific tags for subnet discovery
  public_subnet_tags = {
    "kubernetes.io/role/elb"                    = "1"
    "kubernetes.io/cluster/carddemo-eks-cluster" = "shared"
  }
  
  private_subnet_tags = {
    "kubernetes.io/role/internal-elb"           = "1"
    "kubernetes.io/cluster/carddemo-eks-cluster" = "shared"
  }
  
  tags = {
    Environment = "production"
    Project     = "CardDemo"
  }
}
```

## Input Variables

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| vpc_name | Name of the VPC | `string` | n/a | yes |
| vpc_cidr | CIDR block for the VPC | `string` | `"10.0.0.0/16"` | no |
| availability_zones | List of availability zones for subnet deployment | `list(string)` | n/a | yes |
| public_subnet_cidrs | List of CIDR blocks for public subnets (one per AZ) | `list(string)` | n/a | yes |
| private_subnet_cidrs | List of CIDR blocks for private subnets (one per AZ) | `list(string)` | n/a | yes |
| enable_nat_gateway | Enable NAT Gateway for private subnet internet access | `bool` | `true` | no |
| single_nat_gateway | Use a single NAT gateway for all private subnets (cost optimization) | `bool` | `false` | no |
| enable_dns_hostnames | Enable DNS hostnames in the VPC | `bool` | `true` | no |
| enable_dns_support | Enable DNS support in the VPC | `bool` | `true` | no |
| enable_flow_logs | Enable VPC Flow Logs to CloudWatch | `bool` | `true` | no |
| flow_logs_retention_days | Retention period for VPC Flow Logs in CloudWatch | `number` | `30` | no |
| enable_s3_endpoint | Enable VPC endpoint for S3 | `bool` | `true` | no |
| enable_ecr_endpoint | Enable VPC endpoint for ECR (Docker image registry) | `bool` | `false` | no |
| enable_logs_endpoint | Enable VPC endpoint for CloudWatch Logs | `bool` | `false` | no |
| public_subnet_tags | Additional tags for public subnets | `map(string)` | `{}` | no |
| private_subnet_tags | Additional tags for private subnets | `map(string)` | `{}` | no |
| tags | Tags to apply to all resources | `map(string)` | `{}` | no |

## Outputs

| Name | Description |
|------|-------------|
| vpc_id | ID of the created VPC |
| vpc_cidr | CIDR block of the VPC |
| vpc_arn | ARN of the VPC |
| public_subnet_ids | List of public subnet IDs |
| private_subnet_ids | List of private subnet IDs |
| public_subnet_cidrs | List of public subnet CIDR blocks |
| private_subnet_cidrs | List of private subnet CIDR blocks |
| nat_gateway_ids | List of NAT Gateway IDs |
| internet_gateway_id | ID of the Internet Gateway |
| public_route_table_id | ID of the public route table |
| private_route_table_ids | List of private route table IDs |
| s3_vpc_endpoint_id | ID of S3 VPC endpoint (if enabled) |
| ecr_vpc_endpoint_id | ID of ECR VPC endpoint (if enabled) |
| logs_vpc_endpoint_id | ID of CloudWatch Logs VPC endpoint (if enabled) |
| flow_logs_log_group_name | Name of CloudWatch Log Group for VPC Flow Logs |
| flow_logs_iam_role_arn | ARN of IAM role for VPC Flow Logs |

## Architecture

### Network Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             AWS Cloud (Region: us-east-1)                    │
│                                                                               │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    VPC (10.0.0.0/16)                                 │   │
│  │                                                                       │   │
│  │  ┌─────────────────────┐         ┌─────────────────────┐            │   │
│  │  │  Availability Zone A │         │  Availability Zone B │            │   │
│  │  │                     │         │                     │            │   │
│  │  │  ┌───────────────┐ │         │  ┌───────────────┐ │            │   │
│  │  │  │ Public Subnet │ │         │  │ Public Subnet │ │            │   │
│  │  │  │ 10.0.1.0/24   │◄┼─────────┼─►│ 10.0.2.0/24   │ │            │   │
│  │  │  └───────┬───────┘ │         │  └───────┬───────┘ │            │   │
│  │  │          │          │         │          │          │            │   │
│  │  │     ┌────▼────┐    │         │     ┌────▼────┐    │            │   │
│  │  │     │NAT GW A │    │         │     │NAT GW B │    │            │   │
│  │  │     └────┬────┘    │         │     └────┬────┘    │            │   │
│  │  │          │          │         │          │          │            │   │
│  │  │  ┌───────▼───────┐ │         │  ┌───────▼───────┐ │            │   │
│  │  │  │Private Subnet │ │         │  │Private Subnet │ │            │   │
│  │  │  │ 10.0.11.0/24  │ │         │  │ 10.0.12.0/24  │ │            │   │
│  │  │  │               │ │         │  │               │ │            │   │
│  │  │  │ EKS Nodes     │ │         │  │ EKS Nodes     │ │            │   │
│  │  │  │ RDS Database  │ │         │  │ RDS Database  │ │            │   │
│  │  │  └───────────────┘ │         │  └───────────────┘ │            │   │
│  │  └─────────────────────┘         └─────────────────────┘            │   │
│  │                                                                       │   │
│  │  Internet Gateway                                                    │   │
│  │         ▲                                                             │   │
│  └─────────┼─────────────────────────────────────────────────────────────┘   │
│            │                                                                │
└────────────┼────────────────────────────────────────────────────────────────┘
             │
        Public Internet
```

### VPC Endpoints Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    VPC (10.0.0.0/16)                         │
│                                                               │
│  ┌────────────────────┐         ┌─────────────────────────┐ │
│  │  Private Subnets   │         │    VPC Endpoints        │ │
│  │                    │         │                         │ │
│  │  ┌──────────────┐  │         │  ┌─────────────────┐   │ │
│  │  │ EKS Pods     │──┼─────────┼─►│ S3 Endpoint     │   │ │
│  │  │ (Spring Boot)│  │         │  └─────────────────┘   │ │
│  │  └──────────────┘  │         │                         │ │
│  │                    │         │  ┌─────────────────┐   │ │
│  │  ┌──────────────┐  │         │  │ ECR Endpoint    │   │ │
│  │  │ PostgreSQL   │──┼─────────┼─►│ (Docker Images) │   │ │
│  │  │ RDS Instance │  │         │  └─────────────────┘   │ │
│  │  └──────────────┘  │         │                         │ │
│  │                    │         │  ┌─────────────────┐   │ │
│  └────────────────────┘         │  │ CloudWatch Logs │   │ │
│                                 │  │ Endpoint        │   │ │
│                                 │  └─────────────────┘   │ │
│                                 └─────────────────────────┘ │
│                                                               │
│  (No Internet Gateway traversal - private connectivity)      │
└─────────────────────────────────────────────────────────────┘
```

## Network Design

### CIDR Allocation Strategy

The default VPC CIDR block is `10.0.0.0/16`, providing 65,536 IP addresses. This design supports the CardDemo application's requirement to host multiple environments and scale horizontally:

**VPC CIDR**: `10.0.0.0/16` (65,536 addresses)

**Public Subnets** (Internet-facing resources):
- **AZ A**: `10.0.1.0/24` (256 addresses) - Load balancers, NAT Gateway, bastion hosts
- **AZ B**: `10.0.2.0/24` (256 addresses) - Load balancers, NAT Gateway
- **AZ C**: `10.0.3.0/24` (256 addresses) - Load balancers, NAT Gateway

**Private Subnets** (Application and database tiers):
- **AZ A**: `10.0.11.0/24` (256 addresses) - EKS nodes, RDS instances
- **AZ B**: `10.0.12.0/24` (256 addresses) - EKS nodes, RDS instances
- **AZ C**: `10.0.13.0/24` (256 addresses) - EKS nodes, RDS instances

**Reserved CIDR Ranges** (for future expansion):
- `10.0.4.0/22` - Additional public subnets
- `10.0.20.0/22` - Additional private subnets
- `10.0.100.0/22` - VPN/Transit Gateway subnets
- `10.0.200.0/22` - Database-specific subnets

### Subnet Design Rationale

1. **Public Subnets**: Host resources requiring direct internet access:
   - Application Load Balancers (ALB) for frontend traffic
   - Network Load Balancers (NLB) for backend traffic
   - NAT Gateways for private subnet outbound traffic
   - Bastion hosts for administrative access (if required)

2. **Private Subnets**: Host application workloads without direct internet exposure:
   - EKS worker nodes running Spring Boot backend pods
   - EKS worker nodes running React frontend pods (Nginx)
   - RDS PostgreSQL database instances
   - ElastiCache nodes (if used for session management)

3. **Multi-AZ Deployment**: Subnets span multiple Availability Zones to ensure:
   - High availability (survive single AZ failure)
   - Load distribution across AZs
   - Reduced latency with cross-AZ replication
   - Compliance with fault-tolerance requirements

### Routing Configuration

**Public Route Table** (associated with public subnets):
- `0.0.0.0/0` → Internet Gateway (for internet-bound traffic)
- `10.0.0.0/16` → Local (for intra-VPC traffic)
- S3 prefix list → S3 VPC Endpoint (if enabled)

**Private Route Tables** (associated with private subnets):
- `0.0.0.0/0` → NAT Gateway (for outbound internet access)
- `10.0.0.0/16` → Local (for intra-VPC traffic)
- S3 prefix list → S3 VPC Endpoint (if enabled)
- ECR prefix list → ECR VPC Endpoint (if enabled)

## Security Considerations

### Network ACLs (NACLs)

The module uses AWS default Network ACLs with stateless packet filtering:
- **Inbound Rules**: Allow all traffic by default (can be restricted post-deployment)
- **Outbound Rules**: Allow all traffic by default (can be restricted post-deployment)

For production environments, consider implementing custom NACL rules:
```hcl
resource "aws_network_acl_rule" "private_inbound_https" {
  network_acl_id = module.vpc.default_network_acl_id
  rule_number    = 100
  protocol       = "tcp"
  rule_action    = "allow"
  cidr_block     = "0.0.0.0/0"
  from_port      = 443
  to_port        = 443
}
```

### Security Groups

While this module provisions VPC infrastructure, security groups are defined in dependent modules (EKS, RDS). Best practices:
- Use security groups as the primary security control (stateful filtering)
- Implement least-privilege access (allow only required ports and sources)
- Reference security groups by ID rather than CIDR blocks
- Document security group rules with descriptions

### VPC Endpoints Security Benefits

VPC Endpoints eliminate the need to traverse the internet for AWS service access:
1. **S3 VPC Endpoint**: Enables private connectivity to S3 for:
   - Application logs and backups
   - Terraform state storage
   - Static asset storage

2. **ECR VPC Endpoint**: Enables private Docker image pulls:
   - EKS nodes pull Spring Boot and React images without internet access
   - Reduced data transfer costs
   - Enhanced security (no public registry exposure)

3. **CloudWatch Logs VPC Endpoint**: Enables private log streaming:
   - Application logs from Spring Boot
   - VPC Flow Logs
   - EKS control plane logs

### VPC Flow Logs

VPC Flow Logs capture network traffic metadata for security analysis:
- **Monitoring**: Detect unusual traffic patterns or connection attempts
- **Troubleshooting**: Diagnose connectivity issues between resources
- **Compliance**: Meet audit requirements for network traffic logging
- **Forensics**: Investigate security incidents

Flow logs are stored in CloudWatch Logs with configurable retention (default 30 days).

### Encryption

- **VPC Traffic**: All traffic within the VPC is encrypted in transit by default (AWS network encryption)
- **Flow Logs**: CloudWatch Log Groups can be encrypted with KMS keys
- **VPC Endpoints**: All VPC endpoint traffic is encrypted in transit

## High Availability Design

### Multi-AZ Architecture

The module deploys resources across multiple Availability Zones (minimum 2, recommended 3):

**Benefits**:
- **Fault Tolerance**: Application remains available if one AZ fails
- **Zero RPO**: Data replication across AZs ensures no data loss
- **Maintenance Windows**: Perform maintenance in one AZ while others serve traffic

**AZ Distribution**:
- Public subnets in each AZ (for ALB/NLB targets)
- Private subnets in each AZ (for EKS nodes and RDS replicas)
- NAT Gateways in each AZ (when `single_nat_gateway = false`)

### NAT Gateway Redundancy

**Single NAT Gateway** (`single_nat_gateway = true`):
- One NAT Gateway in a single AZ
- Lower cost (~$32/month for one NAT Gateway)
- Single point of failure (if AZ fails, private subnets lose internet access)
- Suitable for development/test environments

**Multi-NAT Gateway** (`single_nat_gateway = false`):
- One NAT Gateway per AZ
- Higher cost (~$96/month for three NAT Gateways)
- No single point of failure (each AZ has independent internet egress)
- Suitable for production environments
- Meets CardDemo's high availability requirements

### RDS Multi-AZ Deployment

When deploying PostgreSQL RDS instances in this VPC:
- Enable Multi-AZ deployment for automatic failover
- Place primary and standby instances in different private subnets
- Use subnet groups spanning all private subnets

### EKS Node Distribution

When deploying EKS worker nodes in this VPC:
- Distribute node groups across all private subnets
- Configure cluster autoscaler to maintain balance across AZs
- Use pod topology spread constraints to distribute application pods

## Cost Optimization

### Development Environment

For development environments, minimize costs:

```hcl
module "vpc" {
  source = "./modules/vpc"
  
  vpc_name             = "carddemo-dev-vpc"
  availability_zones   = ["us-east-1a", "us-east-1b"]  # Only 2 AZs
  
  enable_nat_gateway   = true
  single_nat_gateway   = true  # Single NAT Gateway (~$32/month)
  
  enable_s3_endpoint   = true   # Free (no hourly charges)
  enable_ecr_endpoint  = false  # $7.20/month per AZ - disable for dev
  enable_logs_endpoint = false  # $7.20/month per AZ - disable for dev
  
  enable_flow_logs     = false  # Disable flow logs to save CloudWatch costs
}
```

**Estimated Monthly Cost**: ~$35 (NAT Gateway only)

### Production Environment

For production environments, prioritize availability:

```hcl
module "vpc" {
  source = "./modules/vpc"
  
  vpc_name             = "carddemo-prod-vpc"
  availability_zones   = ["us-east-1a", "us-east-1b", "us-east-1c"]  # 3 AZs
  
  enable_nat_gateway   = true
  single_nat_gateway   = false  # NAT Gateway per AZ (~$96/month)
  
  enable_s3_endpoint   = true   # Free - always enable
  enable_ecr_endpoint  = true   # $21.60/month (3 AZs) - saves data transfer costs
  enable_logs_endpoint = true   # $21.60/month (3 AZs) - saves data transfer costs
  
  enable_flow_logs     = true   # ~$10/month for 1GB logs
}
```

**Estimated Monthly Cost**: ~$150 (includes NAT Gateways, VPC Endpoints, Flow Logs)

### Cost Optimization Strategies

1. **VPC Endpoints Reduce Data Transfer Costs**:
   - S3 VPC Endpoint: Eliminates $0.09/GB data transfer charges for S3 access
   - ECR VPC Endpoint: Saves $0.09/GB for Docker image pulls (can save $100+/month)
   - CloudWatch Logs VPC Endpoint: Saves $0.09/GB for log streaming

2. **Single NAT Gateway for Non-Production**:
   - Development, staging, and test environments can use `single_nat_gateway = true`
   - Reduces costs from $96/month to $32/month
   - Acceptable risk for non-critical environments

3. **Flow Logs Selective Enablement**:
   - Enable flow logs only in production and security-sensitive environments
   - Use sampling to reduce log volume (not supported in this module yet)
   - Set shorter retention periods for development (7 days vs 30 days)

4. **Right-Size Subnet CIDR Blocks**:
   - Use /24 subnets (256 addresses) for most workloads
   - Avoid over-provisioning IP addresses
   - Leave room for expansion with reserved CIDR ranges

## Migration from Mainframe VTAM

This VPC module replaces IBM mainframe VTAM (Virtual Telecommunications Access Method) networking with AWS cloud networking:

### Legacy VTAM Concepts → AWS VPC Mapping

| Mainframe VTAM | AWS VPC Equivalent | Notes |
|----------------|-------------------|-------|
| VTAM Network | VPC | Isolated network environment |
| VTAM Subareas | Subnets | Logical network segments |
| VTAM Sessions | Security Groups | Control traffic between resources |
| SNA Routing | Route Tables | Define traffic routing rules |
| VTAM Gateway | Internet Gateway / NAT Gateway | Internet connectivity |
| VTAM ACLs | Network ACLs | Stateless packet filtering |

### Key Differences

1. **IP-Based vs SNA**: AWS uses standard IP networking instead of IBM SNA (Systems Network Architecture)
2. **Elastic Scaling**: VPC supports dynamic scaling of resources; VTAM required capacity planning
3. **Multi-AZ Availability**: AWS provides built-in fault tolerance; mainframe required separate GDPS setup
4. **Managed Services**: AWS manages underlying network infrastructure; mainframe required dedicated network administrators

### Benefits of AWS VPC over VTAM

- **Lower Operational Overhead**: No need to manage physical network infrastructure
- **Elastic Capacity**: Scale network resources on-demand without capacity planning
- **Global Reach**: Deploy VPCs in multiple AWS regions for global presence
- **Integrated Security**: Native integration with AWS security services (Security Groups, NACLs, VPC Flow Logs)
- **Cost Efficiency**: Pay only for resources used (NAT Gateways, VPC Endpoints, data transfer)

## Troubleshooting

### Common Issues

1. **NAT Gateway Connectivity Issues**:
   - Verify NAT Gateway has an Elastic IP attached
   - Check private route tables have `0.0.0.0/0` → NAT Gateway route
   - Verify security groups allow outbound traffic on required ports

2. **EKS Nodes Cannot Pull Images**:
   - Enable ECR VPC Endpoint (`enable_ecr_endpoint = true`)
   - Verify private subnets have route to NAT Gateway (if not using VPC endpoint)
   - Check IAM role attached to EKS nodes has ECR pull permissions

3. **RDS Connection Timeouts**:
   - Verify RDS is deployed in private subnets
   - Check security group rules allow inbound traffic on PostgreSQL port (5432)
   - Verify route tables allow traffic between EKS subnets and RDS subnets

4. **High NAT Gateway Costs**:
   - Enable VPC endpoints for AWS services to reduce data transfer through NAT
   - Review VPC Flow Logs to identify top data consumers
   - Consider consolidating NAT Gateways in non-production environments

### Debugging Tools

- **VPC Flow Logs**: Analyze rejected connections and high-volume traffic
- **VPC Reachability Analyzer**: Test connectivity between resources (AWS Console)
- **Network Access Analyzer**: Verify network paths meet security requirements
- **AWS CloudWatch Metrics**: Monitor NAT Gateway bandwidth, connection counts

## Maintenance and Updates

### Terraform State Management

This module creates stateful resources (NAT Gateways, Elastic IPs). Best practices:
- Store Terraform state in S3 with versioning enabled
- Use state locking with DynamoDB to prevent concurrent modifications
- Regularly backup Terraform state files

### Updating the VPC

When updating VPC configuration:
1. **Review Terraform Plan**: Always review `terraform plan` output before applying
2. **Test in Non-Production**: Apply changes to dev/staging environments first
3. **Monitor Impact**: Watch CloudWatch metrics for connectivity issues after changes
4. **Rollback Plan**: Keep previous Terraform state for quick rollback if needed

### Adding Subnets

To add additional subnets:
1. Define new CIDR blocks from reserved ranges
2. Update `availability_zones`, `public_subnet_cidrs`, or `private_subnet_cidrs` variables
3. Apply Terraform changes (adds new subnets without disrupting existing resources)

### Upgrading NAT Gateways

To upgrade from single NAT Gateway to multi-NAT:
1. Update `single_nat_gateway = false`
2. Review cost impact (~$64/month increase for 2 additional NAT Gateways)
3. Apply Terraform changes (adds NAT Gateways in remaining AZs)
4. Existing connections may be briefly interrupted during route table updates

## Additional Resources

- [AWS VPC Documentation](https://docs.aws.amazon.com/vpc/)
- [AWS VPC Best Practices](https://docs.aws.amazon.com/vpc/latest/userguide/vpc-security-best-practices.html)
- [Terraform AWS VPC Module (Official)](https://registry.terraform.io/modules/terraform-aws-modules/vpc/aws/latest)
- [AWS VPC Endpoints Guide](https://docs.aws.amazon.com/vpc/latest/privatelink/vpc-endpoints.html)
- [EKS VPC Requirements](https://docs.aws.amazon.com/eks/latest/userguide/network_reqs.html)

## Support

For issues or questions related to this module:
- Review the troubleshooting section above
- Check AWS VPC CloudWatch metrics and VPC Flow Logs
- Consult the CardDemo migration team documentation
- Open an issue in the project repository

## License

This module is part of the CardDemo mainframe-to-cloud migration project and is licensed under Apache License 2.0.
