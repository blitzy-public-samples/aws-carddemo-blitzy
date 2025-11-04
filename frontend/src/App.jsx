/**
 * CardDemo Application - Root Component
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * 
 * Root React component providing application-level routing, layout structure,
 * and global error boundary. Configures React Router with 17+ routes mapping
 * to transformed BMS screen components, implements authentication guards for
 * protected routes, provides Material-UI ThemeProvider for consistent theming,
 * and includes global components (Header, Footer, ErrorBoundary).
 * 
 * Transformation: BMS COMEN01 mapset navigation structure → React Router 6
 * Source: app/bms/COMEN01.bms (Main Menu Screen)
 * Target: Modern SPA with client-side routing replacing CICS transaction flow
 */

import React from 'react';
import { Routes, Route, Navigate, BrowserRouter, Outlet, useNavigate, useLocation } from 'react-router-dom';
import { ThemeProvider, createTheme } from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';
import { Box, Container } from '@mui/material';
import { ToastContainer } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';

// Layout components - Common UI elements
import Header from './components/common/Header';
import Footer from './components/common/Footer';
import ErrorBoundary from './components/common/ErrorBoundary';
import Loading from './components/common/Loading';

// Auth components - User authentication (COSGN00C → LoginComponent)
import LoginComponent from './components/auth/LoginComponent';

// Menu components - Navigation menus (COMEN01C → MainMenuComponent)
import MainMenuComponent from './components/menu/MainMenuComponent';
import SecondaryMenuComponent from './components/menu/SecondaryMenuComponent';

// Account components - Credit card account management (COACTVWC, COACTUPC, COACTADD)
import AccountViewComponent from './components/account/AccountViewComponent';
import AccountAddComponent from './components/account/AccountAddComponent';
import AccountUpdateComponent from './components/account/AccountUpdateComponent';

// Card components - Card management (COCRDLIC, COCRDSLC, COCRDUPC)
import CardListComponent from './components/card/CardListComponent';
import CardSelectComponent from './components/card/CardSelectComponent';
import CardUpdateComponent from './components/card/CardUpdateComponent';

// Transaction components - Transaction processing (COTRN00C, COTRN01C, COTRN02C)
import TransactionListComponent from './components/transaction/TransactionListComponent';
import TransactionCategoryComponent from './components/transaction/TransactionCategoryComponent';
import TransactionAddComponent from './components/transaction/TransactionAddComponent';

// Payment components - Bill payment (COBIL00C)
import BillPaymentComponent from './components/payment/BillPaymentComponent';

// Report components - Report generation (CORPT00C)
import ReportMenuComponent from './components/report/ReportMenuComponent';

// Admin components - Administrative functions (COADM01C)
import AdminComponent from './components/admin/AdminComponent';

// User components - User management (COUSR00C, COUSR01C)
import UserManagementComponent from './components/user/UserManagementComponent';
import UserProfileComponent from './components/user/UserProfileComponent';

/**
 * Material-UI Theme Configuration
 * 
 * Defines consistent color palette, typography, and component styles
 * throughout the application, replacing BMS color attributes
 * (COLOR=BLUE, COLOR=YELLOW, COLOR=RED) with modern Material Design
 */
