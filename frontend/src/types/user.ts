/**
 * User Type Definitions
 * 
 * TypeScript type definitions for user and authentication data structures.
 * Converted from COBOL SEC-USER-DATA copybook (CSUSR01Y.cpy) to match
 * backend UserDto.java for type-safe user and authentication handling.
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 * 
 * Conversion Notes:
 * - Source: COBOL copybook CSUSR01Y.cpy (SEC-USER-DATA structure)
 * - Password hash (SEC-USR-PWD) intentionally excluded per security requirements
 * - RACF roles replaced with Spring Security role-based access control
 * - Timestamps added to match backend JPA entity audit fields
 * 
 * @see backend/src/main/java/com/carddemo/model/dto/UserDto.java
 * @see backend/src/main/java/com/carddemo/model/entity/UserSecurity.java
 */

/**
 * User data structure matching backend UserDto
 * 
 * Represents a CardDemo application user with authentication and profile information.
 * Converted from COBOL SEC-USER-DATA structure (CSUSR01Y.cpy).
 * 
 * Security Note: Password hash (SEC-USR-PWD) is intentionally excluded from this
 * interface per security best practices. Passwords are only transmitted during
 * authentication via AuthRequest and never stored or exposed in client state.
 * 
 * COBOL to TypeScript Field Mappings:
 * - SEC-USR-ID (PIC X(08)) → userId: string
 * - SEC-USR-FNAME (PIC X(20)) → userFirstName: string
 * - SEC-USR-LNAME (PIC X(20)) → userLastName: string
 * - SEC-USR-TYPE (PIC X(01)) → userType: string
 * - SEC-USR-PWD (PIC X(08)) → EXCLUDED (security requirement)
 * - JPA audit fields → createdAt, updatedAt, lastLoginTs
 * 
 * Usage:
 * - UserListPage: Display user data in table
 * - UserAddPage/UserUpdatePage: User form data structures
 * - UserTable: Table column definitions
 * - UserForm: Form field validation
 * - AuthContext: Current user session state
 * - userService: API response type
 * 
 * @interface User
 */
export interface User {
  /**
   * User ID (unique identifier)
   * 
   * Maximum 8 characters alphanumeric.
   * Converted from COBOL SEC-USR-ID (PIC X(08)).
   * Primary key for user_security table.
   * 
   * Example: "USER0001", "ADMIN001"
   */
  userId: string;

  /**
   * User first name
   * 
   * Maximum 20 characters.
   * Converted from COBOL SEC-USR-FNAME (PIC X(20)).
   * 
   * Example: "John", "Mary"
   */
  userFirstName: string;

  /**
   * User last name
   * 
   * Maximum 20 characters.
   * Converted from COBOL SEC-USR-LNAME (PIC X(20)).
   * 
   * Example: "Smith", "Johnson"
   */
  userLastName: string;

  /**
   * User type/role code
   * 
   * Single character code indicating user's role in the system.
   * Converted from COBOL SEC-USR-TYPE (PIC X(01)).
   * 
   * Valid values:
   * - 'A': Administrator (ROLE_ADMIN) - Full system access
   * - 'U': Regular User (ROLE_USER) - Standard user access
   * - 'O': Operator (ROLE_OPERATOR) - Operational access
   * 
   * Used for role-based UI rendering and Spring Security authorization.
   * Maps to UserType enum for type-safe role checking.
   * 
   * @see UserType
   */
  userType: string;

  /**
   * Record creation timestamp
   * 
   * ISO 8601 formatted datetime string indicating when the user record
   * was created in the database.
   * 
   * Format: "YYYY-MM-DDTHH:mm:ss.sssZ"
   * Example: "2024-01-15T10:30:00.000Z"
   */
  createdAt: string;

  /**
   * Record last update timestamp
   * 
   * ISO 8601 formatted datetime string indicating when the user record
   * was last modified in the database.
   * 
   * Format: "YYYY-MM-DDTHH:mm:ss.sssZ"
   * Example: "2024-01-20T14:45:30.000Z"
   */
  updatedAt: string;

