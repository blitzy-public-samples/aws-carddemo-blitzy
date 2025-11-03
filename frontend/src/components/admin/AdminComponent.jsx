/**
 * CardDemo Admin Dashboard Component
 * 
 * React functional component implementing administrative dashboard for ROLE_ADMIN users,
 * transforming COADM01 BMS mapset and COADM01C COBOL program into modern Material-UI
 * web interface. Displays dashboard with cards for user management, batch job monitoring,
 * system configuration, audit logs, database maintenance, and system health metrics.
 * 
 * COBOL Source Mapping:
 * - COBOL Program: COADM01C.cbl (Admin Menu for Admin users)
 * - CICS Transaction: CA00
 * - BMS Mapset: COADM01.bms (Admin Menu Screen)
 * - Copybook: COADM02Y.cpy (Admin Menu Options)
 * 
 * Transformation Details:
 * - COADM01C.cbl lines 82-84: EIBCALEN=0 check → useEffect redirect non-admin users
 * - COADM01C.cbl lines 87-90: CDEMO-PGM-REENTER flag → React component mount/remount
 * - COADM01.bms lines 80-139: OPTN001-OPTN012 display fields → Material-UI Card grid
 * - COADM01.bms lines 145-149: OPTION input field → Card-based navigation (onClick)
 * - COADM01.bms lines 154-157: ERRMSG field → Material-UI Alert component
 * - COADM01C.cbl lines 96-98: DFHPF3 (F3 key) → Back/Logout button
 * - COADM01C.cbl line 143: XCTL PROGRAM → React Router navigate()
 * - COADM02Y.cpy lines 20-48: 4 user management options → Expanded to 6 admin cards
 * 
 * Authorization:
 * - COBOL: EIBCALEN=0 redirects to COSGN00C (lines 82-84)
 * - React: useSelector(selectUser) verifies userType='A' (ROLE_ADMIN)
 * - Non-admin users redirected to login/menu screen
 * 
 * Dashboard Cards (expanding from COADM02Y.cpy 4 options):
 * 1. User Management - User List/Add/Update/Delete (COUSR00C-03C)
 * 2. Batch Job Monitor - Real-time batch job status and control
 * 3. System Configuration - View/edit system settings
 * 4. Audit Logs - Access system audit trail
 * 5. Database Maintenance - Database operations dashboard
 * 6. System Health - Metrics, performance indicators, resource usage
 * 
 * Integration Points:
 * - Redux authSlice: User authentication state for authorization checks
 * - React Router: Navigation to child admin routes
 * - Material-UI: Component library for modern dashboard UI
 * 
 * @module components/admin/AdminComponent
 */

import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useSelector } from 'react-redux';
import {
  Container,
  Grid,
  Card,
  CardContent,
  Typography,
  Alert,
  Box,
  IconButton
} from '@mui/material';
import {
  People as PeopleIcon,
  Schedule as ScheduleIcon,
  Settings as SettingsIcon,
  Description as DescriptionIcon,
  Storage as StorageIcon,
  HealthAndSafety as HealthIcon
} from '@mui/icons-material';
import { selectUser, selectIsAuthenticated } from '../../redux/slices/authSlice';

/**
 * AdminComponent - Administrative Dashboard
 * 
 * Functional component rendering admin dashboard with role-based access control.
 * Only users with userType='A' (ROLE_ADMIN) can access this component.
 * 
 * COBOL Procedure Division Mapping:
 * - MAIN-PARA (lines 75-110): Component initialization and authorization check
 * - PROCESS-ENTER-KEY (lines 115-155): Card onClick handlers with navigation
 * - RETURN-TO-SIGNON-SCREEN (lines 160-167): Redirect unauthorized users
 * - SEND-MENU-SCREEN (lines 172-184): Render dashboard with cards
 * - POPULATE-HEADER-INFO (lines 202-221): Header with date/time display
 * - BUILD-MENU-OPTIONS (lines 226-263): Generate menu option list → Card array
 * 
 * State Management:
 * - user: From Redux authSlice, contains userId, userType, role
 * - isAuthenticated: From Redux authSlice, authentication status
 * - error: Component-local state for error messages
 * 
 * Authorization Flow:
 * 1. useEffect checks user.userType on mount
 * 2. If not 'A' (Admin), redirect to home/menu
 * 3. Matches COBOL lines 82-84: IF EIBCALEN = 0 PERFORM RETURN-TO-SIGNON-SCREEN
 * 
 * @returns {JSX.Element} Admin dashboard component
 */
