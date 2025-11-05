# OCR Processing Application

[![Build Status](https://github.com/your-org/ocr-processing-app/workflows/CI/badge.svg)](https://github.com/your-org/ocr-processing-app/actions)
[![Test Coverage](https://codecov.io/gh/your-org/ocr-processing-app/branch/main/graph/badge.svg)](https://codecov.io/gh/your-org/ocr-processing-app)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Node.js Version](https://img.shields.io/badge/node-22.x-brightgreen.svg)](https://nodejs.org)
[![Python Version](https://img.shields.io/badge/python-3.12-blue.svg)](https://www.python.org)

---

## Table of Contents

- [Project Overview](#project-overview)
- [Key Features](#key-features)
- [Architecture](#architecture)
- [Technology Stack](#technology-stack)
- [Quick Start](#quick-start)
- [Development Setup](#development-setup)
- [Project Structure](#project-structure)
- [API Documentation](#api-documentation)
- [Testing](#testing)
- [Deployment](#deployment)
- [Contributing](#contributing)
- [Security](#security)
- [License](#license)
- [Support & Contact](#support--contact)

---

## Project Overview

The **OCR Processing Application** is a comprehensive, production-ready system designed to automate document digitization through intelligent Optical Character Recognition (OCR) and Natural Language Processing (NLP). Built from the ground up as an enterprise-grade solution, this application transforms physical documents, PDFs, and images into searchable, editable, and structured digital formats.

### Purpose

This system addresses the critical need for automated document processing across multiple business functions:

- **Finance Teams**: Process invoices, receipts, and financial documents (100-500 docs/month per user)
- **Operations Coordinators**: Digitize forms, applications, and legacy paperwork (50-200 docs/month)
- **Legal Assistants**: Extract key terms and data from contracts (20-100 docs/month)
- **Administrative Staff**: Convert various business documents for records management

### Core Capabilities

- **Automated Document Digitization**: Convert physical documents into searchable digital formats with high accuracy
- **Intelligent Data Extraction**: Use OCR and NLP to identify document types and extract structured data fields with confidence scoring
- **Validation and Correction Workflows**: Allow users to review, correct, and approve extracted data through intuitive interfaces
- **Batch Processing**: Handle high-volume document processing (100+ documents simultaneously) with parallel processing
- **Flexible Integration**: Provide REST APIs, webhooks, and pre-built connectors for seamless integration with business systems
- **Enterprise-Grade Quality**: Deliver 99.9% uptime, SOC 2 compliance-ready architecture, and comprehensive audit trails
- **Learning Capabilities**: Use user corrections to continuously improve extraction accuracy over time

---

## Key Features

### Document Processing
- **Drag-and-Drop Upload Interface**: Intuitive file upload with real-time progress tracking
- **Hybrid OCR Engine**: Combines Tesseract (open-source) with Google Cloud Vision and AWS Textract for optimal accuracy
- **Multi-Page Document Support**: Process documents with 1-100+ pages
- **Document Type Classification**: Automatically classify documents (invoices, receipts, contracts, forms)
- **Field Extraction with Confidence Scoring**: Extract structured data with confidence scores for each field
- **Image Preprocessing**: Automatic deskew, denoise, contrast enhancement, and resolution optimization

### Validation and Review
- **Split-View Document Viewer**: Side-by-side display of document image and extracted data
- **Inline Field Editing**: Correct extracted data with immediate validation
- **Confidence Indicators**: Color-coded confidence scores highlight fields requiring review
- **Validation Rules Engine**: Configurable validation rules for data integrity
- **Approval Workflows**: Multi-step approval process for processed documents

### Search and Discovery
- **Full-Text Search**: Powered by ElasticSearch for sub-second search across 100,000+ documents
- **Advanced Filtering**: Filter by document type, date range, status, confidence score, and custom fields
- **Faceted Search**: Quick navigation using document metadata facets

### Template Builder
- **Visual Zone Selection**: Canvas-based tool for defining extraction zones on document images
- **Custom Field Mapping**: Map extraction zones to structured data fields
- **Template Testing**: Validate templates against sample documents with accuracy metrics
- **Template Versioning**: Version control with rollback capabilities

### Batch Processing
- **High-Volume Processing**: Process 100+ documents in parallel
- **Queue Management**: RabbitMQ-based job queue with auto-scaling workers
- **Progress Tracking**: Real-time progress updates via WebSocket
- **Batch Summary Reports**: Comprehensive processing results and error reports

### Data Export
- **Multiple Formats**: Export to JSON, CSV, XML, and Excel
- **Bulk Export**: Export multiple documents or date ranges
- **Scheduled Exports**: Automated export jobs with configurable schedules

### Integrations
- **REST API**: Comprehensive API with OpenAPI 3.0 documentation
- **Webhooks**: Event-based notifications for document lifecycle events
- **Pre-Built Connectors**:
  - QuickBooks (accounting integration)
  - Salesforce (CRM integration)
  - NetSuite (ERP integration)
  - SharePoint (document management)
  - Dropbox (cloud storage)

### Analytics and Reporting
- **Processing Volume Metrics**: Track documents processed by type, user, and time period
- **Accuracy Metrics**: Monitor OCR accuracy and confidence scores over time
- **User Activity Reports**: Track user engagement and productivity
- **Cost Analytics**: Monitor processing costs and budget utilization

### Security and Compliance
- **Multi-Tenant Architecture**: Complete data isolation per account
- **Role-Based Access Control (RBAC)**: Custom roles with granular permissions
- **OAuth 2.0 and SAML 2.0**: Social login and enterprise SSO support
- **Multi-Factor Authentication (MFA)**: TOTP-based 2FA
- **Audit Logging**: Comprehensive audit trail for all operations
- **Data Encryption**: TLS 1.3 in transit, AES-256 at rest
- **Virus Scanning**: ClamAV integration for all uploaded files

---

## Architecture

The OCR Processing Application is built using a **microservices architecture** to ensure scalability, maintainability, and independent service deployment.

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Users / API Clients                        │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    Frontend (Next.js / React)                       │
│  - Server-Side Rendering    - Client-Side State Management         │
│  - Authentication UI        - Real-Time Updates (WebSocket)         │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     API Gateway (NestJS)                            │
│  - Request Routing          - Rate Limiting                         │
│  - Authentication           - API Versioning                        │
└─────────────────────────────────────────────────────────────────────┘
                                   │
        ┌──────────────────────────┼──────────────────────────┐
        ▼                          ▼                          ▼
┌──────────────────┐   ┌──────────────────┐   ┌──────────────────┐
│   Document       │   │   Processing     │   │  Integration     │
│   Service        │   │   Service        │   │  Service         │
│  (NestJS)        │   │  (NestJS)        │   │  (NestJS)        │
└──────────────────┘   └──────────────────┘   └──────────────────┘
        │                          │                          │
        │                          ▼                          │
        │              ┌──────────────────┐                  │
        │              │  Message Queue   │                  │
        │              │  (RabbitMQ/SQS)  │                  │
        │              └──────────────────┘                  │
        │                          │                          │
        │                          ▼                          │
        │              ┌──────────────────┐                  │
        │              │   OCR Service    │                  │
        │              │   (FastAPI)      │                  │
        │              └──────────────────┘                  │
        │                          │                          │
        └──────────────────────────┼──────────────────────────┘
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         Data Layer                                  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌────────────┐│
│  │ PostgreSQL  │  │  MongoDB    │  │   Redis     │  │ElasticSearch││
│  │ (Metadata)  │  │ (Documents) │  │  (Cache)    │  │  (Search)   ││
│  └─────────────┘  └─────────────┘  └─────────────┘  └────────────┘│
└─────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   Object Storage (AWS S3 / GCS)                     │
│              - Original Documents   - Processed Artifacts           │
└─────────────────────────────────────────────────────────────────────┘
```

### Core Services

1. **Frontend Service (Next.js/React)**: Server-side rendered web application providing user interface and client-side interactions
2. **API Gateway Service (NestJS)**: Routes requests, handles authentication, rate limiting, and API versioning
3. **Document Management Service (NestJS)**: Manages document uploads, storage, metadata, and retrieval
4. **OCR Processing Service (Python/FastAPI)**: Executes OCR extraction using Tesseract and cloud APIs (Google Vision/AWS Textract)
5. **NLP Service (Python/FastAPI)**: Performs document classification, entity extraction, and validation using spaCy/Hugging Face
6. **Queue Management Service (RabbitMQ/SQS)**: Coordinates asynchronous job processing and worker task distribution
7. **Search Service (ElasticSearch)**: Provides full-text search capabilities across all processed documents
8. **Integration Service (NestJS)**: Manages webhooks, third-party API connections, and data export operations

### Data Flow

1. **Upload Stage**: User uploads → Virus scan → File validation → S3 storage → Generate tracking ID → Queue for processing
2. **OCR Stage**: Worker pulls from queue → OCR extraction (Tesseract + Cloud API) → Raw text storage → Confidence scoring
3. **NLP Stage**: Document classification → Field extraction → Validation rules application → Flag low-confidence items
4. **Storage Stage**: Structured data to PostgreSQL → Full document to MongoDB → Search index to ElasticSearch
5. **Notification Stage**: WebSocket/webhook notification → Email alert (if configured) → Update dashboard

For detailed architecture documentation, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Technology Stack

### Frontend
- **Framework**: React 18.3.1, Next.js 14.2.21
- **Language**: TypeScript 5.3.3
- **Styling**: TailwindCSS 3.4.15
- **State Management**: React Query 5.62.8
- **Form Handling**: React Hook Form 7.54.2
- **HTTP Client**: Axios 1.7.9
- **Real-Time**: Socket.io Client 4.8.1
- **PDF Rendering**: PDF.js 4.9.261
- **Charts**: Recharts 2.15.0

### Backend API
- **Framework**: NestJS 10.4.15
- **Language**: TypeScript 5.3.3
- **Runtime**: Node.js 22.x LTS
- **ORM**: TypeORM 0.3.20
- **Authentication**: Passport.js, JWT
- **Validation**: class-validator, class-transformer
- **Documentation**: Swagger/OpenAPI 3.0

### OCR Processing Service
- **Framework**: FastAPI 0.115.6
- **Language**: Python 3.12.12
- **OCR Engines**: Tesseract 5.5.0, Google Cloud Vision API, AWS Textract
- **NLP**: spaCy 3.8.3, Hugging Face Transformers 4.47.1
- **Image Processing**: Pillow 11.0.0, OpenCV 4.10.0
- **ML**: PyTorch 2.5.1, scikit-learn 1.6.0

### Databases
- **Relational**: PostgreSQL 16.6
- **Document Store**: MongoDB 7.0.15
- **Cache**: Redis 7.4.2
- **Search Engine**: ElasticSearch 8.17.0

### Message Queue
- **Message Broker**: RabbitMQ 4.0.5 (or AWS SQS)

### Infrastructure
- **Containerization**: Docker 27.4.1
- **Orchestration**: Kubernetes 1.31.x
- **Infrastructure as Code**: Terraform 1.10.3
- **Cloud Providers**: AWS or Google Cloud Platform
- **Object Storage**: AWS S3 or Google Cloud Storage

### Monitoring & Observability
- **APM**: DataDog or New Relic
- **Error Tracking**: Sentry 8.48.0
- **Metrics**: Prometheus 3.1.0
- **Visualization**: Grafana 11.4.0
- **Log Aggregation**: CloudWatch or GCP Operations

### Security
- **Virus Scanning**: ClamAV 1.4.1
- **Vulnerability Scanning**: Trivy 0.58.2, Snyk
- **Security Testing**: OWASP ZAP 2.15.0
- **Code Quality**: SonarQube 10.9

---

## Quick Start

### Prerequisites

Ensure you have the following installed on your system:

- **Node.js**: 22.x LTS ([Download](https://nodejs.org))
- **Python**: 3.12.12 ([Download](https://www.python.org))
- **Docker**: 27.4.1+ ([Download](https://www.docker.com))
- **Docker Compose**: 2.31.0+ ([Download](https://docs.docker.com/compose/install/))
- **Git**: 2.47.1+ ([Download](https://git-scm.com))

### Installation

1. **Clone the repository**:

```bash
git clone https://github.com/your-org/ocr-processing-app.git
cd ocr-processing-app
```

2. **Install root dependencies** (if using monorepo with Lerna/Turbo):

```bash
npm install
```

3. **Install frontend dependencies**:

```bash
cd frontend
npm install
cd ..
```

4. **Install backend dependencies**:

```bash
cd backend
npm install
cd ..
```

5. **Install OCR service dependencies**:

```bash
cd ocr-service
pip install -r requirements.txt
# Or using Poetry:
# poetry install
cd ..
```

6. **Set up environment variables**:

Copy the example environment files and configure them:

```bash
# Frontend
cp frontend/.env.local.example frontend/.env.local

# Backend
cp backend/.env.example backend/.env

# OCR Service
cp ocr-service/.env.example ocr-service/.env
```

Edit each `.env` file with your configuration (database URLs, API keys, etc.).

7. **Start infrastructure services** (PostgreSQL, MongoDB, Redis, ElasticSearch, RabbitMQ):

```bash
docker-compose up -d
```

Wait for all services to be healthy (check with `docker-compose ps`).

8. **Run database migrations**:

```bash
cd backend
npm run migration:run
cd ..
```

9. **Start all services**:

**Option A: Start all services together** (from root directory):

```bash
npm run dev
```

**Option B: Start services individually** (in separate terminals):

```bash
# Terminal 1: Frontend
cd frontend
npm run dev

# Terminal 2: Backend API
cd backend
npm run start:dev

# Terminal 3: OCR Service
cd ocr-service
uvicorn main:app --reload --host 0.0.0.0 --port 8000
```

10. **Access the application**:

- **Web Application**: [http://localhost:3000](http://localhost:3000)
- **Backend API**: [http://localhost:3001](http://localhost:3001)
- **API Documentation (Swagger)**: [http://localhost:3001/api/docs](http://localhost:3001/api/docs)
- **OCR Service**: [http://localhost:8000](http://localhost:8000)
- **OCR Service Docs**: [http://localhost:8000/docs](http://localhost:8000/docs)

### First Steps

1. **Create an account**: Navigate to [http://localhost:3000/auth/signup](http://localhost:3000/auth/signup)
2. **Upload a document**: Go to the dashboard and click "Upload Document"
3. **Review extracted data**: Once processing completes, view and correct extracted fields
4. **Explore features**: Try batch processing, template builder, and integrations

---

## Development Setup

### Detailed Environment Setup

#### Frontend Service

1. **Navigate to frontend directory**:

```bash
cd frontend
```

2. **Configure environment variables** (`.env.local`):

```env
# API Configuration
NEXT_PUBLIC_API_BASE_URL=http://localhost:3001
NEXT_PUBLIC_WS_URL=http://localhost:3001

# Authentication (NextAuth.js)
NEXTAUTH_URL=http://localhost:3000
NEXTAUTH_SECRET=your-secret-key-min-32-chars

# OAuth Providers
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
MICROSOFT_CLIENT_ID=your-microsoft-client-id
MICROSOFT_CLIENT_SECRET=your-microsoft-client-secret
```

3. **Development commands**:

```bash
npm run dev          # Start development server
npm run build        # Build for production
npm run start        # Start production server
npm run lint         # Run ESLint
npm run format       # Format code with Prettier
npm test             # Run tests
```

#### Backend Service

1. **Navigate to backend directory**:

```bash
cd backend
```

2. **Configure environment variables** (`.env`):

```env
# Application
NODE_ENV=development
PORT=3001

# Database - PostgreSQL
DB_HOST=localhost
DB_PORT=5432
DB_USERNAME=postgres
DB_PASSWORD=postgres
DB_NAME=ocr_db

# Database - MongoDB
MONGODB_URI=mongodb://localhost:27017/ocr_documents

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=

# ElasticSearch
ELASTICSEARCH_URL=http://localhost:9200
ELASTICSEARCH_USERNAME=elastic
ELASTICSEARCH_PASSWORD=changeme

# RabbitMQ
RABBITMQ_URL=amqp://localhost:5672

# JWT
JWT_ACCESS_SECRET=your-access-token-secret-min-32-chars
JWT_REFRESH_SECRET=your-refresh-token-secret-min-32-chars

# OAuth
GOOGLE_CLIENT_ID=your-google-client-id
GOOGLE_CLIENT_SECRET=your-google-client-secret
MICROSOFT_CLIENT_ID=your-microsoft-client-id
MICROSOFT_CLIENT_SECRET=your-microsoft-client-secret

# AWS (if using AWS services)
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=your-access-key
AWS_SECRET_ACCESS_KEY=your-secret-key
AWS_S3_BUCKET_NAME=ocr-documents-dev

# Google Cloud (if using GCP services)
GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account-key.json
GCS_BUCKET_NAME=ocr-documents-dev

# Email
SENDGRID_API_KEY=your-sendgrid-api-key
FROM_EMAIL=noreply@yourapp.com

# OCR Service
OCR_SERVICE_URL=http://localhost:8000
```

3. **Database migrations**:

```bash
npm run migration:generate -- -n MigrationName  # Generate new migration
npm run migration:run                            # Run pending migrations
npm run migration:revert                         # Revert last migration
```

4. **Development commands**:

```bash
npm run start:dev    # Start development server with hot reload
npm run build        # Build for production
npm run start:prod   # Start production server
npm run lint         # Run ESLint
npm run format       # Format code with Prettier
npm test             # Run unit tests
npm run test:e2e     # Run end-to-end tests
npm run test:cov     # Run tests with coverage
```

#### OCR Processing Service

1. **Navigate to OCR service directory**:

```bash
cd ocr-service
```

2. **Configure environment variables** (`.env`):

```env
# Application
ENVIRONMENT=development
PORT=8000

# OCR Configuration
TESSERACT_CMD=/usr/bin/tesseract
DEFAULT_OCR_ENGINE=hybrid  # tesseract, google_vision, aws_textract, hybrid
CONFIDENCE_THRESHOLD=0.85

# Google Cloud Vision
GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account-key.json

# AWS Textract
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=your-access-key
AWS_SECRET_ACCESS_KEY=your-secret-key

# Redis (for caching)
REDIS_HOST=localhost
REDIS_PORT=6379

# RabbitMQ (for job queue)
RABBITMQ_URL=amqp://localhost:5672
RABBITMQ_QUEUE=ocr_processing_queue
```

3. **Install Tesseract OCR**:

**Ubuntu/Debian**:
```bash
sudo apt-get update
sudo apt-get install -y tesseract-ocr tesseract-ocr-eng
```

**macOS**:
```bash
brew install tesseract
```

**Windows**:
Download installer from [GitHub Tesseract Releases](https://github.com/UB-Mannheim/tesseract/wiki)

4. **Development commands**:

```bash
uvicorn main:app --reload                 # Start development server
uvicorn main:app --host 0.0.0.0 --port 8000  # Start with custom host/port
pytest                                    # Run tests
pytest --cov=app                          # Run tests with coverage
black .                                   # Format code
flake8 .                                  # Run linter
mypy .                                    # Run type checker
```

### Running Tests

#### Run All Tests

```bash
npm test  # From root directory (if monorepo)
```

#### Frontend Tests

```bash
cd frontend
npm test                     # Run unit tests
npm run test:e2e             # Run E2E tests with Playwright
npm run test:coverage        # Run tests with coverage report
```

#### Backend Tests

```bash
cd backend
npm test                     # Run unit tests
npm run test:integration     # Run integration tests
npm run test:e2e             # Run E2E API tests
npm run test:cov             # Run all tests with coverage
```

#### OCR Service Tests

```bash
cd ocr-service
pytest                       # Run all tests
pytest tests/unit            # Run unit tests only
pytest tests/integration     # Run integration tests only
pytest --cov=app --cov-report=html  # Generate HTML coverage report
```

### Code Formatting and Linting

#### Frontend & Backend (TypeScript)

```bash
npm run format       # Format code with Prettier
npm run lint         # Run ESLint
npm run lint:fix     # Auto-fix linting issues
```

#### OCR Service (Python)

```bash
black .              # Format code with Black
flake8 .             # Run Flake8 linter
mypy .               # Run MyPy type checker
```

---

## Project Structure

```
ocr-processing-app/
│
├── frontend/                       # Next.js/React web application
│   ├── components/                 # React components
│   │   ├── auth/                   # Authentication components
│   │   ├── documents/              # Document-related components
│   │   ├── upload/                 # Upload components
│   │   ├── templates/              # Template builder components
│   │   ├── analytics/              # Analytics components
│   │   ├── search/                 # Search components
│   │   └── common/                 # Shared/common components
│   ├── pages/                      # Next.js pages (routes)
│   │   ├── api/                    # API routes
│   │   ├── auth/                   # Authentication pages
│   │   ├── dashboard/              # Dashboard pages
│   │   ├── documents/              # Document pages
│   │   ├── templates/              # Template pages
│   │   ├── analytics/              # Analytics pages
│   │   └── settings/               # Settings pages
│   ├── hooks/                      # Custom React hooks
│   ├── lib/                        # Utility libraries
│   ├── styles/                     # Global styles
│   ├── __tests__/                  # Test files
│   ├── public/                     # Static assets
│   ├── package.json                # Frontend dependencies
│   ├── tsconfig.json               # TypeScript configuration
│   ├── next.config.js              # Next.js configuration
│   └── tailwind.config.js          # TailwindCSS configuration
│
├── backend/                        # NestJS backend API service
│   ├── src/
│   │   ├── auth/                   # Authentication module
│   │   ├── users/                  # User management module
│   │   ├── documents/              # Document management module
│   │   ├── processing/             # Processing job module
│   │   ├── templates/              # Template management module
│   │   ├── integrations/           # Third-party integrations
│   │   ├── export/                 # Data export module
│   │   ├── search/                 # Search module
│   │   ├── analytics/              # Analytics module
│   │   ├── audit/                  # Audit logging module
│   │   ├── api-keys/               # API key management
│   │   ├── storage/                # Storage service (S3/GCS)
│   │   ├── notifications/          # Notification service
│   │   ├── database/               # Database configuration and migrations
│   │   │   ├── migrations/         # TypeORM migrations
│   │   │   └── schemas/            # MongoDB schemas
│   │   ├── common/                 # Common utilities
│   │   │   ├── decorators/         # Custom decorators
│   │   │   ├── filters/            # Exception filters
│   │   │   ├── guards/             # Auth guards
│   │   │   ├── interceptors/       # Interceptors
│   │   │   └── pipes/              # Validation pipes
│   │   ├── main.ts                 # Application entry point
│   │   └── app.module.ts           # Root module
│   ├── test/                       # Test files
│   │   ├── unit/                   # Unit tests
│   │   ├── integration/            # Integration tests
│   │   └── e2e/                    # E2E tests
│   ├── swagger/                    # OpenAPI specification
│   ├── package.json                # Backend dependencies
│   ├── tsconfig.json               # TypeScript configuration
│   └── nest-cli.json               # NestJS CLI configuration
│
├── ocr-service/                    # Python FastAPI OCR microservice
│   ├── app/
│   │   ├── routers/                # API routers
│   │   │   ├── ocr.py              # OCR processing endpoints
│   │   │   └── health.py           # Health check endpoint
│   │   ├── services/               # Business logic services
│   │   │   ├── ocr_service.py      # OCR orchestration
│   │   │   ├── tesseract_engine.py # Tesseract wrapper
│   │   │   ├── google_vision_engine.py  # Google Vision API
│   │   │   ├── aws_textract_engine.py   # AWS Textract API
│   │   │   ├── nlp_service.py      # NLP processing
│   │   │   ├── document_classifier.py   # Document classification
│   │   │   ├── entity_extractor.py # Entity extraction
│   │   │   └── field_validator.py  # Field validation
│   │   ├── models/                 # Pydantic models
│   │   │   ├── ocr_request.py      # Request models
│   │   │   ├── ocr_response.py     # Response models
│   │   │   ├── document_types.py   # Document type definitions
│   │   │   └── extraction_templates.py  # Template models
│   │   └── utils/                  # Utility functions
│   │       ├── image_preprocessor.py    # Image preprocessing
│   │       └── document_parser.py  # Document parsing
│   ├── tests/                      # Test files
│   │   ├── unit/                   # Unit tests
│   │   └── integration/            # Integration tests
│   ├── main.py                     # Application entry point
│   ├── config.py                   # Configuration management
│   ├── requirements.txt            # Python dependencies
│   └── pyproject.toml              # Poetry configuration
│
├── k8s/                            # Kubernetes manifests
│   ├── namespace.yaml              # Namespace definition
│   ├── configmaps/                 # ConfigMaps
│   ├── secrets/                    # Secret templates
│   ├── deployments/                # Deployment definitions
│   ├── services/                   # Service definitions
│   ├── ingress/                    # Ingress rules
│   └── hpa/                        # Horizontal Pod Autoscalers
│
├── terraform/                      # Infrastructure as Code
│   ├── main.tf                     # Main Terraform config
│   ├── variables.tf                # Input variables
│   ├── outputs.tf                  # Output values
│   └── modules/                    # Terraform modules
│       ├── vpc/                    # VPC module
│       ├── eks/                    # EKS/GKE module
│       ├── rds/                    # Database module
│       └── s3/                     # Storage module
│
├── .github/                        # GitHub specific files
│   └── workflows/                  # CI/CD workflows
│       ├── frontend-ci.yml         # Frontend CI/CD
│       ├── backend-ci.yml          # Backend CI/CD
│       ├── ocr-service-ci.yml      # OCR service CI/CD
│       ├── deploy-staging.yml      # Staging deployment
│       ├── deploy-production.yml   # Production deployment
│       └── security-scan.yml       # Security scanning
│
├── monitoring/                     # Monitoring configurations
│   ├── prometheus.yml              # Prometheus config
│   ├── grafana-dashboards/         # Grafana dashboards
│   └── alerting-rules.yml          # Alert rules
│
├── docs/                           # Documentation
│   ├── ARCHITECTURE.md             # Architecture documentation
│   ├── API.md                      # API documentation
│   ├── DEPLOYMENT.md               # Deployment guide
│   ├── DEVELOPMENT.md              # Development guide
│   ├── SECURITY.md                 # Security policies
│   ├── USER_GUIDE.md               # User documentation
│   ├── ADMIN_GUIDE.md              # Admin documentation
│   └── diagrams/                   # Architecture diagrams
│
├── Dockerfile.frontend             # Frontend Docker image
├── Dockerfile.backend              # Backend Docker image
├── Dockerfile.ocr-service          # OCR service Docker image
├── docker-compose.yml              # Local development environment
├── docker-compose.prod.yml         # Production-like environment
├── .gitignore                      # Git ignore patterns
├── .dockerignore                   # Docker ignore patterns
├── .editorconfig                   # Editor configuration
├── .prettierrc                     # Prettier configuration
├── .eslintrc.js                    # ESLint configuration
├── package.json                    # Root package.json (monorepo)
├── CONTRIBUTING.md                 # Contribution guidelines
├── LICENSE                         # Apache License 2.0
└── README.md                       # This file
```

---

## API Documentation

The OCR Processing Application provides a comprehensive REST API for programmatic access to all features.

### API Base URL

- **Development**: `http://localhost:3001/api/v1`
- **Production**: `https://api.yourapp.com/api/v1`

### Interactive API Documentation

- **Swagger UI**: [http://localhost:3001/api/docs](http://localhost:3001/api/docs)
- **OpenAPI Specification**: [/backend/swagger/openapi.yaml](./backend/swagger/openapi.yaml)

### Authentication

All API requests (except public endpoints) require authentication using one of these methods:

1. **JWT Bearer Token** (for user sessions):
```bash
Authorization: Bearer <access_token>
```

2. **API Key** (for integrations):
```bash
X-API-Key: <your_api_key>
```

### API Endpoints Overview

#### Authentication
- `POST /api/v1/auth/register` - Register new user
- `POST /api/v1/auth/login` - Login with credentials
- `POST /api/v1/auth/refresh` - Refresh access token
- `POST /api/v1/auth/logout` - Logout user

#### Documents
- `POST /api/v1/documents/upload` - Upload document for processing
- `GET /api/v1/documents` - List documents (paginated)
- `GET /api/v1/documents/:id` - Get document details
- `PATCH /api/v1/documents/:id/fields` - Update extracted fields
- `POST /api/v1/documents/:id/approve` - Approve document
- `DELETE /api/v1/documents/:id` - Delete document

#### Processing
- `GET /api/v1/processing/jobs/:id` - Get job status
- `POST /api/v1/processing/batch` - Create batch processing job
- `GET /api/v1/processing/jobs` - List processing jobs

#### Templates
- `GET /api/v1/templates` - List templates
- `POST /api/v1/templates` - Create template
- `GET /api/v1/templates/:id` - Get template details
- `PUT /api/v1/templates/:id` - Update template
- `DELETE /api/v1/templates/:id` - Delete template

#### Search
- `GET /api/v1/search/documents` - Search documents
- `GET /api/v1/search/suggest` - Get search suggestions

#### Export
- `POST /api/v1/export` - Export documents
- `GET /api/v1/export/:id/download` - Download export file

#### Integrations
- `GET /api/v1/integrations` - List integrations
- `POST /api/v1/integrations/:provider/connect` - Connect integration
- `POST /api/v1/integrations/webhooks` - Create webhook subscription

#### Analytics
- `GET /api/v1/analytics/metrics` - Get analytics metrics
- `GET /api/v1/analytics/processing-volume` - Get processing volume data
- `GET /api/v1/analytics/accuracy` - Get accuracy metrics

### Rate Limiting

- **Default**: 1000 requests per hour per account
- **Burst**: Up to 100 requests per minute
- Rate limit headers are included in all responses:
  - `X-RateLimit-Limit`: Total requests allowed
  - `X-RateLimit-Remaining`: Requests remaining
  - `X-RateLimit-Reset`: Timestamp when limit resets

### Error Handling

All errors follow this consistent format:

```json
{
  "success": false,
  "error": {
    "code": "ERROR_CODE",
    "message": "Human-readable error message",
    "details": [
      {
        "field": "field_name",
        "message": "Field-specific error"
      }
    ]
  }
}
```

### Example Usage

**Upload a Document**:

```bash
curl -X POST http://localhost:3001/api/v1/documents/upload \
  -H "Authorization: Bearer <your_token>" \
  -F "file=@/path/to/document.pdf" \
  -F "document_type=invoice"
```

**Response**:

```json
{
  "success": true,
  "data": {
    "id": "doc_abc123",
    "filename": "document.pdf",
    "status": "queued",
    "created_at": "2025-10-31T12:00:00Z"
  }
}
```

For comprehensive API documentation with examples, see [docs/API.md](docs/API.md).

---

## Testing

The OCR Processing Application maintains high test coverage (≥80%) across all services.

### Test Types

1. **Unit Tests**: Test individual functions and components in isolation
2. **Integration Tests**: Test API endpoints and database interactions
3. **End-to-End Tests**: Test complete user workflows

### Running Tests

#### All Tests

```bash
npm test  # Run all tests across all services
```

#### Frontend Tests

```bash
cd frontend

# Unit tests
npm test                          # Run in watch mode
npm test -- --coverage            # With coverage report

# E2E tests
npm run test:e2e                  # Run Playwright tests
npm run test:e2e:headed           # Run with browser visible
npm run test:e2e:debug            # Run in debug mode
```

#### Backend Tests

```bash
cd backend

# Unit tests
npm test                          # Run unit tests
npm run test:watch                # Watch mode

# Integration tests
npm run test:integration          # API endpoint tests

# E2E tests
npm run test:e2e                  # Full E2E API tests

# Coverage
npm run test:cov                  # All tests with coverage
```

#### OCR Service Tests

```bash
cd ocr-service

# All tests
pytest                            # Run all tests

# Specific test types
pytest tests/unit                 # Unit tests only
pytest tests/integration          # Integration tests only

# Coverage
pytest --cov=app                  # With coverage report
pytest --cov=app --cov-report=html  # HTML coverage report

# Verbose output
pytest -v                         # Verbose mode
pytest -v -s                      # With print statements
```

### Test Coverage Reports

After running tests with coverage, view reports:

**Frontend & Backend**:
```bash
open coverage/lcov-report/index.html  # macOS
xdg-open coverage/lcov-report/index.html  # Linux
```

**OCR Service**:
```bash
open htmlcov/index.html  # macOS
xdg-open htmlcov/index.html  # Linux
```

### Continuous Integration

All tests run automatically on:
- Every push to feature branches
- Every pull request
- Before merging to main branch

See CI/CD workflows in `.github/workflows/` for details.

---

## Deployment

The OCR Processing Application supports multiple deployment strategies.

### Deployment Options

1. **Docker Compose** (Development/Testing)
2. **Kubernetes** (Production - AWS EKS or Google GKE)
3. **Terraform** (Infrastructure provisioning)

### Docker Deployment

#### Build Images

```bash
# Frontend
docker build -f Dockerfile.frontend -t ocr-frontend:latest .

# Backend
docker build -f Dockerfile.backend -t ocr-backend:latest .

# OCR Service
docker build -f Dockerfile.ocr-service -t ocr-service:latest .
```

#### Run with Docker Compose

```bash
# Development
docker-compose up -d

# Production-like environment
docker-compose -f docker-compose.prod.yml up -d

# View logs
docker-compose logs -f

# Stop services
docker-compose down
```

### Kubernetes Deployment

#### Prerequisites

- Kubernetes cluster (EKS, GKE, or local Minikube)
- `kubectl` installed and configured
- Docker images pushed to container registry

#### Deploy to Kubernetes

```bash
# Create namespace
kubectl apply -f k8s/namespace.yaml

# Apply ConfigMaps and Secrets
kubectl apply -f k8s/configmaps/
kubectl apply -f k8s/secrets/

# Deploy databases and infrastructure
kubectl apply -f k8s/deployments/postgres-deployment.yaml
kubectl apply -f k8s/deployments/mongodb-deployment.yaml
kubectl apply -f k8s/deployments/redis-deployment.yaml
kubectl apply -f k8s/deployments/elasticsearch-deployment.yaml
kubectl apply -f k8s/deployments/rabbitmq-deployment.yaml

# Deploy application services
kubectl apply -f k8s/deployments/backend-deployment.yaml
kubectl apply -f k8s/deployments/ocr-service-deployment.yaml
kubectl apply -f k8s/deployments/frontend-deployment.yaml

# Deploy services
kubectl apply -f k8s/services/

# Deploy ingress
kubectl apply -f k8s/ingress/

# Deploy autoscalers
kubectl apply -f k8s/hpa/

# Check deployment status
kubectl get pods -n ocr-app
kubectl get services -n ocr-app
```

### Terraform Infrastructure

#### Initialize Terraform

```bash
cd terraform

# Initialize
terraform init

# Plan deployment
terraform plan -out=tfplan

# Apply changes
terraform apply tfplan
```

#### Terraform Modules

- **VPC**: Network infrastructure
- **EKS/GKE**: Kubernetes cluster
- **RDS**: PostgreSQL database
- **S3/GCS**: Object storage
- **CloudFront/CDN**: Content delivery

### Environment-Specific Deployments

#### Staging

```bash
# Via GitHub Actions
git push origin main  # Automatically deploys to staging

# Or manually
kubectl apply -f k8s/ --namespace=ocr-staging
```

#### Production

```bash
# Via GitHub Actions (requires approval)
# Create a release tag
git tag -a v1.0.0 -m "Release v1.0.0"
git push origin v1.0.0

# Or manually (not recommended)
kubectl apply -f k8s/ --namespace=ocr-production
```

### Health Checks

Verify deployment health:

```bash
# Application health
curl http://localhost:3001/health

# OCR service health
curl http://localhost:8000/api/v1/health

# Kubernetes health
kubectl get pods -n ocr-app
kubectl describe pod <pod-name> -n ocr-app
```

### Rollback

If deployment fails:

```bash
# Kubernetes rollback
kubectl rollout undo deployment/backend-deployment -n ocr-app
kubectl rollout undo deployment/ocr-service-deployment -n ocr-app
kubectl rollout undo deployment/frontend-deployment -n ocr-app

# Verify rollback
kubectl rollout status deployment/backend-deployment -n ocr-app
```

### Monitoring Deployment

```bash
# Watch deployment progress
kubectl get pods -n ocr-app -w

# View logs
kubectl logs -f deployment/backend-deployment -n ocr-app
kubectl logs -f deployment/ocr-service-deployment -n ocr-app

# Access dashboard
kubectl port-forward svc/grafana 3000:3000 -n monitoring
```

For comprehensive deployment documentation, see [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

---

## Contributing

We welcome contributions from the community! Whether it's bug fixes, feature enhancements, documentation improvements, or bug reports, your contributions are valued.

### How to Contribute

1. **Fork the repository**
2. **Create a feature branch**: `git checkout -b feature/your-feature-name`
3. **Make your changes**
4. **Write or update tests**
5. **Run tests and linting**: `npm test && npm run lint`
6. **Commit your changes**: `git commit -m "Add feature: your feature description"`
7. **Push to your fork**: `git push origin feature/your-feature-name`
8. **Create a Pull Request**

### Contribution Guidelines

- **Code Style**: Follow existing code style (ESLint, Prettier, Black)
- **Tests**: Maintain ≥80% test coverage
- **Documentation**: Update relevant documentation
- **Commit Messages**: Use clear, descriptive commit messages
- **Pull Requests**: Provide detailed description of changes

### Development Workflow

1. **Pick an issue** from the issue tracker or create a new one
2. **Discuss your approach** in the issue comments
3. **Implement your changes** following the coding standards
4. **Test thoroughly** (unit, integration, E2E)
5. **Submit a pull request** with clear description
6. **Respond to review feedback** promptly
7. **Celebrate** when your PR is merged! 🎉

### Code Review Process

- All PRs require approval from at least 2 maintainers
- Automated checks (tests, linting, security) must pass
- Changes must maintain or improve test coverage
- Documentation must be updated if applicable

### Code of Conduct

We are committed to providing a welcoming and inclusive environment. Please read and follow our [Code of Conduct](CODE_OF_CONDUCT.md).

### Reporting Bugs

Found a bug? Please create an issue with:
- Clear title and description
- Steps to reproduce
- Expected vs actual behavior
- Screenshots (if applicable)
- Environment details (OS, browser, versions)

### Suggesting Features

Have an idea? Create an issue with:
- Clear description of the feature
- Use cases and benefits
- Proposed implementation approach (optional)
- Mockups or examples (if applicable)

For detailed contribution guidelines, see [CONTRIBUTING.md](CONTRIBUTING.md).

---

## Security

Security is a top priority for the OCR Processing Application.

### Security Features

- **Data Encryption**: TLS 1.3 in transit, AES-256 at rest
- **Authentication**: JWT with refresh tokens, OAuth 2.0, SAML 2.0
- **Authorization**: Role-based access control (RBAC)
- **Multi-Factor Authentication**: TOTP-based 2FA
- **Virus Scanning**: ClamAV integration for file uploads
- **Rate Limiting**: Prevent abuse and DDoS
- **Audit Logging**: Comprehensive audit trail
- **Input Validation**: Protect against injection attacks
- **Security Headers**: Helmet.js for HTTP security headers

### Reporting Security Vulnerabilities

**DO NOT** create public GitHub issues for security vulnerabilities.

Instead, report security issues to:
- **Email**: security@yourapp.com
- **AWS Vulnerability Reporting**: [AWS Vulnerability Disclosure](https://aws.amazon.com/security/vulnerability-reporting/)

Include:
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if any)

We will:
- Acknowledge receipt within 24 hours
- Provide a detailed response within 72 hours
- Work with you to understand and address the issue
- Credit you in our security advisories (if desired)

### Security Best Practices

When using this application:
- Keep all dependencies updated
- Use strong passwords and enable MFA
- Rotate API keys regularly
- Monitor audit logs for suspicious activity
- Follow principle of least privilege for user roles
- Regularly backup your data
- Use HTTPS in production
- Store secrets in secure vaults (AWS Secrets Manager, GCP Secret Manager)

For detailed security policies, see [docs/SECURITY.md](docs/SECURITY.md).

---

## License

This project is licensed under the **Apache License 2.0**.

```
Copyright 2025 Your Organization

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

See the [LICENSE](LICENSE) file for full license text.

---

## Support & Contact

### Documentation

- **Architecture**: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- **API Reference**: [docs/API.md](docs/API.md)
- **Deployment Guide**: [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)
- **Development Guide**: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)
- **User Guide**: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)
- **Admin Guide**: [docs/ADMIN_GUIDE.md](docs/ADMIN_GUIDE.md)

### Get Help

- **Issue Tracker**: [GitHub Issues](https://github.com/your-org/ocr-processing-app/issues)
- **Discussions**: [GitHub Discussions](https://github.com/your-org/ocr-processing-app/discussions)
- **Email Support**: support@yourapp.com
- **Stack Overflow**: Tag questions with `ocr-processing-app`

### Community

- **Slack/Discord**: [Join our community](https://yourapp.com/community)
- **Twitter**: [@OcrApp](https://twitter.com/ocrapp)
- **Blog**: [https://blog.yourapp.com](https://blog.yourapp.com)

### Commercial Support

Enterprise support packages available. Contact: sales@yourapp.com

---

## Acknowledgments

This project uses the following open-source technologies:
- **React** and **Next.js** - Frontend framework
- **NestJS** - Backend framework
- **FastAPI** - OCR microservice framework
- **Tesseract OCR** - Open-source OCR engine
- **PostgreSQL**, **MongoDB**, **Redis**, **ElasticSearch** - Data layer
- **Docker** and **Kubernetes** - Containerization and orchestration

Special thanks to all contributors and the open-source community!

---

**Built with ❤️ by the OCR Processing Team**

---

## Quick Links

- [🚀 Quick Start](#quick-start)
- [📖 Documentation](docs/)
- [🐛 Report Bug](https://github.com/your-org/ocr-processing-app/issues)
- [💡 Request Feature](https://github.com/your-org/ocr-processing-app/issues)
- [🤝 Contributing](#contributing)
- [📄 License](#license)
