# EKS Terraform Module

## Overview

This Terraform module provisions an Amazon Elastic Kubernetes Service (EKS) cluster to host the modernized CardDemo credit card management application. The EKS cluster replaces the IBM z/OS mainframe environment, specifically:

- **CICS Transaction Processing** → **Kubernetes Pods** running Spring Boot REST APIs
- **JCL Batch Jobs** → **Spring Batch Jobs** running as Kubernetes CronJobs and Jobs
- **VSAM File Access** → **PostgreSQL Database** access from containerized applications

### Architecture Transition

**Mainframe Architecture (Legacy):**
```
┌─────────────────────────────────────────┐
│     IBM z/OS Mainframe                  │
│  ┌─────────────────────────────────┐   │
│  │  CICS Transaction Server         │   │
│  │  - Online programs (15 COBOL)    │   │
│  │  - Sub-200ms response time       │   │
│  └─────────────────────────────────┘   │
│  ┌─────────────────────────────────┐   │
│  │  JCL Batch Jobs (28 jobs)        │   │
│  │  - COMBTRAN.jcl (transaction     │   │
│  │    combination and sorting)      │   │
│  │  - DALYREJS.jcl (daily reject    │   │
│  │    processing with GDG)          │   │
│  │  - 4-hour overnight window       │   │
│  └─────────────────────────────────┘   │
│  ┌─────────────────────────────────┐   │
│  │  VSAM KSDS Files (11 datasets)   │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
```

**Cloud-Native Architecture (Target):**
```
┌─────────────────────────────────────────────────────┐
│     Amazon EKS Cluster (Kubernetes 1.31)            │
│  ┌─────────────────────────────────────────────┐   │
│  │  Frontend Pods (React SPA)                   │   │
│  │  - Nginx serving static assets               │   │
│  │  - Horizontal Pod Autoscaler (3-10 replicas) │   │
│  └─────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────┐   │
│  │  Backend Pods (Spring Boot 3.4.5 / Java 21)  │   │
│  │  - REST API Controllers                      │   │
│  │  - Business logic services                   │   │
│  │  - Sub-200ms response time maintained        │   │
│  │  - Horizontal Pod Autoscaler (3-10 replicas) │   │
│  └─────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────┐   │
│  │  Spring Batch Jobs (Kubernetes CronJobs)     │   │
│  │  - TransactionProcessingJob (COMBTRAN)       │   │
│  │  - DailyRejectJob (DALYREJS)                 │   │
│  │  - AccountProcessingJob                      │   │
│  │  - 4-hour overnight window maintained        │   │
│  └─────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────┐   │
│  │  PostgreSQL StatefulSet (16.x)               │   │
│  │  - Replaces VSAM KSDS datasets               │   │
│  │  - Persistent Volume Claims (100Gi)          │   │
│  └─────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
        │
        ├──> External Payment Networks (ISO 8583)
        ├──> Bank Core Systems (file interfaces)
        └──> Regulatory Reporting (exact formats preserved)
```

### Performance Requirements

The EKS cluster must maintain or exceed mainframe performance benchmarks:

- **Transaction Response Time**: Sub-200ms for card authorization requests (95th percentile)
- **Throughput**: 10,000 transactions per second (TPS) at peak load
- **Batch Processing**: Complete all 28 batch jobs within 4-hour overnight window
- **Availability**: 99.9% uptime SLA with multi-AZ deployment

### Batch Job Migration Context

The following JCL batch jobs are converted to Spring Batch jobs running in this EKS cluster:

**COMBTRAN.jcl → TransactionCombinationJob:**
- **Original Function**: Sort current transaction file and system-generated transactions using mainframe SORT utility, then load combined file to VSAM KSDS transaction master
- **Modernized Implementation**: Spring Batch job with ItemReader (reading from PostgreSQL transaction staging table), ItemProcessor (sorting and deduplication logic), ItemWriter (writing to transaction master table)
- **Execution**: Kubernetes CronJob scheduled at 01:00 daily
- **Resource Allocation**: Dedicated Job pod with 2 CPU cores, 4Gi memory

**DALYREJS.jcl → DailyRejectProcessingJob:**
- **Original Function**: Define Generation Data Group (GDG) for daily reject files with 5 generation limit and automatic scratch
- **Modernized Implementation**: Spring Batch job processing rejected transactions with archival to S3 with versioning (replaces GDG concept)
- **Execution**: Kubernetes CronJob scheduled at 05:00 daily
- **Resource Allocation**: Dedicated Job pod with 1 CPU core, 2Gi memory

## Module Purpose

This Terraform module creates and configures:

1. **EKS Control Plane**: Managed Kubernetes cluster (version 1.31) with multi-AZ high availability
2. **Worker Node Groups**: Auto-scaling EC2 instances (3-10 nodes) for running application pods
3. **IAM Roles and Policies**: Cluster and node roles with appropriate permissions
4. **Security Groups**: Network access controls for cluster and worker communication
5. **OIDC Identity Provider**: For Kubernetes ServiceAccount to IAM role mapping (IRSA)
6. **Cluster Add-ons**: Essential components (VPC CNI, CoreDNS, kube-proxy)
7. **Logging and Monitoring**: CloudWatch integration for cluster audit logs

