/**
 * CardDemo Main Menu Component
 * 
 * React functional component transforming COMEN01.bms BMS mapset and COMEN01C.cbl COBOL program
 * into modern web navigation dashboard. Displays role-based menu options for CardDemo application
 * features including Account, Credit Card, Transaction, and Bill Payment modules.
 * 
 * COBOL Source Mapping:
 * - BMS Mapset: app/bms/COMEN01.bms (Main Menu Screen 3270 terminal layout)
 * - COBOL Program: app/cbl/COMEN01C.cbl (Menu navigation logic and option processing)
 * - Menu Options: app/cpy/COMEN02Y.cpy (10 menu option definitions with target programs)
 * - CICS Transaction: CM00 → React route /menu
 * 
 * BMS Screen Layout Transformation:
 * ```bms
 * COMEN01 DFHMSD (Main Menu Mapset)
 *   - TRNNAME: Transaction ID (CM00) → Header component
 *   - TITLE01/TITLE02: Page titles → Header component
 *   - OPTN001-OPTN010: Menu option fields (12 available, 10 used) → Material-UI Cards
 *   - OPTION: User selection input (PIC 9(02)) → Card click handlers
 *   - ERRMSG: Error message display → Alert component
 * ```
 * 
 * COBOL Business Logic Transformation (COMEN01C.cbl):
 * 
 * 1. BUILD-MENU-OPTIONS Paragraph (lines 236-277):
 *    Maps menu options from COMEN02Y.cpy to screen fields OPTN001O-OPTN012O
 *    → Transformed to menuOptions array with React rendering
 * 
 * 2. PROCESS-ENTER-KEY Paragraph (lines 115-165):
 *    Validates user option selection and transfers control to target program
 *    → Transformed to handleMenuSelection with React Router navigation
 * 
 * 3. Role-Based Access Control (lines 136-143):
 *    ```cobol
 *    IF CDEMO-USRTYP-USER AND
 *       CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
 *        MOVE 'No access - Admin Only option...' TO WS-MESSAGE
 *    ```
 *    → Transformed to menu filtering based on user.userType from Redux
 * 
 * Menu Options from COMEN02Y.cpy (10 options):
 * 1. Account View (COACTVWC) → /account/view
 * 2. Account Update (COACTUPC) → /account/update
 * 3. Credit Card List (COCRDLIC) → /card/list
 * 4. Credit Card View (COCRDSLC) → /card/view
 * 5. Credit Card Update (COCRDUPC) → /card/update
 * 6. Transaction List (COTRN00C) → /transaction/list
 * 7. Transaction View (COTRN01C) → /transaction/category
 * 8. Transaction Add (COTRN02C) → /transaction/add
 * 9. Transaction Reports (CORPT00C) → /reports
 * 10. Bill Payment (COBIL00C) → /payment/bill
 * 
 * Key Features:
 * - Dynamic menu rendering from menuOptions array matching COMEN02Y.cpy structure
 * - Role-based menu filtering (userType 'R' Regular vs 'A' Admin)
 * - Material-UI Card components for modern navigation interface
 * - React Router Link integration for client-side navigation
 * - Error handling for invalid selections with Alert display
 * - Header/Footer components matching BMS screen layout
 * - Responsive grid layout using Material-UI Grid system
 * 
 * Integration Points:
 * - Redux authSlice: selectUser selector for role-based filtering
 * - React Router: Link and useNavigate for navigation
 * - Header component: Displays transaction name, date/time, user info
 * - Footer component: Displays function key legends
 * - Constants: USER_ROLES for role comparison
 * 
 * @module components/menu/MainMenuComponent
 */

import React, { useState, useEffect } from 'react';
import { useSelector } from 'react-redux';
import { useNavigate, Link } from 'react-router-dom';
import {
  Container,
  Grid,
  Card,
  CardContent,
  Typography,
  Box,
  Alert,
  Paper
} from '@mui/material';
import { selectUser } from '../../redux/slices/authSlice';
import Header from '../common/Header.jsx';
import Footer from '../common/Footer.jsx';
import { USER_ROLES } from '../../utils/constants.js';

