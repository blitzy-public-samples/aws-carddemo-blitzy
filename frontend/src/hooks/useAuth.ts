/**
 * useAuth Custom Hook - Convenience Re-Export
 * 
 * Purpose: Provides convenient access to authentication context without
 *          directly importing from context directory. Re-exports useAuth
 *          hook from AuthContext.tsx for cleaner imports throughout the
 *          application.
 * 
 * COBOL to React Authentication Mapping:
 * - COBOL COCOM01Y.cpy CARDDEMO-COMMAREA → React Context with localStorage
 * - COBOL CDEMO-USER-ID (PIC X(08)) → user.userId field
 * - COBOL CDEMO-USER-TYPE (PIC X(01)) → user.userType field
 * - COBOL SEC-USR-FNAME (PIC X(20)) → user.userFirstName field
 * - COBOL SEC-USR-LNAME (PIC X(20)) → user.userLastName field
 * - COBOL EXEC CICS ASSIGN USERID → const { user } = useAuth()
 * - COBOL RACF authentication → JWT token-based authentication
 * - COBOL EXEC CICS SIGNOFF → logout() function
 * 
 * Why This File Exists:
 * This file serves as a convenience re-export point for the useAuth hook.
 * The actual implementation is in context/AuthContext.tsx. This pattern
 * allows components to import authentication functionality from the more
 * intuitive '@/hooks/useAuth' path instead of '@/context/AuthContext'.
 * 
 * This is a common React pattern that improves developer experience:
 * - Hooks are typically imported from the hooks directory
 * - Context providers are imported from the context directory
 * - This separation makes code organization more intuitive
 * 
 * Authentication Features Provided:
 * - isAuthenticated: Boolean flag indicating authentication status
 * - user: User object with profile data (userId, firstName, lastName, userType)
 * - token: JWT token string for API authorization
 * - login(userId, password): Function to authenticate user credentials
 * - logout(): Function to clear authentication state
 * - isLoading: Boolean flag for async operation status
 * 
 * COBOL Session Management vs React Context:
 * 
 * COBOL Approach (COMMAREA):
 * ```cobol
 * * Store user info in COMMAREA for program-to-program passing
 * MOVE WS-USER-ID TO CDEMO-USER-ID.
 * MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE.
 * MOVE SEC-USR-FNAME TO CDEMO-CUST-FNAME.
 * EXEC CICS XCTL PROGRAM('COMEN01C') 
 *      COMMAREA(CARDDEMO-COMMAREA)
 * END-EXEC.
 * 
 * * Retrieve user info in target program
 * EXEC CICS RETRIEVE INTO(CARDDEMO-COMMAREA) END-EXEC.
 * IF CDEMO-USER-TYPE = 'A'
 *    PERFORM ADMIN-PROCESSING
 * END-IF.
 * ```
 * 
 * React Approach (useAuth Hook):
 * ```typescript
 * // Access user info anywhere in component tree
 * const { isAuthenticated, user, login, logout } = useAuth();
 * 
 * // Check authentication status
 * if (!isAuthenticated) {
 *   return <Navigate to="/login" />;
 * }
 * 
 * // Access user data from COBOL SEC-USER-DATA
 * const userName = `${user?.userFirstName} ${user?.userLastName}`;
 * 
 * // Role-based access control (COBOL SEC-USR-TYPE)
 * if (user?.userType === 'A') {
 *   return <AdminPanel />;
 * }
 * ```
 * 
 * Usage Examples:
 * 
 * Example 1: Protected Route Component
 * ```typescript
 * import { useAuth } from '@/hooks/useAuth';
 * import { Navigate } from 'react-router-dom';
 * 
 * const ProtectedRoute: React.FC<{ children: ReactNode }> = ({ children }) => {
 *   const { isAuthenticated, isLoading } = useAuth();
 *   
 *   // Show loading spinner while checking authentication
 *   if (isLoading) {
 *     return <LoadingSpinner />;
 *   }
 *   
 *   // Redirect to login if not authenticated
 *   if (!isAuthenticated) {
 *     return <Navigate to="/login" replace />;
 *   }
 *   
 *   return <>{children}</>;
 * };
 * ```
 * 
 * Example 2: Signon Page (replaces COSGN00C.cbl)
 * ```typescript
 * import { useAuth } from '@/hooks/useAuth';
 * import { useNavigate } from 'react-router-dom';
 * 
 * const SignonPage: React.FC = () => {
 *   const { login, isLoading } = useAuth();
 *   const navigate = useNavigate();
 *   const [error, setError] = useState<string>('');
 *   
 *   const handleSubmit = async (values: { userId: string; password: string }) => {
 *     try {
 *       // Login replaces COBOL authentication logic
 *       await login(values.userId, values.password);
 *       
 *       // Navigate to main menu (replaces EXEC CICS RETURN TRANSID('CC00'))
 *       navigate('/');
 *     } catch (err) {
 *       setError('Invalid user ID or password. Please try again.');
 *     }
 *   };
 *   
 *   return (
 *     <form onSubmit={handleSubmit}>
 *       <TextField label="User ID" name="userId" inputProps={{ maxLength: 8 }} />
 *       <TextField label="Password" name="password" type="password" />
 *       {error && <Alert severity="error">{error}</Alert>}
 *       <Button type="submit" disabled={isLoading}>
 *         {isLoading ? 'Signing in...' : 'Sign In'}
 *       </Button>
 *     </form>
 *   );
 * };
 * ```
 * 
 * Example 3: Header with User Info
 * ```typescript
 * import { useAuth } from '@/hooks/useAuth';
 * 
 * const Header: React.FC = () => {
 *   const { isAuthenticated, user, logout } = useAuth();
 *   const navigate = useNavigate();
 *   
 *   const handleLogout = () => {
 *     logout();
 *     navigate('/login');
 *   };
 *   
 *   return (
 *     <AppBar position="static">
 *       <Toolbar>
 *         <Typography variant="h6">CardDemo</Typography>
 *         <Box sx={{ flexGrow: 1 }} />
 *         
 *         {isAuthenticated && user && (
 *           <>
 *             <Typography>
 *               {user.userFirstName} {user.userLastName}
 *               <Chip
 *                 label={user.userType === 'A' ? 'Admin' : 'User'}
 *                 color={user.userType === 'A' ? 'error' : 'primary'}
 *                 size="small"
 *                 sx={{ ml: 1 }}
 *               />
 *             </Typography>
 *             
 *             <IconButton color="inherit" onClick={handleLogout}>
 *               <LogoutIcon />
 *             </IconButton>
 *           </>
 *         )}
 *       </Toolbar>
 *     </AppBar>
 *   );
 * };
 * ```
 * 
 * Example 4: Role-Based Access Control
 * ```typescript
 * import { useAuth } from '@/hooks/useAuth';
 * 
 * const AdminMenuPage: React.FC = () => {
 *   const { user } = useAuth();
 *   
 *   // Check admin role (COBOL CDEMO-USRTYP-ADMIN value 'A')
 *   if (user?.userType !== 'A') {
 *     return (
 *       <Alert severity="error">
 *         Access denied. Administrator privileges required.
 *       </Alert>
 *     );
 *   }
 *   
 *   return (
 *     <Box>
 *       <Typography variant="h5">Administrator Menu</Typography>
 *       <List>
 *         <ListItem button onClick={() => navigate('/admin/users')}>
 *           <ListItemText primary="User Management" />
 *         </ListItem>
 *         <ListItem button onClick={() => navigate('/admin/reports')}>
 *           <ListItemText primary="System Reports" />
 *         </ListItem>
 *       </List>
 *     </Box>
 *   );
 * };
 * ```
 * 
 * Example 5: Transaction Entry with User Context
 * ```typescript
 * import { useAuth } from '@/hooks/useAuth';
 * 
 * const TransactionEntryPage: React.FC = () => {
 *   const { user } = useAuth();
 *   
 *   const handleSubmit = async (values: TransactionFormValues) => {
 *     // Include user ID in transaction (from COBOL session context)
 *     await transactionService.createTransaction({
 *       ...values,
 *       userId: user?.userId, // From COBOL CDEMO-USER-ID
 *       timestamp: new Date(),
 *     });
 *   };
 *   
 *   return (
 *     <Box>
 *       <Typography variant="subtitle2" color="text.secondary">
 *         Entering transaction as: {user?.userId}
 *       </Typography>
 *       <TransactionForm onSubmit={handleSubmit} />
 *     </Box>
 *   );
 * };
 * ```
 * 
 * Security Comparison:
 * 
 * COBOL/RACF Security:
 * - User ID stored in CICS COMMAREA (CDEMO-USER-ID)
 * - Password validated against USRSEC VSAM file
 * - Session managed by CICS transaction server
 * - User type determines access (SEC-USR-TYPE: 'A', 'U')
 * - RACF profiles control resource access
 * 
 * React/JWT Security:
 * - JWT token stored in localStorage (encrypted at rest)
 * - Password validated with BCrypt hashing (backend)
 * - Token contains encrypted claims (userId, userType, expiration)
 * - Token expires after 1 hour (automatic logout)
 * - Spring Security controls API endpoint access
 * - Role-based access control via @PreAuthorize annotations
 * 
 * Error Handling:
 * The useAuth hook will throw an error if used outside the AuthProvider.
 * This is intentional to prevent accidental usage before context initialization.
 * 
 * Error Message:
 * "useAuth must be used within an AuthProvider. Wrap your component tree with <AuthProvider> in App.tsx."
 * 
 * Correct Setup in App.tsx:
 * ```typescript
 * import { AuthProvider } from './context/AuthContext';
 * 
 * function App() {
 *   return (
 *     <AuthProvider>
 *       <Router>
 *         <Routes>
 *           <Route path="/login" element={<SignonPage />} />
 *           <Route path="/" element={<ProtectedRoute><MainMenu /></ProtectedRoute>} />
 *         </Routes>
 *       </Router>
 *     </AuthProvider>
 *   );
 * }
 * ```
 * 
 * Performance Considerations:
 * - Context updates trigger re-renders only for consuming components
 * - Token stored in localStorage persists across page refreshes
 * - User profile fetched once on login, cached in context
 * - No polling or repeated authentication checks
 * - Logout is instant (local state clear)
 * 
 * Testing:
 * ```typescript
 * import { renderHook } from '@testing-library/react';
 * import { AuthProvider } from '@/context/AuthContext';
 * import { useAuth } from '@/hooks/useAuth';
 * 
 * describe('useAuth', () => {
 *   it('should throw error when used outside AuthProvider', () => {
 *     expect(() => {
 *       renderHook(() => useAuth());
 *     }).toThrow('useAuth must be used within an AuthProvider');
 *   });
 *   
 *   it('should provide authentication context when wrapped', () => {
 *     const wrapper = ({ children }) => <AuthProvider>{children}</AuthProvider>;
 *     const { result } = renderHook(() => useAuth(), { wrapper });
 *     
 *     expect(result.current).toHaveProperty('isAuthenticated');
 *     expect(result.current).toHaveProperty('user');
 *     expect(result.current).toHaveProperty('login');
 *     expect(result.current).toHaveProperty('logout');
 *   });
 * });
 * ```
 * 
 * Migration Notes:
 * This hook replaces COBOL session management patterns used throughout
 * the CardDemo application. Every COBOL program that accessed CDEMO-USER-ID
 * or CDEMO-USER-TYPE from COMMAREA now uses this hook to access the same
 * information from React Context.
 * 
 * COBOL Programs Using COMMAREA → React Components Using useAuth:
 * - COSGN00C.cbl (signon) → SignonPage.tsx with useAuth()
 * - COMEN01C.cbl (main menu) → MainMenuPage.tsx with useAuth()
 * - COADM01C.cbl (admin menu) → AdminMenuPage.tsx with useAuth()
 * - COACTUPC.cbl (account update) → AccountUpdatePage.tsx with useAuth()
 * - COTRN02C.cbl (transaction entry) → TransactionEntryPage.tsx with useAuth()
 * - All other CICS programs → Corresponding React pages with useAuth()
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 * 
 * @see frontend/src/context/AuthContext.tsx - Actual useAuth implementation
 * @see app/cpy/COCOM01Y.cpy - Original COBOL COMMAREA structure
 * @see app/cpy/CSUSR01Y.cpy - Original COBOL user security data
 * @see app/cbl/COSGN00C.cbl - Original COBOL signon program
 * @see backend/src/main/java/com/carddemo/security/JwtTokenProvider.java
 */

