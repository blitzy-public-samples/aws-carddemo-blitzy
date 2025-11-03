/**
 * CardDemo Login Component
 * 
 * React functional component implementing the CardDemo application sign-on screen,
 * transforming BMS COSGN00 mapset and COBOL COSGN00C.cbl authentication program
 * to a modern web login form with Material-UI styling, Formik form handling, and
 * Redux state management.
 * 
 * COBOL Source Mapping:
 * - BMS Mapset: COSGN00.bms (3270 terminal sign-on screen)
 * - COBOL Program: COSGN00C.cbl (Sign-on Screen for CardDemo Application)
 * - CICS Transaction: CC00
 * - VSAM File: USRSEC (User Security file)
 * 
 * Transformation Details:
 * - BMS 3270 screen fields → Material-UI TextField components
 * - COBOL field validation → Yup schema validation + backend validation
 * - CICS pseudo-conversational processing → RESTful authentication
 * - VSAM file authentication → JWT token-based authentication
 * - COMMAREA session state → Redux state + localStorage JWT tokens
 * - CICS XCTL program transfer → React Router navigation
 * - PF keys (ENTER, PF3) → Button onClick handlers
 * 
 * Screen Field Mappings (COSGN00.bms → React):
 * - USERID field (lines 156-160) → userId TextField with autoFocus
 * - PASSWD field (lines 175-180) → password TextField with type="password"
 * - ERRMSG field (lines 197-200) → Alert component with error display
 * - PF3=Exit → Cancel button with navigate('/')
 * - ENTER=Sign-on → Submit button with form submission
 * 
 * Validation Logic (COSGN00C.cbl → Yup + Redux):
 * - Lines 118-122: Empty userId check → Yup .required('Please enter User ID ...')
 * - Lines 123-127: Empty password check → Yup .required('Please enter Password ...')
 * - Lines 132-136: UPPER-CASE conversion → onChange handler .toUpperCase()
 * - Lines 211-257: USRSEC file authentication → Redux loginUser async thunk
 * 
 * Authentication Flow (COSGN00C.cbl lines 209-257 → Redux):
 * 1. User enters credentials (userId, password)
 * 2. Form submission triggers handleSubmit with validation
 * 3. Credentials converted to uppercase (matching COBOL FUNCTION UPPER-CASE)
 * 4. Redux loginUser thunk dispatched → calls authService.login
 * 5. Backend authenticates via POST /api/auth/login
 * 6. Success: JWT token stored, user profile in Redux state
 * 7. Navigation based on userType (Admin 'A' → /admin, Regular 'R' → /menu)
 * 8. Error: Display error message matching COBOL error messages
 * 
 * Role-Based Navigation (COSGN00C.cbl lines 230-240):
 * - userType 'A' (Admin) → navigate('/admin') [COBOL: XCTL PROGRAM('COADM01C')]
 * - userType 'R' (Regular) → navigate('/menu') [COBOL: XCTL PROGRAM('COMEN01C')]
 * 
 * Error Messages (COSGN00C.cbl):
 * - Line 120: "Please enter User ID ..." - Empty userId
 * - Line 125: "Please enter Password ..." - Empty password
 * - Line 242: "Wrong Password. Try again ..." - Password mismatch
 * - Line 249: "User not found. Try again ..." - User not found (RESP 13)
 * - Line 254: "Unable to verify the User ..." - System error (RESP OTHER)
 * 
 * @component
 * @module components/auth/LoginComponent
 */

import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useDispatch, useSelector } from 'react-redux';
import { Formik, Form } from 'formik';
import * as Yup from 'yup';
import {
  Box,
  Container,
  TextField,
  Button,
  Typography,
  Paper,
  Alert,
  CircularProgress,
  Grid
} from '@mui/material';
import { toast } from 'react-toastify';
import { loginUser } from '../../redux/slices/authSlice';

