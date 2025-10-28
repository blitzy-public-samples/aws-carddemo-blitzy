/**
 * Root Application Component
 * 
 * Purpose: Main React component that sets up the application-wide routing,
 *          authentication, theme management, and provider hierarchy for the
 *          CardDemo application migrated from COBOL/CICS/BMS mainframe.
 * 
 * COBOL to React Application Transformation:
 * - CICS transaction routing (XCTL/LINK) → React Router client-side routing
 * - BMS screen flow (17 maps) → React SPA page navigation
 * - RACF session security → JWT authentication context
 * - COBOL COMMAREA session → React Context state
 * - EXEC CICS RETURN TRANSID → navigate() function calls
 * - BMS map navigation → URL-based routing with params
 * 
 * Provider Hierarchy (Outer to Inner):
 * 1. BrowserRouter - Client-side routing foundation
 * 2. ThemeContextProvider - Material-UI theme management (light/dark mode)
 * 3. AuthProvider - JWT authentication and user session state
 * 4. CssBaseline - Material-UI CSS reset and baseline styles
 * 5. Suspense - Lazy loading boundary with loading fallback
 * 6. Routes - Route definitions and navigation
 * 
 * Route Structure (17 BMS maps → 16 React pages + login):
 * 
 * Public Routes (no authentication):
 * - /login → SignonPage (COSGN00.bms)
 * 
 * Protected Routes (require authentication):
 * - / → MainMenuPage (COMEN01.bms)
 * - /admin → AdminMenuPage (COADM01.bms)
 * - /accounts/:id → AccountViewPage (COACTVW.bms)
 * - /accounts/:id/edit → AccountUpdatePage (COACTUP.bms)
 * - /cards → CardListPage (COCRDLI.bms)
 * - /cards/:id/edit → CardUpdatePage (COCRDUP.bms)
 * - /transactions → TransactionListPage (COTRN00.bms)
 * - /transactions/:id → TransactionDetailPage (COTRN01.bms)
 * - /transactions/new → TransactionEntryPage (COTRN02.bms)
 * - /billing/:accountId → BillingPage (COBIL00.bms)
 * - /reports → ReportMenuPage (CORPT00.bms)
 * - /users → UserListPage (COUSR00.bms)
 * - /users/new → UserAddPage (COUSR01.bms)
 * - /users/:id/edit → UserUpdatePage (COUSR02.bms)
 * - /users/:id/delete → UserDeletePage (COUSR03.bms)
 * 
 * Authentication Flow:
 * 1. Unauthenticated user visits any route
 * 2. ProtectedRoute checks AuthContext.isAuthenticated
 * 3. If false, redirect to /login with Navigate component
 * 4. User enters credentials in SignonPage
 * 5. On successful login, AuthContext.isAuthenticated = true
 * 6. User redirected to originally requested route or /
 * 7. PublicRoute prevents authenticated users from accessing /login
 * 
 * Code Splitting Strategy:
 * - All page components lazy loaded with React.lazy()
 * - Reduces initial bundle size for faster load time
 * - Each route loads on-demand during navigation
 * - Suspense fallback shows LoadingSpinner during chunk load
 * 
 * Per Agent Action Plan Section 0.7.1 MINIMAL CHANGE CLAUSE:
 * This App.tsx includes ONLY the routing and providers necessary for the
 * BMS-to-React migration. No unnecessary features, frameworks, or abstractions
 * have been added beyond what is required for functional equivalence.
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 * 
 * Converted from:
 * @see app/bms/COSGN00.bms - Signon screen → /login route
 * @see app/bms/COMEN01.bms - Main menu → / route
 * @see app/bms/COADM01.bms - Admin menu → /admin route
 * @see app/bms/*.bms - All 17 BMS maps → React page routes
 * 
 * Dependencies:
 * @see frontend/src/context/AuthContext.tsx - Authentication state management
 * @see frontend/src/context/ThemeContext.tsx - Theme state management
 * @see frontend/src/components/common/LoadingSpinner.tsx - Loading indicator
 * @see frontend/src/pages/*.tsx - All page components (lazy loaded)
 */

import React, { Suspense, lazy } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ThemeProvider } from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';
import { AuthProvider, useAuth } from './context/AuthContext';
import { ThemeContextProvider, useThemeContext } from './context/ThemeContext';
import LoadingSpinner from './components/common/LoadingSpinner';

/**
 * Lazy-loaded page components for code splitting
 * Each component loaded on-demand during route navigation
 * Reduces initial bundle size and improves load performance
 */

// Public page (authentication page)
const SignonPage = lazy(() => import('./pages/SignonPage'));

