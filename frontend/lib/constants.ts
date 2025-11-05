/**
 * Application-wide constants for the OCR Processing Application
 *
 * This module provides a single source of truth for all configuration values,
 * API endpoints, validation rules, document types, status enums, and default settings.
 * All constants are immutable and properly typed to prevent runtime modifications.
 *
 * @module constants
 */

/**
 * API endpoint configuration
 * Defines all REST API endpoint paths following the /api/v1 pattern
 */
export const API_ENDPOINTS = {
  /** Base API URL pattern */
  BASE_URL: '/api/v1',

  /** Authentication endpoints */
  auth: '/auth',

  /** Document management endpoints */
  documents: '/documents',

  /** Template management endpoints */
  templates: '/templates',

  /** Processing job endpoints */
  processing: '/processing',

  /** Analytics and reporting endpoints */
  analytics: '/analytics',

  /** User management endpoints */
  users: '/users',

  /** Third-party integration endpoints */
  integrations: '/integrations',

  /** API key management endpoints */
  apiKeys: '/api-keys',

  /** Webhook configuration endpoints */
  webhooks: '/webhooks',

  /** Data export endpoints */
  export: '/export',
} as const;

/**
 * Supported document types for classification
 * These types are used for document classification and template matching
 */
export const DOCUMENT_TYPES = ['invoice', 'receipt', 'contract', 'form', 'other'] as const;

/**
 * Document status enumeration
 * Represents the current state of a document in the system
 */
export const DOCUMENT_STATUS = {
  /** Document uploaded, awaiting processing */
  PENDING: 'pending',

  /** Document is currently being processed by OCR */
  PROCESSING: 'processing',

  /** OCR processing completed successfully */
  COMPLETED: 'completed',

  /** Processing failed due to an error */
  FAILED: 'failed',

  /** Document is under manual review */
  REVIEWING: 'reviewing',

  /** Document has been approved by user */
  APPROVED: 'approved',
} as const;

/**
 * Processing job status enumeration
 * Tracks the state of background processing jobs
 */
export const PROCESSING_STATUS = {
  /** Job created but not yet queued */
  PENDING: 'pending',

  /** Job added to processing queue */
  QUEUED: 'queued',

  /** Job is actively being processed */
  PROCESSING: 'processing',

  /** Job completed successfully */
  COMPLETED: 'completed',

  /** Job failed with error */
  FAILED: 'failed',

  /** Job cancelled by user or system */
  CANCELLED: 'cancelled',
} as const;

/**
 * File size limits configuration
 * All sizes are in bytes
 */
export const FILE_SIZE_LIMITS = {
  /** Maximum individual file size: 10MB in bytes */
  MAX_FILE_SIZE: 10485760,

  /** Maximum number of documents in a single batch per Section 0.1.2 */
  MAX_BATCH_SIZE: 100,

  /** Maximum total size for batch upload: 100MB in bytes */
  MAX_TOTAL_BATCH_SIZE: 104857600,
} as const;

/**
 * Supported MIME types for document upload
 * Used for file validation on upload
 */
export const SUPPORTED_FILE_FORMATS = ['application/pdf', 'image/jpeg', 'image/png'] as const;

/**
 * Supported file extensions for document upload
 * Used for client-side file validation
 */
export const SUPPORTED_FILE_EXTENSIONS = ['.pdf', '.jpg', '.jpeg', '.png'] as const;

/**
 * Date formatting patterns for date-fns library
 * Provides consistent date display across the application
 */
export const DATE_FORMATS = {
  /** ISO 8601 format: yyyy-MM-dd */
  ISO: 'yyyy-MM-dd',

  /** Short US format: MM/dd/yyyy */
  SHORT: 'MM/dd/yyyy',

  /** Long format: MMMM dd, yyyy */
  LONG: 'MMMM dd, yyyy',

  /** Relative time format (e.g., "2 hours ago") */
  RELATIVE: 'relative',

  /** Full date and time: MMMM dd, yyyy HH:mm:ss */
  DATETIME: 'MMMM dd, yyyy HH:mm:ss',
} as const;

/**
 * Pagination configuration
 * Default values for paginated list views
 */
export const PAGINATION = {
  /** Default number of items per page */
  DEFAULT_PAGE_SIZE: 25,

  /** Maximum number of items per page */
  MAX_PAGE_SIZE: 100,

  /** Default starting page (1-indexed) */
  DEFAULT_PAGE: 1,
} as const;

/**
 * OCR confidence score thresholds
 * Values range from 0.0 to 1.0, representing confidence percentage
 */
export const CONFIDENCE_THRESHOLDS = {
  /** Low confidence threshold (below this requires review) */
  LOW: 0.7,

  /** Medium confidence threshold */
  MEDIUM: 0.85,

  /** High confidence threshold (above this is considered reliable) */
  HIGH: 0.95,
} as const;

/**
 * OCR processing configuration
 * Settings for OCR engine behavior
 */
export const OCR_CONFIG = {
  /** OCR processing timeout in milliseconds (60 seconds) */
  TIMEOUT: 60000,

  /** Number of retry attempts for failed OCR operations */
  RETRY_ATTEMPTS: 3,

  /** Maximum number of pages to process per document */
  MAX_PAGES: 1000,
} as const;

/**
 * API client configuration
 * Settings for HTTP client behavior per Section 0.7.1
 */
export const API_CONFIG = {
  /** API request timeout in milliseconds (30 seconds) */
  TIMEOUT: 30000,

  /** Number of retry attempts for failed API requests */
  RETRY_ATTEMPTS: 3,

  /** Rate limit: requests per hour per account */
  RATE_LIMIT: 1000,
} as const;

