# Contributing Guidelines

Thank you for your interest in contributing to the **OCR Processing Application**. Whether it's a bug report, new feature, correction, or additional documentation, we greatly value feedback and contributions from our community.

This OCR Processing Application is a comprehensive, production-ready system for automating document digitization through OCR (Optical Character Recognition), intelligent data extraction, and workflow automation. We welcome contributions that help improve accuracy, performance, security, and user experience.

Please read through this document before submitting any issues or pull requests to ensure we have all the necessary information to effectively respond to your bug report or contribution.


## Development Environment Setup

Before contributing, please ensure you have the following tools installed:

### Required Software

- **Node.js**: Version 22.x LTS (for frontend and backend API services)
- **Python**: Version 3.12.12 or higher (for OCR processing service)
- **Docker**: Version 27.x or higher (for local development environment)
- **Docker Compose**: Version 2.31.x or higher (for orchestrating local services)
- **Git**: Version 2.47.x or higher

### Optional but Recommended

- **Poetry**: Version 1.8.5 (Python dependency management)
- **pnpm** or **yarn**: Fast package managers (alternative to npm)
- **kubectl**: For Kubernetes deployment testing
- **Terraform**: For infrastructure as code testing

### Local Development Setup

1. **Clone the repository**:
   ```bash
   git clone <repository-url>
   cd ocr-processing-app
   ```

2. **Install dependencies**:
   ```bash
   # Frontend
   cd frontend
   npm install
   
   # Backend API
   cd ../backend
   npm install
   
   # OCR Service
   cd ../ocr-service
   pip install -r requirements.txt
   # or using poetry
   poetry install
   ```

3. **Start local services** (PostgreSQL, MongoDB, Redis, ElasticSearch, RabbitMQ):
   ```bash
   docker-compose up -d
   ```

4. **Set up environment variables**:
   ```bash
   # Copy example environment files
   cp frontend/.env.local.example frontend/.env.local
   cp backend/.env.example backend/.env
   cp ocr-service/.env.example ocr-service/.env
   
   # Edit files with your local configuration
   ```

5. **Run database migrations**:
   ```bash
   cd backend
   npm run migration:run
   ```

6. **Start development servers**:
   ```bash
   # Terminal 1 - Frontend
   cd frontend
   npm run dev
   
   # Terminal 2 - Backend API
   cd backend
   npm run start:dev
   
   # Terminal 3 - OCR Service
   cd ocr-service
   uvicorn main:app --reload
   ```

### Verify Installation

- Frontend should be accessible at: `http://localhost:3000`
- Backend API should be accessible at: `http://localhost:3001`
- OCR Service should be accessible at: `http://localhost:8000`
- API documentation (Swagger) at: `http://localhost:3001/api/docs`


## Reporting Bugs/Feature Requests

We welcome you to use the GitHub issue tracker to report bugs or suggest features.

When filing an issue, please check existing open, or recently closed, issues to make sure somebody else hasn't already reported the issue. Please try to include as much information as you can. Details like these are incredibly useful:

* A reproducible test case or series of steps
* The version of our code being used
* Any modifications you've made relevant to the bug
* Anything unusual about your environment or deployment
* **For OCR-related issues**: Sample documents (with sensitive data removed), OCR confidence scores, expected vs actual output
* **For performance issues**: Document size, processing time, system resource usage
* **For API issues**: Request/response payloads, HTTP status codes, authentication method used


## Contributing via Pull Requests

Contributions via pull requests are much appreciated. Before sending us a pull request, please ensure that:

1. You are working against the latest source on the *main* branch.
2. You check existing open, and recently merged, pull requests to make sure someone else hasn't addressed the problem already.
3. You open an issue to discuss any significant work - we would hate for your time to be wasted.
4. Your code follows our **Code Quality Standards** (see below).
5. You have added appropriate **tests** with minimum 80% coverage for new code.
6. All existing and new tests pass locally.
7. You follow our **Commit Message Conventions** (see below).

To send us a pull request, please:

1. Fork the repository.
2. Modify the source; please focus on the specific change you are contributing. If you also reformat all the code, it will be hard for us to focus on your change.
3. **Run code formatters and linters** to ensure code quality.
4. **Ensure local tests pass** with `npm test` (frontend/backend) or `pytest` (OCR service).
5. Commit to your fork using **clear, conventional commit messages**.
6. Send us a pull request, answering any default questions in the pull request interface.
7. Pay attention to any **automated CI failures** reported in the pull request, and stay involved in the conversation.