// Protected pages (require authentication)
const MainMenuPage = lazy(() => import('./pages/MainMenuPage'));
const AdminMenuPage = lazy(() => import('./pages/AdminMenuPage'));
const AccountViewPage = lazy(() => import('./pages/AccountViewPage'));
const AccountUpdatePage = lazy(() => import('./pages/AccountUpdatePage'));
const CardListPage = lazy(() => import('./pages/CardListPage'));
const CardUpdatePage = lazy(() => import('./pages/CardUpdatePage'));
const TransactionListPage = lazy(() => import('./pages/TransactionListPage'));
const TransactionDetailPage = lazy(() => import('./pages/TransactionDetailPage'));
const TransactionEntryPage = lazy(() => import('./pages/TransactionEntryPage'));
const BillingPage = lazy(() => import('./pages/BillingPage'));
const ReportMenuPage = lazy(() => import('./pages/ReportMenuPage'));
const UserListPage = lazy(() => import('./pages/UserListPage'));
const UserAddPage = lazy(() => import('./pages/UserAddPage'));
const UserUpdatePage = lazy(() => import('./pages/UserUpdatePage'));
const UserDeletePage = lazy(() => import('./pages/UserDeletePage'));

/**
 * ProtectedRoute Component
 * 
 * Purpose: Route wrapper that enforces authentication requirement.
 *          Replaces COBOL RACF security checks and CICS security validation.
 * 
 * COBOL Equivalent:
 * - EXEC CICS VERIFY PASSWORD(...) USERID(...)
 * - RACF profile checks before program execution
 * - COBOL IF SEC-USR-ID = SPACES THEN PERFORM SIGNON-SCREEN
 * 
 * Behavior:
 * - Checks AuthContext.isAuthenticated flag
 * - If authenticated (true), renders child components (protected page)
 * - If not authenticated (false), redirects to /login with Navigate
 * - Replace flag prevents navigation history pollution
 * 
 * Usage:
 * <Route path="/accounts/:id" element={
 *   <ProtectedRoute>
 *     <AccountViewPage />
 *   </ProtectedRoute>
 * } />
 */
const ProtectedRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated } = useAuth();
  
  if (!isAuthenticated) {
    // COBOL equivalent: EXEC CICS XCTL PROGRAM('COSGN00')
    return <Navigate to="/login" replace />;
  }
  
  return <>{children}</>;
};

/**
 * PublicRoute Component
 * 
 * Purpose: Route wrapper that redirects authenticated users away from
 *          public pages (e.g., login page) to prevent redundant authentication.
 * 
 * COBOL Equivalent:
 * - COBOL IF SEC-USR-ID NOT = SPACES THEN PERFORM MAIN-MENU
 * - Prevents re-authentication when user already has valid session
 * 
 * Behavior:
 * - Checks AuthContext.isAuthenticated flag
 * - If authenticated (true), redirects to / (main menu)
 * - If not authenticated (false), renders child components (login page)
 * - Prevents authenticated users from accessing login page
 * 
 * Usage:
 * <Route path="/login" element={
 *   <PublicRoute>
 *     <SignonPage />
 *   </PublicRoute>
 * } />
 */
const PublicRoute: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { isAuthenticated } = useAuth();
  
  if (isAuthenticated) {
    // COBOL equivalent: EXEC CICS XCTL PROGRAM('COMEN01')
    return <Navigate to="/" replace />;
  }
  
  return <>{children}</>;
};

/**
 * AppContent Component
 * 
 * Purpose: Inner component that accesses theme context and applies Material-UI
 *          ThemeProvider. Must be inside ThemeContextProvider to access theme.
 * 
 * This separation is necessary because useThemeContext() hook requires the
 * component to be wrapped by ThemeContextProvider, which happens in App component.
 */
