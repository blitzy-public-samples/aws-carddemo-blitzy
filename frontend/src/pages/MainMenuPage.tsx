/**
 * MainMenuPage Component
 * 
 * Converted from BMS map: COMEN01.bms (3270 terminal main menu screen)
 * Converted from COBOL program: COMEN01C.cbl (main menu navigation logic)
 * 
 * Original BMS Structure (24x80 3270 Terminal Screen):
 * - Line 1-2: Header with transaction name, program name, title, date, time
 * - Line 4: "Main Menu" centered title
 * - Lines 6-17: 12 menu options (OPTN001-OPTN012) displayed as list items
 * - Line 20: "Please select an option :" prompt with 2-digit numeric input field
 * - Line 23: Error message field (ERRMSG) in red for validation errors
 * - Line 24: Function key legend "ENTER=Continue  F3=Exit"
 * 
 * COBOL Menu Logic (COMEN01C.cbl):
 * - EXEC CICS RECEIVE MAP('COMEN1A') - Receive user input
 * - Validate OPTION field: numeric, range 1-12, required
 * - Based on option, EXEC CICS XCTL to target program:
 *   Option 1: XCTL PROGRAM('COACTVWC') - Account View
 *   Option 2: XCTL PROGRAM('COACTUPC') - Account Update
 *   Option 3: XCTL PROGRAM('COCRDLIC') - Card List
 *   Option 4: XCTL PROGRAM('COCRDUPC') - Card Update
 *   Option 5: XCTL PROGRAM('COTRN00C') - Transaction List
 *   Option 6: XCTL PROGRAM('COTRN01C') - Transaction Detail
 *   Option 7: XCTL PROGRAM('COTRN02C') - Transaction Entry
 *   Option 8: XCTL PROGRAM('COBIL00C') - Billing
 *   Option 9: XCTL PROGRAM('CORPT00C') - Reports
 *   Option 10: XCTL PROGRAM('COUSR00C') - User Management (Admin only)
 *   Option 11: XCTL PROGRAM('COADM01C') - Admin Menu (Admin only)
 *   Option 12: EXEC CICS SIGNOFF - Logout
 * 
 * React Implementation:
 * - Material-UI Card component for menu container
 * - List component for 12 menu options with click handlers
 * - Formik form for option input with Yup validation schema
 * - TextField for 2-digit numeric option input (NUM attribute from BMS)
 * - ErrorMessage component for validation errors (replaces ERRMSG field)
 * - React Router navigation based on selected option (replaces EXEC CICS XCTL)
 * - Role-based menu filtering (hide admin options for non-admin users)
 * - Header and Footer components for consistent page layout
 * 
 * Field Validation (from BMS OPTION field attributes):
 * - ATTRB=(FSET,IC,NORM,NUM,UNPROT): Numeric only, 2 characters
 * - JUSTIFY=(RIGHT,ZERO): Right-justified with zero-fill
 * - Validation: Required, numeric, integer, range 1-12
 * 
 * Screen Flow Mapping:
 * COBOL: EXEC CICS SEND MAP → RECEIVE MAP → XCTL PROGRAM
 * React: Display menu → User selects option → navigate() to route
 * 
 * Per Agent Action Plan Section 0.7.2:
 * Maintain identical field validation and screen flow logic from COMEN01C.cbl
 * while transforming 3270 terminal interface to responsive React SPA.
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 * 
 * @see app/bms/COMEN01.bms - Original BMS map definition
 * @see app/cpy-bms/COMEN01.CPY - Original BMS copybook
 * @see app/cbl/COMEN01C.cbl - Original COBOL program logic
 * @see frontend/src/components/common/Header.tsx - Page header
 * @see frontend/src/components/common/Footer.tsx - Page footer
 * @see frontend/src/components/common/ErrorMessage.tsx - Error display
 * @see frontend/src/hooks/useAuth.ts - Authentication context
 * @see frontend/src/utils/constants.ts - User type constants
 */

import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box,
  Card,
  CardContent,
  Typography,
  List,
  ListItem,
  ListItemButton,
  ListItemText,
  TextField,
  Button,
  Container,
  Divider,
  Paper,
} from '@mui/material';
import { Formik, Form, FormikHelpers } from 'formik';
import * as yup from 'yup';
import Header from '../components/common/Header';
import Footer from '../components/common/Footer';
import ErrorMessage from '../components/common/ErrorMessage';
import { useAuth } from '../hooks/useAuth';
import { USER_TYPES } from '../utils/constants';

/**
 * Form values interface for menu option selection
 * Maps to BMS OPTIONI field (PIC X(2))
 */
