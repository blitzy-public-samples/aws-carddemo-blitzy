/**
 * App Component - Root Application Entry Point
 * 
 * Main React application component for the CardDemo credit card management system.
 * Configures routing using React Router v6 to replicate the original BMS 3270 screen
 * navigation flow from the mainframe CICS application, wraps the application with
 * AuthContext provider for global authentication state management, and defines all
 * application routes matching the original transaction flows.
 * 
 * This component serves as the central orchestrator for:
 * - Client-side routing (replacing PF key navigation from 3270 terminals)
 * - Authentication state management via context
 * - Protected route access control based on user roles
 * - Global error boundary for graceful error handling
 * - Consistent layout with Header, Footer, and Navigation components
 * 
 * Route Mapping (CICS Transaction → React Route):
 * - CC00 (COSGN00M.bms) → / (LoginComponent)
 * - CM00 (COMEN01M.bms) → /menu (MainMenuComponent)
 * - CA00 (COADM01M.bms) → /admin (AdminMenuComponent)
 * - CAVW (COACTVWM.bms) → /account/view (AccountViewComponent)
 * - CAUP (COACTUPM.bms) → /account/update (AccountUpdateComponent)
 * - CCLI (COCRDLIM.bms) → /cards (CardListComponent)
 * - CCDL (COCRDSLM.bms) → /cards/:id (CardDetailComponent)
 * - CCUP (COCRDUPM.bms) → /cards/:id/update (CardUpdateComponent)
 * - CT00 (COTRN00M.bms) → /transactions (TransactionListComponent)
 * - CT01 (COTRN01M.bms) → /transactions/:id (TransactionViewComponent)
 * - CT02 (COTRN02M.bms) → /transactions/add (TransactionAddComponent)
 * - CB00 (COBIL00M.bms) → /billing (BillPaymentComponent)
 * - CR00 (CORPT00M.bms) → /reports (TransactionReportComponent)
 * - CU00 (COUSR00M.bms) → /admin/users (UserListComponent)
 * - CU01 (COUSR01M.bms) → /admin/users/add (UserAddComponent)
 * - CU02 (COUSR02M.bms) → /admin/users/:id/update (UserUpdateComponent)
 * 
 * @component
 * @example
 * // Mounted in index.js
 * import App from './App';
 * ReactDOM.render(<App />, document.getElementById('root'));
 */

import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';

// Context Provider
import { AuthProvider, useAuth } from './context/AuthContext';

// Authentication Components
import LoginComponent from './components/auth/LoginComponent';

// Menu Navigation Components
import MainMenuComponent from './components/menu/MainMenuComponent';
import AdminMenuComponent from './components/menu/AdminMenuComponent';

// Account Management Components
import AccountViewComponent from './components/account/AccountViewComponent';
import AccountUpdateComponent from './components/account/AccountUpdateComponent';

// Card Management Components
import CardListComponent from './components/card/CardListComponent';
import CardDetailComponent from './components/card/CardDetailComponent';
import CardUpdateComponent from './components/card/CardUpdateComponent';

// Transaction Management Components
import TransactionListComponent from './components/transaction/TransactionListComponent';
import TransactionViewComponent from './components/transaction/TransactionViewComponent';
import TransactionAddComponent from './components/transaction/TransactionAddComponent';

// Billing and Reporting Components
import BillPaymentComponent from './components/billing/BillPaymentComponent';
import TransactionReportComponent from './components/report/TransactionReportComponent';

// User Administration Components
import UserListComponent from './components/user/UserListComponent';
import UserAddComponent from './components/user/UserAddComponent';
import UserUpdateComponent from './components/user/UserUpdateComponent';

// Common Layout Components
import Header from './components/common/Header';
import Footer from './components/common/Footer';
import Navigation from './components/common/Navigation';
import ErrorBoundary from './components/common/ErrorBoundary';

