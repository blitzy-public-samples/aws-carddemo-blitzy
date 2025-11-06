/**
 * OCR Client Service
 * 
 * Axios-based HTTP client for communication with the Python FastAPI OCR processing service.
 * Implements retry logic with exponential backoff, comprehensive error handling, and
 * structured logging for reliable integration with the OCR microservice.
 * 
 * Configuration:
 * - OCR_SERVICE_URL: Base URL of the OCR service (default: http://ocr-service:8000)
 * 
 * Retry Behavior:
 * - Maximum 3 attempts for retryable errors (network, timeout, 5xx)
 * - Exponential backoff: 1s, 2s, 4s (capped at 10s)
 * - 4xx errors are not retried (client errors)
 * 
 * Timeout:
 * - 60 seconds for all requests (optimized for document processing)
 * 
 * @module OcrClient
 * @see Section 0.4.2 Backend API ↔ OCR Service Communication
 * @see Section 0.5.5 Phase 4: OCR Processing Pipeline
 */

import { Injectable, HttpException, HttpStatus, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import axios, { AxiosInstance, AxiosRequestConfig, AxiosError } from 'axios';
import { v4 as uuidv4 } from 'uuid';

/**
 * Request payload for OCR document processing
 */
export interface OcrProcessRequest {
  /** Unique document identifier from the backend system */
  documentId: string;
  
  /** Account identifier for multi-tenant isolation */
  accountId: string;
  
  /** S3 presigned URL or accessible file URL for the document */
  fileUrl: string;
  
  /** Original filename for reference */
  fileName: string;
  
  /** MIME type of the document (e.g., application/pdf, image/jpeg) */
  mimeType: string;
  
  /** Optional template ID for template-based extraction */
  templateId?: string;
  
  /** Optional processing options */
  options?: {
    /** Force use of cloud API (Google Vision/AWS Textract) instead of Tesseract */
    useCloudApi?: boolean;
    
    /** Language code for OCR (e.g., 'eng', 'spa', 'fra') */
    language?: string;
    
    /** DPI for image processing (default: 300) */
    dpi?: number;
  };
}

/**
 * Response from OCR document processing
 */
export interface OcrProcessResponse {
  /** Indicates if processing was successful */
  success: boolean;
  
  /** Job identifier for tracking asynchronous processing */
  jobId: string;
  
  /** Document identifier matching the request */
  documentId: string;
  
  /** Full extracted text content from the document */
  extractedText: string;
  
  /** Overall confidence score (0-100) */
  confidence: number;
  
  /** Number of pages processed */
  pageCount: number;
  
  /** Extracted structured fields (if template was used or NLP extraction) */
  fields?: Array<{
    name: string;
    value: string;
    confidence: number;
    boundingBox: {
      x: number;
      y: number;
      width: number;
      height: number;
    };
  }>;
  
  /** Processing metadata */
  metadata: {
    /** Processing time in milliseconds */
    processingTime: number;
    
    /** OCR engine used (tesseract, google-vision, aws-textract) */
    engine: string;
    
    /** Detected or specified language */
    language: string;
  };
  
  /** Error message if processing failed */
  error?: string;
}

/**
 * Health check response from OCR service
 */
export interface HealthCheckResponse {
  /** Service status */
  status: 'ok' | 'degraded' | 'down';
  
  /** Service version */
  version: string;
  
  /** Timestamp of the health check */
  timestamp: string;
  
  /** Status of individual service components */
  services: {
    /** Tesseract OCR engine availability */
    tesseract: boolean;
    
    /** Cloud API (Google Vision/AWS Textract) availability */
    cloudApi: boolean;
  };
}

/**
 * Custom exception for OCR service errors
 * 
 * Base exception class for all OCR service-related errors.
 * Provides context about the error including original error details.
 */
export class OcrServiceException extends HttpException {
  public readonly originalError?: any;
  public readonly timestamp: string;
  
  constructor(
    message: string,
    statusCode: number,
    originalError?: any,
  ) {
    super(
      {
        success: false,
        error: {
          code: 'OCR_SERVICE_ERROR',
          message,
          details: originalError?.response?.data || originalError?.message,
        },
      },
      statusCode,
    );
    
    this.originalError = originalError;
    this.timestamp = new Date().toISOString();
    this.name = 'OcrServiceException';
    
    // Maintain proper stack trace
    if (Error.captureStackTrace) {
      Error.captureStackTrace(this, this.constructor);
    }
  }
}

/**
 * Exception thrown when OCR service request times out
 * 
 * Indicates that the OCR processing exceeded the 60-second timeout limit.
 * This typically happens with very large documents or when the service is overloaded.
 */
export class OcrTimeoutException extends OcrServiceException {
  constructor(message: string = 'OCR service request timed out after 60 seconds', originalError?: any) {
    super(message, HttpStatus.GATEWAY_TIMEOUT, originalError);
    this.name = 'OcrTimeoutException';
  }
}

/**
 * Exception thrown when unable to connect to OCR service
 * 
 * Indicates that the OCR service is unreachable, down, or network connectivity issues exist.
 */
export class OcrConnectionException extends OcrServiceException {
  constructor(message: string = 'Unable to connect to OCR service', originalError?: any) {
    super(message, HttpStatus.SERVICE_UNAVAILABLE, originalError);
    this.name = 'OcrConnectionException';
  }
}

/**
 * Metrics tracking for OCR client operations
 */
interface OcrClientMetrics {
  totalRequests: number;
  successfulRequests: number;
  failedRequests: number;
  totalRetries: number;
  averageResponseTime: number;
  lastRequestTimestamp: string | null;
}

/**
 * OCR Client Service
 * 
 * HTTP client for communicating with the Python FastAPI OCR processing service.
 * Provides methods for document processing and health checks with automatic retry logic,
 * comprehensive error handling, and request/response logging.
 * 
 * Features:
 * - Automatic retry with exponential backoff (3 attempts)
 * - 60-second timeout for document processing
 * - Request/response logging and metrics
 * - Custom exception handling
 * - Health monitoring
 * 
 * @example
 * ```typescript
 * const ocrClient = new OcrClient(configService);
 * 
 * const response = await ocrClient.processDocument({
 *   documentId: '123e4567-e89b-12d3-a456-426614174000',
 *   accountId: 'acc_123',
 *   fileUrl: 'https://s3.amazonaws.com/bucket/document.pdf',
 *   fileName: 'invoice.pdf',
 *   mimeType: 'application/pdf',
 * });
 * 
 * console.log(`Extracted text: ${response.extractedText}`);
 * console.log(`Confidence: ${response.confidence}%`);
 * ```
 */
@Injectable()
export class OcrClient {
  private readonly logger = new Logger(OcrClient.name);
  private readonly axiosInstance: AxiosInstance;
  private readonly ocrServiceUrl: string;
  private readonly metrics: OcrClientMetrics = {
    totalRequests: 0,
    successfulRequests: 0,
    failedRequests: 0,
    totalRetries: 0,
    averageResponseTime: 0,
    lastRequestTimestamp: null,
  };
  
  /**
   * Constructor with dependency injection
   * 
   * @param configService - NestJS ConfigService for environment variables
   */
  constructor(private readonly configService: ConfigService) {
    this.ocrServiceUrl = this.configService.get<string>(
      'OCR_SERVICE_URL',
      'http://ocr-service:8000',
    );
    
    if (!this.ocrServiceUrl) {
      throw new Error('OCR_SERVICE_URL must be configured');
    }
    
    this.logger.log(`Initializing OCR client with service URL: ${this.ocrServiceUrl}`);
    this.axiosInstance = this.initializeAxiosInstance();
  }
  
  /**
   * Initialize Axios instance with interceptors and configuration
   * 
   * @returns Configured Axios instance
   * @private
   */
  private initializeAxiosInstance(): AxiosInstance {
    const instance = axios.create({
      baseURL: this.ocrServiceUrl,
      timeout: 60000, // 60 seconds per requirements
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
      },
    });
    
    // Request interceptor for logging and headers
    instance.interceptors.request.use(
      (config) => {
        const requestId = uuidv4();
        config.headers['X-Request-ID'] = requestId;
        
        // Add account ID if available in request data
        if (config.data?.accountId) {
          config.headers['X-Account-ID'] = config.data.accountId;
        }
        
        this.logger.debug(
          `Outgoing OCR request: ${config.method?.toUpperCase()} ${config.url} [Request-ID: ${requestId}]`,
        );
        
        // Store request start time for metrics
        (config as any).metadata = { startTime: Date.now() };
        
        return config;
      },
      (error) => {
        this.logger.error('Request interceptor error', error);
        return Promise.reject(error);
      },
    );
    
    // Response interceptor for logging
    instance.interceptors.response.use(
      (response) => {
        const duration = Date.now() - (response.config as any).metadata?.startTime;
        
        this.logger.debug(
          `OCR response received: ${response.status} ${response.config.url} (${duration}ms)`,
        );
        
        // Update metrics
        this.updateMetrics(true, duration);
        
        return response.data;
      },
      (error) => {
        const duration = Date.now() - (error.config)?.metadata?.startTime;
        
        this.logger.error(
          `OCR request failed: ${error.response?.status || 'NO_RESPONSE'} ${error.config?.url} (${duration}ms) - ${error.message}`,
        );
        
        // Update metrics
        this.updateMetrics(false, duration);
        
        return Promise.reject(error);
      },
    );
    
    return instance;
  }
  
  /**
   * Process a document using the OCR service
   * 
   * Sends a document to the OCR service for text extraction and optional field extraction.
   * Implements automatic retry logic for transient failures with exponential backoff.
   * 
   * @param request - OCR processing request with document details
   * @returns OCR processing response with extracted text and metadata
   * @throws {OcrTimeoutException} When request exceeds 60-second timeout
   * @throws {OcrConnectionException} When unable to connect to OCR service
   * @throws {OcrServiceException} For other OCR service errors
   * 
   * @example
   * ```typescript
   * const response = await ocrClient.processDocument({
   *   documentId: '123e4567-e89b-12d3-a456-426614174000',
   *   accountId: 'acc_123',
   *   fileUrl: 'https://s3.amazonaws.com/bucket/invoice.pdf',
   *   fileName: 'invoice.pdf',
   *   mimeType: 'application/pdf',
   *   options: { useCloudApi: false, language: 'eng', dpi: 300 }
   * });
   * ```
   */
  public async processDocument(
    request: OcrProcessRequest,
  ): Promise<OcrProcessResponse> {
    // Validate required fields
    this.validateOcrRequest(request);
    
    this.logger.log(
      `Processing document ${request.documentId} for account ${request.accountId} (${request.fileName})`,
    );
    
    try {
      const response = await this.makeRequestWithRetry<OcrProcessResponse>({
        method: 'POST',
        url: '/api/v1/ocr/process',
        data: request,
      }, 3);
      
      // Validate response structure
      this.validateOcrResponse(response);
      
      this.logger.log(
        `Document ${request.documentId} processed successfully: ${response.pageCount} pages, ${response.confidence}% confidence, ${response.extractedText.length} characters`,
      );
      
      return response;
    } catch (error) {
      const axiosError = error as AxiosError;
      
      // Transform to specific exception types
      if (axiosError.code === 'ECONNABORTED' || axiosError.message?.includes('timeout')) {
        throw new OcrTimeoutException(
          `OCR processing timed out for document ${request.documentId}`,
          axiosError,
        );
      }
      
      if (
        axiosError.code === 'ECONNREFUSED' ||
        axiosError.code === 'ENOTFOUND' ||
        axiosError.code === 'ETIMEDOUT'
      ) {
        throw new OcrConnectionException(
          `Unable to reach OCR service for document ${request.documentId}`,
          axiosError,
        );
      }
      
      // Generic OCR service error
      throw this.transformAxiosError(axiosError, request.documentId, request.accountId);
    }
  }
  
  /**
   * Check health status of the OCR service
   * 
   * Performs a health check to verify the OCR service is running and responsive.
   * Returns degraded status instead of throwing on failures to allow graceful handling.
   * 
   * @returns Health check response with service status
   * 
   * @example
   * ```typescript
   * const health = await ocrClient.checkHealth();
   * if (health.status === 'ok') {
   *   console.log('OCR service is healthy');
   * }
   * ```
   */
  public async checkHealth(): Promise<HealthCheckResponse> {
    this.logger.debug('Performing OCR service health check');
    
    try {
      const response = await this.makeRequestWithRetry<HealthCheckResponse>({
        method: 'GET',
        url: '/api/v1/ocr/health',
      }, 1); // Only retry once for health checks
      
      this.logger.debug(`OCR service health status: ${response.status}`);
      
      return response;
    } catch (error) {
      this.logger.warn('OCR service health check failed, returning degraded status', error);
      
      // Return degraded status instead of throwing
      return {
        status: 'down',
        version: 'unknown',
        timestamp: new Date().toISOString(),
        services: {
          tesseract: false,
          cloudApi: false,
        },
      };
    }
  }
  
  /**
   * Get client metrics for monitoring
   * 
   * Returns operational metrics for the OCR client including request counts,
   * success/failure rates, and performance statistics.
   * 
   * @returns Current client metrics
   * 
   * @example
   * ```typescript
   * const metrics = ocrClient.getMetrics();
   * console.log(`Success rate: ${metrics.successfulRequests / metrics.totalRequests * 100}%`);
   * ```
   */
  public getMetrics(): Readonly<OcrClientMetrics> {
    return { ...this.metrics };
  }
  
  /**
   * Make HTTP request with automatic retry logic
   * 
   * Implements exponential backoff retry strategy for transient failures.
   * Retries network errors, timeouts, and 5xx status codes up to the specified limit.
   * Does not retry 4xx client errors.
   * 
   * @param config - Axios request configuration
   * @param retries - Maximum number of retry attempts (default: 3)
   * @returns Response data
   * @throws Last error if all retries are exhausted
   * @private
   */
  private async makeRequestWithRetry<T>(
    config: AxiosRequestConfig,
    retries: number = 3,
  ): Promise<T> {
    let attempt = 0;
    let lastError: any = null;
    
    this.metrics.totalRequests++;
    
    while (attempt < retries) {
      attempt++;
      
      try {
        this.logger.debug(
          `OCR request attempt ${attempt}/${retries}: ${config.method?.toUpperCase()} ${config.url}`,
        );
        
        const response = await this.axiosInstance.request<any, T>(config);
        
        if (attempt > 1) {
          this.metrics.totalRetries += (attempt - 1);
        }
        
        return response;
      } catch (error) {
        lastError = error;
        const axiosError = error as AxiosError;
        
        // Check if error is retryable
        const isRetryable = this.isRetryableError(axiosError);
        const attemptsRemaining = retries - attempt;
        
        if (!isRetryable) {
          this.logger.warn(
            `Non-retryable error encountered: ${axiosError.response?.status || axiosError.code}`,
          );
          break;
        }
        
        if (attemptsRemaining === 0) {
          this.logger.error(
            `All ${retries} retry attempts exhausted for ${config.method} ${config.url}`,
          );
          break;
        }
        
        // Calculate backoff delay
        const delay = this.calculateBackoffDelay(attempt);
        
        this.logger.warn(
          `Request failed (attempt ${attempt}/${retries}), retrying in ${delay}ms: ${axiosError.message}`,
        );
        
        // Wait before retrying
        await this.sleep(delay);
      }
    }
    
    // All retries exhausted or non-retryable error
    if (attempt > 1) {
      this.metrics.totalRetries += (attempt - 1);
    }
    
    throw lastError;
  }
  
  /**
   * Calculate exponential backoff delay
   * 
   * Uses exponential backoff formula with a maximum cap:
   * - Attempt 1: ~1 second
   * - Attempt 2: ~2 seconds
   * - Attempt 3: ~4 seconds
   * - Maximum: 10 seconds
   * 
   * @param attempt - Current attempt number (1-based)
   * @returns Delay in milliseconds
   * @private
   */
  private calculateBackoffDelay(attempt: number): number {
    const baseDelay = 1000; // 1 second
    const maxDelay = 10000; // 10 seconds
    const exponentialDelay = baseDelay * Math.pow(2, attempt - 1);
    
    return Math.min(exponentialDelay, maxDelay);
  }
  
  /**
   * Determine if an error is retryable
   * 
   * Retryable errors include:
   * - Network errors (ECONNREFUSED, ETIMEDOUT, ENOTFOUND)
   * - Timeout errors (ECONNABORTED)
   * - 5xx server errors (500-599)
   * 
   * Non-retryable errors include:
   * - 4xx client errors (400-499)
   * 
   * @param error - Axios error object
   * @returns True if error should be retried
   * @private
   */
  private isRetryableError(error: AxiosError): boolean {
    // Network errors are retryable
    if (error.code === 'ECONNREFUSED' || 
        error.code === 'ETIMEDOUT' || 
        error.code === 'ENOTFOUND' ||
        error.code === 'ECONNABORTED') {
      return true;
    }
    
    // 5xx server errors are retryable
    const status = error.response?.status;
    if (status && status >= 500 && status < 600) {
      return true;
    }
    
    // 4xx client errors are not retryable
    if (status && status >= 400 && status < 500) {
      return false;
    }
    
    // Default to not retryable for unknown errors
    return false;
  }
  
  /**
   * Transform Axios error to custom OCR exception
   * 
   * @param error - Axios error object
   * @param documentId - Document identifier for context
   * @param accountId - Account identifier for context
   * @returns Transformed custom exception
   * @private
   */
  private transformAxiosError(
    error: AxiosError,
    documentId: string,
    accountId: string,
  ): OcrServiceException {
    const status = error.response?.status || HttpStatus.BAD_GATEWAY;
    const responseData = error.response?.data as any;
    const message = responseData?.error?.message || 
                    error.message || 
                    'Unknown OCR service error';
    
    const contextualMessage = `OCR service error for document ${documentId} (account: ${accountId}): ${message}`;
    
    return new OcrServiceException(contextualMessage, status, error);
  }
  
  /**
   * Validate OCR request has required fields
   * 
   * @param request - OCR request to validate
   * @throws {OcrServiceException} If required fields are missing
   * @private
   */
  private validateOcrRequest(request: OcrProcessRequest): void {
    const requiredFields: (keyof OcrProcessRequest)[] = [
      'documentId',
      'accountId',
      'fileUrl',
      'fileName',
      'mimeType',
    ];
    
    const missingFields = requiredFields.filter(field => !request[field]);
    
    if (missingFields.length > 0) {
      throw new OcrServiceException(
        `Missing required fields in OCR request: ${missingFields.join(', ')}`,
        HttpStatus.BAD_REQUEST,
      );
    }
    
    // Validate URL format
    try {
      new URL(request.fileUrl);
    } catch {
      throw new OcrServiceException(
        `Invalid fileUrl format: ${request.fileUrl}`,
        HttpStatus.BAD_REQUEST,
      );
    }
  }
  
  /**
   * Validate OCR response has expected structure
   * 
   * @param response - OCR response to validate
   * @throws {OcrServiceException} If response structure is invalid
   * @private
   */
  private validateOcrResponse(response: OcrProcessResponse): void {
    if (!response || typeof response !== 'object') {
      throw new OcrServiceException(
        'Invalid OCR response: response is not an object',
        HttpStatus.INTERNAL_SERVER_ERROR,
      );
    }
    
    if (typeof response.success !== 'boolean') {
      throw new OcrServiceException(
        'Invalid OCR response: missing or invalid success field',
        HttpStatus.INTERNAL_SERVER_ERROR,
      );
    }
    
    if (!response.success && !response.error) {
      throw new OcrServiceException(
        `OCR processing failed: ${  response.error || 'Unknown error'}`,
        HttpStatus.INTERNAL_SERVER_ERROR,
      );
    }
    
    const requiredFields = ['jobId', 'documentId', 'extractedText', 'confidence', 'pageCount', 'metadata'];
    const missingFields = requiredFields.filter(field => !(field in response));
    
    if (missingFields.length > 0) {
      throw new OcrServiceException(
        `Invalid OCR response: missing fields ${missingFields.join(', ')}`,
        HttpStatus.INTERNAL_SERVER_ERROR,
      );
    }
  }
  
  /**
   * Update client metrics
   * 
   * @param success - Whether the request was successful
   * @param duration - Request duration in milliseconds
   * @private
   */
  private updateMetrics(success: boolean, duration: number): void {
    if (success) {
      this.metrics.successfulRequests++;
    } else {
      this.metrics.failedRequests++;
    }
    
    // Update average response time
    const totalRequests = this.metrics.successfulRequests + this.metrics.failedRequests;
    const currentAverage = this.metrics.averageResponseTime;
    this.metrics.averageResponseTime = 
      ((currentAverage * (totalRequests - 1)) + duration) / totalRequests;
    
    this.metrics.lastRequestTimestamp = new Date().toISOString();
  }
  
  /**
   * Sleep for specified duration
   * 
   * @param ms - Duration in milliseconds
   * @returns Promise that resolves after the delay
   * @private
   */
  private sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}