## Prerequisites

Before using this module, ensure the following prerequisites are met:

### Required Terraform Providers

```hcl
terraform {
  required_version = ">= 1.10.0"
  
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.82.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.35.0"
    }
  }
}
```

### AWS Provider Configuration

```hcl
provider "aws" {
  region = "us-east-1"  # Adjust to your target region
  
  default_tags {
    tags = {
      Project     = "CardDemo-Modernization"
      Environment = terraform.workspace
      ManagedBy   = "Terraform"
      Migration   = "Mainframe-to-Cloud"
    }
  }
}
```

### VPC Module Dependency

This EKS module **requires** the VPC module to be deployed first. The VPC module creates:

- VPC with CIDR block (e.g., 10.0.0.0/16)
- Public subnets across 3 availability zones (for load balancers)
- Private subnets across 3 availability zones (for EKS worker nodes)
- NAT Gateways for private subnet internet access
- Internet Gateway for public subnet access
- Route tables with appropriate routes

**Integration Point**: The VPC module outputs (`vpc_id`, `private_subnet_ids`, `public_subnet_ids`) are consumed as inputs to this EKS module.

## Input Variables

### Required Variables

| Variable Name | Type | Description |
|---------------|------|-------------|
| `cluster_name` | `string` | Name of the EKS cluster. Must be unique within the AWS account and region. Recommended format: `carddemo-eks-{environment}` |
| `vpc_id` | `string` | ID of the VPC where EKS cluster will be deployed. Must be obtained from VPC module output. |
| `private_subnet_ids` | `list(string)` | List of private subnet IDs for worker nodes. Must span at least 2 availability zones for high availability. Obtained from VPC module output. |
| `node_min_size` | `number` | Minimum number of worker nodes in the auto-scaling group. Recommended: `3` for production (one per AZ). |
| `node_max_size` | `number` | Maximum number of worker nodes in the auto-scaling group. Recommended: `10` for handling peak transaction loads (10,000 TPS). |
| `node_desired_size` | `number` | Desired number of worker nodes at cluster initialization. Recommended: `3` for baseline capacity. |

### Optional Variables

| Variable Name | Type | Default | Description |
|---------------|------|---------|-------------|
| `kubernetes_version` | `string` | `"1.31"` | Kubernetes version for EKS cluster control plane and worker nodes. Supported versions: 1.28, 1.29, 1.30, 1.31. |
| `node_instance_type` | `string` | `"t3.xlarge"` | EC2 instance type for worker nodes. `t3.xlarge` provides 4 vCPUs and 16 GiB memory suitable for Spring Boot applications. |
| `node_disk_size` | `number` | `100` | EBS volume size (in GiB) for worker node root disk. Must accommodate container images and temporary data. |
| `enable_cluster_logging` | `bool` | `true` | Enable CloudWatch logging for EKS control plane. Captures API server, audit, authenticator, controller manager, and scheduler logs. |
| `cluster_log_retention_days` | `number` | `7` | CloudWatch log retention period (days) for cluster logs. Recommended: 7 for dev, 30 for prod. |
| `enable_irsa` | `bool` | `true` | Enable IAM Roles for Service Accounts (IRSA) using OIDC provider. Required for fine-grained pod-level IAM permissions. |
| `tags` | `map(string)` | `{}` | Additional tags to apply to all resources created by this module. |

### Example Variable Declaration

```hcl
# terraform.tfvars
cluster_name        = "carddemo-eks-prod"
vpc_id              = "vpc-0a1b2c3d4e5f6g7h8"  # From VPC module output
private_subnet_ids  = [
  "subnet-0a1b2c3d4e5f6g7h8",
  "subnet-1b2c3d4e5f6g7h8i9",
  "subnet-2c3d4e5f6g7h8i9j0"
]
node_min_size       = 3
node_max_size       = 10
node_desired_size   = 3
kubernetes_version  = "1.31"
node_instance_type  = "t3.xlarge"

tags = {
  CostCenter = "IT-Modernization"
  Owner      = "Platform-Engineering"
}
```

## Output Values

This module exports the following outputs for use by other modules and components:

| Output Name | Type | Description | Usage |
|-------------|------|-------------|-------|
| `cluster_id` | `string` | Unique identifier of the EKS cluster | Used for kubectl configuration and resource tagging |
| `cluster_name` | `string` | Name of the EKS cluster | Used in CI/CD pipelines for kubectl context |
| `cluster_endpoint` | `string` | HTTPS endpoint URL for Kubernetes API server | Used by kubectl, Terraform Kubernetes provider, and CI/CD tools |
| `cluster_certificate_authority_data` | `string` | Base64-encoded certificate authority data for cluster | Required for secure kubectl communication |
| `cluster_security_group_id` | `string` | Security group ID attached to EKS control plane | Used for configuring additional ingress/egress rules |
| `cluster_oidc_issuer_url` | `string` | OIDC provider URL for IAM Roles for Service Accounts (IRSA) | Used to create IAM trust relationships for Kubernetes ServiceAccounts |
| `node_group_id` | `string` | Identifier of the primary EKS managed node group | Used for node group updates and monitoring |
| `node_group_arn` | `string` | ARN of the primary EKS managed node group | Used for IAM policy attachments and CloudWatch alarms |
| `node_security_group_id` | `string` | Security group ID attached to worker nodes | Used for configuring pod-to-pod and pod-to-external communication |
| `node_role_arn` | `string` | ARN of IAM role attached to worker nodes | Used for granting additional permissions to nodes |
| `cluster_autoscaler_role_arn` | `string` | ARN of IAM role for Cluster Autoscaler | Used by Cluster Autoscaler deployment for node scaling decisions |

