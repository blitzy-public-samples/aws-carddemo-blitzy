/**
 * UserListPage.tsx
 * 
 * Converted from COBOL program: COUSR00C.cbl
 * BMS Map: COUSR00.bms (24x80 3270 terminal screen)
 * Data Structure: CSUSR01Y.cpy (SEC-USER-DATA)
 * 
 * Purpose: User management list page displaying users in table format with
 * columns for user ID, first name, last name, and user type. Supports user
 * selection for view/edit/delete operations and maintains user management
 * logic from COUSR00C.cbl COBOL program.
 * 
 * Original BMS Screen Layout:
 * - Header: Transaction name, titles, date/time (lines 1-2)
 * - Title: "List Users" (line 4)
 * - Search field: USRIDIN (8 chars, line 6)
 * - Table headers: Sel, User ID, First Name, Last Name, Type (line 8-9)
 * - Data rows: 10 rows displaying user data (lines 10-19)
 * - Page number: PAGENUM (line 4, position 71)
 * - Help text: "Type 'U' to Update or 'D' to Delete a User from the list" (line 21)
 * - Error message: ERRMSG (line 23)
 * - Function keys: ENTER=Continue, F3=Back, F7=Backward, F8=Forward (line 24)
 * 
 * Conversion Notes:
 * - BMS 3270 terminal screen converted to React Material-UI component
 * - VSAM USRSEC file browse (EXEC CICS STARTBR/READNEXT) replaced with REST API call
 * - 10-row fixed display converted to paginated DataGrid with Material-UI
 * - Selection fields (SEL0001-SEL0010) replaced with row selection callbacks
 * - Function keys (F7/F8 pagination) replaced with DataGrid pagination controls
 * - RACF role-based access control enforced via useAuth hook checking userType === 'A'
 * 
 * Security: Admin-only access (USER_TYPES.ADMIN required)
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Box, Button, Container, Paper, Typography, Stack } from '@mui/material';
import PersonAddIcon from '@mui/icons-material/PersonAdd';
import RefreshIcon from '@mui/icons-material/Refresh';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';

// Internal imports - component dependencies
import { getAllUsers } from '../services/userService';
import { UserTable } from '../components/tables/UserTable';
import { Header } from '../components/common/Header';
import { Footer } from '../components/common/Footer';
import { ErrorMessage } from '../components/common/ErrorMessage';
import { LoadingSpinner } from '../components/common/LoadingSpinner';

// Type definitions
import { User } from '../types/user';

// Custom hooks and utilities
import { useAuth } from '../hooks/useAuth';
import { USER_TYPES } from '../utils/constants';

/**
 * UserListPage Component
 * 
 * Displays paginated list of users with search, navigation, and action capabilities.
 * Enforces admin-only access control per RACF security requirements.
 * 
 * Component State:
 * - users: Array of User objects fetched from backend
 * - loading: Boolean flag for async data fetching state
 * - error: Error message string for API failures
 * - page: Current page number (0-indexed, matching DataGrid convention)
 * - pageSize: Number of rows per page (default 10, matching BMS 10-row display)
 * - totalUsers: Total count of users for pagination calculation
 * - searchUserId: Search filter value for user ID lookup
 * 
 * API Integration:
 * - Fetches user list via getAllUsers() from userService
 * - Supports pagination parameters (page, pageSize)
 * - Supports search filtering by userId
 * - Error handling for 403 Unauthorized, 404 Not Found, 500 Server Error
 * 
 * Navigation:
 * - Add User button → /users/new (COUSR01.bms)
 * - Row click for Edit → /users/:userId/edit (COUSR02.bms)
 * - Row click for Delete → /users/:userId/delete (COUSR03.bms)
 * - Back button → / (main menu)
 */
