/**
 * Core Axios HTTP Client Configuration Module
 * 
 * Converted from: COBOL COMMAREA session management and EXEC CICS LINK program calls
 * Purpose: Provides centralized API communication setup with JWT authentication,
 *          error handling, and retry logic for Spring Boot backend integration
 * 
 * Key Features:
 * - JWT token management via request interceptors
 * - Centralized error handling via response interceptors
 * - Token refresh logic for expired sessions
 * - Retry mechanism with exponential backoff
 * - Date serialization/deserialization
 * - Request queuing during token refresh
 */

import axios, {
  AxiosInstance,
  AxiosResponse,
  AxiosError,
  InternalAxiosRequestConfig
} from 'axios';

/**
 * API Error Interface
 * Represents standardized error responses from Spring Boot backend
 */
export interface ApiError {
  status: number;
  message: string;
  errors: string[];
}

/**
 * Request Queue Entry
 * Used to queue requests during token refresh
 */
interface QueuedRequest {
  resolve: (value: any) => void;
  reject: (reason: any) => void;
  config: InternalAxiosRequestConfig;
}

/**
 * Environment Configuration
 * Base URL is configurable via environment variables for different environments
 */
const API_BASE_URL = import.meta.env['VITE_API_BASE_URL'] || 'http://localhost:8080/api';
const REQUEST_TIMEOUT = 30000; // 30 seconds for normal operations
const LONG_REQUEST_TIMEOUT = 60000; // 60 seconds for reports and file uploads
const MAX_RETRY_ATTEMPTS = 3;
const RETRY_DELAY_MS = 1000; // Initial delay for exponential backoff

/**
 * Token Management
 */
const AUTH_TOKEN_KEY = 'authToken';
const REFRESH_TOKEN_KEY = 'refreshToken';

/**
 * Request Queue for handling concurrent requests during token refresh
 */
let isRefreshing = false;
let requestQueue: QueuedRequest[] = [];

/**
 * Date Serialization Helper
 * Converts JavaScript Date objects to ISO string format for API requests
 */
const serializeDate = (data: any): any => {
  if (data instanceof Date) {
    return data.toISOString();
  }
  if (Array.isArray(data)) {
    return data.map(serializeDate);
  }
  if (data !== null && typeof data === 'object') {
    return Object.keys(data).reduce((result, key) => {
      result[key] = serializeDate(data[key]);
      return result;
    }, {} as any);
  }
  return data;
};

/**
 * Date Deserialization Helper
 * Parses ISO string dates back to JavaScript Date objects in API responses
 */
const deserializeDate = (data: any): any => {
  if (typeof data === 'string') {
    // ISO 8601 date pattern
    const isoDatePattern = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3})?Z?$/;
    if (isoDatePattern.test(data)) {
      return new Date(data);
    }
  }
  if (Array.isArray(data)) {
    return data.map(deserializeDate);
  }
  if (data !== null && typeof data === 'object') {
    return Object.keys(data).reduce((result, key) => {
      result[key] = deserializeDate(data[key]);
      return result;
    }, {} as any);
  }
  return data;
};

/**
 * Create Axios Instance with Base Configuration
 */
const api: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: REQUEST_TIMEOUT,
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true, // Enable CORS with credentials
});

/**
 * Request Interceptor
 * Automatically attaches JWT token from localStorage to Authorization header
 * Handles date serialization and dynamic timeout configuration
 */
api.interceptors.request.use(
  (config: InternalAxiosRequestConfig): InternalAxiosRequestConfig => {
    // Retrieve JWT token from localStorage
    const token = localStorage.getItem(AUTH_TOKEN_KEY);
    
    // Attach Authorization header if token exists
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }

    // Configure timeout based on endpoint
    // Reports and file uploads need longer timeout
    if (
      config.url?.includes('/reports/generate') ||
      config.url?.includes('/upload') ||
      config.headers['Content-Type']?.toString().includes('multipart/form-data')
    ) {
      config.timeout = LONG_REQUEST_TIMEOUT;
    }

    // Handle multipart/form-data for file uploads
    if (config.data instanceof FormData) {
      config.headers['Content-Type'] = 'multipart/form-data';
    }

    // Serialize dates in request data
    if (config.data && config.headers['Content-Type'] === 'application/json') {
      config.data = serializeDate(config.data);
    }

    // Development logging
    if (import.meta.env.DEV) {
      console.log('[API Request]', {
        method: config.method?.toUpperCase(),
        url: config.url,
        data: config.data,
        headers: config.headers,
      });
    }

    return config;
  },
  (error: AxiosError): Promise<AxiosError> => {
    // Log request errors in development
    if (import.meta.env.DEV) {
      console.error('[API Request Error]', error);
    }
    return Promise.reject(error);
  }
);

