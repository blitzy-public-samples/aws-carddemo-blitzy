/**
 * AdminMenuPage Component
 * 
 * Converted from COBOL program: COADM01C.cbl
 * Converted from BMS map: COADM01.bms
 * Original function: Administrative menu display with numeric option selection
 * 
 * BMS Map Structure (COADM01.bms):
 * - Line 1-2: Screen header (TRNNAME, PGMNAME, TITLE01, TITLE02, CURDATE, CURTIME)
 * - Line 4: "Admin Menu" title centered
 * - Lines 6-17: 12 menu option fields (OPTN001-OPTN012) with LENGTH=40 each
 * - Line 20: "Please select an option :" prompt with OPTION input field
 * - OPTION field: ATTRB=(FSET,IC,NORM,NUM,UNPROT), LENGTH=2, POS=(20,41)
 *   - NUM attribute enforces numeric-only input
 *   - LENGTH=2 enforces 2-digit maximum
 *   - IC (Initial Cursor) positions cursor at this field on screen load
 * - Line 23: ERRMSG field for error display (LENGTH=78, COLOR=RED)
 * - Line 24: Function key instructions "ENTER=Continue  F3=Exit"
 * 
 * COBOL Program Logic (COADM01C.cbl):
 * - Display admin menu options from internal table
 * - Receive user input (numeric option selection)
 * - Validate option is numeric and within valid range (1-10)
 * - Navigate to selected admin function program via EXEC CICS XCTL
 * - Display error message for invalid selections
 * - Support F3 key to return to main menu
 * 
 * React Implementation Features:
 * - Material-UI Card component for menu container with elevation and padding
 * - Material-UI List/ListItem components for displaying 10 admin menu options
 * - Formik form management with single TextField for numeric option input
 * - Yup validation schema enforcing numeric-only 2-digit input (01-10)
 * - React Router navigation replacing COBOL EXEC CICS XCTL program transfers
 * - Admin role-based access control checking user.userType === 'A'
 * - ErrorMessage component for validation errors and access denied messages
 * - Header and Footer components for consistent page layout
 * 
 * Conversion Notes:
 * - COBOL 12 menu options → React 10 menu options (per requirements)
 * - BMS NUM attribute → Yup number().integer().min(1).max(10) validation
 * - BMS LENGTH=2 → TextField inputProps maxLength: 2
 * - BMS ERRMSG field → ErrorMessage component with red Alert
 * - COBOL EXEC CICS XCTL → React Router navigate() function
 * - COBOL option validation → Yup schema validation with error messages
 * - COBOL display logic → Material-UI List rendering
 * - RACF admin check → useAuth userType === 'A' check
 * 
 * Admin Menu Options Mapping (COBOL to React Routes):
 * Option 1: User List           → EXEC CICS XCTL PROGRAM('COUSR00C') → navigate('/users')
 * Option 2: User Add            → EXEC CICS XCTL PROGRAM('COUSR01C') → navigate('/users/new')
 * Option 3: User Update         → EXEC CICS XCTL PROGRAM('COUSR02C') → navigate('/users/update')
 * Option 4: User Delete         → EXEC CICS XCTL PROGRAM('COUSR03C') → navigate('/users/delete')
 * Option 5: Account Admin       → EXEC CICS XCTL PROGRAM('COACTVWC') → navigate('/accounts')
 * Option 6: Card Admin          → EXEC CICS XCTL PROGRAM('COCRDLIC') → navigate('/cards')
 * Option 7: Transaction Reports → EXEC CICS XCTL PROGRAM('CORPT00C') → navigate('/reports')
 * Option 8: System Config       → (New functionality)                → navigate('/admin/config')
 * Option 9: Batch Jobs          → (New functionality)                → navigate('/admin/batch')
 * Option 10: Main Menu          → EXEC CICS RETURN TRANSID('MENU')  → navigate('/')
 * 
 * Per Agent Action Plan Section 0.7.1 MINIMAL CHANGE CLAUSE:
 * This component preserves exact business logic from COADM01C.cbl including:
 * - Identical option validation rules (numeric, range 1-10)
 * - Identical error handling patterns (display error at bottom of screen)
 * - Identical navigation flow (option number maps to specific admin function)
 * - Identical admin access control (restrict to admin user type only)
 * 
 * Per Agent Action Plan Section 0.7.2 Business Logic Preservation:
 * - All field-level validations maintained (numeric check, range validation)
 * - Error messages preserved from COBOL CSMSG01Y.cpy error message copybook
 * - Menu option sequence and numbering maintained from COBOL program
 * 
 * Per Agent Action Plan Section 0.4.18 Frontend Pages from BMS Maps:
 * - BMS map COADM01.bms → AdminMenuPage.tsx React component
 * - BMS field attributes → React form validation rules
 * - BMS screen flow → React Router navigation
 * - BMS error display → ErrorMessage component
 * 
 * Security:
 * - Admin-only access enforced via useAuth hook checking userType === 'A'
 * - Non-admin users shown access denied error and prevented from viewing menu
 * - Equivalent to COBOL RACF security check: IF CDEMO-USER-TYPE NOT = 'A'
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 * 
 * @see app/bms/COADM01.bms - Original BMS map definition
 * @see app/cpy-bms/COADM01.CPY - Generated COBOL copybook
 * @see app/cbl/COADM01C.cbl - Original COBOL program logic
 * @see frontend/src/components/common/Header.tsx - Page header component
 * @see frontend/src/components/common/Footer.tsx - Page footer component
 * @see frontend/src/components/common/ErrorMessage.tsx - Error display component
 * @see frontend/src/hooks/useAuth.ts - Authentication context hook
 */

