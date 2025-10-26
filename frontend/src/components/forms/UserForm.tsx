/**
 * UserForm Component
 * 
 * Reusable React TypeScript form component for user management data entry and validation.
 * Converted from BMS maps COUSR01.bms (Add User) and COUSR02.bms (Update User) to provide
 * modern web interface for creating and updating user records with comprehensive validation.
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 * 
 * Conversion Notes:
 * - Source: BMS maps COUSR01.bms (lines 84-150) and COUSR02.bms (lines 85-154)
 * - Replaces 3270 terminal screens with responsive React form
 * - Uses Formik for form state management replacing BMS field state tracking
 * - Uses Yup validation matching BMS field attributes (LENGTH, UNPROT, DRK)
 * - Uses Material-UI components for modern UI/UX
 * 
 * BMS Field to React Component Mappings:
 * - FNAME (IC, UNPROT, LENGTH=20) → TextField with autoFocus, maxLength 20
 * - LNAME (UNPROT, LENGTH=20) → TextField with maxLength 20
 * - USERID (UNPROT, LENGTH=8) → TextField with maxLength 8 (disabled in update mode)
 * - PASSWD (DRK, UNPROT, LENGTH=8) → TextField type="password" with maxLength 8
 * - USRTYPE (UNPROT, LENGTH=1) → Select with MenuItem options (A/U/O)
 * - ERRMSG (ASKIP, BRT, COLOR=RED) → Formik error messages as helperText
 * 
 * Features:
 * - Form state management with Formik
 * - Client-side validation with Yup schemas
 * - Field-level error display matching BMS ERRMSG behavior
 * - Support for both create and update modes
 * - Material-UI responsive grid layout
 * - Type-safe integration with User interface
 * - Proper callback handling (onSubmit, onCancel)
 * 
 * Usage:
 * ```typescript
 * // Add new user (create mode)
 * <UserForm
 *   mode="create"
 *   onSubmit={async (values) => await userService.createUser(values)}
 *   onCancel={() => navigate('/users')}
 * />
 * 
 * // Update existing user (update mode)
 * <UserForm
 *   mode="update"
 *   initialValues={existingUser}
 *   onSubmit={async (values) => await userService.updateUser(values)}
 *   onCancel={() => navigate('/users')}
 * />
 * ```
 * 
 * @module components/forms/UserForm
 * @see frontend/src/pages/UserAddPage.tsx - Parent page for user creation
 * @see frontend/src/pages/UserUpdatePage.tsx - Parent page for user updates
 * @see frontend/src/types/user.ts - User and UserType definitions
 * @see app/bms/COUSR01.bms - Original BMS Add User map
 * @see app/bms/COUSR02.bms - Original BMS Update User map
 */

import React from 'react';
import { Formik, Form, FormikHelpers } from 'formik';
import {
  Box,
  Button,
  FormControl,
  FormHelperText,
  Grid,
  InputLabel,
  MenuItem,
  Select,
  TextField,
  Typography
} from '@mui/material';
import * as yup from 'yup';
import { UserType } from '../../types/user';

/**
 * User form data structure
 * 
 * TypeScript interface defining the data structure for user form submission.
 * Matches User interface but includes password field for create/update operations.
 * 
 * Field Mappings from BMS Maps:
 * - userId: USERID field (PIC X(08) from COBOL CSUSR01Y.cpy)
 * - password: PASSWD field (PIC X(08), DRK attribute for hidden display)
 * - userFirstName: FNAME field (PIC X(20))
 * - userLastName: LNAME field (PIC X(20))
 * - userType: USRTYPE field (PIC X(01), values A/U/O)
 * 
 * Security Note: Password field is only used during form submission and is
 * transmitted over HTTPS to backend where it's hashed with BCrypt. Password
 * is never stored in component state after submission.
 * 
 * @interface UserFormData
 */
export interface UserFormData {
  /**
   * User ID (unique identifier)
   * 
   * Maximum 8 characters alphanumeric.
   * From BMS USERID field (COUSR01.bms line 111-115, COUSR02.bms line 85-89).
   * 
   * Validation:
   * - Required in both create and update modes
   * - Max length: 8 characters
   * - Pattern: Alphanumeric only (A-Z, a-z, 0-9)
   * 
   * Example: "USER0001", "ADMIN001"
   */
  userId: string;