/**
 * Yup Validation Schema
 * 
 * Defines client-side validation rules for login form fields, transforming COBOL
 * COSGN00C.cbl PROCESS-ENTER-KEY paragraph validation logic (lines 117-130) to
 * declarative Yup schema validation.
 * 
 * COBOL Mapping:
 * ```cobol
 * EVALUATE TRUE
 *     WHEN USERIDI OF COSGN0AI = SPACES OR LOW-VALUES
 *         MOVE 'Please enter User ID ...' TO WS-MESSAGE
 *     WHEN PASSWDI OF COSGN0AI = SPACES OR LOW-VALUES
 *         MOVE 'Please enter Password ...' TO WS-MESSAGE
 * END-EVALUATE
 * ```
 * 
 * Validation Rules:
 * - userId: Required, max 8 characters, alphanumeric (matches COBOL PIC X(8))
 * - password: Required, max 8 characters (matches COBOL PIC X(8))
 * 
 * BMS Field Constraints:
 * - USERID field LENGTH=8 (COSGN00.bms line 159)
 * - PASSWD field LENGTH=8 (COSGN00.bms line 178)
 */
const validationSchema = Yup.object({
  userId: Yup.string()
    .required('Please enter User ID ...')
    .max(8, 'User ID must be 8 characters or less')
    .matches(/^[A-Z0-9]+$/i, 'User ID must contain only letters and numbers'),
  password: Yup.string()
    .required('Please enter Password ...')
    .max(8, 'Password must be 8 characters or less')
});

/**
 * LoginComponent
 * 
 * Main login component rendering sign-on form with Material-UI styling, Formik
 * form handling, Yup validation, and Redux state management for authentication.
 * 
 * Component Features:
 * - Controlled form inputs with Formik state management
 * - Client-side validation with Yup schema
 * - Async authentication via Redux loginUser thunk
 * - Role-based navigation (Admin → /admin, User → /menu)
 * - Error message display with Alert component and toast notifications
 * - Auto-redirect if already authenticated
 * - Loading state with CircularProgress indicator
 * - Uppercase conversion for userId (matching COBOL FUNCTION UPPER-CASE)
 * 
 * Redux State Integration:
 * - useSelector: Access auth state (user, loading, error, isAuthenticated)
 * - useDispatch: Dispatch loginUser thunk for authentication
 * 
 * React Router Integration:
 * - useNavigate: Programmatic navigation after authentication
 * - Auto-redirect on mount if already authenticated
 * - Role-based navigation (userType determines target route)
 * 
 * @returns {JSX.Element} Login form component with Material-UI styling
 */
