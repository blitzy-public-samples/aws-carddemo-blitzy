/**
 * Centralized Axios HTTP Client Configuration
 * 
 * Purpose: Provides a pre-configured axios instance for all API calls to the Spring Boot backend.
 * This replaces CICS COMMAREA state management with stateless JWT-based authentication.
 * 
 * Features:
 * - Automatic JWT token injection from localStorage
 * - Request/response interceptors for authentication
 * - Global error handling with automatic 401 redirect to login
 * - Base URL configuration from environment variables
 * - Consistent timeout settings
 * - CORS credentials support
 * - Error response transformation to consistent format
 * 
 * Migration Context: Replaces RACF security checks with JWT token-based authentication
 * as specified in Agent Action Plan section 0.1 and 0.3.
 */

import axios from 'axios';

/**
 * Base URL Configuration
 * Uses Vite environment variable (VITE_API_BASE_URL)
 * Falls back to localhost:8080 for local development if not specified
 */
const API_BASE_URL = 
  import.meta.env.VITE_API_BASE_URL || 
  'http://localhost:8080/api';

/**
 * Request timeout in milliseconds (30 seconds)
 * Ensures requests don't hang indefinitely
 */
const REQUEST_TIMEOUT = 30000;

/**
 * Local storage key for JWT token
 * Consistent key used across the application
 */
const TOKEN_STORAGE_KEY = 'jwt_token';

/**
 * Local storage key for token expiration timestamp
 */
const TOKEN_EXPIRY_STORAGE_KEY = 'jwt_token_expiry';

/**
 * Create axios instance with default configuration
 */
const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: REQUEST_TIMEOUT,
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true, // Enable CORS credentials support
});

/**
 * Request Interceptor
 * Automatically injects JWT token from localStorage into Authorization header
 * for all authenticated requests
 */
apiClient.interceptors.request.use(
  (config) => {
    // Retrieve JWT token from localStorage
    const token = localStorage.getItem(TOKEN_STORAGE_KEY);
    
    if (token) {
      // Check if token is expired before attaching
      const tokenExpiry = localStorage.getItem(TOKEN_EXPIRY_STORAGE_KEY);
      
      if (tokenExpiry) {
        const expiryTime = parseInt(tokenExpiry, 10);
        const currentTime = Date.now();
        
        // If token is expired, clear it and don't attach to request
        if (currentTime >= expiryTime) {
          localStorage.removeItem(TOKEN_STORAGE_KEY);
          localStorage.removeItem(TOKEN_EXPIRY_STORAGE_KEY);
          
          // Redirect to login if token expired (unless already on login page)
          if (!window.location.pathname.includes('/login')) {
            window.location.href = '/login';
          }
          
          return Promise.reject(new Error('Token expired'));
        }
      }
      
      // Inject JWT token into Authorization header (Bearer scheme)
      config.headers.Authorization = `Bearer ${token}`;
    }
    
    // Log request for debugging (can be disabled in production)
    if (import.meta.env.MODE === 'development') {
      console.log(`[API Request] ${config.method?.toUpperCase()} ${config.url}`, {
        params: config.params,
        data: config.data,
      });
    }
    
    return config;
  },
  (error) => {
    // Handle request error
    console.error('[API Request Error]', error);
    return Promise.reject(error);
  }
);

/**
 * Response Interceptor
 * Handles global error responses and transforms errors to consistent format
 */
