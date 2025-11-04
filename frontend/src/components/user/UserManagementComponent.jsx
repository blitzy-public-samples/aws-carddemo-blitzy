/**
 * User Management Component
 * 
 * Comprehensive admin-only user management React component transforming four COBOL CICS programs
 * (COUSR00C, COUSR01C, COUSR02C, COUSR03C) and their corresponding BMS mapsets into a unified
 * Material-UI administrative interface. Provides complete CRUD operations for user management
 * with role-based access control requiring ROLE_ADMIN authorization.
 * 
 * COBOL Source Transformation:
 * - COUSR00C.cbl (List Users) → Material-UI DataGrid with search, filter, and pagination
 * - COUSR01C.cbl (Add User) → Formik-controlled creation dialog with Yup validation
 * - COUSR02C.cbl (Update User) → Two-step edit dialog (fetch user by ID, then update form)
 * - COUSR03C.cbl (Delete User) → Confirmation dialog with read-only user details
 * 
 * BMS Screen Mapping:
 * - COUSR00.bms: 10-row table (User ID, First Name, Last Name, Type) with search field
 * - COUSR01.bms: Add form (First Name, Last Name, User ID, Password, User Type selector)
 * - COUSR02.bms: Update form with User ID lookup and editable fields
 * - COUSR03.bms: Delete confirmation with protected/read-only fields (ASKIP)
 * 
 * USRSEC File Validation Rules Preserved:
 * - User ID: Exactly 8 characters (PIC X(08) from CSUSR01Y.cpy)
 * - First Name: Max 20 characters (PIC X(20))
 * - Last Name: Max 20 characters (PIC X(20))
 * - Password: Minimum 8 characters (PIC X(08), BCrypt hashed on backend)
 * - User Type: 'A' (Admin) or 'U' (User) enum (PIC X(01))
 * 
 * REST API Integration:
 * - GET /api/users: List users with search and pagination (page, pageSize, search query params)
 * - POST /api/users: Create new user (firstName, lastName, userId, password, userType)
 * - PUT /api/users/:id: Update existing user (firstName, lastName, password, userType)
 * - DELETE /api/users/:id: Delete user with confirmation
 * - POST /api/users/:id/reset-password: Reset user password (administrative function)
 * 
 * Error Message Handling:
 * - Transforms BMS ERRMSG field (POS=(23,1) COLOR=RED LENGTH=78) to Snackbar/Alert components
 * - Success messages via react-toastify toast notifications (auto-dismiss after 5 seconds)
 * - Validation errors displayed inline with Formik field-level error messages
 * 
 * PF Key Navigation Transformation:
 * - F3=Back → Cancel buttons and dialog close actions
 * - F4=Clear → Form reset buttons
 * - F5=Save/Delete → Save and Delete action buttons
 * - F7=Backward, F8=Forward → DataGrid pagination controls
 * - F12=Cancel/Exit → Cancel buttons in dialogs
 * - ENTER=Continue/Fetch/Add → Primary action buttons (Search, Save, Add)
 * 
 * Role-Based Security:
 * - Component requires ROLE_ADMIN authorization (enforced on backend)
 * - Admin-only features: user creation, deletion, role modification, password reset
 * - Two-tier role model: ROLE_USER ('U') and ROLE_ADMIN ('A') matching COBOL USER-TYPE
 * 
 * State Management:
 * - React component state (useState hooks) replacing CICS COMMAREA state management
 * - Redux for global user authentication state (current user role validation)
 * - Local state for user list, selected user, dialog open/close, loading, errors, pagination
 * 
 * Field Length and Validation Constraints (from COBOL):
 * - User ID: max 8 chars, alphanumeric, required, unique
 * - First Name: max 20 chars, alphabetic with spaces, required
 * - Last Name: max 20 chars, alphabetic with spaces, required
 * - Password: min 8 chars, required for creation, optional for update
 * - User Type: enum 'A' or 'U' only, required
 * 
 * @module components/user/UserManagementComponent
 * @requires react React core library with hooks (useState, useEffect, useMemo, useCallback)
 * @requires @mui/material Material-UI components for UI rendering
 * @requires @mui/x-data-grid DataGrid for user list table with pagination
 * @requires @mui/icons-material Material-UI icons for action buttons
 * @requires formik Formik for form state management and validation
 * @requires yup Yup for schema-based form validation
 * @requires react-router-dom React Router for navigation
 * @requires react-toastify Toast notifications for success/error messages
 * @requires ../common/Header Header component for page header
 * @requires ../common/ErrorBoundary ErrorBoundary for graceful error handling
 * @requires ../../services/apiClient API client for REST calls
 */

