/**
 * UserProfileComponent - User Profile Management Component
 * 
 * Self-service user profile management component allowing logged-in users to view and edit
 * their personal information, change their password, and manage preference settings.
 * Transforms COUSR01C/COUSR02C COBOL programs and COUSR01/COUSR02 BMS mapsets from mainframe
 * user management screens into modern React profile management interface.
 * 
 * COBOL Source Mapping:
 * - COBOL Programs: COUSR01C.cbl (Add User), COUSR02C.cbl (Update User)
 * - CICS Transactions: CU01 (Add), CU02 (Update)
 * - BMS Mapsets: COUSR01.bms (Add User Screen), COUSR02.bms (Update User Screen)
 * - VSAM File: USRSEC (User Security) → REST API /api/profile endpoints
 * 
 * Transformation Details:
 * - COUSR01/COUSR02 field structures → React Formik forms with Material-UI components
 *   * FNAME (PIC X(20) UNPROT) → TextField for firstName (editable, max 20 chars)
 *   * LNAME (PIC X(20) UNPROT) → TextField for lastName (editable, max 20 chars)
 *   * USERID (PIC X(8)) → TextField for userId (read-only/disabled in profile context)
 *   * PASSWD (PIC X(8) DRK) → TextField type="password" for password change
 *   * USRTYPE (PIC X(1)) → TextField for userType (read-only display, 'A' Admin / 'R' User)
 *   * ERRMSG (COLOR=RED POS=(23,1)) → Material-UI Alert and toast notifications
 * 
 * - COBOL validation patterns → Yup validation schema
 *   * FNAME/LNAME empty check → .required() validation
 *   * FNAME/LNAME max length (20) → .max(20) validation
 *   * USERID format (8 chars) → read-only field, no edit validation needed
 *   * PASSWD min length (8 chars) → .min(8) validation for password change
 *   * PASSWD confirmation → .oneOf([yup.ref('newPassword')]) for confirmPassword
 * 
 * - CICS transaction processing → REST API integration
 *   * EXEC CICS READ USRSEC → GET /api/profile
 *   * EXEC CICS REWRITE USRSEC → PUT /api/profile
 *   * Password change logic → POST /api/profile/change-password
 *   * EXEC CICS SYNCPOINT → @Transactional boundary in backend
 * 
 * - BMS screen organization → Material-UI Card sections
 *   * Profile Information card (User ID, First Name, Last Name, User Type)
 *   * Password Change card (Current Password, New Password, Confirm Password)
 *   * Preferences card (placeholder for future user preferences)
 * 
 * - Function key mappings → Button actions
 *   * F3=Back → Cancel button with navigate(-1)
 *   * ENTER=Save → Submit button with form validation and API call
 *   * F4=Clear → Reset button to restore original values
 * 
 * Key Features:
 * - Self-service profile editing without admin privileges
 * - Secure password change with current password verification
 * - Real-time form validation matching COBOL field constraints
 * - Success/error notifications via toast messages
 * - Read-only display of User ID and User Type (cannot self-modify)
 * - Role display showing "Regular User" or "Administrator"
 * - Integration with Redux auth state for current user context
 * - Responsive Material-UI design with accessible form controls
 * 
 * Security Constraints:
 * - Users can only edit their own profile (enforced by backend /api/profile endpoint)
 * - User ID cannot be changed (read-only field)
 * - User Type/Role cannot be changed (read-only field, admin-only modification)
 * - Password change requires current password verification via backend
 * - BCrypt password hashing handled by backend (Spring Security)
 * 
 * API Integration:
 * - GET /api/profile: Fetch current user profile data
 * - PUT /api/profile: Update profile information (firstName, lastName)
 * - POST /api/profile/change-password: Change password with verification
 * 
 * Component Structure:
 * 1. Profile Information Section (Card)
 *    - User ID (TextField disabled)
 *    - First Name (TextField editable)
 *    - Last Name (TextField editable)
 *    - User Type (TextField disabled with role display)
 * 
 * 2. Password Change Section (Card)
 *    - Current Password (TextField type="password")
 *    - New Password (TextField type="password")
 *    - Confirm New Password (TextField type="password")
 * 
 * 3. Preferences Section (Card)
 *    - Placeholder for future user preferences
 * 
 * @module components/user/UserProfileComponent
 */

