/**
 * CardDemo Application Header Component
 * 
 * React functional component providing consistent navigation bar with application title,
 * current date/time display, user information, and logout functionality. Replicates BMS
 * mapset header patterns (TRNNAME, TITLE01/02, CURDATE, CURTIME, PGMNAME fields) found
 * in all 17 3270 terminal screens as a modern web navigation bar.
 * 
 * COBOL Source Mapping:
 * - BMS Header Pattern: All 17 mapsets (COSGN00.bms, COMEN01.bms, COACTVW.bms, etc.)
 * - TRNNAME (POS=(1,8)): Transaction name → Page title prop display
 * - TITLE01 (POS=(1,21), COLOR=YELLOW): Main title → "CardDemo Application"
 * - CURDATE (POS=(1,71), FORMAT=mm/dd/yy): Current date → Real-time date display
 * - PGMNAME (POS=(2,8)): Program name → User display name
 * - TITLE02 (POS=(2,21), COLOR=YELLOW): Secondary title → Page title prop
 * - CURTIME (POS=(2,71), FORMAT=hh:mm:ss): Current time → Real-time time display
 * 
 * BMS Field Transformation:
 * ```bms
 * TRNNAME DFHMDF ATTRB=(ASKIP,FSET,NORM), COLOR=BLUE, LENGTH=4, POS=(1,8)
 * TITLE01 DFHMDF ATTRB=(ASKIP,FSET,NORM), COLOR=YELLOW, LENGTH=40, POS=(1,21)
 * CURDATE DFHMDF ATTRB=(ASKIP,FSET,NORM), COLOR=BLUE, LENGTH=8, POS=(1,71), INITIAL='mm/dd/yy'
 * PGMNAME DFHMDF ATTRB=(ASKIP,FSET,NORM), COLOR=BLUE, LENGTH=8, POS=(2,8)
 * TITLE02 DFHMDF ATTRB=(ASKIP,FSET,NORM), COLOR=YELLOW, LENGTH=40, POS=(2,21)
 * CURTIME DFHMDF ATTRB=(ASKIP,FSET,NORM), COLOR=BLUE, LENGTH=8, POS=(2,71), INITIAL='hh:mm:ss'
 * ```
 * 
 * React Equivalent:
 * - Material-UI AppBar with two-row Toolbar layout
 * - Top row: App title (left), real-time date (right)
 * - Bottom row: Page title (left), user info (center), real-time time + logout button (right)
 * - useEffect hook updates time display every second
 * - date-fns format function for date/time formatting matching COBOL patterns
 * 
 * Key Features:
 * - Real-time clock display updating every second (CURDATE/CURTIME equivalents)
 * - User information display from Redux auth state (PGMNAME equivalent)
 * - Logout button dispatching Redux logout action and clearing JWT token
 * - Responsive design using Material-UI Grid system
 * - Color scheme preservation: BLUE (#1976d2), YELLOW (#ffd700), TURQUOISE (#40E0D0)
 * - Navigation to login screen after logout (COBOL EXEC CICS RETURN equivalent)
 * 
 * Props:
 * @param {string} pageTitle - Page title to display in header (TITLE02 equivalent)
 * 
 * Integration Points:
 * - Redux authSlice: logout action, selectUser selector for user display
 * - React Router: useNavigate hook for post-logout navigation to /login
 * - date-fns: format function for date/time display matching BMS patterns
 * - Material-UI: AppBar, Toolbar, Typography, Box, Button, IconButton components
 * 
 * State Management:
 * - Local state: currentTime (Date object updated every second)
 * - Redux state: user profile (userId, firstName, lastName, userType)
 * 
 * Logout Flow:
 * 1. User clicks logout button (ExitToApp icon)
 * 2. Dispatch Redux logout action → clears state.user, state.token, state.isAuthenticated
 * 3. Remove JWT token from localStorage (carddemo_jwt_token key)
 * 4. Remove user profile from localStorage (carddemo_user key)
 * 5. Navigate to /login route (React Router)
 * 
 * Maps COBOL COSGN00C.cbl PF3 key handling (lines 88-90):
 * ```cobol
 * WHEN DFHPF3
 *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
 *     PERFORM SEND-PLAIN-TEXT
 *     EXEC CICS RETURN END-EXEC
 * ```
 * 
 * @module components/common/Header
 */

import React, { useState, useEffect } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { useNavigate } from 'react-router-dom';
import {
  AppBar,
  Toolbar,
  Typography,
  Box,
  Button,
  IconButton,
  Grid
} from '@mui/material';
import { ExitToApp, Menu as MenuIcon } from '@mui/icons-material';
import { format } from 'date-fns';
import { logout, selectUser } from '../../redux/slices/authSlice';