### Example Output Usage

```hcl
# Configure kubectl
output "kubectl_config" {
  description = "kubectl configuration command"
  value       = "aws eks update-kubeconfig --name ${module.eks.cluster_name} --region us-east-1"
}

# Use in Kubernetes provider
provider "kubernetes" {
  host                   = module.eks.cluster_endpoint
  cluster_ca_certificate = base64decode(module.eks.cluster_certificate_authority_data)
  
  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args = [
      "eks",
      "get-token",
      "--cluster-name",
      module.eks.cluster_name
    ]
  }
}
```

## Usage Example

### Basic Usage

```hcl
module "vpc" {
  source = "./modules/vpc"
  
  vpc_cidr             = "10.0.0.0/16"
  availability_zones   = ["us-east-1a", "us-east-1b", "us-east-1c"]
  public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  private_subnet_cidrs = ["10.0.11.0/24", "10.0.12.0/24", "10.0.13.0/24"]
  
  tags = {
    Project = "CardDemo-Modernization"
  }
}

module "eks" {
  source = "./modules/eks"
  
  # Required inputs
  cluster_name       = "carddemo-eks-${terraform.workspace}"
  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids
  
  # Node group configuration
  node_min_size      = 3
  node_max_size      = 10
  node_desired_size  = 3
  
  # Cluster configuration
  kubernetes_version = "1.31"
  node_instance_type = "t3.xlarge"
  node_disk_size     = 100
  
  # Logging and monitoring
  enable_cluster_logging      = true
  cluster_log_retention_days  = 30
  
  # IRSA for fine-grained IAM
  enable_irsa = true
  
  tags = {
    Environment = terraform.workspace
    CostCenter  = "IT-Modernization"
    Migration   = "COBOL-to-Java"
  }
}
```

### Production-Grade Configuration

```hcl
module "eks" {
  source = "./modules/eks"
  
  cluster_name       = "carddemo-eks-prod"
  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids
  
  # Production node group sizing
  node_min_size      = 6   # 2 nodes per AZ for resilience
  node_max_size      = 20  # Scale to 20 nodes for peak loads
  node_desired_size  = 6
  
  # Production-grade instances
  kubernetes_version = "1.31"
  node_instance_type = "m5.2xlarge"  # 8 vCPUs, 32 GiB for production workloads
  node_disk_size     = 200
  
  # Enhanced logging
  enable_cluster_logging      = true
  cluster_log_retention_days  = 90
  
  enable_irsa = true
  
  tags = {
    Environment        = "production"
    CostCenter         = "IT-Modernization"
    Migration          = "COBOL-to-Java"
    Compliance         = "PCI-DSS"
    DataClassification = "Confidential"
  }
}
```

## IAM Roles Structure

The module creates the following IAM roles with appropriate trust relationships and policies:

### 1. EKS Cluster Role

**Purpose**: Assumed by EKS control plane to manage AWS resources on behalf of the cluster.

**Trust Relationship**:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "eks.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
```

**Attached AWS Managed Policies**:
- `arn:aws:iam::aws:policy/AmazonEKSClusterPolicy`: Allows EKS to create and manage AWS resources (EC2 instances, load balancers, security groups) required for cluster operation
- `arn:aws:iam::aws:policy/AmazonEKSVPCResourceController`: Enables EKS to manage ENIs for pod networking and security group management

**Permissions Summary**:
- Create/delete EC2 network interfaces for pod networking
- Manage security groups for cluster communication
- Create/manage Elastic Load Balancers for Kubernetes Services
- Access CloudWatch Logs for control plane logging

### 2. EKS Node Role

**Purpose**: Assumed by EC2 worker node instances to interact with EKS control plane and AWS services.

**Trust Relationship**:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ec2.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
```

**Attached AWS Managed Policies**:
- `arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy`: Core permissions for worker nodes to connect to EKS control plane
- `arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy`: Allows Amazon VPC CNI plugin to manage pod IP addresses and ENIs
- `arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly`: Enables nodes to pull container images from Amazon ECR

**Permissions Summary**:
- Register nodes with EKS cluster
- Describe EC2 instances and network interfaces
- Manage ENIs for pod networking
- Pull container images from ECR
- Write logs to CloudWatch
- Access EC2 Instance Metadata Service (IMDS)

### 3. Cluster Autoscaler Role (IRSA)

**Purpose**: Assumed by Cluster Autoscaler pod using IAM Roles for Service Accounts (IRSA) to scale node groups.