const theme = createTheme({
  palette: {
    primary: {
      main: '#1976d2', // Primary blue - replaces BMS COLOR=BLUE
      light: '#42a5f5',
      dark: '#1565c0',
    },
    secondary: {
      main: '#dc004e', // Secondary red - replaces BMS COLOR=RED for errors
      light: '#ff5983',
      dark: '#9a0036',
    },
    warning: {
      main: '#ffa726', // Warning orange
    },
    success: {
      main: '#66bb6a', // Success green - replaces BMS COLOR=GREEN
    },
    info: {
      main: '#29b6f6', // Info cyan - replaces BMS COLOR=TURQUOISE
    },
    background: {
      default: '#f5f5f5', // Light gray background
      paper: '#ffffff',
    },
    text: {
      primary: '#212121',
      secondary: '#757575',
    },
  },
  typography: {
    fontFamily: [
      'Roboto',
      'Arial',
      'sans-serif',
      '-apple-system',
      'BlinkMacSystemFont',
    ].join(','),
    h1: {
      fontSize: '2.5rem',
      fontWeight: 500,
    },
    h2: {
      fontSize: '2rem',
      fontWeight: 500,
    },
    h3: {
      fontSize: '1.75rem',
      fontWeight: 500,
    },
    h4: {
      fontSize: '1.5rem',
      fontWeight: 500,
    },
    h5: {
      fontSize: '1.25rem',
      fontWeight: 500,
    },
    h6: {
      fontSize: '1rem',
      fontWeight: 500,
    },
    button: {
      textTransform: 'none', // Preserve case in buttons
    },
  },
  components: {
    MuiButton: {
      styleOverrides: {
        root: {
          borderRadius: 4,
        },
      },
    },
    MuiCard: {
      styleOverrides: {
        root: {
          borderRadius: 8,
        },
      },
    },
  },
});

/**
 * ProtectedLayout Component
 * 
 * Wrapper component for authenticated routes that:
 * 1. Checks JWT token authentication status
 * 2. Redirects to /login if not authenticated
 * 3. Renders Header + child routes + Footer for authenticated users
 * 4. Implements role-based route guards for admin access
 * 
 * Transformation: CICS transaction security → JWT token-based auth
 * Replaces: CICS HANDLE CONDITION NOTAUTH with React Router navigation
 */
const ProtectedLayout = () => {
  const navigate = useNavigate();
  const location = useLocation();

  // Check authentication status from sessionStorage or localStorage
  // JWT token set by LoginComponent after successful authentication
  const isAuthenticated = React.useMemo(() => {
    const token = sessionStorage.getItem('jwtToken') || localStorage.getItem('jwtToken');
    if (!token) {
      return false;
    }

    // Validate token expiration (basic check)
    try {
      const tokenPayload = JSON.parse(atob(token.split('.')[1]));
      const expirationTime = tokenPayload.exp * 1000; // Convert to milliseconds
      const currentTime = Date.now();
      
      if (currentTime >= expirationTime) {
        // Token expired, clear storage
        sessionStorage.removeItem('jwtToken');
        sessionStorage.removeItem('userType');
        sessionStorage.removeItem('userId');
        localStorage.removeItem('jwtToken');
        localStorage.removeItem('userType');
        localStorage.removeItem('userId');
        return false;
      }
      
      return true;
    } catch (error) {
      console.error('Token validation error:', error);
      return false;
    }
  }, []);

  // Redirect to login if not authenticated
  React.useEffect(() => {
    if (!isAuthenticated) {
      // Save attempted URL for redirect after login
      sessionStorage.setItem('redirectUrl', location.pathname);
      navigate('/login', { replace: true });
    }
  }, [isAuthenticated, navigate, location]);

  // Check admin role for admin routes
  const isAdminRoute = location.pathname.startsWith('/admin') || 
                       location.pathname.startsWith('/users');
  
  if (isAdminRoute) {
    const userType = sessionStorage.getItem('userType') || localStorage.getItem('userType');
    if (userType !== 'A') {
      // Not admin, redirect to main menu
      navigate('/menu', { replace: true });
      return null;
    }
  }

  if (!isAuthenticated) {
    return <Loading />;
  }

  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        minHeight: '100vh',
      }}
    >
      <Header />
      <Container
        component="main"
        maxWidth="lg"
        sx={{
          flex: 1,
          py: 3,
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        <Outlet />
      </Container>
      <Footer />
    </Box>
  );
};

