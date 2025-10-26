/**
 * User Service Module
 * 
 * TypeScript service module providing comprehensive user management API operations.
 * Converted from COBOL programs COUSR00C.cbl (user list), COUSR01C.cbl (user add),
 * COUSR02C.cbl (user update), and COUSR03C.cbl (user delete).
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 * 
 * Original COBOL Programs:
 * - COUSR00C.cbl: List all users from USRSEC file using STARTBR/READNEXT browse
 * - COUSR01C.cbl: Add new Regular/Admin user to USRSEC file using WRITE command
 * - COUSR02C.cbl: Update user in USRSEC file using REWRITE command
 * - COUSR03C.cbl: Delete user from USRSEC file using DELETE command
 * 
 * Transformation Summary:
 * - COBOL EXEC CICS STARTBR/READNEXT → REST GET /api/users with pagination
 * - COBOL EXEC CICS READ FILE('USRSEC') → REST GET /api/users/:id
 * - COBOL EXEC CICS WRITE FILE('USRSEC') → REST POST /api/users
 * - COBOL EXEC CICS REWRITE FILE('USRSEC') → REST PUT /api/users/:id
 * - COBOL EXEC CICS DELETE FILE('USRSEC') → REST DELETE /api/users/:id
 * - COBOL file-status codes → HTTP status codes (404 Not Found, 409 Conflict)
 * - COBOL CSUSR01Y copybook structure → TypeScript User interface
 * - COBOL plain-text passwords → BCrypt hashed passwords (backend)
 * - RACF security → Spring Security role-based access control
 * 
 * API Endpoints:
 * - GET    /api/users              → List all users with pagination and sorting
 * - GET    /api/users/:id          → Get user by ID
 * - POST   /api/users              → Create new user
 * - PUT    /api/users/:id          → Update existing user
 * - DELETE /api/users/:id          → Delete user
 * 
 * Security Notes:
 * - JWT token automatically attached via api.ts request interceptor
 * - Admin role (ROLE_ADMIN) required for all user management operations
 * - Password hash never exposed in GET responses per security requirements
 * - Password required only on POST (create) and optional on PUT (update)
 * - Password validation (minimum 8 characters, complexity rules) enforced by backend
 * 
 * Error Handling:
 * - 400 Bad Request: Validation errors (invalid user data)
 * - 401 Unauthorized: Missing or invalid JWT token
 * - 403 Forbidden: Insufficient permissions (non-admin user)
 * - 404 Not Found: User ID does not exist (COBOL file-status 23)
 * - 409 Conflict: Duplicate user ID (COBOL file-status 22)
 * - 500 Internal Server Error: Backend processing error
 * 
 * Usage Examples:
 * 
 * ```typescript
 * // List all users with pagination
 * const result = await getAllUsers({ page: 1, pageSize: 20 });
 * console.log(result.users); // User[]
 * console.log(result.pagination); // { page: 1, pageSize: 20, totalItems: 150, totalPages: 8 }
 * 
 * // Get specific user by ID
 * const user = await getUserById('USER0001');
 * console.log(user.userFirstName); // "John"
 * 
 * // Create new user
 * const newUser = await createUser({
 *   userId: 'USER0050',
 *   userFirstName: 'Jane',
 *   userLastName: 'Doe',
 *   userType: 'U',
 *   password: 'Secure123'
 * });
 * 
 * // Update user
 * const updated = await updateUser('USER0050', {
 *   userFirstName: 'Janet',
 *   password: 'NewPass456' // optional
 * });
 * 
 * // Delete user
 * await deleteUser('USER0050');
 * ```
 * 
 * @module services/userService
 * @see backend/src/main/java/com/carddemo/controller/UserController.java
 * @see backend/src/main/java/com/carddemo/service/UserService.java
 * @see backend/src/main/java/com/carddemo/repository/UserSecurityRepository.java
 */