**Trust Relationship**:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::{account_id}:oidc-provider/{oidc_provider}"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "{oidc_provider}:sub": "system:serviceaccount:kube-system:cluster-autoscaler",
          "{oidc_provider}:aud": "sts.amazonaws.com"
        }
      }
    }
  ]
}
```

**Custom IAM Policy**:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "autoscaling:DescribeAutoScalingGroups",
        "autoscaling:DescribeAutoScalingInstances",
        "autoscaling:DescribeLaunchConfigurations",
        "autoscaling:DescribeTags",
        "autoscaling:SetDesiredCapacity",
        "autoscaling:TerminateInstanceInAutoScalingGroup",
        "ec2:DescribeLaunchTemplateVersions"
      ],
      "Resource": "*"
    }
  ]
}
```

**Permissions Summary**:
- Query Auto Scaling group configuration and status
- Modify Auto Scaling group desired capacity (scale up/down)
- Terminate instances in Auto Scaling group
- Describe EC2 launch template versions

### 4. EBS CSI Driver Role (IRSA)

**Purpose**: Assumed by EBS CSI driver pods to create and attach EBS volumes for persistent storage (PostgreSQL StatefulSet).

**Trust Relationship**: Similar to Cluster Autoscaler, using IRSA with `system:serviceaccount:kube-system:ebs-csi-controller-sa`.

**Attached AWS Managed Policy**:
- `arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy`

**Permissions Summary**:
- Create, delete, attach, and detach EBS volumes
- Create and delete EBS snapshots for backups
- Tag EBS volumes for tracking
- Describe EC2 instances, volumes, and snapshots

## Security Groups Configuration

The module creates and configures the following security groups:

### 1. EKS Cluster Security Group

**Purpose**: Controls traffic to/from EKS control plane (Kubernetes API server).

**Ingress Rules**:

| Protocol | Port Range | Source | Description |
|----------|------------|--------|-------------|
| TCP | 443 | Worker node security group | HTTPS communication from worker nodes to API server |
| TCP | 443 | VPC CIDR block | HTTPS access from VPC for kubectl, CI/CD tools |

**Egress Rules**:

| Protocol | Port Range | Destination | Description |
|----------|------------|-------------|-------------|
| TCP | 1025-65535 | Worker node security group | Kubelet communication to worker nodes |
| TCP | 443 | 0.0.0.0/0 | HTTPS for pulling images, accessing AWS APIs |

### 2. Worker Node Security Group

**Purpose**: Controls traffic to/from worker node EC2 instances and pods.

**Ingress Rules**:

| Protocol | Port Range | Source | Description |
|----------|------------|--------|-------------|
| ALL | ALL | Same security group | Allow all pod-to-pod and node-to-node communication within cluster |
| TCP | 1025-65535 | Cluster security group | Allow kubelet and pod communication from control plane |
| TCP | 443 | Cluster security group | Allow HTTPS from control plane for admission webhooks |
| TCP | 8080 | Application Load Balancer SG | Backend API traffic (Spring Boot application) |
| TCP | 80 | Application Load Balancer SG | Frontend traffic (React SPA served by Nginx) |
| TCP | 5432 | Same security group | PostgreSQL database access from backend pods |

**Egress Rules**:

| Protocol | Port Range | Destination | Description |
|----------|------------|-------------|-------------|
| ALL | ALL | 0.0.0.0/0 | Allow all outbound traffic for pulling images, AWS API calls, external integrations |

**Security Best Practices**:
- Security groups follow principle of least privilege
- No SSH (port 22) ingress allowed (use AWS Systems Manager Session Manager for emergency access)
- Database port 5432 restricted to intra-cluster communication only
- All rules tagged for audit trail and compliance

## Cluster Add-ons

The module configures essential EKS add-ons required for cluster operation:

### 1. Amazon VPC CNI Plugin

**Version**: Latest compatible with Kubernetes 1.31 (automatically selected by EKS)

**Purpose**: Provides native VPC networking for Kubernetes pods. Each pod receives a secondary private IP address from the VPC subnet CIDR range.

**Key Features**:
- **Pod IP Addressing**: Pods receive IP addresses directly from VPC subnets, enabling direct communication with VPC resources (RDS, ElastiCache, on-premises via VPN/Direct Connect)
- **Security Group Support**: Assign EC2 security groups directly to pods for fine-grained network security
- **Network Performance**: High-throughput, low-latency networking matching EC2 instance network performance
- **IP Address Management**: Warm IP pool management to quickly allocate IPs to new pods

**Configuration**:
- **WARM_ENI_TARGET**: Number of spare ENIs to keep attached to nodes (default: 1)
- **WARM_IP_TARGET**: Number of spare IPs to keep available (default: 5 for quick pod scaling)
- **MINIMUM_IP_TARGET**: Minimum number of IPs per node (default: 10)

**Resource Allocation**:
- CPU: 25m (25 millicores)
- Memory: 128Mi

### 2. CoreDNS

**Version**: Latest compatible with Kubernetes 1.31

**Purpose**: Provides DNS resolution for Kubernetes Services and pods. Critical for service discovery within the cluster.