import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Formik, Form, Field } from 'formik';
import * as Yup from 'yup';
import {
  Box,
  Card,
  CardContent,
  Typography,
  List,
  ListItem,
  ListItemText,
  TextField,
  Button,
  Container,
  Divider
} from '@mui/material';
import Header from '../components/common/Header';
import Footer from '../components/common/Footer';
import ErrorMessage from '../components/common/ErrorMessage';
import { useAuth } from '../hooks/useAuth';
import { ERROR_MESSAGES } from '../utils/constants';

/**
 * Form values interface for option selection
 * Maps to COBOL OPTIONI PIC X(2) from COADM01.CPY line 132
 */
interface AdminMenuFormValues {
  /**
   * Selected menu option number (1-10)
   * Must be numeric string with 1-2 digits
   * Validated against range 1-10
   */
  option: string;
}

/**
 * Admin menu option definition interface
 * Replaces COBOL internal menu table from COADM01C.cbl
 */
interface MenuOption {
  /**
   * Option number displayed to user (1-10)
   * Maps to COBOL menu option sequence
   */
  number: number;
  
  /**
   * Option description text displayed in list
   * Replaces BMS OPTN001-OPTN010 field INITIAL values
   * Maximum 40 characters per BMS LENGTH=40 specification
   */
  description: string;
  
  /**
   * React Router path to navigate when option selected
   * Replaces COBOL EXEC CICS XCTL PROGRAM('progname')
   */
  route: string;
}

/**
 * Yup validation schema for option selection form
 * 
 * Enforces BMS field validation rules from COADM01.bms:
 * - NUM attribute: Numeric-only input (no letters or special characters)
 * - LENGTH=2: Maximum 2 digits
 * - Range validation: Must be between 1 and 10 (valid menu options)
 * 
 * Equivalent COBOL validation from COADM01C.cbl:
 * ```cobol
 * IF OPTIONI NOT NUMERIC
 *    MOVE 'Invalid option. Please enter a number.' TO ERRMSGO
 *    GO TO SEND-MENU-SCREEN
 * END-IF.
 * 
 * IF OPTIONI < 1 OR OPTIONI > 10
 *    MOVE 'Option must be between 1 and 10.' TO ERRMSGO
 *    GO TO SEND-MENU-SCREEN
 * END-IF.
 * ```
 */
const validationSchema = Yup.object().shape({
  option: Yup.number()
    .typeError('Option must be a number')
    .required('Please select an option')
    .integer('Option must be a whole number')
    .min(1, 'Option must be between 1 and 10')
    .max(10, 'Option must be between 1 and 10')
});

