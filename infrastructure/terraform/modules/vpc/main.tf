#
# Terraform VPC Module for CardDemo Cloud Migration
# Purpose: Creates comprehensive AWS VPC networking infrastructure replacing mainframe VTAM networking
# Converted from: Mainframe VTAM networking configuration
#

# ============================================================================
# VPC Resource
# ============================================================================

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = var.enable_dns_support
  enable_dns_hostnames = var.enable_dns_hostnames

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-vpc-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# Public Subnets (across multiple availability zones)
# ============================================================================

resource "aws_subnet" "public" {
  count                   = length(var.availability_zones)
  vpc_id                  = aws_vpc.main.id
  cidr_block              = var.public_subnet_cidrs[count.index]
  availability_zone       = var.availability_zones[count.index]
  map_public_ip_on_launch = true

  tags = merge(
    var.common_tags,
    {
      Name                                            = "${var.project_name}-public-subnet-${var.availability_zones[count.index]}-${var.environment}"
      Environment                                     = var.environment
      Project                                         = var.project_name
      Tier                                            = "public"
      ManagedBy                                       = "Terraform"
      "kubernetes.io/role/elb"                        = "1"
      "kubernetes.io/cluster/${var.project_name}-${var.environment}" = "shared"
    }
  )
}

# ============================================================================
# Private Subnets (across multiple availability zones)
# ============================================================================

resource "aws_subnet" "private" {
  count                   = length(var.availability_zones)
  vpc_id                  = aws_vpc.main.id
  cidr_block              = var.private_subnet_cidrs[count.index]
  availability_zone       = var.availability_zones[count.index]
  map_public_ip_on_launch = false

  tags = merge(
    var.common_tags,
    {
      Name                                            = "${var.project_name}-private-subnet-${var.availability_zones[count.index]}-${var.environment}"
      Environment                                     = var.environment
      Project                                         = var.project_name
      Tier                                            = "private"
      ManagedBy                                       = "Terraform"
      "kubernetes.io/role/internal-elb"               = "1"
      "kubernetes.io/cluster/${var.project_name}-${var.environment}" = "shared"
    }
  )
}

# ============================================================================
# Internet Gateway (for public subnet internet access)
# ============================================================================

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-igw-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# Elastic IPs for NAT Gateways
# ============================================================================

resource "aws_eip" "nat" {
  count  = var.enable_nat_gateway ? (var.single_nat_gateway ? 1 : length(var.availability_zones)) : 0
  domain = "vpc"

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-eip-nat-${count.index + 1}-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
    }
  )

  depends_on = [aws_internet_gateway.main]
}

# ============================================================================
# NAT Gateways (for private subnet outbound connectivity)
# ============================================================================

resource "aws_nat_gateway" "main" {
  count         = var.enable_nat_gateway ? (var.single_nat_gateway ? 1 : length(var.availability_zones)) : 0
  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-nat-${count.index + 1}-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
    }
  )

  depends_on = [aws_internet_gateway.main]
}

# ============================================================================
# Public Route Table
# ============================================================================

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-public-rt-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      Tier        = "public"
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# Public Route (to Internet Gateway)
# ============================================================================

resource "aws_route" "public_internet_gateway" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.main.id
}

# ============================================================================
# Public Route Table Associations
# ============================================================================