GitHub provides additional document on [forking a repository](https://help.github.com/articles/fork-a-repo/) and [creating a pull request](https://help.github.com/articles/creating-a-pull-request/).


## Code Quality Standards

We maintain high code quality standards across all services. Please ensure your contributions meet the following requirements:

### TypeScript/JavaScript (Frontend & Backend)

- **Linting**: Code must pass ESLint with zero warnings
  ```bash
  npm run lint
  ```
- **Formatting**: Use Prettier for consistent code formatting
  ```bash
  npm run format
  ```
- **Type Safety**: Use TypeScript strict mode; avoid `any` types without justification
- **Naming Conventions**: 
  - Variables/functions: `camelCase`
  - Components: `PascalCase`
  - Constants: `UPPER_SNAKE_CASE`
  - Files: `kebab-case.ts` or `PascalCase.tsx` for components

### Python (OCR Service)

- **Formatting**: Use Black for code formatting
  ```bash
  black ocr-service/
  ```
- **Linting**: Code must pass Flake8 with zero errors
  ```bash
  flake8 ocr-service/
  ```
- **Type Checking**: Use MyPy for static type checking
  ```bash
  mypy ocr-service/
  ```
- **Type Hints**: All function signatures must include type hints
- **Naming Conventions**:
  - Variables/functions: `snake_case`
  - Classes: `PascalCase`
  - Constants: `UPPER_SNAKE_CASE`

### General Code Quality

- **Test Coverage**: Minimum 80% code coverage required for all new code
- **Documentation**: All public functions/classes must have docstrings or JSDoc comments
- **Error Handling**: Implement comprehensive error handling; no empty catch blocks
- **Security**: No hardcoded secrets, credentials, or sensitive data in code
- **Performance**: Consider performance implications; avoid unnecessary loops or database queries


## Technology-Specific Guidelines

### Frontend (React/Next.js)

- Use **functional components** with hooks (avoid class components)
- Use **React Query** (`@tanstack/react-query`) for server state management
- Use **React Hook Form** for form handling
- Implement **error boundaries** for graceful error handling
- Follow **accessibility best practices** (WCAG 2.1 AA):
  - Semantic HTML elements
  - ARIA labels where necessary
  - Keyboard navigation support
  - Alt text for images
- **Component Structure**:
  ```tsx
  // Good example
  import { FC } from 'react';
  
  interface Props {
    title: string;
    onSubmit: () => void;
  }
  
  export const DocumentCard: FC<Props> = ({ title, onSubmit }) => {
    // Component logic
    return <div>...</div>;
  };
  ```

### Backend (NestJS)

- Use **dependency injection** for all services
- Use **DTOs with class-validator** for input validation
- Use **guards** for authentication and authorization
- Use **interceptors** for logging and transformation
- **Module Structure**: Follow NestJS module pattern
  ```typescript
  // Good example
  @Injectable()
  export class DocumentsService {
    constructor(
      @InjectRepository(Document)
      private readonly documentRepo: Repository<Document>,
    ) {}
    
    async findById(id: string): Promise<Document> {
      // Service logic
    }
  }
  ```

### OCR Service (Python/FastAPI)

- Use **Pydantic models** for request/response validation
- Use **async/await** for I/O operations
- Use **dependency injection** for services
- Implement proper **error handling** with custom exception classes
- **Endpoint Structure**:
  ```python
  # Good example
  from fastapi import APIRouter, Depends
  from pydantic import BaseModel
  
  router = APIRouter()
  
  class OCRRequest(BaseModel):
      document_url: str
      options: dict = {}
  
  @router.post("/process")
  async def process_document(request: OCRRequest) -> OCRResponse:
      # Endpoint logic
      pass
  ```


## Testing Requirements

All contributions must include appropriate tests. We require **minimum 80% code coverage** for new code.

### Frontend Testing

- **Unit Tests**: Use **Jest** and **React Testing Library**
  ```bash
  cd frontend
  npm test
  ```
- **Component Tests**: Test component rendering, user interactions, and state changes
- **Integration Tests**: Test page-level functionality
- **E2E Tests**: Use **Playwright** for critical user flows
  ```bash
  npm run test:e2e
  ```

### Backend Testing

- **Unit Tests**: Use **Jest** for service and utility testing
  ```bash
  cd backend
  npm test
  ```
- **Integration Tests**: Test API endpoints with **Supertest**
- **E2E Tests**: Test complete API workflows
- **Coverage Report**:
  ```bash
  npm run test:cov
  ```

### OCR Service Testing

- **Unit Tests**: Use **Pytest** for service testing
  ```bash
  cd ocr-service
  pytest
  ```
- **Integration Tests**: Test API endpoints with **pytest-asyncio**
- **Coverage Report**:
  ```bash
  pytest --cov=app --cov-report=html
  ```

### Test Best Practices

- Write **descriptive test names** that explain what is being tested
- Use **arrange-act-assert** pattern
- **Mock external dependencies** (databases, APIs, file systems)
- Ensure tests are **deterministic** (no random failures)
- Tests should **clean up** after themselves (no test data pollution)


## Commit Message Conventions

We follow the **Conventional Commits** specification for clear and consistent commit messages.

### Format

```
<type>(<scope>): <subject>

<body>

<footer>
```

### Types

- **feat**: A new feature
- **fix**: A bug fix
- **docs**: Documentation only changes
- **style**: Code style changes (formatting, missing semicolons, etc.)
- **refactor**: Code refactoring without functional changes
- **perf**: Performance improvements
- **test**: Adding or updating tests
- **chore**: Maintenance tasks (dependencies, build config, etc.)

### Examples

```
feat(ocr): add confidence scoring for extracted fields

Implement character-level and field-level confidence scoring
using OCR engine confidence values and validation rules.

Closes #123
```

```
fix(api): resolve race condition in document processing

Add proper locking mechanism to prevent concurrent processing
of the same document.

Fixes #456
```

```
docs(contributing): add code quality standards section

Update CONTRIBUTING.md with detailed guidelines for code
quality, testing, and commit message conventions.
```

### Scope Examples

- `frontend`, `backend`, `ocr-service`
- `auth`, `documents`, `templates`, `search`
- `api`, `ui`, `database`, `queue`


## Security Guidelines

Security is a top priority. Please follow these guidelines when contributing:

### Authentication and Authorization

- **Never hardcode** credentials, API keys, tokens, or secrets in code
- Use **environment variables** for all configuration
- Implement proper **authentication checks** on all protected endpoints
- Use **RBAC (Role-Based Access Control)** for authorization
- Validate and sanitize **all user inputs**

### Data Protection

- **Encrypt sensitive data** at rest (use AES-256)
- Use **TLS 1.3** for all data transmission
- Implement **proper tenant isolation** (account_id in all queries)
- **Never log** sensitive data (passwords, tokens, PII)
- Follow **GDPR and CCPA** requirements for data handling

### Secure Coding Practices

- Use **parameterized queries** to prevent SQL injection
- Implement **input validation** on both client and server
- Use **output encoding** to prevent XSS attacks
- Implement **CSRF protection** for state-changing operations
- Use **security headers** (Helmet.js for Node.js)
- Keep **dependencies up-to-date** to avoid known vulnerabilities

### Security Testing

- Run **security scans** before submitting PRs
- Test for **common vulnerabilities** (OWASP Top 10)
- Report security issues via proper channels (see below)

**IMPORTANT**: If you discover a security vulnerability, please **do not** create a public GitHub issue. See the "Security issue notifications" section below for proper reporting procedures.


## Finding contributions to work on
Looking at the existing issues is a great way to find something to contribute on. As our projects, by default, use the default GitHub issue labels (enhancement/bug/duplicate/help wanted/invalid/question/wontfix), looking at any 'help wanted' issues is a great place to start.


## Code of Conduct
This project has adopted the [Amazon Open Source Code of Conduct](https://aws.github.io/code-of-conduct).
For more information see the [Code of Conduct FAQ](https://aws.github.io/code-of-conduct-faq) or contact
opensource-codeofconduct@amazon.com with any additional questions or comments.


## Security issue notifications
If you discover a potential security issue in this project we ask that you notify AWS/Amazon Security via our [vulnerability reporting page](http://aws.amazon.com/security/vulnerability-reporting/). Please do **not** create a public github issue.


## Licensing

See the [LICENSE](LICENSE) file for our project's licensing. We will ask you to confirm the licensing of your contribution.