import api from './api';
import { User } from '../types/user';

/**
 * Pagination parameters interface
 * Used for requesting and receiving paginated user lists
 * 
 * Replaces COBOL WS-PAGE-NUM and WS-REC-COUNT variables from COUSR00C.cbl
 */
interface PaginationParams {
  /** Current page number (1-based indexing) */
  page: number;
  
  /** Number of items per page */
  pageSize: number;
  
  /** Total number of items across all pages (from server response) */
  totalItems?: number;
  
  /** Total number of pages (from server response) */
  totalPages?: number;
}

/**
 * User list query parameters
 * Optional parameters for getAllUsers() API call
 * 
 * Supports pagination, sorting, and filtering for UserListPage table
 */
interface GetAllUsersParams {
  /** Page number (1-based), defaults to 1 */
  page?: number;
  
  /** Items per page, defaults to 20 */
  pageSize?: number;
  
  /** Field name to sort by (e.g., 'userId', 'userLastName', 'userType') */
  sortBy?: string;
  
  /** Sort direction: 'asc' or 'desc' */
  sortDirection?: 'asc' | 'desc';
}

/**
 * User list response structure
 * Returned by getAllUsers() function
 * 
 * Contains paginated user array and pagination metadata
 */
interface UserListResponse {
  /** Array of user objects for current page */
  users: User[];
  
  /** Pagination metadata including current page, page size, and totals */
  pagination: PaginationParams;
}

/**
 * Create user request data
 * Required fields for creating a new user
 * 
 * Omits auto-generated fields (createdAt, updatedAt, lastLoginTs)
 * and requires password field for initial authentication setup
 * 
 * Converted from COBOL COUSR01C.cbl input screen fields
 */
export interface CreateUserRequest {
  /** User ID (8 characters max, alphanumeric) */
  userId: string;
  
  /** First name (20 characters max) */
  userFirstName: string;
  
  /** Last name (20 characters max) */
  userLastName: string;
  
  /** User type code: 'A' (Admin), 'U' (User), 'O' (Operator) */
  userType: string;
  
  /** Password (8 characters max for COBOL compatibility, will be BCrypt hashed) */
  password: string;
}

/**
 * Update user request data
 * Optional fields for updating existing user
 * 
 * All fields are optional - only provided fields will be updated
 * Password is optional - if omitted, existing password remains unchanged
 * 
 * Converted from COBOL COUSR02C.cbl update screen fields
 */
export interface UpdateUserRequest {
  /** First name (optional) */
  userFirstName?: string;
  
  /** Last name (optional) */
  userLastName?: string;
  
  /** User type code (optional) */
  userType?: string;
  
  /** New password (optional, will be BCrypt hashed if provided) */
  password?: string;
}

