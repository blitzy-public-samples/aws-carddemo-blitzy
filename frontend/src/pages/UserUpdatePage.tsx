/**
 * UserUpdatePage Component
 * 
 * User update/modification page component for CardDemo application.
 * Converted from COUSR02.bms (BMS 3270 terminal screen) and COUSR02C.cbl
 * (COBOL user update program) to modern React TypeScript SPA interface.
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 * 
 * Original COBOL Program: app/cbl/COUSR02C.cbl
 * Original BMS Map: app/bms/COUSR02.bms
 * Original Copybook: app/cpy/CSUSR01Y.cpy (SEC-USER-DATA structure)
 * 
 * Conversion Notes:
 * - Replaces COBOL EXEC CICS READ FILE('USRSEC') with REST GET /api/users/:id
 * - Replaces COBOL EXEC CICS REWRITE FILE('USRSEC') with REST PUT /api/users/:id
 * - Converts BMS 24x80 terminal screen to responsive React layout
 * - Uses Material-UI components instead of 3270 screen fields
 * - Implements client-side validation matching BMS field attributes
 * - Maintains identical field validation rules from COBOL program
 * - Preserves user update logic with optional password change capability
 * 
 * COBOL to React Transformation Summary:
 * 
 * COBOL BMS Map Fields (COUSR02.bms):
 * - USRIDIN (line 85-89): User ID field, 8 chars, UNPROT → TextField disabled in update mode
 * - FNAME (line 103-107): First Name field, 20 chars, UNPROT, IC → TextField with autoFocus
 * - LNAME (line 116-120): Last Name field, 20 chars, UNPROT → TextField
 * - PASSWD (line 130-134): Password field, 8 chars, DRK, UNPROT → TextField type="password" (optional)
 * - USRTYPE (line 145-149): User Type field, 1 char, UNPROT → Select dropdown (A/U/O)
 * - ERRMSG (line 155-158): Error message area, RED, BRT → ErrorMessage component
 * 
 * COBOL Copybook Structure (CSUSR01Y.cpy):
 * - SEC-USR-ID (PIC X(08)) → userId: string
 * - SEC-USR-FNAME (PIC X(20)) → userFirstName: string
 * - SEC-USR-LNAME (PIC X(20)) → userLastName: string
 * - SEC-USR-PWD (PIC X(08)) → password: string (optional in update, BCrypt hashed by backend)
 * - SEC-USR-TYPE (PIC X(01)) → userType: string (A=Admin, U=User, O=Operator)
 * 
 * COBOL Processing Flow (COUSR02C.cbl):
 * 1. EXEC CICS RECEIVE MAP('COUSR2A') MAPSET('COUSR02') - Receive input
 * 2. Validate user ID entered
 * 3. EXEC CICS READ FILE('USRSEC') RIDFLD(SEC-USR-ID) INTO(SEC-USER-DATA) UPDATE
 * 4. If PF3 pressed (Save & Exit) or PF5 pressed (Save):
 *    - Validate all input fields (names, password if provided, user type)
 *    - Move updated fields to SEC-USER-DATA copybook
 *    - EXEC CICS REWRITE FILE('USRSEC') FROM(SEC-USER-DATA)
 *    - If RESP = DFHRESP(NORMAL): Display success message
 *    - If RESP = DFHRESP(NOTFND): Display "User not found" error (file-status 23)
 * 5. If PF12 pressed (Cancel): Return to user list screen (EXEC CICS RETURN TRANSID('CUSR'))
 * 6. EXEC CICS SEND MAP('COUSR2A') MAPSET('COUSR02') - Display updated screen
 * 
 * React Implementation Flow:
 * 1. useEffect on mount: Call getUserById() to fetch existing user data
 * 2. Populate UserForm initialValues with loaded user data
 * 3. User modifies form fields (first name, last name, password optional, user type)
 * 4. On Submit: Call updateUser() API with changed fields
 * 5. On Success: Show success message, navigate to /users list page
 * 6. On Cancel: Navigate back to /users list page without saving
 * 7. Handle loading states and error messages throughout
 * 
 * Key Features:
 * - Load existing user data on page mount via getUserById API
 * - User ID field displayed but disabled (primary key cannot change)
 * - Optional password change (leave blank to keep existing password per requirements)
 * - Name fields validation (20 chars max each per COBOL PIC X(20))
 * - User type dropdown with role options (Admin/User/Operator)
 * - Admin role-based access control (only admins can update users)
 * - Comprehensive error handling for API failures
 * - Loading indicators during fetch and submit operations
 * - Success message display after successful update
 * - Navigate back to user list after save or cancel
 * 
 * Security Features:
 * - JWT token automatically included via api.ts interceptor
 * - Admin role (ROLE_ADMIN) required enforced by backend
 * - Password optional - only updated if provided (BCrypt hashed by backend)
 * - Password never displayed (even in update mode, field is always empty)
 * - useAuth hook verifies user is authenticated admin before allowing updates
 * 
 * Error Handling:
 * - 401 Unauthorized: Redirect to login page
 * - 403 Forbidden: Show "Admin access required" message
 * - 404 Not Found: Show "User not found" message (COBOL file-status 23 equivalent)
 * - 400 Bad Request: Show validation error messages
 * - 500 Internal Server Error: Show generic error message
 * 
 * Usage:
 * - Accessed via route: /users/:userId/edit
 * - Requires authentication with admin role
 * - Returns to /users list on save or cancel
 * 
 * @module pages/UserUpdatePage
 * @see frontend/src/components/forms/UserForm.tsx - Reusable form component
 * @see frontend/src/services/userService.ts - API service functions
 * @see frontend/src/types/user.ts - User type definitions
 * @see app/cbl/COUSR02C.cbl - Original COBOL program
 * @see app/bms/COUSR02.bms - Original BMS map
 * @see app/cpy/CSUSR01Y.cpy - Original COBOL copybook
 */

