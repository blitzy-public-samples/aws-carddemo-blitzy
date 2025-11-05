/**
 * CardDemo API Client
 * 
 * Centralized Axios HTTP client configuration providing foundational API communication 
 * infrastructure for CardDemo frontend services. This module serves as the core dependency 
 * for all service modules (authService, accountService, cardService, transactionService),
 * ensuring consistent API interaction patterns across the application.
 * 
 * Transformation Context:
 * Replaces CICS EXEC commands (EXEC CICS LINK, EXEC CICS SEND/RECEIVE) with modern 
 * HTTP REST API calls, transforming mainframe transaction processing into cloud-native
 * RESTful architecture while preserving identical business logic and response semantics.
 * 
 * Key Features:
 * - Environment-based base URL configuration
 * - Automatic JWT token injection for authenticated requests
 * - Global error handling with user-friendly messages
 * - Token expiration detection with automatic redirect to login
 * - Response data extraction and transformation
 * - Network error handling with retry suggestions
 * - Timeout configuration to match CICS response time requirements (< 200ms target)
 * - Consistent error format for all services
 * 
 * @module services/apiClient
 */

import axios from 'axios';

/**
 * Axios instance configured with base URL, timeout, and default headers.
 * 
 * Configuration:
 * - baseURL: Configurable via REACT_APP_API_BASE_URL environment variable
 * - timeout: 30 seconds (30000ms) to accommodate batch operations while maintaining
 *   performance targets for online transactions (< 200ms for card authorization)
 * - headers: JSON content type for request/response bodies
 * 
 * Environment Variable Configuration:
 * - Development: REACT_APP_API_BASE_URL=http://localhost:8080/api
 * - Production: REACT_APP_API_BASE_URL=https://api.carddemo.example.com/api
 */
const apiClient = axios.create({
  baseURL: process.env.REACT_APP_API_BASE_URL || 'http://localhost:8080/api',
  timeout: 30000, // 30 second timeout
  headers: {
    'Content-Type': 'application/json',
    'Accept': 'application/json'
  }
});

/**
 * Request Interceptor
 * 
 * Automatically injects JWT token into request headers for authenticated API calls.
 * This replaces CICS session management and RACF security with JWT token-based
 * authentication, maintaining equivalent security semantics.
 * 
 * Token Storage:
 * - Token stored in localStorage with key 'carddemo_jwt_token'
 * - Retrieved and attached to Authorization header as Bearer token
 * - Missing token allows request to proceed (for public endpoints like /login)
 * 
 * COBOL Equivalence:
 * Maps CICS EXEC CICS ASSIGN to retrieve user context for authorization
 */
apiClient.interceptors.request.use(
  (config) => {
    // Retrieve JWT token from localStorage
    const token = localStorage.getItem('carddemo_jwt_token');
    
    // Inject token into Authorization header if present
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    
    return config;
  },
  (error) => {
    // Request configuration error - reject with error
    return Promise.reject(error);
  }
);

/**
 * Response Interceptor
 * 
 * Provides global error handling and token expiration detection for all API responses.
 * Transforms HTTP error codes into user-friendly error messages while preserving
 * detailed error information for debugging and validation handling.
 * 
 * Error Handling Strategy:
 * - 401 Unauthorized: Clears authentication state and redirects to login
 * - 403 Forbidden: Insufficient permissions error message
 * - 404 Not Found: Resource not found with custom message support
 * - 400 Bad Request: Validation errors with field-specific error details
 * - 500+ Server Errors: Generic server error message
 * - Network Errors: Connection failure detection with user guidance
 * 
 * COBOL Equivalence:
 * Maps CICS HANDLE CONDITION ERROR global error handling to centralized
 * HTTP error response processing with consistent error propagation.
 */