interface MenuFormValues {
  /**
   * Selected menu option number (1-12)
   * Converted from COBOL OPTIONI field with NUM attribute
   * Must be numeric string, 1-2 characters, range 1-12
   */
  option: string;
}

/**
 * Menu option configuration interface
 * Defines structure for each of the 12 menu items
 */
interface MenuOption {
  /**
   * Option number (1-12) displayed to user
   * Maps to COBOL validation IF OPTIONI >= 1 AND <= 12
   */
  id: number;

  /**
   * Display label for menu item
   * Populated from COBOL program that sets OPTN001O-OPTN012O fields
   * Format: "n. Description" (e.g., "1. Account View")
   */
  label: string;

  /**
   * React Router route path for navigation
   * Replaces COBOL EXEC CICS XCTL PROGRAM('xxxxxxxx')
   */
  route: string;

  /**
   * Optional admin-only flag
   * When true, only shown to users with userType='A' (ADMIN)
   * Replaces COBOL IF CDEMO-USER-TYPE = 'A' logic
   */
  adminOnly?: boolean;
}

/**
 * Yup validation schema for menu option form
 * Replicates BMS NUM attribute and COBOL field validation from COMEN01C.cbl
 * 
 * COBOL Validation Logic:
 * ```cobol
 * IF OPTIONI NOT NUMERIC
 *    MOVE 'Invalid option. Please enter a number.' TO ERRMSGO
 *    GO TO SEND-MAP-DATAONLY
 * END-IF.
 * IF OPTIONI < 1 OR OPTIONI > 12
 *    MOVE 'Option must be between 1 and 12.' TO ERRMSGO
 *    GO TO SEND-MAP-DATAONLY
 * END-IF.
 * ```
 */
const menuValidationSchema = yup.object().shape({
  option: yup
    .number()
    .typeError('Invalid option. Please enter a number.')
    .required('Please select an option.')
    .integer('Option must be a whole number.')
    .min(1, 'Option must be between 1 and 12.')
    .max(12, 'Option must be between 1 and 12.'),
});

/**
 * MainMenuPage Component
 * 
 * Main navigation menu page displaying 12 selectable options for accessing
 * different areas of the CardDemo application. Implements numeric input
 * field for option selection with validation matching COBOL logic.
 * 
 * Features:
 * - 12 menu options mapped to application routes
 * - Click on list item OR enter option number
 * - Numeric validation (1-12 range)
 * - Role-based filtering (hide admin options for non-admin users)
 * - Responsive Material-UI layout
 * - Integration with Header and Footer components
 * - Error message display for validation failures
 * 
 * Authentication:
 * - Redirects to login if not authenticated (handled by route protection)
 * - Accesses user.userType from useAuth() for role-based menu filtering
 * 
 * Navigation Flow:
 * User selects option → Validation → Navigate to target route
 * Replaces: RECEIVE MAP → Validate → XCTL PROGRAM
 * 
 * @returns React component rendering main menu page
 */