import React, { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Box, Container, Typography, Paper, Alert } from '@mui/material';
import { getUserById, updateUser, UpdateUserRequest } from '../services/userService';
import UserForm, { UserFormData } from '../components/forms/UserForm';
import Header from '../components/common/Header';
import Footer from '../components/common/Footer';
import ErrorMessage from '../components/common/ErrorMessage';
import LoadingSpinner from '../components/common/LoadingSpinner';
import { User } from '../types/user';
import { useAuth } from '../hooks/useAuth';

/**
 * UserUpdatePage Component
 * 
 * Page component for updating existing user records with comprehensive
 * validation and error handling matching COBOL COUSR02C.cbl behavior.
 * 
 * Component State Management:
 * - user: User | null - Loaded user data from getUserById API
 * - loading: boolean - Loading flag for initial data fetch
 * - error: string | null - Error message for display
 * - success: boolean - Success flag to show confirmation message
 * 
 * Note: Form submission loading state is managed by UserForm component via Formik's isSubmitting
 * 
 * URL Parameters:
 * - userId: string - User ID from route path /users/:userId/edit
 * 
 * Component Lifecycle:
 * 1. Mount: Extract userId from URL params via useParams()
 * 2. Mount: Check authentication and admin role via useAuth()
 * 3. Mount: Fetch user data via getUserById(userId) in useEffect
 * 4. Render: Display loading spinner during data fetch
 * 5. Render: Display UserForm with loaded data as initialValues
 * 6. Submit: Call updateUser with modified fields
 * 7. Success: Show success message, navigate to /users after 2 seconds
 * 8. Cancel: Navigate to /users immediately
 * 
 * COBOL COUSR02C.cbl Paragraph Equivalents:
 * - Main-Process → UserUpdatePage functional component
 * - Get-User-Data → useEffect with getUserById call
 * - Process-User-Update → handleSubmit function
 * - Update-User-Record → updateUser API call
 * - Display-Success-Message → success state with Alert component
 * - Display-Error-Message → error state with ErrorMessage component
 * - Return-To-List → navigate('/users')
 * 
 * @returns JSX.Element - Rendered user update page with form
 */