/**
 * WebSocket event names for real-time updates
 * Used for subscribing to and emitting events
 */
export const WEBSOCKET_EVENTS = {
  /** Fired when document processing is complete */
  DOCUMENT_PROCESSED: 'document:processed',

  /** Fired when document processing starts */
  DOCUMENT_PROCESSING: 'document:processing',

  /** Fired during job execution with progress updates */
  JOB_PROGRESS: 'job:progress',

  /** Fired when job completes successfully */
  JOB_COMPLETED: 'job:completed',

  /** Fired when job fails */
  JOB_FAILED: 'job:failed',

  /** WebSocket connection established */
  CONNECT: 'connect',

  /** WebSocket connection terminated */
  DISCONNECT: 'disconnect',

  /** WebSocket error occurred */
  ERROR: 'error',
} as const;

/**
 * User-friendly error messages
 * Provides consistent error messaging across the application
 */
export const ERROR_MESSAGES = {
  /** Generic network error */
  NETWORK_ERROR: 'Network error. Please check your connection and try again.',

  /** 401 Unauthorized */
  UNAUTHORIZED: 'Your session has expired. Please log in again.',

  /** 403 Forbidden */
  FORBIDDEN: 'You do not have permission to perform this action.',

  /** 404 Not Found */
  NOT_FOUND: 'The requested resource was not found.',

  /** 400 Validation Error */
  VALIDATION_ERROR: 'Please check your input and try again.',

  /** 500 Server Error */
  SERVER_ERROR: 'An unexpected error occurred. Please try again later.',

  /** 429 Rate Limit Exceeded */
  RATE_LIMIT_EXCEEDED: 'Too many requests. Please wait a moment and try again.',

  /** File size validation error */
  FILE_TOO_LARGE: 'File size exceeds the maximum limit of 10MB.',

  /** File type validation error */
  INVALID_FILE_TYPE: 'Invalid file type. Only PDF, JPG, and PNG files are supported.',

  /** Upload operation failed */
  UPLOAD_FAILED: 'File upload failed. Please try again.',

  /** Processing operation failed */
  PROCESSING_FAILED: 'Document processing failed. Please try again or contact support.',
} as const;

/**
 * Validation rules for user input
 * Used for form validation across the application
 */
export const VALIDATION_RULES = {
  /** Minimum password length in characters */
  MIN_PASSWORD_LENGTH: 8,

  /** Maximum password length in characters */
  MAX_PASSWORD_LENGTH: 128,

  /** RFC 5322 compliant email validation regex */
  EMAIL_REGEX:
    /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/,

  /** E.164 phone number format validation regex */
  PHONE_REGEX: /^\+?[1-9]\d{1,14}$/,

  /** URL validation regex (http/https) */
  URL_REGEX:
    /^https?:\/\/(www\.)?[-a-zA-Z0-9@:%._\+~#=]{1,256}\.[a-zA-Z0-9()]{1,6}\b([-a-zA-Z0-9()@:%_\+.~#?&//=]*)$/,

  /** Minimum username length in characters */
  MIN_USERNAME_LENGTH: 3,

  /** Maximum username length in characters */
  MAX_USERNAME_LENGTH: 50,
} as const;

/**
 * Application route paths
 * Centralized routing configuration for consistent navigation
 */
export const ROUTES = {
  /** Landing/home page */
  HOME: '/',

  /** Login page */
  LOGIN: '/auth/login',

  /** Registration page */
  SIGNUP: '/auth/signup',

  /** Password reset flow */
  FORGOT_PASSWORD: '/auth/forgot-password',

  /** Main user dashboard */
  DASHBOARD: '/dashboard',

  /** Document list view */
  DOCUMENTS: '/documents',

  /** Document detail view (requires :id parameter) */
  DOCUMENT_DETAIL: '/documents/[id]',

  /** Single document upload */
  DOCUMENT_UPLOAD: '/documents/upload',

  /** Batch document upload */
  BATCH_UPLOAD: '/documents/batch',

  /** Template list view */
  TEMPLATES: '/templates',

  /** Visual template builder */
  TEMPLATE_BUILDER: '/templates/builder',

  /** Template detail/edit view (requires :id parameter) */
  TEMPLATE_DETAIL: '/templates/[id]',

  /** Analytics dashboard */
  ANALYTICS: '/analytics',

  /** Account settings */
  SETTINGS: '/settings',

  /** User management */
  USERS: '/settings/users',

  /** Integration management */
  INTEGRATIONS: '/settings/integrations',

  /** API key management */
  API_KEYS: '/settings/api-keys',
} as const;

/**
 * TypeScript type exports for better type inference
 */
export type DocumentType = (typeof DOCUMENT_TYPES)[number];
export type DocumentStatus = (typeof DOCUMENT_STATUS)[keyof typeof DOCUMENT_STATUS];
export type ProcessingStatus = (typeof PROCESSING_STATUS)[keyof typeof PROCESSING_STATUS];
export type SupportedFileFormat = (typeof SUPPORTED_FILE_FORMATS)[number];
export type SupportedFileExtension = (typeof SUPPORTED_FILE_EXTENSIONS)[number];
export type WebSocketEvent = (typeof WEBSOCKET_EVENTS)[keyof typeof WEBSOCKET_EVENTS];
export type RouteKey = keyof typeof ROUTES;
export type RoutePath = (typeof ROUTES)[RouteKey];