const AdminComponent = () => {
  // Redux state selectors - Maps COBOL COMMAREA access
  // COBOL: CARDDEMO-COMMAREA fields CDEMO-USER-ID, CDEMO-USER-TYPE
  const user = useSelector(selectUser);
  const isAuthenticated = useSelector(selectIsAuthenticated);
  
  // React Router navigation - Replaces CICS XCTL
  // COBOL line 143: EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME)
  const navigate = useNavigate();
  
  // Component-local state for error handling
  // Maps COBOL WS-MESSAGE variable (line 38)
  const [error, setError] = useState('');

  /**
   * Authorization Check Effect
   * 
   * Verifies user has ROLE_ADMIN authority on component mount.
   * Redirects non-admin users to home screen.
   * 
   * COBOL Mapping (COADM01C.cbl lines 82-84):
   * ```cobol
   * IF EIBCALEN = 0
   *     MOVE 'COSGN00C' TO CDEMO-FROM-PROGRAM
   *     PERFORM RETURN-TO-SIGNON-SCREEN
   * ```
   * 
   * Authorization Logic:
   * - Check if user is authenticated (isAuthenticated flag)
   * - Check if user object exists
   * - Verify userType='A' (Admin) from COBOL SEC-USR-TYPE field
   * - If any check fails, redirect to home ('/') or login screen
   * 
   * Note: userType 'A' maps to COBOL CDEMO-USRTYP-ADMIN (88-level)
   *       userType 'R' maps to COBOL CDEMO-USRTYP-USER (88-level)
   */
  useEffect(() => {
    // Authorization check: Require authenticated admin user
    // Maps COBOL EIBCALEN=0 check and CDEMO-USER-TYPE validation
    if (!isAuthenticated || !user || user.userType !== 'A') {
      // Non-admin or unauthenticated user - redirect to home
      // Matches COBOL RETURN-TO-SIGNON-SCREEN paragraph (lines 160-167)
      navigate('/');
    }
  }, [user, isAuthenticated, navigate]);

  /**
   * Admin Dashboard Cards Configuration
   * 
   * Defines admin dashboard card array with routing and display information.
   * Expands from COADM02Y.cpy 4 user management options to full admin feature set.
   * 
   * COBOL Mapping (COADM02Y.cpy lines 19-48):
   * Original COBOL options:
   * 1. User List (Security) - COUSR00C
   * 2. User Add (Security) - COUSR01C
   * 3. User Update (Security) - COUSR02C
   * 4. User Delete (Security) - COUSR03C
   * 
   * Expanded to modern admin dashboard:
   * 1. User Management - Consolidates COUSR00C-03C functions
   * 2. Batch Job Monitor - New: Real-time job status (modernizes JCL monitoring)
   * 3. System Configuration - New: System settings management
   * 4. Audit Logs - New: Audit trail access for compliance
   * 5. Database Maintenance - New: Database operations
   * 6. System Health - New: Performance and resource monitoring
   * 
   * Card Properties:
   * - id: Unique identifier (maps CDEMO-ADMIN-OPT-NUM)
   * - title: Card title (maps CDEMO-ADMIN-OPT-NAME)
   * - description: Card description (additional context)
   * - icon: Material-UI icon component
   * - path: React Router path (replaces CDEMO-ADMIN-OPT-PGMNAME)
   * - color: Theme color for card border
   */
  const adminCards = [
    {
      id: 1,
      title: 'User Management',
      description: 'Manage users, roles, and permissions. View, add, update, and delete user accounts.',
      icon: <PeopleIcon fontSize="large" />,
      path: '/admin/users',
      color: '#1976d2' // Blue - matches COBOL COLOR=BLUE
    },
    {
      id: 2,
      title: 'Batch Job Monitor',
      description: 'Monitor and control batch job execution. View job status, logs, and execution history.',
      icon: <ScheduleIcon fontSize="large" />,
      path: '/admin/batch-jobs',
      color: '#2e7d32' // Green - new admin feature
    },
    {
      id: 3,
      title: 'System Configuration',
      description: 'View and modify system configuration settings. Manage application parameters.',
      icon: <SettingsIcon fontSize="large" />,
      path: '/admin/config',
      color: '#ed6c02' // Orange - new admin feature
    },
    {
      id: 4,
      title: 'Audit Logs',
      description: 'Access system audit trail and activity logs. Review user actions and system events.',
      icon: <DescriptionIcon fontSize="large" />,
      path: '/admin/audit-logs',
      color: '#9c27b0' // Purple - new admin feature
    },
    {
      id: 5,
      title: 'Database Maintenance',
      description: 'Perform database maintenance operations. Backup, restore, and optimize database.',
      icon: <StorageIcon fontSize="large" />,
      path: '/admin/database',
      color: '#0288d1' // Cyan - new admin feature
    },
    {
      id: 6,
      title: 'System Health',
      description: 'Monitor system health and performance metrics. View resource usage and system status.',
      icon: <HealthIcon fontSize="large" />,
      path: '/admin/health',
      color: '#d32f2f' // Red - new admin feature
    }
  ];

  /**
   * Card Click Handler
   * 
   * Handles navigation when admin dashboard card is clicked.
   * Replaces COBOL PROCESS-ENTER-KEY paragraph and XCTL navigation.
   * 
   * COBOL Mapping (COADM01C.cbl lines 115-155):
   * ```cobol
   * PROCESS-ENTER-KEY.
   *     [Validate WS-OPTION input]
   *     IF NOT ERR-FLG-ON
   *         IF CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)(1:5) NOT = 'DUMMY'
   *             MOVE WS-TRANID    TO CDEMO-FROM-TRANID
   *             MOVE WS-PGMNAME   TO CDEMO-FROM-PROGRAM
   *             MOVE ZEROS        TO CDEMO-PGM-CONTEXT
   *             EXEC CICS
   *                 XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))
   *                 COMMAREA(CARDDEMO-COMMAREA)
   *             END-EXEC
   *         END-IF
   *     END-IF
   * ```
   * 
   * Transformation:
   * - No numeric option validation needed (card-based UI)
   * - No DUMMY program check needed (all paths valid)
   * - XCTL PROGRAM → navigate(path)
   * - COMMAREA state → Redux persists user context automatically
   * 
   * @param {string} path - React Router path for navigation
   */
  const handleCardClick = (path) => {
    try {
      // Navigate to selected admin route
      // Maps COBOL EXEC CICS XCTL PROGRAM (line 143)
      navigate(path);
    } catch (err) {
      // Error handling - display error message
      // Maps COBOL error handling (lines 100-102, 130-133)
      setError('Unable to navigate to selected option. Please try again.');
      
      // Auto-clear error after 5 seconds
      setTimeout(() => setError(''), 5000);
    }
  };

  /**
   * Component Render
   * 
   * Renders admin dashboard with header, card grid, and error display.
   * 
   * Layout Structure (COADM01.bms mapping):
   * - Header Section (lines 29-74): Transaction name, title, date, time
   * - Main Content (lines 80-139): OPTN001-OPTN012 → Material-UI Card grid
   * - Error Display (lines 154-157): ERRMSG → Material-UI Alert
   * - Footer (lines 159-162): ENTER/F3 legend → Not needed (card-based UI)
   * 
   * Color Mapping from BMS:
   * - BLUE (lines 30, 35, etc.) → Primary theme colors
   * - YELLOW (lines 39, 62) → Header/subtitle colors
   * - RED (line 155) → Error message color (Alert severity='error')
   * - TURQUOISE (line 141) → Accent colors
   */
  return (
    <Container maxWidth="lg">
      {/* 
        Header Section
        Maps COADM01.bms lines 29-74: TRNNAME, TITLE01, TITLE02, CURDATE, CURTIME
        Maps COADM01C.cbl POPULATE-HEADER-INFO paragraph (lines 202-221)
      */}
      <Box sx={{ my: 4 }}>
        {/* 
          Main Title
          Maps COADM01.bms line 78: 'Admin Menu' INITIAL value
          COLOR=NEUTRAL ATTRB=BRT (bright display)
        */}
        <Typography 
          variant="h4" 
          align="center" 
          gutterBottom
          sx={{ 
            fontWeight: 600,
            color: 'primary.main',
            mb: 1
          }}
        >
          Admin Menu
        </Typography>

        {/* 
          Subtitle
          Maps COADM01.bms line 61: TITLE02 field (subtitle display)
          COLOR=YELLOW (warning/highlight color)
        */}
        <Typography 
          variant="subtitle1" 
          align="center" 
          color="text.secondary"
          sx={{ mb: 3 }}
        >
          Administrative Functions Dashboard
        </Typography>

        {/* 
          User Welcome Message
          Displays authenticated admin user name
          Maps COBOL COMMAREA CDEMO-USER-ID display
        */}
        {user && (
          <Typography 
            variant="body2" 
            align="center"
            color="text.secondary"
          >
            Welcome, {user.firstName || user.userId} (Administrator)
          </Typography>
        )}
      </Box>

      {/* 
        Dashboard Card Grid
        Maps COADM01.bms lines 80-139: OPTN001-OPTN012 display fields
        Maps COADM01C.cbl BUILD-MENU-OPTIONS paragraph (lines 226-263)
        
        COBOL displays menu options as numbered list:
        1. User List (Security)
        2. User Add (Security)
        3. User Update (Security)
        4. User Delete (Security)
        
        React displays as interactive Material-UI cards in responsive grid.
        Grid responsive breakpoints:
        - xs=12: Full width on extra small screens (mobile)
        - sm=6: Two columns on small screens (tablet)
        - md=4: Three columns on medium+ screens (desktop)
      */}
      <Grid container spacing={3}>
        {adminCards.map((card) => (
          <Grid item xs={12} sm={6} md={4} key={card.id}>
            {/* 
              Admin Feature Card
              Maps single OPTN00X display field from BMS
              ATTRB=ASKIP,FSET,NORM COLOR=BLUE → Material-UI Card with hover effects
            */}
            <Card
              sx={{
                cursor: 'pointer',
                height: '100%',
                display: 'flex',
                flexDirection: 'column',
                transition: 'all 0.3s ease',
                borderTop: `4px solid ${card.color}`,
                '&:hover': {
                  boxShadow: 6,
                  transform: 'translateY(-4px)'
                }
              }}
              onClick={() => handleCardClick(card.path)}
              // Accessibility: Make card keyboard navigable
              role="button"
              tabIndex={0}
              onKeyPress={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  handleCardClick(card.path);
                }
              }}
            >
              <CardContent sx={{ flexGrow: 1 }}>
                {/* 
                  Card Icon and Title
                  Icon provides visual identification for admin function
                */}
                <Box display="flex" alignItems="center" mb={2}>
                  <Box 
                    sx={{ 
                      color: card.color,
                      display: 'flex',
                      alignItems: 'center'
                    }}
                  >
                    {card.icon}
                  </Box>
                  <Typography 
                    variant="h6" 
                    ml={2}
                    sx={{ 
                      fontWeight: 500,
                      color: 'text.primary'
                    }}
                  >
                    {card.title}
                  </Typography>
                </Box>

                {/* 
                  Card Description
                  Provides additional context for admin function
                */}
                <Typography 
                  variant="body2" 
                  color="text.secondary"
                  sx={{ lineHeight: 1.6 }}
                >
                  {card.description}
                </Typography>
              </CardContent>
            </Card>
          </Grid>
        ))}
      </Grid>

      {/* 
        Error Message Display
        Maps COADM01.bms lines 154-157: ERRMSG field
        DFHMDF ATTRB=(ASKIP,BRT,FSET) COLOR=RED LENGTH=78 POS=(23,1)
        
        Maps COADM01C.cbl error handling:
        - Line 101: CCDA-MSG-INVALID-KEY → WS-MESSAGE
        - Line 131-132: 'Please enter a valid option number...' → WS-MESSAGE
        - Line 177: WS-MESSAGE → ERRMSGO display
        
        Material-UI Alert with severity='error' provides red color matching BMS COLOR=RED.
        Auto-dismiss after 5 seconds via handleCardClick error handling.
      */}
      {error && (
        <Alert 
          severity="error" 
          sx={{ mt: 3 }}
          onClose={() => setError('')}
        >
          {error}
        </Alert>
      )}

      {/* 
        Footer Information
        Maps COADM01.bms lines 159-162: Navigation legend
        'ENTER=Continue  F3=Exit' COLOR=YELLOW
        
        Note: Card-based navigation eliminates need for ENTER key instruction.
        F3=Exit functionality handled by main navigation/header component.
        COBOL line 96-98: WHEN DFHPF3 PERFORM RETURN-TO-SIGNON-SCREEN
      */}
      <Box sx={{ mt: 4, mb: 2 }}>
        <Typography 
          variant="body2" 
          align="center"
          color="text.secondary"
        >
          Click on any card to access administrative functions
        </Typography>
      </Box>
    </Container>
  );
};

/**
 * Component Export
 * 
 * Default export of AdminComponent for use in React Router configuration.
 * 
 * Usage in App.jsx routing:
 * ```jsx
 * import AdminComponent from './components/admin/AdminComponent';
 * 
 * <Route 
 *   path="/admin" 
 *   element={
 *     <ProtectedRoute requiredRole="ADMIN">
 *       <AdminComponent />
 *     </ProtectedRoute>
 *   } 
 * />
 * ```
 * 
 * Protected Route Implementation:
 * Component includes built-in authorization check via useEffect.
 * Additional ProtectedRoute wrapper recommended for route-level security.
 * 
 * Child Routes:
 * - /admin/users - User management (maps COUSR00C-03C)
 * - /admin/batch-jobs - Batch job monitoring
 * - /admin/config - System configuration
 * - /admin/audit-logs - Audit trail access
 * - /admin/database - Database maintenance
 * - /admin/health - System health monitoring
 */
export default AdminComponent;