  /**
   * Last successful login timestamp
   * 
   * ISO 8601 formatted datetime string indicating the user's most recent
   * successful authentication. Null if user has never logged in.
   * 
   * Format: "YYYY-MM-DDTHH:mm:ss.sssZ"
   * Example: "2024-01-22T08:15:45.000Z"
   * 
   * Used for security auditing and account monitoring.
   */
  lastLoginTs: string | null;

  // Note: Password hash (SEC-USR-PWD from COBOL) is intentionally excluded
  // from this interface per security best practices. Password is only
  // transmitted during authentication via AuthRequest.
}

/**
 * User type/role enumeration
 * 
 * Defines user roles for Spring Security role-based access control (RBAC).
 * Replaces RACF security roles from mainframe system per Agent Action Plan
 * Section 0.7.9 (RACF to Spring Security Mapping).
 * 
 * RACF to Spring Security Role Mappings:
 * - RACF user profiles → Spring Security UserDetails
 * - RACF roles → UserType enum (ADMIN/USER/OPERATOR)
 * - RACF access rules → Spring Security @PreAuthorize annotations
 * 
 * Each enum value corresponds to a COBOL SEC-USR-TYPE code and a Spring
 * Security granted authority (ROLE_ADMIN, ROLE_USER, ROLE_OPERATOR).
 * 
 * Usage:
 * - Role-based UI component rendering
 * - Protected route access control
 * - Menu option visibility
 * - Feature flag enablement
 * 
 * Example:
 * ```typescript
 * if (user.userType === UserType.ADMIN) {
 *   // Show admin menu options
 * }
 * ```
 * 
 * @enum {string}
 */
export enum UserType {
  /**
   * Administrator role
   * 
   * Full system access including:
   * - User management (create, update, delete users)
   * - Account administration
   * - Card management
   * - System configuration
   * - Report generation
   * - Batch job monitoring
   * 
   * Corresponds to:
   * - COBOL code: 'A'
   * - Spring Security: ROLE_ADMIN
   * - RACF: Administrative user profile
   */
  ADMIN = 'A',

  /**
   * Regular user role
   * 
   * Standard user access including:
   * - View own account information
   * - View card details
   * - View transaction history
   * - Update personal information
   * - Generate standard reports
   * 
   * Corresponds to:
   * - COBOL code: 'U'
   * - Spring Security: ROLE_USER
   * - RACF: Standard user profile
   */
  USER = 'U',

  /**
   * Operator role
   * 
   * Operational access including:
   * - Transaction processing
   * - Batch job execution
   * - System monitoring
   * - Transaction inquiry
   * - Limited account updates
   * 
   * Corresponds to:
   * - COBOL code: 'O'
   * - Spring Security: ROLE_OPERATOR
   * - RACF: Operator user profile
   */
  OPERATOR = 'O'
}

/**
 * Authentication request for user login
 * 
 * Request payload for the POST /api/auth/login endpoint.
 * Matches backend AuthRequest.java data transfer object.
 * 
 * Replaces COBOL CICS signon screen (COSGN00C.cbl) authentication.
 * Implements JWT-based authentication replacing RACF security per
 * Agent Action Plan Section 0.7.9.
 * 
 * Security Notes:
 * - Password is transmitted in plain text over HTTPS (TLS encryption)
 * - Backend hashes password with BCrypt before database comparison
 * - Password is never logged or stored in browser
 * - Failed login attempts are rate-limited by backend
 * 
 * COBOL to Java Authentication Flow:
 * - COBOL: EXEC CICS SIGNON with RACF validation
 * - Java: REST API POST /api/auth/login with JWT token generation
 * 
 * Usage:
 * - SignonPage: Login form submission
 * - authService.login(): API call payload
 * - AuthContext: Authentication state management
 * 
 * @interface AuthRequest
 * @see backend/src/main/java/com/carddemo/model/dto/AuthRequest.java
 * @see backend/src/main/java/com/carddemo/controller/AuthController.java
 */
export interface AuthRequest {
  /**
   * User ID
   * 
   * Maximum 8 characters alphanumeric.
   * Must match existing user_security.user_id in database.
   * 
   * Converted from COBOL SEC-USR-ID (PIC X(08)).
   * 
   * Example: "USER0001", "ADMIN001"
   */
  userId: string;