/**
 * Process Queued Requests
 * Executes all queued requests after token refresh
 */
const processQueue = (error: AxiosError | null = null): void => {
  requestQueue.forEach((request) => {
    if (error) {
      request.reject(error);
    } else {
      request.resolve(api(request.config));
    }
  });
  requestQueue = [];
};

/**
 * Refresh Token Function
 * Attempts to refresh the JWT token using the refresh token
 */
const refreshAuthToken = async (): Promise<string> => {
  try {
    const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
    
    if (!refreshToken) {
      throw new Error('No refresh token available');
    }

    // Call token refresh endpoint using a separate axios instance to avoid interceptor loops
    // This bypasses the api instance interceptors to prevent infinite loops
    const refreshInstance = axios.create({
      baseURL: API_BASE_URL,
      timeout: REQUEST_TIMEOUT,
    });

    const response = await refreshInstance.post('/auth/refresh', {
      refreshToken,
    });

    const { token, refreshToken: newRefreshToken } = response.data;

    // Update tokens in localStorage
    localStorage.setItem(AUTH_TOKEN_KEY, token);
    if (newRefreshToken) {
      localStorage.setItem(REFRESH_TOKEN_KEY, newRefreshToken);
    }

    return token;
  } catch (error) {
    // Clear tokens on refresh failure
    localStorage.removeItem(AUTH_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    throw error;
  }
};

/**
 * Response Interceptor
 * Handles centralized error processing, token refresh, and date deserialization
 */
api.interceptors.response.use(
  (response: AxiosResponse): AxiosResponse => {
    // Deserialize dates in response data
    if (response.data) {
      response.data = deserializeDate(response.data);
    }

    // Development logging
    if (import.meta.env.DEV) {
      console.log('[API Response]', {
        status: response.status,
        url: response.config.url,
        data: response.data,
      });
    }

    return response;
  },
  async (error: AxiosError): Promise<any> => {
    const originalRequest = error.config as InternalAxiosRequestConfig & {
      _retry?: boolean;
      _retryCount?: number;
    };

    // Development logging
    if (import.meta.env.DEV) {
      console.error('[API Response Error]', {
        status: error.response?.status,
        url: originalRequest?.url,
        message: error.message,
        data: error.response?.data,
      });
    }

    // Handle 401 Unauthorized - Token refresh logic
    if (error.response?.status === 401 && !originalRequest._retry) {
      if (isRefreshing) {
        // Queue request if token refresh is already in progress
        return new Promise((resolve, reject) => {
          requestQueue.push({ resolve, reject, config: originalRequest });
        });
      }

      originalRequest._retry = true;
      isRefreshing = true;

      try {
        // Attempt to refresh token
        const newToken = await refreshAuthToken();
        
        // Update Authorization header with new token
        originalRequest.headers.Authorization = `Bearer ${newToken}`;
        
        // Process queued requests with new token
        processQueue();
        isRefreshing = false;
        
        // Retry original request with new token
        return api(originalRequest);
      } catch (refreshError) {
        // Token refresh failed - clear tokens and redirect to login
        processQueue(error);
        isRefreshing = false;
        
        localStorage.removeItem(AUTH_TOKEN_KEY);
        localStorage.removeItem(REFRESH_TOKEN_KEY);
        
        // Redirect to login page
        if (typeof window !== 'undefined') {
          window.location.href = '/login';
        }
        
        return Promise.reject(refreshError);
      }
    }

    // Handle 403 Forbidden - Access denied
    if (error.response?.status === 403) {
      const responseData = error.response.data as any;
      const apiError: ApiError = {
        status: 403,
        message: 'Access denied. You do not have permission to perform this action.',
        errors: [responseData?.message || 'Forbidden'],
      };
      return Promise.reject(apiError);
    }

    // Handle 404 Not Found
    if (error.response?.status === 404) {
      const responseData = error.response.data as any;
      const apiError: ApiError = {
        status: 404,
        message: 'Resource not found.',
        errors: [responseData?.message || 'Not Found'],
      };
      return Promise.reject(apiError);
    }

    // Handle 500 Internal Server Error
    if (error.response?.status === 500) {
      const responseData = error.response.data as any;
      const apiError: ApiError = {
        status: 500,
        message: 'An internal server error occurred. Please try again later.',
        errors: [responseData?.message || 'Internal Server Error'],
      };
      return Promise.reject(apiError);
    }

    // Handle network errors with retry logic
    if (isNetworkError(error) && shouldRetry(error)) {
      originalRequest._retryCount = originalRequest._retryCount || 0;

      if (originalRequest._retryCount < MAX_RETRY_ATTEMPTS) {
        originalRequest._retryCount++;

        // Calculate exponential backoff delay
        const delay = RETRY_DELAY_MS * Math.pow(2, originalRequest._retryCount - 1);

        if (import.meta.env.DEV) {
          console.log(`[API Retry] Attempt ${originalRequest._retryCount} after ${delay}ms`);
        }

        // Wait before retrying
        await new Promise((resolve) => setTimeout(resolve, delay));

        // Retry the request
        return api(originalRequest);
      }
    }

    // Handle request timeout
    if (error.code === 'ECONNABORTED' || error.message.includes('timeout')) {
      const apiError: ApiError = {
        status: 408,
        message: 'Request timeout. Please check your connection and try again.',
        errors: ['Request Timeout'],
      };
      return Promise.reject(apiError);
    }

    // Return standardized error format
    return Promise.reject(handleApiError(error));
  }
);

/**
 * Handle API Error
 * Converts AxiosError to standardized ApiError format
 * 
 * @param error - AxiosError from failed request
 * @returns Standardized ApiError object
 */
export const handleApiError = (error: AxiosError): ApiError => {
  // Extract error details from response
  const status = error.response?.status || 0;
  const responseData = error.response?.data as any;

  let message = 'An unexpected error occurred.';
  let errors: string[] = [];

  if (responseData) {
    // Spring Boot error response format
    message = responseData.message || responseData.error || message;
    
    // Handle validation errors (Spring Boot @Valid)
    if (responseData.errors && Array.isArray(responseData.errors)) {
      errors = responseData.errors;
    } else if (responseData.fieldErrors) {
      errors = Object.entries(responseData.fieldErrors).map(
        ([field, msg]) => `${field}: ${msg}`
      );
    } else if (typeof responseData.error === 'string') {
      errors = [responseData.error];
    }
  } else if (error.message) {
    message = error.message;
    errors = [error.message];
  }

  return {
    status,
    message,
    errors,
  };
};

/**
 * Check if error is a network error
 * Network errors typically don't have a response object
 * 
 * @param error - AxiosError to check
 * @returns true if network error, false otherwise
 */
export const isNetworkError = (error: AxiosError): boolean => {
  return (
    !error.response &&
    Boolean(error.request) &&
    (error.code === 'ERR_NETWORK' ||
      error.code === 'ECONNREFUSED' ||
      error.code === 'ETIMEDOUT' ||
      error.message.includes('Network Error'))
  );
};

/**
 * Check if error should be retried
 * Determines if the request should be retried based on error type
 * 
 * @param error - AxiosError to evaluate
 * @returns true if request should be retried, false otherwise
 */
export const shouldRetry = (error: AxiosError): boolean => {
  // Retry on network errors
  if (isNetworkError(error)) {
    return true;
  }

  // Retry on specific HTTP status codes (transient server errors)
  const status = error.response?.status;
  const retryableStatusCodes = [408, 429, 502, 503, 504];
  
  if (status && retryableStatusCodes.includes(status)) {
    return true;
  }

  // Don't retry client errors (4xx except specific cases) or successful responses
  return false;
};

/**
 * Export configured Axios instance as default export
 * Used by all service modules (authService, accountService, cardService, etc.)
 */
export default api;

