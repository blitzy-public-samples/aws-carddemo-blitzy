/**
 * UserAddPage Component
 * 
 * User add/creation page component for CardDemo application.
 * Converted from COBOL program COUSR01C.cbl and BMS map COUSR01.bms (24x80 3270 terminal screen).
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 * 
 * Original COBOL Program: COUSR01C.cbl
 * Original BMS Map: COUSR01.bms
 * Original Copybook: CSUSR01Y.cpy (SEC-USER-DATA structure)
 * 
 * Conversion Notes:
 * - BMS 3270 screen replaced with responsive React SPA page
 * - EXEC CICS RECEIVE MAP replaced with UserForm component submission
 * - EXEC CICS WRITE FILE('USRSEC') replaced with REST API POST /api/users
 * - EXEC CICS SEND MAP replaced with React component rendering
 * - EXEC CICS RETURN TRANSID('USR0') replaced with navigate('/users')
 * - COBOL field validation replaced with Formik/Yup validation in UserForm
 * - RACF security checks replaced with Spring Security role-based access control
 * - Plain-text password replaced with BCrypt hashing (backend)
 * 
 * Field Mappings (COBOL to React):
 * - FNAME (PIC X(20), IC, UNPROT) → UserForm userFirstName field with autoFocus
 * - LNAME (PIC X(20), UNPROT) → UserForm userLastName field
 * - USERID (PIC X(08), UNPROT) → UserForm userId field (8 chars alphanumeric)
 * - PASSWD (PIC X(08), DRK, UNPROT) → UserForm password field (type=password, masked)
 * - USRTYPE (PIC X(01), UNPROT) → UserForm userType Select dropdown (A/U/O)
 * - ERRMSG (ASKIP, BRT, COLOR=RED) → ErrorMessage component for API errors
 * 
 * COBOL Business Logic Preserved:
 * - User creation logic from COUSR01C.cbl PROCEDURE DIVISION
 * - Field validation rules from BMS map attributes
 * - Error handling for duplicate user ID (file-status 22 → HTTP 409)
 * - Error handling for validation failures (field-status errors → HTTP 400)
 * - Admin role requirement for user creation (RACF → Spring Security)
 * 
 * Security Requirements:
 * - Only users with ROLE_ADMIN (userType='A') can create users
 * - JWT token automatically attached to API requests
 * - Password transmitted over HTTPS and hashed by backend with BCrypt
 * - Unauthorized users redirected to main menu or unauthorized page
 * 
 * Navigation Flow:
 * - Success: Navigate to /users (user list page) after successful creation
 * - Cancel: Navigate to /users without creating user
 * - Not authenticated: Redirect to /login
 * - Not admin: Redirect to / (main menu)
 * 
 * Error Handling:
 * - 409 Conflict: Duplicate user ID (user already exists)
 * - 400 Bad Request: Validation errors (invalid field values)
 * - 401 Unauthorized: Missing or invalid JWT token
 * - 403 Forbidden: User lacks ROLE_ADMIN permission
 * - 500 Internal Server Error: Backend processing error
 * 
 * @module pages/UserAddPage
 * @see app/cbl/COUSR01C.cbl - Original COBOL program
 * @see app/bms/COUSR01.bms - Original BMS map definition
 * @see app/cpy/CSUSR01Y.cpy - Original COBOL user data structure
 * @see backend/src/main/java/com/carddemo/controller/UserController.java
 * @see backend/src/main/java/com/carddemo/service/UserService.java
 */

import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Box, Container, Paper, Typography } from '@mui/material';
import { createUser } from '../services/userService';
import UserForm, { UserFormData } from '../components/forms/UserForm';
import Header from '../components/common/Header';
import Footer from '../components/common/Footer';
import { useAuth } from '../hooks/useAuth';
import { USER_TYPES } from '../utils/constants';
import ErrorMessage from '../components/common/ErrorMessage';