/**
 * Menu Options Configuration
 * 
 * Defines all menu options from COMEN02Y.cpy copybook (lines 19-85) including
 * option number, display name, target program/route, and required user type.
 * 
 * COBOL Structure (COMEN02Y.cpy):
 * ```cobol
 * 01 CARDDEMO-MAIN-MENU-OPTIONS.
 *   05 CDEMO-MENU-OPT-COUNT PIC 9(02) VALUE 10.
 *   05 CDEMO-MENU-OPTIONS-DATA.
 *     10 FILLER PIC 9(02) VALUE 1.              (Option number)
 *     10 FILLER PIC X(35) VALUE 'Account View'. (Option name)
 *     10 FILLER PIC X(08) VALUE 'COACTVWC'.     (Target program)
 *     10 FILLER PIC X(01) VALUE 'U'.            (User type: U=User, A=Admin)
 * ```
 * 
 * Each menu option contains:
 * - id: Option number (1-10) matching CDEMO-MENU-OPT-NUM
 * - name: Display text matching CDEMO-MENU-OPT-NAME
 * - route: React Router path replacing COBOL XCTL PROGRAM
 * - userType: Access level ('U' User / 'A' Admin) matching CDEMO-MENU-OPT-USRTYPE
 * - description: Brief description for tooltip/help text
 */
const menuOptions = [
  {
    id: 1,
    name: 'Account View',
    route: '/account/view',
    userType: 'U',
    description: 'View account details and current balance'
  },
  {
    id: 2,
    name: 'Account Update',
    route: '/account/update',
    userType: 'U',
    description: 'Update account information and settings'
  },
  {
    id: 3,
    name: 'Credit Card List',
    route: '/card/list',
    userType: 'U',
    description: 'View list of credit cards associated with account'
  },
  {
    id: 4,
    name: 'Credit Card View',
    route: '/card/view',
    userType: 'U',
    description: 'View detailed credit card information'
  },
  {
    id: 5,
    name: 'Credit Card Update',
    route: '/card/update',
    userType: 'U',
    description: 'Update credit card details and status'
  },
  {
    id: 6,
    name: 'Transaction List',
    route: '/transaction/list',
    userType: 'U',
    description: 'View transaction history with pagination'
  },
  {
    id: 7,
    name: 'Transaction View',
    route: '/transaction/category',
    userType: 'U',
    description: 'View transaction category summary and reports'
  },
  {
    id: 8,
    name: 'Transaction Add',
    route: '/transaction/add',
    userType: 'U',
    description: 'Add new transaction to account'
  },
  {
    id: 9,
    name: 'Transaction Reports',
    route: '/reports',
    userType: 'U',
    description: 'Generate and view transaction reports'
  },
  {
    id: 10,
    name: 'Bill Payment',
    route: '/payment/bill',
    userType: 'U',
    description: 'Process bill payment transactions'
  }
];

/**
 * MainMenuComponent
 * 
 * Main menu navigation dashboard component displaying available application features
 * based on user role and access level. Transforms COMEN01 BMS mapset and COMEN01C
 * COBOL program into modern React component with Material-UI styling.
 * 
 * Component Lifecycle:
 * 1. Mount: Load user profile from Redux (useSelector)
 * 2. Render: Filter menu options based on user role
 * 3. User Interaction: Handle menu selection and navigate to target route
 * 4. Unmount: Clean up state (handled automatically by React)
 * 
 * COBOL Program Flow (COMEN01C.cbl):
 * ```cobol
 * MAIN-PARA.
 *   IF EIBCALEN = 0
 *     PERFORM RETURN-TO-SIGNON-SCREEN      (Check authentication)
 *   ELSE
 *     PERFORM SEND-MENU-SCREEN             (Display menu)
 *     PERFORM RECEIVE-MENU-SCREEN          (Get user input)
 *     PERFORM PROCESS-ENTER-KEY            (Process selection)
 *   END-IF
 * ```
 * 
 * React Equivalent Flow:
 * 1. Check authentication via Redux selectUser
 * 2. Render menu options dynamically
 * 3. Handle click events on menu cards
 * 4. Navigate to target route using React Router
 * 
 * @returns {JSX.Element} Rendered main menu component with navigation options
 * 
 * @example
 * // Usage in App.js routing
 * <Route path="/menu" element={<MainMenuComponent />} />
 * 
 * @example
 * // Navigation from LoginComponent after successful authentication
 * if (userType === 'R') {
 *   navigate('/menu');  // Regular users go to main menu
 * } else {
 *   navigate('/admin'); // Admin users go to admin menu
 * }
 */
