/**
 * UserUpdateComponent.jsx
 * 
 * React functional component for updating existing user information with admin-only access.
 * Transforms COUSR02M.bms 3270 screen layout to modern Material-UI form.
 * 
 * Features:
 * - Two-phase workflow: User ID search followed by editable form
 * - Password reset capability (optional field)
 * - User type modification (Admin/Regular User)
 * - Account status toggle
 * - Field validation from COUSR02C.cbl business logic
 * - Spring Security JWT authentication with ADMIN role requirement
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useFormik } from 'formik';
import * as Yup from 'yup';
import {
  Box,
  Container,
  Paper,
  Typography,
  TextField,
  Button,
  Select,
  MenuItem,
  FormControl,
  InputLabel,
  FormHelperText,
  Divider,
  CircularProgress,
  Snackbar,
  Alert,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogContentText,
  DialogActions,
  Grid,
  Switch,
  FormControlLabel,
  Checkbox,
  InputAdornment,
  IconButton,
} from '@mui/material';
import {
  Visibility,
  VisibilityOff,
  Search as SearchIcon,
  Save as SaveIcon,
  Cancel as CancelIcon,
  Edit as EditIcon,
} from '@mui/icons-material';
import axios from 'axios';

/**
 * Validation schema for user search phase
 * Validates User ID entry (USRIDIN field from BMS)
 */
const searchValidationSchema = Yup.object({
  userId: Yup.string()
    .required('User ID cannot be empty')
    .min(1, 'User ID cannot be empty')
    .max(8, 'User ID must be 8 characters or less')
    .matches(/^[A-Za-z0-9]+$/, 'User ID must contain only alphanumeric characters'),
});

/**
 * Validation schema for user update form
 * Implements validation rules from COUSR02C.cbl UPDATE-USER-INFO paragraph
 */
