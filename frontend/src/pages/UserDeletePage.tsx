/**
 * UserDeletePage Component
 * 
 * Converted from COBOL program COUSR03C.cbl and BMS map COUSR03.bms.
 * Displays user information in read-only format and provides deletion confirmation.
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 * 
 * Original COBOL Program: app/cbl/COUSR03C.cbl
 * Original BMS Map: app/bms/COUSR03.bms (24x80 3270 terminal screen)
 * Original Copybook: app/cpy/CSUSR01Y.cpy (SEC-USER-DATA structure)
 * 
 * COBOL to React Conversion:
 * - COUSR03.bms USRIDIN field (POS=(6,21), LENGTH=8) → URL parameter userId
 * - COUSR03.bms FNAME field (POS=(11,18), LENGTH=20, ASKIP) → read-only Typography
 * - COUSR03.bms LNAME field (POS=(13,18), LENGTH=20, ASKIP) → read-only Typography
 * - COUSR03.bms USRTYPE field (POS=(15,17), LENGTH=1, ASKIP) → read-only Typography with description
 * - F5=Delete function key → Delete button with ConfirmDialog
 * - F3=Back function key → Cancel button navigation
 * - EXEC CICS READ FILE('USRSEC') → REST GET /api/users/:userId
 * - EXEC CICS DELETE FILE('USRSEC') → REST DELETE /api/users/:userId
 * - EXEC CICS XCTL PROGRAM('COUSR00C') → navigate('/admin/users')
 * 
 * Business Logic Preservation (per Section 0.7.2):
 * - Requires admin role (SEC-USR-TYPE = 'A') for user deletion
 * - Displays user information before deletion for verification
 * - Requires explicit confirmation (Y/N in COBOL, dialog button in React)
 * - Cannot delete non-existent users (COBOL file-status 23 → HTTP 404)
 * - May fail if user has dependencies (backend enforces referential integrity)
 * 
 * Security Requirements (per Section 0.7.9):
 * - Admin role check: useAuth().user.userType === USER_TYPES.ADMIN
 * - JWT token automatically attached by api.ts interceptor
 * - Backend enforces @PreAuthorize("hasRole('ADMIN')") on DELETE endpoint
 * - User cannot delete their own account (backend validation)
 * 
 * Error Handling (per Section 0.7.6):
 * - 401 Unauthorized: Invalid/missing JWT → redirect to login
 * - 403 Forbidden: Non-admin user or self-deletion attempt
 * - 404 Not Found: User ID does not exist
 * - 409 Conflict: User has dependent records preventing deletion
 * - 500 Internal Server Error: Backend database error
 * 
 * Performance Requirements (per Section 0.7.7):
 * - Page load: < 200ms for user data fetch
 * - Deletion operation: < 200ms response time
 * 
 * @see COBOL program: app/cbl/COUSR03C.cbl
 * @see BMS map: app/bms/COUSR03.bms
 * @see Backend: UserController.deleteUser()
 * @see Backend: UserService.deleteUser()
 */

import React, { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Container,
  Box,
  Card,
  CardContent,
  Typography,
  Button,
  Alert,
  Grid,
  Divider,
  Paper,
} from '@mui/material';
import { getUserById, deleteUser } from '../services/userService';
import { User } from '../types/user';
import ConfirmDialog from '../components/common/ConfirmDialog';
import Header from '../components/common/Header';
import Footer from '../components/common/Footer';
import ErrorMessage from '../components/common/ErrorMessage';
import LoadingSpinner from '../components/common/LoadingSpinner';
import { useAuth } from '../hooks/useAuth';
import { USER_TYPES } from '../utils/constants';

/**
 * UserDeletePage Component
 * 
 * Page component for user deletion confirmation and execution.
 * 
 * Features:
 * - Fetches user data by ID from URL parameter
 * - Displays user information in read-only format
 * - Shows confirmation dialog before deletion
 * - Executes deletion via REST API
 * - Enforces admin-only access control
 * - Navigates to user list after successful deletion
 * - Handles all error scenarios with appropriate messages
 * 
 * Component State:
 * - user: User object fetched from backend
 * - loading: Boolean flag for async operations
 * - error: Error message string for display
 * - confirmDialogOpen: Boolean flag for confirmation dialog visibility
 * - isDeleting: Boolean flag for deletion in progress
 * 
 * URL Parameters:
 * - userId: User ID to delete (from route /admin/users/delete/:userId)
 * 
 * Navigation Flow:
 * - Entry: From UserListPage clicking delete icon/button
 * - Success: Navigate to /admin/users
 * - Cancel: Navigate to /admin/users
 * - Error: Stay on page and display error message
 * 
 * COBOL Program Flow (COUSR03C.cbl):
 * 1. Receive map COUSR03 with user ID input
 * 2. EXEC CICS READ FILE('USRSEC') to fetch user data
 * 3. Display user data in read-only fields
 * 4. Wait for F5=Delete or F3=Back function key
 * 5. If F5 pressed: EXEC CICS DELETE FILE('USRSEC')
 * 6. Display success/error message
 * 7. EXEC CICS XCTL PROGRAM('COUSR00C') to return to list
 * 
 * React Component Flow:
 * 1. Extract userId from URL parameter
 * 2. useEffect: call getUserById() API to fetch user data
 * 3. Render user data in Card with read-only Typography fields
 * 4. User clicks Delete button: show ConfirmDialog
 * 5. User confirms deletion: call deleteUser() API
 * 6. On success: navigate to /admin/users
 * 7. On error: display error message and stay on page
 */