/**
 * UserAddPage functional component
 * 
 * Implements user creation page with admin role-based access control.
 * Displays form for entering new user details (user ID, password, first name,
 * last name, user type) with comprehensive validation matching COBOL constraints.
 * 
 * Component State:
 * - isSubmitting: Boolean flag indicating form submission in progress
 * - errorMessage: String containing error message to display, null if no error
 * 
 * Component Behavior:
 * 1. Check authentication status on mount via useEffect
 * 2. Verify user has admin role (USER_TYPES.ADMIN = 'A')
 * 3. Redirect non-authenticated users to /login
 * 4. Redirect non-admin users to / (main menu)
 * 5. Display UserForm with all required fields
 * 6. Handle form submission via createUser API call
 * 7. Display error messages for API failures (409, 400, 500)
 * 8. Navigate to /users on successful user creation
 * 9. Navigate to /users on cancel button click
 * 
 * COBOL Flow Equivalent (COUSR01C.cbl):
 * ```cobol
 * MAIN-PARA.
 *     * Check user authorization (RACF)
 *     IF SEC-USR-TYPE NOT = 'A'
 *        MOVE 'Not authorized to add users' TO WS-ERROR-MSG
 *        EXEC CICS RETURN TRANSID('CC00') END-EXEC
 *     END-IF.
 *     
 *     * Display add user screen
 *     EXEC CICS SEND MAP('COUSR1A') MAPSET('COUSR01') 
 *          ERASE CURSOR END-EXEC.
 *     
 *     * Receive user input
 *     EXEC CICS RECEIVE MAP('COUSR1A') MAPSET('COUSR01') END-EXEC.
 *     
 *     * Validate input fields
 *     PERFORM VALIDATE-USER-DATA.
 *     IF WS-ERROR-FLAG = 'Y'
 *        PERFORM DISPLAY-ERROR-MESSAGE
 *        GO TO MAIN-PARA
 *     END-IF.
 *     
 *     * Write new user to USRSEC file
 *     EXEC CICS WRITE FILE('USRSEC') 
 *          FROM(SEC-USER-DATA)
 *          RIDFLD(SEC-USR-ID)
 *          RESP(WS-RESP)
 *     END-EXEC.
 *     
 *     * Check for duplicate key (file-status 22)
 *     IF WS-RESP = DFHRESP(DUPREC)
 *        MOVE 'User ID already exists' TO WS-ERROR-MSG
 *        PERFORM DISPLAY-ERROR-MESSAGE
 *        GO TO MAIN-PARA
 *     END-IF.
 *     
 *     * Success - return to user list
 *     EXEC CICS RETURN TRANSID('USR0') END-EXEC.
 * ```
 * 
 * @returns {React.ReactElement} UserAddPage component
 */