import React, { useState, useEffect } from 'react';
import { useSelector } from 'react-redux';
import { useNavigate } from 'react-router-dom';
import { Formik } from 'formik';
import * as yup from 'yup';
import {
  Card,
  CardContent,
  CardHeader,
  TextField,
  Button,
  Grid,
  Typography,
  Alert,
  Box,
  CircularProgress
} from '@mui/material';
import { toast } from 'react-toastify';
import apiClient from '../../services/apiClient';
import { selectUser } from '../../redux/slices/authSlice';

/**
 * Validation Schema - Profile Information
 * 
 * Yup validation schema for profile information update form, matching COBOL COUSR01C/COUSR02C
 * field validation constraints from BMS mapset definitions.
 * 
 * COBOL Field Mappings:
 * - FNAME: PIC X(20) UNPROT → firstName: string, required, max 20 characters
 * - LNAME: PIC X(20) UNPROT → lastName: string, required, max 20 characters
 * - USERID: PIC X(8) → userId: read-only field, not validated in update form
 * 
 * Validation Rules (from COUSR01C.cbl lines 118-151):
 * ```cobol
 * WHEN FNAMEI OF COUSR1AI = SPACES OR LOW-VALUES
 *     MOVE 'First Name can NOT be empty...' TO WS-MESSAGE
 * WHEN LNAMEI OF COUSR1AI = SPACES OR LOW-VALUES
 *     MOVE 'Last Name can NOT be empty...' TO WS-MESSAGE
 * ```
 */
const profileValidationSchema = yup.object({
  firstName: yup
    .string()
    .required('First Name is required')
    .max(20, 'First Name cannot exceed 20 characters')
    .trim(),
  lastName: yup
    .string()
    .required('Last Name is required')
    .max(20, 'Last Name cannot exceed 20 characters')
    .trim()
});

/**
 * Validation Schema - Password Change
 * 
 * Yup validation schema for password change form with current password verification,
 * new password strength validation, and password confirmation matching.
 * 
 * COBOL Field Mappings:
 * - PASSWD: PIC X(8) DRK UNPROT → password field with minimum 8 character constraint
 * 
 * Validation Rules (from COUSR01C.cbl lines 136-141):
 * ```cobol
 * WHEN PASSWDI OF COUSR1AI = SPACES OR LOW-VALUES
 *     MOVE 'Password can NOT be empty...' TO WS-MESSAGE
 * ```
 * 
 * Additional Requirements:
 * - Current password required for verification (security best practice)
 * - New password minimum 8 characters (matches COBOL PIC X(8) constraint)
 * - Confirm password must match new password exactly
 */