/**
 * Get all users with optional pagination and sorting
 * 
 * Retrieves paginated list of all users from the system.
 * Converted from COBOL program COUSR00C.cbl which uses EXEC CICS STARTBR/READNEXT
 * to browse through USRSEC file records.
 * 
 * Original COBOL Flow (COUSR00C.cbl):
 * 1. EXEC CICS STARTBR FILE('USRSEC') - Start browse operation
 * 2. PERFORM READNEXT-USER-SEC UNTIL USER-SEC-EOF - Read records sequentially
 * 3. EXEC CICS ENDBR FILE('USRSEC') - End browse operation
 * 4. Display users in BMS map COUSR00 (10 users per page)
 * 
 * Modern REST API Implementation:
 * - Sends GET request to /api/users endpoint
 * - Backend uses JPA repository findAll() with Pageable parameter
 * - Returns paginated results with metadata
 * 
 * Query Parameters:
 * - page: Current page number (1-based)
 * - pageSize: Number of items per page (default: 20, COBOL default: 10)
 * - sortBy: Field name for sorting (e.g., 'userId', 'userLastName')
 * - sortDirection: 'asc' or 'desc'
 * 
 * Security:
 * - Requires valid JWT token (automatically attached by api.ts interceptor)
 * - Requires ROLE_ADMIN permission (enforced by backend @PreAuthorize)
 * 
 * Error Handling:
 * - 401 Unauthorized: Invalid or missing JWT token
 * - 403 Forbidden: User lacks ROLE_ADMIN permission
 * - 500 Internal Server Error: Backend database error
 * 
 * @param params - Optional query parameters for pagination and sorting
 * @returns Promise resolving to UserListResponse with users array and pagination metadata
 * 
 * @throws {ApiError} If request fails (network error, authorization, server error)
 * 
 * @example
 * ```typescript
 * // Get first page with default page size
 * const result = await getAllUsers();
 * 
 * // Get specific page with custom page size
 * const page2 = await getAllUsers({ page: 2, pageSize: 50 });
 * 
 * // Get users sorted by last name
 * const sorted = await getAllUsers({ 
 *   sortBy: 'userLastName', 
 *   sortDirection: 'asc' 
 * });
 * 
 * // Access results
 * console.log(result.users); // User[]
 * console.log(result.pagination.totalItems); // Total user count
 * ```
 * 
 * @see COBOL program: app/cbl/COUSR00C.cbl
 * @see BMS map: app/bms/COUSR00.bms
 * @see Backend: UserController.getAllUsers()
 */
export async function getAllUsers(
  params?: GetAllUsersParams
): Promise<UserListResponse> {
  try {
    // Build query parameters with defaults
    const queryParams: Record<string, any> = {
      page: params?.page || 1,
      pageSize: params?.pageSize || 20,
    };

    // Add sorting parameters if provided
    if (params?.sortBy) {
      queryParams['sortBy'] = params.sortBy;
      queryParams['sortDirection'] = params.sortDirection || 'asc';
    }

    // Make GET request to backend
    // api.get() automatically attaches JWT token via request interceptor
    const response = await api.get<UserListResponse>('/users', {
      params: queryParams,
    });

    // Return response data containing users array and pagination metadata
    return response.data;
  } catch (error) {
    // Error is already processed by api.ts response interceptor
    // which converts AxiosError to standardized ApiError
    // Re-throw for caller to handle (typically displayed in UserListPage)
    throw error;
  }
}

/**
 * Get user by ID
 * 
 * Retrieves a single user's details by their unique user ID.
 * Converted from COBOL READ operation in COUSR02C.cbl and COUSR03C.cbl
 * which use EXEC CICS READ FILE('USRSEC') RIDFLD(user-id).
 * 
 * Original COBOL Flow:
 * 1. EXEC CICS READ FILE('USRSEC') RIDFLD(WS-USER-ID) INTO(SEC-USER-DATA)
 * 2. If RESP = DFHRESP(NORMAL) - User found
 * 3. If RESP = DFHRESP(NOTFND) - User not found (file-status 23)
 * 4. Display user data in BMS map COUSR02 (update) or COUSR03 (delete)
 * 
 * Modern REST API Implementation:
 * - Sends GET request to /api/users/:userId endpoint
 * - Backend uses JPA repository findById() method
 * - Returns user entity or 404 if not found
 * 
 * Security:
 * - Requires valid JWT token
 * - Requires ROLE_ADMIN permission
 * - Password hash excluded from response per security requirements
 * 
 * Error Handling:
 * - 401 Unauthorized: Invalid or missing JWT token
 * - 403 Forbidden: User lacks ROLE_ADMIN permission
 * - 404 Not Found: User ID does not exist (COBOL file-status 23)
 * - 500 Internal Server Error: Backend database error
 * 
 * @param userId - User ID to retrieve (8 characters max, alphanumeric)
 * @returns Promise resolving to User object
 * 
 * @throws {ApiError} Status 404 if user not found, other errors for auth/server issues
 * 
 * @example
 * ```typescript
 * try {
 *   const user = await getUserById('USER0001');
 *   console.log(user.userFirstName); // "John"
 *   console.log(user.userType); // "A" (Admin)
 * } catch (error) {
 *   if (error.status === 404) {
 *     console.error('User not found');
 *   }
 * }
 * ```
 * 
 * @see COBOL programs: app/cbl/COUSR02C.cbl, app/cbl/COUSR03C.cbl
 * @see Backend: UserController.getUserById()
 */
