/**
 * UserListComponent.jsx
 * 
 * React functional component for displaying and managing the list of users in the CardDemo application.
 * This component transforms the COUSR00M.bms BMS 3270 terminal screen to a modern React interface
 * using Material-UI components.
 * 
 * Original COBOL Program: COUSR00C.cbl
 * Original BMS Mapset: COUSR00M.bms
 * Original Data Structure: CSUSR01Y.cpy
 * 
 * Features:
 * - Paginated list of users (10 users per page matching COBOL pagination)
 * - Search functionality by User ID
 * - Action buttons for Update and Delete operations
 * - Admin-only access with role-based authentication
 * - Displays User ID, First Name, Last Name, User Type (Admin/User), Creation Date, Last Login
 * - JWT token-based authentication
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box,
  Button,
  CircularProgress,
  Container,
  IconButton,
  Paper,
  Snackbar,
  Alert,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TablePagination,
  TextField,
  Typography,
  Tooltip
} from '@mui/material';
import {
  Edit as EditIcon,
  Delete as DeleteIcon,
  ArrowBack as ArrowBackIcon,
  Search as SearchIcon
} from '@mui/icons-material';
import axios from 'axios';

/**
 * UserListComponent
 * 
 * Main component for displaying the user list with admin-only access.
 * Transforms COUSR00M.bms 3270 screen to responsive web interface.
 */