const UserDeletePage: React.FC = () => {
  // Extract userId from URL parameters
  // Replaces COBOL RECEIVE MAP COUSR03 MAPONLY to get USRIDIN field
  const { userId } = useParams<{ userId: string }>();
  
  // Navigation hook for programmatic routing
  // Replaces COBOL EXEC CICS XCTL PROGRAM('COUSR00C')
  const navigate = useNavigate();
  
  // Authentication context for role-based access control
  // Replaces COBOL SEC-USR-TYPE check from CSUSR01Y.cpy
  const { user: currentUser, isAuthenticated } = useAuth();
  
  // Component state
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [confirmDialogOpen, setConfirmDialogOpen] = useState<boolean>(false);
  const [isDeleting, setIsDeleting] = useState<boolean>(false);

  /**
   * Fetch user data on component mount
   * 
   * Converted from COBOL:
   * EXEC CICS READ FILE('USRSEC') 
   *      RIDFLD(WS-USER-ID) 
   *      INTO(SEC-USER-DATA)
   *      RESP(WS-RESP-CD)
   * END-EXEC.
   * 
   * Error Handling:
   * - RESP = DFHRESP(NORMAL) → User found, display data
   * - RESP = DFHRESP(NOTFND) → User not found (file-status 23), display error
   * - RESP = other → System error, display error message
   */
  useEffect(() => {
    // Check authentication - redirect if not logged in
    if (!isAuthenticated) {
      navigate('/login');
      return;
    }

    // Check admin role - only admins can delete users
    // Replaces COBOL: IF SEC-USR-TYPE NOT = 'A' PERFORM UNAUTHORIZED-ERROR END-IF
    if (currentUser?.userType !== USER_TYPES.ADMIN) {
      setError('Unauthorized: Only administrators can delete users.');
      setLoading(false);
      return;
    }

    // Validate userId parameter exists
    if (!userId) {
      setError('Invalid request: User ID is required.');
      setLoading(false);
      return;
    }

    // Fetch user data from backend
    const fetchUser = async () => {
      try {
        setLoading(true);
        setError(null);
        
        // Call REST API GET /api/users/:userId
        // Replaces COBOL EXEC CICS READ FILE('USRSEC')
        const userData = await getUserById(userId);
        
        // Store fetched user data in state
        // Replaces COBOL MOVE SEC-USER-DATA to BMS map fields
        setUser(userData);
      } catch (err: any) {
        // Handle API errors
        // Replaces COBOL file-status and RESP code checks
        if (err.status === 404) {
          setError(`User not found: ${userId}`);
        } else if (err.status === 403) {
          setError('Unauthorized: Insufficient permissions to view user.');
        } else if (err.status === 401) {
          setError('Session expired. Please login again.');
          // Redirect to login after short delay
          setTimeout(() => navigate('/login'), 2000);
        } else {
          setError(err.message || 'Failed to load user data. Please try again.');
        }
      } finally {
        setLoading(false);
      }
    };

    fetchUser();
  }, [userId, isAuthenticated, currentUser, navigate]);

  /**
   * Handle delete button click
   * Shows confirmation dialog before deletion
   * 
   * Replaces COBOL F5=Delete function key handling
   */
  const handleDeleteClick = () => {
    setConfirmDialogOpen(true);
  };

  /**
   * Handle cancel button click
   * Navigate back to user list without deletion
   * 
   * Converted from COBOL F3=Back function key:
   * EXEC CICS XCTL PROGRAM('COUSR00C') 
   *      COMMAREA(CARDDEMO-COMMAREA)
   * END-EXEC.
   */
  const handleCancel = () => {
    navigate('/admin/users');
  };

  /**
   * Handle confirmation dialog cancel
   * Close dialog without performing deletion
   * 
   * Replaces COBOL confirmation field N (No) input
   */
  const handleConfirmCancel = () => {
    setConfirmDialogOpen(false);
  };

  /**
   * Handle deletion confirmation
   * Execute user deletion via REST API
   * 
   * Converted from COBOL deletion logic:
   * EXEC CICS DELETE FILE('USRSEC') 
   *      RIDFLD(WS-USER-ID)
   *      RESP(WS-RESP-CD)
   * END-EXEC.
   * IF WS-RESP-CD = DFHRESP(NORMAL)
   *    MOVE 'User deleted successfully' TO ERRMSG
   *    EXEC CICS XCTL PROGRAM('COUSR00C')
   * ELSE
   *    MOVE 'Error deleting user' TO ERRMSG
   * END-IF.
   * 
   * Error Handling:
   * - Success: Navigate to user list with success message
   * - 404 Not Found: User already deleted or doesn't exist
   * - 409 Conflict: User has dependent records (referential integrity violation)
   * - 403 Forbidden: Attempting to delete own account (backend validation)
   */
  const handleConfirmDelete = async () => {
    if (!userId) return;

    try {
      setIsDeleting(true);
      setError(null);
      
      // Call REST API DELETE /api/users/:userId
      // Replaces COBOL EXEC CICS DELETE FILE('USRSEC')
      await deleteUser(userId);
      
      // Close confirmation dialog
      setConfirmDialogOpen(false);
      
      // Navigate back to user list on success
      // Replaces COBOL EXEC CICS XCTL PROGRAM('COUSR00C')
      navigate('/admin/users', { 
        state: { 
          message: `User ${userId} deleted successfully.`,
          severity: 'success'
        } 
      });
    } catch (err: any) {
      // Handle deletion errors
      // Replaces COBOL file-status and RESP code error handling
      setConfirmDialogOpen(false);
      
      if (err.status === 404) {
        setError(`User not found: ${userId}. May have been already deleted.`);
      } else if (err.status === 409) {
        setError('Cannot delete user: User has associated records or dependencies.');
      } else if (err.status === 403) {
        setError('Cannot delete user: You cannot delete your own account or lack permissions.');
      } else if (err.status === 401) {
        setError('Session expired. Please login again.');
        setTimeout(() => navigate('/login'), 2000);
      } else {
        setError(err.message || 'Failed to delete user. Please try again.');
      }
    } finally {
      setIsDeleting(false);
    }
  };

  /**
   * Get user type description
   * Maps single-character user type code to readable label
   * 
   * Replaces COBOL 88-level condition names:
   * 88 IS-ADMIN VALUE 'A'.
   * 88 IS-USER VALUE 'U'.
   * 88 IS-OPERATOR VALUE 'R'.
   */
  const getUserTypeLabel = (userType: string): string => {
    switch (userType) {
      case USER_TYPES.ADMIN:
        return 'Administrator (A)';
      case USER_TYPES.USER:
        return 'Regular User (U)';
      case USER_TYPES.OPERATOR:
        return 'Operator (R)';
      default:
        return `Unknown (${userType})`;
    }
  };

  // Show loading spinner while fetching user data
  // Replaces COBOL EXEC CICS WAIT or blocking read operation
  if (loading) {
    return (
      <>
        <Header />
        <Container maxWidth="md" sx={{ mt: 4, mb: 4 }}>
          <LoadingSpinner message="Loading user information..." />
        </Container>
        <Footer />
      </>
    );
  }

  // Render component
  return (
    <>
      {/* Page Header - replaces BMS map header section (lines 1-2) */}
      <Header />

      <Container maxWidth="md" sx={{ mt: 4, mb: 4 }}>
        {/* Page Title - replaces BMS DFHMDF INITIAL='Delete User' at POS=(4,35) */}
        <Typography variant="h4" component="h1" gutterBottom align="center">
          Delete User
        </Typography>

        {/* Error Message Display - replaces BMS ERRMSG field at POS=(23,1) */}
        {error && (
          <Box sx={{ mb: 2 }}>
            <ErrorMessage message={error} onClose={() => setError(null)} />
          </Box>
        )}

        {/* Unauthorized Access Message - admin-only page */}
        {!loading && currentUser?.userType !== USER_TYPES.ADMIN && (
          <Alert severity="error" sx={{ mb: 2 }}>
            Access Denied: This page requires administrator privileges.
          </Alert>
        )}

        {/* Warning Message - irreversible operation notice */}
        {!error && user && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            Warning: Deleting a user is a permanent action and cannot be undone. 
            Please verify the user information below before proceeding.
          </Alert>
        )}

        {/* User Information Card - displays read-only user data */}
        {user && (
          <Card sx={{ mt: 2 }}>
            <CardContent>
              {/* Card Title */}
              <Typography variant="h6" gutterBottom>
                User Information
              </Typography>
              
              <Divider sx={{ mb: 2 }} />

              {/* User ID Field - replaces BMS USRIDIN at POS=(6,21), LENGTH=8, read-only */}
              <Grid container spacing={2}>
                <Grid item xs={12} sm={4}>
                  <Typography variant="subtitle2" color="text.secondary">
                    User ID:
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={8}>
                  <Typography variant="body1" fontWeight="medium">
                    {user.userId}
                  </Typography>
                </Grid>
              </Grid>

              <Divider sx={{ my: 2 }} />

              {/* First Name Field - replaces BMS FNAME at POS=(11,18), LENGTH=20, ASKIP */}
              <Grid container spacing={2}>
                <Grid item xs={12} sm={4}>
                  <Typography variant="subtitle2" color="text.secondary">
                    First Name:
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={8}>
                  <Typography variant="body1">
                    {user.userFirstName || '(Not provided)'}
                  </Typography>
                </Grid>
              </Grid>

              <Divider sx={{ my: 2 }} />

              {/* Last Name Field - replaces BMS LNAME at POS=(13,18), LENGTH=20, ASKIP */}
              <Grid container spacing={2}>
                <Grid item xs={12} sm={4}>
                  <Typography variant="subtitle2" color="text.secondary">
                    Last Name:
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={8}>
                  <Typography variant="body1">
                    {user.userLastName || '(Not provided)'}
                  </Typography>
                </Grid>
              </Grid>

              <Divider sx={{ my: 2 }} />

              {/* User Type Field - replaces BMS USRTYPE at POS=(15,17), LENGTH=1, ASKIP */}
              <Grid container spacing={2}>
                <Grid item xs={12} sm={4}>
                  <Typography variant="subtitle2" color="text.secondary">
                    User Type:
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={8}>
                  <Typography variant="body1">
                    {getUserTypeLabel(user.userType)}
                  </Typography>
                </Grid>
              </Grid>

              {/* Additional Metadata - audit fields not in COBOL */}
              <Divider sx={{ my: 2 }} />

              <Grid container spacing={2}>
                <Grid item xs={12} sm={4}>
                  <Typography variant="subtitle2" color="text.secondary">
                    Created At:
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={8}>
                  <Typography variant="body2" color="text.secondary">
                    {user.createdAt ? new Date(user.createdAt).toLocaleString() : 'N/A'}
                  </Typography>
                </Grid>
              </Grid>

              <Divider sx={{ my: 2 }} />

              <Grid container spacing={2}>
                <Grid item xs={12} sm={4}>
                  <Typography variant="subtitle2" color="text.secondary">
                    Last Login:
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={8}>
                  <Typography variant="body2" color="text.secondary">
                    {user.lastLoginTs ? new Date(user.lastLoginTs).toLocaleString() : 'Never'}
                  </Typography>
                </Grid>
              </Grid>
            </CardContent>
          </Card>
        )}

        {/* Action Buttons - replaces BMS function keys */}
        {user && (
          <Box sx={{ mt: 3, display: 'flex', gap: 2, justifyContent: 'center' }}>
            {/* Cancel Button - replaces F3=Back function key */}
            <Button
              variant="outlined"
              size="large"
              onClick={handleCancel}
              disabled={isDeleting}
            >
              Cancel
            </Button>
            
            {/* Delete Button - replaces F5=Delete function key */}
            <Button
              variant="contained"
              color="error"
              size="large"
              onClick={handleDeleteClick}
              disabled={isDeleting || currentUser?.userType !== USER_TYPES.ADMIN}
            >
              Delete User
            </Button>
          </Box>
        )}

        {/* Navigation hint - replaces BMS function key legend at POS=(24,1) */}
        {user && (
          <Paper elevation={0} sx={{ mt: 3, p: 2, bgcolor: 'grey.100' }}>
            <Typography variant="body2" color="text.secondary" align="center">
              <strong>Actions:</strong> Cancel = Return to user list without deletion | 
              Delete User = Permanently remove this user (requires confirmation)
            </Typography>
          </Paper>
        )}
      </Container>

      {/* Page Footer - replaces BMS map footer section */}
      <Footer />

      {/* Confirmation Dialog - replaces COUSR03.bms confirmation with Y/N input */}
      <ConfirmDialog
        open={confirmDialogOpen}
        title="Confirm User Deletion"
        message={`Are you sure you want to permanently delete user "${user?.userId}" (${user?.userFirstName} ${user?.userLastName})? This action cannot be undone.`}
        confirmText="Delete"
        cancelText="Cancel"
        confirmColor="error"
        onConfirm={handleConfirmDelete}
        onCancel={handleConfirmCancel}
        loading={isDeleting}
      />
    </>
  );
};

export default UserDeletePage;