const UserAddPage: React.FC = () => {
  // React Router navigation hook for programmatic navigation
  // Replaces COBOL EXEC CICS RETURN TRANSID commands
  const navigate = useNavigate();
  
  // Authentication context hook providing user session data
  // Replaces COBOL COCOM01Y.cpy CARDDEMO-COMMAREA session management
  const { user, isAuthenticated, isLoading: authLoading } = useAuth();
  
  // Component state for error message display
  // Replaces COBOL WS-ERROR-MSG working storage variable
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  /**
   * Authentication and authorization check
   * 
   * Validates user is authenticated and has admin role before allowing access.
   * Equivalent to RACF security check in COBOL COUSR01C.cbl MAIN-PARA.
   * 
   * COBOL Equivalent:
   * ```cobol
   * MAIN-PARA.
   *     EXEC CICS ASSIGN USERID(CDEMO-USER-ID) END-EXEC.
   *     
   *     IF CDEMO-USER-ID = LOW-VALUES
   *        EXEC CICS RETURN TRANSID('LOGN') END-EXEC
   *     END-IF.
   *     
   *     EXEC CICS READ FILE('USRSEC')
   *          RIDFLD(CDEMO-USER-ID)
   *          INTO(SEC-USER-DATA)
   *     END-EXEC.
   *     
   *     IF SEC-USR-TYPE NOT = 'A'
   *        MOVE 'Not authorized to add users' TO WS-ERROR-MSG
   *        EXEC CICS RETURN TRANSID('CC00') END-EXEC
   *     END-IF.
   * ```
   */
  useEffect(() => {
    // Wait for authentication check to complete
    if (!authLoading) {
      // Not authenticated - redirect to login page
      // Equivalent to EXEC CICS RETURN TRANSID('LOGN')
      if (!isAuthenticated) {
        navigate('/login');
      } 
      // Not admin - redirect to main menu
      // Equivalent to EXEC CICS RETURN TRANSID('CC00')
      else if (user?.userType !== USER_TYPES.ADMIN) {
        navigate('/');
      }
    }
  }, [isAuthenticated, authLoading, user, navigate]);

  /**
   * Handle form submission
   * 
   * Processes user creation form submission by calling backend API.
   * Implements business logic from COBOL COUSR01C.cbl CREATE-USER-RECORD paragraph.
   * 
   * Original COBOL Paragraph (COUSR01C.cbl):
   * ```cobol
   * CREATE-USER-RECORD.
   *     * Populate SEC-USER-DATA structure
   *     MOVE USRIDI TO SEC-USR-ID.
   *     MOVE FNAMEI TO SEC-USR-FNAME.
   *     MOVE LNAMEI TO SEC-USR-LNAME.
   *     MOVE PASSWDI TO SEC-USR-PWD.
   *     MOVE USRTYPEI TO SEC-USR-TYPE.
   *     
   *     * Write new user record to USRSEC file
   *     EXEC CICS WRITE FILE('USRSEC')
   *          FROM(SEC-USER-DATA)
   *          RIDFLD(SEC-USR-ID)
   *          RESP(WS-RESP)
   *          RESP2(WS-RESP2)
   *     END-EXEC.
   *     
   *     * Check for errors
   *     EVALUATE WS-RESP
   *        WHEN DFHRESP(NORMAL)
   *           MOVE 'User created successfully' TO WS-MSG
   *           EXEC CICS RETURN TRANSID('USR0') END-EXEC
   *        WHEN DFHRESP(DUPREC)
   *           MOVE 'User ID already exists. Choose different ID.' TO WS-ERROR-MSG
   *           PERFORM DISPLAY-ERROR-MESSAGE
   *        WHEN OTHER
   *           MOVE 'Failed to create user. Contact support.' TO WS-ERROR-MSG
   *           PERFORM DISPLAY-ERROR-MESSAGE
   *     END-EVALUATE.
   * ```
   * 
   * Error Handling:
   * - HTTP 409: Duplicate user ID (COBOL DUPREC/file-status 22)
   * - HTTP 400: Validation errors (COBOL field validation failures)
   * - Other errors: Generic error message (COBOL ABEND equivalent)
   * 
   * @param formData - Validated user form data from UserForm component
   * @returns Promise<void> - Async function completing on API response
   */
  const handleSubmit = async (formData: UserFormData): Promise<void> => {
    try {
      // Clear any previous error messages
      // Equivalent to MOVE SPACES TO WS-ERROR-MSG
      setErrorMessage(null);

      // Call API to create new user
      // Backend will hash password with BCrypt per security requirements
      // Replaces EXEC CICS WRITE FILE('USRSEC')
      // Note: Form submission state (isSubmitting) is managed internally by UserForm component via Formik
      await createUser({
        userId: formData.userId,
        userFirstName: formData.userFirstName,
        userLastName: formData.userLastName,
        userType: formData.userType,
        password: formData.password,
      });

      // Success: Navigate to user list page
      // Equivalent to EXEC CICS RETURN TRANSID('USR0')
      navigate('/users');
    } catch (error: any) {
      // Error occurred - display error message to user
      // Equivalent to PERFORM DISPLAY-ERROR-MESSAGE paragraph
      // Note: Form will be re-enabled automatically by Formik after async operation completes
      
      // Handle specific error scenarios matching COBOL RESP codes
      if (error.response?.status === 409) {
        // Duplicate user ID - COBOL DUPREC response code (file-status 22)
        // Equivalent to WHEN DFHRESP(DUPREC)
        setErrorMessage(
          'User ID already exists. Please choose a different user ID.'
        );
      } else if (error.response?.status === 400) {
        // Validation errors - COBOL field validation failures
        // Extract specific validation error message from backend response
        const validationError =
          error.response?.data?.message ||
          'Invalid user data. Please check all fields and try again.';
        setErrorMessage(validationError);
      } else if (error.response?.status === 403) {
        // Forbidden - user lacks admin permission
        // This shouldn't normally occur due to front-end authorization check
        setErrorMessage(
          'You do not have permission to create users. Contact your administrator.'
        );
      } else {
        // General error - COBOL ABEND or OTHER response code
        // Equivalent to WHEN OTHER
        setErrorMessage(
          'Failed to create user. Please try again or contact support.'
        );
      }
    }
  };

  /**
   * Handle form cancellation
   * 
   * Navigates back to user list page without creating user.
   * Implements cancel logic from COBOL COUSR01C.cbl F3-KEY paragraph.
   * 
   * Original COBOL Paragraph:
   * ```cobol
   * F3-KEY.
   *     * User pressed F3 (Back) - return to user list
   *     EXEC CICS RETURN TRANSID('USR0') END-EXEC.
   * ```
   * 
   * @returns void
   */
  const handleCancel = (): void => {
    // Navigate back to user list without saving
    // Equivalent to EXEC CICS RETURN TRANSID('USR0')
    navigate('/users');
  };

  /**
   * Handle error message dismissal
   * 
   * Clears error message when user dismisses alert.
   * Equivalent to MOVE SPACES TO WS-ERROR-MSG in COBOL.
   * 
   * @returns void
   */
  const handleErrorDismiss = (): void => {
    setErrorMessage(null);
  };

  /**
   * Render loading indicator while checking authentication
   * 
   * Shows loading message during authentication status check.
   * Prevents premature form display before authorization verification.
   */
  if (authLoading) {
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
          maxWidth="md"
          sx={{
            flex: 1,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            py: 3,
          }}
        >
          <Typography variant="body1" color="text.secondary">
            Loading...
          </Typography>
        </Container>
        <Footer />
      </Box>
    );
  }

  /**
   * Don't render form if user is not authorized
   * 
   * Return null while useEffect redirect is processing.
   * Prevents flash of unauthorized content before redirect completes.
   */
  if (!isAuthenticated || user?.userType !== USER_TYPES.ADMIN) {
    return null;
  }

  /**
   * Main page render
   * 
   * Displays complete user add page with header, form, and footer.
   * Layout structure matches BMS COUSR01.bms 24x80 screen converted to responsive design.
   * 
   * BMS Screen Layout (COUSR01.bms):
   * ```
   * Line  1: Tran:CC01   [Title]                Date:mm/dd/yy
   * Line  2: Prog:COUSR01C [Title]             Time:hh:mm:ss
   * Line  3: 
   * Line  4:                  Add User
   * Line  8: First Name: [____________________] Last Name: [____________________]
   * Line 11: User ID: [________] (8 Char)      Password: [________] (8 Char)
   * Line 14: User Type: [_] (A=Admin, U=User)
   * Line 23: [Error message in red]
   * Line 24: ENTER=Add User  F3=Back  F4=Clear  F12=Exit
   * ```
   * 
   * React Layout:
   * - Header: Application header with branding and user info
   * - Main: Container with page title, error message, and user form in Paper
   * - Footer: Application footer with copyright and links
   */
  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        minHeight: '100vh',
        backgroundColor: 'background.default',
      }}
    >
      {/* Application header with branding and user info */}
      <Header />

      {/* Main content area */}
      <Container
        component="main"
        maxWidth="md"
        sx={{
          flex: 1,
          py: 4,
        }}
      >
        {/* Page title - replaces BMS line 4 "Add User" */}
        <Typography
          variant="h4"
          component="h1"
          gutterBottom
          sx={{
            fontWeight: 500,
            color: 'primary.main',
            mb: 3,
          }}
        >
          Add New User
        </Typography>

        {/* Error message display - replaces BMS line 23 ERRMSG field (RED, BRT) */}
        {errorMessage && (
          <Box sx={{ mb: 2 }}>
            <ErrorMessage
              message={errorMessage}
              severity="error"
              onClose={handleErrorDismiss}
            />
          </Box>
        )}

        {/* User creation form container - replaces BMS lines 8-14 */}
        <Paper
          elevation={3}
          sx={{
            p: 4,
            backgroundColor: 'background.paper',
          }}
        >
          {/* Reusable UserForm component handling all field validation and layout */}
          {/* Replaces BMS fields: FNAME, LNAME, USERID, PASSWD, USRTYPE */}
          {/* Note: UserForm manages submission state internally via Formik */}
          <UserForm
            mode="create"
            onSubmit={handleSubmit}
            onCancel={handleCancel}
          />
        </Paper>

        {/* Help text - replaces BMS line 24 function key instructions */}
        <Box sx={{ mt: 2 }}>
          <Typography variant="body2" color="text.secondary">
            Fill in all required fields and click "Add User" to create a new user account.
            Click "Cancel" to return to the user list without saving.
          </Typography>
        </Box>
      </Container>

      {/* Application footer */}
      <Footer />
    </Box>
  );
};

/**
 * Default export for React Router lazy loading
 * 
 * Enables code splitting and on-demand loading of UserAddPage component.
 * 
 * Usage in router configuration:
 * ```typescript
 * import { lazy } from 'react';
 * const UserAddPage = lazy(() => import('./pages/UserAddPage'));
 * 
 * <Route path="/users/add" element={<UserAddPage />} />
 * ```
 */
export default UserAddPage;