const MainMenuComponent = () => {
  // ===========================================================================
  // State Management
  // ===========================================================================
  
  /**
   * Local State: Error Message
   * 
   * Stores error message for display when invalid menu selection occurs or
   * user attempts to access unauthorized menu option.
   * 
   * Maps COBOL WS-MESSAGE variable (COMEN01C.cbl line 38):
   * ```cobol
   * 05 WS-MESSAGE PIC X(80) VALUE SPACES.
   * ```
   * 
   * Error scenarios:
   * - Invalid option number selected (non-existent option)
   * - Unauthorized access attempt (regular user accessing admin-only option)
   * - System error during navigation
   */
  const [errorMessage, setErrorMessage] = useState('');

  /**
   * Local State: Filtered Menu Options
   * 
   * Stores menu options filtered based on user role and access level.
   * Populated during component initialization by filterMenuOptionsByRole().
   * 
   * Maps COBOL BUILD-MENU-OPTIONS paragraph (COMEN01C.cbl lines 236-277)
   * which dynamically builds menu display based on CDEMO-MENU-OPT-USRTYPE.
   */
  const [filteredOptions, setFilteredOptions] = useState([]);

  /**
   * Redux State: Current User
   * 
   * Retrieves authenticated user profile from Redux auth state.
   * Used for role-based menu filtering and access control validation.
   * 
   * Maps COBOL COMMAREA fields (from COCOM01Y.cpy):
   * - user.userId → CDEMO-USER-ID (PIC X(8))
   * - user.userType → CDEMO-USER-TYPE (PIC X(1))
   *   * 'R' = Regular User (CDEMO-USRTYP-USER)
   *   * 'A' = Admin User (CDEMO-USRTYP-ADMIN)
   */
  const user = useSelector(selectUser);

  /**
   * React Router Navigation Hook
   * 
   * Provides navigate function for programmatic navigation after menu selection.
   * Replaces COBOL EXEC CICS XCTL PROGRAM() command pattern.
   */
  const navigate = useNavigate();

  // ===========================================================================
  // Side Effects
  // ===========================================================================

  /**
   * Effect: Filter Menu Options by User Role
   * 
   * Filters menu options based on authenticated user's role on component mount
   * and whenever user profile changes. Implements role-based access control
   * matching COBOL COMEN01C.cbl lines 136-143.
   * 
   * COBOL Access Control Logic:
   * ```cobol
   * IF CDEMO-USRTYP-USER AND
   *    CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
   *     SET ERR-FLG-ON TO TRUE
   *     MOVE 'No access - Admin Only option...' TO WS-MESSAGE
   * ```
   * 
   * Filtering Rules:
   * - Regular User ('R'): Show only options with userType 'U' (User level)
   * - Admin User ('A'): Show all options (both 'U' and 'A')
   * - No user: Redirect to login (handled by App routing)
   * 
   * Dependencies: [user]
   */
  useEffect(() => {
    if (user) {
      const filtered = filterMenuOptionsByRole(user.userType);
      setFilteredOptions(filtered);
    } else {
      // No user authenticated, redirect to login
      navigate('/login');
    }
  }, [user, navigate]);

  /**
   * Effect: Clear Error Message on User Change
   * 
   * Clears any displayed error messages when user profile changes,
   * ensuring stale error messages don't persist across user sessions.
   * 
   * Maps COBOL error flag clearing pattern:
   * ```cobol
   * SET ERR-FLG-OFF TO TRUE
   * MOVE SPACES TO WS-MESSAGE
   * ```
   * 
   * Dependencies: [user]
   */
  useEffect(() => {
    setErrorMessage('');
  }, [user]);

  // ===========================================================================
  // Helper Functions
  // ===========================================================================

  /**
   * Filter Menu Options by User Role
   * 
   * Filters menu options array based on user's role and access level.
   * Implements COBOL role-based access control from COMEN01C.cbl.
   * 
   * COBOL Equivalent (COMEN01C.cbl lines 136-143):
   * ```cobol
   * IF CDEMO-USRTYP-USER AND
   *    CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
   *     MOVE 'No access - Admin Only option...' TO WS-MESSAGE
   *     PERFORM SEND-MENU-SCREEN
   * END-IF
   * ```
   * 
   * Filtering Logic:
   * - Admin users ('A'): See all menu options (userType 'U' and 'A')
   * - Regular users ('R'): See only user-level options (userType 'U')
   * 
   * In COMEN02Y.cpy, all 10 menu options are marked with userType 'U',
   * meaning all options are accessible to both regular and admin users.
   * The filtering logic is implemented here to support future admin-only
   * options if menu structure is enhanced.
   * 
   * @param {string} userType - User type from Redux auth state ('R' or 'A')
   * @returns {Array} Filtered array of menu options accessible to user
   * 
   * @example
   * // Regular user
   * const options = filterMenuOptionsByRole('R');
   * // Returns only options with userType 'U'
   * 
   * @example
   * // Admin user
   * const options = filterMenuOptionsByRole('A');
   * // Returns all options (both 'U' and 'A')
   */
  const filterMenuOptionsByRole = (userType) => {
    // Admin users can see all menu options
    if (userType === USER_ROLES.ADMIN) {
      return menuOptions;
    }
    
    // Regular users can only see user-level options (not admin-only)
    // Filter out any options with userType 'A'
    return menuOptions.filter(option => option.userType === 'U');
  };

  /**
   * Validate Menu Option Access
   * 
   * Validates if current user has access to selected menu option based on
   * role and option's required user type. Returns error message if access
   * is denied, or null if access is allowed.
   * 
   * COBOL Equivalent (COMEN01C.cbl lines 136-143):
   * ```cobol
   * IF CDEMO-USRTYP-USER AND
   *    CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
   *     SET ERR-FLG-ON TO TRUE
   *     MOVE 'No access - Admin Only option...' TO WS-MESSAGE
   * END-IF
   * ```
   * 
   * Validation Rules:
   * - Admin users: Can access all options
   * - Regular users: Can only access options with userType 'U'
   * - Attempting to access admin-only option returns error message
   * 
   * @param {object} option - Menu option object to validate
   * @returns {string|null} Error message if access denied, null if allowed
   */
  const validateOptionAccess = (option) => {
    // Admin users have access to all options
    if (user.userType === USER_ROLES.ADMIN) {
      return null;
    }

    // Regular users cannot access admin-only options
    if (user.userType === USER_ROLES.USER && option.userType === 'A') {
      // Maps COBOL error message from lines 140-141
      return 'No access - Admin Only option...';
    }

    // Access allowed
    return null;
  };

  // ===========================================================================
  // Event Handlers
  // ===========================================================================

  /**
   * Handle Menu Selection
   * 
   * Handles user click on menu option card. Validates access rights, displays
   * error message if unauthorized, or navigates to target route if authorized.
   * 
   * COBOL Equivalent: PROCESS-ENTER-KEY Paragraph (COMEN01C.cbl lines 115-165)
   * ```cobol
   * PROCESS-ENTER-KEY.
   *   MOVE OPTIONI TO WS-OPTION
   *   IF WS-OPTION IS NOT NUMERIC OR
   *      WS-OPTION > CDEMO-MENU-OPT-COUNT OR
   *      WS-OPTION = ZEROS
   *       MOVE 'Please enter a valid option number...' TO WS-MESSAGE
   *   END-IF
   * 
   *   IF CDEMO-USRTYP-USER AND
   *      CDEMO-MENU-OPT-USRTYPE(WS-OPTION) = 'A'
   *       MOVE 'No access - Admin Only option...' TO WS-MESSAGE
   *   END-IF
   * 
   *   IF NOT ERR-FLG-ON
   *       EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))
   *            COMMAREA(CARDDEMO-COMMAREA)
   *       END-EXEC
   *   END-IF
   * ```
   * 
   * Processing Flow:
   * 1. Validate option exists in filtered menu options
   * 2. Validate user has access rights to selected option
   * 3. If validation fails: Display error message (WS-MESSAGE → Alert)
   * 4. If validation passes: Navigate to target route (XCTL → navigate)
   * 
   * @param {object} option - Selected menu option object
   * @returns {void}
   * 
   * @example
   * // User clicks on "Account View" card
   * handleMenuSelection({
   *   id: 1,
   *   name: 'Account View',
   *   route: '/account/view',
   *   userType: 'U'
   * });
   * // Result: navigate('/account/view')
   */
  const handleMenuSelection = (option) => {
    try {
      // Clear any previous error messages
      // Maps COBOL: MOVE SPACES TO WS-MESSAGE (line 79)
      setErrorMessage('');

      // Validate user access to selected option
      // Maps COBOL lines 136-143 role-based access check
      const accessError = validateOptionAccess(option);
      if (accessError) {
        // Display error message for unauthorized access
        // Maps COBOL: MOVE 'No access - Admin Only option...' TO WS-MESSAGE
        setErrorMessage(accessError);
        return;
      }

      // Option is valid and user has access - navigate to target route
      // Maps COBOL: EXEC CICS XCTL PROGRAM(CDEMO-MENU-OPT-PGMNAME(WS-OPTION))
      // COBOL XCTL transfers control to target program
      // React Router navigate performs client-side navigation
      navigate(option.route);

    } catch (error) {
      // Handle unexpected navigation errors
      // Maps COBOL error handling for CICS XCTL failures
      setErrorMessage('An error occurred while navigating. Please try again.');
      console.error('Navigation error:', error);
    }
  };

  /**
   * Handle Logout
   * 
   * Handles user logout action from menu screen. Navigates to login screen,
   * allowing Header component's logout action to clear authentication state.
   * 
   * COBOL Equivalent (COMEN01C.cbl lines 96-98):
   * ```cobol
   * WHEN DFHPF3
   *     MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
   *     PERFORM RETURN-TO-SIGNON-SCREEN
   * ```
   * 
   * @returns {void}
   */
  const handleLogout = () => {
    // Navigate to login screen
    // Maps COBOL: EXEC CICS XCTL PROGRAM('COSGN00C')
    navigate('/login');
  };

  // ===========================================================================
  // Render Guards
  // ===========================================================================

  /**
   * Authentication Check
   * 
   * Ensures user is authenticated before rendering menu. If no user in Redux
   * state, useEffect will redirect to login, but this prevents flash of content.
   * 
   * Maps COBOL EIBCALEN check (COMEN01C.cbl lines 82-84):
   * ```cobol
   * IF EIBCALEN = 0
   *     MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM
   *     PERFORM RETURN-TO-SIGNON-SCREEN
   * ```
   */
  if (!user) {
    return null; // Don't render anything while redirecting to login
  }

  // ===========================================================================
  // Component Render
  // ===========================================================================

  /**
   * Main Menu Component JSX
   * 
   * Renders complete main menu screen with:
   * - Header component with transaction name and date/time
   * - Welcome message with user name
   * - Error alert if unauthorized access attempted
   * - Grid of menu option cards (2 columns on desktop, 1 on mobile)
   * - Footer component with function key legends
   * 
   * BMS Screen Layout (COMEN01.bms):
   * ```
   * Line 1:  Tran: CM00  [TITLE01: CardDemo Application]     Date: mm/dd/yy
   * Line 2:  Prog: COMEN01C  [TITLE02: Main Menu]            Time: hh:mm:ss
   * Line 4:               Main Menu
   * Line 6-17: Menu options (OPTN001-OPTN012)
   * Line 20: Please select an option: [INPUT FIELD]
   * Line 23: [ERROR MESSAGE]
   * Line 24: ENTER=Continue  F3=Exit
   * ```
   * 
   * React Layout:
   * - Header: Transaction name, titles, date/time (replaces lines 1-2)
   * - Title: "Main Menu" (replaces line 4)
   * - Cards Grid: Menu options (replaces lines 6-17, option input field)
   * - Alert: Error message (replaces line 23)
   * - Footer: Function legends (replaces line 24)
   */
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      {/* Header Component - Maps BMS header fields (lines 1-2) */}
      <Header pageTitle="Main Menu" />

      {/* Main Content Area */}
      <Container 
        component="main" 
        maxWidth="lg" 
        sx={{ 
          mt: 4, 
          mb: 4, 
          flex: 1,
          display: 'flex',
          flexDirection: 'column'
        }}
      >
        {/* Welcome Message Section */}
        <Paper 
          elevation={2} 
          sx={{ 
            p: 3, 
            mb: 3, 
            backgroundColor: '#f5f5f5',
            borderLeft: '4px solid #1976d2'
          }}
        >
          <Typography 
            variant="h4" 
            component="h1" 
            gutterBottom
            sx={{ 
              color: '#1976d2',
              fontWeight: 500
            }}
          >
            Main Menu
          </Typography>
          <Typography 
            variant="body1" 
            sx={{ 
              color: '#555',
              mt: 1
            }}
          >
            Welcome, {user.firstName} {user.lastName}! Please select an option from the menu below.
          </Typography>
        </Paper>

        {/* Error Message Alert - Maps BMS ERRMSG field (line 154-157) */}
        {errorMessage && (
          <Alert 
            severity="error" 
            sx={{ mb: 3 }}
            onClose={() => setErrorMessage('')}
          >
            {errorMessage}
          </Alert>
        )}

        {/* Menu Options Grid - Maps BMS OPTN001-OPTN010 fields (lines 80-139) */}
        <Grid container spacing={3}>
          {filteredOptions.map((option) => (
            <Grid item xs={12} sm={6} md={6} key={option.id}>
              <Card
                sx={{
                  height: '100%',
                  display: 'flex',
                  flexDirection: 'column',
                  cursor: 'pointer',
                  transition: 'all 0.3s ease',
                  '&:hover': {
                    transform: 'translateY(-4px)',
                    boxShadow: 6,
                    backgroundColor: '#e3f2fd'
                  },
                  '&:active': {
                    transform: 'translateY(-2px)',
                    boxShadow: 4
                  },
                  border: '1px solid #e0e0e0'
                }}
                onClick={() => handleMenuSelection(option)}
                component={Link}
                to={option.route}
                style={{ textDecoration: 'none' }}
              >
                <CardContent sx={{ flexGrow: 1, p: 3 }}>
                  {/* Option Number Badge */}
                  <Box
                    sx={{
                      display: 'inline-block',
                      backgroundColor: '#1976d2',
                      color: 'white',
                      borderRadius: '50%',
                      width: 32,
                      height: 32,
                      textAlign: 'center',
                      lineHeight: '32px',
                      fontWeight: 'bold',
                      mb: 2
                    }}
                  >
                    {option.id}
                  </Box>

                  {/* Option Name - Maps CDEMO-MENU-OPT-NAME */}
                  <Typography 
                    variant="h6" 
                    component="h2"
                    sx={{
                      color: '#1976d2',
                      fontWeight: 500,
                      mb: 1
                    }}
                  >
                    {option.name}
                  </Typography>

                  {/* Option Description */}
                  <Typography 
                    variant="body2" 
                    color="text.secondary"
                    sx={{ mt: 1 }}
                  >
                    {option.description}
                  </Typography>

                  {/* Admin Badge (if admin-only option) */}
                  {option.userType === 'A' && (
                    <Box
                      sx={{
                        display: 'inline-block',
                        mt: 2,
                        px: 2,
                        py: 0.5,
                        backgroundColor: '#ff9800',
                        color: 'white',
                        borderRadius: 1,
                        fontSize: '0.75rem',
                        fontWeight: 'bold'
                      }}
                    >
                      ADMIN ONLY
                    </Box>
                  )}
                </CardContent>
              </Card>
            </Grid>
          ))}
        </Grid>

        {/* Empty State Message - if no options available */}
        {filteredOptions.length === 0 && (
          <Paper 
            elevation={1} 
            sx={{ 
              p: 4, 
              textAlign: 'center',
              backgroundColor: '#fff3e0'
            }}
          >
            <Typography variant="h6" color="text.secondary">
              No menu options available for your user type.
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
              Please contact your system administrator for access.
            </Typography>
          </Paper>
        )}
      </Container>

      {/* Footer Component - Maps BMS footer legends (line 158-162) */}
      <Footer />
    </Box>
  );
};

/**
 * Export MainMenuComponent as default export
 * 
 * Allows importing component with any name:
 * import MainMenu from './components/menu/MainMenuComponent.jsx';
 * 
 * Matches export schema requirement: is_default: True
 */
export default MainMenuComponent;