import { useState, useEffect, useMemo, useCallback } from 'react';
import {
  Box,
  Typography,
  Button,
  TextField,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Select,
  MenuItem,
  FormControl,
  InputLabel,
  Alert,
  Chip,
  IconButton,
  CircularProgress,
  Snackbar,
  Paper,
  Grid
} from '@mui/material';
import { DataGrid } from '@mui/x-data-grid';
import { Edit, Delete, Add, LockReset, Search, Close } from '@mui/icons-material';
import { Formik } from 'formik';
import * as yup from 'yup';
import { useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import Header from '../common/Header';
import ErrorBoundary from '../common/ErrorBoundary';
import apiClient from '../../services/apiClient';

/**
 * Yup Validation Schema for User Creation Form
 * 
 * Defines validation rules matching COBOL USRSEC file field constraints from CSUSR01Y.cpy:
 * - SEC-USR-ID PIC X(08): User ID exactly 8 characters
 * - SEC-USR-FNAME PIC X(20): First Name max 20 characters
 * - SEC-USR-LNAME PIC X(20): Last Name max 20 characters
 * - SEC-USR-PWD PIC X(08): Password minimum 8 characters
 * - SEC-USR-TYPE PIC X(01): User Type 'A' (Admin) or 'U' (User)
 * 
 * Integrates with Formik for automatic field validation on blur and form submission.
 */
const userCreateValidationSchema = yup.object({
  userId: yup
    .string()
    .required('User ID is required')
    .matches(/^[A-Za-z0-9]+$/, 'User ID must be alphanumeric')
    .length(8, 'User ID must be exactly 8 characters')
    .uppercase('User ID must be uppercase'),
  firstName: yup
    .string()
    .required('First Name is required')
    .max(20, 'First Name cannot exceed 20 characters')
    .matches(/^[A-Za-z\s]+$/, 'First Name must contain only letters and spaces'),
  lastName: yup
    .string()
    .required('Last Name is required')
    .max(20, 'Last Name cannot exceed 20 characters')
    .matches(/^[A-Za-z\s]+$/, 'Last Name must contain only letters and spaces'),
  password: yup
    .string()
    .required('Password is required')
    .min(8, 'Password must be at least 8 characters'),
  userType: yup
    .string()
    .required('User Type is required')
    .oneOf(['A', 'U'], 'User Type must be A (Admin) or U (User)')
});

/**
 * Yup Validation Schema for User Update Form
 * 
 * Similar to create schema but password is optional for updates
 * (allows updating other fields without changing password)
 */
const userUpdateValidationSchema = yup.object({
  firstName: yup
    .string()
    .required('First Name is required')
    .max(20, 'First Name cannot exceed 20 characters')
    .matches(/^[A-Za-z\s]+$/, 'First Name must contain only letters and spaces'),
  lastName: yup
    .string()
    .required('Last Name is required')
    .max(20, 'Last Name cannot exceed 20 characters')
    .matches(/^[A-Za-z\s]+$/, 'Last Name must contain only letters and spaces'),
  password: yup
    .string()
    .min(8, 'Password must be at least 8 characters')
    .nullable(),
  userType: yup
    .string()
    .required('User Type is required')
    .oneOf(['A', 'U'], 'User Type must be A (Admin) or U (User)')
});

/**
 * UserManagementComponent
 * 
 * Main functional component implementing complete user management functionality.
 * Transforms COUSR00C, COUSR01C, COUSR02C, COUSR03C COBOL programs into modern React UI.
 * 
 * Component State:
 * - users: Array of user objects from GET /api/users
 * - loading: Boolean loading state for async operations
 * - error: Error message string for display
 * - searchQuery: User ID search filter value (COUSR00 USRIDIN field)
 * - page: Current pagination page (0-indexed for DataGrid)
 * - pageSize: Number of users per page (10 to match COBOL screen)
 * - totalUsers: Total user count for pagination
 * - selectedUser: User object for edit/delete operations
 * - addDialogOpen: Boolean for add user dialog visibility
 * - editDialogOpen: Boolean for edit user dialog visibility
 * - deleteDialogOpen: Boolean for delete confirmation dialog visibility
 * - snackbarOpen: Boolean for success/error snackbar visibility
 * - snackbarMessage: String message for snackbar display
 * - snackbarSeverity: 'success' | 'error' | 'info' | 'warning'
 * 
 * @returns {JSX.Element} Rendered user management component
 */
const UserManagementComponent = () => {
  // ============================================================================
  // Component State
  // ============================================================================

  /**
   * User List State
   * Stores array of user objects retrieved from GET /api/users endpoint.
   * Maps COBOL COUSR00C working storage WS-USER-DATA with USER-REC OCCURS 10 TIMES.
   */
  const [users, setUsers] = useState([]);

  /**
   * Loading State
   * Indicates async operations in progress (fetch, create, update, delete).
   * Displays CircularProgress spinner during API calls.
   */
  const [loading, setLoading] = useState(false);

  /**
   * Error State
   * Stores error messages for display in Alert components.
   * Maps COBOL WS-ERR-FLG and WS-MESSAGE error handling patterns.
   */
  const [error, setError] = useState(null);

  /**
   * Search Query State
   * User ID search filter value from search TextField.
   * Maps COUSR00 BMS USRIDIN field (POS=(6,21) LENGTH=8).
   */
  const [searchQuery, setSearchQuery] = useState('');

  /**
   * Pagination State
   * - page: Current page number (0-indexed)
   * - pageSize: Users per page (10 matching COBOL screen 10-row table)
   * - totalUsers: Total user count for pagination calculation
   * 
   * Maps COBOL COUSR00C pagination logic with WS-PAGE-NUM and WS-REC-COUNT.
   */
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(10);
  const [totalUsers, setTotalUsers] = useState(0);

  /**
   * Selected User State
   * Stores user object for edit and delete operations.
   * Maps COBOL COUSR02C/COUSR03C CDEMO-CU00-USR-SELECTED field.
   */
  const [selectedUser, setSelectedUser] = useState(null);

  /**
   * Dialog Open States
   * Control visibility of add, edit, and delete dialogs.
   * Maps COBOL screen transitions between COUSR00, COUSR01, COUSR02, COUSR03.
   */
  const [addDialogOpen, setAddDialogOpen] = useState(false);
  const [editDialogOpen, setEditDialogOpen] = useState(false);
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);

  /**
   * Snackbar State for Toast Notifications
   * Displays success/error messages after operations complete.
   * Maps COUSR00/01/02/03 BMS ERRMSG field (POS=(23,1) COLOR=RED LENGTH=78).
   */
  const [snackbarOpen, setSnackbarOpen] = useState(false);
  const [snackbarMessage, setSnackbarMessage] = useState('');
  const [snackbarSeverity, setSnackbarSeverity] = useState('success');

  // ============================================================================
  // Hooks
  // ============================================================================

  /**
   * React Router Navigate Hook
   * Provides navigation function for back/cancel actions.
   * Maps COBOL COUSR00C F3=Back PF key to navigate to admin dashboard.
   */
  const navigate = useNavigate();

  // ============================================================================
  // Data Fetching Functions
  // ============================================================================

  /**
   * Fetch Users Function
   * 
   * Retrieves user list from GET /api/users endpoint with pagination and search parameters.
   * Maps COBOL COUSR00C PROCESS-ENTER-KEY paragraph sequential USRSEC file read logic.
   * 
   * API Request:
   * - Method: GET
   * - Endpoint: /api/users
   * - Query Params: page, pageSize, search (User ID filter)
   * 
   * Response Structure:
   * {
   *   users: [...],           // Array of user objects
   *   totalUsers: number,     // Total count for pagination
   *   currentPage: number,    // Current page number
   *   pageSize: number        // Users per page
   * }
   * 
   * COBOL Mapping (COUSR00C.cbl lines 150-220):
   * - START USRSEC KEY >= WS-USRSEC-KEY → GET /api/users with search filter
   * - READ USRSEC NEXT → Sequential user retrieval with pagination
   * - WS-REC-COUNT → totalUsers response field
   * - WS-PAGE-NUM → currentPage response field
   * 
   * @returns {Promise<void>}
   */
  const fetchUsers = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);

      // Build API query parameters
      // Maps COBOL START USRSEC KEY and pagination logic
      const params = {
        page: page + 1, // API expects 1-indexed pages, DataGrid uses 0-indexed
        pageSize: pageSize
      };

      // Add search filter if provided (User ID search)
      // Maps COUSR00 USRIDIN field search functionality
      if (searchQuery && searchQuery.trim()) {
        params.search = searchQuery.trim().toUpperCase();
      }

      // Execute GET request to retrieve user list
      const response = await apiClient.get('/users', { params });

      // Extract user data from response
      // Maps COBOL USER-REC working storage array population
      const { data } = response;
      setUsers(data.users || []);
      setTotalUsers(data.totalUsers || 0);

      // Clear any previous errors on successful fetch
      setError(null);
    } catch (err) {
      // Handle API errors
      // Maps COBOL HANDLE CONDITION ERROR error handling
      console.error('Error fetching users:', err);
      const errorMessage = err.message || 'Failed to load users. Please try again.';
      setError(errorMessage);
      
      // Display error toast notification
      // Maps BMS ERRMSG field display
      toast.error(errorMessage, {
        position: 'top-right',
        autoClose: 5000,
        hideProgressBar: false,
        closeOnClick: true,
        pauseOnHover: true
      });
    } finally {
      setLoading(false);
    }
  }, [page, pageSize, searchQuery]);

  /**
   * Effect: Fetch Users on Mount and Filter/Pagination Changes
   * 
   * Executes fetchUsers when component mounts or when pagination/search parameters change.
   * Maps COBOL COUSR00C MAIN-PARA initialization and screen refresh logic.
   */
  useEffect(() => {
    fetchUsers();
  }, [fetchUsers]);

  // ============================================================================
  // Event Handlers - Add User Dialog
  // ============================================================================

  /**
   * Handle Add User Button Click
   * 
   * Opens add user dialog for creating new user.
   * Maps COBOL COUSR01C program entry from COUSR00C selection.
   */
  const handleAddUserClick = () => {
    setAddDialogOpen(true);
  };

  /**
   * Handle Add User Dialog Close
   * 
   * Closes add user dialog without saving.
   * Maps COUSR01 BMS F3=Back and F12=Exit PF key functionality.
   */
  const handleAddDialogClose = () => {
    setAddDialogOpen(false);
  };

  /**
   * Handle Add User Submit
   * 
   * Processes add user form submission, creates new user via POST /api/users endpoint.
   * Maps COBOL COUSR01C PROCESS-ENTER-KEY paragraph and WRITE USRSEC file operation.
   * 
   * @param {Object} values - Form values from Formik
   * @param {Object} formikBag - Formik helper methods
   * @returns {Promise<void>}
   */
  const handleAddUserSubmit = async (values, { setSubmitting, setFieldError, resetForm }) => {
    try {
      setSubmitting(true);

      // Prepare request payload
      // Maps COBOL USRSEC record structure from CSUSR01Y.cpy
      const newUser = {
        userId: values.userId.toUpperCase(),
        firstName: values.firstName.trim(),
        lastName: values.lastName.trim(),
        password: values.password,
        userType: values.userType
      };

      // Execute POST request to create user
      // Maps COBOL EXEC CICS WRITE DATASET('USRSEC') FROM(SEC-USER-RECORD)
      await apiClient.post('/users', newUser);

      // Display success message
      // Maps COUSR01C success message display in ERRMSG field
      toast.success('User created successfully', {
        position: 'top-right',
        autoClose: 5000
      });

      // Close dialog and refresh user list
      setAddDialogOpen(false);
      resetForm();
      
      // Refresh user list to show newly created user
      // Maps COBOL return to COUSR00 list screen
      await fetchUsers();
    } catch (err) {
      // Handle API errors
      console.error('Error creating user:', err);
      
      // Display field-specific or general error
      // Maps COBOL validation error handling
      if (err.errors && typeof err.errors === 'object') {
        // Field-specific errors from backend validation
        Object.keys(err.errors).forEach((field) => {
          setFieldError(field, err.errors[field]);
        });
      } else {
        // General error message
        const errorMessage = err.message || 'Failed to create user. Please try again.';
        toast.error(errorMessage, {
          position: 'top-right',
          autoClose: 5000
        });
      }
    } finally {
      setSubmitting(false);
    }
  };

  // ============================================================================
  // Event Handlers - Edit User Dialog
  // ============================================================================

  /**
   * Handle Edit User Button Click
   * 
   * Opens edit user dialog with selected user data.
   * Maps COBOL COUSR02C program entry from COUSR00C selection with 'U' action.
   * 
   * @param {Object} user - Selected user object from DataGrid row
   */
  const handleEditUserClick = (user) => {
    setSelectedUser(user);
    setEditDialogOpen(true);
  };

  /**
   * Handle Edit User Dialog Close
   * 
   * Closes edit user dialog without saving changes.
   * Maps COUSR02 BMS F3=Save&Exit and F12=Cancel PF key functionality.
   */
  const handleEditDialogClose = () => {
    setEditDialogOpen(false);
    setSelectedUser(null);
  };

  /**
   * Handle Edit User Submit
   * 
   * Processes edit user form submission, updates user via PUT /api/users/:id endpoint.
   * Maps COBOL COUSR02C PROCESS-ENTER-KEY paragraph and REWRITE USRSEC file operation.
   * 
   * @param {Object} values - Form values from Formik
   * @param {Object} formikBag - Formik helper methods
   * @returns {Promise<void>}
   */
  const handleEditUserSubmit = async (values, { setSubmitting, setFieldError }) => {
    try {
      setSubmitting(true);

      // Prepare request payload
      // Maps COBOL USRSEC record structure update
      const updateData = {
        firstName: values.firstName.trim(),
        lastName: values.lastName.trim(),
        userType: values.userType
      };

      // Include password only if provided (optional for updates)
      if (values.password && values.password.trim()) {
        updateData.password = values.password;
      }

      // Execute PUT request to update user
      // Maps COBOL EXEC CICS REWRITE DATASET('USRSEC') FROM(SEC-USER-RECORD)
      await apiClient.put(`/users/${selectedUser.id}`, updateData);

      // Display success message
      toast.success('User updated successfully', {
        position: 'top-right',
        autoClose: 5000
      });

      // Close dialog and refresh user list
      setEditDialogOpen(false);
      setSelectedUser(null);
      
      // Refresh user list to show updated user
      await fetchUsers();
    } catch (err) {
      // Handle API errors
      console.error('Error updating user:', err);
      
      // Display field-specific or general error
      if (err.errors && typeof err.errors === 'object') {
        Object.keys(err.errors).forEach((field) => {
          setFieldError(field, err.errors[field]);
        });
      } else {
        const errorMessage = err.message || 'Failed to update user. Please try again.';
        toast.error(errorMessage, {
          position: 'top-right',
          autoClose: 5000
        });
      }
    } finally {
      setSubmitting(false);
    }
  };

  // ============================================================================
  // Event Handlers - Delete User Dialog
  // ============================================================================

  /**
   * Handle Delete User Button Click
   * 
   * Opens delete confirmation dialog with selected user data.
   * Maps COBOL COUSR03C program entry from COUSR00C selection with 'D' action.
   * 
   * @param {Object} user - Selected user object from DataGrid row
   */
  const handleDeleteUserClick = (user) => {
    setSelectedUser(user);
    setDeleteDialogOpen(true);
  };

  /**
   * Handle Delete User Dialog Close
   * 
   * Closes delete confirmation dialog without deleting.
   * Maps COUSR03 BMS F3=Back PF key functionality.
   */
  const handleDeleteDialogClose = () => {
    setDeleteDialogOpen(false);
    setSelectedUser(null);
  };

  /**
   * Handle Delete User Confirm
   * 
   * Processes delete user confirmation, deletes user via DELETE /api/users/:id endpoint.
   * Maps COBOL COUSR03C F5=Delete confirmation and DELETE USRSEC file operation.
   * 
   * @returns {Promise<void>}
   */
  const handleDeleteUserConfirm = async () => {
    try {
      setLoading(true);

      // Execute DELETE request to remove user
      // Maps COBOL EXEC CICS DELETE DATASET('USRSEC')
      await apiClient.delete(`/users/${selectedUser.id}`);

      // Display success message
      toast.success('User deleted successfully', {
        position: 'top-right',
        autoClose: 5000
      });

      // Close dialog and refresh user list
      setDeleteDialogOpen(false);
      setSelectedUser(null);
      
      // Refresh user list after deletion
      await fetchUsers();
    } catch (err) {
      // Handle API errors
      console.error('Error deleting user:', err);
      const errorMessage = err.message || 'Failed to delete user. Please try again.';
      toast.error(errorMessage, {
        position: 'top-right',
        autoClose: 5000
      });
    } finally {
      setLoading(false);
    }
  };

  // ============================================================================
  // Event Handlers - Password Reset
  // ============================================================================

  /**
   * Handle Reset Password Button Click
   * 
   * Resets user password via POST /api/users/:id/reset-password endpoint.
   * Administrative function to generate and email new password to user.
   * 
   * @param {Object} user - Selected user object from DataGrid row
   * @returns {Promise<void>}
   */
  const handleResetPasswordClick = async (user) => {
    try {
      // Confirm password reset action
      const confirmed = window.confirm(
        `Are you sure you want to reset password for user ${user.userId}? ` +
        `A new password will be generated and sent to the user.`
      );

      if (!confirmed) {
        return;
      }

      setLoading(true);

      // Execute POST request to reset password
      await apiClient.post(`/users/${user.id}/reset-password`);

      // Display success message
      toast.success(`Password reset successfully for user ${user.userId}`, {
        position: 'top-right',
        autoClose: 5000
      });
    } catch (err) {
      // Handle API errors
      console.error('Error resetting password:', err);
      const errorMessage = err.message || 'Failed to reset password. Please try again.';
      toast.error(errorMessage, {
        position: 'top-right',
        autoClose: 5000
      });
    } finally {
      setLoading(false);
    }
  };

  // ============================================================================
  // Event Handlers - Search and Pagination
  // ============================================================================

  /**
   * Handle Search Input Change
   * 
   * Updates search query state when user types in search TextField.
   * Maps COUSR00 BMS USRIDIN field input handling.
   * 
   * @param {Object} event - Input change event
   */
  const handleSearchChange = (event) => {
    setSearchQuery(event.target.value);
  };

  /**
   * Handle Search Submit
   * 
   * Executes user search when Enter key pressed or Search button clicked.
   * Resets pagination to first page and fetches filtered results.
   * Maps COUSR00C PROCESS-ENTER-KEY with search filter logic.
   */
  const handleSearchSubmit = () => {
    setPage(0); // Reset to first page
    fetchUsers(); // fetchUsers will use current searchQuery state
  };

  /**
   * Handle Clear Search
   * 
   * Clears search query and resets to full user list.
   * Maps COUSR00C F4=Clear PF key functionality.
   */
  const handleClearSearch = () => {
    setSearchQuery('');
    setPage(0);
    // fetchUsers will be triggered by useEffect on searchQuery change
  };

  /**
   * Handle Page Change
   * 
   * Updates current page when user navigates pagination controls.
   * Maps COUSR00C F7=Backward and F8=Forward PF key pagination logic.
   * 
   * @param {number} newPage - New page number (0-indexed)
   */
  const handlePageChange = (newPage) => {
    setPage(newPage);
  };

  /**
   * Handle Page Size Change
   * 
   * Updates page size when user changes rows per page selection.
   * Resets to first page after page size change.
   * 
   * @param {number} newPageSize - New page size
   */
  const handlePageSizeChange = (newPageSize) => {
    setPageSize(newPageSize);
    setPage(0); // Reset to first page
  };

  /**
   * Handle Back Button Click
   * 
   * Navigates back to admin dashboard or main menu.
   * Maps COUSR00C F3=Back PF key to return to COADM01C.
   */
  const handleBackClick = () => {
    navigate('/admin');
  };

  // ============================================================================
  // DataGrid Column Definitions
  // ============================================================================

  /**
   * DataGrid Columns Configuration
   * 
   * Defines column structure for user list table matching COUSR00 BMS screen layout:
   * - User ID column (8 chars, BLUE, POS=(10-19,12))
   * - First Name column (20 chars, BLUE, POS=(10-19,24))
   * - Last Name column (20 chars, BLUE, POS=(10-19,48))
   * - Type column (1 char, BLUE, POS=(10-19,73))
   * - Actions column (Edit, Delete, Reset Password buttons)
   * 
   * Uses useMemo to prevent unnecessary re-renders when columns config doesn't change.
   */
  const columns = useMemo(() => [
    {
      field: 'userId',
      headerName: 'User ID',
      width: 120,
      // Maps COUSR00 BMS USRID01-10 fields (LENGTH=8 POS=(10-19,12))
      description: 'User ID (8 characters)'
    },
    {
      field: 'firstName',
      headerName: 'First Name',
      width: 200,
      flex: 1,
      // Maps COUSR00 BMS FNAME01-10 fields (LENGTH=20 POS=(10-19,24))
      description: 'User first name (max 20 characters)'
    },
    {
      field: 'lastName',
      headerName: 'Last Name',
      width: 200,
      flex: 1,
      // Maps COUSR00 BMS LNAME01-10 fields (LENGTH=20 POS=(10-19,48))
      description: 'User last name (max 20 characters)'
    },
    {
      field: 'userType',
      headerName: 'Type',
      width: 100,
      // Maps COUSR00 BMS UTYPE01-10 fields (LENGTH=1 POS=(10-19,73))
      description: 'User type: A (Admin) or U (User)',
      renderCell: (params) => {
        // Display user type as colored chip
        // A=Admin in red, U=User in blue
        const isAdmin = params.value === 'A';
        return (
          <Chip
            label={isAdmin ? 'Admin' : 'User'}
            color={isAdmin ? 'error' : 'primary'}
            size="small"
            sx={{ fontWeight: 600 }}
          />
        );
      }
    },
    {
      field: 'actions',
      headerName: 'Actions',
      width: 200,
      sortable: false,
      filterable: false,
      // Maps COUSR00 BMS selection logic with 'U' for Update and 'D' for Delete
      description: 'User management actions (Edit, Delete, Reset Password)',
      renderCell: (params) => {
        const user = params.row;
        return (
          <Box sx={{ display: 'flex', gap: 0.5 }}>
            {/* Edit User Button - Maps 'U' selection in COUSR00 */}
            <IconButton
              size="small"
              color="primary"
              onClick={() => handleEditUserClick(user)}
              title="Edit User"
              aria-label="edit user"
            >
              <Edit fontSize="small" />
            </IconButton>

            {/* Delete User Button - Maps 'D' selection in COUSR00 */}
            <IconButton
              size="small"
              color="error"
              onClick={() => handleDeleteUserClick(user)}
              title="Delete User"
              aria-label="delete user"
            >
              <Delete fontSize="small" />
            </IconButton>

            {/* Reset Password Button - Administrative function */}
            <IconButton
              size="small"
              color="warning"
              onClick={() => handleResetPasswordClick(user)}
              title="Reset Password"
              aria-label="reset password"
            >
              <LockReset fontSize="small" />
            </IconButton>
          </Box>
        );
      }
    }
  ], []);

  // ============================================================================
  // Component Rendering
  // ============================================================================

  return (
    <ErrorBoundary>
      {/* Page Header - Maps COUSR00 BMS header fields */}
      <Header pageTitle="User Management" />

      <Box sx={{ padding: 3 }}>
        {/* Page Title and Instructions */}
        <Box sx={{ marginBottom: 3 }}>
          <Typography variant="h4" gutterBottom>
            User Management
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Manage system users with administrative privileges. Create, update, and delete user accounts.
          </Typography>
        </Box>

        {/* Error Alert Display */}
        {error && (
          <Alert
            severity="error"
            onClose={() => setError(null)}
            sx={{ marginBottom: 2 }}
          >
            {error}
          </Alert>
        )}

        {/* Search and Action Toolbar */}
        <Paper elevation={2} sx={{ padding: 2, marginBottom: 3 }}>
          <Grid container spacing={2} alignItems="center">
            {/* Search Field - Maps COUSR00 BMS USRIDIN field */}
            <Grid item xs={12} sm={6} md={4}>
              <TextField
                label="Search User ID"
                value={searchQuery}
                onChange={handleSearchChange}
                onKeyPress={(e) => {
                  if (e.key === 'Enter') {
                    handleSearchSubmit();
                  }
                }}
                placeholder="Enter User ID"
                size="small"
                fullWidth
                InputProps={{
                  endAdornment: searchQuery && (
                    <IconButton
                      size="small"
                      onClick={handleClearSearch}
                      title="Clear search"
                    >
                      <Close fontSize="small" />
                    </IconButton>
                  )
                }}
              />
            </Grid>

            {/* Search Button - Maps COUSR00 ENTER key */}
            <Grid item xs={12} sm={3} md={2}>
              <Button
                variant="contained"
                color="primary"
                startIcon={<Search />}
                onClick={handleSearchSubmit}
                fullWidth
                size="medium"
              >
                Search
              </Button>
            </Grid>

            {/* Spacer */}
            <Grid item xs={12} sm={3} md={4} />

            {/* Add User Button - Maps COUSR00 to COUSR01 program transition */}
            <Grid item xs={12} sm={12} md={2}>
              <Button
                variant="contained"
                color="success"
                startIcon={<Add />}
                onClick={handleAddUserClick}
                fullWidth
                size="medium"
              >
                Add User
              </Button>
            </Grid>
          </Grid>
        </Paper>

        {/* User List DataGrid - Maps COUSR00 BMS 10-row table */}
        <Paper elevation={2} sx={{ height: 600, width: '100%' }}>
          <DataGrid
            rows={users}
            columns={columns}
            pageSize={pageSize}
            page={page}
            rowCount={totalUsers}
            paginationMode="server"
            onPageChange={handlePageChange}
            onPageSizeChange={handlePageSizeChange}
            rowsPerPageOptions={[10, 25, 50]}
            loading={loading}
            disableSelectionOnClick
            sx={{
              '& .MuiDataGrid-cell': {
                borderBottom: '1px solid rgba(224, 224, 224, 1)'
              },
              '& .MuiDataGrid-columnHeaders': {
                backgroundColor: '#f5f5f5',
                fontWeight: 600
              }
            }}
          />
        </Paper>

        {/* Back Button - Maps COUSR00 F3=Back PF key */}
        <Box sx={{ marginTop: 3, display: 'flex', justifyContent: 'flex-start' }}>
          <Button
            variant="outlined"
            onClick={handleBackClick}
            size="large"
          >
            Back to Admin
          </Button>
        </Box>

        {/* Add User Dialog - Maps COUSR01 BMS screen */}
        <Dialog
          open={addDialogOpen}
          onClose={handleAddDialogClose}
          maxWidth="sm"
          fullWidth
        >
          <DialogTitle>
            <Typography variant="h6">Add New User</Typography>
          </DialogTitle>
          
          <Formik
            initialValues={{
              userId: '',
              firstName: '',
              lastName: '',
              password: '',
              userType: 'U'
            }}
            validationSchema={userCreateValidationSchema}
            onSubmit={handleAddUserSubmit}
          >
            {({ values, errors, touched, handleChange, handleBlur, handleSubmit, isSubmitting }) => (
              <form onSubmit={handleSubmit}>
                <DialogContent>
                  <Grid container spacing={2}>
                    {/* First Name Field - Maps COUSR01 BMS FNAME */}
                    <Grid item xs={12}>
                      <TextField
                        name="firstName"
                        label="First Name"
                        value={values.firstName}
                        onChange={handleChange}
                        onBlur={handleBlur}
                        error={touched.firstName && Boolean(errors.firstName)}
                        helperText={touched.firstName && errors.firstName}
                        fullWidth
                        required
                        autoFocus
                        inputProps={{ maxLength: 20 }}
                      />
                    </Grid>

                    {/* Last Name Field - Maps COUSR01 BMS LNAME */}
                    <Grid item xs={12}>
                      <TextField
                        name="lastName"
                        label="Last Name"
                        value={values.lastName}
                        onChange={handleChange}
                        onBlur={handleBlur}
                        error={touched.lastName && Boolean(errors.lastName)}
                        helperText={touched.lastName && errors.lastName}
                        fullWidth
                        required
                        inputProps={{ maxLength: 20 }}
                      />
                    </Grid>

                    {/* User ID Field - Maps COUSR01 BMS USERID */}
                    <Grid item xs={12}>
                      <TextField
                        name="userId"
                        label="User ID"
                        value={values.userId}
                        onChange={(e) => {
                          // Auto-uppercase user ID input
                          e.target.value = e.target.value.toUpperCase();
                          handleChange(e);
                        }}
                        onBlur={handleBlur}
                        error={touched.userId && Boolean(errors.userId)}
                        helperText={touched.userId && errors.userId || '8 characters, alphanumeric'}
                        fullWidth
                        required
                        inputProps={{ maxLength: 8 }}
                      />
                    </Grid>

                    {/* Password Field - Maps COUSR01 BMS PASSWD (DRK attribute) */}
                    <Grid item xs={12}>
                      <TextField
                        name="password"
                        label="Password"
                        type="password"
                        value={values.password}
                        onChange={handleChange}
                        onBlur={handleBlur}
                        error={touched.password && Boolean(errors.password)}
                        helperText={touched.password && errors.password || 'Minimum 8 characters'}
                        fullWidth
                        required
                        inputProps={{ minLength: 8 }}
                      />
                    </Grid>

                    {/* User Type Field - Maps COUSR01 BMS USRTYPE */}
                    <Grid item xs={12}>
                      <FormControl fullWidth required error={touched.userType && Boolean(errors.userType)}>
                        <InputLabel>User Type</InputLabel>
                        <Select
                          name="userType"
                          value={values.userType}
                          onChange={handleChange}
                          onBlur={handleBlur}
                          label="User Type"
                        >
                          <MenuItem value="U">User (Regular)</MenuItem>
                          <MenuItem value="A">Admin (Administrative)</MenuItem>
                        </Select>
                        {touched.userType && errors.userType && (
                          <Typography variant="caption" color="error" sx={{ marginTop: 0.5, marginLeft: 1.5 }}>
                            {errors.userType}
                          </Typography>
                        )}
                      </FormControl>
                    </Grid>
                  </Grid>
                </DialogContent>

                <DialogActions sx={{ padding: 2, paddingTop: 0 }}>
                  {/* Cancel Button - Maps COUSR01 F12=Exit */}
                  <Button onClick={handleAddDialogClose} disabled={isSubmitting}>
                    Cancel
                  </Button>
                  
                  {/* Add User Button - Maps COUSR01 ENTER=Add User */}
                  <Button
                    type="submit"
                    variant="contained"
                    color="primary"
                    disabled={isSubmitting}
                    startIcon={isSubmitting && <CircularProgress size={20} />}
                  >
                    {isSubmitting ? 'Adding...' : 'Add User'}
                  </Button>
                </DialogActions>
              </form>
            )}
          </Formik>
        </Dialog>

        {/* Edit User Dialog - Maps COUSR02 BMS screen */}
        <Dialog
          open={editDialogOpen}
          onClose={handleEditDialogClose}
          maxWidth="sm"
          fullWidth
        >
          <DialogTitle>
            <Typography variant="h6">Edit User</Typography>
          </DialogTitle>
          
          {selectedUser && (
            <Formik
              initialValues={{
                firstName: selectedUser.firstName || '',
                lastName: selectedUser.lastName || '',
                password: '',
                userType: selectedUser.userType || 'U'
              }}
              validationSchema={userUpdateValidationSchema}
              onSubmit={handleEditUserSubmit}
            >
              {({ values, errors, touched, handleChange, handleBlur, handleSubmit, isSubmitting }) => (
                <form onSubmit={handleSubmit}>
                  <DialogContent>
                    <Grid container spacing={2}>
                      {/* Display User ID (read-only) */}
                      <Grid item xs={12}>
                        <TextField
                          label="User ID"
                          value={selectedUser.userId}
                          fullWidth
                          disabled
                          helperText="User ID cannot be changed"
                        />
                      </Grid>

                      {/* First Name Field - Maps COUSR02 BMS FNAME */}
                      <Grid item xs={12}>
                        <TextField
                          name="firstName"
                          label="First Name"
                          value={values.firstName}
                          onChange={handleChange}
                          onBlur={handleBlur}
                          error={touched.firstName && Boolean(errors.firstName)}
                          helperText={touched.firstName && errors.firstName}
                          fullWidth
                          required
                          inputProps={{ maxLength: 20 }}
                        />
                      </Grid>

                      {/* Last Name Field - Maps COUSR02 BMS LNAME */}
                      <Grid item xs={12}>
                        <TextField
                          name="lastName"
                          label="Last Name"
                          value={values.lastName}
                          onChange={handleChange}
                          onBlur={handleBlur}
                          error={touched.lastName && Boolean(errors.lastName)}
                          helperText={touched.lastName && errors.lastName}
                          fullWidth
                          required
                          inputProps={{ maxLength: 20 }}
                        />
                      </Grid>

                      {/* Password Field - Maps COUSR02 BMS PASSWD (optional for update) */}
                      <Grid item xs={12}>
                        <TextField
                          name="password"
                          label="Password"
                          type="password"
                          value={values.password}
                          onChange={handleChange}
                          onBlur={handleBlur}
                          error={touched.password && Boolean(errors.password)}
                          helperText={touched.password && errors.password || 'Leave blank to keep current password'}
                          fullWidth
                          inputProps={{ minLength: 8 }}
                        />
                      </Grid>

                      {/* User Type Field - Maps COUSR02 BMS USRTYPE */}
                      <Grid item xs={12}>
                        <FormControl fullWidth required error={touched.userType && Boolean(errors.userType)}>
                          <InputLabel>User Type</InputLabel>
                          <Select
                            name="userType"
                            value={values.userType}
                            onChange={handleChange}
                            onBlur={handleBlur}
                            label="User Type"
                          >
                            <MenuItem value="U">User (Regular)</MenuItem>
                            <MenuItem value="A">Admin (Administrative)</MenuItem>
                          </Select>
                          {touched.userType && errors.userType && (
                            <Typography variant="caption" color="error" sx={{ marginTop: 0.5, marginLeft: 1.5 }}>
                              {errors.userType}
                            </Typography>
                          )}
                        </FormControl>
                      </Grid>
                    </Grid>
                  </DialogContent>

                  <DialogActions sx={{ padding: 2, paddingTop: 0 }}>
                    {/* Cancel Button - Maps COUSR02 F12=Cancel */}
                    <Button onClick={handleEditDialogClose} disabled={isSubmitting}>
                      Cancel
                    </Button>
                    
                    {/* Save Button - Maps COUSR02 F5=Save */}
                    <Button
                      type="submit"
                      variant="contained"
                      color="primary"
                      disabled={isSubmitting}
                      startIcon={isSubmitting && <CircularProgress size={20} />}
                    >
                      {isSubmitting ? 'Saving...' : 'Save Changes'}
                    </Button>
                  </DialogActions>
                </form>
              )}
            </Formik>
          )}
        </Dialog>

        {/* Delete User Confirmation Dialog - Maps COUSR03 BMS screen */}
        <Dialog
          open={deleteDialogOpen}
          onClose={handleDeleteDialogClose}
          maxWidth="sm"
          fullWidth
        >
          <DialogTitle>
            <Typography variant="h6" color="error">
              Delete User Confirmation
            </Typography>
          </DialogTitle>
          
          <DialogContent>
            {selectedUser && (
              <>
                <Alert severity="warning" sx={{ marginBottom: 2 }}>
                  <Typography variant="body2" fontWeight="bold">
                    Warning: This action cannot be undone!
                  </Typography>
                  <Typography variant="body2">
                    Are you sure you want to delete this user?
                  </Typography>
                </Alert>

                <Grid container spacing={2}>
                  {/* User ID - Read Only - Maps COUSR03 BMS USRIDIN (ASKIP) */}
                  <Grid item xs={12}>
                    <TextField
                      label="User ID"
                      value={selectedUser.userId}
                      fullWidth
                      disabled
                    />
                  </Grid>

                  {/* First Name - Read Only - Maps COUSR03 BMS FNAME (ASKIP) */}
                  <Grid item xs={12}>
                    <TextField
                      label="First Name"
                      value={selectedUser.firstName}
                      fullWidth
                      disabled
                    />
                  </Grid>

                  {/* Last Name - Read Only - Maps COUSR03 BMS LNAME (ASKIP) */}
                  <Grid item xs={12}>
                    <TextField
                      label="Last Name"
                      value={selectedUser.lastName}
                      fullWidth
                      disabled
                    />
                  </Grid>

                  {/* User Type - Read Only - Maps COUSR03 BMS USRTYPE (ASKIP) */}
                  <Grid item xs={12}>
                    <TextField
                      label="User Type"
                      value={selectedUser.userType === 'A' ? 'Admin (Administrative)' : 'User (Regular)'}
                      fullWidth
                      disabled
                    />
                  </Grid>
                </Grid>
              </>
            )}
          </DialogContent>

          <DialogActions sx={{ padding: 2, paddingTop: 0 }}>
            {/* Cancel Button - Maps COUSR03 F3=Back */}
            <Button onClick={handleDeleteDialogClose} disabled={loading}>
              Cancel
            </Button>
            
            {/* Delete Button - Maps COUSR03 F5=Delete */}
            <Button
              variant="contained"
              color="error"
              onClick={handleDeleteUserConfirm}
              disabled={loading}
              startIcon={loading && <CircularProgress size={20} color="inherit" />}
            >
              {loading ? 'Deleting...' : 'Delete User'}
            </Button>
          </DialogActions>
        </Dialog>

        {/* Snackbar for Notifications (not used since we use react-toastify) */}
        {/* Kept for potential future use or alternative notification pattern */}
        <Snackbar
          open={snackbarOpen}
          autoHideDuration={5000}
          onClose={() => setSnackbarOpen(false)}
          anchorOrigin={{ vertical: 'top', horizontal: 'right' }}
        >
          <Alert
            onClose={() => setSnackbarOpen(false)}
            severity={snackbarSeverity}
            sx={{ width: '100%' }}
          >
            {snackbarMessage}
          </Alert>
        </Snackbar>
      </Box>
    </ErrorBoundary>
  );
};

// Export component as default
export default UserManagementComponent;