resource "aws_route_table_association" "public" {
  count          = length(var.availability_zones)
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# ============================================================================
# Private Route Tables
# ============================================================================

resource "aws_route_table" "private" {
  count  = var.enable_nat_gateway ? (var.single_nat_gateway ? 1 : length(var.availability_zones)) : length(var.availability_zones)
  vpc_id = aws_vpc.main.id

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-private-rt-${count.index + 1}-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      Tier        = "private"
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# Private Routes (to NAT Gateway)
# ============================================================================

resource "aws_route" "private_nat_gateway" {
  count                  = var.enable_nat_gateway ? (var.single_nat_gateway ? 1 : length(var.availability_zones)) : 0
  route_table_id         = aws_route_table.private[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.main[count.index].id
}

# ============================================================================
# Private Route Table Associations
# ============================================================================

resource "aws_route_table_association" "private" {
  count          = length(var.availability_zones)
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = var.single_nat_gateway ? aws_route_table.private[0].id : aws_route_table.private[count.index].id
}

# ============================================================================
# Network ACL for Public Subnets
# ============================================================================

resource "aws_network_acl" "public" {
  vpc_id     = aws_vpc.main.id
  subnet_ids = aws_subnet.public[*].id

  # Allow all inbound traffic (customizable based on security requirements)
  ingress {
    protocol   = -1
    rule_no    = 100
    action     = "allow"
    cidr_block = "0.0.0.0/0"
    from_port  = 0
    to_port    = 0
  }

  # Allow all outbound traffic
  egress {
    protocol   = -1
    rule_no    = 100
    action     = "allow"
    cidr_block = "0.0.0.0/0"
    from_port  = 0
    to_port    = 0
  }

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-public-nacl-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      Tier        = "public"
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# Network ACL for Private Subnets
# ============================================================================

resource "aws_network_acl" "private" {
  vpc_id     = aws_vpc.main.id
  subnet_ids = aws_subnet.private[*].id

  # Allow all inbound traffic from VPC CIDR
  ingress {
    protocol   = -1
    rule_no    = 100
    action     = "allow"
    cidr_block = var.vpc_cidr
    from_port  = 0
    to_port    = 0
  }

  # Allow return traffic from internet (ephemeral ports)
  ingress {
    protocol   = "tcp"
    rule_no    = 110
    action     = "allow"
    cidr_block = "0.0.0.0/0"
    from_port  = 1024
    to_port    = 65535
  }

  # Allow all outbound traffic
  egress {
    protocol   = -1
    rule_no    = 100
    action     = "allow"
    cidr_block = "0.0.0.0/0"
    from_port  = 0
    to_port    = 0
  }

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-private-nacl-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      Tier        = "private"
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# CloudWatch Log Group for VPC Flow Logs
# ============================================================================

resource "aws_cloudwatch_log_group" "flow_logs" {
  count             = var.enable_flow_logs ? 1 : 0
  name              = "/aws/vpc/${var.project_name}-${var.environment}"
  retention_in_days = var.flow_logs_retention_days

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-vpc-flow-logs-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# IAM Role for VPC Flow Logs
# ============================================================================

resource "aws_iam_role" "flow_logs" {
  count = var.enable_flow_logs ? 1 : 0
  name  = "${var.project_name}-vpc-flow-logs-role-${var.environment}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "vpc-flow-logs.amazonaws.com"
        }
      }
    ]
  })

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-vpc-flow-logs-role-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# IAM Policy for VPC Flow Logs
# ============================================================================

resource "aws_iam_role_policy" "flow_logs" {
  count = var.enable_flow_logs ? 1 : 0
  name  = "${var.project_name}-vpc-flow-logs-policy-${var.environment}"
  role  = aws_iam_role.flow_logs[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents",
          "logs:DescribeLogGroups",
          "logs:DescribeLogStreams"
        ]
        Effect = "Allow"
        Resource = "*"
      }
    ]
  })
}

# ============================================================================
# VPC Flow Logs
# ============================================================================

resource "aws_flow_log" "main" {
  count                = var.enable_flow_logs ? 1 : 0
  iam_role_arn         = aws_iam_role.flow_logs[0].arn
  log_destination      = aws_cloudwatch_log_group.flow_logs[0].arn
  traffic_type         = "ALL"
  vpc_id               = aws_vpc.main.id
  log_destination_type = "cloud-watch-logs"

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-vpc-flow-logs-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# Security Group for VPC Endpoints
# ============================================================================

resource "aws_security_group" "vpc_endpoints" {
  name        = "${var.project_name}-vpc-endpoints-sg-${var.environment}"
  description = "Security group for VPC endpoints"
  vpc_id      = aws_vpc.main.id

  # Allow HTTPS traffic from VPC CIDR
  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
    description = "Allow HTTPS from VPC"
  }

  # Allow all outbound traffic
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
    description = "Allow all outbound traffic"
  }

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-vpc-endpoints-sg-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# VPC Endpoint for S3 (Gateway Type)
# ============================================================================

