/**
 * Common Type Definitions for CardDemo Application
 * 
 * This module provides foundational shared TypeScript types for generic API patterns
 * and frontend utilities. All types are framework-agnostic and reusable across the
 * application to enable strict type safety and consistent API communication.
 * 
 * Converted from COBOL mainframe architecture to modern TypeScript React SPA
 * Part of CardDemo mainframe-to-cloud migration project
 * 
 * @module types/common
 */

/**
 * Generic API response wrapper
 * Provides consistent structure for all REST API responses from the backend
 * Type parameter T represents the actual data payload
 * 
 * This interface matches the standardized response structure from the Spring Boot
 * backend REST controllers, ensuring type-safe API communication.
 * 
 * @template T - The type of data being returned in the response
 * 
 * @example
 * ```typescript
 * // Account retrieval response
 * const response: ApiResponse<Account> = {
 *   data: { acctId: 123, acctCurrBal: 1000.00, ... },
 *   status: 'success',
 *   message: 'Account retrieved successfully',
 *   timestamp: '2025-10-25T10:30:00Z'
 * };
 * 
 * // Transaction list response
 * const listResponse: ApiResponse<Transaction[]> = {
 *   data: [...transactions],
 *   status: 'success',
 *   message: 'Transactions retrieved successfully'
 * };
 * ```
 */
export interface ApiResponse<T> {
  /** Actual data payload of type T */
  data: T;
  
  /** Response status indicating success or error */
  status: 'success' | 'error';
  
  /** Human-readable message describing the response */
  message: string;
  
  /** Optional ISO 8601 timestamp of when the response was generated */
  timestamp?: string;
}

/**
 * Error response structure for API errors
 * Matches backend GlobalExceptionHandler ErrorResponse.java structure
 * Used for displaying error messages to users and debugging issues
 * 
 * This interface ensures consistent error handling between frontend and backend,
 * preserving the exact error structure from Spring Boot exception handling.
 * 
 * @example
 * ```typescript
 * const error: ErrorResponse = {
 *   status: 404,
 *   error: 'DataNotFoundException',
 *   message: 'Account with ID 12345 not found',
 *   path: '/api/accounts/12345',
 *   timestamp: '2025-10-25T10:30:00Z',
 *   details: 'Account ID does not exist in database'
 * };
 * ```
 */
export interface ErrorResponse {
  /** HTTP status code (400, 404, 500, etc.) */
  status: number;
  
  /** Error type/category (e.g., 'ValidationException', 'DataNotFoundException') */
  error: string;
  
  /** Human-readable error message suitable for display to users */
  message: string;
  
  /** Request path that caused the error */
  path: string;
  
  /** ISO 8601 timestamp when error occurred */
  timestamp: string;
  
  /** Detailed error description or stack trace (dev/test environments only) */
  details?: string;
}

/**
 * Pagination parameters for paginated API requests
 * Used by table components for server-side or client-side pagination
 * 
 * Supports both request parameters (page, pageSize) and response metadata
 * (totalItems, totalPages) for complete pagination control.
 * 
 * @example
 * ```typescript
 * // Request pagination params
 * const requestParams: PaginationParams = {
 *   page: 1,
 *   pageSize: 20
 * };
 * 
 * // Response with pagination metadata
 * const responseParams: PaginationParams = {
 *   page: 1,
 *   pageSize: 20,
 *   totalItems: 150,
 *   totalPages: 8
 * };
 * ```
 */
export interface PaginationParams {
  /** Current page number (1-based indexing) */
  page: number;
  
  /** Number of items per page */
  pageSize: number;
  
  /** Total number of items across all pages (optional, from server response) */
  totalItems?: number;
  
  /** Total number of pages (optional, from server response) */
  totalPages?: number;
}

/**
 * Sorting parameters for table columns
 * Used by table components for server-side or client-side sorting
 * 
 * Supports single-field sorting with ascending or descending direction.
 * Field names should match entity property names from backend.
 * 
 * @example
 * ```typescript
 * // Sort by account balance descending
 * const sort: SortParams = {
 *   field: 'acctCurrBal',
 *   direction: 'desc'
 * };
 * 
 * // Sort by transaction date ascending
 * const dateSort: SortParams = {
 *   field: 'transOrigTs',
 *   direction: 'asc'
 * };
 * ```
 */
export interface SortParams {
  /** Field name to sort by (e.g., 'acctId', 'transAmt', 'cardNum') */
  field: string;
  
  /** Sort direction: ascending or descending */
  direction: 'asc' | 'desc';
}

/**
 * Form field validation errors
 * Maps each field name in T to its error message string
 * Used by form components (Formik, React Hook Form) for displaying field-level validation errors
 * 
 * This generic type ensures error keys match the form data structure exactly,
 * providing compile-time safety for form validation.
 * 
 * @template T - The form data type
 * 
 * @example
 * ```typescript
 * interface AccountFormData {
 *   acctId: string;
 *   acctCreditLimit: string;
 *   acctCashCreditLimit: string;
 * }
 * 
 * const errors: FormErrors<AccountFormData> = {
 *   acctId: 'Account ID is required',
 *   acctCreditLimit: 'Credit limit must be a positive number',
 *   acctCashCreditLimit: 'Cash credit limit cannot exceed total credit limit'
 * };
 * ```
 */