export async function getUserById(userId: string): Promise<User> {
  try {
    // Make GET request to retrieve specific user
    // URL path parameter automatically encoded by axios
    const response = await api.get<User>(`/users/${userId}`);

    // Return user data (password hash excluded by backend)
    return response.data;
  } catch (error) {
    // Re-throw error for caller to handle
    // 404 error indicates user not found (COBOL file-status 23)
    throw error;
  }
}

/**
 * Create new user
 * 
 * Creates a new user account in the system with specified credentials and role.
 * Converted from COBOL program COUSR01C.cbl which uses EXEC CICS WRITE
 * to add new user record to USRSEC file.
 * 
 * Original COBOL Flow (COUSR01C.cbl):
 * 1. Receive user input from BMS map COUSR01 (user add form)
 * 2. Validate all fields (user ID, names, type, password)
 * 3. EXEC CICS WRITE FILE('USRSEC') FROM(SEC-USER-DATA) RIDFLD(SEC-USR-ID)
 * 4. If RESP = DFHRESP(NORMAL) - User created successfully
 * 5. If RESP = DFHRESP(DUPREC) - Duplicate user ID (file-status 22)
 * 6. Display success/error message on screen
 * 
 * Modern REST API Implementation:
 * - Sends POST request to /api/users endpoint with user data
 * - Backend validates data using @Valid annotation
 * - Backend hashes password using BCrypt before database insert
 * - Backend uses JPA repository save() method
 * - Returns created user entity with generated timestamps
 * 
 * Password Security:
 * - COBOL stored plain-text passwords (SEC-USR-PWD PIC X(08))
 * - Modern system uses BCrypt hashing per Agent Action Plan Section 0.7.9
 * - Password transmitted over HTTPS (TLS encryption)
 * - Password never returned in response (security requirement)
 * - Minimum 8 characters required (COBOL compatibility constraint)
 * - Complexity rules enforced by backend validation
 * 
 * Validation Rules (enforced by backend):
 * - userId: Required, 1-8 characters, alphanumeric, unique
 * - userFirstName: Required, 1-20 characters
 * - userLastName: Required, 1-20 characters
 * - userType: Required, must be 'A', 'U', or 'O'
 * - password: Required, minimum 8 characters, complexity rules
 * 
 * Security:
 * - Requires valid JWT token
 * - Requires ROLE_ADMIN permission (only admins can create users)
 * 
 * Error Handling:
 * - 400 Bad Request: Validation errors (invalid field values)
 * - 401 Unauthorized: Invalid or missing JWT token
 * - 403 Forbidden: User lacks ROLE_ADMIN permission
 * - 409 Conflict: Duplicate user ID (COBOL file-status 22)
 * - 500 Internal Server Error: Backend database error
 * 
 * @param userData - User data for creation including password
 * @returns Promise resolving to created User object (password excluded)
 * 
 * @throws {ApiError} Status 409 if user ID exists, 400 for validation errors
 * 
 * @example
 * ```typescript
 * try {
 *   const newUser = await createUser({
 *     userId: 'USER0050',
 *     userFirstName: 'Jane',
 *     userLastName: 'Doe',
 *     userType: 'U', // Regular user
 *     password: 'Secure123'
 *   });
 *   console.log('User created:', newUser.userId);
 *   console.log('Created at:', newUser.createdAt);
 * } catch (error) {
 *   if (error.status === 409) {
 *     console.error('User ID already exists');
 *   } else if (error.status === 400) {
 *     console.error('Validation errors:', error.errors);
 *   }
 * }
 * ```
 * 
 * @see COBOL program: app/cbl/COUSR01C.cbl
 * @see BMS map: app/bms/COUSR01.bms
 * @see Backend: UserController.createUser()
 * @see Backend: UserService.createUser() - BCrypt password hashing
 */