  /**
   * User password
   * 
   * Maximum 8 characters (COBOL compatibility constraint).
   * Will be hashed by backend using BCrypt before validation.
   * 
   * Security Notes:
   * - Transmitted over HTTPS only
   * - Never stored in browser state
   * - Backend validates against BCrypt hash in database
   * - Failed attempts trigger rate limiting
   * 
   * Converted from COBOL SEC-USR-PWD (PIC X(08)).
   * 
   * Example: "Pass1234"
   */
  password: string;
}

/**
 * Authentication response from successful login
 * 
 * Response payload from POST /api/auth/login endpoint after successful
 * authentication. Matches backend AuthResponse.java data transfer object.
 * 
 * Contains JWT token for stateless authentication replacing CICS session
 * management and RACF security context per Agent Action Plan Section 0.7.9.
 * 
 * JWT Token Details:
 * - Algorithm: HS256 (HMAC with SHA-256)
 * - Expiration: 1 hour (3600 seconds)
 * - Claims: userId, userType, issued-at, expiration
 * - Storage: localStorage or sessionStorage in browser
 * - Transmission: Authorization header "Bearer <token>"
 * 
 * Session Management Flow:
 * - COBOL: CICS COMMAREA maintains session state on mainframe
 * - Java: Stateless JWT token validated on each API request
 * - React: AuthContext stores token and user state in browser
 * 
 * Token Refresh:
 * - Frontend monitors expiresIn to refresh before expiration
 * - Backend provides refresh token mechanism (future enhancement)
 * - User re-authenticates if token expires during session
 * 
 * Usage:
 * - SignonPage: Store token after successful login
 * - AuthContext: Initialize authentication state
 * - api.ts: Add token to Authorization header for API calls
 * - Protected routes: Verify token exists before rendering
 * 
 * @interface AuthResponse
 * @see backend/src/main/java/com/carddemo/model/dto/AuthResponse.java
 * @see backend/src/main/java/com/carddemo/security/JwtTokenProvider.java
 */
export interface AuthResponse {
  /**
   * JWT authentication token
   * 
   * JSON Web Token (JWT) containing user identity and claims.
   * Used for stateless authentication on subsequent API requests.
   * 
   * Format: "header.payload.signature" (Base64Url encoded)
   * Example: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VySWQiOiJVU0VSMDAwMSIsInVzZXJUeXBlIjoiVSIsImlhdCI6MTYxNjIzOTAyMiwiZXhwIjoxNjE2MjQyNjIyfQ.signature"
   * 
   * Storage: localStorage.setItem('authToken', token)
   * Usage: headers['Authorization'] = `Bearer ${token}`
   * 
   * Security Notes:
   * - Contains no sensitive data (hashed signature prevents tampering)
   * - Expires after time specified in expiresIn
   * - Validated by backend on each API request
   * - Revoked on logout (removed from browser storage)
   */
  token: string;

  /**
   * Token expiration time in seconds
   * 
   * Number of seconds until the JWT token expires.
   * Frontend should refresh token before expiration or prompt re-login.
   * 
   * Default value: 3600 (1 hour)
   * 
   * Usage:
   * - Calculate expiration timestamp: Date.now() + (expiresIn * 1000)
   * - Set timer to refresh token: setTimeout(refreshToken, (expiresIn - 300) * 1000)
   * - Display session timeout warning to user
   * 
   * Example: 3600 (token expires in 1 hour)
   */
  expiresIn: number;

  /**
   * User type/role code
   * 
   * Single character code indicating authenticated user's role.
   * Used for immediate role-based UI rendering without additional API calls.
   * 
   * Valid values:
   * - 'A': Administrator (UserType.ADMIN)
   * - 'U': Regular User (UserType.USER)
   * - 'O': Operator (UserType.OPERATOR)
   * 
   * Usage:
   * - Determine initial route after login
   * - Show/hide menu options based on role
   * - Enable/disable features based on permissions
   * - Cache in AuthContext for global access
   * 
   * Example: "A" (Administrator)
   * 
   * @see UserType
   */
  userType: string;
}
