/**
 * UserAddComponent.jsx
 * 
 * React functional component for creating new users with admin-only access.
 * Transforms COUSR01M.bms 3270 screen layout to Material-UI form.
 * 
 * Original COBOL Program: COUSR01C.cbl
 * Original BMS Mapset: COUSR01.bms
 * Original Copybook: CSUSR01Y.cpy
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useFormik } from 'formik';
import * as Yup from 'yup';
import {
  Box,
  Button,
  TextField,
  Typography,
  Paper,
  Grid,
  FormControl,
  FormHelperText,
  CircularProgress,
  Snackbar,
  Alert,
  RadioGroup,
  FormControlLabel,
  Radio,
  FormLabel
} from '@mui/material';
import axios from 'axios';

/**
 * Validation schema using Yup - maps to COBOL validation rules from COUSR01C.cbl
 * Preserves validation logic from PROCESS-ENTER-KEY paragraph
 */
const validationSchema = Yup.object({
  firstName: Yup.string()
    .required('First Name can NOT be empty...')
    .max(20, 'First Name cannot exceed 20 characters')
    .trim(),
  
  lastName: Yup.string()
    .required('Last Name can NOT be empty...')
    .max(20, 'Last Name cannot exceed 20 characters')
    .trim(),
  
  userId: Yup.string()
    .required('User ID can NOT be empty...')
    .max(8, 'User ID cannot exceed 8 characters')
    .matches(/^[a-zA-Z0-9]+$/, 'User ID must contain only alphanumeric characters')
    .trim(),
  
  password: Yup.string()
    .required('Password can NOT be empty...')
    .min(8, 'Password must be exactly 8 characters')
    .max(8, 'Password must be exactly 8 characters')
    .matches(
      /^(?=.*[A-Za-z])(?=.*\d)[A-Za-z\d@$!%*#?&]{8}$/,
      'Password must contain at least one letter and one number'
    ),
  
  confirmPassword: Yup.string()
    .required('Password confirmation is required')
    .oneOf([Yup.ref('password'), null], 'Passwords must match'),
  
  userType: Yup.string()
    .required('User Type can NOT be empty...')
    .oneOf(['A', 'U'], 'User Type must be A (Admin) or U (User)')
});

/**
 * UserAddComponent - Main functional component
 * Implements complete user creation workflow with comprehensive validation
 */
const UserAddComponent = () => {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [snackbar, setSnackbar] = useState({
    open: false,
    message: '',
    severity: 'success'
  });

  /**
   * Check if user ID already exists
   * Maps to COBOL DUPKEY/DUPREC check in WRITE-USER-SEC-FILE paragraph
   */
  const checkUserIdExists = async (userId) => {
    try {
      const token = localStorage.getItem('jwtToken');
      const response = await axios.get(
        `/api/admin/users/${userId}`,
        {
          headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json'
          }
        }
      );
      return response.status === 200;
    } catch (error) {
      if (error.response && error.response.status === 404) {
        return false;
      }
      throw error;
    }
  };

  /**
   * Handle form submission
   * Maps to COBOL PROCESS-ENTER-KEY and WRITE-USER-SEC-FILE paragraphs
   */
  const handleSubmit = async (values, { setFieldError, resetForm }) => {
    setLoading(true);
    
    try {
      // Check for duplicate user ID (maps to DUPKEY/DUPREC check)
      const userExists = await checkUserIdExists(values.userId);
      if (userExists) {
        setFieldError('userId', 'User ID already exist...');
        setLoading(false);
        return;
      }

      // Prepare user data matching SEC-USER-DATA structure from CSUSR01Y.cpy
      const userData = {
        userId: values.userId.toUpperCase(),
        firstName: values.firstName,
        lastName: values.lastName,
        password: values.password,
        userType: values.userType
      };

      // Make POST request to create user (maps to CICS WRITE)
      const token = localStorage.getItem('jwtToken');
      const response = await axios.post(
        '/api/admin/users',
        userData,
        {
          headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json'
          }
        }
      );

      if (response.status === 201 || response.status === 200) {
        // Success message matching COBOL: "User {userId} has been added..."
        setSnackbar({
          open: true,
          message: `User ${values.userId} has been added...`,
          severity: 'success'
        });
        
        // Clear form after successful submission (maps to INITIALIZE-ALL-FIELDS)
        resetForm();
        
        // Navigate back to user list after short delay
        setTimeout(() => {
          navigate('/admin/users');
        }, 2000);
      }
    } catch (error) {
      // Error handling mapping COBOL error responses
      let errorMessage = 'Unable to Add User...';
      
      if (error.response) {
        if (error.response.status === 409) {
          errorMessage = 'User ID already exist...';
          setFieldError('userId', errorMessage);
        } else if (error.response.status === 401 || error.response.status === 403) {
          errorMessage = 'Unauthorized access. Admin privileges required.';
        } else if (error.response.data && error.response.data.message) {
          errorMessage = error.response.data.message;
        }
      } else if (error.request) {
        errorMessage = 'Network error. Please check your connection.';
      }
      
      setSnackbar({
        open: true,
        message: errorMessage,
        severity: 'error'
      });
    } finally {
      setLoading(false);
    }
  };

  /**
   * Handle Back button (F3)
   * Maps to COBOL: WHEN DFHPF3 - returns to COADM01C
   */
  const handleBack = () => {
    navigate('/admin/users');
  };

  /**
   * Handle Clear button (F4)
   * Maps to COBOL: WHEN DFHPF4 - PERFORM CLEAR-CURRENT-SCREEN
   */
  const handleClear = () => {
    formik.resetForm();
    setSnackbar({
      open: true,
      message: 'Form cleared',
      severity: 'info'
    });
  };

  /**
   * Handle Snackbar close
   */
  const handleSnackbarClose = (event, reason) => {
    if (reason === 'clickaway') {
      return;
    }
    setSnackbar({ ...snackbar, open: false });
  };

  // Initialize Formik with validation schema and submit handler
  const formik = useFormik({
    initialValues: {
      firstName: '',
      lastName: '',
      userId: '',
      password: '',
      confirmPassword: '',
      userType: 'U'  // Default to Regular User
    },
    validationSchema: validationSchema,
    onSubmit: handleSubmit,
    validateOnChange: true,
    validateOnBlur: true,
    validateOnMount: true,  // Ensure validation runs on mount
    isInitialValid: false  // Form is invalid initially (empty required fields)
  });

  return (
    <Box sx={{ p: 3 }}>
      {/* Page Title - maps to BMS screen title at POS=(4,35) */}
      <Typography variant="h4" component="h1" gutterBottom align="center" sx={{ mb: 3 }}>
        Add User
      </Typography>

      <Paper elevation={3} sx={{ p: 4, maxWidth: 800, mx: 'auto' }}>
        <form onSubmit={formik.handleSubmit} noValidate>
          <Grid container spacing={3}>
            {/* First Name and Last Name on same row - maps to BMS POS=(8,18) and POS=(8,56) */}
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                id="firstName"
                name="firstName"
                label="First Name"
                value={formik.values.firstName}
                onChange={formik.handleChange}
                onBlur={formik.handleBlur}
                error={formik.touched.firstName && Boolean(formik.errors.firstName)}
                helperText={formik.touched.firstName && formik.errors.firstName}
                required
                inputProps={{
                  maxLength: 20,
                  autoFocus: true  // IC (Initial Cursor) attribute from BMS
                }}
                disabled={loading}
              />
            </Grid>

            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                id="lastName"
                name="lastName"
                label="Last Name"
                value={formik.values.lastName}
                onChange={formik.handleChange}
                onBlur={formik.handleBlur}
                error={formik.touched.lastName && Boolean(formik.errors.lastName)}
                helperText={formik.touched.lastName && formik.errors.lastName}
                required
                inputProps={{
                  maxLength: 20
                }}
                disabled={loading}
              />
            </Grid>

            {/* User ID and Password on same row - maps to BMS POS=(11,15) and POS=(11,55) */}
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                id="userId"
                name="userId"
                label="User ID"
                value={formik.values.userId}
                onChange={formik.handleChange}
                onBlur={formik.handleBlur}
                error={formik.touched.userId && Boolean(formik.errors.userId)}
                helperText={
                  (formik.touched.userId && formik.errors.userId) || 
                  '(8 Char max, alphanumeric)'
                }
                required
                inputProps={{
                  maxLength: 8
                }}
                disabled={loading}
              />
            </Grid>

            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                id="password"
                name="password"
                label="Password"
                type="password"
                value={formik.values.password}
                onChange={formik.handleChange}
                onBlur={formik.handleBlur}
                error={formik.touched.password && Boolean(formik.errors.password)}
                helperText={
                  (formik.touched.password && formik.errors.password) || 
                  '(8 Char, must contain letter and number)'
                }
                required
                inputProps={{
                  maxLength: 8
                }}
                disabled={loading}
              />
            </Grid>

            {/* Password Confirmation field - additional field for double-entry verification */}
            <Grid item xs={12} sm={6}>
              <TextField
                fullWidth
                id="confirmPassword"
                name="confirmPassword"
                label="Confirm Password"
                type="password"
                value={formik.values.confirmPassword}
                onChange={formik.handleChange}
                onBlur={formik.handleBlur}
                error={formik.touched.confirmPassword && Boolean(formik.errors.confirmPassword)}
                helperText={formik.touched.confirmPassword && formik.errors.confirmPassword}
                required
                inputProps={{
                  maxLength: 8
                }}
                disabled={loading}
              />
            </Grid>

            {/* User Type - maps to BMS POS=(14,17) with hint "(A=Admin, U=User)" */}
            <Grid item xs={12} sm={6}>
              <FormControl 
                fullWidth 
                error={formik.touched.userType && Boolean(formik.errors.userType)}
                required
                disabled={loading}
              >
                <FormLabel component="legend">User Type</FormLabel>
                <RadioGroup
                  row
                  id="userType"
                  name="userType"
                  value={formik.values.userType}
                  onChange={formik.handleChange}
                >
                  <FormControlLabel 
                    value="U" 
                    control={<Radio />} 
                    label="Regular User" 
                  />
                  <FormControlLabel 
                    value="A" 
                    control={<Radio />} 
                    label="Admin" 
                  />
                </RadioGroup>
                {formik.touched.userType && formik.errors.userType && (
                  <FormHelperText>{formik.errors.userType}</FormHelperText>
                )}
              </FormControl>
            </Grid>

            {/* Action Buttons - maps to BMS function keys */}
            <Grid item xs={12}>
              <Box sx={{ display: 'flex', gap: 2, justifyContent: 'flex-end', mt: 2 }}>
                {/* ENTER=Add User */}
                <Button
                  type="submit"
                  variant="contained"
                  color="primary"
                  disabled={loading || !formik.isValid}
                  startIcon={loading ? <CircularProgress size={20} /> : null}
                >
                  {loading ? 'Adding...' : 'Add User'}
                </Button>

                {/* F4=Clear */}
                <Button
                  type="button"
                  variant="outlined"
                  color="secondary"
                  onClick={handleClear}
                  disabled={loading}
                >
                  Clear
                </Button>

                {/* F3=Back */}
                <Button
                  type="button"
                  variant="outlined"
                  onClick={handleBack}
                  disabled={loading}
                >
                  Back to User List
                </Button>
              </Box>
            </Grid>
          </Grid>
        </form>
      </Paper>

      {/* Snackbar for success and error messages - maps to COBOL ERRMSGO field */}
      <Snackbar
        open={snackbar.open}
        autoHideDuration={6000}
        onClose={handleSnackbarClose}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert 
          onClose={handleSnackbarClose} 
          severity={snackbar.severity}
          sx={{ width: '100%' }}
        >
          {snackbar.message}
        </Alert>
      </Snackbar>
    </Box>
  );
};

export default UserAddComponent;