export async function createUser(
  userData: CreateUserRequest
): Promise<User> {
  try {
    // Make POST request to create new user
    // Request body automatically serialized to JSON by axios
    // Password will be BCrypt hashed by backend before database insert
    const response = await api.post<User>('/users', userData);

    // Return created user data (password excluded, timestamps included)
    return response.data;
  } catch (error) {
    // Re-throw error for caller to handle
    // 409 error indicates duplicate user ID (COBOL file-status 22)
    // 400 error indicates validation failures
    throw error;
  }
}

/**
 * Update existing user
 * 
 * Updates an existing user's information with partial data.
 * Converted from COBOL program COUSR02C.cbl which uses EXEC CICS REWRITE
 * to update user record in USRSEC file.
 * 
 * Original COBOL Flow (COUSR02C.cbl):
 * 1. EXEC CICS READ FILE('USRSEC') RIDFLD(user-id) INTO(SEC-USER-DATA) UPDATE
 * 2. Receive updated fields from BMS map COUSR02 (user update form)
 * 3. Modify SEC-USER-DATA record with new values
 * 4. EXEC CICS REWRITE FILE('USRSEC') FROM(SEC-USER-DATA)
 * 5. If RESP = DFHRESP(NORMAL) - User updated successfully
 * 6. If RESP = DFHRESP(NOTFND) - User not found (file-status 23)
 * 7. Display success/error message on screen
 * 
 * Modern REST API Implementation:
 * - Sends PUT request to /api/users/:userId endpoint
 * - Only provided fields are updated (partial update)
 * - Backend retrieves existing user, applies changes, saves
 * - Backend uses JPA repository save() method (update mode)
 * - Password is optional - if provided, re-hashed with BCrypt
 * 
 * Partial Update Support:
 * - All fields are optional in UpdateUserRequest
 * - Only fields present in request body will be updated
 * - Omitted fields retain their existing values
 * - userId cannot be changed (primary key constraint)
 * 
 * Password Update:
 * - Password is optional in update request
 * - If omitted, existing password hash remains unchanged
 * - If provided, new password is BCrypt hashed before database update
 * - User must re-authenticate with new password after change
 * 
 * Security:
 * - Requires valid JWT token
 * - Requires ROLE_ADMIN permission
 * - Password hash never exposed in response
 * 
 * Error Handling:
 * - 400 Bad Request: Validation errors (invalid field values)
 * - 401 Unauthorized: Invalid or missing JWT token
 * - 403 Forbidden: User lacks ROLE_ADMIN permission
 * - 404 Not Found: User ID does not exist (COBOL file-status 23)
 * - 500 Internal Server Error: Backend database error
 * 
 * @param userId - User ID to update (must exist)
 * @param userData - Partial user data with fields to update
 * @returns Promise resolving to updated User object
 * 
 * @throws {ApiError} Status 404 if user not found, 400 for validation errors
 * 
 * @example
 * ```typescript
 * // Update only first name
 * const user1 = await updateUser('USER0050', {
 *   userFirstName: 'Janet'
 * });
 * 
 * // Update multiple fields including password
 * const user2 = await updateUser('USER0050', {
 *   userFirstName: 'Janet',
 *   userLastName: 'Smith',
 *   password: 'NewSecure456'
 * });
 * 
 * // Update user type (change role)
 * const user3 = await updateUser('USER0050', {
 *   userType: 'A' // Promote to admin
 * });
 * ```
 * 
 * @see COBOL program: app/cbl/COUSR02C.cbl
 * @see BMS map: app/bms/COUSR02.bms
 * @see Backend: UserController.updateUser()
 * @see Backend: UserService.updateUser() - BCrypt password re-hashing if provided
 */