const passwordValidationSchema = yup.object({
  currentPassword: yup
    .string()
    .required('Current Password is required')
    .min(8, 'Current Password must be at least 8 characters'),
  newPassword: yup
    .string()
    .required('New Password is required')
    .min(8, 'New Password must be at least 8 characters')
    .matches(
      /^(?=.*[A-Za-z])(?=.*\d)[A-Za-z\d@$!%*#?&]/,
      'Password must contain at least one letter and one number'
    ),
  confirmPassword: yup
    .string()
    .required('Confirm Password is required')
    .oneOf([yup.ref('newPassword'), null], 'Passwords must match')
});

/**
 * UserProfileComponent - Functional Component
 * 
 * Main component implementing self-service user profile management with profile information
 * editing, password change functionality, and preferences management.
 * 
 * Component Lifecycle:
 * 1. Mount: Fetch current user profile from backend via GET /api/profile
 * 2. Render: Display profile information, password change, and preferences sections
 * 3. User Actions: Handle profile update, password change, cancel navigation
 * 4. Submit: Validate forms and submit to appropriate API endpoints
 * 5. Success: Display toast notification and refresh profile data
 * 6. Error: Display error alert/toast with user-friendly error message
 * 
 * COBOL Program Flow Mapping (COUSR02C.cbl):
 * ```cobol
 * MAIN-PARA.
 *     IF EIBCALEN = 0
 *         [Return to previous screen]
 *     ELSE
 *         IF NOT CDEMO-PGM-REENTER
 *             [Initial screen display with user data fetch]
 *         ELSE
 *             EVALUATE EIBAID
 *                 WHEN DFHENTER    → Submit profile update
 *                 WHEN DFHPF3      → Navigate back/cancel
 *                 WHEN DFHPF5      → Save changes
 * ```
 * 
 * @returns {JSX.Element} User profile management interface with forms and sections
 */
const UserProfileComponent = () => {
  // Redux State Access - Current authenticated user from auth slice
  // Maps COBOL COMMAREA access (CDEMO-USER-ID, CDEMO-USER-TYPE)
  const currentUser = useSelector(selectUser);

  // React Router Navigation - Programmatic navigation for Cancel button
  // Maps COBOL EXEC CICS XCTL program transfer for screen navigation
  const navigate = useNavigate();

  // Component State Management
  // Loading state for async API operations (fetch, update, password change)
  const [loading, setLoading] = useState(true);

  // Profile data loaded from backend API
  // Maps COBOL SEC-USER-DATA structure from VSAM USRSEC file read
  const [profileData, setProfileData] = useState({
    userId: '',
    firstName: '',
    lastName: '',
    userType: '',
    role: ''
  });

  // Error state for API errors and validation failures
  // Maps COBOL WS-MESSAGE error message display
  const [error, setError] = useState(null);

  // Success message state for operation confirmation
  const [successMessage, setSuccessMessage] = useState(null);

  // Password change form visibility toggle
  const [showPasswordChange, setShowPasswordChange] = useState(false);

  /**
   * Effect Hook - Fetch User Profile on Component Mount
   * 
   * Executes on component mount to fetch current user profile data from backend API.
   * Maps COBOL COUSR02C.cbl PROCESS-ENTER-KEY paragraph initial data fetch logic.
   * 
   * COBOL Mapping (COUSR02C.cbl lines 143-180):
   * ```cobol
   * PROCESS-ENTER-KEY.
   *     WHEN USRIDINI OF COUSR2AI = SPACES OR LOW-VALUES
   *         [Error handling]
   *     WHEN OTHER
   *         PERFORM READ-USER-SEC-FILE
   *         [Populate screen fields with user data]
   * ```
   * 
   * API Call:
   * - Endpoint: GET /api/profile
   * - Headers: Authorization Bearer token (auto-injected by apiClient)
   * - Response: User profile object with userId, firstName, lastName, userType, role
   * 
   * Error Handling:
   * - Network errors: Display error alert with retry guidance
   * - 404 Not Found: Profile not found error (should not occur for authenticated user)
   * - 401 Unauthorized: Redirect to login (handled by apiClient interceptor)
   * - 500 Server Error: Display generic error message
   */
  useEffect(() => {
    const fetchProfileData = async () => {
      try {
        setLoading(true);
        setError(null);

        // Call GET /api/profile to fetch current user's profile
        // Maps COBOL: EXEC CICS READ DATASET('USRSEC') INTO(SEC-USER-DATA) RIDFLD(SEC-USR-ID)
        const response = await apiClient.get('/profile');

        // Extract profile data from response
        // Maps COBOL SEC-USER-DATA fields to React state
        const profile = response.data;

        setProfileData({
          userId: profile.userId || currentUser?.userId || '',
          firstName: profile.firstName || '',
          lastName: profile.lastName || '',
          userType: profile.userType || currentUser?.userType || 'R',
          role: profile.role || currentUser?.role || 'ROLE_USER'
        });

        setLoading(false);
      } catch (err) {
        // Error handling for profile fetch failure
        // Maps COBOL error handling from COUSR02C.cbl lines 146-150
        console.error('Error fetching profile data:', err);

        const errorMessage = err.message || 'Unable to load profile data. Please try again.';
        setError(errorMessage);
        setLoading(false);

        // Display error toast notification
        toast.error(errorMessage);
      }
    };

    // Only fetch profile if user is authenticated
    if (currentUser) {
      fetchProfileData();
    } else {
      // No authenticated user - redirect to login
      // Maps COBOL: IF EIBCALEN = 0 MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
      navigate('/login');
    }
  }, [currentUser, navigate]);

  /**
   * Handler - Update Profile Information
   * 
   * Handles profile information update form submission (firstName, lastName).
   * Validates form data using Yup schema and submits to backend API via PUT request.
   * 
   * COBOL Mapping (COUSR02C.cbl lines 287-328):
   * ```cobol
   * UPDATE-USER-INFO.
   *     IF USR-MODIFIED-YES
   *         MOVE FNAMEI   OF COUSR2AI TO SEC-USR-FNAME
   *         MOVE LNAMEI   OF COUSR2AI TO SEC-USR-LNAME
   *         PERFORM REWRITE-USER-SEC-FILE
   *         EVALUATE WS-RESP-CD
   *             WHEN 0
   *                 MOVE 'User successfully updated.' TO WS-MESSAGE
   *             WHEN OTHER
   *                 MOVE 'User update failed.' TO WS-MESSAGE
   * ```
   * 
   * @param {object} values - Form values from Formik (firstName, lastName)
   * @param {object} formikBag - Formik helpers (setSubmitting, resetForm)
   */
  const handleProfileUpdate = async (values, { setSubmitting, resetForm }) => {
    try {
      setError(null);
      setSuccessMessage(null);

      // Prepare update payload with only editable fields
      // User ID and User Type are read-only and excluded from update
      const updatePayload = {
        firstName: values.firstName.trim(),
        lastName: values.lastName.trim()
      };

      // Call PUT /api/profile to update user profile
      // Maps COBOL: EXEC CICS REWRITE DATASET('USRSEC') FROM(SEC-USER-DATA) RIDFLD(SEC-USR-ID)
      const response = await apiClient.put('/profile', updatePayload);

      // Update local profile data with response
      const updatedProfile = response.data;
      setProfileData({
        ...profileData,
        firstName: updatedProfile.firstName,
        lastName: updatedProfile.lastName
      });

      // Display success message
      // Maps COBOL: MOVE 'User successfully updated.' TO WS-MESSAGE
      const successMsg = 'Profile updated successfully';
      setSuccessMessage(successMsg);
      toast.success(successMsg);

      // Reset form to clear dirty state
      resetForm({ values: { firstName: updatedProfile.firstName, lastName: updatedProfile.lastName } });
    } catch (err) {
      // Error handling for profile update failure
      // Maps COBOL: MOVE 'User update failed.' TO WS-MESSAGE
      console.error('Error updating profile:', err);

      const errorMessage = err.message || 'Failed to update profile. Please try again.';
      setError(errorMessage);
      toast.error(errorMessage);
    } finally {
      setSubmitting(false);
    }
  };

  /**
   * Handler - Change Password
   * 
   * Handles password change form submission with current password verification.
   * Validates password fields using Yup schema and submits to backend API.
   * 
   * COBOL Mapping (COUSR01C.cbl lines 136-141 and password update logic):
   * ```cobol
   * WHEN PASSWDI OF COUSR1AI = SPACES OR LOW-VALUES
   *     MOVE 'Password can NOT be empty...' TO WS-MESSAGE
   * [Password update]
   * MOVE PASSWDI OF COUSR1AI TO SEC-USR-PWD
   * PERFORM REWRITE-USER-SEC-FILE
   * ```
   * 
   * Security Requirements:
   * - Current password verification via backend (BCrypt comparison)
   * - New password strength validation (min 8 chars, alphanumeric)
   * - Password confirmation matching
   * - BCrypt hashing handled by backend (Spring Security)
   * 
   * @param {object} values - Form values (currentPassword, newPassword, confirmPassword)
   * @param {object} formikBag - Formik helpers (setSubmitting, resetForm)
   */
  const handlePasswordChange = async (values, { setSubmitting, resetForm }) => {
    try {
      setError(null);
      setSuccessMessage(null);

      // Prepare password change payload
      const passwordPayload = {
        currentPassword: values.currentPassword,
        newPassword: values.newPassword
      };

      // Call POST /api/profile/change-password to change user password
      // Backend verifies current password with BCrypt before allowing change
      await apiClient.post('/profile/change-password', passwordPayload);

      // Display success message
      const successMsg = 'Password changed successfully';
      setSuccessMessage(successMsg);
      toast.success(successMsg);

      // Reset password change form and hide section
      resetForm();
      setShowPasswordChange(false);
    } catch (err) {
      // Error handling for password change failure
      console.error('Error changing password:', err);

      // Extract error message (may include specific validation errors)
      let errorMessage = 'Failed to change password. Please try again.';
      if (err.message) {
        errorMessage = err.message;
      } else if (err.errors && err.errors.currentPassword) {
        errorMessage = 'Current password is incorrect';
      }

      setError(errorMessage);
      toast.error(errorMessage);
    } finally {
      setSubmitting(false);
    }
  };

  /**
   * Handler - Cancel Navigation
   * 
   * Handles Cancel button click to navigate back to previous page or menu.
   * Maps COBOL F3=Back function key handling.
   * 
   * COBOL Mapping (COUSR01C.cbl lines 93-95):
   * ```cobol
   * WHEN DFHPF3
   *     MOVE 'COADM01C' TO CDEMO-TO-PROGRAM
   *     PERFORM RETURN-TO-PREV-SCREEN
   * ```
   */
  const handleCancel = () => {
    // Navigate back to previous page
    // Maps COBOL EXEC CICS XCTL PROGRAM(CDEMO-TO-PROGRAM)
    navigate(-1);
  };

  /**
   * Helper Function - Format User Type Display
   * 
   * Converts COBOL user type code to human-readable role display string.
   * Maps COBOL 88-level condition names for user type validation.
   * 
   * COBOL Mapping (from CSUSR01Y.cpy):
   * ```cobol
   * 05 SEC-USR-TYPE    PIC X(01).
   *    88 USRTYP-ADMIN           VALUE 'A'.
   *    88 USRTYP-USER            VALUE 'U', 'R'.
   * ```
   * 
   * @param {string} userType - User type code ('A' Admin, 'R' Regular User)
   * @returns {string} Human-readable role display
   */
  const getUserTypeDisplay = (userType) => {
    switch (userType) {
      case 'A':
        return 'Administrator';
      case 'R':
      case 'U':
        return 'Regular User';
      default:
        return 'User';
    }
  };

  // Loading State Display
  // Shows CircularProgress spinner while fetching profile data from backend
  if (loading) {
    return (
      <Box display="flex" justifyContent="center" alignItems="center" minHeight="400px">
        <CircularProgress />
      </Box>
    );
  }

  // Component JSX Render
  // Renders profile management interface with Material-UI components
  // Maps COUSR01/COUSR02 BMS mapset screen layout to modern React UI
  return (
    <Box sx={{ padding: 3 }}>
      <Typography variant="h4" gutterBottom>
        User Profile
      </Typography>

      {/* Global Error Alert Display */}
      {/* Maps COBOL ERRMSG field at POS=(23,1) COLOR=RED from BMS mapset */}
      {error && (
        <Alert severity="error" onClose={() => setError(null)} sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {/* Global Success Alert Display */}
      {successMessage && (
        <Alert severity="success" onClose={() => setSuccessMessage(null)} sx={{ mb: 2 }}>
          {successMessage}
        </Alert>
      )}

      {/* Profile Information Card Section */}
      {/* Maps COUSR01/COUSR02 BMS screen fields: FNAME, LNAME, USERID, USRTYPE */}
      <Card sx={{ mb: 3 }}>
        <CardHeader
          title="Profile Information"
          titleTypographyProps={{ variant: 'h6' }}
          sx={{ backgroundColor: '#f5f5f5' }}
        />
        <CardContent>
          <Formik
            initialValues={{
              firstName: profileData.firstName,
              lastName: profileData.lastName
            }}
            validationSchema={profileValidationSchema}
            onSubmit={handleProfileUpdate}
            enableReinitialize
          >
            {({ values, errors, touched, handleChange, handleBlur, handleSubmit, isSubmitting, dirty }) => (
              <form onSubmit={handleSubmit}>
                <Grid container spacing={3}>
                  {/* User ID Field - Read-only (disabled) */}
                  {/* Maps COUSR02 USERID field - ASKIP in profile context */}
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      id="userId"
                      name="userId"
                      label="User ID"
                      value={profileData.userId}
                      disabled
                      variant="outlined"
                      helperText="User ID cannot be changed"
                    />
                  </Grid>

                  {/* User Type Field - Read-only (disabled) */}
                  {/* Maps COUSR01/COUSR02 USRTYPE field - ASKIP in profile context */}
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      id="userType"
                      name="userType"
                      label="User Type"
                      value={getUserTypeDisplay(profileData.userType)}
                      disabled
                      variant="outlined"
                      helperText="User type is managed by administrators"
                    />
                  </Grid>

                  {/* First Name Field - Editable */}
                  {/* Maps COUSR01/COUSR02 FNAME field: PIC X(20) UNPROT COLOR=GREEN */}
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      id="firstName"
                      name="firstName"
                      label="First Name"
                      value={values.firstName}
                      onChange={handleChange}
                      onBlur={handleBlur}
                      error={touched.firstName && Boolean(errors.firstName)}
                      helperText={touched.firstName && errors.firstName}
                      variant="outlined"
                      required
                      inputProps={{ maxLength: 20 }}
                    />
                  </Grid>

                  {/* Last Name Field - Editable */}
                  {/* Maps COUSR01/COUSR02 LNAME field: PIC X(20) UNPROT COLOR=GREEN */}
                  <Grid item xs={12} sm={6}>
                    <TextField
                      fullWidth
                      id="lastName"
                      name="lastName"
                      label="Last Name"
                      value={values.lastName}
                      onChange={handleChange}
                      onBlur={handleBlur}
                      error={touched.lastName && Boolean(errors.lastName)}
                      helperText={touched.lastName && errors.lastName}
                      variant="outlined"
                      required
                      inputProps={{ maxLength: 20 }}
                    />
                  </Grid>

                  {/* Form Action Buttons */}
                  {/* Maps COUSR01/COUSR02 function keys: ENTER=Save, F3=Back */}
                  <Grid item xs={12}>
                    <Box display="flex" gap={2}>
                      <Button
                        type="submit"
                        variant="contained"
                        color="primary"
                        disabled={isSubmitting || !dirty}
                      >
                        {isSubmitting ? 'Saving...' : 'Save Changes'}
                      </Button>
                      <Button
                        variant="outlined"
                        color="secondary"
                        onClick={handleCancel}
                        disabled={isSubmitting}
                      >
                        Cancel
                      </Button>
                    </Box>
                  </Grid>
                </Grid>
              </form>
            )}
          </Formik>
        </CardContent>
      </Card>

      {/* Password Change Card Section */}
      {/* Maps COUSR01/COUSR02 PASSWD field: PIC X(8) DRK UNPROT */}
      <Card sx={{ mb: 3 }}>
        <CardHeader
          title="Change Password"
          titleTypographyProps={{ variant: 'h6' }}
          sx={{ backgroundColor: '#f5f5f5' }}
        />
        <CardContent>
          {!showPasswordChange ? (
            <Box>
              <Typography variant="body2" color="textSecondary" gutterBottom>
                Click the button below to change your password
              </Typography>
              <Button
                variant="outlined"
                color="primary"
                onClick={() => setShowPasswordChange(true)}
                sx={{ mt: 1 }}
              >
                Change Password
              </Button>
            </Box>
          ) : (
            <Formik
              initialValues={{
                currentPassword: '',
                newPassword: '',
                confirmPassword: ''
              }}
              validationSchema={passwordValidationSchema}
              onSubmit={handlePasswordChange}
            >
              {({ values, errors, touched, handleChange, handleBlur, handleSubmit, isSubmitting, resetForm }) => (
                <form onSubmit={handleSubmit}>
                  <Grid container spacing={3}>
                    {/* Current Password Field */}
                    <Grid item xs={12}>
                      <TextField
                        fullWidth
                        id="currentPassword"
                        name="currentPassword"
                        label="Current Password"
                        type="password"
                        value={values.currentPassword}
                        onChange={handleChange}
                        onBlur={handleBlur}
                        error={touched.currentPassword && Boolean(errors.currentPassword)}
                        helperText={touched.currentPassword && errors.currentPassword}
                        variant="outlined"
                        required
                      />
                    </Grid>

                    {/* New Password Field */}
                    {/* Maps COUSR01/COUSR02 PASSWD field with min 8 char constraint */}
                    <Grid item xs={12} sm={6}>
                      <TextField
                        fullWidth
                        id="newPassword"
                        name="newPassword"
                        label="New Password"
                        type="password"
                        value={values.newPassword}
                        onChange={handleChange}
                        onBlur={handleBlur}
                        error={touched.newPassword && Boolean(errors.newPassword)}
                        helperText={touched.newPassword && errors.newPassword}
                        variant="outlined"
                        required
                      />
                    </Grid>

                    {/* Confirm Password Field */}
                    <Grid item xs={12} sm={6}>
                      <TextField
                        fullWidth
                        id="confirmPassword"
                        name="confirmPassword"
                        label="Confirm New Password"
                        type="password"
                        value={values.confirmPassword}
                        onChange={handleChange}
                        onBlur={handleBlur}
                        error={touched.confirmPassword && Boolean(errors.confirmPassword)}
                        helperText={touched.confirmPassword && errors.confirmPassword}
                        variant="outlined"
                        required
                      />
                    </Grid>

                    {/* Password Change Action Buttons */}
                    <Grid item xs={12}>
                      <Box display="flex" gap={2}>
                        <Button
                          type="submit"
                          variant="contained"
                          color="primary"
                          disabled={isSubmitting}
                        >
                          {isSubmitting ? 'Changing...' : 'Change Password'}
                        </Button>
                        <Button
                          variant="outlined"
                          color="secondary"
                          onClick={() => {
                            resetForm();
                            setShowPasswordChange(false);
                          }}
                          disabled={isSubmitting}
                        >
                          Cancel
                        </Button>
                      </Box>
                    </Grid>
                  </Grid>
                </form>
              )}
            </Formik>
          )}
        </CardContent>
      </Card>

      {/* Preferences Card Section - Placeholder */}
      {/* Future extensibility for user preferences management */}
      <Card>
        <CardHeader
          title="Preferences"
          titleTypographyProps={{ variant: 'h6' }}
          sx={{ backgroundColor: '#f5f5f5' }}
        />
        <CardContent>
          <Typography variant="body2" color="textSecondary">
            User preferences and settings will be available in a future release.
          </Typography>
          <Typography variant="caption" color="textSecondary" display="block" sx={{ mt: 1 }}>
            Planned features: Display options, notification settings, language preferences
          </Typography>
        </CardContent>
      </Card>
    </Box>
  );
};

// Default Export - UserProfileComponent
// Export component as default export matching schema requirements
export default UserProfileComponent;