export type FormErrors<T> = {
  [K in keyof T]?: string;
};

/**
 * Validation rule for form fields
 * Defines a validator function and error message for reusable validation logic
 * Enables declarative validation rules that can be shared across forms
 * 
 * @template T - The type of value being validated (defaults to any)
 * 
 * @example
 * ```typescript
 * // Required field validator
 * const requiredRule: ValidationRule<string> = {
 *   validator: (value) => value.trim().length > 0,
 *   message: 'This field is required'
 * };
 * 
 * // Numeric validator
 * const numericRule: ValidationRule<string> = {
 *   validator: (value) => /^\d+$/.test(value),
 *   message: 'Must be a valid number'
 * };
 * 
 * // Credit limit validator
 * const creditLimitRule: ValidationRule<number> = {
 *   validator: (value) => value > 0 && value <= 100000,
 *   message: 'Credit limit must be between $0 and $100,000'
 * };
 * 
 * // Apply validation
 * const isValid = requiredRule.validator(formValue);
 * if (!isValid) {
 *   console.error(requiredRule.message);
 * }
 * ```
 */
export interface ValidationRule<T = any> {
  /** Validation function returning true if value is valid, false if invalid */
  validator: (value: T) => boolean;
  
  /** Error message to display when validation fails */
  message: string;
}

/**
 * Represents a value that may be null or undefined
 * Useful for optional database fields or API responses
 * 
 * @template T - The base type
 * 
 * @example
 * ```typescript
 * const middleName: Nullable<string> = null;
 * const expirationDate: Nullable<Date> = undefined;
 * ```
 */
export type Nullable<T> = T | null;

/**
 * Represents an optional value (may be undefined)
 * Similar to TypeScript's built-in optional properties
 * 
 * @template T - The base type
 * 
 * @example
 * ```typescript
 * const acctGroupId: Optional<string> = undefined;
 * ```
 */
export type Optional<T> = T | undefined;

/**
 * Makes all properties of T optional recursively
 * Useful for partial updates where only changed fields are sent
 * 
 * @template T - The base type
 * 
 * @example
 * ```typescript
 * interface Account {
 *   acctId: number;
 *   acctCurrBal: number;
 *   customer: {
 *     custId: number;
 *     custFirstName: string;
 *   };
 * }
 * 
 * const partialUpdate: DeepPartial<Account> = {
 *   acctCurrBal: 1500.00,
 *   customer: {
 *     custFirstName: 'John'
 *   }
 * };
 * ```
 */
export type DeepPartial<T> = {
  [P in keyof T]?: T[P] extends object ? DeepPartial<T[P]> : T[P];
};

/**
 * Extracts keys from T that have values of type V
 * Useful for filtering object properties by type
 * 
 * @template T - The object type
 * @template V - The value type to match
 * 
 * @example
 * ```typescript
 * interface Account {
 *   acctId: number;
 *   acctCurrBal: number;
 *   acctActiveStatus: string;
 *   acctOpenDate: Date;
 * }
 * 
 * // Get only numeric fields
 * type NumericFields = KeysOfType<Account, number>;
 * // Result: 'acctId' | 'acctCurrBal'
 * 
 * // Get only string fields
 * type StringFields = KeysOfType<Account, string>;
 * // Result: 'acctActiveStatus'
 * ```
 */
export type KeysOfType<T, V> = {
  [K in keyof T]: T[K] extends V ? K : never;
}[keyof T];

/**
 * HTTP request methods supported by REST API
 * Covers standard CRUD operations from backend controllers
 * 
 * @example
 * ```typescript
 * const method: HttpMethod = 'POST';
 * 
 * function apiRequest(method: HttpMethod, url: string, data?: any) {
 *   // Make API request with specified method
 * }
 * ```
 */
export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

/**
 * Status of asynchronous requests
 * Used by hooks and components for tracking loading states
 * 
 * State transitions typically flow:
 * idle → loading → success (or error)
 * 
 * @example
 * ```typescript
 * const [status, setStatus] = useState<RequestStatus>('idle');
 * 
 * async function fetchData() {
 *   setStatus('loading');
 *   try {
 *     const result = await api.get('/accounts');
 *     setStatus('success');
 *   } catch (error) {
 *     setStatus('error');
 *   }
 * }
 * 
 * // Render based on status
 * if (status === 'loading') return <LoadingSpinner />;
 * if (status === 'error') return <ErrorMessage />;
 * if (status === 'success') return <DataDisplay />;
 * ```
 */
export type RequestStatus = 'idle' | 'loading' | 'success' | 'error';