const UserListComponent = () => {
  // Navigation hook for routing (replaces CICS XCTL commands)
  const navigate = useNavigate();

  // Component state management (replaces COBOL WORKING-STORAGE)
  const [users, setUsers] = useState([]); // User list data
  const [loading, setLoading] = useState(true); // Loading state for async operations
  const [error, setError] = useState(''); // Error messages (replaces ERRMSG field)
  const [searchUserId, setSearchUserId] = useState(''); // Search input (replaces USRIDIN field)
  const [filteredUsers, setFilteredUsers] = useState([]); // Filtered results
  
  // Pagination state (replaces COBOL page tracking with CDEMO-CU00-PAGE-NUM)
  const [page, setPage] = useState(0); // Current page (0-indexed)
  const [rowsPerPage] = useState(10); // Fixed at 10 to match COBOL pagination
  
  // Snackbar state for displaying messages
  const [snackbarOpen, setSnackbarOpen] = useState(false);
  const [snackbarMessage, setSnackbarMessage] = useState('');
  const [snackbarSeverity, setSnackbarSeverity] = useState('info');

  /**
   * Verify user authentication and admin role on component mount.
   * This replaces RACF security checks in the COBOL program.
   * If user is not authenticated or not an admin, redirect to login.
   */
  useEffect(() => {
    const token = localStorage.getItem('jwtToken');
    const userType = localStorage.getItem('userType');
    
    // Check authentication and admin role (equivalent to COBOL security check)
    if (!token) {
      showSnackbar('Authentication required. Please login.', 'error');
      navigate('/login');
      return;
    }
    
    // Verify admin role (replaces RACF ADMIN check)
    if (userType !== 'A' && userType !== 'ADMIN') {
      showSnackbar('Access denied. Administrator privileges required.', 'error');
      navigate('/menu'); // Redirect to main menu if not admin
      return;
    }
    
    // Load user list if authenticated and authorized
    fetchUsers();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []); // Empty dependency array = run once on mount for authentication check

  /**
   * Fetch users from the REST API endpoint.
   * Replaces COBOL file I/O operations:
   * - STARTBR on USRSEC file
   * - READNEXT loop to read 10 records
   * - ENDBR to close browse
   */
  const fetchUsers = async () => {
    setLoading(true);
    setError('');
    
    try {
      // Get JWT token from localStorage (replaces CICS security context)
      const token = localStorage.getItem('jwtToken');
      
      // Call REST API endpoint (replaces VSAM USRSEC file access)
      const response = await axios.get('/api/admin/users', {
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json'
        }
      });
      
      // Process successful response
      if (response.data && Array.isArray(response.data)) {
        setUsers(response.data);
        setFilteredUsers(response.data); // Initialize filtered list
      } else {
        throw new Error('Invalid response format from server');
      }
      
    } catch (err) {
      // Error handling (replaces COBOL RESP code checking)
      console.error('Error fetching users:', err);
      
      if (err.response) {
        // Server responded with error status
        if (err.response.status === 401) {
          setError('Session expired. Please login again.');
          showSnackbar('Session expired. Please login again.', 'error');
          navigate('/login');
        } else if (err.response.status === 403) {
          setError('Access denied. Administrator privileges required.');
          showSnackbar('Access denied. Administrator privileges required.', 'error');
          navigate('/menu');
        } else {
          setError(err.response.data?.message || 'Unable to retrieve user list');
          showSnackbar(err.response.data?.message || 'Unable to retrieve user list', 'error');
        }
      } else if (err.request) {
        // Request made but no response received
        setError('Unable to connect to server. Please try again.');
        showSnackbar('Unable to connect to server. Please try again.', 'error');
      } else {
        // Error in request setup
        setError(err.message || 'An unexpected error occurred');
        showSnackbar(err.message || 'An unexpected error occurred', 'error');
      }
    } finally {
      setLoading(false);
    }
  };

  /**
   * Handle search functionality by User ID.
   * Replaces COBOL logic that filters users based on USRIDIN field.
   * Performs client-side filtering of the user list.
   */
  const handleSearch = () => {
    if (!searchUserId || searchUserId.trim() === '') {
      // Empty search - show all users
      setFilteredUsers(users);
      setPage(0); // Reset to first page
      return;
    }
    
    // Filter users by User ID (case-insensitive partial match)
    const searchTerm = searchUserId.trim().toUpperCase();
    const filtered = users.filter(user => 
      user.userId && user.userId.toUpperCase().includes(searchTerm)
    );
    
    setFilteredUsers(filtered);
    setPage(0); // Reset to first page after search
    
    if (filtered.length === 0) {
      showSnackbar('No users found matching the search criteria', 'info');
    }
  };

  /**
   * Handle Enter key press in search field.
   * Replaces COBOL DFHENTER processing in PROCESS-ENTER-KEY paragraph.
   */
  const handleSearchKeyPress = (event) => {
    if (event.key === 'Enter') {
      handleSearch();
    }
  };

  /**
   * Clear search and show all users.
   * Resets the filtered list to show all records.
   */
  const handleClearSearch = () => {
    setSearchUserId('');
    setFilteredUsers(users);
    setPage(0);
  };

  /**
   * Handle page change for pagination.
   * Replaces COBOL PF7/PF8 key processing for backward/forward navigation.
   * 
   * @param {Event} event - The change event
   * @param {number} newPage - The new page number (0-indexed)
   */
  const handleChangePage = (event, newPage) => {
    setPage(newPage);
  };

  /**
   * Navigate to user update screen.
   * Replaces COBOL XCTL to COUSR02C program when selection 'U' is entered.
   * 
   * @param {string} userId - The ID of the user to update
   */
  const handleUpdate = (userId) => {
    if (!userId) {
      showSnackbar('Invalid user selection', 'error');
      return;
    }
    
    // Navigate to update screen (replaces CICS XCTL PROGRAM('COUSR02C'))
    navigate(`/admin/users/update/${userId}`);
  };

  /**
   * Navigate to user delete screen.
   * Replaces COBOL XCTL to COUSR03C program when selection 'D' is entered.
   * 
   * @param {string} userId - The ID of the user to delete
   */
  const handleDelete = (userId) => {
    if (!userId) {
      showSnackbar('Invalid user selection', 'error');
      return;
    }
    
    // Navigate to delete screen (replaces CICS XCTL PROGRAM('COUSR03C'))
    navigate(`/admin/users/delete/${userId}`);
  };

  /**
   * Navigate back to admin menu.
   * Replaces COBOL PF3 key processing that performs XCTL to COADM01C.
   */
  const handleBack = () => {
    navigate('/admin/menu'); // Replaces CICS XCTL PROGRAM('COADM01C')
  };

  /**
   * Display snackbar notification.
   * Replaces COBOL error message field (ERRMSGO).
   * 
   * @param {string} message - The message to display
   * @param {string} severity - The severity level ('success', 'error', 'warning', 'info')
   */
  const showSnackbar = (message, severity = 'info') => {
    setSnackbarMessage(message);
    setSnackbarSeverity(severity);
    setSnackbarOpen(true);
  };

  /**
   * Close snackbar notification.
   */
  const handleCloseSnackbar = (event, reason) => {
    if (reason === 'clickaway') {
      return;
    }
    setSnackbarOpen(false);
  };

  /**
   * Format user type for display.
   * Transforms COBOL 88-level condition names to readable text.
   * SEC-USR-TYPE 'A' = 'Admin', 'U' = 'User'
   * 
   * @param {string} userType - The user type code ('A' or 'U')
   * @returns {string} - Formatted user type text
   */
  const formatUserType = (userType) => {
    if (!userType) return 'Unknown';
    
    const type = userType.toUpperCase();
    switch (type) {
      case 'A':
      case 'ADMIN':
        return 'Admin';
      case 'U':
      case 'USER':
        return 'User';
      default:
        return userType;
    }
  };

  /**
   * Format date for display.
   * Converts ISO date string to MM/DD/YYYY format matching BMS screen display.
   * 
   * @param {string} dateString - ISO date string
   * @returns {string} - Formatted date string
   */
  const formatDate = (dateString) => {
    if (!dateString) return 'N/A';
    
    try {
      const date = new Date(dateString);
      if (isNaN(date.getTime())) return 'Invalid Date';
      
      const month = String(date.getMonth() + 1).padStart(2, '0');
      const day = String(date.getDate()).padStart(2, '0');
      const year = date.getFullYear();
      
      return `${month}/${day}/${year}`;
    } catch (error) {
      return 'Invalid Date';
    }
  };

  /**
   * Format timestamp for display.
   * Converts ISO timestamp to MM/DD/YYYY HH:MM:SS format.
   * 
   * @param {string} timestampString - ISO timestamp string
   * @returns {string} - Formatted timestamp string
   */
  const formatTimestamp = (timestampString) => {
    if (!timestampString) return 'Never';
    
    try {
      const date = new Date(timestampString);
      if (isNaN(date.getTime())) return 'Invalid';
      
      const month = String(date.getMonth() + 1).padStart(2, '0');
      const day = String(date.getDate()).padStart(2, '0');
      const year = date.getFullYear();
      const hours = String(date.getHours()).padStart(2, '0');
      const minutes = String(date.getMinutes()).padStart(2, '0');
      const seconds = String(date.getSeconds()).padStart(2, '0');
      
      return `${month}/${day}/${year} ${hours}:${minutes}:${seconds}`;
    } catch (error) {
      return 'Invalid';
    }
  };

  /**
   * Calculate paginated users for current page.
   * Implements client-side pagination matching COBOL's 10 records per page.
   */
  const paginatedUsers = filteredUsers.slice(
    page * rowsPerPage,
    page * rowsPerPage + rowsPerPage
  );

  // Display loading state (replaces CICS SEND MAP with "Please wait..." message)
  if (loading) {
    return (
      <Container maxWidth="lg">
        <Box sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '400px' }}>
          <CircularProgress size={60} />
        </Box>
      </Container>
    );
  }

  // Main component render
  return (
    <Container maxWidth="xl">
      <Box sx={{ mt: 4, mb: 4 }}>
        {/* Header Section - Replaces BMS screen header with TITLE01, TITLE02 */}
        <Paper elevation={3} sx={{ p: 3, mb: 3 }}>
          <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', mb: 2 }}>
            <Typography variant="h4" component="h1" color="primary">
              List Users
            </Typography>
            <Button
              variant="outlined"
              startIcon={<ArrowBackIcon />}
              onClick={handleBack}
              aria-label="Back to Admin Menu"
            >
              Back to Admin Menu
            </Button>
          </Box>
          
          <Typography variant="body2" color="text.secondary">
            Transaction: CU00 | Program: COUSR00C
          </Typography>
        </Paper>

        {/* Search Section - Replaces USRIDIN field at POS=(6,21) */}
        <Paper elevation={2} sx={{ p: 2, mb: 3 }}>
          <Box sx={{ display: 'flex', gap: 2, alignItems: 'center' }}>
            <TextField
              label="Search User ID"
              variant="outlined"
              size="small"
              value={searchUserId}
              onChange={(e) => setSearchUserId(e.target.value.toUpperCase())}
              onKeyPress={handleSearchKeyPress}
              placeholder="Enter User ID"
              inputProps={{
                maxLength: 8, // Matches COBOL field length
                style: { textTransform: 'uppercase' }
              }}
              sx={{ width: 250 }}
              aria-label="Search User ID"
            />
            <Button
              variant="contained"
              startIcon={<SearchIcon />}
              onClick={handleSearch}
              aria-label="Search Users"
            >
              Search
            </Button>
            <Button
              variant="outlined"
              onClick={handleClearSearch}
              aria-label="Clear Search"
            >
              Clear
            </Button>
            <Box sx={{ flexGrow: 1 }} />
            <Typography variant="body2" color="text.secondary">
              Page: {page + 1} of {Math.ceil(filteredUsers.length / rowsPerPage) || 1}
            </Typography>
          </Box>
        </Paper>

        {/* Error Message Display - Replaces ERRMSG field at POS=(23,1) */}
        {error && (
          <Paper elevation={2} sx={{ p: 2, mb: 3, bgcolor: 'error.light' }}>
            <Typography color="error">
              {error}
            </Typography>
          </Paper>
        )}

        {/* User List Table - Replaces BMS screen rows from line 10-19 */}
        <Paper elevation={2}>
          <TableContainer>
            <Table aria-label="User List Table">
              <TableHead>
                <TableRow sx={{ bgcolor: 'primary.main' }}>
                  <TableCell sx={{ color: 'white', fontWeight: 'bold' }}>Sel</TableCell>
                  <TableCell sx={{ color: 'white', fontWeight: 'bold' }}>User ID</TableCell>
                  <TableCell sx={{ color: 'white', fontWeight: 'bold' }}>First Name</TableCell>
                  <TableCell sx={{ color: 'white', fontWeight: 'bold' }}>Last Name</TableCell>
                  <TableCell sx={{ color: 'white', fontWeight: 'bold' }}>Type</TableCell>
                  <TableCell sx={{ color: 'white', fontWeight: 'bold' }}>Created Date</TableCell>
                  <TableCell sx={{ color: 'white', fontWeight: 'bold' }}>Last Login</TableCell>
                  <TableCell sx={{ color: 'white', fontWeight: 'bold' }}>Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {paginatedUsers.length > 0 ? (
                  paginatedUsers.map((user, index) => (
                    <TableRow
                      key={user.userId || index}
                      sx={{
                        '&:hover': { bgcolor: 'action.hover' },
                        bgcolor: index % 2 === 0 ? 'background.paper' : 'action.hover'
                      }}
                    >
                      {/* Selection column - Replaces SEL0001-SEL0010 fields */}
                      <TableCell>{(page * rowsPerPage) + index + 1}</TableCell>
                      
                      {/* User ID - Replaces USRID01-USRID10 fields */}
                      <TableCell>
                        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                          {user.userId || 'N/A'}
                        </Typography>
                      </TableCell>
                      
                      {/* First Name - Replaces FNAME01-FNAME10 fields */}
                      <TableCell>{user.firstName || 'N/A'}</TableCell>
                      
                      {/* Last Name - Replaces LNAME01-LNAME10 fields */}
                      <TableCell>{user.lastName || 'N/A'}</TableCell>
                      
                      {/* User Type - Replaces UTYPE01-UTYPE10 fields */}
                      <TableCell>
                        <Typography
                          variant="body2"
                          sx={{
                            color: formatUserType(user.userType) === 'Admin' ? 'error.main' : 'primary.main',
                            fontWeight: 'bold'
                          }}
                        >
                          {formatUserType(user.userType)}
                        </Typography>
                      </TableCell>
                      
                      {/* Created Date - Additional field from requirements */}
                      <TableCell>
                        <Typography variant="body2">
                          {formatDate(user.createdDate)}
                        </Typography>
                      </TableCell>
                      
                      {/* Last Login - Additional field from requirements */}
                      <TableCell>
                        <Typography variant="body2">
                          {formatTimestamp(user.lastLogin)}
                        </Typography>
                      </TableCell>
                      
                      {/* Action Buttons - Replaces 'U' and 'D' selection options */}
                      <TableCell>
                        <Box sx={{ display: 'flex', gap: 1 }}>
                          <Tooltip title="Update User">
                            <IconButton
                              color="primary"
                              onClick={() => handleUpdate(user.userId)}
                              size="small"
                              aria-label={`Update user ${user.userId}`}
                            >
                              <EditIcon />
                            </IconButton>
                          </Tooltip>
                          <Tooltip title="Delete User">
                            <IconButton
                              color="error"
                              onClick={() => handleDelete(user.userId)}
                              size="small"
                              aria-label={`Delete user ${user.userId}`}
                            >
                              <DeleteIcon />
                            </IconButton>
                          </Tooltip>
                        </Box>
                      </TableCell>
                    </TableRow>
                  ))
                ) : (
                  <TableRow>
                    <TableCell colSpan={8} align="center">
                      <Typography variant="body1" color="text.secondary" sx={{ py: 3 }}>
                        {searchUserId 
                          ? 'No users found matching the search criteria'
                          : 'No users available'
                        }
                      </Typography>
                    </TableCell>
                  </TableRow>
                )}
                
                {/* Fill empty rows to maintain consistent table height (matches COBOL display) */}
                {paginatedUsers.length < rowsPerPage && paginatedUsers.length > 0 && (
                  [...Array(rowsPerPage - paginatedUsers.length)].map((_, index) => (
                    <TableRow key={`empty-${index}`} sx={{ height: 53 }}>
                      <TableCell colSpan={8}>&nbsp;</TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>

          {/* Pagination Controls - Replaces PF7/PF8 function keys */}
          <TablePagination
            component="div"
            count={filteredUsers.length}
            page={page}
            onPageChange={handleChangePage}
            rowsPerPage={rowsPerPage}
            rowsPerPageOptions={[]} // Fixed at 10, no options
            labelDisplayedRows={({ from, to, count }) => 
              `${from}-${to} of ${count !== -1 ? count : `more than ${to}`}`
            }
            aria-label="Pagination Controls"
          />
        </Paper>

        {/* Help Text - Replaces BMS screen instruction text */}
        <Paper elevation={1} sx={{ p: 2, mt: 3, bgcolor: 'info.light' }}>
          <Typography variant="body2" color="text.secondary">
            <strong>Instructions:</strong> Type &apos;U&apos; to Update or &apos;D&apos; to Delete a User from the list.
            Use the Edit (pencil) icon to update a user or the Delete (trash) icon to remove a user.
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            <strong>Function Keys:</strong> F3=Back | F7=Backward | F8=Forward | ENTER=Continue
            (Navigation via buttons and pagination controls)
          </Typography>
        </Paper>

        {/* Snackbar for notifications - Replaces COBOL WS-MESSAGE field */}
        <Snackbar
          open={snackbarOpen}
          autoHideDuration={6000}
          onClose={handleCloseSnackbar}
          anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
        >
          <Alert
            onClose={handleCloseSnackbar}
            severity={snackbarSeverity}
            sx={{ width: '100%' }}
            variant="filled"
          >
            {snackbarMessage}
          </Alert>
        </Snackbar>
      </Box>
    </Container>
  );
};

// Default export as specified in the schema
export default UserListComponent;