apiClient.interceptors.response.use(
  (response) => {
    // Log successful response for debugging
    if (import.meta.env.MODE === 'development') {
      console.log(`[API Response] ${response.config.method?.toUpperCase()} ${response.config.url}`, {
        status: response.status,
        data: response.data,
      });
    }
    
    // Return the response data for successful requests
    return response;
  },
  (error) => {
    // Extract error details
    const errorResponse = {
      message: 'An unexpected error occurred',
      status: null,
      data: null,
    };
    
    if (error.response) {
      // Server responded with error status code (4xx, 5xx)
      errorResponse.status = error.response.status;
      errorResponse.data = error.response.data;
      
      // Extract error message from response
      if (error.response.data && error.response.data.message) {
        errorResponse.message = error.response.data.message;
      } else if (error.response.data && typeof error.response.data === 'string') {
        errorResponse.message = error.response.data;
      } else {
        errorResponse.message = `Request failed with status ${error.response.status}`;
      }
      
      // Handle specific HTTP status codes
      switch (error.response.status) {
        case 401:
          // Unauthorized - clear token and redirect to login
          localStorage.removeItem(TOKEN_STORAGE_KEY);
          localStorage.removeItem(TOKEN_EXPIRY_STORAGE_KEY);
          
          // Only redirect if not already on login page
          if (!window.location.pathname.includes('/login')) {
            console.warn('[API] Unauthorized access - redirecting to login');
            window.location.href = '/login';
          }
          
          errorResponse.message = 'Authentication required. Please log in.';
          break;
          
        case 403:
          // Forbidden - user doesn't have permission
          errorResponse.message = 'You do not have permission to perform this action.';
          break;
          
        case 404:
          // Not Found
          errorResponse.message = error.response.data?.message || 'Resource not found.';
          break;
          
        case 409:
          // Conflict - business logic error
          errorResponse.message = error.response.data?.message || 'A conflict occurred.';
          break;
          
        case 422:
          // Unprocessable Entity - validation error
          errorResponse.message = error.response.data?.message || 'Validation failed.';
          break;
          
        case 500:
          // Internal Server Error
          errorResponse.message = 'Server error. Please try again later.';
          break;
          
        case 503:
          // Service Unavailable
          errorResponse.message = 'Service temporarily unavailable. Please try again later.';
          break;
          
        default:
          // Other status codes
          break;
      }
      
      // Log error details for debugging
      console.error('[API Error]', {
        url: error.config?.url,
        method: error.config?.method,
        status: error.response.status,
        message: errorResponse.message,
        data: error.response.data,
      });
    } else if (error.request) {
      // Request was made but no response received (network error)
      errorResponse.message = 'Network error. Please check your internet connection.';
      errorResponse.status = 0;
      
      console.error('[API Network Error]', {
        url: error.config?.url,
        method: error.config?.method,
      });
    } else {
      // Something else happened in setting up the request
      errorResponse.message = error.message || 'Request configuration error';
      
      console.error('[API Setup Error]', error.message);
    }
    
    // Return rejected promise with consistent error format
    return Promise.reject(errorResponse);
  }
);

/**
 * Helper function to store JWT token and expiration in localStorage
 * Called by authentication service after successful login
 * 
 * @param {string} token - JWT token string
 * @param {number} expiresIn - Token expiration time in milliseconds (optional)
 */
export const setAuthToken = (token, expiresIn = null) => {
  if (token) {
    localStorage.setItem(TOKEN_STORAGE_KEY, token);
    
    if (expiresIn) {
      const expiryTime = Date.now() + expiresIn;
      localStorage.setItem(TOKEN_EXPIRY_STORAGE_KEY, expiryTime.toString());
    }
  }
};

/**
 * Helper function to retrieve JWT token from localStorage
 * 
 * @returns {string|null} JWT token or null if not present
 */
export const getAuthToken = () => {
  return localStorage.getItem(TOKEN_STORAGE_KEY);
};

/**
 * Helper function to remove JWT token from localStorage
 * Called during logout
 */
export const clearAuthToken = () => {
  localStorage.removeItem(TOKEN_STORAGE_KEY);
  localStorage.removeItem(TOKEN_EXPIRY_STORAGE_KEY);
};

/**
 * Helper function to check if user is authenticated
 * 
 * @returns {boolean} True if valid token exists, false otherwise
 */
export const isAuthenticated = () => {
  const token = localStorage.getItem(TOKEN_STORAGE_KEY);
  
  if (!token) {
    return false;
  }
  
  // Check if token is expired
  const tokenExpiry = localStorage.getItem(TOKEN_EXPIRY_STORAGE_KEY);
  if (tokenExpiry) {
    const expiryTime = parseInt(tokenExpiry, 10);
    const currentTime = Date.now();
    
    if (currentTime >= expiryTime) {
      // Token expired, clear it
      clearAuthToken();
      return false;
    }
  }
  
  return true;
};

/**
 * Export the configured axios instance as default export
 * This instance includes all interceptors and default configuration
 * 
 * Available methods:
 * - get(url, config): Perform GET request
 * - post(url, data, config): Perform POST request
 * - put(url, data, config): Perform PUT request
 * - delete(url, config): Perform DELETE request
 * - patch(url, data, config): Perform PATCH request
 * 
 * Example usage:
 * import apiClient from './utils/apiClient';
 * 
 * const response = await apiClient.get('/accounts/123');
 * const newAccount = await apiClient.post('/accounts', { name: 'John' });
 */
export default apiClient;