  /**
   * User password
   * 
   * Exactly 8 characters (COBOL compatibility constraint).
   * From BMS PASSWD field with DRK attribute (COUSR01.bms line 126-130, COUSR02.bms line 130-134).
   * 
   * Validation:
   * - Required in create mode
   * - Optional in update mode (only if changing password)
   * - Exactly 8 characters length
   * 
   * Security:
   * - Displayed as password field (type="password")
   * - Transmitted over HTTPS only
   * - Backend hashes with BCrypt before storage
   * 
   * Example: "Pass1234"
   */
  password: string;

  /**
   * User first name
   * 
   * Maximum 20 characters.
   * From BMS FNAME field with IC attribute (COUSR01.bms line 84-88, COUSR02.bms line 103-107).
   * 
   * Validation:
   * - Required
   * - Max length: 20 characters
   * 
   * Example: "John", "Mary"
   */
  userFirstName: string;

  /**
   * User last name
   * 
   * Maximum 20 characters.
   * From BMS LNAME field (COUSR01.bms line 97-101, COUSR02.bms line 116-120).
   * 
   * Validation:
   * - Required
   * - Max length: 20 characters
   * 
   * Example: "Smith", "Johnson"
   */
  userLastName: string;

  /**
   * User type/role code
   * 
   * Single character code indicating user's role.
   * From BMS USRTYPE field (COUSR01.bms line 141-145, COUSR02.bms line 145-149).
   * 
   * Validation:
   * - Required
   * - Must be one of: 'A' (Admin), 'U' (User), 'O' (Operator)
   * 
   * Maps to UserType enum and Spring Security roles:
   * - 'A' → UserType.ADMIN → ROLE_ADMIN
   * - 'U' → UserType.USER → ROLE_USER
   * - 'O' → UserType.OPERATOR → ROLE_OPERATOR
   * 
   * Example: "A", "U", "O"
   */
  userType: string;
}

/**
 * UserForm component props
 * 
 * TypeScript interface defining the props accepted by UserForm component.
 * Supports both create and update modes with flexible callback handling.
 * 
 * @interface UserFormProps
 */
export interface UserFormProps {
  /**
   * Initial form values for update mode
   * 
   * Optional partial User object used to populate form fields in update mode.
   * If not provided, form initializes with empty values (create mode).
   * 
   * Note: Password is never included in initialValues for security reasons.
   * In update mode, password field is optional and only submitted if user
   * enters a new password.
   * 
   * Example:
   * ```typescript
   * {
   *   userId: "USER0001",
   *   userFirstName: "John",
   *   userLastName: "Smith",
   *   userType: "U"
   * }
   * ```
   */
  initialValues?: Partial<UserFormData>;

  /**
   * Form submission callback
   * 
   * Called when form is submitted and passes validation.
   * Receives validated UserFormData object.
   * Can be async for API calls.
   * 
   * Example:
   * ```typescript
   * onSubmit={async (values) => {
   *   await userService.createUser(values);
   *   navigate('/users');
   * }}
   * ```
   * 
   * @param values - Validated form data
   * @returns Promise<void> or void
   */
  onSubmit: (values: UserFormData) => void | Promise<void>;

  /**
   * Form cancellation callback
   * 
   * Called when user clicks Cancel button or wants to abandon form.
   * Typically used to navigate back to user list page.
   * 
   * Example:
   * ```typescript
   * onCancel={() => navigate('/users')}
   * ```
   */
  onCancel: () => void;

  /**
   * Form operation mode
   * 
   * Determines form behavior and validation rules:
   * - 'create': All fields editable, password required, userId editable
   * - 'update': userId disabled (primary key), password optional
   * 
   * Default: 'create'
   * 
   * Affects:
   * - userId field disabled state
   * - Password field required validation
   * - Submit button label ("Add User" vs "Update User")
   * 
   * Example: mode="update"
   */
  mode?: 'create' | 'update';
}

/**
 * Yup validation schema for user form
 * 
 * Defines comprehensive field-level validation rules matching BMS field
 * attributes from COUSR01.bms and COUSR02.bms maps.
 * 
 * Validation Rules from BMS Constraints:
 * - userId: Required, max 8 chars, alphanumeric (from USERID LENGTH=8)
 * - password: Required in create mode, exactly 8 chars (from PASSWD LENGTH=8)
 * - userFirstName: Required, max 20 chars (from FNAME LENGTH=20)
 * - userLastName: Required, max 20 chars (from LNAME LENGTH=20)
 * - userType: Required, one of A/U/O (from USRTYPE LENGTH=1 with "(A=Admin, U=User)" note)
 * 
 * Error messages replicate BMS ERRMSG field behavior (COUSR01.bms line 151-154,
 * COUSR02.bms line 155-158) displayed in RED color.
 * 
 * @param mode - Form mode to determine conditional validation (password required only in create mode)
 * @returns Yup validation schema object
 */