const UserUpdatePage: React.FC = () => {
  // Extract userId from URL parameters (route: /users/:userId/edit)
  // Replaces COBOL CDEMO-USER-ID from COMMAREA
  const { userId } = useParams<{ userId: string }>();
  
  // React Router navigation hook for programmatic routing
  // Replaces COBOL EXEC CICS RETURN TRANSID('CUSR')
  const navigate = useNavigate();
  
  // Authentication context for role-based access control
  // Replaces COBOL RACF security checks and SEC-USR-TYPE validation
  const { user: currentUser, isAuthenticated } = useAuth();
  
  // State: Loaded user data from database
  // Equivalent to COBOL SEC-USER-DATA copybook area in working storage
  const [user, setUser] = useState<User | null>(null);
  
  // State: Initial data loading flag (getUserById in progress)
  // Equivalent to COBOL "WAIT" status during EXEC CICS READ operation
  const [loading, setLoading] = useState<boolean>(true);
  
  // State: Error message for display
  // Equivalent to COBOL WS-ERROR-MSG (PIC X(78)) displayed in ERRMSG field
  const [error, setError] = useState<string | null>(null);
  
  // State: Success confirmation flag
  // Equivalent to COBOL WS-SUCCESS-FLAG used to display confirmation message
  const [success, setSuccess] = useState<boolean>(false);

  /**
   * Load user data on component mount
   * 
   * Effect hook that fetches existing user data from backend when component
   * mounts or userId changes. Handles loading states and error conditions.
   * 
   * COBOL Equivalent (COUSR02C.cbl):
   * ```cobol
   * MAIN-PROCESS.
   *     MOVE CDEMO-USER-ID TO SEC-USR-ID.
   *     EXEC CICS READ FILE('USRSEC')
   *          RIDFLD(SEC-USR-ID)
   *          INTO(SEC-USER-DATA)
   *          UPDATE
   *          RESP(WS-RESP)
   *     END-EXEC.
   *     
   *     IF WS-RESP = DFHRESP(NORMAL)
   *        MOVE SEC-USER-DATA TO SCREEN-FIELDS
   *        PERFORM DISPLAY-UPDATE-SCREEN
   *     ELSE
   *        IF WS-RESP = DFHRESP(NOTFND)
   *           MOVE 'User not found' TO WS-ERROR-MSG
   *           PERFORM DISPLAY-ERROR-MESSAGE
   *        END-IF
   *     END-IF.
   * ```
   * 
   * Error Handling:
   * - 404 Not Found: User ID does not exist (COBOL file-status 23)
   * - 401 Unauthorized: JWT token invalid, redirect to login
   * - 403 Forbidden: User lacks admin role, show access denied
   * - 500 Server Error: Backend error, show generic error message
   * 
   * Dependencies: [userId] - Re-fetch if userId URL parameter changes
   */
  useEffect(() => {
    // Async function to fetch user data
    const loadUser = async () => {
      // Validate userId parameter exists
      if (!userId) {
        setError('User ID is required');
        setLoading(false);
        return;
      }

      try {
        // Set loading state (equivalent to COBOL wait cursor)
        setLoading(true);
        setError(null);

        // Call getUserById API (replaces EXEC CICS READ FILE('USRSEC'))
        // Throws error if user not found (404) or other API errors
        const userData = await getUserById(userId);
        
        // Store user data in state (equivalent to moving to COBOL working storage)
        setUser(userData);
      } catch (err: any) {
        // Handle API errors matching COBOL RESP codes
        if (err.response?.status === 404) {
          // User not found (COBOL file-status 23: NOTFND)
          setError(`User '${userId}' not found`);
        } else if (err.response?.status === 403) {
          // Forbidden - insufficient permissions
          setError('You do not have permission to update users. Admin access required.');
        } else if (err.response?.status === 401) {
          // Unauthorized - redirect to login
          navigate('/login');
          return;
        } else {
          // Generic error (COBOL RESP != NORMAL)
          setError(err.message || 'Failed to load user data. Please try again.');
        }
      } finally {
        // Clear loading state regardless of success or failure
        setLoading(false);
      }
    };

    // Execute async data fetch
    loadUser();
  }, [userId, navigate]);

  /**
   * Handle form submission for user update
   * 
   * Callback function passed to UserForm component. Called when user clicks
   * Save button and form passes validation. Sends PUT request to update user.
   * 
   * COBOL Equivalent (COUSR02C.cbl):
   * ```cobol
   * PROCESS-USER-UPDATE.
   *     * Validate all fields
   *     PERFORM VALIDATE-FIRST-NAME.
   *     PERFORM VALIDATE-LAST-NAME.
   *     IF PASSWD-FIELD NOT = SPACES
   *        PERFORM VALIDATE-PASSWORD
   *     END-IF.
   *     PERFORM VALIDATE-USER-TYPE.
   *     
   *     IF WS-VALID-FLAG = 'Y'
   *        * Move screen fields to copybook
   *        MOVE FNAME-INPUT TO SEC-USR-FNAME
   *        MOVE LNAME-INPUT TO SEC-USR-LNAME
   *        IF PASSWD-INPUT NOT = SPACES
   *           MOVE PASSWD-INPUT TO SEC-USR-PWD
   *        END-IF
   *        MOVE USRTYPE-INPUT TO SEC-USR-TYPE
   *        
   *        * Update VSAM file
   *        EXEC CICS REWRITE FILE('USRSEC')
   *             FROM(SEC-USER-DATA)
   *             RESP(WS-RESP)
   *        END-EXEC
   *        
   *        IF WS-RESP = DFHRESP(NORMAL)
   *           MOVE 'User updated successfully' TO WS-SUCCESS-MSG
   *           PERFORM DISPLAY-SUCCESS-MESSAGE
   *           PERFORM RETURN-TO-USER-LIST
   *        ELSE
   *           MOVE 'Failed to update user' TO WS-ERROR-MSG
   *           PERFORM DISPLAY-ERROR-MESSAGE
   *        END-IF
   *     END-IF.
   * ```
   * 
   * Validation:
   * - Client-side validation via Yup schema in UserForm
   * - Backend validation for business rules
   * - Password optional - only updated if provided (not blank)
   * 
   * @param values - Form data with updated user fields
   * @returns Promise<void> - Resolves when update completes or fails
   */
  const handleSubmit = async (values: UserFormData): Promise<void> => {
    try {
      // Clear error and success state before submission
      setError(null);
      setSuccess(false);

      // Build update request payload with only changed fields
      // Equivalent to moving screen fields to COBOL SEC-USER-DATA copybook
      const updateData: UpdateUserRequest = {
        userFirstName: values.userFirstName,
        userLastName: values.userLastName,
        userType: values.userType,
      };

      // Include password only if provided (not empty string)
      // Matches COBOL logic: IF PASSWD-INPUT NOT = SPACES
      if (values.password && values.password.trim().length > 0) {
        updateData.password = values.password;
      }

      // Call updateUser API (replaces EXEC CICS REWRITE FILE('USRSEC'))
      // Backend will validate data and update database record
      // Backend will BCrypt hash password if provided
      await updateUser(userId!, updateData);
      
      // Set success flag to display confirmation message
      // Equivalent to MOVE 'Y' TO WS-SUCCESS-FLAG
      setSuccess(true);
      setError(null);

      // Navigate back to user list after brief delay to show success message
      // Replaces COBOL EXEC CICS RETURN TRANSID('CUSR')
      setTimeout(() => {
        navigate('/users');
      }, 2000);
    } catch (err: any) {
      // Handle API errors matching COBOL RESP codes
      if (err.response?.status === 404) {
        // User not found during update (race condition, deleted by another user)
        setError(`User '${userId}' not found. It may have been deleted.`);
      } else if (err.response?.status === 400) {
        // Validation error from backend
        const validationErrors = err.response?.data?.errors;
        if (validationErrors && Array.isArray(validationErrors)) {
          setError(`Validation errors: ${validationErrors.join(', ')}`);
        } else {
          setError(err.response?.data?.message || 'Invalid user data. Please check your inputs.');
        }
      } else if (err.response?.status === 403) {
        // Forbidden - insufficient permissions
        setError('You do not have permission to update users. Admin access required.');
      } else if (err.response?.status === 401) {
        // Unauthorized - session expired, redirect to login
        navigate('/login');
        return;
      } else {
        // Generic error (COBOL RESP != NORMAL)
        setError(err.message || 'Failed to update user. Please try again.');
      }
      
      // Clear success flag on error
      setSuccess(false);
    }
  };

  /**
   * Handle form cancellation
   * 
   * Callback function passed to UserForm component. Called when user clicks
   * Cancel button. Navigates back to user list without saving changes.
   * 
   * COBOL Equivalent (COUSR02C.cbl):
   * ```cobol
   * PROCESS-PF12-CANCEL.
   *     * User pressed PF12 (Cancel) - return to user list
   *     EXEC CICS RETURN TRANSID('CUSR') END-EXEC.
   * ```
   * 
   * No data is saved. User changes are discarded.
   * Equivalent to pressing F12 (Cancel) on COBOL BMS screen.
   */
  const handleCancel = (): void => {
    // Navigate back to user list page without saving
    // Replaces COBOL EXEC CICS RETURN TRANSID('CUSR')
    navigate('/users');
  };

  /**
   * Check authentication and admin role
   * 
   * Verify user is authenticated and has admin role before allowing access
   * to user update functionality. Matches COBOL RACF security checks.
   * 
   * COBOL Equivalent (COUSR02C.cbl):
   * ```cobol
   * SECURITY-CHECK.
   *     EXEC CICS ASSIGN USERID(WS-USERID) END-EXEC.
   *     
   *     * Read user security record
   *     MOVE WS-USERID TO SEC-USR-ID.
   *     EXEC CICS READ FILE('USRSEC')
   *          RIDFLD(SEC-USR-ID)
   *          INTO(SEC-USER-DATA)
   *     END-EXEC.
   *     
   *     * Check if user is admin
   *     IF SEC-USR-TYPE NOT = 'A'
   *        MOVE 'Admin access required' TO WS-ERROR-MSG
   *        PERFORM DISPLAY-ERROR-MESSAGE
   *        PERFORM RETURN-TO-MAIN-MENU
   *     END-IF.
   * ```
   */
  if (!isAuthenticated) {
    // User not authenticated - redirect to login page
    // Replaces COBOL EXEC CICS SIGNOFF
    navigate('/login');
    return null;
  }

  if (currentUser?.userType !== 'A') {
    // User is not admin - show access denied message
    // Replaces COBOL IF SEC-USR-TYPE NOT = 'A'
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
        <Header />
        <Container component="main" sx={{ mt: 4, mb: 4, flex: 1 }}>
          <Paper elevation={3} sx={{ p: 4 }}>
            <Typography variant="h4" component="h1" gutterBottom color="error">
              Access Denied
            </Typography>
            <Typography variant="body1" paragraph>
              You do not have permission to update users. This function requires administrator access.
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Current user role: {currentUser?.userType === 'U' ? 'User' : currentUser?.userType === 'O' ? 'Operator' : 'Unknown'}
            </Typography>
          </Paper>
        </Container>
        <Footer />
      </Box>
    );
  }

  /**
   * Render Component JSX
   * 
   * Renders complete user update page with Header, UserForm, Footer,
   * loading indicators, error messages, and success confirmations.
   * 
   * Layout Structure:
   * - Header: Application header with navigation and user info
   * - Main Content: UserForm wrapped in Container and Paper
   * - Footer: Application footer with copyright info
   * 
   * Conditional Rendering:
   * - loading === true: Show LoadingSpinner
   * - error !== null: Show ErrorMessage component
   * - success === true: Show success Alert
   * - user !== null: Show UserForm with loaded data
   * 
   * BMS Screen Layout (COUSR02.bms) to React Layout Mapping:
   * - Line 1: Tran + Title + Date → Header component
   * - Line 2: Prog + Title + Time → Header component
   * - Line 4: "Update User" heading → Typography variant="h4"
   * - Lines 6-15: User input fields → UserForm component
   * - Line 23: Error message area → ErrorMessage or Alert component
   * - Line 24: Function key legend → UserForm buttons
   */
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      {/* Application Header */}
      <Header />

      {/* Main Content Container */}
      <Container component="main" sx={{ mt: 4, mb: 4, flex: 1 }}>
        {/* Page Title */}
        <Typography
          variant="h4"
          component="h1"
          gutterBottom
          sx={{ mb: 3, fontWeight: 'bold', color: 'primary.main' }}
        >
          Update User
        </Typography>

        {/* Loading Indicator during initial data fetch */}
        {loading && (
          <Box sx={{ display: 'flex', justifyContent: 'center', py: 8 }}>
            <LoadingSpinner message="Loading user data..." size={60} />
          </Box>
        )}

        {/* Error Message Display */}
        {!loading && error && (
          <Box sx={{ mb: 3 }}>
            <ErrorMessage message={error} severity="error" />
          </Box>
        )}

        {/* Success Message Display */}
        {success && (
          <Box sx={{ mb: 3 }}>
            <Alert severity="success">
              User updated successfully! Returning to user list...
            </Alert>
          </Box>
        )}

        {/* User Update Form - Only show when data is loaded and no errors */}
        {!loading && !error && user && (
          <Paper elevation={3} sx={{ p: 4 }}>
            <UserForm
              mode="update"
              initialValues={{
                userId: user.userId,
                userFirstName: user.userFirstName,
                userLastName: user.userLastName,
                userType: user.userType,
                password: '', // Password always empty (optional change)
              }}
              onSubmit={handleSubmit}
              onCancel={handleCancel}
            />
            
            {/* Helper Text for Password Field */}
            <Box sx={{ mt: 2, p: 2, bgcolor: 'info.light', borderRadius: 1 }}>
              <Typography variant="body2" color="info.contrastText">
                <strong>Note:</strong> Leave the password field empty to keep the existing password. 
                Only enter a new password if you want to change it.
              </Typography>
            </Box>
          </Paper>
        )}
      </Container>

      {/* Application Footer */}
      <Footer />
    </Box>
  );
};

// Export UserUpdatePage as default export per schema requirements
export default UserUpdatePage;
