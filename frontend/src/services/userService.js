/**
 * User Management Service
 * 
 * Purpose: JavaScript service module for user administration operations communicating 
 * with Spring Boot backend REST API. Handles all user CRUD operations for administrative users.
 * 
 * Replaces COBOL Programs:
 * - COUSR00C.cbl (CU00 transaction - user list with VSAM STARTBR/READNEXT)
 * - COUSR01C.cbl (CU01 transaction - user add with VSAM WRITE)
 * - COUSR02C.cbl (CU02 transaction - user update with VSAM REWRITE)
 * - COUSR03C.cbl (CU03 transaction - user delete with VSAM DELETE)
 * 
 * VSAM File Transformation:
 * - USRSEC file operations → PostgreSQL user table via Spring Data JPA
 * - CSUSR01Y.cpy record structure → JSON user objects
 * 
 * Security Model:
 * - All operations require admin role authorization (ROLE_ADMIN)
 * - JWT token automatically injected by apiClient interceptor
 * - Replaces RACF security checks with Spring Security @PreAuthorize
 * 
 * User Type Mapping:
 * - 'A' = Admin user (USRTYPE-ADMIN from COBOL 88-level condition)
 * - 'U' = Regular/General user (USRTYPE-GENERAL-USER from COBOL 88-level condition)
 * 
 * Data Structure Mapping (CSUSR01Y.cpy → JSON):
 * - SEC-USR-ID (PIC X(08)) → userId (string, max 8 characters)
 * - SEC-USR-FNAME (PIC X(20)) → firstName (string, max 20 characters)
 * - SEC-USR-LNAME (PIC X(20)) → lastName (string, max 20 characters)
 * - SEC-USR-PWD (PIC X(08)) → password (string, BCrypt hashed server-side, never returned in responses)
 * - SEC-USR-TYPE (PIC X(01)) → userType (string, 'A' or 'U')
 * 
 * API Endpoints:
 * - GET /api/admin/users - List all users with optional search criteria
 * - GET /api/admin/users/{userId} - Get single user by ID
 * - POST /api/admin/users - Create new user
 * - PUT /api/admin/users/{userId} - Update existing user
 * - DELETE /api/admin/users/{userId} - Delete user
 * 
 * Migration Context: Section 0.6 File-by-File Transformation Plan specifies this file
 * transforms COBOL user management programs to REST API client service.
 */

import apiClient from '../utils/apiClient.js';

/**
 * Base path for user management endpoints
 * All user management operations require admin privileges
 */
const USER_ADMIN_BASE_PATH = '/admin/users';

/**
 * User type constants matching COBOL 88-level conditions
 * SEC-USR-TYPE PIC X(01) with 88 USRTYPE-ADMIN VALUE 'A', 88 USRTYPE-GENERAL-USER VALUE 'U'
 */
export const USER_TYPES = {
  ADMIN: 'A',
  USER: 'U',
};

/**
 * User type display names for UI rendering
 */
export const USER_TYPE_LABELS = {
  [USER_TYPES.ADMIN]: 'Admin',
  [USER_TYPES.USER]: 'Regular User',
};

/**
 * Validates user ID format (max 8 characters, uppercase alphanumeric)
 * Matches COBOL SEC-USR-ID PIC X(08) field validation
 * 
 * @param {string} userId - User ID to validate
 * @returns {boolean} True if valid, false otherwise
 */
const validateUserId = (userId) => {
  if (!userId || typeof userId !== 'string') {
    return false;
  }
  
  // Must be 1-8 characters, alphanumeric
  const userIdRegex = /^[A-Z0-9]{1,8}$/;
  return userIdRegex.test(userId.toUpperCase());
};

/**
 * Validates user type value
 * Must be 'A' (Admin) or 'U' (User) matching COBOL 88-level conditions
 * 
 * @param {string} userType - User type to validate
 * @returns {boolean} True if valid, false otherwise
 */
const validateUserType = (userType) => {
  return userType === USER_TYPES.ADMIN || userType === USER_TYPES.USER;
};

/**
 * Transforms error response from server into user-friendly error object
 * Handles common error scenarios from Spring Boot backend
 * 
 * @param {Error} error - Axios error object
 * @returns {Error} Transformed error with user-friendly message
 */