**Key Features**:
- **Service Discovery**: Resolves Kubernetes Service names to ClusterIP addresses (e.g., `backend-service.default.svc.cluster.local`)
- **Pod DNS**: Provides DNS names for individual pods (e.g., `10-0-11-25.default.pod.cluster.local`)
- **External DNS**: Forwards external DNS queries to VPC DNS resolver (Route 53 Resolver)
- **DNS Caching**: Caches DNS responses to reduce latency and external DNS query load

**High Availability Configuration**:
- **Replicas**: 2 (deployed across different nodes and AZs)
- **Anti-Affinity**: Ensures replicas run on different nodes
- **Resource Requests**: CPU 100m, Memory 128Mi per replica
- **Autoscaling**: Horizontal Pod Autoscaler scales replicas based on DNS query load

**Custom Configuration**:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: coredns
  namespace: kube-system
data:
  Corefile: |
    .:53 {
        errors
        health {
          lameduck 5s
        }
        ready
        kubernetes cluster.local in-addr.arpa ip6.arpa {
          pods insecure
          fallthrough in-addr.arpa ip6.arpa
          ttl 30
        }
        prometheus :9153
        forward . /etc/resolv.conf {
          max_concurrent 1000
        }
        cache 30
        loop
        reload
        loadbalance
    }
```

### 3. kube-proxy

**Version**: Latest compatible with Kubernetes 1.31

**Purpose**: Maintains network rules on worker nodes to enable Kubernetes Service networking. Implements Service load balancing using iptables or IPVS.

**Key Features**:
- **Service Load Balancing**: Distributes traffic to Service backend pods using round-robin
- **ClusterIP Implementation**: Creates virtual IP addresses for Services accessible within cluster
- **NodePort Implementation**: Exposes Services on static ports across all worker nodes
- **Session Affinity**: Maintains client IP session affinity when configured

**Mode Configuration**:
- **Mode**: iptables (default, suitable for clusters up to 1000 nodes)
- **Alternative**: IPVS mode for larger clusters with improved performance

**Resource Allocation**:
- CPU: 100m per node
- Memory: 64Mi per node

### 4. EBS CSI Driver (Optional but Recommended)

**Purpose**: Enables dynamic provisioning of Amazon EBS volumes as Kubernetes PersistentVolumes. Required for PostgreSQL StatefulSet with persistent storage.

**Key Features**:
- **Dynamic Provisioning**: Automatically creates EBS volumes based on PersistentVolumeClaim requests
- **Volume Types**: Supports gp3 (general purpose SSD), io2 (provisioned IOPS SSD), st1 (throughput optimized HDD)
- **Snapshot Support**: Create EBS snapshots for backup and restore operations
- **Volume Expansion**: Resize volumes online without pod restart

**Installation** (via Terraform Kubernetes provider):
```hcl
resource "kubernetes_storage_class" "ebs_gp3" {
  metadata {
    name = "ebs-gp3"
    annotations = {
      "storageclass.kubernetes.io/is-default-class" = "true"
    }
  }
  
  storage_provisioner    = "ebs.csi.aws.com"
  volume_binding_mode    = "WaitForFirstConsumer"
  allow_volume_expansion = true
  
  parameters = {
    type      = "gp3"
    encrypted = "true"
    fsType    = "ext4"
  }
}
```

## Node Group Configuration

### Managed Node Group

The module creates an EKS managed node group with the following configuration:

**Capacity Configuration**:
- **Minimum Size**: Configurable (recommended: 3 for production)
- **Maximum Size**: Configurable (recommended: 10 for baseline, 20 for high-traffic production)
- **Desired Size**: Configurable (recommended: 3 initially, autoscaler adjusts based on demand)

**Instance Configuration**:
- **Instance Type**: Configurable (default: `t3.xlarge` with 4 vCPUs, 16 GiB RAM)
  - **Development**: `t3.large` (2 vCPUs, 8 GiB) for cost optimization
  - **Production**: `m5.2xlarge` (8 vCPUs, 32 GiB) for Spring Boot + PostgreSQL workload
  - **High-Memory**: `r5.xlarge` (4 vCPUs, 32 GiB) if Spring Batch jobs require additional memory
- **AMI Type**: `AL2_x86_64` (Amazon Linux 2, optimized for EKS)
- **Disk Size**: Configurable (default: 100 GiB gp3 SSD)
- **Disk Encryption**: Enabled using AWS KMS default key

**Networking**:
- **Subnets**: Deployed across private subnets in multiple availability zones for high availability
- **Public IP**: Disabled (nodes communicate via NAT Gateway)

**Scaling Configuration**:

The node group integrates with Kubernetes Cluster Autoscaler for automatic scaling:

**Scale-Up Triggers**:
- Pods in "Pending" state due to insufficient node capacity
- CPU utilization > 70% sustained for 5 minutes across all nodes
- Memory utilization > 80% sustained for 5 minutes

**Scale-Down Triggers**:
- Node utilization < 50% for 10 minutes consecutively
- All pods on node can be safely rescheduled to other nodes
- Node has been underutilized for scale-down delay period (default: 10 minutes)

**Scale-Down Protection**:
- Nodes with non-replicatable pods (e.g., StatefulSet pods, pods with local storage) are protected
- Minimum node count (configured `node_min_size`) is always maintained
- Nodes are drained gracefully (respecting pod disruption budgets) before termination

**Update Strategy**:
- **Max Unavailable**: 1 (ensures rolling updates with minimal disruption)
- **Update Policy**: Kubernetes version updates applied automatically to new nodes; existing nodes require manual upgrade or re-deployment

### Node Taints and Labels

**Default Labels** (applied automatically):
```yaml
node.kubernetes.io/instance-type: t3.xlarge
topology.kubernetes.io/zone: us-east-1a
eks.amazonaws.com/nodegroup: carddemo-node-group
```

**Custom Labels** (applied via module configuration):
```yaml
workload-type: general
environment: production
app: carddemo
```

**Node Taints** (optional, configured via module):

Example for dedicated batch processing nodes:
```yaml
taints:
  - key: batch-workload
    value: "true"
    effect: NoSchedule