/**
 * Header Component
 * 
 * Primary navigation header component displaying application branding, real-time clock,
 * user information, and logout functionality. Transforms BMS mapset header fields from
 * 3270 terminal screens into modern Material-UI navigation bar.
 * 
 * Component Layout (2-row AppBar structure):
 * 
 * Row 1: [App Title: "CardDemo Application"          Date: mm/dd/yyyy]
 * Row 2: [Page Title                    User: Name   Time: hh:mm:ss AM/PM  Logout]
 * 
 * BMS Mapping:
 * - Row 1 Left (TITLE01): Application title in YELLOW
 * - Row 1 Right (CURDATE): Current date display in BLUE
 * - Row 2 Left (TITLE02): Page-specific title in YELLOW (from pageTitle prop)
 * - Row 2 Center (PGMNAME): User display name in BLUE
 * - Row 2 Right (CURTIME): Current time display in BLUE
 * - Row 2 Far Right: Logout button with ExitToApp icon
 * 
 * @param {object} props - Component props
 * @param {string} props.pageTitle - Page-specific title to display in header
 *                                   Maps to BMS TITLE02 field (POS=(2,21))
 *                                   Example values: "Main Menu", "Account View", "Card List"
 * @returns {JSX.Element} Rendered header component with AppBar and navigation elements
 * 
 * @example
 * // Usage in page components
 * <Header pageTitle="Main Menu" />
 * <Header pageTitle="Account View" />
 * <Header pageTitle="Card List" />
 * 
 * @example
 * // BMS TITLE02 values from different screens:
 * // COSGN00.bms line 61-64: TITLE02 "CardDemo Sign On"
 * // COMEN01.bms line 61-64: TITLE02 "CardDemo Main Menu"
 * // COACTVW.bms line 61-64: TITLE02 "CardDemo Account View"
 */
