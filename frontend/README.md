# CardDemo Frontend

Modern React 18 single-page application for credit card management, replacing 17 legacy BMS 3270 terminal screens with a cloud-native web UI.

## Table of Contents

- [Project Overview](#project-overview)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Project Structure](#project-structure)
- [Available Scripts](#available-scripts)
- [Component Architecture](#component-architecture)
- [State Management](#state-management)
- [API Integration](#api-integration)
- [Field Validation](#field-validation)
- [Pagination](#pagination)
- [Navigation](#navigation)
- [Testing](#testing)
- [Environment Configuration](#environment-configuration)
- [Docker Build](#docker-build)
- [Styling](#styling)
- [Performance](#performance)
- [Browser Support](#browser-support)
- [Troubleshooting](#troubleshooting)
- [Additional Documentation](#additional-documentation)
- [Contributing](#contributing)
- [License](#license)

## Project Overview

**CardDemo Frontend** is a React 18 single-page application (SPA) that provides a modern web interface for the CardDemo credit card management system. This application is part of a comprehensive mainframe-to-cloud migration, transforming the legacy CICS/BMS 3270 terminal interface into a responsive, accessible web application.

### Key Features

- **Modern UI**: React 18 functional components with Material-UI design system
- **State Management**: Redux Toolkit for predictable state management
- **Responsive Design**: Works seamlessly on desktop, tablet, and mobile devices
- **Real-time Validation**: Client-side form validation with Yup schemas
- **Secure Authentication**: JWT token-based authentication with automatic refresh
- **Accessibility**: WCAG 2.1 Level AA compliant
- **Performance Optimized**: Code splitting, lazy loading, and optimized builds with Vite

### Technology Stack

| Technology | Version | Purpose |
|-----------|---------|---------|
| React | 18.2.0 | Core UI library |
| Redux Toolkit | 2.0.1 | State management |
| React Router | 6.21.1 | Client-side routing |
| Material-UI | 5.15.3 | UI component library |
| Axios | 1.6.5 | HTTP client for API calls |
| Formik | 2.4.5 | Form handling |
| Yup | 1.3.3 | Schema validation |
| Vite | 5.0.11 | Build tool and dev server |
| Vitest | 1.1.3 | Unit testing framework |

### Architecture

The application follows a layered architecture pattern:

```
┌─────────────────────────────────────────┐
│         React Components Layer           │
│  (Presentational & Container Components) │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│         Redux State Layer                │
│     (Global Application State)           │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│         Service Layer                    │
│     (API Integration & Business Logic)   │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│         Backend REST API                 │
│     (Spring Boot Microservices)          │
└─────────────────────────────────────────┘
```

## Prerequisites

Before you begin, ensure you have the following installed on your development machine:

### Required

- **Node.js**: 20.x LTS or higher
  ```bash
  node --version  # Should be v20.x.x or higher
  ```

- **npm**: 10.x or higher
  ```bash
  npm --version  # Should be 10.x.x or higher
  ```

### Optional

- **Docker Desktop**: For containerized development
  - Download from [https://www.docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop)

- **Visual Studio Code**: Recommended IDE
  - Download from [https://code.visualstudio.com/](https://code.visualstudio.com/)

### Recommended VS Code Extensions

- ESLint (`dbaeumer.vscode-eslint`)
- Prettier - Code formatter (`esbenp.prettier-vscode`)
- ES7+ React/Redux/React-Native snippets (`dsznajder.es7-react-js-snippets`)
- Auto Rename Tag (`formulahendry.auto-rename-tag`)
- Path Intellisense (`christian-kohler.path-intellisense`)

## Quick Start

### Local Development

1. **Navigate to the frontend directory**:
   ```bash
   cd frontend
   ```

2. **Install dependencies**:
   ```bash
   npm install
   ```

3. **Start the development server**:
   ```bash
   npm run dev
   ```

4. **Access the application**:
   Open your browser and navigate to [http://localhost:3000](http://localhost:3000)

5. **Default credentials**:
   - Regular User: `USER0001` / `PASSWORD`
   - Admin User: `ADMIN001` / `PASSWORD`

### Docker Compose

From the repository root directory:

```bash
# Start all services (frontend, backend, database, redis)
docker-compose up

# Start only the frontend
docker-compose up frontend

# Stop all services
docker-compose down
```

The application will be available at [http://localhost:3000](http://localhost:3000)

## Project Structure

```
frontend/
├── public/                     # Static assets
│   ├── index.html             # HTML template
│   ├── manifest.json          # PWA manifest
│   └── favicon.ico            # Application icon
├── src/
│   ├── App.jsx                # Root component with routing
│   ├── index.js               # React DOM entry point
│   ├── components/            # React functional components
│   │   ├── auth/              # Authentication components
│   │   │   └── LoginComponent.jsx          # Login form (from COSGN00 BMS)
│   │   ├── menu/              # Navigation menu components
│   │   │   ├── MainMenuComponent.jsx       # Main menu (from COMEN01 BMS)
│   │   │   └── SecondaryMenuComponent.jsx  # Secondary menu (from COMEN02 BMS)
│   │   ├── account/           # Account management components
│   │   │   ├── AccountViewComponent.jsx    # Account details view (from COACTVW BMS)
│   │   │   ├── AccountAddComponent.jsx     # Add new account (from COACTADD BMS)
│   │   │   └── AccountUpdateComponent.jsx  # Update account (from COACTUP BMS)
│   │   ├── card/              # Card management components
│   │   │   ├── CardListComponent.jsx       # Card list (from COCRDLI BMS)
│   │   │   ├── CardSelectComponent.jsx     # Card selection (from COCRDSL BMS)
│   │   │   └── CardUpdateComponent.jsx     # Card update (from COCRDUP BMS)
│   │   ├── transaction/       # Transaction components
│   │   │   ├── TransactionListComponent.jsx      # Transaction list (from COTRN00 BMS)
│   │   │   ├── TransactionCategoryComponent.jsx  # Category summary (from COTRN01 BMS)
│   │   │   └── TransactionAddComponent.jsx       # Add transaction (from COTRN02 BMS)
│   │   ├── payment/           # Payment components
│   │   │   └── BillPaymentComponent.jsx    # Bill payment (from COBIL00 BMS)
│   │   ├── report/            # Report components
│   │   │   └── ReportMenuComponent.jsx     # Report menu (from CORPT00 BMS)
│   │   ├── admin/             # Administrative components
│   │   │   └── AdminComponent.jsx          # Admin dashboard (from COADM01 BMS)
│   │   ├── user/              # User management components
│   │   │   ├── UserManagementComponent.jsx # User list (from COUSR00 BMS)
│   │   │   └── UserProfileComponent.jsx    # User profile (from COUSR01 BMS)
│   │   └── common/            # Shared components
│   │       ├── Header.jsx                  # Application header
│   │       ├── Footer.jsx                  # Application footer
│   │       ├── ErrorBoundary.jsx           # Error boundary wrapper
│   │       └── Loading.jsx                 # Loading spinner
│   ├── services/              # API client services
│   │   ├── authService.js     # Authentication API calls
│   │   ├── accountService.js  # Account API calls
│   │   ├── cardService.js     # Card API calls
│   │   ├── transactionService.js  # Transaction API calls
│   │   └── apiClient.js       # Axios configuration with interceptors
│   ├── redux/                 # State management
│   │   ├── store.js           # Redux store configuration
│   │   ├── slices/            # Redux Toolkit slices
│   │   │   ├── authSlice.js   # Authentication state
│   │   │   ├── accountSlice.js    # Account state
│   │   │   ├── cardSlice.js       # Card state
│   │   │   └── transactionSlice.js # Transaction state
│   │   └── middleware/        # Custom middleware
│   │       └── apiMiddleware.js   # API error handling middleware
│   ├── utils/                 # Utility functions
│   │   ├── formatters.js      # Data formatting (dates, currency, phone)
│   │   ├── validators.js      # Form validation schemas (Yup)
│   │   └── constants.js       # Application constants and enums
│   └── styles/                # CSS modules
│       ├── App.css            # Global styles
│       └── components.css     # Component-specific styles
├── package.json               # npm dependencies and scripts
├── package-lock.json          # Locked dependency versions
├── vite.config.js             # Vite configuration
├── .eslintrc.json             # ESLint configuration
├── .prettierrc                # Prettier configuration
├── Dockerfile                 # Container build configuration
└── README.md                  # This file
```

### Directory Organization Principles

- **components/**: Organized by feature domain (auth, account, card, etc.)
- **services/**: One service file per backend API domain
- **redux/slices/**: One slice per major state domain
- **utils/**: Pure functions with no side effects
- **styles/**: Co-located CSS modules or global styles

## Available Scripts

### Development

```bash
# Start development server with hot module replacement
npm run dev

# Build for production
npm run build

# Preview production build locally
npm run preview
```

### Testing

```bash
# Run all tests
npm test

# Run tests in watch mode (for Test-Driven Development)
npm run test:watch

# Generate test coverage report
npm run test:coverage
```

### Code Quality

```bash
# Run ESLint to check for code issues
npm run lint

# Auto-fix ESLint issues
npm run lint:fix

# Format code with Prettier
npm run format
```

### Docker

```bash
# Build Docker image
docker build -t carddemo-frontend:latest .

# Run container
docker run -p 3000:80 \
  -e REACT_APP_API_URL=http://localhost:8080/api \
  carddemo-frontend:latest
```

## Component Architecture

### BMS Screen to React Component Mapping

Each of the 17 BMS 3270 mapsets has been transformed into a React functional component, preserving the original screen functionality while providing a modern user experience.

| BMS Screen | COBOL Program | React Component | Route | Purpose |
|-----------|--------------|----------------|-------|---------|
| COSGN00 | COSGN00C | LoginComponent | `/login` | User authentication |
| COMEN01 | COMEN01C | MainMenuComponent | `/menu` | Main navigation menu |
| COMEN02 | COMEN01C | SecondaryMenuComponent | `/menu/secondary` | Secondary menu options |
| COACTVW | COACTVWC | AccountViewComponent | `/accounts/:id` | View account details |
| COACTADD | COACTADD | AccountAddComponent | `/accounts/new` | Create new account |
| COACTUP | COACTUPC | AccountUpdateComponent | `/accounts/:id/edit` | Update account information |
| COCRDLI | COCRDLIC | CardListComponent | `/cards` | List cards (7 per page) |
| COCRDSL | COCRDSLC | CardSelectComponent | `/cards/select` | Select card for operations |
| COCRDUP | COCRDUPC | CardUpdateComponent | `/cards/:id/edit` | Update card details |
| COTRN00 | COTRN00C | TransactionListComponent | `/transactions` | List transactions (10 per page) |
| COTRN01 | COTRN01C | TransactionCategoryComponent | `/transactions/categories` | Transaction category summary |
| COTRN02 | COTRN02C | TransactionAddComponent | `/transactions/new` | Add new transaction |
| COBIL00 | COBIL00C | BillPaymentComponent | `/payments` | Process bill payment |
| CORPT00 | CORPT00C | ReportMenuComponent | `/reports` | Report generation menu |
| COADM01 | COADM01C | AdminComponent | `/admin` | Administrative dashboard |
| COUSR00 | COUSR00C | UserManagementComponent | `/users` | User list and management |
| COUSR01 | COUSR01C | UserProfileComponent | `/profile` | View/edit user profile |

### Component Design Patterns

All components follow React best practices and modern patterns:

#### Functional Components with Hooks

```javascript
import React, { useState, useEffect } from 'react';
import { useSelector, useDispatch } from 'react-redux';

const AccountViewComponent = () => {
  const [loading, setLoading] = useState(true);
  const dispatch = useDispatch();
  const account = useSelector(state => state.accounts.selectedAccount);

  useEffect(() => {
    // Fetch account data on component mount
    dispatch(fetchAccountById(accountId));
  }, [accountId, dispatch]);

  return (
    // Component JSX
  );
};
```

#### Material-UI Integration

All components use Material-UI components for consistent design:

```javascript
import { 
  Card, 
  CardContent, 
  TextField, 
  Button, 
  Grid 
} from '@mui/material';

// Consistent Material Design UI elements
```

#### Form Handling with Formik and Yup

```javascript
import { Formik, Form, Field } from 'formik';
import * as yup from 'yup';

const validationSchema = yup.object({
  accountId: yup.string()
    .matches(/^[0-9]{11}$/, 'Account ID must be 11 digits')
    .required('Account ID is required'),
  creditLimit: yup.number()
    .min(0, 'Credit limit must be positive')
    .max(999999.99, 'Credit limit exceeds maximum')
    .required('Credit limit is required')
});

// Formik provides form state management and validation
```

#### Error Boundaries

All components are wrapped in error boundaries for graceful error handling:

```javascript
<ErrorBoundary>
  <AccountViewComponent />
</ErrorBoundary>
```

### BMS Field Attribute Preservation

React components maintain the field behavior from original BMS screens:

| BMS Attribute | React Equivalent | Implementation |
|--------------|------------------|----------------|
| UNPROT (Unprotected) | Enabled input | `<TextField disabled={false} />` |
| PROT (Protected) | Disabled/readonly | `<TextField disabled={true} />` |
| NUM (Numeric) | Number input | `<TextField type="number" />` |
| IC (Initial Cursor) | Auto-focus | `<TextField autoFocus={true} />` |
| BRT (Bright) | Bold text | `<Typography fontWeight="bold" />` |
| FSET (Modified Data Tag) | Dirty field tracking | Formik `touched` state |
| PICIN/PICOUT | Input mask | `react-input-mask` component |

## State Management

### Redux Toolkit Architecture

The application uses Redux Toolkit for centralized state management with four primary slices:

#### 1. Auth Slice

Manages authentication state and user session:

```javascript
// redux/slices/authSlice.js
import { createSlice, createAsyncThunk } from '@reduxjs/toolkit';

export const loginUser = createAsyncThunk(
  'auth/login',
  async (credentials, { rejectWithValue }) => {
    try {
      const response = await authService.login(credentials);
      return response.data;
    } catch (error) {
      return rejectWithValue(error.response.data);
    }
  }
);

const authSlice = createSlice({
  name: 'auth',
  initialState: {
    user: null,
    token: null,
    isAuthenticated: false,
    loading: false,
    error: null
  },
  reducers: {
    logout: (state) => {
      state.user = null;
      state.token = null;
      state.isAuthenticated = false;
    }
  },
  extraReducers: (builder) => {
    builder
      .addCase(loginUser.pending, (state) => {
        state.loading = true;
      })
      .addCase(loginUser.fulfilled, (state, action) => {
        state.loading = false;
        state.isAuthenticated = true;
        state.user = action.payload.user;
        state.token = action.payload.token;
      })
      .addCase(loginUser.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload;
      });
  }
});
```

#### 2. Account Slice

Manages account data and operations:

```javascript
// State structure
{
  accounts: [],
  selectedAccount: null,
  loading: false,
  error: null,
  pagination: {
    currentPage: 1,
    pageSize: 10,
    totalPages: 0,
    totalItems: 0
  }
}
```

#### 3. Card Slice

Manages card data with pagination (7 cards per page):

```javascript
// State structure
{
  cards: [],
  selectedCard: null,
  loading: false,
  error: null,
  pagination: {
    currentPage: 1,
    pageSize: 7,  // Maintains BMS screen limit
    totalPages: 0,
    totalItems: 0
  }
}
```

#### 4. Transaction Slice

Manages transaction data with pagination (10 transactions per page):

```javascript
// State structure
{
  transactions: [],
  categories: [],
  filters: {
    dateFrom: null,
    dateTo: null,
    category: null,
    minAmount: null,
    maxAmount: null
  },
  loading: false,
  error: null,
  pagination: {
    currentPage: 1,
    pageSize: 10,  // Maintains BMS screen limit
    totalPages: 0,
    totalItems: 0
  }
}
```

### Redux Store Configuration

```javascript
// redux/store.js
import { configureStore } from '@reduxjs/toolkit';
import authReducer from './slices/authSlice';
import accountReducer from './slices/accountSlice';
import cardReducer from './slices/cardSlice';
import transactionReducer from './slices/transactionSlice';

export const store = configureStore({
  reducer: {
    auth: authReducer,
    accounts: accountReducer,
    cards: cardReducer,
    transactions: transactionReducer
  },
  middleware: (getDefaultMiddleware) =>
    getDefaultMiddleware().concat(apiMiddleware),
  devTools: process.env.NODE_ENV !== 'production'
});
```

## API Integration

### Backend Integration

The frontend communicates with the Spring Boot backend through RESTful APIs:

- **Base URL**: `http://localhost:8080/api` (configurable via environment variables)
- **Authentication**: JWT token in `Authorization: Bearer <token>` header
- **Data Format**: JSON request/response (transformed from COBOL COMMAREA structures)
- **Error Handling**: Standardized error responses with toast notifications

### API Client Configuration

```javascript
// services/apiClient.js
import axios from 'axios';
import { store } from '../redux/store';
import { logout } from '../redux/slices/authSlice';

const apiClient = axios.create({
  baseURL: process.env.REACT_APP_API_URL || 'http://localhost:8080/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
});

// Request interceptor - Add JWT token to all requests
apiClient.interceptors.request.use(
  (config) => {
    const token = store.getState().auth.token;
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// Response interceptor - Handle errors globally
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      // Token expired or invalid - logout user
      store.dispatch(logout());
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export default apiClient;
```

### Service Layer

Each API domain has a dedicated service file:

#### Authentication Service

```javascript
// services/authService.js
import apiClient from './apiClient';

export const login = async (credentials) => {
  const response = await apiClient.post('/auth/login', credentials);
  return response.data;
};

export const logout = async () => {
  const response = await apiClient.post('/auth/logout');
  return response.data;
};

export const refreshToken = async () => {
  const response = await apiClient.post('/auth/refresh');
  return response.data;
};
```

#### Account Service

```javascript
// services/accountService.js
import apiClient from './apiClient';

export const getAccountById = async (accountId) => {
  const response = await apiClient.get(`/accounts/${accountId}`);
  return response.data;
};

export const updateAccount = async (accountId, data) => {
  const response = await apiClient.put(`/accounts/${accountId}`, data);
  return response.data;
};

export const createAccount = async (data) => {
  const response = await apiClient.post('/accounts', data);
  return response.data;
};

export const getAccountsByCustomer = async (customerId, page = 1, size = 10) => {
  const response = await apiClient.get('/accounts', {
    params: { customerId, page, size }
  });
  return response.data;
};
```

### COMMAREA to JSON Transformation

Original COBOL COMMAREA structures are transformed to JSON DTOs:

```javascript
// COBOL COMMAREA (from COSGN00 BMS)
01 SIGN-ON-REQUEST.
   05 USER-ID           PIC X(8).
   05 PASSWORD          PIC X(8).

// React/JSON Equivalent
{
  "userId": "USER0001",
  "password": "PASSWORD"
}

// Response
{
  "responseCode": "00",
  "userName": "John Smith",
  "userType": "R",
  "token": "eyJhbGciOiJIUzI1NiIs..."
}
```

## Field Validation

### Client-Side Validation

The application implements comprehensive client-side validation using Yup schemas, matching the original COBOL validation rules:

#### Account Validation Schema

```javascript
// utils/validators.js
import * as yup from 'yup';

export const accountSchema = yup.object({
  accountId: yup.string()
    .matches(/^[0-9]{11}$/, 'Account ID must be 11 digits')
    .required('Account ID is required'),
  
  creditLimit: yup.number()
    .min(0, 'Credit limit must be positive')
    .max(999999.99, 'Credit limit exceeds maximum')
    .required('Credit limit is required'),
  
  cashCreditLimit: yup.number()
    .min(0, 'Cash credit limit must be positive')
    .max(yup.ref('creditLimit'), 'Cash limit cannot exceed credit limit')
    .required('Cash credit limit is required'),
  
  expirationDate: yup.date()
    .min(new Date(), 'Expiration date must be in the future')
    .required('Expiration date is required'),
  
  accountStatus: yup.string()
    .oneOf(['A', 'C', 'S'], 'Invalid account status')
    .required('Account status is required')
});
```

#### Card Validation Schema

```javascript
export const cardSchema = yup.object({
  cardNumber: yup.string()
    .matches(/^[0-9]{16}$/, 'Card number must be 16 digits')
    .test('luhn', 'Invalid card number', (value) => {
      // Luhn algorithm validation
      return validateLuhn(value);
    })
    .required('Card number is required'),
  
  expirationMonth: yup.number()
    .min(1, 'Month must be between 1 and 12')
    .max(12, 'Month must be between 1 and 12')
    .required('Expiration month is required'),
  
  expirationYear: yup.number()
    .min(new Date().getFullYear(), 'Year must be current or future')
    .required('Expiration year is required'),
  
  cvv: yup.string()
    .matches(/^[0-9]{3,4}$/, 'CVV must be 3 or 4 digits')
    .required('CVV is required')
});
```

#### Transaction Validation Schema

```javascript
export const transactionSchema = yup.object({
  transactionAmount: yup.number()
    .min(0.01, 'Amount must be greater than zero')
    .max(99999.99, 'Amount exceeds maximum')
    .test('decimal', 'Amount must have at most 2 decimal places', (value) => {
      return /^\d+(\.\d{1,2})?$/.test(value);
    })
    .required('Transaction amount is required'),
  
  transactionType: yup.string()
    .required('Transaction type is required'),
  
  merchantName: yup.string()
    .max(50, 'Merchant name cannot exceed 50 characters')
    .required('Merchant name is required'),
  
  transactionDate: yup.date()
    .max(new Date(), 'Transaction date cannot be in the future')
    .required('Transaction date is required')
});
```

### Validation Usage in Components

```javascript
import { Formik, Form, Field } from 'formik';
import { TextField } from 'formik-mui';
import { accountSchema } from '../utils/validators';

const AccountUpdateComponent = () => {
  return (
    <Formik
      initialValues={initialValues}
      validationSchema={accountSchema}
      onSubmit={handleSubmit}
    >
      {({ errors, touched }) => (
        <Form>
          <Field
            component={TextField}
            name="accountId"
            label="Account ID"
            fullWidth
            error={touched.accountId && Boolean(errors.accountId)}
            helperText={touched.accountId && errors.accountId}
          />
          {/* Additional fields */}
        </Form>
      )}
    </Formik>
  );
};
```

## Pagination

### Maintaining COBOL Pagination Patterns

The application preserves the exact pagination behavior from the original BMS screens:

#### Card List Pagination (7 cards per page)

```javascript
// CardListComponent.jsx
import { Pagination } from '@mui/material';

const CardListComponent = () => {
  const [page, setPage] = useState(1);
  const pageSize = 7;  // Matches COCRDLI BMS screen limit

  useEffect(() => {
    dispatch(fetchCards({ page, pageSize }));
  }, [page, dispatch]);

  const handlePageChange = (event, value) => {
    setPage(value);
  };

  return (
    <>
      <CardList cards={cards} />
      <Pagination 
        count={totalPages}
        page={page}
        onChange={handlePageChange}
        color="primary"
      />
    </>
  );
};
```

#### Transaction List Pagination (10 transactions per page)

```javascript
// TransactionListComponent.jsx
const TransactionListComponent = () => {
  const [page, setPage] = useState(1);
  const pageSize = 10;  // Matches COTRN00 BMS screen limit

  useEffect(() => {
    dispatch(fetchTransactions({ page, pageSize, filters }));
  }, [page, filters, dispatch]);

  return (
    <>
      <TransactionTable transactions={transactions} />
      <Pagination 
        count={totalPages}
        page={page}
        onChange={(e, value) => setPage(value)}
        showFirstButton
        showLastButton
      />
    </>
  );
};
```

### Pagination State Management

Redux manages pagination state for each domain:

```javascript
// Redux slice pagination state
{
  pagination: {
    currentPage: 1,
    pageSize: 7,  // or 10, depending on screen
    totalPages: 5,
    totalItems: 33
  }
}
```

## Navigation

### React Router Configuration

PF key functions from BMS screens are replaced with React Router navigation:

```javascript
// App.jsx
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Navigate to="/login" replace />} />
        <Route path="/login" element={<LoginComponent />} />
        
        {/* Protected routes */}
        <Route element={<ProtectedRoute />}>
          <Route path="/menu" element={<MainMenuComponent />} />
          <Route path="/accounts/:id" element={<AccountViewComponent />} />
          <Route path="/accounts/:id/edit" element={<AccountUpdateComponent />} />
          <Route path="/accounts/new" element={<AccountAddComponent />} />
          <Route path="/cards" element={<CardListComponent />} />
          <Route path="/cards/:id/edit" element={<CardUpdateComponent />} />
          <Route path="/transactions" element={<TransactionListComponent />} />
          <Route path="/transactions/new" element={<TransactionAddComponent />} />
          <Route path="/transactions/categories" element={<TransactionCategoryComponent />} />
          <Route path="/payments" element={<BillPaymentComponent />} />
          <Route path="/reports" element={<ReportMenuComponent />} />
          <Route path="/profile" element={<UserProfileComponent />} />
          
          {/* Admin routes */}
          <Route element={<AdminRoute />}>
            <Route path="/admin" element={<AdminComponent />} />
            <Route path="/users" element={<UserManagementComponent />} />
          </Route>
        </Route>
        
        <Route path="*" element={<NotFound />} />
      </Routes>
    </BrowserRouter>
  );
}
```

### PF Key to Button Mapping

| BMS PF Key | Function | React Implementation |
|-----------|----------|---------------------|
| PF3 | Return to previous screen | Back button with `navigate(-1)` |
| PF7 | Page backward | Previous button in pagination |
| PF8 | Page forward | Next button in pagination |
| PF12 | Cancel operation | Cancel button with `navigate('/menu')` |
| ENTER | Submit form | Submit button in Formik form |

### Navigation Implementation Examples

```javascript
import { useNavigate } from 'react-router-dom';
import { Button } from '@mui/material';

const AccountViewComponent = () => {
  const navigate = useNavigate();

  return (
    <>
      {/* PF3 - Return */}
      <Button onClick={() => navigate(-1)}>
        Back
      </Button>

      {/* PF12 - Cancel */}
      <Button onClick={() => navigate('/menu')}>
        Cancel
      </Button>

      {/* Navigate to edit screen */}
      <Button onClick={() => navigate(`/accounts/${accountId}/edit`)}>
        Edit Account
      </Button>
    </>
  );
};
```

## Testing

### Testing Strategy

The application implements comprehensive testing at multiple levels:

#### Unit Tests

Component tests using React Testing Library and Vitest:

```bash
# Run all tests
npm test

# Run tests in watch mode
npm run test:watch

# Generate coverage report
npm run test:coverage
```

#### Example Component Test

```javascript
// components/auth/__tests__/LoginComponent.test.jsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { Provider } from 'react-redux';
import { BrowserRouter } from 'react-router-dom';
import { vi } from 'vitest';
import LoginComponent from '../LoginComponent';
import { store } from '../../../redux/store';

describe('LoginComponent', () => {
  it('renders login form', () => {
    render(
      <Provider store={store}>
        <BrowserRouter>
          <LoginComponent />
        </BrowserRouter>
      </Provider>
    );

    expect(screen.getByLabelText(/user id/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /login/i })).toBeInTheDocument();
  });

  it('displays validation errors for empty fields', async () => {
    render(
      <Provider store={store}>
        <BrowserRouter>
          <LoginComponent />
        </BrowserRouter>
      </Provider>
    );

    const submitButton = screen.getByRole('button', { name: /login/i });
    fireEvent.click(submitButton);

    await waitFor(() => {
      expect(screen.getByText(/user id is required/i)).toBeInTheDocument();
      expect(screen.getByText(/password is required/i)).toBeInTheDocument();
    });
  });

  it('calls login API with correct credentials', async () => {
    const mockLogin = vi.fn();
    // Test implementation
  });
});
```

#### Redux Slice Tests

```javascript
// redux/slices/__tests__/authSlice.test.js
import authReducer, { loginUser, logout } from '../authSlice';

describe('authSlice', () => {
  const initialState = {
    user: null,
    token: null,
    isAuthenticated: false,
    loading: false,
    error: null
  };

  it('should handle logout', () => {
    const previousState = {
      user: { id: 1, name: 'Test User' },
      token: 'test-token',
      isAuthenticated: true
    };

    expect(authReducer(previousState, logout())).toEqual(initialState);
  });

  it('should handle loginUser.pending', () => {
    const action = { type: loginUser.pending.type };
    const state = authReducer(initialState, action);
    expect(state.loading).toBe(true);
  });

  // Additional tests...
});
```

#### Service Integration Tests

```javascript
// services/__tests__/accountService.test.js
import { vi } from 'vitest';
import * as accountService from '../accountService';
import apiClient from '../apiClient';

vi.mock('../apiClient');

describe('accountService', () => {
  it('fetches account by id', async () => {
    const mockAccount = { id: 1, accountId: '12345678901' };
    apiClient.get.mockResolvedValue({ data: mockAccount });

    const result = await accountService.getAccountById(1);

    expect(apiClient.get).toHaveBeenCalledWith('/accounts/1');
    expect(result).toEqual(mockAccount);
  });
});
```

### Coverage Requirements

- **Minimum Line Coverage**: 80%
- **Minimum Branch Coverage**: 70%
- **Critical Components**: 90%+ coverage (authentication, payment)

### Running Tests

```bash
# All tests with coverage
npm run test:coverage

# Watch mode for TDD
npm run test:watch

# CI/CD pipeline
npm run test -- --run --coverage
```

## Environment Configuration

### Environment Variables

The application uses environment-specific configuration files:

#### Development (.env.development)

```env
REACT_APP_API_URL=http://localhost:8080/api
REACT_APP_ENV=development
REACT_APP_LOG_LEVEL=debug
REACT_APP_ENABLE_REDUX_DEVTOOLS=true
```

#### Production (.env.production)

```env
REACT_APP_API_URL=https://api.carddemo.example.com/api
REACT_APP_ENV=production
REACT_APP_LOG_LEVEL=error
REACT_APP_ENABLE_REDUX_DEVTOOLS=false
```

### Configuration Usage

```javascript
// Access environment variables
const apiUrl = process.env.REACT_APP_API_URL;
const environment = process.env.REACT_APP_ENV;

// Conditional behavior based on environment
if (process.env.NODE_ENV === 'development') {
  console.log('Running in development mode');
}
```

### Build-Time vs Runtime Configuration

- **Build-Time**: Environment variables prefixed with `REACT_APP_` are embedded in the build
- **Runtime**: For Docker deployments, use environment variable substitution in the container

## Docker Build

### Dockerfile

```dockerfile
# Multi-stage build for optimized production image

# Stage 1: Build
FROM node:20-alpine AS build

WORKDIR /app

# Copy package files
COPY package*.json ./

# Install dependencies
RUN npm ci

# Copy source code
COPY . .

# Build production bundle
RUN npm run build

# Stage 2: Production
FROM nginx:1.25-alpine

# Copy custom nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Copy built assets from build stage
COPY --from=build /app/dist /usr/share/nginx/html

# Expose port 80
EXPOSE 80

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost/ || exit 1

# Start nginx
CMD ["nginx", "-g", "daemon off;"]
```

### Building the Docker Image

```bash
# Build image
docker build -t carddemo-frontend:latest .

# Build with specific tag
docker build -t carddemo-frontend:1.0.0 .

# Build with build arguments
docker build \
  --build-arg REACT_APP_API_URL=https://api.example.com \
  -t carddemo-frontend:latest .
```

### Running the Container

```bash
# Run with environment variables
docker run -d \
  --name carddemo-frontend \
  -p 3000:80 \
  -e REACT_APP_API_URL=http://localhost:8080/api \
  carddemo-frontend:latest

# Run with custom nginx config
docker run -d \
  --name carddemo-frontend \
  -p 3000:80 \
  -v $(pwd)/nginx.conf:/etc/nginx/conf.d/default.conf \
  carddemo-frontend:latest
```

### Docker Compose

```yaml
# docker-compose.yml (frontend service)
services:
  frontend:
    build:
      context: ./frontend
      dockerfile: Dockerfile
    ports:
      - "3000:80"
    environment:
      - REACT_APP_API_URL=http://backend:8080/api
    depends_on:
      - backend
    networks:
      - carddemo-network
```

## Styling

### Material-UI Theming

The application uses a custom Material-UI theme matching the CardDemo branding:

```javascript
// theme.js
import { createTheme } from '@mui/material/styles';

export const theme = createTheme({
  palette: {
    primary: {
      main: '#1976d2',
      light: '#42a5f5',
      dark: '#1565c0',
      contrastText: '#fff'
    },
    secondary: {
      main: '#dc004e',
      light: '#ff5c8d',
      dark: '#9a0036',
      contrastText: '#fff'
    },
    error: {
      main: '#f44336'
    },
    warning: {
      main: '#ff9800'
    },
    success: {
      main: '#4caf50'
    },
    background: {
      default: '#f5f5f5',
      paper: '#ffffff'
    }
  },
  typography: {
    fontFamily: '"Roboto", "Helvetica", "Arial", sans-serif',
    h1: {
      fontSize: '2.5rem',
      fontWeight: 500
    },
    h2: {
      fontSize: '2rem',
      fontWeight: 500
    },
    button: {
      textTransform: 'none',
      fontWeight: 500
    }
  },
  shape: {
    borderRadius: 8
  },
  spacing: 8
});
```

### Responsive Design

The application implements responsive layouts using Material-UI Grid:

```javascript
<Grid container spacing={3}>
  <Grid item xs={12} sm={6} md={4}>
    {/* Responsive card */}
  </Grid>
</Grid>
```

### Accessibility

- **WCAG 2.1 Level AA** compliance
- Proper ARIA labels on all interactive elements
- Keyboard navigation support
- Screen reader compatibility
- Color contrast ratios meeting accessibility standards

## Performance

### Optimization Strategies

#### Code Splitting

```javascript
import React, { lazy, Suspense } from 'react';

// Lazy load components
const AccountViewComponent = lazy(() => import('./components/account/AccountViewComponent'));
const CardListComponent = lazy(() => import('./components/card/CardListComponent'));

function App() {
  return (
    <Suspense fallback={<Loading />}>
      <Routes>
        <Route path="/accounts/:id" element={<AccountViewComponent />} />
        <Route path="/cards" element={<CardListComponent />} />
      </Routes>
    </Suspense>
  );
}
```

#### Memoization

```javascript
import React, { memo, useMemo } from 'react';

// Memoize expensive components
const TransactionRow = memo(({ transaction }) => {
  return <TableRow>{/* transaction data */}</TableRow>;
});

// Memoize expensive computations
const TransactionListComponent = () => {
  const sortedTransactions = useMemo(() => {
    return transactions.sort((a, b) => b.date - a.date);
  }, [transactions]);

  return <Table data={sortedTransactions} />;
};
```

#### Virtual Scrolling

For large lists, use react-window:

```javascript
import { FixedSizeList } from 'react-window';

const TransactionVirtualList = ({ transactions }) => {
  const Row = ({ index, style }) => (
    <div style={style}>
      <TransactionRow transaction={transactions[index]} />
    </div>
  );

  return (
    <FixedSizeList
      height={600}
      itemCount={transactions.length}
      itemSize={80}
      width="100%"
    >
      {Row}
    </FixedSizeList>
  );
};
```

#### Bundle Optimization

Vite automatically performs:
- Tree shaking for unused code removal
- Minification of JavaScript and CSS
- Code splitting for optimal chunk sizes
- Asset optimization (images, fonts)

### Performance Metrics

- **First Contentful Paint (FCP)**: < 1.5s
- **Largest Contentful Paint (LCP)**: < 2.5s
- **Time to Interactive (TTI)**: < 3.5s
- **Cumulative Layout Shift (CLS)**: < 0.1

## Browser Support

### Supported Browsers

The application supports modern browsers:

- **Chrome**: Latest 2 versions
- **Firefox**: Latest 2 versions
- **Safari**: Latest 2 versions
- **Edge**: Latest 2 versions

### Not Supported

- Internet Explorer (any version)
- Legacy Edge (pre-Chromium)

### Progressive Enhancement

The application uses progressive enhancement principles:
- Core functionality works without JavaScript (where possible)
- Enhanced features for modern browsers
- Graceful degradation for older browsers

## Troubleshooting

### Common Issues and Solutions

#### Port 3000 Already in Use

**Problem**: Development server fails to start because port 3000 is in use.

**Solution**: Change the port in `vite.config.js`:

```javascript
export default defineConfig({
  server: {
    port: 3001
  }
});
```

Or use environment variable:
```bash
PORT=3001 npm run dev
```

#### API Connection Errors

**Problem**: Frontend cannot connect to backend API.

**Solution**:
1. Verify backend is running on port 8080
2. Check `REACT_APP_API_URL` in `.env` file
3. Verify CORS is configured on backend to allow frontend origin

#### CORS Errors

**Problem**: Browser blocks API requests due to CORS policy.

**Solution**: Backend must include CORS headers:

```java
// Spring Boot backend
@CrossOrigin(origins = "http://localhost:3000")
```

#### JWT Token Expiration

**Problem**: User is unexpectedly logged out.

**Solution**: Token is expired. Options:
1. Implement automatic token refresh
2. Increase token expiration time on backend
3. Show warning before expiration

#### Build Failures

**Problem**: `npm run build` fails with memory errors.

**Solution**: Increase Node.js memory limit:

```bash
NODE_OPTIONS=--max_old_space_size=4096 npm run build
```

#### Slow Development Server

**Problem**: Vite dev server is slow to start or reload.

**Solution**:
1. Clear node_modules and reinstall: `rm -rf node_modules && npm install`
2. Clear Vite cache: `rm -rf node_modules/.vite`
3. Disable browser extensions that may interfere

### Getting Help

If you encounter issues not covered here:

1. Check the [GitHub Issues](https://github.com/your-repo/issues)
2. Review backend logs for API errors
3. Check browser console for frontend errors
4. Enable Redux DevTools for state debugging

## Additional Documentation

### Related Documentation

- **API Documentation**: [../docs/API.md](../docs/API.md)
  - Complete REST API endpoint reference
  - Request/response schemas
  - Authentication flow

- **Architecture Documentation**: [../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)
  - System architecture diagrams
  - Component relationships
  - Data flow diagrams

- **Deployment Guide**: [../docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md)
  - Kubernetes deployment instructions
  - Docker configuration
  - CI/CD pipeline setup

- **Migration Guide**: [../docs/MIGRATION_GUIDE.md](../docs/MIGRATION_GUIDE.md)
  - COBOL to Java transformation patterns
  - BMS to React component mapping
  - Data migration strategies

### Component Documentation

Each component includes JSDoc comments:

```javascript
/**
 * AccountViewComponent displays detailed account information.
 * 
 * @component
 * @param {Object} props - Component props
 * @param {string} props.accountId - Account identifier
 * @returns {JSX.Element} Account view component
 * 
 * @example
 * <AccountViewComponent accountId="12345678901" />
 */
const AccountViewComponent = ({ accountId }) => {
  // Component implementation
};
```

## Contributing

We welcome contributions to improve the CardDemo frontend!

### Development Workflow

1. **Fork the repository**
2. **Create a feature branch**:
   ```bash
   git checkout -b feature/your-feature-name
   ```
3. **Make your changes**
4. **Run tests**:
   ```bash
   npm test
   npm run lint
   ```
5. **Commit your changes**:
   ```bash
   git commit -m "feat: add new feature"
   ```
6. **Push to your fork**:
   ```bash
   git push origin feature/your-feature-name
   ```
7. **Create a pull request**

### Coding Standards

- Follow ESLint configuration
- Use Prettier for code formatting
- Write tests for new features
- Update documentation for significant changes
- Use semantic commit messages (feat, fix, docs, style, refactor, test, chore)

### Pull Request Guidelines

- Provide clear description of changes
- Reference related issues
- Include screenshots for UI changes
- Ensure all tests pass
- Update README if needed

## License

This project is licensed under the Apache License 2.0.

Copyright 2024 CardDemo Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

---

**CardDemo Frontend** - Modern React 18 application for credit card management

For questions or support, please open an issue in the GitHub repository.