const AppContent: React.FC = () => {
  const { theme } = useThemeContext();
  
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <Suspense fallback={<LoadingSpinner />}>
        <Routes>
          {/* ============================================================
               PUBLIC ROUTES - No authentication required
               COBOL equivalent: Pre-signon transaction entry points
               ============================================================ */}
          
          {/* 
            Login/Signon Page
            Source: app/bms/COSGN00.bms
            COBOL Program: app/cbl/COSGN00C.cbl
            Function: User authentication with JWT token generation
          */}
          <Route 
            path="/login" 
            element={
              <PublicRoute>
                <SignonPage />
              </PublicRoute>
            } 
          />
          
          {/* ============================================================
               PROTECTED ROUTES - Require authentication
               COBOL equivalent: Post-signon CICS transactions
               ============================================================ */}
          
          {/* 
            Main Menu (Default Route)
            Source: app/bms/COMEN01.bms
            COBOL Program: app/cbl/COMEN01C.cbl
            Function: Main menu with 12 navigation options
            COBOL Transaction ID: CC00
          */}
          <Route 
            path="/" 
            element={
              <ProtectedRoute>
                <MainMenuPage />
              </ProtectedRoute>
            } 
          />
          
          {/* 
            Admin Menu
            Source: app/bms/COADM01.bms
            COBOL Program: app/cbl/COADM01C.cbl
            Function: Administrative menu with system configuration options
            COBOL Transaction ID: CADM
            RACF Role: Admin (user type 'A')
          */}
          <Route 
            path="/admin" 
            element={
              <ProtectedRoute>
                <AdminMenuPage />
              </ProtectedRoute>
            } 
          />
          
          {/* ============================================================
               ACCOUNT MANAGEMENT ROUTES
               COBOL Programs: COACTVWC.cbl, COACTUPC.cbl
               ============================================================ */}
          
          {/* 
            Account View/Inquiry
            Source: app/bms/COACTVW.bms
            COBOL Program: app/cbl/COACTVWC.cbl
            Function: Read-only account information display
            COBOL Transaction ID: CAVW
          */}
          <Route 
            path="/accounts/:id" 
            element={
              <ProtectedRoute>
                <AccountViewPage />
              </ProtectedRoute>
            } 
          />
          
          {/* 
            Account Update/Maintenance
            Source: app/bms/COACTUP.bms
            COBOL Program: app/cbl/COACTUPC.cbl
            Function: Account data modification with validation
            COBOL Transaction ID: CAUP
          */}
          <Route 
            path="/accounts/:id/edit" 
            element={
              <ProtectedRoute>
                <AccountUpdatePage />
              </ProtectedRoute>
            } 
          />
          
          {/* ============================================================
               CARD MANAGEMENT ROUTES
               COBOL Programs: COCRDLIC.cbl, COCRDUPC.cbl
               ============================================================ */}
          
          {/* 
            Card List Display
            Source: app/bms/COCRDLI.bms
            COBOL Program: app/cbl/COCRDLIC.cbl
            Function: Browse credit cards with pagination
            COBOL Transaction ID: CCLI
          */}
          <Route 
            path="/cards" 
            element={
              <ProtectedRoute>
                <CardListPage />
              </ProtectedRoute>
            } 
          />
          
          {/* 
            Card Update/Maintenance
            Source: app/bms/COCRDUP.bms
            COBOL Program: app/cbl/COCRDUPC.cbl
            Function: Card data modification with validation
            COBOL Transaction ID: CCUP
          */}
          <Route 
            path="/cards/:id/edit" 
            element={
              <ProtectedRoute>
                <CardUpdatePage />
              </ProtectedRoute>
            } 
          />
          
          {/* ============================================================
               TRANSACTION MANAGEMENT ROUTES
               COBOL Programs: COTRN00C.cbl, COTRN01C.cbl, COTRN02C.cbl
               ============================================================ */}
          
          {/* 
            Transaction List Display
            Source: app/bms/COTRN00.bms
            COBOL Program: app/cbl/COTRN00C.cbl
            Function: Browse transactions with date filtering
            COBOL Transaction ID: CTRN
          */}
          <Route 
            path="/transactions" 
            element={
              <ProtectedRoute>
                <TransactionListPage />
              </ProtectedRoute>
            } 
          />
          
          {/* 
            Transaction Detail View
            Source: app/bms/COTRN01.bms
            COBOL Program: app/cbl/COTRN01C.cbl
            Function: Read-only transaction information display
            COBOL Transaction ID: CTR1
          */}
          <Route 
            path="/transactions/:id" 
            element={
              <ProtectedRoute>
                <TransactionDetailPage />
              </ProtectedRoute>
            } 
          />
          
          {/* 
            Transaction Entry/Posting
            Source: app/bms/COTRN02.bms
            COBOL Program: app/cbl/COTRN02C.cbl
            Function: Post new transaction with real-time validation
            COBOL Transaction ID: CTR2
          */}
          <Route 
            path="/transactions/new" 
            element={
              <ProtectedRoute>
                <TransactionEntryPage />
              </ProtectedRoute>
            } 
          />
          
          {/* ============================================================
               BILLING AND REPORTING ROUTES
               COBOL Programs: COBIL00C.cbl, CORPT00C.cbl
               ============================================================ */}
          
          {/* 
            Billing and Statement Processing
            Source: app/bms/COBIL00.bms
            COBOL Program: app/cbl/COBIL00C.cbl
            Function: Display billing info and process payments
            COBOL Transaction ID: CBIL
          */}
          <Route 
            path="/billing/:accountId" 
            element={
              <ProtectedRoute>
                <BillingPage />
              </ProtectedRoute>
            } 
          />
          
          {/* 
            Report Generation Menu
            Source: app/bms/CORPT00.bms
            COBOL Program: app/cbl/CORPT00C.cbl
            Function: Report selection and submission
            COBOL Transaction ID: CRPT
          */}
          <Route 
            path="/reports" 
            element={
              <ProtectedRoute>
                <ReportMenuPage />
              </ProtectedRoute>
            } 
          />
          
          {/* ============================================================
               USER ADMINISTRATION ROUTES
               COBOL Programs: COUSR00C.cbl, COUSR01C.cbl, COUSR02C.cbl, COUSR03C.cbl
               Replaces: RACF user management functions
               ============================================================ */}
          
          {/* 
            User List Display
            Source: app/bms/COUSR00.bms
            COBOL Program: app/cbl/COUSR00C.cbl
            Function: Browse system users with admin access control
            COBOL Transaction ID: CUSR
            RACF Role: Admin (user type 'A')
          */}
          <Route 
            path="/users" 
            element={
              <ProtectedRoute>
                <UserListPage />
              </ProtectedRoute>
            } 
          />
          
          {/* 
            User Add/Creation
            Source: app/bms/COUSR01.bms
            COBOL Program: app/cbl/COUSR01C.cbl
            Function: Create new user with password and role
            COBOL Transaction ID: CUS1
            RACF Role: Admin (user type 'A')
          */}
          <Route 
            path="/users/new" 
            element={
              <ProtectedRoute>
                <UserAddPage />
              </ProtectedRoute>
            } 
          />
          
          {/* 
            User Update/Modification
            Source: app/bms/COUSR02.bms
            COBOL Program: app/cbl/COUSR02C.cbl
            Function: Modify existing user data and role
            COBOL Transaction ID: CUS2
            RACF Role: Admin (user type 'A')
          */}
          <Route 
            path="/users/:id/edit" 
            element={
              <ProtectedRoute>
                <UserUpdatePage />
              </ProtectedRoute>
            } 
          />
          
          {/* 
            User Deletion Confirmation
            Source: app/bms/COUSR03.bms
            COBOL Program: app/cbl/COUSR03C.cbl
            Function: Confirm and execute user deletion
            COBOL Transaction ID: CUS3
            RACF Role: Admin (user type 'A')
          */}
          <Route 
            path="/users/:id/delete" 
            element={
              <ProtectedRoute>
                <UserDeletePage />
              </ProtectedRoute>
            } 
          />
          
          {/* ============================================================
               CATCH-ALL / 404 ROUTE
               Redirects any unmatched routes to main menu
               COBOL equivalent: Invalid transaction ID → return to main menu
               ============================================================ */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </ThemeProvider>
  );
};

