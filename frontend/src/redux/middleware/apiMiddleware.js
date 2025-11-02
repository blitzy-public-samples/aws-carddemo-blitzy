/**
 * Redux API Middleware
 * 
 * Centralized API request/response handling middleware for Redux store.
 * Implements JWT token attachment, HTTP status code processing, error transformation,
 * and retry logic with exponential backoff. Replaces CICS HANDLE CONDITION and 
 * RESP/RESP2 error handling patterns from mainframe COBOL programs.
 * 
 * Key Features:
 * - Automatic JWT token attachment to Authorization headers
 * - HTTP status code mapping (CICS RESP → HTTP status)
 * - User-friendly error message transformation
 * - Exponential backoff retry logic for idempotent requests
 * - Request cancellation with AbortController
 * - Loading state management
 * - Toast notification integration
 * 
 * COBOL Pattern Transformation:
 * - EXEC CICS READ RESP(WS-RESP-CD) → axios.get() with response.status
 * - EVALUATE WS-RESP-CD → switch(error.response?.status)
 * - WS-MESSAGE error messages → toast.error(message)
 * - HANDLE CONDITION ERROR → axios interceptor error handler
 * - DFHRESP(NORMAL) → HTTP 200/201
 * - DFHRESP(NOTFND) → HTTP 404
 * - DFHRESP(NOTAUTH) → HTTP 401
 * - DFHRESP(INVREQ) → HTTP 400
 * - DFHRESP(ERROR) → HTTP 500
 */

import axios from 'axios';
import { toast } from 'react-toastify';

// Retry configuration constants
const MAX_RETRY_ATTEMPTS = 3;
const RETRY_DELAY_MS = 1000;
const IDEMPOTENT_METHODS = ['GET'];

// Track pending requests for cancellation
const pendingRequests = new Map();

// Error message mappings from backend error codes to user-friendly messages
// Replaces COBOL error message handling (WS-MESSAGE, ERRMSG fields in BMS screens)
const ERROR_MESSAGES = {
  'ACCOUNT_NOT_FOUND': 'Account not found. Please verify account number.',
  'CARD_EXPIRED': 'Card has expired. Please contact support.',
  'CARD_NOT_FOUND': 'Card not found. Please verify card number.',
  'INSUFFICIENT_BALANCE': 'Insufficient account balance.',
  'INVALID_CREDENTIALS': 'Invalid username or password.',
  'USER_NOT_FOUND': 'User not found. Please check user ID.',
  'TRANSACTION_FAILED': 'Transaction processing failed. Please try again.',
  'DUPLICATE_TRANSACTION': 'Duplicate transaction detected.',
  'VALIDATION_ERROR': 'Please correct the highlighted fields.',
  'INVALID_ACCOUNT_NUMBER': 'Invalid account number format.',
  'INVALID_CARD_NUMBER': 'Invalid card number format.',
  'PAYMENT_FAILED': 'Payment processing failed. Please try again.',
  'UNAUTHORIZED_ACCESS': 'You do not have permission to perform this action.',
  'SESSION_EXPIRED': 'Your session has expired. Please login again.',
  'SERVER_UNAVAILABLE': 'Server is temporarily unavailable. Please try again later.',
  'NETWORK_ERROR': 'Network connection error. Please check your connection.',
  'INVALID_REQUEST': 'Invalid request data. Please check your input.',
  'TRANSACTION_LIMIT_EXCEEDED': 'Transaction limit exceeded.',
  'ACCOUNT_LOCKED': 'Account is locked. Please contact support.',
  'CARD_BLOCKED': 'Card is blocked. Please contact support.',
  'INVALID_DATE_RANGE': 'Invalid date range specified.',
  'UPDATE_CONFLICT': 'Data has been modified by another user. Please refresh and try again.'
};

/**
 * Retrieves JWT token from localStorage
 * @returns {string|null} JWT token or null if not found
 */
const getAuthToken = () => {
  try {
    const token = localStorage.getItem('jwtToken');
    return token;
  } catch (error) {
    console.error('Error retrieving auth token:', error);
    return null;
  }
};