resource "aws_vpc_endpoint" "s3" {
  count        = var.enable_vpc_endpoints["s3"] ? 1 : 0
  vpc_id       = aws_vpc.main.id
  service_name = "com.amazonaws.${data.aws_region.current.name}.s3"

  vpc_endpoint_type = "Gateway"
  route_table_ids = concat(
    [aws_route_table.public.id],
    aws_route_table.private[*].id
  )

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-s3-endpoint-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# VPC Endpoint for ECR API (Interface Type)
# ============================================================================

resource "aws_vpc_endpoint" "ecr_api" {
  count             = var.enable_vpc_endpoints["ecr"] ? 1 : 0
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${data.aws_region.current.name}.ecr.api"
  vpc_endpoint_type = "Interface"

  security_group_ids = [aws_security_group.vpc_endpoints.id]
  subnet_ids         = aws_subnet.private[*].id

  private_dns_enabled = true

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-ecr-api-endpoint-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# VPC Endpoint for ECR DKR (Interface Type)
# ============================================================================

resource "aws_vpc_endpoint" "ecr_dkr" {
  count             = var.enable_vpc_endpoints["ecr"] ? 1 : 0
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${data.aws_region.current.name}.ecr.dkr"
  vpc_endpoint_type = "Interface"

  security_group_ids = [aws_security_group.vpc_endpoints.id]
  subnet_ids         = aws_subnet.private[*].id

  private_dns_enabled = true

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-ecr-dkr-endpoint-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# VPC Endpoint for CloudWatch Logs (Interface Type)
# ============================================================================

resource "aws_vpc_endpoint" "logs" {
  count             = var.enable_vpc_endpoints["cloudwatch"] ? 1 : 0
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${data.aws_region.current.name}.logs"
  vpc_endpoint_type = "Interface"

  security_group_ids = [aws_security_group.vpc_endpoints.id]
  subnet_ids         = aws_subnet.private[*].id

  private_dns_enabled = true

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-logs-endpoint-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# VPC Endpoint for EKS (Interface Type)
# ============================================================================

resource "aws_vpc_endpoint" "eks" {
  count             = var.enable_vpc_endpoints["eks"] ? 1 : 0
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${data.aws_region.current.name}.eks"
  vpc_endpoint_type = "Interface"

  security_group_ids = [aws_security_group.vpc_endpoints.id]
  subnet_ids         = aws_subnet.private[*].id

  private_dns_enabled = true

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-eks-endpoint-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# VPC Endpoint for EC2 (Interface Type)
# ============================================================================

resource "aws_vpc_endpoint" "ec2" {
  count             = var.enable_vpc_endpoints["ec2"] ? 1 : 0
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${data.aws_region.current.name}.ec2"
  vpc_endpoint_type = "Interface"

  security_group_ids = [aws_security_group.vpc_endpoints.id]
  subnet_ids         = aws_subnet.private[*].id

  private_dns_enabled = true

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-ec2-endpoint-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# VPC Endpoint for STS (Interface Type)
# ============================================================================

resource "aws_vpc_endpoint" "sts" {
  count             = var.enable_vpc_endpoints["sts"] ? 1 : 0
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${data.aws_region.current.name}.sts"
  vpc_endpoint_type = "Interface"

  security_group_ids = [aws_security_group.vpc_endpoints.id]
  subnet_ids         = aws_subnet.private[*].id

  private_dns_enabled = true

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-sts-endpoint-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# VPC Endpoint for Autoscaling (Interface Type)
# ============================================================================

resource "aws_vpc_endpoint" "autoscaling" {
  count             = var.enable_vpc_endpoints["autoscaling"] ? 1 : 0
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${data.aws_region.current.name}.autoscaling"
  vpc_endpoint_type = "Interface"

  security_group_ids = [aws_security_group.vpc_endpoints.id]
  subnet_ids         = aws_subnet.private[*].id

  private_dns_enabled = true

  tags = merge(
    var.common_tags,
    {
      Name        = "${var.project_name}-autoscaling-endpoint-${var.environment}"
      Environment = var.environment
      Project     = var.project_name
      ManagedBy   = "Terraform"
    }
  )
}

# ============================================================================
# Data Source for Current AWS Region
# ============================================================================

data "aws_region" "current" {}

# ============================================================================
# Data Source for Available Availability Zones
# ============================================================================

data "aws_availability_zones" "available" {
  state = "available"
}