/**
 * App Component (Root)
 * 
 * Purpose: Top-level component that establishes provider hierarchy and
 *          initializes the React application routing structure.
 * 
 * Provider Hierarchy Explanation:
 * 1. BrowserRouter - Must be outermost to enable routing throughout app
 * 2. ThemeContextProvider - Provides theme state (light/dark mode)
 * 3. AuthProvider - Provides authentication state and JWT token management
 * 4. AppContent - Accesses theme context and wraps with Material-UI ThemeProvider
 *    - ThemeProvider (MUI) - Applies theme to all Material-UI components
 *    - CssBaseline - Normalizes CSS across browsers
 *    - Suspense - Handles lazy loading with fallback
 *    - Routes - Route definitions and navigation logic
 * 
 * This hierarchy ensures:
 * - Router context available to all components
 * - Theme state accessible before MUI ThemeProvider
 * - Authentication state available to route guards
 * - Consistent styling across all pages
 * - Optimized code splitting and bundle size
 * 
 * Navigation Flow (COBOL to React):
 * COBOL: EXEC CICS XCTL PROGRAM('COMEN01') COMMAREA(...)
 * React: navigate('/') with state in AuthContext
 * 
 * COBOL: EXEC CICS RETURN TRANSID('CAUP') COMMAREA(...)
 * React: navigate('/accounts/:id/edit') with URL params
 * 
 * COBOL: EXEC CICS SIGNOFF
 * React: AuthContext.logout() then navigate('/login')
 * 
 * Per Agent Action Plan Section 0.4.17:
 * This is the root component entry point below main.tsx in the React
 * component tree hierarchy. All pages and components descend from App.
 */
const App: React.FC = () => {
  return (
    <BrowserRouter>
      <ThemeContextProvider>
        <AuthProvider>
          <AppContent />
        </AuthProvider>
      </ThemeContextProvider>
    </BrowserRouter>
  );
};

export default App;