export async function updateUser(
  userId: string,
  userData: UpdateUserRequest
): Promise<User> {
  try {
    // Make PUT request to update existing user
    // URL path parameter identifies which user to update
    // Request body contains partial user data (only fields to change)
    // Password will be re-hashed by backend if provided
    const response = await api.put<User>(`/users/${userId}`, userData);

    // Return updated user data with new updatedAt timestamp
    return response.data;
  } catch (error) {
    // Re-throw error for caller to handle
    // 404 error indicates user not found (COBOL file-status 23)
    // 400 error indicates validation failures
    throw error;
  }
}

/**
 * Delete user
 * 
 * Permanently deletes a user account from the system.
 * Converted from COBOL program COUSR03C.cbl which uses EXEC CICS DELETE
 * to remove user record from USRSEC file.
 * 
 * Original COBOL Flow (COUSR03C.cbl):
 * 1. Display user details in BMS map COUSR03 (delete confirmation screen)
 * 2. Wait for user confirmation (ENTER key)
 * 3. EXEC CICS DELETE FILE('USRSEC') RIDFLD(user-id)
 * 4. If RESP = DFHRESP(NORMAL) - User deleted successfully
 * 5. If RESP = DFHRESP(NOTFND) - User not found (file-status 23)
 * 6. Display success/error message on screen
 * 7. Return to user list screen (COUSR00)
 * 
 * Modern REST API Implementation:
 * - Sends DELETE request to /api/users/:userId endpoint
 * - Frontend shows confirmation dialog before calling this function
 * - Backend uses JPA repository deleteById() method
 * - Returns 204 No Content on success (no response body)
 * 
 * Delete Operation Considerations:
 * - Permanent deletion - cannot be undone
 * - User cannot delete their own account (backend validation)
 * - May fail if user has dependent records (referential integrity)
 * - Confirmation dialog should be shown before calling (UserDeletePage)
 * - Returns void/undefined on success (204 No Content)
 * 
 * Referential Integrity:
 * - Backend may prevent deletion if user has audit trail records
 * - Backend may implement soft delete (mark inactive) instead of hard delete
 * - Backend should return 409 Conflict if deletion violates constraints
 * 
 * Security:
 * - Requires valid JWT token
 * - Requires ROLE_ADMIN permission
 * - User cannot delete their own account (backend enforces)
 * - Audit log should record deletion for compliance
 * 
 * Error Handling:
 * - 401 Unauthorized: Invalid or missing JWT token
 * - 403 Forbidden: User lacks ROLE_ADMIN permission or trying to delete self
 * - 404 Not Found: User ID does not exist (COBOL file-status 23)
 * - 409 Conflict: User has dependent records preventing deletion
 * - 500 Internal Server Error: Backend database error
 * 
 * @param userId - User ID to delete (must exist)
 * @returns Promise resolving to void on successful deletion
 * 
 * @throws {ApiError} Status 404 if user not found, 409 if cannot delete due to dependencies
 * 
 * @example
 * ```typescript
 * // Show confirmation dialog first (in UserDeletePage component)
 * const confirmed = await showConfirmDialog(
 *   'Delete User',
 *   `Are you sure you want to delete user ${userId}? This cannot be undone.`
 * );
 * 
 * if (confirmed) {
 *   try {
 *     await deleteUser('USER0050');
 *     console.log('User deleted successfully');
 *     // Navigate back to user list
 *     navigate('/admin/users');
 *   } catch (error) {
 *     if (error.status === 404) {
 *       console.error('User not found');
 *     } else if (error.status === 409) {
 *       console.error('Cannot delete user: has dependent records');
 *     }
 *   }
 * }
 * ```
 * 
 * @see COBOL program: app/cbl/COUSR03C.cbl
 * @see BMS map: app/bms/COUSR03.bms
 * @see Backend: UserController.deleteUser()
 * @see Backend: UserService.deleteUser() - Handles referential integrity checks
 */