const getValidationSchema = (mode: 'create' | 'update' = 'create') => {
  return yup.object({
    userId: yup
      .string()
      .required('User ID is required')
      .max(8, 'User ID must be at most 8 characters')
      .matches(/^[A-Za-z0-9]+$/, 'User ID must be alphanumeric'),
    
    password: mode === 'create'
      ? yup
          .string()
          .required('Password is required')
          .min(8, 'Password must be exactly 8 characters')
          .max(8, 'Password must be exactly 8 characters')
      : yup
          .string()
          .test('password-length', 'Password must be exactly 8 characters if provided', (value) => {
            if (!value || value.length === 0) return true; // Optional in update mode
            return value.length === 8;
          }),
    
    userFirstName: yup
      .string()
      .required('First Name is required')
      .max(20, 'First Name must be at most 20 characters'),
    
    userLastName: yup
      .string()
      .required('Last Name is required')
      .max(20, 'Last Name must be at most 20 characters'),
    
    userType: yup
      .string()
      .required('User Type is required')
      .oneOf(['A', 'U', 'O'], 'User Type must be A (Admin), U (User), or O (Operator)')
  });
};

/**
 * UserForm Component
 * 
 * Reusable form component for user management operations (add/update).
 * Implements comprehensive validation and error handling matching BMS behavior.
 * 
 * Component Features:
 * - Formik integration for form state management
 * - Yup schema validation with real-time error display
 * - Material-UI components for modern responsive UI
 * - Support for both create and update modes
 * - Proper field focus (autoFocus on first field matching BMS IC attribute)
 * - Password field masking (type="password" matching BMS DRK attribute)
 * - Accessible form labels and error messages
 * - Responsive grid layout (2-column on larger screens, stacked on mobile)
 * 
 * BMS to React Attribute Mappings:
 * - IC (Initial Cursor) → autoFocus on userFirstName field
 * - DRK (Dark/hidden) → type="password" for password field
 * - UNPROT (Unprotected/editable) → enabled TextField
 * - PROT (Protected/read-only) → disabled TextField (userId in update mode)
 * - HILIGHT=UNDERLINE → MUI default underline style
 * - FSET → Formik manages field modified state
 * - COLOR=RED → error color for validation messages
 * 
 * @param props - UserFormProps configuration object
 * @returns JSX.Element - Rendered form component
 */