```

Pods requiring batch processing nodes must specify matching tolerations:
```yaml
tolerations:
  - key: batch-workload
    operator: Equal
    value: "true"
    effect: NoSchedule
```

## VPC Module Integration

This EKS module tightly integrates with the VPC module. Understanding this relationship is critical for proper deployment.

### VPC Requirements

The VPC module must provide:

1. **VPC ID**: Unique identifier for the Virtual Private Cloud
2. **Private Subnets**: At least 2 subnets in different availability zones for worker nodes
3. **Public Subnets**: At least 2 subnets in different availability zones for load balancers
4. **NAT Gateways**: Configured in public subnets to enable private subnet internet access
5. **Route Tables**: Private subnet route tables with default route to NAT Gateway
6. **DNS Support**: VPC DNS resolution and DNS hostnames enabled

### Subnet Tagging for EKS

The VPC module must tag subnets appropriately for EKS to discover and use them:

**Private Subnet Tags** (for worker nodes):
```hcl
tags = {
  "kubernetes.io/role/internal-elb" = "1"
  "kubernetes.io/cluster/${cluster_name}" = "shared"
}
```

**Public Subnet Tags** (for internet-facing load balancers):
```hcl
tags = {
  "kubernetes.io/role/elb" = "1"
  "kubernetes.io/cluster/${cluster_name}" = "shared"
}
```

### Network Flow

```
┌─────────────────────────────────────────────────────────┐
│                        VPC (10.0.0.0/16)                 │
│                                                           │
│  ┌────────────────────── Public Subnets ──────────────┐ │
│  │  10.0.1.0/24 (AZ-a)  10.0.2.0/24 (AZ-b)            │ │
│  │  ┌──────────────┐     ┌──────────────┐             │ │
│  │  │ Internet     │     │ Application  │             │ │
│  │  │ Gateway      │     │ Load Balancer│             │ │
│  │  └──────────────┘     └──────────────┘             │ │
│  │  ┌──────────────┐     ┌──────────────┐             │ │
│  │  │ NAT Gateway  │     │ NAT Gateway  │             │ │
│  │  │ (AZ-a)       │     │ (AZ-b)       │             │ │
│  │  └──────────────┘     └──────────────┘             │ │
│  └──────────────────────────────────────────────────────┘ │
│           │                       │                       │
│           ▼                       ▼                       │
│  ┌───────────────────── Private Subnets ────────────────┐ │
│  │  10.0.11.0/24 (AZ-a) 10.0.12.0/24 (AZ-b)            │ │
│  │  ┌──────────────────────────────────────────────┐   │ │
│  │  │       EKS Worker Nodes                        │   │ │
│  │  │  ┌────────────┐  ┌────────────┐              │   │ │
│  │  │  │ Backend    │  │ Frontend   │              │   │ │
│  │  │  │ Pods (3-10)│  │ Pods (3-10)│              │   │ │
│  │  │  └────────────┘  └────────────┘              │   │ │
│  │  │  ┌────────────┐  ┌────────────┐              │   │ │
│  │  │  │ Spring     │  │ PostgreSQL │              │   │ │
│  │  │  │ Batch Jobs │  │ StatefulSet│              │   │ │
│  │  │  └────────────┘  └────────────┘              │   │ │
│  │  └──────────────────────────────────────────────┘   │ │
│  └──────────────────────────────────────────────────────┘ │
│                                                           │
└─────────────────────────────────────────────────────────┘
         │                                      │
         ▼                                      ▼
  External Payment                      Bank Core Systems
  Networks (ISO 8583)                   (File Interfaces)
```

### Integration Example

```hcl
# VPC module deployment
module "vpc" {
  source = "./modules/vpc"
  
  vpc_cidr             = "10.0.0.0/16"
  availability_zones   = ["us-east-1a", "us-east-1b", "us-east-1c"]
  public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  private_subnet_cidrs = ["10.0.11.0/24", "10.0.12.0/24", "10.0.13.0/24"]
  enable_nat_gateway   = true
  single_nat_gateway   = false  # One NAT Gateway per AZ for HA
  
  # Enable DNS for EKS
  enable_dns_hostnames = true
  enable_dns_support   = true
  
  tags = {
    "kubernetes.io/cluster/carddemo-eks-prod" = "shared"
  }
}

# EKS module consumes VPC outputs
module "eks" {
  source = "./modules/eks"
  