const LoginComponent = () => {
  // React Router navigation hook
  const navigate = useNavigate();
  
  // Redux dispatch and selector hooks
  const dispatch = useDispatch();
  const { user, loading, error, isAuthenticated } = useSelector((state) => state.auth);
  
  // Local state for error message display
  const [errorMessage, setErrorMessage] = useState('');

  /**
   * Auto-Redirect Effect
   * 
   * Automatically redirects authenticated users to appropriate dashboard based on
   * user type, preventing authenticated users from accessing login page.
   * 
   * Maps COBOL COSGN00C.cbl XCTL logic (lines 230-240):
   * - Admin users (userType 'A') → /admin [COBOL: XCTL PROGRAM('COADM01C')]
   * - Regular users (userType 'R') → /menu [COBOL: XCTL PROGRAM('COMEN01C')]
   * 
   * Dependency: [isAuthenticated, user, navigate]
   */
  useEffect(() => {
    if (isAuthenticated && user) {
      // Determine navigation path based on user type
      // Maps COBOL 88-level condition CDEMO-USRTYP-ADMIN (COCOM01Y.cpy line 27)
      const redirectPath = user.userType === 'A' ? '/admin' : '/menu';
      navigate(redirectPath);
    }
  }, [isAuthenticated, user, navigate]);

  /**
   * Form Submission Handler
   * 
   * Handles login form submission by dispatching Redux loginUser async thunk with
   * user credentials, managing loading state, handling success/error responses, and
   * navigating to appropriate dashboard based on user role.
   * 
   * COBOL Mapping (COSGN00C.cbl lines 132-257):
   * ```cobol
   * MOVE FUNCTION UPPER-CASE(USERIDI OF COSGN0AI) TO WS-USER-ID
   * MOVE FUNCTION UPPER-CASE(PASSWDI OF COSGN0AI) TO WS-USER-PWD
   * PERFORM READ-USER-SEC-FILE
   * 
   * READ-USER-SEC-FILE.
   *     EXEC CICS READ DATASET ('USRSEC') INTO (SEC-USER-DATA) RIDFLD (WS-USER-ID)
   *     EVALUATE WS-RESP-CD
   *         WHEN 0
   *             IF SEC-USR-PWD = WS-USER-PWD
   *                 MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE
   *                 IF CDEMO-USRTYP-ADMIN
   *                     EXEC CICS XCTL PROGRAM ('COADM01C')
   *                 ELSE
   *                     EXEC CICS XCTL PROGRAM ('COMEN01C')
   *                 END-IF
   *             ELSE
   *                 MOVE 'Wrong Password. Try again ...' TO WS-MESSAGE
   *             END-IF
   *         WHEN 13
   *             MOVE 'User not found. Try again ...' TO WS-MESSAGE
   *         WHEN OTHER
   *             MOVE 'Unable to verify the User ...' TO WS-MESSAGE
   *     END-EVALUATE
   * ```
   * 
   * Authentication Flow:
   * 1. Clear any existing error messages
   * 2. Convert credentials to uppercase (matching COBOL FUNCTION UPPER-CASE)
   * 3. Dispatch Redux loginUser thunk with credentials
   * 4. On success: Extract user profile, navigate based on userType, show welcome toast
   * 5. On error: Set error message, display error toast
   * 
   * @param {object} values - Formik form values {userId, password}
   * @param {object} formikBag - Formik bag with setSubmitting function
   * @param {string} values.userId - User ID from form input
   * @param {string} values.password - Password from form input
   * @param {function} formikBag.setSubmitting - Formik function to control submit button state
   */
  const handleSubmit = async (values, { setSubmitting }) => {
    try {
      // Clear any existing error messages
      setErrorMessage('');
      
      // Convert credentials to uppercase per COBOL COSGN00C.cbl lines 132-136
      // MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID
      // MOVE FUNCTION UPPER-CASE(PASSWDI) TO WS-USER-PWD
      const credentials = {
        userId: values.userId.toUpperCase(),
        password: values.password.toUpperCase()
      };

      // Dispatch Redux loginUser async thunk
      // Maps COBOL: PERFORM READ-USER-SEC-FILE (line 139)
      const resultAction = await dispatch(loginUser(credentials));
      
      // Check if login was successful
      if (loginUser.fulfilled.match(resultAction)) {
        // Extract user profile from fulfilled action payload
        const { user: authenticatedUser } = resultAction.payload;
        
        // Role-based navigation per COSGN00C.cbl lines 230-240
        if (authenticatedUser.userType === 'A') {
          // Admin user - EXEC CICS XCTL PROGRAM('COADM01C')
          navigate('/admin');
        } else {
          // Regular user - EXEC CICS XCTL PROGRAM('COMEN01C')
          navigate('/menu');
        }
        
        // Display welcome message with user's name
        // Maps COBOL success path with user context
        const userName = `${authenticatedUser.firstName || ''} ${authenticatedUser.lastName || ''}`.trim() 
          || authenticatedUser.userId;
        toast.success(`Welcome, ${userName}!`);
      } else {
        // Handle rejected action with error message
        // Maps COBOL error handling with WS-MESSAGE display (lines 242-256)
        const errorMsg = resultAction.payload || resultAction.error.message || 'Login failed';
        setErrorMessage(errorMsg);
        toast.error(errorMsg);
      }
    } catch (err) {
      // Handle unexpected errors not caught by Redux thunk
      // Maps COBOL WHEN OTHER condition (line 252-256)
      const errorMsg = err.response?.data?.message 
        || err.message 
        || 'Unable to verify the User ...';
      setErrorMessage(errorMsg);
      toast.error(errorMsg);
    } finally {
      // Always reset submitting state regardless of success/failure
      setSubmitting(false);
    }
  };

  /**
   * Cancel Button Handler
   * 
   * Handles cancel button click (PF3=Exit functionality) by navigating to
   * application root, matching COBOL COSGN00C.cbl PF3 key handling.
   * 
   * COBOL Mapping (COSGN00C.cbl lines 88-90):
   * ```cobol
   * WHEN DFHPF3
   *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
   *     PERFORM SEND-PLAIN-TEXT
   * ```
   * 
   * BMS Mapping (COSGN00.bms line 205):
   * - Function key legend: "ENTER=Sign-on  F3=Exit"
   */
  const handleCancel = () => {
    // PF3=Exit functionality - navigate to application root
    navigate('/');
  };

  return (
    <Container maxWidth="sm">
      <Box sx={{ mt: 8, display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
        <Paper elevation={3} sx={{ p: 4, width: '100%' }}>
          {/* 
            Header Section
            
            Maps BMS COSGN00.bms screen title and system information (lines 29-93)
            - TITLE01: Application title
            - TITLE02: Subtitle
            - CURDATE: Current date display
            - CURTIME: Current time display
          */}
          <Box sx={{ mb: 3, textAlign: 'center' }}>
            <Typography variant="h5" color="primary" gutterBottom>
              CardDemo - Credit Card Management
            </Typography>
            <Typography variant="caption" color="textSecondary">
              Mainframe Modernization Demo
            </Typography>
          </Box>

          {/*
            ASCII Art Banner
            
            Maps BMS COSGN00.bms decorative banner (lines 104-144)
            - Dollar bill ASCII art representing national reserve note
            - Provides visual identity matching mainframe 3270 screen design
          */}
          <Box sx={{ 
            mb: 3, 
            p: 2, 
            bgcolor: 'background.default', 
            borderRadius: 1,
            textAlign: 'center',
            fontFamily: 'monospace',
            fontSize: '0.75rem',
            color: 'text.secondary'
          }}>
            <Typography component="pre" sx={{ m: 0 }}>
              {`+========================================+\n` +
               `|%%%%%%%  NATIONAL RESERVE NOTE  %%%%%%%%|\n` +
               `|%(1)  THE UNITED STATES OF KICSLAND (1)%|\n` +
               `+========================================+`}
            </Typography>
          </Box>

          {/*
            Error Message Display
            
            Maps BMS COSGN00.bms ERRMSG field (lines 197-200)
            - COLOR=RED, BRT attributes → MUI Alert severity="error"
            - POS=(23,1) → Bottom section of form
            - Displays validation errors and authentication failures
            
            Error messages match COBOL COSGN00C.cbl WS-MESSAGE values:
            - Line 120: "Please enter User ID ..."
            - Line 125: "Please enter Password ..."
            - Line 242: "Wrong Password. Try again ..."
            - Line 249: "User not found. Try again ..."
            - Line 254: "Unable to verify the User ..."
          */}
          {(errorMessage || error) && (
            <Alert severity="error" sx={{ mb: 2 }}>
              {errorMessage || error}
            </Alert>
          )}

          {/*
            Login Form with Formik
            
            Maps BMS COSGN00.bms screen fields to React form inputs with
            Material-UI components, Formik form management, and Yup validation.
            
            Form Fields:
            - USERID (lines 156-160) → userId TextField
            - PASSWD (lines 175-180) → password TextField
            - ENTER key → Sign In button
            - PF3 key → Cancel button
          */}
          <Formik
            initialValues={{ userId: '', password: '' }}
            validationSchema={validationSchema}
            onSubmit={handleSubmit}
          >
            {({ errors, touched, isSubmitting, values, handleChange, handleBlur }) => (
              <Form>
                {/*
                  Form Instructions
                  
                  Maps BMS COSGN00.bms instruction text (lines 147-150)
                  - "Type your User ID and Password, then press ENTER:"
                */}
                <Typography variant="body2" color="textSecondary" sx={{ mb: 2, textAlign: 'center' }}>
                  Type your User ID and Password, then press Sign In
                </Typography>

                {/*
                  User ID Field
                  
                  Maps BMS COSGN00.bms USERID field (lines 156-160):
                  - ATTRB=(FSET,IC,NORM,UNPROT) → autoFocus={true}, enabled input
                  - COLOR=GREEN → MUI default input color
                  - LENGTH=8 → maxLength={8}
                  - HILIGHT=OFF → normal display
                  - POS=(19,43) → positioned in form
                  
                  COBOL COSGN00C.cbl transformation logic (lines 132-133):
                  - MOVE FUNCTION UPPER-CASE(USERIDI) TO WS-USER-ID
                  - Implemented via onChange handler with .toUpperCase()
                */}
                <TextField
                  fullWidth
                  id="userId"
                  name="userId"
                  label="User ID"
                  placeholder="(8 Char)"
                  value={values.userId}
                  onChange={(e) => {
                    // Auto-uppercase and limit to 8 characters
                    // Maps COBOL FUNCTION UPPER-CASE (COSGN00C.cbl line 132)
                    const upperValue = e.target.value.toUpperCase().slice(0, 8);
                    handleChange({ target: { name: 'userId', value: upperValue } });
                  }}
                  onBlur={handleBlur}
                  error={touched.userId && Boolean(errors.userId)}
                  helperText={touched.userId && errors.userId}
                  margin="normal"
                  autoFocus
                  inputProps={{ maxLength: 8 }}
                  sx={{ mb: 2 }}
                />

                {/*
                  Password Field
                  
                  Maps BMS COSGN00.bms PASSWD field (lines 175-180):
                  - ATTRB=(DRK,FSET,UNPROT) → type="password" (masked input)
                  - COLOR=GREEN → MUI default input color
                  - LENGTH=8 → maxLength={8}
                  - HILIGHT=OFF → normal display
                  - POS=(20,43) → positioned in form
                  - INITIAL='________' → placeholder indication
                  
                  COBOL COSGN00C.cbl transformation logic (lines 135-136):
                  - MOVE FUNCTION UPPER-CASE(PASSWDI) TO WS-USER-PWD
                  - Password stored in uppercase for comparison
                */}
                <TextField
                  fullWidth
                  id="password"
                  name="password"
                  label="Password"
                  type="password"
                  placeholder="(8 Char)"
                  value={values.password}
                  onChange={handleChange}
                  onBlur={handleBlur}
                  error={touched.password && Boolean(errors.password)}
                  helperText={touched.password && errors.password}
                  margin="normal"
                  inputProps={{ maxLength: 8 }}
                  sx={{ mb: 3 }}
                />

                {/*
                  Action Buttons
                  
                  Maps BMS COSGN00.bms function key legend (line 205):
                  - "ENTER=Sign-on  F3=Exit"
                  - ENTER key → Sign In button (type="submit")
                  - F3 key → Cancel button (onClick={handleCancel})
                  
                  Button States:
                  - Disabled during submission or loading
                  - CircularProgress indicator during async authentication
                  - Grid layout for responsive button positioning
                */}
                <Grid container spacing={2}>
                  <Grid item xs={6}>
                    {/*
                      Sign In Button (ENTER Key)
                      
                      Maps COBOL COSGN00C.cbl ENTER key handling (lines 86-87):
                      - WHEN DFHENTER → PERFORM PROCESS-ENTER-KEY
                      - Triggers form validation and authentication
                    */}
                    <Button
                      type="submit"
                      fullWidth
                      variant="contained"
                      color="primary"
                      disabled={isSubmitting || loading}
                      startIcon={loading && <CircularProgress size={20} />}
                    >
                      {loading ? 'Signing In...' : 'Sign In'}
                    </Button>
                  </Grid>
                  <Grid item xs={6}>
                    {/*
                      Cancel Button (PF3 Key)
                      
                      Maps COBOL COSGN00C.cbl PF3 key handling (lines 88-90):
                      - WHEN DFHPF3 → MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
                      - Exits sign-on screen and returns to application root
                    */}
                    <Button
                      fullWidth
                      variant="outlined"
                      color="secondary"
                      onClick={handleCancel}
                      disabled={isSubmitting || loading}
                    >
                      Cancel
                    </Button>
                  </Grid>
                </Grid>
              </Form>
            )}
          </Formik>

          {/*
            Footer with Current Date/Time
            
            Maps BMS COSGN00.bms date/time display (lines 47-74, 186-196):
            - CURDATE field: Current date in mm/dd/yy format
            - CURTIME field: Current time in hh:mm:ss format
            
            COBOL COSGN00C.cbl date/time population (lines 179-196):
            - MOVE FUNCTION CURRENT-DATE TO WS-CURDATE-DATA
            - Format date and time for display
          */}
          <Box sx={{ mt: 3, textAlign: 'center' }}>
            <Typography variant="caption" color="textSecondary">
              {new Date().toLocaleString()}
            </Typography>
          </Box>
        </Paper>
      </Box>
    </Container>
  );
};

/**
 * Component Export
 * 
 * Exports LoginComponent as default export for use in application routing.
 * 
 * Usage in App.jsx routing:
 * ```jsx
 * import LoginComponent from './components/auth/LoginComponent';
 * 
 * <Route path="/login" element={<LoginComponent />} />
 * ```
 * 
 * Integration Points:
 * - React Router: Renders at /login route
 * - Redux Store: Connects to auth slice for state management
 * - authService: Called via Redux loginUser thunk for authentication
 * - Material-UI: Provides consistent theme styling across application
 */
export default LoginComponent;