apiClient.interceptors.response.use(
  (response) => {
    // Successful response - return response object directly
    // Services can access response.data, response.status, response.headers
    return response;
  },
  (error) => {
    // Handle HTTP error responses
    if (error.response) {
      const { status, data } = error.response;
      
      // 401 Unauthorized - Token expired or invalid
      // Automatically clear authentication state and redirect to login
      // Maps COBOL COSGN00C authentication failure handling
      if (status === 401) {
        // Clear JWT token from localStorage
        localStorage.removeItem('carddemo_jwt_token');
        
        // Clear user profile from localStorage
        localStorage.removeItem('carddemo_user');
        
        // Redirect to login page
        // This preserves CICS pseudo-conversational pattern where
        // session expiration returns user to sign-on screen
        window.location.href = '/login';
        
        return Promise.reject({ 
          message: 'Session expired. Please login again.',
          status: 401
        });
      }
      
      // 403 Forbidden - Insufficient permissions
      // Maps COBOL role-based access control from USRSEC user type validation
      if (status === 403) {
        return Promise.reject({ 
          message: 'You do not have permission to perform this action.',
          status: 403
        });
      }
      
      // 404 Not Found
      // Maps COBOL VSAM file NOTFND condition handling
      if (status === 404) {
        return Promise.reject({ 
          message: data.message || 'Resource not found.',
          status: 404
        });
      }
      
      // 400 Bad Request - Validation errors
      // Maps COBOL field validation error handling with detailed field-level errors
      // Preserves COBOL PIC clause validation and business rule violations
      if (status === 400) {
        return Promise.reject({ 
          message: data.message || 'Invalid request data.',
          errors: data.errors || {},
          status: 400
        });
      }
      
      // 500 Internal Server Error and other 5xx errors
      // Maps COBOL HANDLE ABEND and file I/O error conditions
      if (status >= 500) {
        return Promise.reject({ 
          message: 'Server error. Please try again later.',
          status
        });
      }
      
      // Other HTTP error codes
      return Promise.reject({ 
        message: data.message || 'An unexpected error occurred.',
        status
      });
    }
    
    // Network error - no response received
    // Maps CICS communication failure conditions
    if (error.request) {
      return Promise.reject({ 
        message: 'Network error. Please check your connection.',
        networkError: true
      });
    }
    
    // Request configuration error or other unexpected errors
    return Promise.reject({ 
      message: error.message || 'An unexpected error occurred.'
    });
  }
);

/**
 * HTTP GET method wrapper
 * 
 * Performs HTTP GET request for data retrieval operations.
 * Maps COBOL EXEC CICS READ and sequential file READ operations.
 * 
 * @param {string} url - API endpoint URL (relative to baseURL)
 * @param {object} config - Optional axios configuration (headers, params, etc.)
 * @returns {Promise} Promise resolving to axios response object
 * 
 * @example
 * // Retrieve account by ID (maps COBOL COACTVWC READ ACCTDAT)
 * const response = await get('/accounts/123456789');
 * const account = response.data;
 * 
 * @example
 * // Retrieve transactions with query parameters (maps COBOL COTRN00C sequential read)
 * const response = await get('/transactions', {
 *   params: { accountId: '123456789', page: 1, pageSize: 10 }
 * });
 * const transactions = response.data;
 */
const get = (url, config = {}) => apiClient.get(url, config);

/**
 * HTTP POST method wrapper
 * 
 * Performs HTTP POST request for resource creation and command operations.
 * Maps COBOL EXEC CICS WRITE and transaction initiation operations.
 * 
 * @param {string} url - API endpoint URL (relative to baseURL)
 * @param {object} data - Request payload (JSON serializable object)
 * @param {object} config - Optional axios configuration (headers, etc.)
 * @returns {Promise} Promise resolving to axios response object
 * 
 * @example
 * // User authentication (maps COBOL COSGN00C sign-on validation)
 * const response = await post('/auth/login', {
 *   userId: 'USER001',
 *   password: 'password123'
 * });
 * const { token, userName, userType } = response.data;
 * 
 * @example
 * // Create new transaction (maps COBOL COTRN02C WRITE TRANSACT)
 * const response = await post('/transactions', {
 *   accountId: '123456789',
 *   transactionAmount: 99.99,
 *   transactionType: 'Purchase',
 *   merchantName: 'Example Store'
 * });
 * const newTransaction = response.data;
 */
