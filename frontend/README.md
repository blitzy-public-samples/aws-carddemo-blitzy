# CardDemo Frontend - React TypeScript SPA

Modern React 18.3 Single Page Application with TypeScript 5.7, replacing 17 BMS 3270 terminal screens from the COBOL mainframe CardDemo application with responsive web interfaces.

## Table of Contents

- [Overview](#overview)
- [Technology Stack](#technology-stack)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Local Development Setup](#local-development-setup)
- [BMS Screen to React Page Mapping](#bms-screen-to-react-page-mapping)
- [Available NPM Scripts](#available-npm-scripts)
- [REST API Integration](#rest-api-integration)
- [Form Validation](#form-validation)
- [State Management](#state-management)
- [Routing](#routing)
- [Styling](#styling)
- [Testing](#testing)
- [Build and Deployment](#build-and-deployment)
- [Troubleshooting](#troubleshooting)
- [Code Quality Standards](#code-quality-standards)
- [Contributing](#contributing)
- [Migration Notes](#migration-notes)
- [Performance](#performance)
- [Links](#links)
- [Support](#support)

## Overview

The CardDemo frontend is a comprehensive React-based Single Page Application that modernizes the mainframe 3270 terminal experience into a contemporary web interface. This application replaces all 17 BMS (Basic Mapping Support) screens with fully responsive React components while maintaining identical business logic, field validation rules, and screen flow patterns from the original COBOL system.

**Key Features:**
- Modern, responsive UI/UX replacing 3270 terminal screens
- Comprehensive form validation maintaining BMS field attribute rules
- RESTful API integration with Spring Boot backend
- Client-side routing for seamless navigation
- Type-safe development with TypeScript
- Material-UI component library for consistent design
- Optimized build process with Vite
- Comprehensive testing with Vitest

## Technology Stack

The frontend leverages the latest stable versions of modern web technologies:

### Core Framework
- **React** 18.3.1 - Modern UI library with concurrent rendering
- **TypeScript** 5.7.2 - Type-safe JavaScript with latest features
- **Vite** 6.0.3 - Next-generation build tool (faster than webpack)

### UI Components and Styling
- **Material-UI (MUI)** 6.2.0 - Comprehensive React component library
- **@mui/icons-material** 6.2.0 - Material Design icons
- **@emotion/react** 11.14.0 - CSS-in-JS library for MUI
- **@emotion/styled** 11.14.0 - Styled components for MUI

### HTTP and State Management
- **Axios** 1.7.9 - Promise-based HTTP client for REST APIs
- **@tanstack/react-query** 5.62.7 - Server state management with caching
- **Zustand** 5.0.2 - Lightweight global state management
- **React Router DOM** 6.28.0 - Declarative routing for React

### Form Handling and Validation
- **Formik** 2.4.6 - Form state management and submission
- **Yup** 1.4.0 - Schema-based form validation
- **React Hook Form** 7.54.0 - Alternative performant form library

### Utilities
- **date-fns** 4.1.0 - Modern date manipulation library
- **lodash** 4.17.21 - JavaScript utility library
- **classnames** 2.5.1 - Conditional className utility

### Development Tools
- **ESLint** 9.17.0 - JavaScript/TypeScript linter
- **Prettier** 3.4.2 - Opinionated code formatter
- **@vitejs/plugin-react** 4.3.4 - Vite plugin for React Fast Refresh

### Testing
- **Vitest** 2.1.8 - Vite-native unit test framework
- **@testing-library/react** 16.1.0 - React component testing utilities
- **@testing-library/jest-dom** 6.6.3 - Custom Jest matchers for DOM
- **@testing-library/user-event** 14.5.2 - User interaction simulation
- **jsdom** 25.0.1 - DOM implementation for Node.js testing

### TypeScript Definitions
- **@types/react** 18.3.18
- **@types/react-dom** 18.3.5
- **@types/lodash** 4.17.13
- **@types/node** 22.10.2

## Project Structure

```
frontend/
├── public/                          # Static assets
│   ├── index.html                   # HTML template
│   ├── favicon.ico                  # Site icon
│   └── robots.txt                   # SEO robots file
│
├── src/
│   ├── main.tsx                     # Application entry point
│   ├── App.tsx                      # Root component with routing
│   ├── vite-env.d.ts                # Vite environment types
│   │
│   ├── pages/                       # Page components (17 from BMS maps)
│   │   ├── SignonPage.tsx           # [COSGN00.bms] User authentication
│   │   ├── MainMenuPage.tsx         # [COMEN01.bms] Main menu navigation
│   │   ├── AdminMenuPage.tsx        # [COADM01.bms] Admin menu
│   │   ├── AccountUpdatePage.tsx    # [COACTUP.bms] Account update form
│   │   ├── AccountViewPage.tsx      # [COACTVW.bms] Account view display
│   │   ├── CardListPage.tsx         # [COCRDLI.bms] Card list with pagination
│   │   ├── CardUpdatePage.tsx       # [COCRDUP.bms] Card update form
│   │   ├── TransactionListPage.tsx  # [COTRN00.bms] Transaction list with filters
│   │   ├── TransactionDetailPage.tsx # [COTRN01.bms] Transaction detail view
│   │   ├── TransactionEntryPage.tsx # [COTRN02.bms] Transaction entry form
│   │   ├── BillingPage.tsx          # [COBIL00.bms] Billing and statements
│   │   ├── ReportMenuPage.tsx       # [CORPT00.bms] Report generation menu
│   │   ├── UserListPage.tsx         # [COUSR00.bms] User list display
│   │   ├── UserAddPage.tsx          # [COUSR01.bms] Add user form
│   │   ├── UserUpdatePage.tsx       # [COUSR02.bms] Update user form
│   │   └── UserDeletePage.tsx       # [COUSR03.bms] Delete user confirmation
│   │
│   ├── components/                  # Reusable UI components
│   │   ├── common/                  # Common components
│   │   │   ├── Header.tsx           # Application header
│   │   │   ├── Footer.tsx           # Application footer
│   │   │   ├── Navigation.tsx       # Navigation menu
│   │   │   ├── ErrorMessage.tsx     # Error display component
│   │   │   ├── LoadingSpinner.tsx   # Loading indicator
│   │   │   └── ConfirmDialog.tsx    # Confirmation dialog
│   │   │
│   │   ├── forms/                   # Form components
│   │   │   ├── AccountForm.tsx      # Reusable account form
│   │   │   ├── CardForm.tsx         # Reusable card form
│   │   │   ├── TransactionForm.tsx  # Reusable transaction form
│   │   │   └── UserForm.tsx         # Reusable user form
│   │   │
│   │   └── tables/                  # Table components
│   │       ├── AccountTable.tsx     # Account data table
│   │       ├── CardTable.tsx        # Card data table
│   │       ├── TransactionTable.tsx # Transaction data table
│   │       └── UserTable.tsx        # User data table
│   │
│   ├── services/                    # API client services
│   │   ├── api.ts                   # Axios base configuration
│   │   ├── authService.ts           # Authentication API calls
│   │   ├── accountService.ts        # Account API calls
│   │   ├── cardService.ts           # Card API calls
│   │   ├── transactionService.ts    # Transaction API calls
│   │   ├── userService.ts           # User management API calls
│   │   └── reportService.ts         # Report generation API calls
│   │
│   ├── hooks/                       # Custom React hooks
│   │   ├── useAuth.ts               # Authentication state hook
│   │   ├── useForm.ts               # Form handling hook
│   │   ├── usePagination.ts         # Table pagination hook
│   │   └── useDebounce.ts           # Input debouncing hook
│   │
│   ├── context/                     # React Context providers
│   │   ├── AuthContext.tsx          # Authentication context
│   │   └── ThemeContext.tsx         # Theme context
│   │
│   ├── types/                       # TypeScript type definitions
│   │   ├── account.ts               # Account-related types
│   │   ├── card.ts                  # Card-related types
│   │   ├── transaction.ts           # Transaction-related types
│   │   ├── user.ts                  # User-related types
│   │   └── common.ts                # Common types and interfaces
│   │
│   ├── utils/                       # Utility functions
│   │   ├── dateFormatter.ts         # Date formatting utilities
│   │   ├── currencyFormatter.ts     # Currency formatting utilities
│   │   ├── validation.ts            # Validation helper functions
│   │   └── constants.ts             # Application constants
│   │
│   ├── styles/                      # CSS stylesheets
│   │   ├── global.css               # Global styles
│   │   ├── variables.css            # CSS custom properties
│   │   └── theme.css                # Theme styles
│   │
│   └── assets/                      # Static assets
│       ├── images/                  # Image files
│       └── icons/                   # Icon files
│
├── package.json                     # NPM dependencies
├── package-lock.json                # Dependency lock file
├── tsconfig.json                    # TypeScript configuration
├── vite.config.ts                   # Vite build configuration
├── Dockerfile                       # Container image definition
├── .dockerignore                    # Docker ignore patterns
├── .eslintrc.json                   # ESLint configuration
├── .prettierrc                      # Prettier configuration
└── README.md                        # This file
```

## Prerequisites

Before running the frontend application, ensure you have the following installed:

- **Node.js** 20.x or higher (Active LTS version)
- **NPM** 10.x or higher (comes with Node.js)
- **Backend API** running on `http://localhost:8080`
- **Modern Web Browser** - Chrome, Firefox, Safari, or Edge (latest versions)

To verify your installations:

```bash
node --version  # Should display v20.x.x or higher
npm --version   # Should display 10.x.x or higher
```

## Local Development Setup

### Option 1: Standalone Frontend Development

This is the recommended approach for frontend-only development:

```bash
# Navigate to frontend directory
cd frontend

# Install dependencies
npm install

# Start development server
npm run dev
```

The development server will start on **http://localhost:5173** with:
- Hot Module Replacement (HMR) for instant updates
- API proxy configured to forward `/api/*` requests to `http://localhost:8080`
- Source maps enabled for debugging
- Fast refresh for React components

**Environment Variables:**

Create a `.env` file in the frontend directory for local development:

```bash
# Backend API URL
VITE_API_BASE_URL=http://localhost:8080

# Environment
VITE_ENV=development
```

### Option 2: Full Stack Development with Docker Compose

Run the entire application stack (frontend, backend, PostgreSQL) together:

```bash
# From project root directory
docker-compose up frontend backend postgres

# Or run in detached mode
docker-compose up -d frontend backend postgres
```

Access points:
- **Frontend**: http://localhost:3000
- **Backend API**: http://localhost:8080
- **PostgreSQL**: localhost:5432

To stop the services:

```bash
docker-compose down
```

### First-Time Setup

After installing dependencies, you may want to:

1. **Check for TypeScript errors:**
   ```bash
   npm run type-check
   ```

2. **Run linter:**
   ```bash
   npm run lint
   ```

3. **Format code:**
   ```bash
   npm run format
   ```

4. **Run tests:**
   ```bash
   npm test
   ```

## BMS Screen to React Page Mapping

The following table documents the complete transformation from BMS (Basic Mapping Support) 3270 terminal screens to modern React components. Each mapping preserves the original screen's functionality, field validation rules, and business logic.

| BMS Map File | React Page Component | Route Path | Function | Fields (Est) | Validation Complexity |
|--------------|---------------------|------------|----------|--------------|---------------------|
| COSGN00.bms | SignonPage.tsx | `/login` | User signon and authentication | 5 | Medium - Credentials validation |
| COMEN01.bms | MainMenuPage.tsx | `/` | Main menu navigation and transaction routing | 12 | Low - Menu options |
| COADM01.bms | AdminMenuPage.tsx | `/admin` | Administration menu | 10 | Low - Admin options |
| COACTUP.bms | AccountUpdatePage.tsx | `/accounts/:id/edit` | Account update and maintenance | 25 | High - Financial field validation |
| COACTVW.bms | AccountViewPage.tsx | `/accounts/:id` | Account view and inquiry | 20 | Low - Read-only display |
| COCRDLI.bms | CardListPage.tsx | `/cards` | Credit card list display with pagination | 30 | Medium - List navigation |
| COCRDSL.bms | CardSelectionPage.tsx | `/cards/select` | Credit card selection | 8 | Low - Selection logic |
| COCRDUP.bms | CardUpdatePage.tsx | `/cards/:id/edit` | Credit card update | 22 | High - Card data validation |
| COBIL00.bms | BillingPage.tsx | `/billing/:accountId` | Billing and statement processing | 18 | Medium - Billing display |
| COTRN00.bms | TransactionListPage.tsx | `/transactions` | Transaction list display | 35 | Medium - Date range filtering |
| COTRN01.bms | TransactionDetailPage.tsx | `/transactions/:id` | Transaction detail view | 20 | Low - Detail display |
| COTRN02.bms | TransactionEntryPage.tsx | `/transactions/new` | Transaction entry and posting | 28 | Very High - Transaction validation |
| CORPT00.bms | ReportMenuPage.tsx | `/reports` | Report generation menu | 15 | Low - Report selection |
| COUSR00.bms | UserListPage.tsx | `/users` | User administration list | 20 | Medium - User listing |
| COUSR01.bms | UserAddPage.tsx | `/users/new` | User add function | 18 | High - User validation |
| COUSR02.bms | UserUpdatePage.tsx | `/users/:id/edit` | User update function | 18 | High - User validation |
| COUSR03.bms | UserDeletePage.tsx | `/users/:id/delete` | User delete confirmation | 8 | Medium - Confirmation logic |

### BMS Field Attribute Mapping

Original BMS field attributes have been translated to React validation rules:

| BMS Attribute | React Equivalent | Implementation |
|---------------|------------------|----------------|
| ASKIP | `disabled={true}` | Read-only field, skip in tab order |
| PROT | `readOnly={true}` | Protected field, display only |
| NUM | Yup `.number()` | Numeric validation with Formik/Yup |
| BRT | `fontWeight: 'bold'` | Bright/highlighted field |
| UNPROT | Standard input | Unprotected, user can modify |
| FSET | Form state tracking | Field has been modified by user |
| IC | `autoFocus={true}` | Initial cursor position |

### Screen Flow Preservation

The application maintains the original COBOL screen flow:

1. **Signon** (CC00) → User authentication
2. **Main Menu** (CM00) → Route selection based on user type
3. **Functional Screens** → Account, Card, Transaction, User management
4. **Admin Menu** (CA00) → Administrative functions (admin users only)

## Available NPM Scripts

The following scripts are available for development, testing, and deployment:

### Development

```bash
# Start development server with hot reload (port 5173)
npm run dev

# Start development server with host exposed (for Docker)
npm run dev -- --host
```

### Building

```bash
# Production build - outputs to dist/ directory
npm run build

# Preview production build locally (port 4173)
npm run preview
```

### Code Quality

```bash
# Run ESLint to check code quality
npm run lint

# Auto-fix ESLint issues
npm run lint:fix

# Check code formatting with Prettier
npm run format:check

# Format code with Prettier
npm run format
```

### Type Checking

```bash
# Run TypeScript compiler for type checking (no emit)
npm run type-check
```

### Testing

```bash
# Run unit tests with Vitest
npm test

# Run tests in watch mode (useful during development)
npm run test:watch

# Run tests with UI interface
npm run test:ui

# Generate test coverage report
npm run test:coverage

# Run tests in CI mode (single run, no watch)
npm run test:ci
```

### Cleaning

```bash
# Remove node_modules and package-lock.json
npm run clean

# Clean and reinstall dependencies
npm run clean:install
```

## REST API Integration

The frontend communicates with the Spring Boot backend through RESTful APIs. All API interactions are centralized in the `src/services/` directory.

### API Configuration

Base configuration in `src/services/api.ts`:

```typescript
import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

const apiClient = axios.create({
  baseURL: `${API_BASE_URL}/api`,
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor - Add JWT token to requests
apiClient.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('authToken');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// Response interceptor - Handle errors globally
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      // Unauthorized - redirect to login
      localStorage.removeItem('authToken');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);
```

### API Endpoints

#### Authentication Service (`authService.ts`)

| Method | Endpoint | Function | Request Body | Response |
|--------|----------|----------|--------------|----------|
| POST | `/api/auth/login` | User login | `{ userId, password }` | `{ token, expiresIn, userType }` |
| POST | `/api/auth/logout` | User logout | - | `{ message }` |
| GET | `/api/auth/validate` | Validate token | - | `{ valid, user }` |

#### Account Service (`accountService.ts`)

| Method | Endpoint | Function | Parameters | Response |
|--------|----------|----------|------------|----------|
| GET | `/api/accounts/:id` | Get account details | Account ID | `AccountDto` |
| PUT | `/api/accounts/:id` | Update account | Account ID, AccountDto | `AccountDto` |
| POST | `/api/accounts` | Create account | AccountDto | `AccountDto` |
| GET | `/api/accounts` | List accounts | `?page=0&size=20` | `Page<AccountDto>` |

#### Card Service (`cardService.ts`)

| Method | Endpoint | Function | Parameters | Response |
|--------|----------|----------|------------|----------|
| GET | `/api/cards` | List all cards | `?page=0&size=20` | `Page<CardDto>` |
| GET | `/api/cards/:cardNum` | Get card details | Card number | `CardDto` |
| PUT | `/api/cards/:cardNum` | Update card | Card number, CardDto | `CardDto` |
| POST | `/api/cards` | Create card | CardDto | `CardDto` |
| GET | `/api/cards/account/:accountId` | Get cards by account | Account ID | `CardDto[]` |

#### Transaction Service (`transactionService.ts`)

| Method | Endpoint | Function | Parameters | Response |
|--------|----------|----------|------------|----------|
| GET | `/api/transactions` | List transactions | `?page=0&size=20&startDate&endDate` | `Page<TransactionDto>` |
| GET | `/api/transactions/:id` | Get transaction | Transaction ID | `TransactionDto` |
| POST | `/api/transactions` | Post transaction | TransactionDto | `TransactionDto` |
| GET | `/api/transactions/card/:cardNum` | Get by card | Card number, date range | `TransactionDto[]` |

#### User Service (`userService.ts`)

| Method | Endpoint | Function | Parameters | Response |
|--------|----------|----------|------------|----------|
| GET | `/api/users` | List users | `?page=0&size=20` | `Page<UserDto>` |
| GET | `/api/users/:userId` | Get user | User ID | `UserDto` |
| POST | `/api/users` | Create user | UserDto | `UserDto` |
| PUT | `/api/users/:userId` | Update user | User ID, UserDto | `UserDto` |
| DELETE | `/api/users/:userId` | Delete user | User ID | `{ message }` |

#### Report Service (`reportService.ts`)

| Method | Endpoint | Function | Parameters | Response |
|--------|----------|----------|------------|----------|
| GET | `/api/reports/menu` | Get report menu | - | `ReportOption[]` |
| POST | `/api/reports/generate` | Generate report | `{ reportType, parameters }` | Report file |

### Error Handling

All services implement consistent error handling:

```typescript
try {
  const response = await apiClient.get('/api/accounts/123');
  return response.data;
} catch (error) {
  if (axios.isAxiosError(error)) {
    throw new Error(error.response?.data?.message || 'API request failed');
  }
  throw error;
}
```

### Response Types

TypeScript interfaces for API responses are defined in `src/types/`:

```typescript
// src/types/account.ts
export interface AccountDto {
  acctId: number;
  acctActiveStatus: string;
  acctCurrBal: number;
  acctCreditLimit: number;
  acctOpenDate: string;
  // ... other fields
}
```

## Form Validation

Form validation preserves the exact validation rules from BMS field attributes and COBOL business logic. The application uses **Formik** for form state management and **Yup** for schema-based validation.

### Validation Strategy

BMS field attributes are translated to Yup validation schemas:

```typescript
// Example: Account Update Form Validation
import * as Yup from 'yup';

const accountValidationSchema = Yup.object().shape({
  acctId: Yup.number()
    .required('Account ID is required')
    .positive('Account ID must be positive')
    .integer('Account ID must be an integer'),
  
  acctCreditLimit: Yup.number()
    .required('Credit limit is required')
    .min(0, 'Credit limit must be non-negative')
    .max(999999.99, 'Credit limit exceeds maximum'),
  
  acctActiveStatus: Yup.string()
    .required('Account status is required')
    .oneOf(['Y', 'N'], 'Status must be Y or N'),
  
  acctOpenDate: Yup.date()
    .required('Open date is required')
    .max(new Date(), 'Open date cannot be in the future'),
});
```

### Form Implementation Pattern

```typescript
// Example: Using Formik with validation
import { Formik, Form, Field } from 'formik';

function AccountUpdatePage() {
  const handleSubmit = async (values, { setSubmitting }) => {
    try {
      await accountService.updateAccount(values.acctId, values);
      // Success handling
    } catch (error) {
      // Error handling
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Formik
      initialValues={initialAccount}
      validationSchema={accountValidationSchema}
      onSubmit={handleSubmit}
    >
      {({ errors, touched, isSubmitting }) => (
        <Form>
          <Field name="acctId" />
          {errors.acctId && touched.acctId && <div>{errors.acctId}</div>}
          {/* More fields */}
        </Form>
      )}
    </Formik>
  );
}
```

### Common Validation Rules

| COBOL Validation | Yup Schema | Description |
|------------------|------------|-------------|
| PIC 9(11) | `.number().integer()` | 11-digit integer |
| PIC S9(10)V99 COMP-3 | `.number().test('decimal', fn)` | Decimal with 2 places |
| PIC X(50) | `.string().max(50)` | Alphanumeric, max 50 chars |
| NUMERIC field check | `.number()` | Must be numeric |
| Mandatory field | `.required('message')` | Required field validation |
| Date field | `.date()` | Date validation |
| Status codes (88-level) | `.oneOf(['Y', 'N'])` | Enumerated values |

## State Management

The application uses a hybrid state management approach optimized for different data types:

### Server State - React Query

Used for data fetched from the backend API:

```typescript
import { useQuery, useMutation } from '@tanstack/react-query';
import { accountService } from '../services/accountService';

// Fetch account data
const { data, isLoading, error } = useQuery({
  queryKey: ['account', accountId],
  queryFn: () => accountService.getAccount(accountId),
  staleTime: 5 * 60 * 1000, // 5 minutes
});

// Update account mutation
const mutation = useMutation({
  mutationFn: (account) => accountService.updateAccount(account.id, account),
  onSuccess: () => {
    queryClient.invalidateQueries({ queryKey: ['account'] });
  },
});
```

**Benefits:**
- Automatic caching and background refetching
- Optimistic updates
- Loading and error states
- Deduplication of requests

### Global State - Zustand

Used for application-wide state (auth, theme):

```typescript
import create from 'zustand';

interface AuthState {
  user: User | null;
  token: string | null;
  login: (userId: string, password: string) => Promise<void>;
  logout: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  token: localStorage.getItem('authToken'),
  login: async (userId, password) => {
    const response = await authService.login(userId, password);
    set({ user: response.user, token: response.token });
    localStorage.setItem('authToken', response.token);
  },
  logout: () => {
    set({ user: null, token: null });
    localStorage.removeItem('authToken');
  },
}));
```

### Local State - React Hooks

Used for component-specific state:

```typescript
const [isDialogOpen, setIsDialogOpen] = useState(false);
const [selectedCard, setSelectedCard] = useState<Card | null>(null);
```

### Form State - Formik

Used for complex form state management (see Form Validation section).

## Routing

Client-side routing is implemented with **React Router 6**, providing seamless navigation without full page reloads.

### Route Configuration

```typescript
// src/App.tsx
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';

function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* Public Routes */}
        <Route path="/login" element={<SignonPage />} />
        
        {/* Protected Routes */}
        <Route element={<ProtectedRoute />}>
          <Route path="/" element={<MainMenuPage />} />
          
          {/* Account Routes */}
          <Route path="/accounts/:id" element={<AccountViewPage />} />
          <Route path="/accounts/:id/edit" element={<AccountUpdatePage />} />
          
          {/* Card Routes */}
          <Route path="/cards" element={<CardListPage />} />
          <Route path="/cards/:cardNum/edit" element={<CardUpdatePage />} />
          
          {/* Transaction Routes */}
          <Route path="/transactions" element={<TransactionListPage />} />
          <Route path="/transactions/:id" element={<TransactionDetailPage />} />
          <Route path="/transactions/new" element={<TransactionEntryPage />} />
          
          {/* Billing Route */}
          <Route path="/billing/:accountId" element={<BillingPage />} />
          
          {/* Report Routes */}
          <Route path="/reports" element={<ReportMenuPage />} />
          
          {/* Admin Routes (require admin role) */}
          <Route element={<AdminRoute />}>
            <Route path="/admin" element={<AdminMenuPage />} />
            <Route path="/users" element={<UserListPage />} />
            <Route path="/users/new" element={<UserAddPage />} />
            <Route path="/users/:userId/edit" element={<UserUpdatePage />} />
            <Route path="/users/:userId/delete" element={<UserDeletePage />} />
          </Route>
        </Route>
        
        {/* 404 Not Found */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  );
}
```

### Protected Routes

```typescript
// Protected route wrapper - requires authentication
function ProtectedRoute() {
  const { token } = useAuthStore();
  
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  
  return <Outlet />;
}

// Admin route wrapper - requires admin role
function AdminRoute() {
  const { user } = useAuthStore();
  
  if (user?.userType !== 'A') {
    return <Navigate to="/" replace />;
  }
  
  return <Outlet />;
}
```

### Programmatic Navigation

```typescript
import { useNavigate } from 'react-router-dom';

function MyComponent() {
  const navigate = useNavigate();
  
  const handleSuccess = () => {
    navigate('/accounts/123');
  };
  
  const goBack = () => {
    navigate(-1);
  };
}
```

## Styling

The application uses a multi-layered styling approach combining Material-UI, Emotion, and CSS Modules.

### Material-UI Theme

Custom theme configuration in `src/theme.ts`:

```typescript
import { createTheme } from '@mui/material/styles';

export const theme = createTheme({
  palette: {
    primary: {
      main: '#1976d2', // Blue - matches mainframe color scheme
    },
    secondary: {
      main: '#dc004e',
    },
    background: {
      default: '#f5f5f5',
    },
  },
  typography: {
    fontFamily: '"Roboto", "Helvetica", "Arial", sans-serif',
    h4: {
      fontWeight: 600,
    },
  },
  components: {
    MuiButton: {
      styleOverrides: {
        root: {
          textTransform: 'none', // Disable uppercase transform
        },
      },
    },
  },
});
```

### CSS Variables

Global CSS variables in `src/styles/variables.css`:

```css
:root {
  /* Colors matching BMS terminal colors */
  --color-blue: #0000ff;
  --color-yellow: #ffff00;
  --color-green: #00ff00;
  --color-red: #ff0000;
  
  /* Spacing */
  --spacing-xs: 4px;
  --spacing-sm: 8px;
  --spacing-md: 16px;
  --spacing-lg: 24px;
  --spacing-xl: 32px;
  
  /* Layout */
  --max-width: 1200px;
  --header-height: 64px;
  --footer-height: 48px;
}
```

### Component Styling Patterns

**Using MUI Styled Components:**

```typescript
import { styled } from '@mui/material/styles';
import { Paper } from '@mui/material';

const StyledPaper = styled(Paper)(({ theme }) => ({
  padding: theme.spacing(3),
  marginBottom: theme.spacing(2),
  borderRadius: theme.shape.borderRadius,
}));
```

**Using Emotion CSS:**

```typescript
import { css } from '@emotion/react';

const formStyles = css`
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 600px;
`;
```

### Responsive Design

Mobile-first responsive breakpoints:

```typescript
const styles = {
  container: {
    padding: 2,
    [theme.breakpoints.up('sm')]: {
      padding: 3,
    },
    [theme.breakpoints.up('md')]: {
      padding: 4,
    },
  },
};
```

## Testing

Comprehensive testing strategy using Vitest and React Testing Library.

### Testing Stack

- **Vitest** - Fast unit test framework with Vite integration
- **React Testing Library** - Component testing utilities
- **@testing-library/jest-dom** - Custom DOM matchers
- **@testing-library/user-event** - User interaction simulation
- **MSW (Mock Service Worker)** - API mocking (optional)

### Running Tests

```bash
# Run all tests
npm test

# Run tests in watch mode
npm run test:watch

# Run tests with UI
npm run test:ui

# Generate coverage report
npm run test:coverage
```

### Test Structure

```
src/
├── components/
│   ├── common/
│   │   ├── Header.tsx
│   │   └── Header.test.tsx
│   └── forms/
│       ├── AccountForm.tsx
│       └── AccountForm.test.tsx
├── pages/
│   ├── SignonPage.tsx
│   └── SignonPage.test.tsx
└── services/
    ├── accountService.ts
    └── accountService.test.ts
```

### Example Component Test

```typescript
// src/components/common/Header.test.tsx
import { render, screen } from '@testing-library/react';
import { Header } from './Header';

describe('Header', () => {
  it('renders application title', () => {
    render(<Header />);
    expect(screen.getByText('CardDemo')).toBeInTheDocument();
  });
  
  it('displays logged in user name', () => {
    render(<Header user={{ userId: 'USER001', name: 'John Doe' }} />);
    expect(screen.getByText('John Doe')).toBeInTheDocument();
  });
});
```

### Example Form Test

```typescript
// src/pages/SignonPage.test.tsx
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SignonPage } from './SignonPage';

describe('SignonPage', () => {
  it('validates required fields', async () => {
    render(<SignonPage />);
    
    const submitButton = screen.getByRole('button', { name: /sign in/i });
    await userEvent.click(submitButton);
    
    expect(screen.getByText('User ID is required')).toBeInTheDocument();
    expect(screen.getByText('Password is required')).toBeInTheDocument();
  });
  
  it('submits form with valid credentials', async () => {
    render(<SignonPage />);
    
    await userEvent.type(screen.getByLabelText(/user id/i), 'USER001');
    await userEvent.type(screen.getByLabelText(/password/i), 'Password123');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    
    // Assert navigation or success message
  });
});
```

### Coverage Requirements

Minimum coverage targets:
- **Statements**: 70%
- **Branches**: 70%
- **Functions**: 70%
- **Lines**: 70%

View coverage report:
```bash
npm run test:coverage
# Open coverage/index.html in browser
```

## Build and Deployment

### Development Build

```bash
# Start Vite development server
npm run dev

# Features:
# - Hot Module Replacement (HMR)
# - Source maps
# - Fast refresh
# - Instant feedback
```

### Production Build

```bash
# Create optimized production build
npm run build

# Output directory: dist/
# - Minified JavaScript and CSS
# - Code splitting for optimal loading
# - Asset hashing for cache busting
# - Tree shaking to remove unused code
# - Compressed bundle sizes
```

Build output structure:
```
dist/
├── index.html
├── assets/
│   ├── index.[hash].js        # Main application bundle
│   ├── vendor.[hash].js       # Third-party libraries
│   ├── [page].[hash].js       # Code-split page chunks
│   └── index.[hash].css       # Compiled styles
└── favicon.ico
```

### Preview Production Build

```bash
# Preview production build locally
npm run preview

# Starts static file server on port 4173
# http://localhost:4173
```

### Docker Build

**Dockerfile (multi-stage build):**

```dockerfile
# Build stage
FROM node:20-alpine AS builder

WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

# Production stage
FROM nginx:1.27-alpine

COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
```

**Build and run Docker image:**

```bash
# Build Docker image
docker build -t carddemo-frontend:latest .

# Run container
docker run -p 3000:80 carddemo-frontend:latest

# Access at http://localhost:3000
```

### Kubernetes Deployment

```bash
# Apply Kubernetes manifests
kubectl apply -f infrastructure/kubernetes/frontend/

# Resources created:
# - Deployment (3 replicas)
# - Service (ClusterIP)
# - Ingress (routing)

# Check deployment status
kubectl get pods -l app=carddemo-frontend

# View logs
kubectl logs -l app=carddemo-frontend --tail=100
```

### CI/CD Pipeline

GitHub Actions workflow (`.github/workflows/frontend-ci.yml`):

```yaml
name: Frontend CI/CD

on:
  push:
    branches: [main, develop]
    paths:
      - 'frontend/**'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
      
      - name: Install dependencies
        run: npm ci
        working-directory: ./frontend
      
      - name: Lint
        run: npm run lint
        working-directory: ./frontend
      
      - name: Type check
        run: npm run type-check
        working-directory: ./frontend
      
      - name: Test
        run: npm run test:ci
        working-directory: ./frontend
      
      - name: Build
        run: npm run build
        working-directory: ./frontend
      
      - name: Build Docker image
        run: docker build -t carddemo-frontend:${{ github.sha }} ./frontend
      
      - name: Push to registry
        # Push image to container registry
      
      - name: Deploy to Kubernetes
        # Deploy to cluster
```

## Troubleshooting

### Common Issues and Solutions

#### Port Already in Use

**Problem:** Vite development server fails to start because port 5173 is in use.

**Solution:**
```bash
# Vite will automatically try the next available port
# Or specify a different port:
npm run dev -- --port 3000
```

#### Backend Connection Failed

**Problem:** API requests fail with network errors.

**Solution:**
1. Verify backend is running: `curl http://localhost:8080/api/health`
2. Check `VITE_API_BASE_URL` in `.env` file
3. Verify proxy configuration in `vite.config.ts`
4. Check browser console for CORS errors

#### TypeScript Errors

**Problem:** TypeScript compilation errors during build.

**Solution:**
```bash
# Run type checker to see all errors
npm run type-check

# Common fixes:
# - Add missing type definitions: npm install --save-dev @types/[package]
# - Check tsconfig.json configuration
# - Verify import paths are correct
```

#### Build Errors

**Problem:** Production build fails with errors.

**Solution:**
```bash
# Clean node_modules and reinstall
rm -rf node_modules package-lock.json
npm install

# Clear Vite cache
rm -rf node_modules/.vite

# Try build again
npm run build
```

#### Tests Failing

**Problem:** Unit tests fail unexpectedly.

**Solution:**
```bash
# Clear test cache
npm run test -- --clearCache

# Run tests in verbose mode
npm run test -- --reporter=verbose

# Check test environment setup in vitest.config.ts
```

#### Hot Module Replacement Not Working

**Problem:** Changes not reflecting in browser during development.

**Solution:**
1. Check browser console for HMR errors
2. Restart development server: `Ctrl+C` then `npm run dev`
3. Clear browser cache and hard reload: `Ctrl+Shift+R`
4. Check for syntax errors in modified files

#### Memory Issues During Build

**Problem:** Build process runs out of memory.

**Solution:**
```bash
# Increase Node.js memory limit
NODE_OPTIONS=--max-old-space-size=4096 npm run build
```

#### CORS Errors

**Problem:** Browser blocks API requests due to CORS policy.

**Solution:**
1. Ensure backend has CORS configuration enabled
2. Check API proxy configuration in `vite.config.ts`:
   ```typescript
   server: {
     proxy: {
       '/api': {
         target: 'http://localhost:8080',
         changeOrigin: true,
       },
     },
   }
   ```

## Code Quality Standards

The frontend follows strict code quality standards to ensure maintainability and consistency.

### TypeScript Standards

- **Strict Mode Enabled**: All TypeScript strict checks enforced
- **No `any` Types**: Avoid using `any`; use proper types or `unknown`
- **Explicit Return Types**: All exported functions must have explicit return types
- **Interface Naming**: Use PascalCase; prefix interfaces with `I` only when necessary

```typescript
// Good
interface User {
  userId: string;
  firstName: string;
  lastName: string;
}

function getUser(id: string): Promise<User> {
  return userService.getUser(id);
}

// Avoid
function getUser(id: any): any {
  return userService.getUser(id);
}
```

### React Component Standards

- **Functional Components Only**: No class components
- **Hooks**: Use React hooks for state and side effects
- **Component Naming**: PascalCase for components, camelCase for functions
- **Props Interface**: Define explicit interface for component props
- **Default Exports**: Use default exports for page components, named exports for utilities

```typescript
// Good
interface AccountFormProps {
  account: Account;
  onSubmit: (values: Account) => void;
  onCancel: () => void;
}

export function AccountForm({ account, onSubmit, onCancel }: AccountFormProps) {
  // Component implementation
}

// For page components
export default function AccountViewPage() {
  // Page implementation
}
```

### File Organization Standards

- **One Component Per File**: Each component in its own file
- **Co-locate Tests**: Place test files next to implementation (e.g., `Header.test.tsx` next to `Header.tsx`)
- **Index Files**: Use `index.ts` for barrel exports when appropriate
- **Maximum File Length**: 300 lines per file (excluding tests)

### Naming Conventions

| Item | Convention | Example |
|------|------------|---------|
| Components | PascalCase | `AccountForm`, `UserTable` |
| Functions | camelCase | `getUserById`, `formatCurrency` |
| Hooks | camelCase with `use` prefix | `useAuth`, `usePagination` |
| Constants | UPPER_SNAKE_CASE | `API_BASE_URL`, `MAX_RETRIES` |
| Interfaces/Types | PascalCase | `User`, `AccountDto` |
| Files | Match export name | `AccountForm.tsx`, `useAuth.ts` |

### ESLint Rules

Key enforced rules (`.eslintrc.json`):

- `react/prop-types`: Off (using TypeScript)
- `@typescript-eslint/explicit-function-return-type`: Warn
- `@typescript-eslint/no-explicit-any`: Error
- `react-hooks/rules-of-hooks`: Error
- `react-hooks/exhaustive-deps`: Warn

### Prettier Configuration

Enforced formatting (`.prettierrc`):

```json
{
  "semi": true,
  "trailingComma": "es5",
  "singleQuote": true,
  "printWidth": 100,
  "tabWidth": 2,
  "useTabs": false
}
```

### Pre-commit Checks

Before committing code, ensure:

```bash
# 1. Linting passes
npm run lint

# 2. Type checking passes
npm run type-check

# 3. Formatting is correct
npm run format:check

# 4. Tests pass
npm test

# 5. Build succeeds
npm run build
```

## Contributing

We welcome contributions to improve the CardDemo frontend application!

### Getting Started

1. **Fork the repository** on GitHub
2. **Clone your fork** locally:
   ```bash
   git clone https://github.com/your-username/carddemo.git
   cd carddemo/frontend
   ```
3. **Install dependencies**:
   ```bash
   npm install
   ```
4. **Create a feature branch**:
   ```bash
   git checkout -b feature/your-feature-name
   ```

### Development Workflow

1. **Make your changes** following the code quality standards
2. **Write tests** for new functionality
3. **Run tests**:
   ```bash
   npm test
   ```
4. **Check code quality**:
   ```bash
   npm run lint
   npm run type-check
   npm run format
   ```
5. **Commit changes** with clear commit messages:
   ```bash
   git commit -m "feat: add transaction filtering by date range"
   ```

### Commit Message Guidelines

Follow conventional commits format:

- `feat:` - New feature
- `fix:` - Bug fix
- `docs:` - Documentation changes
- `style:` - Code style changes (formatting, no logic change)
- `refactor:` - Code refactoring
- `test:` - Adding or updating tests
- `chore:` - Maintenance tasks

### Pull Request Process

1. **Push changes** to your fork:
   ```bash
   git push origin feature/your-feature-name
   ```
2. **Create a Pull Request** on GitHub
3. **Describe your changes** clearly in the PR description
4. **Reference related issues** (e.g., "Closes #123")
5. **Wait for review** - maintainers will review and provide feedback
6. **Address feedback** and push updates if needed

### Code Review Checklist

Your PR should:
- [ ] Pass all CI checks (lint, type-check, tests, build)
- [ ] Include tests for new functionality
- [ ] Update documentation if needed
- [ ] Follow code quality standards
- [ ] Have clear, descriptive commit messages
- [ ] Not introduce breaking changes (or clearly document them)

## Migration Notes

This section documents key changes from the original mainframe BMS screens to the React SPA.

### User Experience Improvements

| Aspect | Mainframe (BMS) | Modern React |
|--------|-----------------|--------------|
| Navigation | Function keys (F1-F12) | Mouse clicks and keyboard shortcuts |
| Data Entry | Character-based fields | Modern input components with validation |
| Error Messages | Single-line messages | Contextual inline error messages |
| Help | Fixed help screens | Tooltips and contextual help |
| Screen Size | Fixed 24x80 characters | Responsive, adapts to screen size |

### Preserved Functionality

All business logic from COBOL programs has been preserved:

1. **Field Validation Rules** - All BMS field attributes (ASKIP, PROT, NUM) translated to React validation
2. **Screen Flow Logic** - Navigation patterns maintained (Main Menu → Function → Details)
3. **Data Processing** - Identical business rules for account, card, and transaction operations
4. **User Roles** - Admin vs Regular user permissions preserved
5. **Transaction Boundaries** - API calls maintain same transactional integrity as CICS

### Known Differences

Minor intentional differences for modern UX:

1. **Pagination**: Table pagination uses modern UI patterns instead of page-up/page-down keys
2. **Date Picker**: Visual date picker instead of YYYY-MM-DD text entry (validation still enforced)
3. **Error Handling**: More detailed error messages with better context
4. **Session Management**: JWT token-based instead of CICS session management
5. **Confirmation Dialogs**: Modern modal dialogs instead of full-screen confirmation pages

### Migration Testing

All 17 screens have been validated to ensure:
- [ ] Identical field validation rules
- [ ] Preserved screen flow logic
- [ ] Equivalent business rule enforcement
- [ ] Same data displayed in modern format
- [ ] All user functions accessible

## Performance

The frontend is optimized for fast loading and smooth user experience.

### Performance Targets

| Metric | Target | Measurement |
|--------|--------|-------------|
| Initial Load | < 3 seconds | Time to Interactive (TTI) |
| Route Transition | < 100ms | Client-side navigation |
| API Response | < 200ms | Backend response time |
| Bundle Size | < 500KB gzipped | Main application bundle |

### Optimization Techniques

1. **Code Splitting**: Pages loaded on-demand using React lazy loading
   ```typescript
   const AccountUpdatePage = lazy(() => import('./pages/AccountUpdatePage'));
   ```

2. **Tree Shaking**: Unused code automatically removed during build

3. **Asset Optimization**:
   - Images compressed and served in modern formats (WebP)
   - CSS minified and combined
   - JavaScript minified with Terser

4. **Caching Strategy**:
   - React Query caches API responses (5-minute stale time)
   - Browser caching with hashed filenames
   - Service Worker for offline support (optional)

5. **Bundle Analysis**:
   ```bash
   npm run build -- --sourcemap
   # Analyze bundle with source-map-explorer
   ```

### Performance Monitoring

Monitor performance in production:

- **Lighthouse Scores**: Target 90+ for Performance, Accessibility, Best Practices
- **Core Web Vitals**:
  - LCP (Largest Contentful Paint): < 2.5s
  - FID (First Input Delay): < 100ms
  - CLS (Cumulative Layout Shift): < 0.1

## Links

### Project Documentation
- [Root README](../README.md) - Overall project documentation
- [Backend README](../backend/README.md) - Spring Boot backend documentation
- [Infrastructure README](../infrastructure/README.md) - Deployment and infrastructure

### API Documentation
- [Backend API Swagger](http://localhost:8080/swagger-ui.html) - Interactive API documentation (when backend is running)
- [API Endpoint Reference](../backend/API.md) - Complete API reference

### External Resources
- [React Documentation](https://react.dev/) - Official React documentation
- [TypeScript Handbook](https://www.typescriptlang.org/docs/) - TypeScript guide
- [Material-UI](https://mui.com/) - Component library documentation
- [Vite Guide](https://vitejs.dev/guide/) - Build tool documentation
- [React Router](https://reactrouter.com/) - Routing documentation
- [Formik](https://formik.org/) - Form library documentation

### Related Repositories
- [Original CardDemo](https://github.com/aws-samples/aws-mainframe-modernization-carddemo) - Original COBOL mainframe application

## Support

### Getting Help

If you encounter issues or have questions:

1. **Check Documentation**: Review this README and related documentation
2. **Search Issues**: Check [GitHub Issues](https://github.com/your-org/carddemo/issues) for existing solutions
3. **Ask Questions**: Open a new issue with the `question` label
4. **Report Bugs**: Open a new issue with the `bug` label and include:
   - Steps to reproduce
   - Expected behavior
   - Actual behavior
   - Browser and version
   - Console errors (if any)

### Issue Templates

When reporting issues, please include:

**For Bugs:**
- Browser and version
- Node.js version
- Steps to reproduce
- Error messages
- Screenshots (if applicable)

**For Features:**
- Use case description
- Proposed solution
- Alternative solutions considered
- Impact on existing functionality

### Team Contacts

- **Frontend Team Lead**: [Name] - [email]
- **Project Manager**: [Name] - [email]
- **Technical Support**: [support email]

---

**License**: Apache 2.0 - See [LICENSE](../LICENSE) for details

**Project Status**: Active Development - Modernization of AWS CardDemo mainframe application

**Last Updated**: 2025

---

Thank you for contributing to the CardDemo frontend modernization project! 🚀