/**
 * ProtectedRoute Component
 * 
 * Wrapper component for routes requiring authentication.
 * Checks authentication status from AuthContext and redirects unauthenticated
 * users to the login page. This replaces RACF security checks from the mainframe
 * with Spring Security JWT token validation.
 * 
 * @param {Object} props - Component props
 * @param {React.ReactNode} props.children - Child components to render if authenticated
 * @returns {React.ReactNode} Protected component or redirect to login
 */
const ProtectedRoute = ({ children }) => {
  const { isAuthenticated } = useAuth();
  
  if (!isAuthenticated) {
    // Redirect to login page if not authenticated
    // Equivalent to CICS XCTL to COSGN00C when USRSEC validation fails
    return <Navigate to="/" replace />;
  }
  
  return children;
};

/**
 * AdminRoute Component
 * 
 * Wrapper component for routes requiring administrative privileges.
 * Checks both authentication status and admin role from AuthContext.
 * Redirects non-admin users to main menu. This implements the two-tier
 * role model (Regular User vs Administrative User) from the original RACF security.
 * 
 * @param {Object} props - Component props
 * @param {React.ReactNode} props.children - Child components to render if admin
 * @returns {React.ReactNode} Protected component or redirect to menu
 */
const AdminRoute = ({ children }) => {
  const { isAuthenticated, isAdmin } = useAuth();
  
  if (!isAuthenticated) {
    // Not authenticated - redirect to login
    return <Navigate to="/" replace />;
  }
  
  if (!isAdmin) {
    // Authenticated but not admin - redirect to main menu
    // Equivalent to CICS security check failing for admin-only transactions
    return <Navigate to="/menu" replace />;
  }
  
  return children;
};

/**
 * Layout Component
 * 
 * Wrapper component providing consistent layout structure with Header, Navigation,
 * and Footer components across all authenticated screens. Replicates the consistent
 * BMS screen layout from the mainframe application (header with title/date/time,
 * navigation area, content area, footer with PF key instructions).
 * 
 * @param {Object} props - Component props
 * @param {React.ReactNode} props.children - Page content to render
 * @returns {React.ReactNode} Layout with header, navigation, content, and footer
 */
const Layout = ({ children }) => {
  return (
    <div className="app-layout">
      <Header />
      <Navigation />
      <main className="app-content">
        {children}
      </main>
      <Footer />
    </div>
  );
};

/**
 * App Component
 * 
 * Root application component that orchestrates routing, authentication, and layout.
 * Implements the complete screen navigation flow from the mainframe BMS screens
 * using React Router v6 for modern single-page application routing.
 * 
 * Architecture:
 * 1. ErrorBoundary: Outermost wrapper for global error handling
 * 2. AuthProvider: Provides authentication context to all components
 * 3. BrowserRouter: Enables HTML5 history API routing (pushState/replaceState)
 * 4. Routes: Defines all application routes with protection levels
 * 
 * Route Protection Levels:
 * - Public: Login page (/) - accessible to all
 * - Protected: Most application routes - requires authentication
 * - Admin: User management routes (/admin/*) - requires admin role
 * 
 * @returns {React.ReactNode} Complete application with routing and context
 */