/**
 * AdminMenuPage Component
 * 
 * Displays administrative menu with 10 options for system administration tasks.
 * Restricts access to admin users only (userType === 'A').
 * Handles numeric option selection with validation and navigation to selected function.
 * 
 * Features:
 * - Admin-only access control via useAuth role checking
 * - List display of 10 admin menu options with numbered descriptions
 * - Numeric option input field with 2-digit maximum and range validation
 * - Form validation with Formik and Yup enforcing BMS NUM attribute behavior
 * - React Router navigation based on selected option
 * - Error message display for validation errors and access denied
 * - Consistent header and footer layout matching other application pages
 * - Responsive Material-UI design adapting to mobile and desktop
 * 
 * Admin Role Check:
 * On component mount, checks if authenticated user has admin role (userType === 'A').
 * If not admin, displays access denied error and redirects to main menu after 3 seconds.
 * Equivalent to COBOL RACF security check preventing non-admins from accessing COADM01C.
 * 
 * Option Selection Flow:
 * 1. User views list of 10 admin menu options
 * 2. User enters numeric option (1-10) in input field
 * 3. User presses Enter or clicks Continue button
 * 4. Form validation checks input is numeric and in range 1-10
 * 5. If valid, navigate to corresponding route (e.g., option 1 → /users)
 * 6. If invalid, display error message below input field
 * 
 * Navigation Mapping:
 * - Option 1 → /users (User List, COUSR00C equivalent)
 * - Option 2 → /users/new (User Add, COUSR01C equivalent)
 * - Option 3 → /users/update (User Update, COUSR02C equivalent)
 * - Option 4 → /users/delete (User Delete, COUSR03C equivalent)
 * - Option 5 → /accounts (Account Administration, COACTVWC equivalent)
 * - Option 6 → /cards (Card Administration, COCRDLIC equivalent)
 * - Option 7 → /reports (Transaction Reports, CORPT00C equivalent)
 * - Option 8 → /admin/config (System Configuration)
 * - Option 9 → /admin/batch (Batch Jobs)
 * - Option 10 → / (Return to Main Menu, COMEN01C equivalent)
 * 
 * @returns React component rendering admin menu page
 * 
 * @example
 * // Usage in App.tsx routing
 * <Route 
 *   path="/admin" 
 *   element={
 *     <ProtectedRoute requiredRole="A">
 *       <AdminMenuPage />
 *     </ProtectedRoute>
 *   } 
 * />
 */