const handleApiError = (error) => {
  if (error.response) {
    // Server responded with error status
    const { status, data } = error.response;
    
    switch (status) {
      case 400:
        // Bad Request - validation errors
        return new Error(data.message || 'Invalid user data provided');
      
      case 401:
        // Unauthorized - non-admin user attempting user management
        // Should be caught by UI routing, but handle defensively
        return new Error('Unauthorized: Admin privileges required for user management');
      
      case 404:
        // Not Found - user does not exist
        return new Error(data.message || 'User not found');
      
      case 409:
        // Conflict - duplicate user ID
        return new Error(data.message || 'User ID already exists');
      
      case 422:
        // Unprocessable Entity - business logic validation failed
        return new Error(data.message || 'User validation failed');
      
      case 500:
        // Internal Server Error
        return new Error('Server error occurred while processing user operation');
      
      default:
        return new Error(data.message || `Error: ${status}`);
    }
  } else if (error.request) {
    // Request made but no response received
    return new Error('No response from server. Please check your connection.');
  } else {
    // Error in request configuration
    return new Error(error.message || 'Error configuring user request');
  }
};

/**
 * Get list of users with optional search criteria
 * 
 * Replaces: COUSR00C.cbl EXEC CICS STARTBR/READNEXT DATASET(USRSEC) browsing
 * Endpoint: GET /api/admin/users
 * 
 * Search Criteria (all optional):
 * - userId: Partial match on user ID (case-insensitive)
 * - firstName: Partial match on first name
 * - lastName: Partial match on last name
 * - userType: Exact match on user type ('A' or 'U')
 * 
 * Returns array of user objects (password excluded for security):
 * {
 *   userId: string,
 *   firstName: string,
 *   lastName: string,
 *   userType: string ('A' or 'U'),
 *   createdDate: string (ISO 8601),
 *   lastLoginDate: string (ISO 8601, may be null)
 * }
 * 
 * @param {Object} searchCriteria - Optional search parameters
 * @param {string} searchCriteria.userId - Filter by user ID (partial match)
 * @param {string} searchCriteria.firstName - Filter by first name
 * @param {string} searchCriteria.lastName - Filter by last name
 * @param {string} searchCriteria.userType - Filter by user type ('A' or 'U')
 * @returns {Promise<Array>} Promise resolving to array of user objects
 * @throws {Error} If request fails or unauthorized
 */
const getUsers = async (searchCriteria = {}) => {
  try {
    // Validate userType if provided
    if (searchCriteria.userType && !validateUserType(searchCriteria.userType)) {
      throw new Error(`Invalid user type. Must be '${USER_TYPES.ADMIN}' or '${USER_TYPES.USER}'`);
    }
    
    // Make GET request with search parameters as query params
    // axios.get automatically serializes params object to query string
    const response = await apiClient.get(USER_ADMIN_BASE_PATH, {
      params: searchCriteria,
    });
    
    // Backend returns array of user objects with password field excluded
    // Matches COBOL USER-REC OCCURS 10 TIMES structure but returns all users
    return response.data;
  } catch (error) {
    throw handleApiError(error);
  }
};

/**
 * Get single user by user ID
 * 
 * Replaces: COUSR00C.cbl EXEC CICS READ DATASET(USRSEC) for single user retrieval
 * Endpoint: GET /api/admin/users/{userId}
 * 
 * Returns complete user object (password excluded):
 * {
 *   userId: string,
 *   firstName: string,
 *   lastName: string,
 *   userType: string ('A' or 'U'),
 *   createdDate: string (ISO 8601),
 *   lastLoginDate: string (ISO 8601, may be null)
 * }
 * 
 * @param {string} userId - User ID to retrieve (max 8 characters)
 * @returns {Promise<Object>} Promise resolving to user object
 * @throws {Error} If userId invalid, user not found, or unauthorized
 */
const getUserById = async (userId) => {
  try {
    // Validate userId format (max 8 characters, uppercase)
    if (!validateUserId(userId)) {
      throw new Error('Invalid user ID format. Must be 1-8 alphanumeric characters.');
    }
    
    // Convert to uppercase to match COBOL behavior (PIC X fields typically uppercase)
    const normalizedUserId = userId.toUpperCase();
    
    // Make GET request to retrieve single user
    const response = await apiClient.get(`${USER_ADMIN_BASE_PATH}/${normalizedUserId}`);
    
    // Backend returns user object with password field excluded for security
    return response.data;
  } catch (error) {
    throw handleApiError(error);
  }
};