  cluster_name       = "carddemo-eks-prod"
  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids
  
  # Remaining configuration...
}

# Output kubectl configuration
output "configure_kubectl" {
  description = "Command to configure kubectl"
  value       = "aws eks update-kubeconfig --name ${module.eks.cluster_name} --region us-east-1"
}
```

## Post-Deployment Configuration

After the EKS cluster is provisioned, perform the following configuration steps:

### 1. Configure kubectl

```bash
# Update kubeconfig for cluster access
aws eks update-kubeconfig --name carddemo-eks-prod --region us-east-1

# Verify connectivity
kubectl get nodes
kubectl get namespaces
```

### 2. Deploy Cluster Autoscaler

```bash
# Apply Cluster Autoscaler with IRSA
kubectl apply -f - <<EOF
apiVersion: v1
kind: ServiceAccount
metadata:
  name: cluster-autoscaler
  namespace: kube-system
  annotations:
    eks.amazonaws.com/role-arn: ${CLUSTER_AUTOSCALER_ROLE_ARN}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cluster-autoscaler
  namespace: kube-system
spec:
  replicas: 1
  selector:
    matchLabels:
      app: cluster-autoscaler
  template:
    metadata:
      labels:
        app: cluster-autoscaler
    spec:
      serviceAccountName: cluster-autoscaler
      containers:
      - image: registry.k8s.io/autoscaling/cluster-autoscaler:v1.31.0
        name: cluster-autoscaler
        command:
          - ./cluster-autoscaler
          - --v=4
          - --stderrthreshold=info
          - --cloud-provider=aws
          - --skip-nodes-with-local-storage=false
          - --expander=least-waste
          - --node-group-auto-discovery=asg:tag=k8s.io/cluster-autoscaler/enabled,k8s.io/cluster-autoscaler/carddemo-eks-prod
          - --balance-similar-node-groups
          - --skip-nodes-with-system-pods=false
        resources:
          limits:
            cpu: 100m
            memory: 600Mi
          requests:
            cpu: 100m
            memory: 600Mi
EOF
```

### 3. Deploy Metrics Server

```bash
# Required for Horizontal Pod Autoscaler
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

### 4. Deploy AWS Load Balancer Controller

```bash
# Required for Application Load Balancer provisioning via Ingress
helm repo add eks https://aws.github.io/eks-charts
helm repo update

helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=carddemo-eks-prod \
  --set serviceAccount.create=true \
  --set serviceAccount.name=aws-load-balancer-controller \
  --set serviceAccount.annotations."eks\.amazonaws\.com/role-arn"=${ALB_CONTROLLER_ROLE_ARN}
```

### 5. Deploy EBS CSI Driver

```bash
# Required for PersistentVolume provisioning
helm repo add aws-ebs-csi-driver https://kubernetes-sigs.github.io/aws-ebs-csi-driver
helm repo update

helm install aws-ebs-csi-driver aws-ebs-csi-driver/aws-ebs-csi-driver \
  -n kube-system \
  --set controller.serviceAccount.create=true \
  --set controller.serviceAccount.name=ebs-csi-controller-sa \
  --set controller.serviceAccount.annotations."eks\.amazonaws\.com/role-arn"=${EBS_CSI_ROLE_ARN}
```

## Maintenance and Operations

### Cluster Version Upgrades

EKS cluster and node group Kubernetes versions should be upgraded regularly to receive security patches and new features.

**Upgrade Process**:

1. **Control Plane Upgrade**:
   ```bash
   # Update Terraform configuration
   # Change kubernetes_version = "1.31" to "1.32"
   terraform plan
   terraform apply
   ```

2. **Node Group Upgrade**:
   ```bash
   # EKS managed node groups automatically use cluster version
   # Trigger rolling update via Terraform
   terraform apply -target=module.eks.aws_eks_node_group.main
   ```

3. **Validation**:
   ```bash
   kubectl get nodes
   kubectl version
   ```

### Monitoring and Logging

**CloudWatch Container Insights**:

Enable Container Insights for cluster and application metrics:

```bash
aws eks update-cluster-config \
  --name carddemo-eks-prod \
  --logging '{"clusterLogging":[{"types":["api","audit","authenticator","controllerManager","scheduler"],"enabled":true}]}'
```

**Key Metrics to Monitor**:
- Node CPU and memory utilization (target: <70% average)
- Pod count and status (watch for CrashLoopBackOff, Pending)
- API server request latency (target: p99 < 200ms)
- Cluster Autoscaler scale-up/down events
- Failed pod scheduling events

**Logging Strategy**:
- **Control Plane Logs**: Retained in CloudWatch for 30 days (production)
- **Application Logs**: Collected via Fluentd/Fluent Bit DaemonSet to CloudWatch
- **Audit Logs**: EKS audit logs for security and compliance

### Backup and Disaster Recovery

**Cluster Configuration Backup**:
- Store Terraform state in S3 with versioning enabled
- Store kubectl configuration manifests in Git repository

**Application Data Backup**:
- PostgreSQL backups using EBS snapshots (automated via Kubernetes CronJob)
- Transaction data archived to S3 for compliance (7-year retention)