const post = (url, data, config = {}) => apiClient.post(url, data, config);

/**
 * HTTP PUT method wrapper
 * 
 * Performs HTTP PUT request for resource update operations.
 * Maps COBOL EXEC CICS REWRITE and file UPDATE operations.
 * 
 * @param {string} url - API endpoint URL (relative to baseURL)
 * @param {object} data - Request payload (JSON serializable object)
 * @param {object} config - Optional axios configuration (headers, etc.)
 * @returns {Promise} Promise resolving to axios response object
 * 
 * @example
 * // Update account information (maps COBOL COACTUPC REWRITE ACCTDAT)
 * const response = await put('/accounts/123456789', {
 *   accountStatus: 'A',
 *   creditLimit: 15000.00
 * });
 * const updatedAccount = response.data;
 * 
 * @example
 * // Update card status (maps COBOL COCRDUPC REWRITE CARDDAT)
 * const response = await put('/cards/4111111111111111', {
 *   cardStatus: 'B' // Blocked
 * });
 * const updatedCard = response.data;
 */
const put = (url, data, config = {}) => apiClient.put(url, data, config);

/**
 * HTTP DELETE method wrapper
 * 
 * Performs HTTP DELETE request for resource deletion operations.
 * Maps COBOL EXEC CICS DELETE and file DELETE operations.
 * 
 * @param {string} url - API endpoint URL (relative to baseURL)
 * @param {object} config - Optional axios configuration (headers, etc.)
 * @returns {Promise} Promise resolving to axios response object
 * 
 * @example
 * // Delete user (maps administrative COBOL DELETE operations)
 * const response = await del('/users/12345');
 * // Response typically has status 204 No Content
 */
const del = (url, config = {}) => apiClient.delete(url, config);

/**
 * HTTP PATCH method wrapper
 * 
 * Performs HTTP PATCH request for partial resource update operations.
 * Maps COBOL selective field update patterns.
 * 
 * @param {string} url - API endpoint URL (relative to baseURL)
 * @param {object} data - Request payload with fields to update (JSON serializable object)
 * @param {object} config - Optional axios configuration (headers, etc.)
 * @returns {Promise} Promise resolving to axios response object
 * 
 * @example
 * // Partial update of account (update only specific fields)
 * const response = await patch('/accounts/123456789', {
 *   creditLimit: 20000.00 // Only update credit limit
 * });
 * const updatedAccount = response.data;
 */
const patch = (url, data, config = {}) => apiClient.patch(url, data, config);

/**
 * API Client Export
 * 
 * Exports convenience methods for common HTTP operations and direct axios instance
 * for advanced use cases requiring full axios API access.
 * 
 * Exported Members:
 * - get: HTTP GET method wrapper
 * - post: HTTP POST method wrapper
 * - put: HTTP PUT method wrapper
 * - delete: HTTP DELETE method wrapper (exported as 'del' to avoid reserved keyword)
 * - patch: HTTP PATCH method wrapper
 * - instance: Direct axios instance for advanced configuration
 * 
 * Usage Pattern:
 * All service modules (authService, accountService, cardService, transactionService)
 * import this module and use the HTTP method wrappers to communicate with backend
 * REST API endpoints, ensuring consistent authentication, error handling, and
 * response transformation across the entire frontend application.
 */
export default {
  get,
  post,
  put,
  delete: del,
  patch,
  // Direct axios instance for advanced use cases requiring custom configuration
  // Example: Custom interceptors, streaming responses, file uploads
  instance: apiClient
};