/**
 * Create new user
 * 
 * Replaces: COUSR01C.cbl EXEC CICS WRITE DATASET(USRSEC) user creation
 * Endpoint: POST /api/admin/users
 * 
 * Required fields in userData:
 * - userId: string (1-8 characters, alphanumeric, will be uppercased)
 * - password: string (minimum length enforced server-side, BCrypt hashed)
 * - firstName: string (max 20 characters, non-empty)
 * - lastName: string (max 20 characters, non-empty)
 * - userType: string ('A' for Admin or 'U' for Regular User)
 * 
 * Server-side validations:
 * - userId uniqueness (returns 409 Conflict if duplicate)
 * - Password strength requirements
 * - Field length constraints
 * - User type must be 'A' or 'U'
 * 
 * @param {Object} userData - User data object
 * @param {string} userData.userId - Unique user ID (max 8 characters)
 * @param {string} userData.password - User password (will be BCrypt hashed server-side)
 * @param {string} userData.firstName - User first name (max 20 characters)
 * @param {string} userData.lastName - User last name (max 20 characters)
 * @param {string} userData.userType - User type ('A' or 'U')
 * @returns {Promise<Object>} Promise resolving to created user object (without password)
 * @throws {Error} If validation fails, duplicate user ID, or unauthorized
 */
const createUser = async (userData) => {
  try {
    // Client-side validation before sending to server
    if (!userData.userId) {
      throw new Error('User ID is required');
    }
    
    if (!validateUserId(userData.userId)) {
      throw new Error('Invalid user ID format. Must be 1-8 alphanumeric characters.');
    }
    
    if (!userData.password || userData.password.trim() === '') {
      throw new Error('Password is required');
    }
    
    if (!userData.firstName || userData.firstName.trim() === '') {
      throw new Error('First name is required');
    }
    
    if (userData.firstName.length > 20) {
      throw new Error('First name must not exceed 20 characters');
    }
    
    if (!userData.lastName || userData.lastName.trim() === '') {
      throw new Error('Last name is required');
    }
    
    if (userData.lastName.length > 20) {
      throw new Error('Last name must not exceed 20 characters');
    }
    
    if (!userData.userType) {
      throw new Error('User type is required');
    }
    
    if (!validateUserType(userData.userType)) {
      throw new Error(`Invalid user type. Must be '${USER_TYPES.ADMIN}' or '${USER_TYPES.USER}'`);
    }
    
    // Normalize userId to uppercase matching COBOL behavior
    const normalizedUserData = {
      ...userData,
      userId: userData.userId.toUpperCase(),
      firstName: userData.firstName.trim(),
      lastName: userData.lastName.trim(),
    };
    
    // Make POST request to create user
    // Server will hash password with BCrypt before storing
    const response = await apiClient.post(USER_ADMIN_BASE_PATH, normalizedUserData);
    
    // Backend returns created user object without password field
    return response.data;
  } catch (error) {
    throw handleApiError(error);
  }
};

/**
 * Update existing user
 * 
 * Replaces: COUSR02C.cbl EXEC CICS REWRITE DATASET(USRSEC) user update
 * Endpoint: PUT /api/admin/users/{userId}
 * 
 * Updatable fields in userData:
 * - password: string (optional - only include if changing password)
 * - firstName: string (max 20 characters)
 * - lastName: string (max 20 characters)
 * - userType: string ('A' or 'U')
 * 
 * Note: userId cannot be changed (primary key)
 * 
 * Password Handling:
 * - If password field is included, it will be updated (BCrypt hashed server-side)
 * - If password field is omitted or null, existing password is retained
 * - This allows password reset by including new password in request
 * 
 * @param {string} userId - User ID to update (max 8 characters, immutable)
 * @param {Object} userData - Updated user data
 * @param {string} [userData.password] - New password (optional, only if changing)
 * @param {string} userData.firstName - Updated first name (max 20 characters)
 * @param {string} userData.lastName - Updated last name (max 20 characters)
 * @param {string} userData.userType - Updated user type ('A' or 'U')
 * @returns {Promise<Object>} Promise resolving to updated user object (without password)
 * @throws {Error} If validation fails, user not found, or unauthorized
 */