const UserListPage: React.FC = () => {
  // Authentication and authorization
  const { user, isAuthenticated, isLoading: authLoading } = useAuth();
  const navigate = useNavigate();

  // Component state
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState<number>(0);
  const [pageSize, setPageSize] = useState<number>(10); // Match BMS 10-row display
  const [totalUsers, setTotalUsers] = useState<number>(0);
  const [searchUserId, setSearchUserId] = useState<string>('');

  /**
   * Access Control Check
   * 
   * Enforces admin-only access per RACF security requirements.
   * Non-admin users are redirected with unauthorized error message.
   * 
   * Conversion from COBOL:
   * - RACF security check → Spring Security role-based access control
   * - User type check (SEC-USR-TYPE = 'A') → user.userType === USER_TYPES.ADMIN
   */
  useEffect(() => {
    if (!authLoading && isAuthenticated) {
      if (!user || user.userType !== USER_TYPES.ADMIN) {
        setError('Unauthorized: Admin access required for user management');
        setLoading(false);
      }
    }
  }, [user, isAuthenticated, authLoading]);

  /**
   * Fetch Users Data
   * 
   * Loads paginated user list from backend API on component mount and when
   * pagination/search parameters change.
   * 
   * Conversion from COBOL:
   * - EXEC CICS STARTBR FILE('USRSEC') → REST API GET /api/users
   * - EXEC CICS READNEXT → Paginated API call with page/pageSize parameters
   * - EXEC CICS ENDBR → Automatic connection cleanup by HTTP client
   * 
   * Error Handling:
   * - HTTP 403 → Unauthorized access (non-admin user)
   * - HTTP 404 → No users found
   * - HTTP 500 → Server error
   * - Network error → Connection failure message
   */
  const fetchUsers = async () => {
    // Skip fetch if user is not authorized
    if (!isAuthenticated || !user || user.userType !== USER_TYPES.ADMIN) {
      return;
    }

    try {
      setLoading(true);
      setError(null);

      // Call userService API with pagination and search parameters
      const response = await getAllUsers({
        page,
        pageSize,
        userId: searchUserId || undefined,
      });

      // Update state with fetched data
      setUsers(response.users);
      setTotalUsers(response.total);
    } catch (err: any) {
      // Error handling for various failure scenarios
      if (err.response) {
        // HTTP error responses
        switch (err.response.status) {
          case 403:
            setError('Unauthorized: Admin access required for user management');
            break;
          case 404:
            setError('No users found');
            setUsers([]);
            setTotalUsers(0);
            break;
          case 500:
            setError('Server error: Unable to fetch user list. Please try again later.');
            break;
          default:
            setError(`Error: ${err.response.data?.message || 'Failed to fetch users'}`);
        }
      } else if (err.request) {
        // Network error - no response received
        setError('Network error: Unable to connect to server. Please check your connection.');
      } else {
        // Other errors
        setError(`Error: ${err.message || 'An unexpected error occurred'}`);
      }
    } finally {
      setLoading(false);
    }
  };

  /**
   * Effect: Fetch users on mount and when dependencies change
   * 
   * Triggers data fetch when:
   * - Component mounts (initial load)
   * - Page number changes (F7/F8 pagination in BMS)
   * - Page size changes (rows per page setting)
   * - Search filter changes (USRIDIN field in BMS)
   * - Authentication state changes
   */
  useEffect(() => {
    fetchUsers();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, pageSize, searchUserId, isAuthenticated, user]);

  /**
   * Handle Row Click
   * 
   * Navigates to user detail/edit page when user row is clicked.
   * 
   * Conversion from COBOL:
   * - Selection field (SEL0001-SEL0010) input of 'U' → Row click navigation
   * - EXEC CICS XCTL PROGRAM('COUSR02C') → navigate(`/users/${userId}/edit`)
   * - COMMAREA passing of user ID → URL path parameter
   * 
   * @param userId - User ID to navigate to edit page
   */
  const handleRowClick = (userId: string) => {
    navigate(`/users/${userId}/edit`);
  };

  /**
   * Handle Delete Action
   * 
   * Navigates to user delete confirmation page.
   * 
   * Conversion from COBOL:
   * - Selection field input of 'D' → Delete button click
   * - EXEC CICS XCTL PROGRAM('COUSR03C') → navigate(`/users/${userId}/delete`)
   * 
   * @param userId - User ID to navigate to delete confirmation page
   */
  const handleDeleteClick = (userId: string) => {
    navigate(`/users/${userId}/delete`);
  };

  /**
   * Handle Add User
   * 
   * Navigates to user add page for creating new user.
   * 
   * Conversion from COBOL:
   * - PF key or menu option → Add User button click
   * - EXEC CICS XCTL PROGRAM('COUSR01C') → navigate('/users/new')
   */
  const handleAddUser = () => {
    navigate('/users/new');
  };

  /**
   * Handle Refresh
   * 
   * Reloads user list data from backend.
   * 
   * Conversion from COBOL:
   * - ENTER key on BMS screen → Refresh button click
   * - Re-execute STARTBR/READNEXT sequence → Re-call getAllUsers API
   */
  const handleRefresh = () => {
    fetchUsers();
  };

  /**
   * Handle Search
   * 
   * Updates search filter and resets to first page.
   * 
   * Conversion from COBOL:
   * - USRIDIN field (8 chars) → Search input field
   * - Generic key positioning (RIDFLD) → API query parameter filtering
   * 
   * @param userId - Search string for user ID filter
   */
  const handleSearch = (userId: string) => {
    setSearchUserId(userId);
    setPage(0); // Reset to first page on new search
  };

  /**
   * Handle Page Change
   * 
   * Updates current page for pagination.
   * 
   * Conversion from COBOL:
   * - F7=Backward → Previous page
   * - F8=Forward → Next page
   * - PAGENUM display field → DataGrid page indicator
   * 
   * @param newPage - New page number (0-indexed)
   */
  const handlePageChange = (newPage: number) => {
    setPage(newPage);
  };

  /**
   * Handle Back Navigation
   * 
   * Returns to main menu.
   * 
   * Conversion from COBOL:
   * - F3=Back function key → Back button
   * - EXEC CICS RETURN TRANSID('MENU') → navigate('/')
   */
  const handleBack = () => {
    navigate('/');
  };

  /**
   * Render Loading State
   * 
   * Shows loading spinner during initial authentication check or data fetch.
   */
  if (authLoading || (loading && users.length === 0)) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
        <Header />
        <LoadingSpinner fullScreen message="Loading user list..." />
        <Footer />
      </Box>
    );
  }

  /**
   * Render Unauthorized State
   * 
   * Shows error message for non-admin users attempting to access user management.
   * 
   * Conversion from COBOL:
   * - RACF authorization failure → Error message display
   * - ERRMSG field (red text) → ErrorMessage component with severity="error"
   */
  if (!isAuthenticated || !user || user.userType !== USER_TYPES.ADMIN) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
        <Header />
        <Container maxWidth="lg" sx={{ flexGrow: 1, py: 4 }}>
          <Paper elevation={3} sx={{ p: 4 }}>
            <Typography variant="h4" gutterBottom color="error">
              Access Denied
            </Typography>
            <ErrorMessage
              message="You do not have permission to access user management. Admin access is required."
              severity="error"
            />
            <Box sx={{ mt: 3 }}>
              <Button
                variant="contained"
                onClick={handleBack}
                startIcon={<ArrowBackIcon />}
              >
                Return to Main Menu
              </Button>
            </Box>
          </Paper>
        </Container>
        <Footer />
      </Box>
    );
  }

  /**
   * Main Render
   * 
   * Displays user list page with table, actions, and navigation.
   * 
   * Layout Structure:
   * - Header: Application branding and user context
   * - Page Title: "User Management - List Users"
   * - Action Buttons: Add User, Refresh, Back
   * - Error Display: ErrorMessage component (if error exists)
   * - User Table: UserTable component with pagination
   * - Footer: Copyright and application info
   * 
   * Conversion from BMS Layout:
   * - Lines 1-2: Header (TRNNAME, TITLE01/02, CURDATE, CURTIME) → Header component
   * - Line 4: Title "List Users" → Typography variant="h4"
   * - Line 6: Search field (USRIDIN) → Embedded in UserTable search functionality
   * - Lines 8-19: Table with 10 rows → UserTable DataGrid with pagination
   * - Line 21: Help text → Implicit in Edit/Delete button tooltips
   * - Line 23: Error message (ERRMSG) → ErrorMessage component
   * - Line 24: Function keys → Action buttons and DataGrid controls
   */
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      {/* Application Header */}
      <Header />

      {/* Main Content */}
      <Container maxWidth="lg" sx={{ flexGrow: 1, py: 4 }}>
        <Paper elevation={3} sx={{ p: 3 }}>
          {/* Page Title and Actions */}
          <Box sx={{ mb: 3, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Typography variant="h4" component="h1" gutterBottom>
              User Management - List Users
            </Typography>
            <Stack direction="row" spacing={2}>
              <Button
                variant="outlined"
                onClick={handleBack}
                startIcon={<ArrowBackIcon />}
              >
                Back to Menu
              </Button>
              <Button
                variant="outlined"
                onClick={handleRefresh}
                startIcon={<RefreshIcon />}
                disabled={loading}
              >
                Refresh
              </Button>
              <Button
                variant="contained"
                color="primary"
                onClick={handleAddUser}
                startIcon={<PersonAddIcon />}
              >
                Add User
              </Button>
            </Stack>
          </Box>

          {/* Error Message Display */}
          {error && (
            <Box sx={{ mb: 2 }}>
              <ErrorMessage message={error} severity="error" />
            </Box>
          )}

          {/* User Table Component */}
          <UserTable
            users={users}
            onRowClick={handleRowClick}
            onDeleteClick={handleDeleteClick}
            onSearch={handleSearch}
            page={page}
            pageSize={pageSize}
            total={totalUsers}
            onPageChange={handlePageChange}
            loading={loading}
          />

          {/* Helper Text */}
          <Box sx={{ mt: 2 }}>
            <Typography variant="body2" color="text.secondary">
              Click on a user row to edit details, or use the delete button to remove a user.
              Use the search field to filter users by ID.
            </Typography>
          </Box>
        </Paper>
      </Container>

      {/* Application Footer */}
      <Footer />
    </Box>
  );
};

// Export as default per schema requirements
export default UserListPage;