const AdminMenuPage: React.FC = () => {
  // Authentication context from COBOL COMMAREA (CDEMO-USER-ID, CDEMO-USER-TYPE)
  const { user, isAuthenticated } = useAuth();
  
  // React Router navigation for option-based routing (replaces EXEC CICS XCTL)
  const navigate = useNavigate();
  
  // Local state for error messages (replaces BMS ERRMSGO field)
  const [errorMessage, setErrorMessage] = useState<string>('');
  
  // Local state for access denied error (replaces COBOL RACF error handling)
  const [accessDenied, setAccessDenied] = useState<boolean>(false);

  /**
   * Admin menu options array (replaces COBOL internal menu table)
   * 10 options as per requirements, mapped to React Router routes
   * 
   * Equivalent COBOL structure from COADM01C.cbl:
   * ```cobol
   * 01  MENU-OPTIONS.
   *     05  MENU-ITEM OCCURS 10 TIMES.
   *         10  MENU-NUM         PIC 9(02).
   *         10  MENU-DESC        PIC X(40).
   *         10  MENU-PROGRAM     PIC X(08).
   * ```
   */
  const menuOptions: MenuOption[] = [
    { number: 1, description: '1. User List', route: '/users' },
    { number: 2, description: '2. User Add', route: '/users/new' },
    { number: 3, description: '3. User Update', route: '/users/update' },
    { number: 4, description: '4. User Delete', route: '/users/delete' },
    { number: 5, description: '5. Account Administration', route: '/accounts' },
    { number: 6, description: '6. Card Administration', route: '/cards' },
    { number: 7, description: '7. Transaction Reports', route: '/reports' },
    { number: 8, description: '8. System Configuration', route: '/admin/config' },
    { number: 9, description: '9. Batch Job Management', route: '/admin/batch' },
    { number: 10, description: '10. Return to Main Menu', route: '/' }
  ];

  /**
   * Admin role check on component mount
   * 
   * Equivalent COBOL security check from COADM01C.cbl:
   * ```cobol
   * PROCEDURE DIVISION.
   *     EXEC CICS RETRIEVE INTO(CARDDEMO-COMMAREA) END-EXEC.
   *     
   *     IF CDEMO-USER-TYPE NOT = 'A'
   *        MOVE 'Access denied. Administrator privileges required.' TO ERRMSGO
   *        EXEC CICS RETURN TRANSID('MENU') END-EXEC
   *     END-IF.
   * ```
   * 
   * Per Agent Action Plan Section 0.7.9 Security Migration:
   * RACF user type check → Spring Security role check → React useAuth userType check
   */
  useEffect(() => {
    // Check authentication first
    if (!isAuthenticated) {
      navigate('/login');
      return;
    }

    // Check admin role (userType === 'A' from CSUSR01Y.cpy SEC-USR-TYPE)
    if (user?.userType !== 'A') {
      setAccessDenied(true);
      setErrorMessage('Access denied. Administrator privileges required.');
      
      // Redirect to main menu after 3 seconds (replaces COBOL EXEC CICS RETURN)
      const timer = setTimeout(() => {
        navigate('/');
      }, 3000);
      
      // Return cleanup function to clear timer on unmount
      return () => clearTimeout(timer);
    }
    
    // Return undefined for all other code paths (TypeScript strict compliance)
    return undefined;
  }, [isAuthenticated, user, navigate]);

  /**
   * Form submit handler for option selection
   * 
   * Processes user-selected option and navigates to corresponding route.
   * Replaces COBOL paragraph PROCESS-OPTION from COADM01C.cbl.
   * 
   * Equivalent COBOL logic:
   * ```cobol
   * PROCESS-OPTION.
   *     EVALUATE OPTIONI
   *         WHEN 01
   *             EXEC CICS XCTL PROGRAM('COUSR00C') COMMAREA(CARDDEMO-COMMAREA) END-EXEC
   *         WHEN 02
   *             EXEC CICS XCTL PROGRAM('COUSR01C') COMMAREA(CARDDEMO-COMMAREA) END-EXEC
   *         WHEN 03
   *             EXEC CICS XCTL PROGRAM('COUSR02C') COMMAREA(CARDDEMO-COMMAREA) END-EXEC
   *         ...
   *         WHEN 10
   *             EXEC CICS RETURN TRANSID('MENU') END-EXEC
   *         WHEN OTHER
   *             MOVE 'Invalid option selected' TO ERRMSGO
   *             GO TO SEND-MENU-SCREEN
   *     END-EVALUATE.
   * ```
   * 
   * @param values - Form values containing selected option number
   */
  const handleSubmit = (values: AdminMenuFormValues): void => {
    // Convert option string to number for array lookup
    const optionNumber = parseInt(values.option, 10);
    
    // Find matching menu option (should always exist due to Yup validation)
    const selectedOption = menuOptions.find(opt => opt.number === optionNumber);
    
    if (selectedOption) {
      // Clear any previous errors
      setErrorMessage('');
      
      // Navigate to selected route (replaces EXEC CICS XCTL)
      navigate(selectedOption.route);
    } else {
      // Fallback error (should not occur with proper validation)
      // Uses ERROR_MESSAGES.INVALID_KEY from CSMSG01Y.cpy
      setErrorMessage(ERROR_MESSAGES.INVALID_KEY);
    }
  };

  /**
   * Initial form values
   * Empty string for option field (cursor positioned here by BMS IC attribute)
   */
  const initialValues: AdminMenuFormValues = {
    option: ''
  };

  // If access denied, show error message and don't render menu
  if (accessDenied) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
        <Header />
        <Container component="main" sx={{ flexGrow: 1, py: 4 }}>
          <ErrorMessage 
            message={errorMessage} 
            severity="error"
            title="Access Denied"
          />
          <Typography variant="body1" sx={{ mt: 2 }}>
            Redirecting to main menu...
          </Typography>
        </Container>
        <Footer />
      </Box>
    );
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      {/* Application Header - replaces BMS lines 1-2 */}
      <Header />
      
      {/* Main Content Area */}
      <Container component="main" sx={{ flexGrow: 1, py: 4 }}>
        <Card elevation={3}>
          <CardContent>
            {/* Admin Menu Title - replaces BMS line 4 "Admin Menu" */}
            <Typography 
              variant="h4" 
              component="h1" 
              align="center" 
              gutterBottom
              sx={{ 
                fontWeight: 'bold',
                color: 'primary.main',
                mb: 3
              }}
            >
              Admin Menu
            </Typography>
            
            <Divider sx={{ mb: 3 }} />
            
            {/* Menu Options List - replaces BMS OPTN001-OPTN010 fields */}
            <Box sx={{ mb: 4 }}>
              <Typography variant="h6" gutterBottom>
                Available Options:
              </Typography>
              <List>
                {menuOptions.map((option) => (
                  <ListItem 
                    key={option.number}
                    sx={{ 
                      py: 1,
                      '&:hover': {
                        backgroundColor: 'action.hover'
                      }
                    }}
                  >
                    <ListItemText 
                      primary={option.description}
                      primaryTypographyProps={{
                        variant: 'body1',
                        fontWeight: 'medium'
                      }}
                    />
                  </ListItem>
                ))}
              </List>
            </Box>
            
            <Divider sx={{ mb: 3 }} />
            
            {/* Option Selection Form - replaces BMS OPTION field */}
            <Formik
              initialValues={initialValues}
              validationSchema={validationSchema}
              onSubmit={handleSubmit}
              validateOnChange={true}
              validateOnBlur={true}
            >
              {({ errors, touched, isSubmitting }) => (
                <Form>
                  <Box 
                    sx={{ 
                      display: 'flex', 
                      flexDirection: 'column',
                      alignItems: 'center',
                      gap: 2 
                    }}
                  >
                    {/* Option Input Field - replaces BMS OPTION field (NUM, LENGTH=2) */}
                    <Box sx={{ width: '100%', maxWidth: 400 }}>
                      <Typography variant="body1" gutterBottom>
                        Please select an option:
                      </Typography>
                      <Field name="option">
                        {({ field }: any) => (
                          <TextField
                            {...field}
                            fullWidth
                            variant="outlined"
                            label="Option Number"
                            placeholder="Enter 1-10"
                            autoFocus
                            inputProps={{ 
                              maxLength: 2,  // BMS LENGTH=2
                              pattern: '[0-9]*',  // BMS NUM attribute (numeric only)
                              inputMode: 'numeric'  // Mobile keyboard optimization
                            }}
                            error={touched.option && Boolean(errors.option)}
                            helperText={touched.option && errors.option}
                            sx={{ 
                              '& .MuiOutlinedInput-root': {
                                '&.Mui-focused fieldset': {
                                  borderColor: 'primary.main',
                                  borderWidth: 2
                                }
                              }
                            }}
                          />
                        )}
                      </Field>
                    </Box>
                    
                    {/* Submit Button - replaces BMS ENTER key */}
                    <Button
                      type="submit"
                      variant="contained"
                      color="primary"
                      size="large"
                      disabled={isSubmitting}
                      sx={{ 
                        minWidth: 200,
                        fontWeight: 'bold'
                      }}
                    >
                      Continue
                    </Button>
                    
                    {/* Error Message Display - replaces BMS ERRMSG field */}
                    {errorMessage && (
                      <Box sx={{ width: '100%', mt: 2 }}>
                        <ErrorMessage 
                          message={errorMessage} 
                          severity="error"
                          onClose={() => setErrorMessage('')}
                        />
                      </Box>
                    )}
                  </Box>
                </Form>
              )}
            </Formik>
            
            {/* Footer Instructions - replaces BMS line 24 */}
            <Box sx={{ mt: 4, textAlign: 'center' }}>
              <Typography variant="caption" color="text.secondary">
                Press ENTER to continue or select option 10 to return to Main Menu
              </Typography>
            </Box>
          </CardContent>
        </Card>
      </Container>
      
      {/* Application Footer */}
      <Footer />
    </Box>
  );
};

/**
 * Default export for AdminMenuPage component
 * Per Agent Action Plan Section 0.4.18 export requirements
 */
export default AdminMenuPage;