const updateUser = async (userId, userData) => {
  try {
    // Validate userId format
    if (!validateUserId(userId)) {
      throw new Error('Invalid user ID format. Must be 1-8 alphanumeric characters.');
    }
    
    // Client-side validation of update data
    if (userData.firstName !== undefined) {
      if (!userData.firstName || userData.firstName.trim() === '') {
        throw new Error('First name cannot be empty');
      }
      
      if (userData.firstName.length > 20) {
        throw new Error('First name must not exceed 20 characters');
      }
    }
    
    if (userData.lastName !== undefined) {
      if (!userData.lastName || userData.lastName.trim() === '') {
        throw new Error('Last name cannot be empty');
      }
      
      if (userData.lastName.length > 20) {
        throw new Error('Last name must not exceed 20 characters');
      }
    }
    
    if (userData.userType !== undefined && !validateUserType(userData.userType)) {
      throw new Error(`Invalid user type. Must be '${USER_TYPES.ADMIN}' or '${USER_TYPES.USER}'`);
    }
    
    // Normalize userId to uppercase
    const normalizedUserId = userId.toUpperCase();
    
    // Prepare update data (trim string fields)
    const normalizedUserData = {
      ...userData,
    };
    
    if (userData.firstName) {
      normalizedUserData.firstName = userData.firstName.trim();
    }
    
    if (userData.lastName) {
      normalizedUserData.lastName = userData.lastName.trim();
    }
    
    // Make PUT request to update user
    // Server will hash password with BCrypt if password field is included
    const response = await apiClient.put(
      `${USER_ADMIN_BASE_PATH}/${normalizedUserId}`,
      normalizedUserData
    );
    
    // Backend returns updated user object without password field
    return response.data;
  } catch (error) {
    throw handleApiError(error);
  }
};

/**
 * Delete user
 * 
 * Replaces: COUSR03C.cbl EXEC CICS DELETE DATASET(USRSEC) user deletion
 * Endpoint: DELETE /api/admin/users/{userId}
 * 
 * Deletion Constraints:
 * - User must not have active sessions (checked server-side)
 * - User must not have related data that would violate referential integrity
 * - Server returns 422 Unprocessable Entity if constraints violated
 * 
 * UI Consideration:
 * - Calling code should implement confirmation dialog before invoking this method
 * - "Are you sure you want to delete user {userId}?" pattern
 * 
 * @param {string} userId - User ID to delete (max 8 characters)
 * @returns {Promise<Object>} Promise resolving to success status object
 * @throws {Error} If user not found, has active sessions/data, or unauthorized
 */
const deleteUser = async (userId) => {
  try {
    // Validate userId format
    if (!validateUserId(userId)) {
      throw new Error('Invalid user ID format. Must be 1-8 alphanumeric characters.');
    }
    
    // Normalize userId to uppercase
    const normalizedUserId = userId.toUpperCase();
    
    // Make DELETE request
    // Server will check for active sessions and related data before deleting
    const response = await apiClient.delete(`${USER_ADMIN_BASE_PATH}/${normalizedUserId}`);
    
    // Return success status from server
    // Typically: { success: true, message: "User deleted successfully" }
    return response.data;
  } catch (error) {
    throw handleApiError(error);
  }
};

/**
 * Search users with flexible criteria
 * 
 * Alias for getUsers() method providing semantic clarity for search operations
 * Replaces: COUSR00C.cbl filtered VSAM browsing with search conditions
 * 
 * This method is functionally identical to getUsers() but provides clearer
 * semantic meaning when performing explicit search operations in UI components.
 * 
 * @param {Object} criteria - Search criteria (same as getUsers searchCriteria)
 * @param {string} criteria.userId - Filter by user ID (partial match)
 * @param {string} criteria.firstName - Filter by first name
 * @param {string} criteria.lastName - Filter by last name
 * @param {string} criteria.userType - Filter by user type ('A' or 'U')
 * @returns {Promise<Array>} Promise resolving to array of matching user objects
 * @throws {Error} If request fails or unauthorized
 */
const searchUsers = async (criteria) => {
  // Delegate to getUsers with search criteria
  return getUsers(criteria);
};

/**
 * User Service Object
 * 
 * Exports all user management operations as a single service object
 * Matches pattern used across frontend service modules (authService, accountService, etc.)
 * 
 * All methods require admin role authorization enforced by:
 * - Backend: @PreAuthorize("hasRole('ADMIN')") on Spring Boot endpoints
 * - Frontend: Admin-only route guards in React Router
 * - API Client: Automatic JWT token injection with admin role claim
 */
const userService = {
  getUsers,
  getUserById,
  createUser,
  updateUser,
  deleteUser,
  searchUsers,
};

/**
 * Export user service as default export
 * Usage: import userService from '../services/userService.js'
 */
export default userService;

/**
 * Named exports for constants (optional usage)
 * Usage: import userService, { USER_TYPES } from '../services/userService.js'
 */
export { USER_TYPES as UserTypes, USER_TYPE_LABELS as UserTypeLabels };