**Disaster Recovery**:
- **RTO (Recovery Time Objective)**: 1 hour
- **RPO (Recovery Point Objective)**: 15 minutes (PostgreSQL continuous archiving)
- **Multi-AZ Deployment**: Cluster survives single AZ failure
- **Cross-Region Failover**: Manual failover to secondary region using Terraform

## Security Considerations

### Network Security

- **Private API Endpoint**: Control plane endpoint accessible only within VPC (public access can be disabled)
- **Pod Security Standards**: Enforce restricted pod security standards using PSS
- **Network Policies**: Implement Kubernetes NetworkPolicies for pod-to-pod traffic control
- **Security Groups**: Fine-grained security group rules limiting traffic to necessary ports

### Secrets Management

- **Kubernetes Secrets**: Store sensitive configuration (database credentials, JWT keys) as Kubernetes Secrets
- **AWS Secrets Manager Integration**: Use External Secrets Operator to sync secrets from AWS Secrets Manager
- **Encryption at Rest**: Enable EKS secrets encryption using AWS KMS

### Compliance

- **PCI DSS Requirements**: EKS cluster configuration meets PCI DSS Level 1 requirements for credit card processing
- **Audit Logging**: EKS audit logs capture all API server requests for compliance
- **Encryption**: All data encrypted at rest (EBS volumes, Secrets) and in transit (TLS)

## Troubleshooting

### Common Issues

**Issue**: Nodes fail to join cluster
- **Cause**: IAM role permissions, security group misconfiguration, or subnet routing issues
- **Resolution**: Verify node IAM role has AmazonEKSWorkerNodePolicy, check security group rules allow cluster-to-node communication, ensure private subnets have route to NAT Gateway

**Issue**: Pods stuck in Pending state
- **Cause**: Insufficient node capacity or resource requests exceed node allocatable resources
- **Resolution**: Check Cluster Autoscaler logs, increase node_max_size, or reduce pod resource requests

**Issue**: DNS resolution failures within pods
- **Cause**: CoreDNS pods unhealthy or VPC DNS not enabled
- **Resolution**: Verify CoreDNS pods running (`kubectl get pods -n kube-system -l k8s-app=kube-dns`), check VPC DNS settings

**Issue**: Load balancer not provisioning
- **Cause**: AWS Load Balancer Controller not installed or subnet tags missing
- **Resolution**: Install ALB controller, verify public subnets tagged with `kubernetes.io/role/elb=1`

### Useful Commands

```bash
# Check cluster status
aws eks describe-cluster --name carddemo-eks-prod

# View node group details
aws eks describe-nodegroup --cluster-name carddemo-eks-prod --nodegroup-name carddemo-node-group

# Check node status
kubectl get nodes -o wide

# View pod distribution across nodes
kubectl get pods -o wide --all-namespaces

# Check Cluster Autoscaler logs
kubectl logs -n kube-system deployment/cluster-autoscaler

# View control plane logs in CloudWatch
aws logs tail /aws/eks/carddemo-eks-prod/cluster --follow
```

## Cost Optimization

### Strategies

1. **Right-Size Instances**: Use AWS Compute Optimizer recommendations to select optimal instance types
2. **Spot Instances**: Use Spot instances for non-critical batch workloads (up to 90% cost savings)
3. **Cluster Autoscaler**: Ensure aggressive scale-down to minimize idle node costs
4. **Reserved Instances**: Purchase 1-year or 3-year Reserved Instances for baseline node capacity (up to 75% savings)
5. **Storage Optimization**: Use gp3 EBS volumes instead of gp2 (20% cost savings with same performance)

### Estimated Monthly Cost (Production)

**Baseline Configuration** (3 nodes, t3.xlarge):
- EKS Control Plane: $73
- Worker Nodes (3 × t3.xlarge): $292
- EBS Volumes (3 × 100GB gp3): $24
- NAT Gateway (2 × 1 month): $64
- Application Load Balancer: $23
- **Total**: ~$476/month

**Peak Configuration** (10 nodes, m5.2xlarge):
- EKS Control Plane: $73
- Worker Nodes (10 × m5.2xlarge): $3,840
- EBS Volumes (10 × 200GB gp3): $160
- NAT Gateway (2 × 1 month): $64
- Application Load Balancer: $23
- **Total**: ~$4,160/month

## References

- [Amazon EKS User Guide](https://docs.aws.amazon.com/eks/latest/userguide/)
- [EKS Best Practices Guide](https://aws.github.io/aws-eks-best-practices/)
- [Kubernetes Documentation](https://kubernetes.io/docs/)
- [Terraform AWS EKS Module](https://registry.terraform.io/modules/terraform-aws-modules/eks/aws/latest)
- [CardDemo Mainframe Application](https://github.com/aws-samples/aws-mainframe-modernization-carddemo)

## Support and Contributing

For issues, questions, or contributions related to this EKS module:

1. Review the [CONTRIBUTING.md](../../../../CONTRIBUTING.md) guidelines
2. Open an issue in the project repository
3. Contact the Platform Engineering team

---

**Module Version**: 1.0.0  
**Last Updated**: 2025-10-26  
**Maintained By**: Platform Engineering Team  
**License**: Apache 2.0