const App = () => {
  return (
    <ErrorBoundary>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            {/* Public Route - Login (CC00 Transaction - COSGN00M.bms) */}
            <Route path="/" element={<LoginComponent />} />
            
            {/* Protected Routes - Require Authentication */}
            
            {/* Main Menu (CM00 Transaction - COMEN01M.bms) */}
            <Route 
              path="/menu" 
              element={
                <ProtectedRoute>
                  <Layout>
                    <MainMenuComponent />
                  </Layout>
                </ProtectedRoute>
              } 
            />
            
            {/* Account Management Routes */}
            {/* Account View (CAVW Transaction - COACTVWM.bms) */}
            <Route 
              path="/account/view" 
              element={
                <ProtectedRoute>
                  <Layout>
                    <AccountViewComponent />
                  </Layout>
                </ProtectedRoute>
              } 
            />
            
            {/* Account Update (CAUP Transaction - COACTUPM.bms) */}
            <Route 
              path="/account/update" 
              element={
                <ProtectedRoute>
                  <Layout>
                    <AccountUpdateComponent />
                  </Layout>
                </ProtectedRoute>
              } 
            />
            
            {/* Card Management Routes */}
            {/* Card List (CCLI Transaction - COCRDLIM.bms) */}
            <Route 
              path="/cards" 
              element={
                <ProtectedRoute>
                  <Layout>
                    <CardListComponent />
                  </Layout>
                </ProtectedRoute>
              } 
            />
            
            {/* Card Detail View (CCDL Transaction - COCRDSLM.bms) */}
            <Route 
              path="/cards/:id" 
              element={
                <ProtectedRoute>
                  <Layout>
                    <CardDetailComponent />
                  </Layout>
                </ProtectedRoute>
              } 
            />
            
            {/* Card Update (CCUP Transaction - COCRDUPM.bms) */}
            <Route 
              path="/cards/:id/update" 
              element={
                <ProtectedRoute>
                  <Layout>
                    <CardUpdateComponent />
                  </Layout>
                </ProtectedRoute>
              } 
            />
            
            {/* Transaction Management Routes */}
            {/* Transaction List (CT00 Transaction - COTRN00M.bms) */}
            <Route 
              path="/transactions" 
              element={
                <ProtectedRoute>
                  <Layout>
                    <TransactionListComponent />
                  </Layout>
                </ProtectedRoute>
              } 
            />
            
            {/* Transaction View (CT01 Transaction - COTRN01M.bms) */}
            <Route 
              path="/transactions/:id" 
              element={
                <ProtectedRoute>
                  <Layout>
                    <TransactionViewComponent />
                  </Layout>
                </ProtectedRoute>
              } 
            />
            
            {/* Transaction Add (CT02 Transaction - COTRN02M.bms) */}
            <Route 
              path="/transactions/add" 
              element={
                <ProtectedRoute>
                  <Layout>
                    <TransactionAddComponent />
                  </Layout>
                </ProtectedRoute>
              } 
            />
            
            {/* Billing and Reporting Routes */}
            {/* Bill Payment (CB00 Transaction - COBIL00M.bms) */}
            <Route 
              path="/billing" 
              element={
                <ProtectedRoute>
                  <Layout>
                    <BillPaymentComponent />
                  </Layout>
                </ProtectedRoute>
              } 
            />
            
            {/* Transaction Reports (CR00 Transaction - CORPT00M.bms) */}
            <Route 
              path="/reports" 
              element={
                <ProtectedRoute>
                  <Layout>
                    <TransactionReportComponent />
                  </Layout>
                </ProtectedRoute>
              } 
            />
            
            {/* Admin Routes - Require Admin Role */}
            {/* Admin Menu (CA00 Transaction - COADM01M.bms) */}
            <Route 
              path="/admin" 
              element={
                <AdminRoute>
                  <Layout>
                    <AdminMenuComponent />
                  </Layout>
                </AdminRoute>
              } 
            />
            
            {/* User List (CU00 Transaction - COUSR00M.bms) */}
            <Route 
              path="/admin/users" 
              element={
                <AdminRoute>
                  <Layout>
                    <UserListComponent />
                  </Layout>
                </AdminRoute>
              } 
            />
            
            {/* User Add (CU01 Transaction - COUSR01M.bms) */}
            <Route 
              path="/admin/users/add" 
              element={
                <AdminRoute>
                  <Layout>
                    <UserAddComponent />
                  </Layout>
                </AdminRoute>
              } 
            />
            
            {/* User Update (CU02 Transaction - COUSR02M.bms) */}
            <Route 
              path="/admin/users/:id/update" 
              element={
                <AdminRoute>
                  <Layout>
                    <UserUpdateComponent />
                  </Layout>
                </AdminRoute>
              } 
            />
            
            {/* Catch-All Route - Redirect to login for unknown paths */}
            <Route 
              path="*" 
              element={<Navigate to="/" replace />} 
            />
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </ErrorBoundary>
  );
};

export default App;