/**
 * Re-export useAuth hook from AuthContext
 * 
 * This provides a convenient import path for authentication functionality.
 * Components can import from '@/hooks/useAuth' instead of '@/context/AuthContext',
 * which is more intuitive since hooks are typically imported from the hooks directory.
 * 
 * The actual implementation is in context/AuthContext.tsx (lines 702-714).
 * 
 * @returns AuthContextType - Authentication state and functions
 * @throws Error if used outside AuthProvider component tree
 * 
 * @example
 * // Import from hooks directory (preferred)
 * import { useAuth } from '@/hooks/useAuth';
 * 
 * // Instead of importing from context directory
 * // import { useAuth } from '@/context/AuthContext';
 * 
 * function MyComponent() {
 *   const { isAuthenticated, user, login, logout } = useAuth();
 *   // Use authentication functionality
 * }
 */
export { useAuth } from '../context/AuthContext';

/**
 * Note: This is a re-export file for developer convenience.
 * 
 * Benefits of this pattern:
 * 1. More intuitive import paths (hooks from hooks directory)
 * 2. Cleaner separation of concerns (context vs hooks)
 * 3. Consistent with React community conventions
 * 4. Easy to add hook-specific utilities in future
 * 5. Simplifies component imports (shorter paths)
 * 
 * The actual useAuth hook implementation with all business logic,
 * error handling, and state management is located in:
 * frontend/src/context/AuthContext.tsx
 * 
 * This file serves purely as a convenience re-export to improve
 * developer experience and code organization.
 */