const UserForm: React.FC<UserFormProps> = ({
  initialValues = {},
  onSubmit,
  onCancel,
  mode = 'create'
}) => {
  /**
   * Initial form values with defaults
   * 
   * Merges provided initialValues with default empty strings.
   * Password always defaults to empty string for security (never pre-filled).
   */
  const formInitialValues: UserFormData = {
    userId: initialValues.userId || '',
    password: '', // Always empty for security (never pre-populate password)
    userFirstName: initialValues.userFirstName || '',
    userLastName: initialValues.userLastName || '',
    userType: initialValues.userType || ''
  };

  /**
   * Form submission handler
   * 
   * Wraps onSubmit callback with Formik helpers for proper form state management.
   * Sets submitting state during async operations and resets on completion.
   * 
   * @param values - Validated form data
   * @param formikHelpers - Formik helper functions
   */
  const handleSubmit = async (
    values: UserFormData,
    { setSubmitting }: FormikHelpers<UserFormData>
  ) => {
    try {
      await onSubmit(values);
    } catch (error) {
      // Error handling is delegated to parent component
      // which should display appropriate error messages
      console.error('Form submission error:', error);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Formik
      initialValues={formInitialValues}
      validationSchema={getValidationSchema(mode)}
      onSubmit={handleSubmit}
      enableReinitialize={true}
    >
      {({ values, errors, touched, handleChange, handleBlur, isSubmitting }) => (
        <Form>
          <Box sx={{ flexGrow: 1 }}>
            <Grid container spacing={3}>
              {/* Header with form title matching BMS screen title */}
              <Grid item xs={12}>
                <Typography variant="h5" component="h2" gutterBottom>
                  {mode === 'create' ? 'Add User' : 'Update User'}
                </Typography>
                <Typography variant="body2" color="text.secondary" paragraph>
                  {mode === 'create'
                    ? 'Enter user information to create a new user account.'
                    : 'Update user information. User ID cannot be changed.'}
                </Typography>
              </Grid>

              {/* First Name field - matches BMS FNAME with IC attribute (autoFocus) */}
              <Grid item xs={12} sm={6}>
                <TextField
                  fullWidth
                  id="userFirstName"
                  name="userFirstName"
                  label="First Name"
                  value={values.userFirstName}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.userFirstName && Boolean(errors.userFirstName)}
                  helperText={touched.userFirstName && errors.userFirstName}
                  inputProps={{ maxLength: 20 }}
                  autoFocus // IC attribute from BMS FNAME field (COUSR01.bms line 84)
                  required
                  placeholder="Enter first name"
                />
              </Grid>

              {/* Last Name field - matches BMS LNAME */}
              <Grid item xs={12} sm={6}>
                <TextField
                  fullWidth
                  id="userLastName"
                  name="userLastName"
                  label="Last Name"
                  value={values.userLastName}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.userLastName && Boolean(errors.userLastName)}
                  helperText={touched.userLastName && errors.userLastName}
                  inputProps={{ maxLength: 20 }}
                  required
                  placeholder="Enter last name"
                />
              </Grid>

              {/* User ID field - matches BMS USERID, disabled in update mode */}
              <Grid item xs={12} sm={6}>
                <TextField
                  fullWidth
                  id="userId"
                  name="userId"
                  label="User ID"
                  value={values.userId}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.userId && Boolean(errors.userId)}
                  helperText={
                    (touched.userId && errors.userId) ||
                    (mode === 'update'
                      ? 'User ID cannot be changed'
                      : 'Maximum 8 alphanumeric characters')
                  }
                  inputProps={{ maxLength: 8 }}
                  disabled={mode === 'update'} // Primary key cannot be changed in update mode
                  required
                  placeholder="Enter user ID"
                />
              </Grid>

              {/* Password field - matches BMS PASSWD with DRK attribute (hidden) */}
              <Grid item xs={12} sm={6}>
                <TextField
                  fullWidth
                  id="password"
                  name="password"
                  label="Password"
                  type="password" // DRK attribute from BMS PASSWD field (COUSR01.bms line 126)
                  value={values.password}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.password && Boolean(errors.password)}
                  helperText={
                    (touched.password && errors.password) ||
                    (mode === 'create'
                      ? 'Exactly 8 characters required'
                      : 'Leave blank to keep current password (exactly 8 characters if changing)')
                  }
                  inputProps={{ maxLength: 8 }}
                  required={mode === 'create'}
                  placeholder={mode === 'create' ? 'Enter password' : 'Enter new password (optional)'}
                  autoComplete="new-password"
                />
              </Grid>

              {/* User Type field - matches BMS USRTYPE with dropdown */}
              <Grid item xs={12} sm={6}>
                <FormControl
                  fullWidth
                  error={touched.userType && Boolean(errors.userType)}
                  required
                >
                  <InputLabel id="userType-label">User Type</InputLabel>
                  <Select
                    labelId="userType-label"
                    id="userType"
                    name="userType"
                    value={values.userType}
                    onChange={handleChange}
                    onBlur={handleBlur}
                    label="User Type"
                  >
                    <MenuItem value="">
                      <em>Select user type</em>
                    </MenuItem>
                    <MenuItem value={UserType.ADMIN}>
                      Admin (A) - Full system access
                    </MenuItem>
                    <MenuItem value={UserType.USER}>
                      User (U) - Standard user access
                    </MenuItem>
                    <MenuItem value={UserType.OPERATOR}>
                      Operator (O) - Operational access
                    </MenuItem>
                  </Select>
                  {touched.userType && errors.userType && (
                    <FormHelperText>{errors.userType}</FormHelperText>
                  )}
                  {!errors.userType && (
                    <FormHelperText>
                      Select role: Admin, User, or Operator
                    </FormHelperText>
                  )}
                </FormControl>
              </Grid>

              {/* Action buttons - matches BMS FKEYS */}
              <Grid item xs={12}>
                <Box sx={{ display: 'flex', gap: 2, justifyContent: 'flex-end', mt: 2 }}>
                  {/* Cancel button - matches F12=Cancel from BMS */}
                  <Button
                    variant="outlined"
                    onClick={onCancel}
                    disabled={isSubmitting}
                  >
                    Cancel
                  </Button>

                  {/* Submit button - matches ENTER=Add User / ENTER=Fetch from BMS */}
                  <Button
                    type="submit"
                    variant="contained"
                    color="primary"
                    disabled={isSubmitting}
                  >
                    {isSubmitting
                      ? 'Submitting...'
                      : mode === 'create'
                      ? 'Add User'
                      : 'Update User'}
                  </Button>
                </Box>
              </Grid>
            </Grid>
          </Box>
        </Form>
      )}
    </Formik>
  );
};

export default UserForm;