/**
 * Clears JWT token from localStorage and dispatches logout action
 * Used when session expires (401 Unauthorized)
 */
const clearAuthToken = () => {
  try {
    localStorage.removeItem('jwtToken');
    localStorage.removeItem('user');
    localStorage.removeItem('userType');
  } catch (error) {
    console.error('Error clearing auth token:', error);
  }
};

/**
 * Generates unique request ID for tracking and cancellation
 * @returns {string} Unique request identifier
 */
const generateRequestId = () => {
  return `req_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
};

/**
 * Cancels pending request by ID
 * @param {string} requestId - Request identifier to cancel
 */
const cancelRequest = (requestId) => {
  const controller = pendingRequests.get(requestId);
  if (controller) {
    controller.abort();
    pendingRequests.delete(requestId);
    console.log(`Request ${requestId} cancelled`);
  }
};

/**
 * Cancels all pending requests
 * Used when user logs out or navigates away
 */
const cancelAllRequests = () => {
  pendingRequests.forEach((controller, requestId) => {
    controller.abort();
    console.log(`Request ${requestId} cancelled`);
  });
  pendingRequests.clear();
};

/**
 * Implements exponential backoff retry logic for failed idempotent requests
 * Only retries GET requests (safe to retry without side effects)
 * 
 * @param {Error} error - Axios error object
 * @param {number} retryCount - Current retry attempt number
 * @returns {Promise} Promise resolving to response or rejecting with error
 */
const retryRequest = async (error, retryCount = 0) => {
  // Only retry idempotent operations (GET requests)
  const method = error.config?.method?.toUpperCase();
  if (!IDEMPOTENT_METHODS.includes(method)) {
    console.log(`Not retrying non-idempotent ${method} request`);
    throw error;
  }
  
  // Check if retry limit exceeded
  if (retryCount >= MAX_RETRY_ATTEMPTS) {
    console.error(`Max retry attempts (${MAX_RETRY_ATTEMPTS}) exceeded`);
    throw error;
  }
  
  // Only retry on network errors or 5xx server errors
  const shouldRetry = !error.response || (error.response.status >= 500 && error.response.status < 600);
  if (!shouldRetry) {
    throw error;
  }
  
  // Calculate exponential backoff delay: delay * 2^retryCount
  const delay = RETRY_DELAY_MS * Math.pow(2, retryCount);
  console.log(`Retrying request after ${delay}ms (attempt ${retryCount + 1}/${MAX_RETRY_ATTEMPTS})`);
  
  // Wait for delay period
  await new Promise(resolve => setTimeout(resolve, delay));
  
  // Create new AbortController for retry
  const abortController = new AbortController();
  const retryConfig = {
    ...error.config,
    signal: abortController.signal
  };
  
  // Retry the request
  try {
    const response = await axios.request(retryConfig);
    return response;
  } catch (retryError) {
    // Recursive retry with incremented count
    return retryRequest(retryError, retryCount + 1);
  }
};

/**
 * Transforms backend error response to user-friendly message
 * Maps error codes from backend to predefined user messages
 * 
 * @param {Object} error - Axios error object
 * @returns {string} User-friendly error message
 */
const getErrorMessage = (error) => {
  // Check for custom error code from backend
  const errorCode = error.response?.data?.errorCode;
  if (errorCode && ERROR_MESSAGES[errorCode]) {
    return ERROR_MESSAGES[errorCode];
  }
  
  // Check for custom error message from backend
  const backendMessage = error.response?.data?.message;
  if (backendMessage) {
    return backendMessage;
  }
  
  // Fallback to generic HTTP status messages
  const status = error.response?.status;
  switch (status) {
    case 400:
      return 'Invalid request. Please check your input and try again.';
    case 401:
      return 'Session expired. Please login again.';
    case 403:
      return 'Access denied. You do not have permission to perform this action.';
    case 404:
      return 'Resource not found. Please verify and try again.';
    case 409:
      return 'Conflict detected. Data may have been modified by another user.';
    case 422:
      return 'Validation failed. Please correct the highlighted fields.';
    case 429:
      return 'Too many requests. Please wait and try again.';
    case 500:
      return 'Server error. Please try again later.';
    case 502:
      return 'Bad gateway. Please try again later.';
    case 503:
      return 'Service temporarily unavailable. Please try again later.';
    case 504:
      return 'Gateway timeout. Please try again.';
    default:
      if (error.message === 'Network Error') {
        return ERROR_MESSAGES.NETWORK_ERROR;
      }
      return 'An unexpected error occurred. Please try again.';
  }
};

/**
 * Extracts validation errors from 400 Bad Request responses
 * Formats field-specific validation messages for display
 * 
 * @param {Object} errorResponse - Error response data from backend
 * @returns {Object} Map of field names to error messages
 */
const extractValidationErrors = (errorResponse) => {
  const errors = {};
  
  if (errorResponse?.errors && Array.isArray(errorResponse.errors)) {
    errorResponse.errors.forEach(error => {
      if (error.field && error.message) {
        errors[error.field] = error.message;
      }
    });
  } else if (errorResponse?.fieldErrors) {
    // Alternative format: { fieldErrors: { fieldName: "error message" } }
    Object.assign(errors, errorResponse.fieldErrors);
  }
  
  return errors;
};

/**
 * Configures axios request interceptor
 * Attaches JWT token to Authorization header for authenticated requests
 * Sets Content-Type and other default headers
 */
axios.interceptors.request.use(
  (config) => {
    // Retrieve JWT token from localStorage
    const token = getAuthToken();
    
    // Attach Authorization header if token exists
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    
    // Set Content-Type for requests with body
    if (config.data && !config.headers['Content-Type']) {
      config.headers['Content-Type'] = 'application/json';
    }
    
    // Log request details in development
    if (process.env.NODE_ENV === 'development') {
      console.log(`[API Request] ${config.method?.toUpperCase()} ${config.url}`, {
        headers: config.headers,
        data: config.data
      });
    }
    
    return config;
  },
  (error) => {
    console.error('[API Request Error]', error);
    return Promise.reject(error);
  }
);

/**
 * Configures axios response interceptor
 * Processes HTTP status codes and transforms errors to user-friendly messages
 * Implements CICS RESP code mapping to HTTP status codes
 */
axios.interceptors.response.use(
  (response) => {
    // Log successful response in development
    if (process.env.NODE_ENV === 'development') {
      console.log(`[API Response] ${response.status} ${response.config.url}`, response.data);
    }
    
    // Return response data for successful requests (200-299)
    return response;
  },
  async (error) => {
    const status = error.response?.status;
    const config = error.config;
    
    // Log error details
    console.error('[API Response Error]', {
      status,
      url: config?.url,
      method: config?.method,
      data: error.response?.data
    });
    
    // Handle specific HTTP status codes
    // Maps CICS RESP codes to modern HTTP status handling
    switch (status) {
      case 401: // Unauthorized - DFHRESP(NOTAUTH)
        // Clear JWT token and redirect to login
        clearAuthToken();
        toast.error('Session expired. Please login again.');
        
        // Redirect to login page
        window.location.href = '/login';
        break;
        
      case 403: // Forbidden - DFHRESP(NOTAUTH) with different context
        toast.error('Access denied. You do not have permission to perform this action.');
        break;
        
      case 404: { // Not Found - DFHRESP(NOTFND)
        const notFoundMessage = getErrorMessage(error);
        toast.error(notFoundMessage);
        break;
      }
        
      case 400: { // Bad Request - DFHRESP(INVREQ)
        const validationErrors = extractValidationErrors(error.response?.data);
        
        if (Object.keys(validationErrors).length > 0) {
          // Display field-specific validation errors
          toast.error('Please correct the highlighted fields.');
          
          // Store validation errors in error object for form handling
          error.validationErrors = validationErrors;
        } else {
          toast.error(getErrorMessage(error));
        }
        break;
      }
        
      case 409: // Conflict
        toast.warning('Data has been modified by another user. Please refresh and try again.');
        break;
        
      case 422: // Unprocessable Entity
        toast.error('Validation failed. Please check your input.');
        break;
        
      case 500: // Internal Server Error - DFHRESP(ERROR)
      case 502: // Bad Gateway
      case 503: // Service Unavailable
      case 504: // Gateway Timeout
        toast.error('Server error. Please try again later.');
        
        // Attempt retry for idempotent requests
        try {
          const retryResponse = await retryRequest(error, 0);
          return retryResponse;
        } catch (retryError) {
          // All retries failed, propagate error
          return Promise.reject(retryError);
        }
        
      default:
        // Network error or other unexpected error
        if (error.message === 'Network Error' || !error.response) {
          toast.error('Network error. Please check your connection and try again.');
          
          // Attempt retry for network errors on idempotent requests
          try {
            const retryResponse = await retryRequest(error, 0);
            return retryResponse;
          } catch (retryError) {
            return Promise.reject(retryError);
          }
        } else {
          toast.error(getErrorMessage(error));
        }
    }
    
    return Promise.reject(error);
  }
);

/**
 * Redux middleware for API request handling
 * 
 * Intercepts Redux actions targeting API calls, manages loading states,
 * handles request cancellation, and dispatches success/error actions.
 * 
 * Expected action format:
 * {
 *   type: 'FEATURE/API_REQUEST',
 *   payload: {
 *     endpoint: '/api/resource',
 *     method: 'GET' | 'POST' | 'PUT' | 'DELETE',
 *     data: { ... },
 *     onSuccess: (data) => successAction,
 *     onError: (error) => errorAction,
 *     showLoader: true,
 *     cancelable: true,
 *     requestId: 'optional_custom_id'
 *   }
 * }
 * 
 * @param {Object} store - Redux store instance
 * @returns {Function} Middleware function
 */
const apiMiddleware = store => next => action => {
  // Pass through non-API actions
  if (!action.type || !action.type.includes('API_REQUEST')) {
    return next(action);
  }
  
  // Extract request configuration from action payload
  const {
    endpoint,
    method = 'GET',
    data,
    onSuccess,
    onError,
    showLoader = true,
    cancelable = false,
    requestId = generateRequestId()
  } = action.payload || {};
  
  // Validate required fields
  if (!endpoint) {
    console.error('[API Middleware] Missing required endpoint in action payload');
    return next(action);
  }
  
  // Dispatch loading state if requested
  if (showLoader) {
    store.dispatch({ type: 'app/setLoading', payload: true });
  }
  
  // Create AbortController for request cancellation
  const abortController = new AbortController();
  
  // Track request for cancellation if requested
  if (cancelable) {
    pendingRequests.set(requestId, abortController);
  }
  
  // Configure axios request
  const axiosConfig = {
    method: method.toUpperCase(),
    url: endpoint,
    data: data,
    signal: abortController.signal
  };
  
  // Execute API request
  return axios(axiosConfig)
    .then(response => {
      // Clear loading state
      if (showLoader) {
        store.dispatch({ type: 'app/setLoading', payload: false });
      }
      
      // Remove from pending requests
      if (cancelable) {
        pendingRequests.delete(requestId);
      }
      
      // Dispatch success action if provided
      if (onSuccess && typeof onSuccess === 'function') {
        const successAction = onSuccess(response.data);
        if (successAction) {
          store.dispatch(successAction);
        }
      }
      
      // Return response data
      return response.data;
    })
    .catch(error => {
      // Clear loading state
      if (showLoader) {
        store.dispatch({ type: 'app/setLoading', payload: false });
      }
      
      // Remove from pending requests
      if (cancelable) {
        pendingRequests.delete(requestId);
      }
      
      // Handle abort errors (user cancelled)
      if (error.name === 'AbortError' || error.code === 'ERR_CANCELED') {
        console.log(`[API Middleware] Request ${requestId} was cancelled`);
        return Promise.reject({ cancelled: true, error });
      }
      
      // Dispatch error action if provided
      if (onError && typeof onError === 'function') {
        const errorAction = onError(error);
        if (errorAction) {
          store.dispatch(errorAction);
        }
      }
      
      // Propagate error for promise chain
      return Promise.reject(error);
    });
};

// Export middleware as default
export default apiMiddleware;

// Export utility functions for external use
export {
  cancelRequest,
  cancelAllRequests,
  getErrorMessage,
  extractValidationErrors
};
