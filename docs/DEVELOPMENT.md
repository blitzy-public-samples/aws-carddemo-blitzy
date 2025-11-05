# OCR Processing Application - Development Environment Setup Guide

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Initial Setup](#initial-setup)
3. [Development Workflow](#development-workflow)
4. [Database Management](#database-management)
5. [Testing](#testing)
6. [Troubleshooting](#troubleshooting)
7. [Development Tools](#development-tools)

## Prerequisites

### Required Software

The following software must be installed on your development machine:

| Software | Version | Purpose |
|----------|---------|---------|
| **Node.js** | 22.21.1 (LTS) | JavaScript runtime for frontend and backend |
| **npm** | 10.9.4+ | Package manager (comes with Node.js) |
| **Python** | 3.12.3+ | Python runtime for OCR service |
| **pip** | 24.0+ | Python package installer |
| **Docker** | 27.4.1+ | Container runtime for infrastructure services |
| **Docker Compose** | 2.31.0+ | Multi-container orchestration |
| **Git** | 2.47.1+ | Version control |

### Optional but Recommended

- **nvm** (Node Version Manager) - For managing multiple Node.js versions
- **pyenv** (Python Version Manager) - For managing multiple Python versions
- **VS Code** or **IntelliJ IDEA** - IDEs with TypeScript/Python support

## Initial Setup

### 1. Clone the Repository

```bash
git clone <repository-url>
cd aws-carddemo-blitzy/blitzye0b635763
```

### 2. Install Node.js 22 (via nvm - Recommended)

```bash
# Install nvm if not already installed
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.0/install.sh | bash

# Load nvm
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"

# Install and use Node.js 22
nvm install 22
nvm use 22

# Verify installation
node --version  # Should show v22.21.1
npm --version   # Should show v10.9.4
```

**IMPORTANT:** Always ensure Node.js 22 is active before running any npm commands:
```bash
nvm use 22
```

### 3. Install Project Dependencies

This project uses a monorepo structure with Lerna/Turbo for workspace management.

```bash
# Install root dependencies
npm install

# Install frontend dependencies
cd frontend && npm install && cd ..

# Install backend dependencies
cd backend && npm install && cd ..

# Verify installations
npm run validate  # Runs validation across all workspaces
```

**Expected Results:**
- Root workspace: ~333 packages
- Frontend workspace: ~899 packages
- Backend workspace: ~976 packages
- Total: ~1,946 packages
- Security vulnerabilities: 0 ✅

### 4. Configure Environment Variables

#### Backend Environment Setup

```bash
cd backend
cp .env.example .env
```

Edit `backend/.env` and update the following values for local development:

```env
# Application
NODE_ENV=development
PORT=3000

# Database (matches docker-compose.yml)
DB_HOST=localhost
DB_PORT=5432
DB_USERNAME=ocr_dev
DB_PASSWORD=dev_password
DB_NAME=ocr_db
DB_LOGGING=true

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# MongoDB
MONGODB_URI=mongodb://localhost:27017/ocr_documents

# ElasticSearch
ELASTICSEARCH_URL=http://localhost:9200

# RabbitMQ
RABBITMQ_URL=amqp://guest:guest@localhost:5672
```

#### Frontend Environment Setup

```bash
cd frontend
cp .env.local.example .env.local
```

Edit `frontend/.env.local`:

```env
NEXT_PUBLIC_API_BASE_URL=http://localhost:3000
NEXT_PUBLIC_WS_URL=ws://localhost:3000
```

### 5. Start Infrastructure Services

The application requires PostgreSQL, MongoDB, Redis, ElasticSearch, and RabbitMQ. All services are defined in `docker-compose.yml`.

```bash
# Start all infrastructure services
docker compose up -d

# Verify all services are running
docker compose ps

# Check service health
docker compose logs --tail=50 postgres
docker compose logs --tail=50 mongodb
docker compose logs --tail=50 redis
```

**Service Ports:**
- PostgreSQL: `localhost:5432`
- MongoDB: `localhost:27017`
- Redis: `localhost:6379`
- ElasticSearch: `localhost:9200`
- RabbitMQ Management UI: `http://localhost:15672` (guest/guest)

**Critical: PostgreSQL UUID Extension**

The PostgreSQL container automatically enables the `uuid-ossp` extension on first initialization. This extension is required for UUID primary keys used throughout the application.

To verify the extension is enabled:
```bash
docker exec ocr-postgres-dev psql -U ocr_dev -d ocr_db -c \
  "SELECT extname, extversion FROM pg_extension WHERE extname = 'uuid-ossp';"
```

Expected output:
```
  extname  | extversion 
-----------+------------
 uuid-ossp | 1.1
```

### 6. Run Database Migrations

```bash
cd backend
npm run migration:run
```

This will create all database tables:
- `accounts` - Multi-tenant account management
- `users` - User authentication and profiles
- `roles` - Role definitions for RBAC
- `permissions` - Permission definitions for RBAC
- `user_roles` - User-to-role assignments
- `role_permissions` - Role-to-permission assignments

To verify migrations:
```bash
docker exec ocr-postgres-dev psql -U ocr_dev -d ocr_db -c "\dt"
```

## Development Workflow

### Running the Application

#### Backend API (NestJS)

```bash
cd backend

# Development mode with hot reload
npm run start:dev

# Debug mode
npm run start:debug

# Production mode
npm run build && npm run start:prod
```

The API will be available at: `http://localhost:3000`

API Documentation (Swagger): `http://localhost:3000/api/docs`

#### Frontend (Next.js)

```bash
cd frontend

# Development mode with hot reload
npm run dev

# Production build and start
npm run build && npm run start
```

The web application will be available at: `http://localhost:3000`

#### OCR Service (FastAPI - Coming Soon)

The OCR service implementation is pending. When ready:

```bash
cd ocr-service

# Create virtual environment
python3 -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate

# Install dependencies
pip install -r requirements.txt

# Run development server
uvicorn main:app --reload --port 8000
```

### Code Quality Checks

```bash
# Run linter (ESLint)
npm run lint

# Fix linting issues automatically
npm run lint:fix

# Format code (Prettier)
npm run format

# Type checking
npm run type-check
```

## Database Management

### TypeORM Migrations

#### Create a New Migration

```bash
cd backend
npm run migration:generate -- src/database/migrations/YourMigrationName
```

#### Run Migrations

```bash
npm run migration:run
```

#### Revert Last Migration

```bash
npm run migration:revert
```

### Database Access

#### PostgreSQL

```bash
# Connect via Docker
docker exec -it ocr-postgres-dev psql -U ocr_dev -d ocr_db

# Common queries
\dt               # List tables
\d+ table_name    # Describe table structure
\du               # List users
\l                # List databases
```

#### MongoDB

```bash
# Connect via Docker
docker exec -it ocr-mongodb-dev mongosh mongodb://localhost:27017/ocr_documents

# Common commands
show dbs
use ocr_documents
show collections
db.documents.find().limit(5)
```

#### Redis

```bash
# Connect via Docker
docker exec -it ocr-redis-dev redis-cli

# Common commands
KEYS *
GET key_name
FLUSHALL  # Clear all data (use with caution!)
```

### Resetting the Database

To completely reset your local database:

```bash
# Stop and remove all containers and volumes
docker compose down -v

# Restart infrastructure
docker compose up -d

# Wait for PostgreSQL to initialize (~5 seconds)
sleep 5

# Run migrations
cd backend && npm run migration:run
```

## Testing

### Backend Tests

```bash
cd backend

# Run all tests
npm test

# Run tests in watch mode
npm run test:watch

# Run tests with coverage
npm run test:cov

# Run integration tests
npm run test:e2e
```

**Test Environment:**
- Uses separate database: `ocr_test`
- Configured via `backend/.env.test`
- Automatically created and cleaned up

### Frontend Tests

```bash
cd frontend

# Run unit tests
npm test

# Run tests in watch mode
npm run test:watch

# Run tests with coverage
npm run test:cov

# Run E2E tests (Playwright)
npm run test:e2e
```

### Test Coverage Goals

- Minimum coverage: **80%**
- Critical paths: **100%**

## Troubleshooting

### Common Issues

#### Issue: "npm ERR! code ELIFECYCLE"

**Solution:** Ensure you're using Node.js 22:
```bash
nvm use 22
node --version  # Verify it shows v22.21.1
```

#### Issue: "Cannot connect to PostgreSQL"

**Solutions:**
1. Verify PostgreSQL container is running: `docker compose ps`
2. Check logs: `docker compose logs postgres`
3. Restart the container: `docker compose restart postgres`
4. Verify port 5432 is not in use: `lsof -i :5432`

#### Issue: "uuid-ossp extension not found"

**Solution:** The extension is automatically enabled. If missing:
```bash
docker exec ocr-postgres-dev psql -U ocr_dev -d ocr_db -c \
  'CREATE EXTENSION IF NOT EXISTS "uuid-ossp";'
```

#### Issue: "Port already in use"

**Solution:** Find and kill the process:
```bash
# Find process on port 3000
lsof -i :3000

# Kill the process
kill -9 <PID>
```

#### Issue: "Migration failed"

**Solutions:**
1. Check database connection in `.env`
2. Ensure PostgreSQL is running
3. Verify UUID extension is enabled
4. Check migration syntax
5. Revert and try again: `npm run migration:revert`

### Cleaning Up

#### Remove All Containers and Data

```bash
# Stop everything
docker compose down -v

# Remove all images (optional)
docker compose down --rmi all

# Clean npm cache if having dependency issues
npm cache clean --force
```

#### Start Fresh

```bash
# Remove node_modules and reinstall
rm -rf node_modules package-lock.json
rm -rf frontend/node_modules frontend/package-lock.json
rm -rf backend/node_modules backend/package-lock.json

# Reinstall
npm install
cd frontend && npm install && cd ..
cd backend && npm install && cd ..
```

## Development Tools

### Recommended VS Code Extensions

```json
{
  "recommendations": [
    "dbaeumer.vscode-eslint",
    "esbenp.prettier-vscode",
    "ms-vscode.vscode-typescript-next",
    "bradlc.vscode-tailwindcss",
    "christian-kohler.path-intellisense",
    "ms-python.python",
    "ms-python.vscode-pylance",
    "ms-azuretools.vscode-docker"
  ]
}
```

### Useful Commands

```bash
# View all npm scripts
npm run

# Check for outdated dependencies
npm outdated

# Audit security vulnerabilities
npm audit

# Update dependencies (with caution)
npm update

# Clean Docker system
docker system prune -a --volumes
```

### Database GUI Tools

- **PostgreSQL:** pgAdmin, DBeaver, DataGrip
- **MongoDB:** MongoDB Compass, Robo 3T
- **Redis:** RedisInsight, Medis

## Additional Resources

- [NestJS Documentation](https://docs.nestjs.com/)
- [Next.js Documentation](https://nextjs.org/docs)
- [TypeORM Documentation](https://typeorm.io/)
- [Docker Compose Documentation](https://docs.docker.com/compose/)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)

---

**Last Updated:** November 5, 2025  
**Maintained By:** Development Team  
**Questions?** Contact the development team or create an issue.