const MainMenuPage: React.FC = () => {
  // React Router navigation hook (replaces EXEC CICS XCTL)
  const navigate = useNavigate();

  // Authentication context - retrieve user info from COBOL COCOM01Y.cpy COMMAREA
  const { user, isAuthenticated } = useAuth();

  // Error message state for form validation errors (replaces BMS ERRMSG field)
  const [errorMessage, setErrorMessage] = useState<string>('');

  /**
   * Menu options configuration array
   * Defines all 12 menu items with labels, routes, and access control
   * 
   * Converted from COBOL COMEN01C.cbl option selection logic:
   * - Each option maps to a specific COBOL program via EXEC CICS XCTL
   * - Admin-only options check CDEMO-USER-TYPE = 'A' before allowing access
   * 
   * Menu Structure from COBOL:
   * OPTN001O = '1. View Account Information'
   * OPTN002O = '2. Update Account Information'
   * OPTN003O = '3. List Credit Cards'
   * OPTN004O = '4. Update Credit Card'
   * OPTN005O = '5. View Transactions'
   * OPTN006O = '6. View Transaction Detail'
   * OPTN007O = '7. Enter New Transaction'
   * OPTN008O = '8. View Billing Statement'
   * OPTN009O = '9. Generate Reports'
   * OPTN010O = '10. Manage Users' (Admin only)
   * OPTN011O = '11. Administration Menu' (Admin only)
   * OPTN012O = '12. Sign Off'
   */
  const menuOptions: MenuOption[] = [
    {
      id: 1,
      label: '1. View Account Information',
      route: '/accounts',
    },
    {
      id: 2,
      label: '2. Update Account Information',
      route: '/accounts/update',
    },
    {
      id: 3,
      label: '3. List Credit Cards',
      route: '/cards',
    },
    {
      id: 4,
      label: '4. Update Credit Card',
      route: '/cards/update',
    },
    {
      id: 5,
      label: '5. View Transactions',
      route: '/transactions',
    },
    {
      id: 6,
      label: '6. View Transaction Detail',
      route: '/transactions/detail',
    },
    {
      id: 7,
      label: '7. Enter New Transaction',
      route: '/transactions/entry',
    },
    {
      id: 8,
      label: '8. View Billing Statement',
      route: '/billing',
    },
    {
      id: 9,
      label: '9. Generate Reports',
      route: '/reports',
    },
    {
      id: 10,
      label: '10. Manage Users',
      route: '/users',
      adminOnly: true, // Only show to users with userType='A'
    },
    {
      id: 11,
      label: '11. Administration Menu',
      route: '/admin',
      adminOnly: true, // Only show to users with userType='A'
    },
    {
      id: 12,
      label: '12. Sign Off',
      route: '/logout',
    },
  ];

  /**
   * Filter menu options based on user role
   * Implements COBOL logic: IF CDEMO-USER-TYPE = 'A' THEN show admin options
   * Non-admin users (userType='U' or 'R') see only options 1-9 and 12
   */
  const filteredMenuOptions = menuOptions.filter((option) => {
    // Show all options to admin users (userType='A' from USER_TYPES.ADMIN)
    if (user?.userType === USER_TYPES.ADMIN) {
      return true;
    }
    // Show only non-admin options to regular users
    return !option.adminOnly;
  });

  /**
   * Handle menu option selection by clicking list item
   * Replaces COBOL: User enters option → EXEC CICS XCTL to target program
   * 
   * @param optionId - Menu option number (1-12)
   */
  const handleMenuClick = (optionId: number): void => {
    // Clear any existing error messages
    setErrorMessage('');

    // Find the menu option configuration
    const selectedOption = menuOptions.find((opt) => opt.id === optionId);

    if (!selectedOption) {
      // This should never happen, but handle defensively
      setErrorMessage('Invalid menu option selected.');
      return;
    }

    // Special handling for logout option (id=12)
    // Replaces COBOL: EXEC CICS SIGNOFF END-EXEC
    if (optionId === 12) {
      // Navigate to logout route which will trigger logout and redirect to login
      navigate('/logout');
      return;
    }

    // Navigate to the selected route
    // Replaces COBOL: EXEC CICS XCTL PROGRAM('xxxxxxxx') COMMAREA(...)
    navigate(selectedOption.route);
  };

  /**
   * Handle form submission when user enters option number manually
   * Implements COBOL receive map and validation logic from COMEN01C.cbl
   * 
   * COBOL Flow:
   * 1. EXEC CICS RECEIVE MAP('COMEN1A') MAPSET('COMEN01')
   * 2. Validate OPTIONI field (numeric, range 1-12)
   * 3. EXEC CICS XCTL to target program based on option
   * 
   * @param values - Form values containing option number
   * @param formikHelpers - Formik helper functions
   */
  const handleSubmit = (
    values: MenuFormValues,
    { setSubmitting, resetForm }: FormikHelpers<MenuFormValues>
  ): void => {
    try {
      // Clear any existing error messages
      setErrorMessage('');

      // Parse option number from string to integer
      const optionNumber = parseInt(values.option, 10);

      // Validation already handled by Yup schema, but double-check
      if (isNaN(optionNumber) || optionNumber < 1 || optionNumber > 12) {
        setErrorMessage('Option must be between 1 and 12.');
        setSubmitting(false);
        return;
      }

      // Find the selected menu option
      const selectedOption = menuOptions.find((opt) => opt.id === optionNumber);

      if (!selectedOption) {
        setErrorMessage('Invalid option selected.');
        setSubmitting(false);
        return;
      }

      // Check admin-only access restriction
      // Replaces COBOL: IF CDEMO-USER-TYPE NOT = 'A' THEN reject admin options
      if (selectedOption.adminOnly && user?.userType !== USER_TYPES.ADMIN) {
        setErrorMessage(
          'Access denied. This option requires administrator privileges.'
        );
        setSubmitting(false);
        return;
      }

      // Navigate to selected route
      // Replaces COBOL: EXEC CICS XCTL PROGRAM(target-program) COMMAREA(...)
      handleMenuClick(optionNumber);

      // Reset form after successful navigation
      resetForm();
    } catch (error) {
      // Handle unexpected errors
      console.error('Error processing menu selection:', error);
      setErrorMessage('An error occurred. Please try again.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      {/* Page Header - replaces BMS header lines 1-2 */}
      <Header />

      {/* Main Content Container */}
      <Container maxWidth="md" sx={{ mt: 4, mb: 4 }}>
        <Box
          sx={{
            display: 'flex',
            flexDirection: 'column',
            minHeight: 'calc(100vh - 200px)', // Account for header and footer
          }}
        >
          {/* Main Menu Card - replaces BMS screen lines 4-20 */}
          <Card elevation={3}>
            <CardContent>
              {/* Page Title - replaces BMS line 4: "Main Menu" */}
              <Typography
                variant="h4"
                component="h1"
                align="center"
                gutterBottom
                sx={{ mb: 3 }}
              >
                Main Menu
              </Typography>

              {/* User Information Display */}
              {user && (
                <Typography
                  variant="subtitle2"
                  color="text.secondary"
                  align="center"
                  sx={{ mb: 2 }}
                >
                  Signed in as: {user.userFirstName} {user.userLastName} (
                  {user.userType === USER_TYPES.ADMIN ? 'Administrator' : 'User'})
                </Typography>
              )}

              <Divider sx={{ mb: 3 }} />

              {/* Menu Options List - replaces BMS lines 6-17 (OPTN001-OPTN012) */}
              <Paper variant="outlined" sx={{ mb: 3 }}>
                <List>
                  {filteredMenuOptions.map((option, index) => (
                    <React.Fragment key={option.id}>
                      <ListItem disablePadding>
                        <ListItemButton
                          onClick={() => handleMenuClick(option.id)}
                          sx={{
                            py: 1.5,
                            '&:hover': {
                              backgroundColor: 'action.hover',
                            },
                          }}
                        >
                          <ListItemText
                            primary={option.label}
                            primaryTypographyProps={{
                              variant: 'body1',
                              fontFamily: 'monospace', // Preserve mainframe character spacing
                            }}
                          />
                        </ListItemButton>
                      </ListItem>
                      {/* Divider between items (not after last item) */}
                      {index < filteredMenuOptions.length - 1 && <Divider />}
                    </React.Fragment>
                  ))}
                </List>
              </Paper>

              {/* Option Input Form - replaces BMS line 20 */}
              <Formik
                initialValues={{ option: '' }}
                validationSchema={menuValidationSchema}
                onSubmit={handleSubmit}
              >
                {({ values, errors, touched, handleChange, handleBlur, isSubmitting }) => (
                  <Form>
                    <Box
                      sx={{
                        display: 'flex',
                        alignItems: 'flex-start',
                        gap: 2,
                        flexDirection: { xs: 'column', sm: 'row' },
                      }}
                    >
                      {/* Option Number Input Field */}
                      {/* Replaces BMS OPTION field: NUM, LENGTH=2, POS=(20,41) */}
                      <TextField
                        name="option"
                        label="Please select an option"
                        value={values.option}
                        onChange={handleChange}
                        onBlur={handleBlur}
                        error={touched.option && Boolean(errors.option)}
                        helperText={touched.option && errors.option}
                        placeholder="1-12"
                        inputProps={{
                          maxLength: 2, // BMS LENGTH=2
                          inputMode: 'numeric', // Mobile keyboard optimization
                          pattern: '[0-9]*', // HTML5 numeric validation
                        }}
                        sx={{
                          flexGrow: 1,
                          '& input': {
                            fontFamily: 'monospace', // Mainframe character style
                            textAlign: 'right', // BMS JUSTIFY=RIGHT
                          },
                        }}
                        disabled={isSubmitting}
                        autoFocus // BMS IC (Initial Cursor) attribute
                      />

                      {/* Submit Button - replaces ENTER key function */}
                      <Button
                        type="submit"
                        variant="contained"
                        color="primary"
                        disabled={isSubmitting}
                        sx={{ minWidth: 120 }}
                      >
                        {isSubmitting ? 'Processing...' : 'Go'}
                      </Button>
                    </Box>
                  </Form>
                )}
              </Formik>

              {/* Error Message Display - replaces BMS line 23 ERRMSG field */}
              {errorMessage && (
                <Box sx={{ mt: 2 }}>
                  <ErrorMessage
                    message={errorMessage}
                    severity="error"
                    onClose={() => setErrorMessage('')}
                  />
                </Box>
              )}

              {/* Function Key Legend - replaces BMS line 24 */}
              <Typography
                variant="caption"
                color="text.secondary"
                align="center"
                display="block"
                sx={{ mt: 3 }}
              >
                Click an option or enter option number • ESC = Exit
              </Typography>
            </CardContent>
          </Card>
        </Box>
      </Container>

      {/* Page Footer */}
      <Footer />
    </>
  );
};

/**
 * Default export for MainMenuPage component
 * Per Agent Action Plan Section 0.4.18 export requirements
 */
export default MainMenuPage;