export async function deleteUser(userId: string): Promise<void> {
  try {
    // Make DELETE request to remove user
    // URL path parameter identifies which user to delete
    // No request body needed for DELETE operation
    // Backend returns 204 No Content on success (void response)
    await api.delete(`/users/${userId}`);

    // No return value - void function
    // Success indicated by no exception thrown
  } catch (error) {
    // Re-throw error for caller to handle
    // 404 error indicates user not found (COBOL file-status 23)
    // 409 error indicates deletion conflicts with referential integrity
    throw error;
  }
}

/**
 * User Service Object
 * 
 * Default export providing all user management operations in a single object.
 * Convenient for importing entire service with single statement.
 * 
 * All methods match the individual exported functions above with identical
 * signatures and behavior. This object provides an alternative import style
 * for consumers who prefer object-oriented API access.
 * 
 * Usage Patterns:
 * 
 * Pattern 1 - Named imports (recommended for tree-shaking):
 * ```typescript
 * import { getAllUsers, getUserById, createUser } from './services/userService';
 * const users = await getAllUsers();
 * ```
 * 
 * Pattern 2 - Default import (convenient for mocking in tests):
 * ```typescript
 * import userService from './services/userService';
 * const users = await userService.getAllUsers();
 * ```
 * 
 * Pattern 3 - Namespace import:
 * ```typescript
 * import * as userService from './services/userService';
 * const users = await userService.getAllUsers();
 * ```
 * 
 * Testing Benefits:
 * - Easy to mock entire service: jest.mock('./services/userService')
 * - Can spy on individual methods: jest.spyOn(userService, 'getAllUsers')
 * - Supports dependency injection patterns
 * 
 * @example
 * ```typescript
 * // Import default service object
 * import userService from './services/userService';
 * 
 * // Use in component
 * function UserListPage() {
 *   const [users, setUsers] = useState<User[]>([]);
 *   
 *   useEffect(() => {
 *     userService.getAllUsers({ page: 1, pageSize: 20 })
 *       .then(result => setUsers(result.users))
 *       .catch(error => console.error(error));
 *   }, []);
 *   
 *   return <UserTable users={users} />;
 * }
 * 
 * // Mock in tests
 * jest.mock('./services/userService');
 * const mockGetAllUsers = userService.getAllUsers as jest.Mock;
 * mockGetAllUsers.mockResolvedValue({ 
 *   users: [mockUser1, mockUser2], 
 *   pagination: { page: 1, pageSize: 20, totalItems: 2, totalPages: 1 }
 * });
 * ```
 */
const userService = {
  /**
   * Get all users with optional pagination and sorting
   * @see getAllUsers documentation above
   */
  getAllUsers: (params?: GetAllUsersParams): Promise<UserListResponse> => {
    return getAllUsers(params);
  },

  /**
   * Get user by ID
   * @see getUserById documentation above
   */
  getUserById: (userId: string): Promise<User> => {
    return getUserById(userId);
  },

  /**
   * Create new user
   * @see createUser documentation above
   */
  createUser: (userData: CreateUserRequest): Promise<User> => {
    return createUser(userData);
  },

  /**
   * Update existing user
   * @see updateUser documentation above
   */
  updateUser: (userId: string, userData: UpdateUserRequest): Promise<User> => {
    return updateUser(userId, userData);
  },

  /**
   * Delete user
   * @see deleteUser documentation above
   */
  deleteUser: (userId: string): Promise<void> => {
    return deleteUser(userId);
  },
};

/**
 * Default export for convenient service object import
 * Provides all user management functions in a single namespace
 */
export default userService;