const updateValidationSchema = Yup.object({
  userId: Yup.string()
    .required('User ID cannot be empty'),
  firstName: Yup.string()
    .required('First Name cannot be empty')
    .max(20, 'First Name must be 20 characters or less')
    .matches(/^[A-Za-z\s]+$/, 'First Name must contain only letters'),
  lastName: Yup.string()
    .required('Last Name cannot be empty')
    .max(20, 'Last Name must be 20 characters or less')
    .matches(/^[A-Za-z\s]+$/, 'Last Name must contain only letters'),
  password: Yup.string()
    .when('changePassword', {
      is: true,
      then: (schema) => schema
        .required('Password cannot be empty when changing password')
        .min(8, 'Password must be exactly 8 characters')
        .max(8, 'Password must be exactly 8 characters')
        .matches(/^[A-Za-z0-9!@#$%^&*]+$/, 'Password must contain only alphanumeric and special characters'),
      otherwise: (schema) => schema.notRequired(),
    }),
  confirmPassword: Yup.string()
    .when('changePassword', {
      is: true,
      then: (schema) => schema
        .required('Please confirm password')
        .oneOf([Yup.ref('password')], 'Passwords must match'),
      otherwise: (schema) => schema.notRequired(),
    }),
  userType: Yup.string()
    .required('User Type cannot be empty')
    .oneOf(['A', 'U'], 'User Type must be A (Admin) or U (User)'),
  isActive: Yup.boolean(),
  changePassword: Yup.boolean(),
});

/**
 * UserUpdateComponent
 * 
 * Main component implementing two-phase update workflow:
 * 1. Search Phase: Enter User ID to retrieve user data
 * 2. Edit Phase: Display and modify user information
 * 
 * Preserves BMS field layout and COBOL validation rules for functional equivalence
 */
const UserUpdateComponent = () => {
  const navigate = useNavigate();
  const { userId: urlUserId } = useParams();
  
  // Component state management
  const [phase, setPhase] = useState('search'); // 'search' or 'edit'
  const [loading, setLoading] = useState(false);
  const [userData, setUserData] = useState(null);
  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);
  const [snackbar, setSnackbar] = useState({ open: false, message: '', severity: 'info' });
  const [confirmDialog, setConfirmDialog] = useState(false);
  const [lastModified, setLastModified] = useState(null);
  
  /**
   * Formik instance for search phase
   * Handles User ID input and validation (USRIDIN field from COUSR02M.bms)
   */
  const searchFormik = useFormik({
    initialValues: {
      userId: urlUserId || '',
    },
    validationSchema: searchValidationSchema,
    onSubmit: async (values) => {
      await handleUserSearch(values.userId);
    },
  });
  
  /**
   * Formik instance for edit phase
   * Handles user detail form with validation from COUSR02C.cbl
   */
  const updateFormik = useFormik({
    initialValues: {
      userId: '',
      firstName: '',
      lastName: '',
      password: '',
      confirmPassword: '',
      userType: 'U',
      isActive: true,
      changePassword: false,
    },
    validationSchema: updateValidationSchema,
    onSubmit: () => {
      // Show confirmation dialog before submitting
      setConfirmDialog(true);
    },
  });
  
  /**
   * Effect: Load user data if userId is provided in URL
   * Supports direct navigation to edit mode from user list
   */
  useEffect(() => {
    if (urlUserId) {
      searchFormik.setFieldValue('userId', urlUserId);
      handleUserSearch(urlUserId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [urlUserId]);
  
  /**
   * handleUserSearch
   * 
   * Implements PROCESS-ENTER-KEY paragraph from COUSR02C.cbl
   * Performs READ-USER-SEC-FILE operation via GET /api/admin/users/:id
   * 
   * @param {string} userId - User ID to search for
   */
  const handleUserSearch = async (userId) => {
    if (!userId || userId.trim() === '') {
      setSnackbar({
        open: true,
        message: 'User ID cannot be empty',
        severity: 'error',
      });
      return;
    }
    
    setLoading(true);
    
    try {
      // Get JWT token from localStorage (set during authentication)
      const token = localStorage.getItem('jwtToken');
      
      if (!token) {
        setSnackbar({
          open: true,
          message: 'Authentication required. Please login.',
          severity: 'error',
        });
        navigate('/login');
        return;
      }
      
      // Call GET /api/admin/users/:id endpoint
      const response = await axios.get(
        `/api/admin/users/${userId.trim()}`,
        {
          headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json',
          },
        }
      );
      
      // Successful user retrieval (DFHRESP(NORMAL) equivalent)
      const user = response.data;
      setUserData(user);
      
      // Populate edit form with retrieved data
      // Maps to COBOL: MOVE SEC-USR-FNAME TO FNAMEI OF COUSR2AI, etc.
      updateFormik.setValues({
        userId: user.userId || userId,
        firstName: user.firstName || '',
        lastName: user.lastName || '',
        password: '',
        confirmPassword: '',
        userType: user.userType || 'U',
        isActive: user.isActive !== false, // Default to true
        changePassword: false,
      });
      
      // Store last modified info if available
      if (user.lastModifiedDate || user.modifiedBy) {
        setLastModified({
          date: user.lastModifiedDate,
          by: user.modifiedBy,
        });
      }
      
      // Switch to edit phase
      setPhase('edit');
      
      // Display informational message (matches COBOL message)
      setSnackbar({
        open: true,
        message: 'Press Save button to save your updates',
        severity: 'info',
      });
      
    } catch (error) {
      // Handle errors equivalent to COBOL RESP codes
      if (error.response) {
        if (error.response.status === 404) {
          // DFHRESP(NOTFND) equivalent
          setSnackbar({
            open: true,
            message: 'User ID NOT found',
            severity: 'error',
          });
        } else if (error.response.status === 401 || error.response.status === 403) {
          // Authentication/Authorization failure
          setSnackbar({
            open: true,
            message: 'Unauthorized access. Admin privileges required.',
            severity: 'error',
          });
          navigate('/login');
        } else {
          // OTHER RESP codes
          setSnackbar({
            open: true,
            message: error.response.data?.message || 'Unable to lookup User',
            severity: 'error',
          });
        }
      } else {
        setSnackbar({
          open: true,
          message: 'Network error. Please try again.',
          severity: 'error',
        });
      }
    } finally {
      setLoading(false);
    }
  };
  
  /**
   * handleUpdate
   * 
   * Implements UPDATE-USER-INFO paragraph from COUSR02C.cbl
   * Performs UPDATE-USER-SEC-FILE operation via PUT /api/admin/users/:id
   * 
   * Validates all fields and checks for modifications before submitting
   */
  const handleUpdate = async () => {
    setConfirmDialog(false);
    setLoading(true);
    
    try {
      const token = localStorage.getItem('jwtToken');
      
      if (!token) {
        setSnackbar({
          open: true,
          message: 'Authentication required. Please login.',
          severity: 'error',
        });
        navigate('/login');
        return;
      }
      
      // Build update payload
      // Only include password if changePassword is checked (optional update)
      const updatePayload = {
        userId: updateFormik.values.userId,
        firstName: updateFormik.values.firstName.trim(),
        lastName: updateFormik.values.lastName.trim(),
        userType: updateFormik.values.userType,
        isActive: updateFormik.values.isActive,
      };
      
      // Conditionally add password if being changed
      // Implements COBOL: IF PASSWDI OF COUSR2AI NOT = SEC-USR-PWD
      if (updateFormik.values.changePassword && updateFormik.values.password) {
        updatePayload.password = updateFormik.values.password;
      }
      
      // Check if any fields were actually modified
      // Implements COBOL WS-USR-MODIFIED flag logic
      let isModified = false;
      
      if (userData) {
        if (userData.firstName !== updatePayload.firstName ||
            userData.lastName !== updatePayload.lastName ||
            userData.userType !== updatePayload.userType ||
            userData.isActive !== updatePayload.isActive ||
            updateFormik.values.changePassword) {
          isModified = true;
        }
      } else {
        isModified = true;
      }
      
      if (!isModified) {
        // No modifications detected (USR-MODIFIED-NO)
        setSnackbar({
          open: true,
          message: 'Please modify to update',
          severity: 'warning',
        });
        setLoading(false);
        return;
      }
      
      // Call PUT /api/admin/users/:id endpoint
      await axios.put(
        `/api/admin/users/${updateFormik.values.userId}`,
        updatePayload,
        {
          headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json',
          },
        }
      );
      
      // Successful update (DFHRESP(NORMAL) equivalent)
      setSnackbar({
        open: true,
        message: `User ${updateFormik.values.userId} has been updated`,
        severity: 'success',
      });
      
      // Wait briefly to show success message, then navigate back
      setTimeout(() => {
        handleCancel();
      }, 2000);
      
    } catch (error) {
      // Handle update errors
      if (error.response) {
        if (error.response.status === 404) {
          // DFHRESP(NOTFND) equivalent
          setSnackbar({
            open: true,
            message: 'User ID NOT found',
            severity: 'error',
          });
        } else if (error.response.status === 401 || error.response.status === 403) {
          setSnackbar({
            open: true,
            message: 'Unauthorized access. Admin privileges required.',
            severity: 'error',
          });
          navigate('/login');
        } else if (error.response.status === 400) {
          // Validation error
          setSnackbar({
            open: true,
            message: error.response.data?.message || 'Validation error. Please check your inputs.',
            severity: 'error',
          });
        } else {
          // OTHER RESP codes
          setSnackbar({
            open: true,
            message: error.response.data?.message || 'Unable to Update User',
            severity: 'error',
          });
        }
      } else {
        setSnackbar({
          open: true,
          message: 'Network error. Please try again.',
          severity: 'error',
        });
      }
    } finally {
      setLoading(false);
    }
  };
  
  /**
   * handleClear
   * 
   * Implements CLEAR-CURRENT-SCREEN paragraph from COUSR02C.cbl
   * Resets form to search phase
   */
  const handleClear = () => {
    // INITIALIZE-ALL-FIELDS equivalent
    searchFormik.resetForm();
    updateFormik.resetForm();
    setUserData(null);
    setLastModified(null);
    setPhase('search');
    setSnackbar({ open: false, message: '', severity: 'info' });
  };
  
  /**
   * handleCancel
   * 
   * Implements DFHPF3 and DFHPF12 navigation from COUSR02C.cbl
   * Returns to user list (RETURN-TO-PREV-SCREEN paragraph)
   */
  const handleCancel = () => {
    // Navigate back to admin user list
    // Equivalent to COBOL: MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
    navigate('/admin/users');
  };
  
  /**
   * handleChangePasswordToggle
   * 
   * Toggle password change checkbox
   * Conditionally shows/hides password fields
   */
  const handleChangePasswordToggle = (event) => {
    const checked = event.target.checked;
    updateFormik.setFieldValue('changePassword', checked);
    
    // Clear password fields when unchecking
    if (!checked) {
      updateFormik.setFieldValue('password', '');
      updateFormik.setFieldValue('confirmPassword', '');
    }
  };
  
  /**
   * handleSnackbarClose
   * 
   * Close snackbar notification
   */
  const handleSnackbarClose = () => {
    setSnackbar({ ...snackbar, open: false });
  };
  
  /**
   * Render: Search Phase
   * 
   * Displays User ID search field (USRIDIN from COUSR02M.bms POS=(6,21))
   */
  const renderSearchPhase = () => {
    return (
      <Box component="form" onSubmit={searchFormik.handleSubmit} noValidate>
        <Typography variant="h5" component="h2" gutterBottom align="center" sx={{ mb: 3 }}>
          Update User
        </Typography>
        
        <Grid container spacing={3}>
          <Grid item xs={12}>
            <TextField
              fullWidth
              id="userId"
              name="userId"
              label="Enter User ID"
              placeholder="User ID (8 characters)"
              value={searchFormik.values.userId}
              onChange={searchFormik.handleChange}
              onBlur={searchFormik.handleBlur}
              error={searchFormik.touched.userId && Boolean(searchFormik.errors.userId)}
              helperText={searchFormik.touched.userId && searchFormik.errors.userId}
              disabled={loading}
              autoFocus
              InputProps={{
                endAdornment: (
                  <InputAdornment position="end">
                    <SearchIcon color="action" />
                  </InputAdornment>
                ),
              }}
            />
          </Grid>
          
          <Grid item xs={12}>
            <Box sx={{ display: 'flex', gap: 2, justifyContent: 'center' }}>
              <Button
                type="submit"
                variant="contained"
                color="primary"
                startIcon={loading ? <CircularProgress size={20} /> : <SearchIcon />}
                disabled={loading}
              >
                {loading ? 'Searching...' : 'Fetch User'}
              </Button>
              
              <Button
                variant="outlined"
                color="secondary"
                startIcon={<CancelIcon />}
                onClick={handleCancel}
                disabled={loading}
              >
                Cancel
              </Button>
            </Box>
          </Grid>
        </Grid>
      </Box>
    );
  };
  
  /**
   * Render: Edit Phase
   * 
   * Displays user detail form with fields from COUSR02M.bms:
   * - FNAME (POS=(11,18) LENGTH=20)
   * - LNAME (POS=(11,56) LENGTH=20)
   * - PASSWD (POS=(13,16) LENGTH=8 DRK)
   * - USRTYPE (POS=(15,17) LENGTH=1)
   */
  const renderEditPhase = () => {
    return (
      <Box component="form" onSubmit={updateFormik.handleSubmit} noValidate>
        <Typography variant="h5" component="h2" gutterBottom align="center" sx={{ mb: 2 }}>
          Update User
        </Typography>
        
        {/* Display last modified info if available */}
        {lastModified && (lastModified.date || lastModified.by) && (
          <Typography variant="caption" color="text.secondary" align="center" display="block" sx={{ mb: 2 }}>
            {lastModified.date && `Last modified: ${new Date(lastModified.date).toLocaleString()}`}
            {lastModified.by && ` by ${lastModified.by}`}
          </Typography>
        )}
        
        {/* Separator line (matches BMS DFHMDF at POS=(8,6) LENGTH=70 YELLOW) */}
        <Divider sx={{ my: 2, borderColor: 'warning.main', borderWidth: 1 }} />
        
        <Grid container spacing={3}>
          {/* User ID (read-only in edit phase) */}
          <Grid item xs={12}>
            <TextField
              fullWidth
              id="userId"
              name="userId"
              label="User ID"
              value={updateFormik.values.userId}
              disabled
              InputProps={{
                readOnly: true,
              }}
            />
          </Grid>
          
          {/* First Name field (FNAME from BMS) */}
          <Grid item xs={12} sm={6}>
            <TextField
              fullWidth
              id="firstName"
              name="firstName"
              label="First Name"
              placeholder="First Name (20 characters max)"
              value={updateFormik.values.firstName}
              onChange={updateFormik.handleChange}
              onBlur={updateFormik.handleBlur}
              error={updateFormik.touched.firstName && Boolean(updateFormik.errors.firstName)}
              helperText={updateFormik.touched.firstName && updateFormik.errors.firstName}
              disabled={loading}
              inputProps={{ maxLength: 20 }}
            />
          </Grid>
          
          {/* Last Name field (LNAME from BMS) */}
          <Grid item xs={12} sm={6}>
            <TextField
              fullWidth
              id="lastName"
              name="lastName"
              label="Last Name"
              placeholder="Last Name (20 characters max)"
              value={updateFormik.values.lastName}
              onChange={updateFormik.handleChange}
              onBlur={updateFormik.handleBlur}
              error={updateFormik.touched.lastName && Boolean(updateFormik.errors.lastName)}
              helperText={updateFormik.touched.lastName && updateFormik.errors.lastName}
              disabled={loading}
              inputProps={{ maxLength: 20 }}
            />
          </Grid>
          
          {/* Change Password checkbox */}
          <Grid item xs={12}>
            <FormControlLabel
              control={
                <Checkbox
                  checked={updateFormik.values.changePassword}
                  onChange={handleChangePasswordToggle}
                  name="changePassword"
                  color="primary"
                  disabled={loading}
                />
              }
              label="Change Password"
            />
          </Grid>
          
          {/* Password field (PASSWD from BMS - DRK attribute = hidden) */}
          {/* Only shown when changePassword is checked */}
          {updateFormik.values.changePassword && (
            <>
              <Grid item xs={12} sm={6}>
                <TextField
                  fullWidth
                  id="password"
                  name="password"
                  label="Password"
                  type={showPassword ? 'text' : 'password'}
                  placeholder="Password (8 characters)"
                  value={updateFormik.values.password}
                  onChange={updateFormik.handleChange}
                  onBlur={updateFormik.handleBlur}
                  error={updateFormik.touched.password && Boolean(updateFormik.errors.password)}
                  helperText={updateFormik.touched.password && updateFormik.errors.password}
                  disabled={loading}
                  inputProps={{ maxLength: 8 }}
                  InputProps={{
                    endAdornment: (
                      <InputAdornment position="end">
                        <IconButton
                          aria-label="toggle password visibility"
                          onClick={() => setShowPassword(!showPassword)}
                          edge="end"
                        >
                          {showPassword ? <VisibilityOff /> : <Visibility />}
                        </IconButton>
                      </InputAdornment>
                    ),
                  }}
                />
              </Grid>
              
              {/* Confirm Password field */}
              <Grid item xs={12} sm={6}>
                <TextField
                  fullWidth
                  id="confirmPassword"
                  name="confirmPassword"
                  label="Confirm Password"
                  type={showConfirmPassword ? 'text' : 'password'}
                  placeholder="Confirm Password"
                  value={updateFormik.values.confirmPassword}
                  onChange={updateFormik.handleChange}
                  onBlur={updateFormik.handleBlur}
                  error={updateFormik.touched.confirmPassword && Boolean(updateFormik.errors.confirmPassword)}
                  helperText={updateFormik.touched.confirmPassword && updateFormik.errors.confirmPassword}
                  disabled={loading}
                  inputProps={{ maxLength: 8 }}
                  InputProps={{
                    endAdornment: (
                      <InputAdornment position="end">
                        <IconButton
                          aria-label="toggle confirm password visibility"
                          onClick={() => setShowConfirmPassword(!showConfirmPassword)}
                          edge="end"
                        >
                          {showConfirmPassword ? <VisibilityOff /> : <Visibility />}
                        </IconButton>
                      </InputAdornment>
                    ),
                  }}
                />
              </Grid>
            </>
          )}
          
          {/* User Type field (USRTYPE from BMS) */}
          {/* Implements COBOL 88-level: SEC-USR-TYPE VALUE 'A'/'U' */}
          <Grid item xs={12} sm={6}>
            <FormControl 
              fullWidth
              error={updateFormik.touched.userType && Boolean(updateFormik.errors.userType)}
            >
              <InputLabel id="userType-label">User Type</InputLabel>
              <Select
                labelId="userType-label"
                id="userType"
                name="userType"
                value={updateFormik.values.userType}
                onChange={updateFormik.handleChange}
                onBlur={updateFormik.handleBlur}
                label="User Type"
                disabled={loading}
              >
                <MenuItem value="A">A - Admin</MenuItem>
                <MenuItem value="U">U - User</MenuItem>
              </Select>
              {updateFormik.touched.userType && updateFormik.errors.userType && (
                <FormHelperText>{updateFormik.errors.userType}</FormHelperText>
              )}
            </FormControl>
          </Grid>
          
          {/* Account Status toggle */}
          <Grid item xs={12} sm={6}>
            <FormControl fullWidth>
              <FormControlLabel
                control={
                  <Switch
                    checked={updateFormik.values.isActive}
                    onChange={(e) => updateFormik.setFieldValue('isActive', e.target.checked)}
                    name="isActive"
                    color="primary"
                    disabled={loading}
                  />
                }
                label={updateFormik.values.isActive ? 'Account Active' : 'Account Inactive'}
              />
            </FormControl>
          </Grid>
          
          {/* Action buttons */}
          <Grid item xs={12}>
            <Divider sx={{ my: 2 }} />
            <Box sx={{ display: 'flex', gap: 2, justifyContent: 'center', flexWrap: 'wrap' }}>
              {/* Save button (F5 equivalent from BMS) */}
              <Button
                type="submit"
                variant="contained"
                color="primary"
                startIcon={loading ? <CircularProgress size={20} /> : <SaveIcon />}
                disabled={loading || !updateFormik.isValid}
              >
                {loading ? 'Saving...' : 'Save'}
              </Button>
              
              {/* Clear button (F4 equivalent from BMS) */}
              <Button
                variant="outlined"
                color="secondary"
                startIcon={<EditIcon />}
                onClick={handleClear}
                disabled={loading}
              >
                Clear & Search Another
              </Button>
              
              {/* Cancel button (F3/F12 equivalent from BMS) */}
              <Button
                variant="outlined"
                color="error"
                startIcon={<CancelIcon />}
                onClick={handleCancel}
                disabled={loading}
              >
                Cancel
              </Button>
            </Box>
          </Grid>
        </Grid>
        
        {/* Help text (matches BMS bottom line instructions) */}
        <Typography 
          variant="caption" 
          color="text.secondary" 
          align="center" 
          display="block" 
          sx={{ mt: 3 }}
        >
          ENTER=Save | F3=Cancel | F4=Clear
        </Typography>
      </Box>
    );
  };
  
  /**
   * Main component render
   */
  return (
    <Container maxWidth="md">
      <Box sx={{ mt: 4, mb: 4 }}>
        <Paper elevation={3} sx={{ p: 4 }}>
          {phase === 'search' ? renderSearchPhase() : renderEditPhase()}
        </Paper>
      </Box>
      
      {/* Confirmation dialog before update */}
      <Dialog
        open={confirmDialog}
        onClose={() => setConfirmDialog(false)}
        aria-labelledby="confirm-dialog-title"
        aria-describedby="confirm-dialog-description"
      >
        <DialogTitle id="confirm-dialog-title">
          Confirm User Update
        </DialogTitle>
        <DialogContent>
          <DialogContentText id="confirm-dialog-description">
            Are you sure you want to update user <strong>{updateFormik.values.userId}</strong>?
            {updateFormik.values.changePassword && (
              <>
                <br />
                <strong>Note:</strong> The password will be changed.
              </>
            )}
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmDialog(false)} color="secondary">
            Cancel
          </Button>
          <Button onClick={handleUpdate} color="primary" variant="contained" autoFocus>
            Confirm Update
          </Button>
        </DialogActions>
      </Dialog>
      
      {/* Snackbar for notifications */}
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
    </Container>
  );
};

export default UserUpdateComponent;