const Header = ({ pageTitle = '' }) => {
  // ============================================================================
  // State Management
  // ============================================================================
  
  /**
   * Local State: Current Time
   * 
   * Maintains current time as Date object, updated every second via useEffect hook.
   * Used to display real-time clock in header matching BMS CURTIME field behavior.
   * 
   * Maps COBOL date-time utility routines from CSDAT01Y.cpy copybook:
   * - WS-CURDATE: Current date for display (mm/dd/yy)
   * - WS-CURTIME: Current time for display (hh:mm:ss)
   * 
   * Updated every 1000ms (1 second) to provide real-time clock display.
   */
  const [currentTime, setCurrentTime] = useState(new Date());

  /**
   * Redux Dispatch Hook
   * 
   * Provides dispatch function to trigger Redux actions, specifically logout action
   * when user clicks logout button to terminate session and clear authentication state.
   */
  const dispatch = useDispatch();

  /**
   * React Router Navigation Hook
   * 
   * Provides navigate function for programmatic navigation to /login route after
   * logout action completes, mapping COBOL EXEC CICS XCTL PROGRAM('COSGN00C') pattern.
   */
  const navigate = useNavigate();

  /**
   * Redux State: Current User
   * 
   * Retrieves authenticated user profile from Redux auth state using selectUser selector.
   * User object contains userId, firstName, lastName, userType, role fields from
   * COBOL USRSEC file structure (SEC-USR-ID, SEC-USR-FNAME, SEC-USR-LNAME, SEC-USR-TYPE).
   * 
   * Returns null if user is not authenticated.
   * 
   * Maps COBOL COMMAREA fields:
   * - user.userId → CDEMO-USER-ID (PIC X(8))
   * - user.firstName → SEC-USR-FNAME (PIC X(20))
   * - user.lastName → SEC-USR-LNAME (PIC X(20))
   * - user.userType → CDEMO-USER-TYPE (PIC X(1), 'A' Admin or 'R' Regular)
   */
  const user = useSelector(selectUser);

  // ============================================================================
  // Side Effects
  // ============================================================================

  /**
   * Effect: Real-Time Clock Update
   * 
   * Sets up interval timer to update currentTime state every second, providing
   * real-time clock display in header. Cleanup function clears interval on unmount.
   * 
   * Maps BMS CURTIME field update pattern where mainframe system time is displayed
   * and refreshed on each screen display cycle.
   * 
   * Interval: 1000ms (1 second)
   * Cleanup: clearInterval on component unmount to prevent memory leaks
   */
  useEffect(() => {
    // Set up interval timer for clock updates
    const timer = setInterval(() => {
      setCurrentTime(new Date());
    }, 1000); // Update every 1 second

    // Cleanup function: Clear interval on unmount
    return () => {
      clearInterval(timer);
    };
  }, []); // Empty dependency array - run effect once on mount

  // ============================================================================
  // Event Handlers
  // ============================================================================

  /**
   * Handle Logout Click
   * 
   * Handles user logout action when logout button is clicked. Dispatches Redux logout
   * action to clear authentication state and remove JWT token from localStorage, then
   * navigates to login screen using React Router.
   * 
   * COBOL Mapping (COSGN00C.cbl lines 88-90):
   * Maps PF3=Exit key handling which terminates user session:
   * ```cobol
   * WHEN DFHPF3
   *     MOVE CCDA-MSG-THANK-YOU TO WS-MESSAGE
   *     PERFORM SEND-PLAIN-TEXT
   *     EXEC CICS RETURN END-EXEC
   * ```
   * 
   * Logout Flow:
   * 1. Dispatch Redux logout action
   *    - Clears state.user, state.token, state.isAuthenticated
   *    - Removes 'carddemo_jwt_token' from localStorage
   *    - Removes 'carddemo_user' from localStorage
   * 2. Navigate to /login route using React Router
   * 3. User redirected to login screen to re-authenticate
   * 
   * Maps COBOL session termination:
   * - EXEC CICS RETURN → navigate('/login')
   * - Clear COMMAREA context → Redux state cleared
   * - Remove session token → localStorage token removal
   * 
   * @returns {void}
   */
  const handleLogout = () => {
    // Dispatch Redux logout action to clear authentication state
    // Maps COBOL CICS RETURN command and COMMAREA clearing
    dispatch(logout());

    // Navigate to login screen after logout
    // Maps COBOL EXEC CICS XCTL PROGRAM('COSGN00C')
    navigate('/login');
  };

  // ============================================================================
  // Date/Time Formatting
  // ============================================================================

  /**
   * Format Current Date
   * 
   * Formats currentTime Date object to string using date-fns format function.
   * Matches BMS CURDATE field format pattern: mm/dd/yyyy (extended from mm/dd/yy).
   * 
   * BMS CURDATE Mapping:
   * - COSGN00.bms line 47-51: CURDATE field at POS=(1,71), INITIAL='mm/dd/yy'
   * - COMEN01.bms line 47-51: CURDATE field at POS=(1,71), INITIAL='mm/dd/yy'
   * - All 17 BMS mapsets use consistent date format
   * 
   * Format Pattern: 'MM/dd/yyyy'
   * - MM: Two-digit month (01-12)
   * - dd: Two-digit day (01-31)
   * - yyyy: Four-digit year (extended from yy for Y2K compliance)
   * 
   * Example Output: "01/15/2024"
   */
  const formattedDate = format(currentTime, 'MM/dd/yyyy');

  /**
   * Format Current Time
   * 
   * Formats currentTime Date object to string using date-fns format function.
   * Matches BMS CURTIME field format pattern: hh:mm:ss with AM/PM indicator.
   * 
   * BMS CURTIME Mapping:
   * - COSGN00.bms line 70-74: CURTIME field at POS=(2,71), INITIAL='hh:mm:ss'
   * - COMEN01.bms line 70-74: CURTIME field at POS=(2,71), INITIAL='hh:mm:ss'
   * - All 17 BMS mapsets use consistent time format
   * 
   * Format Pattern: 'hh:mm:ss a'
   * - hh: Two-digit hour (01-12) in 12-hour format
   * - mm: Two-digit minute (00-59)
   * - ss: Two-digit second (00-59)
   * - a: AM/PM indicator
   * 
   * Example Output: "02:35:47 PM"
   */
  const formattedTime = format(currentTime, 'hh:mm:ss a');

  /**
   * Format User Display Name
   * 
   * Constructs user display string from authenticated user profile.
   * If user is authenticated, displays "User: FirstName LastName",
   * otherwise returns empty string.
   * 
   * Maps BMS PGMNAME field which displays program name and user context
   * in 3270 terminal header (POS=(2,8), COLOR=BLUE, LENGTH=8).
   * 
   * COBOL Mapping:
   * - User context from COMMAREA: CDEMO-USER-ID → user.userId
   * - User names from USRSEC: SEC-USR-FNAME, SEC-USR-LNAME → user.firstName, user.lastName
   * 
   * Format: "User: {firstName} {lastName}"
   * Example: "User: John Smith"
   */
  const userDisplayName = user
    ? `User: ${user.firstName || ''} ${user.lastName || ''}`.trim()
    : '';

  // ============================================================================
  // Component Rendering
  // ============================================================================

  return (
    <AppBar
      position="static"
      sx={{
        // Background color matching BMS BLUE color scheme
        // Maps COLOR=BLUE from BMS field definitions
        backgroundColor: '#1976d2', // Material-UI primary blue
        boxShadow: 3,
        marginBottom: 0
      }}
    >
      {/* 
        First Toolbar Row (Top Row)
        BMS Mapping: Line 1 of 3270 terminal screen
        - Left: TITLE01 (Application title, YELLOW)
        - Right: CURDATE (Current date, BLUE)
      */}
      <Toolbar
        sx={{
          minHeight: '48px !important',
          paddingTop: 1,
          paddingBottom: 0.5,
          borderBottom: '1px solid rgba(255, 255, 255, 0.12)'
        }}
      >
        <Grid container alignItems="center" spacing={2}>
          {/* Application Title (TITLE01 equivalent) */}
          <Grid item xs>
            <Typography
              variant="h6"
              component="div"
              sx={{
                fontWeight: 600,
                // YELLOW color from BMS TITLE01 field (COLOR=YELLOW)
                color: '#ffd700',
                fontSize: '1.1rem',
                letterSpacing: '0.5px'
              }}
            >
              CardDemo Application
            </Typography>
          </Grid>

          {/* Current Date Display (CURDATE equivalent) */}
          <Grid item>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
              <Typography
                variant="body2"
                sx={{
                  color: 'rgba(255, 255, 255, 0.9)',
                  fontSize: '0.9rem',
                  fontWeight: 500
                }}
              >
                Date:
              </Typography>
              <Typography
                variant="body2"
                sx={{
                  color: '#ffffff',
                  fontSize: '0.9rem',
                  fontFamily: 'monospace',
                  fontWeight: 600,
                  backgroundColor: 'rgba(0, 0, 0, 0.2)',
                  padding: '2px 8px',
                  borderRadius: '4px'
                }}
              >
                {formattedDate}
              </Typography>
            </Box>
          </Grid>
        </Grid>
      </Toolbar>

      {/* 
        Second Toolbar Row (Bottom Row)
        BMS Mapping: Line 2 of 3270 terminal screen
        - Left: TITLE02 (Page title, YELLOW)
        - Center: PGMNAME (User display name, BLUE)
        - Right: CURTIME (Current time, BLUE) + Logout button
      */}
      <Toolbar
        sx={{
          minHeight: '48px !important',
          paddingTop: 0.5,
          paddingBottom: 1
        }}
      >
        <Grid container alignItems="center" spacing={2}>
          {/* Page Title (TITLE02 equivalent) */}
          <Grid item xs={12} sm={4}>
            <Typography
              variant="subtitle1"
              component="div"
              sx={{
                // YELLOW color from BMS TITLE02 field (COLOR=YELLOW)
                color: '#ffd700',
                fontSize: '1rem',
                fontWeight: 500
              }}
            >
              {pageTitle}
            </Typography>
          </Grid>

          {/* User Display Name (PGMNAME equivalent) */}
          <Grid item xs={12} sm={4} sx={{ textAlign: 'center' }}>
            {user && (
              <Typography
                variant="body2"
                sx={{
                  color: '#ffffff',
                  fontSize: '0.9rem',
                  fontWeight: 500
                }}
              >
                {userDisplayName}
              </Typography>
            )}
          </Grid>

          {/* Current Time Display + Logout Button (CURTIME equivalent + PF3 Exit) */}
          <Grid item xs={12} sm={4}>
            <Box
              sx={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'flex-end',
                gap: 2
              }}
            >
              {/* Current Time Display */}
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                <Typography
                  variant="body2"
                  sx={{
                    color: 'rgba(255, 255, 255, 0.9)',
                    fontSize: '0.9rem',
                    fontWeight: 500
                  }}
                >
                  Time:
                </Typography>
                <Typography
                  variant="body2"
                  sx={{
                    color: '#ffffff',
                    fontSize: '0.9rem',
                    fontFamily: 'monospace',
                    fontWeight: 600,
                    backgroundColor: 'rgba(0, 0, 0, 0.2)',
                    padding: '2px 8px',
                    borderRadius: '4px'
                  }}
                >
                  {formattedTime}
                </Typography>
              </Box>

              {/* Logout Button (PF3=Exit equivalent) */}
              {user && (
                <Button
                  color="inherit"
                  onClick={handleLogout}
                  startIcon={<ExitToApp />}
                  sx={{
                    textTransform: 'none',
                    fontSize: '0.9rem',
                    fontWeight: 500,
                    '&:hover': {
                      backgroundColor: 'rgba(255, 255, 255, 0.1)'
                    }
                  }}
                  aria-label="Logout"
                  title="Logout (PF3=Exit)"
                >
                  Logout
                </Button>
              )}
            </Box>
          </Grid>
        </Grid>
      </Toolbar>
    </AppBar>
  );
};

export default Header;