/**
 * App Component - Root Application Component
 * 
 * Main application component that:
 * 1. Provides Material-UI theme context
 * 2. Configures React Router with all application routes
 * 3. Wraps application in ErrorBoundary for graceful error handling
 * 4. Includes ToastContainer for user notifications
 * 5. Defines route structure mapping CICS transactions to REST endpoints
 * 
 * Route Mapping (CICS Transaction ID → React Route):
 * - CC00 (Sign-on) → /login
 * - CM00 (Main Menu) → /menu
 * - CA00 (Account View) → /accounts/:id
 * - CA01 (Account Add) → /accounts/new
 * - CA02 (Account Update) → /accounts/:id/edit
 * - CC00 (Card List) → /cards
 * - CC01 (Card Select) → /cards/select
 * - CC02 (Card Update) → /cards/:id/edit
 * - CT00 (Transaction List) → /transactions
 * - CT01 (Transaction Category) → /transactions/categories
 * - CT02 (Transaction Add) → /transactions/new
 * - CB00 (Bill Payment) → /payments
 * - CR00 (Reports) → /reports
 * - CA00 (Admin) → /admin
 * - CU00 (User Management) → /users
 * - CU01 (User Profile) → /profile
 */
const App = () => {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <BrowserRouter>
        <ErrorBoundary>
          <Routes>
            {/* Public Routes - No authentication required */}
            <Route path="/login" element={<LoginComponent />} />
            
            {/* Protected Routes - Requires JWT authentication */}
            <Route element={<ProtectedLayout />}>
              {/* Main Menu - COMEN01C transformation */}
              <Route path="/menu" element={<MainMenuComponent />} />
              
              {/* Secondary Menu - COMEN02 transformation */}
              <Route path="/menu/secondary" element={<SecondaryMenuComponent />} />
              
              {/* Account Management Routes - COACTVWC, COACTUPC, COACTADD */}
              <Route path="/accounts" element={<AccountViewComponent />} />
              <Route path="/accounts/:id" element={<AccountViewComponent />} />
              <Route path="/accounts/new" element={<AccountAddComponent />} />
              <Route path="/accounts/:id/edit" element={<AccountUpdateComponent />} />
              
              {/* Card Management Routes - COCRDLIC, COCRDSLC, COCRDUPC */}
              <Route path="/cards" element={<CardListComponent />} />
              <Route path="/cards/select" element={<CardSelectComponent />} />
              <Route path="/cards/:id/edit" element={<CardUpdateComponent />} />
              
              {/* Transaction Routes - COTRN00C, COTRN01C, COTRN02C */}
              <Route path="/transactions" element={<TransactionListComponent />} />
              <Route path="/transactions/categories" element={<TransactionCategoryComponent />} />
              <Route path="/transactions/new" element={<TransactionAddComponent />} />
              
              {/* Bill Payment Routes - COBIL00C */}
              <Route path="/payments" element={<BillPaymentComponent />} />
              <Route path="/payments/bill" element={<BillPaymentComponent />} />
              
              {/* Report Routes - CORPT00C */}
              <Route path="/reports" element={<ReportMenuComponent />} />
              
              {/* Admin Routes - COADM01C (ROLE_ADMIN only) */}
              <Route path="/admin" element={<AdminComponent />} />
              
              {/* User Management Routes - COUSR00C, COUSR01C */}
              <Route path="/users" element={<UserManagementComponent />} />
              <Route path="/profile" element={<UserProfileComponent />} />
            </Route>
            
            {/* Default Redirect - Homepage redirects to login */}
            <Route path="/" element={<Navigate to="/login" replace />} />
            
            {/* Catch-all Route - 404 redirects to login */}
            <Route path="*" element={<Navigate to="/login" replace />} />
          </Routes>
          
          {/* Toast Notification Container
              Replaces BMS ERRMSG field (POS=(23,1), COLOR=RED) with modern
              non-blocking toast notifications for user feedback */}
          <ToastContainer
            position="top-right"
            autoClose={5000}
            hideProgressBar={false}
            newestOnTop={true}
            closeOnClick
            rtl={false}
            pauseOnFocusLoss
            draggable
            pauseOnHover
            theme="light"
          />
        </ErrorBoundary>
      </BrowserRouter>
    </ThemeProvider>
  );
};

export default App;
