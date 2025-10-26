/**
 * Report Generation Service Module
 * 
 * Converted from: COBOL program CORPT00C.cbl
 * Original function: Print transaction reports by submitting batch job from online using extra partition TDQ
 * 
 * Purpose: Provides API calls for business reporting operations including report menu,
 *          report generation with date parameters, and file download capabilities
 * 
 * Key Features:
 * - Report menu retrieval for displaying available report options
 * - Report generation with date range validation and multiple export formats
 * - Binary file download handling for PDF, CSV, and Excel reports
 * - Progress tracking for long-running report generation jobs
 * - Report caching to avoid regenerating identical reports
 * 
 * Conversion notes:
 * - COBOL report menu (MONTHLYI/YEARLYI/CUSTOMI) → REST API endpoints
 * - COBOL date validation logic (lines 259-427) → Client-side validation
 * - COBOL JCL submission (lines 462-510) → REST API POST request
 * - COBOL confirmation flow (lines 464-494) → Frontend confirmation dialog
 * - Fixed-format printed reports → Multiple digital formats (PDF/CSV/Excel)
 * 
 * @module services/reportService
 */

import api from './api';
import {
  ReportMenuItem,
  ReportGenerationRequest,
  ReportGenerationResponse,
  ReportFormat,
  ReportType,
  DEFAULT_REPORT_MENU_ITEMS
} from '../types/report';

/**
 * Retrieve Report Menu Options
 * 
 * Converted from: COBOL CORPT00C.cbl PROCESS-ENTER-KEY paragraph (lines 208-456)
 * Original COBOL logic: Displays menu with MONTHLYI, YEARLYI, CUSTOMI options
 * 
 * Fetches list of available report types and their configuration from backend.
 * This endpoint provides metadata about report options including whether
 * custom date ranges are required and report descriptions.
 * 
 * COBOL mapping:
 * - MONTHLYI option (lines 213-238) → MONTHLY report type
 * - YEARLYI option (lines 239-255) → YEARLY report type
 * - CUSTOMI option (lines 256-436) → CUSTOM report type
 * 
 * Backend endpoint: GET /api/reports/menu
 * 
 * @returns Promise resolving to array of ReportMenuItem objects
 * @throws ApiError if request fails or server returns error
 * 
 * @example
 * ```typescript
 * const menuItems = await getReportMenu();
 * menuItems.forEach(item => {
 *   console.log(`${item.label}: ${item.description}`);
 *   console.log(`Requires date range: ${item.requiresDateRange}`);
 * });
 * ```
 */
export const getReportMenu = async (): Promise<ReportMenuItem[]> => {
  try {
    // Call backend endpoint to fetch report menu configuration
    // Backend may provide dynamic menu items based on user permissions
    const response = await api.get<ReportMenuItem[]>('/reports/menu');
    
    // Return menu items from response data
    // If backend returns empty array, fall back to default menu items
    if (response.data && response.data.length > 0) {
      return response.data;
    } else {
      // Fallback to default menu items if backend returns empty
      return DEFAULT_REPORT_MENU_ITEMS;
    }
  } catch (error) {
    // Log error for debugging
    console.error('[Report Service] Failed to fetch report menu:', error);
    
    // Fallback to default menu items if API call fails
    // This ensures the UI can still function with static report options
    return DEFAULT_REPORT_MENU_ITEMS;
  }
};

/**
 * Generate Report with Parameters
 * 
 * Converted from: COBOL CORPT00C.cbl SUBMIT-JOB-TO-INTRDR paragraph (lines 462-510)
 * Original COBOL logic: Submits JCL batch job with date parameters via TDQ
 * 
 * Submits report generation request to backend with specified parameters.
 * Handles all three report types from COBOL:
 * 1. Monthly: Auto-calculates dates for current month
 * 2. Yearly: Auto-calculates dates for current year
 * 3. Custom: Uses user-provided date range with validation
 * 
 * Date validation rules from COBOL (lines 259-427):
 * - Custom reports require both startDate and endDate
 * - Start date must be before or equal to end date
 * - Month must be 1-12 (COBOL lines 329-336, 355-362)
 * - Day must be 1-31 (COBOL lines 338-345, 364-371)
 * - Year must be numeric (COBOL lines 347-353, 373-379)
 * - Dates validated by CSUTLDTC utility (COBOL lines 388-426)
 * 
 * Report processing flow:
 * 1. Validate request parameters
 * 2. Submit report generation job to backend
 * 3. Backend processes report asynchronously
 * 4. Return report ID and download URL
 * 5. Client can poll status or download when complete
 * 
 * Backend endpoint: POST /api/reports/generate
 * Request body: ReportGenerationRequest JSON
 * Response: ReportGenerationResponse with download URL
 * 
 * @param request - Report generation request parameters
 * @returns Promise resolving to ReportGenerationResponse with download URL
 * @throws ApiError if request fails, validation errors, or server error
 * 
 * @example
 * ```typescript
 * // Monthly report
 * const monthlyReport = await generateReport({
 *   reportType: ReportType.MONTHLY,
 *   format: ReportFormat.PDF
 * });
 * 
 * // Custom date range report
 * const customReport = await generateReport({
 *   reportType: ReportType.CUSTOM,
 *   startDate: '2024-01-01',
 *   endDate: '2024-01-31',
 *   accountId: 123456,
 *   format: ReportFormat.EXCEL
 * });
 * 
 * console.log(`Report ID: ${customReport.reportId}`);
 * console.log(`Download URL: ${customReport.downloadUrl}`);
 * console.log(`Status: ${customReport.status}`);
 * ```
 */
export const generateReport = async (
  request: ReportGenerationRequest
): Promise<ReportGenerationResponse> => {
  try {
    // Validate request parameters before sending to backend
    validateReportRequest(request);
    
    // Prepare request payload with defaults
    const requestPayload: ReportGenerationRequest = {
      reportType: request.reportType,
      startDate: request.startDate,
      endDate: request.endDate,
      accountId: request.accountId,
      format: request.format || ReportFormat.PDF // Default to PDF if not specified
    };
    
    // Submit report generation request to backend
    // Backend processes report asynchronously and returns job metadata
    const response = await api.post<ReportGenerationResponse>(
      '/reports/generate',
      requestPayload
    );
    
    // Return report generation response with download URL and metadata
    return response.data;
  } catch (error) {
    // Log error for debugging
    console.error('[Report Service] Failed to generate report:', error);
    
    // Re-throw error to be handled by caller
    throw error;
  }
};

/**
 * Download Generated Report File
 * 
 * Converted from: COBOL output file operations (no direct equivalent in CORPT00C.cbl)
 * Original COBOL logic: Writes report to sequential file or printer
 * Modern implementation: Downloads binary file (PDF/CSV/Excel) from backend
 * 
 * Downloads generated report file from backend using provided download URL.
 * Handles binary file download with proper Blob response type.
 * Supports multiple file formats (PDF, CSV, Excel).
 * 
 * Download flow:
 * 1. Make GET request to download URL with responseType: 'blob'
 * 2. Create temporary download link using Blob URL
 * 3. Trigger browser download with suggested filename
 * 4. Clean up temporary Blob URL after download
 * 
 * This replaces COBOL sequential file output (COBOL lines 79, 515-535)
 * with modern browser-based file download.
 * 
 * @param downloadUrl - Report download URL from ReportGenerationResponse
 * @param fileName - Suggested filename for downloaded file (with extension)
 * @returns Promise resolving when download completes
 * @throws ApiError if download fails or file not found
 * 
 * @example
 * ```typescript
 * // After report generation completes
 * const reportResponse = await generateReport(request);
 * 
 * if (reportResponse.status === 'COMPLETED') {
 *   await downloadReport(
 *     reportResponse.downloadUrl,
 *     reportResponse.fileName
 *   );
 * }
 * ```
 */
export const downloadReport = async (
  downloadUrl: string,
  fileName: string
): Promise<void> => {
  try {
    // Validate download URL and filename
    if (!downloadUrl || !downloadUrl.trim()) {
      throw new Error('Download URL is required');
    }
    if (!fileName || !fileName.trim()) {
      throw new Error('File name is required');
    }
    
    // Make GET request with responseType 'blob' for binary file download
    // This ensures response data is treated as binary file content
    const response = await api.get(downloadUrl, {
      responseType: 'blob' // Critical for binary file download
    });
    
    // Create Blob object from response data
    const blob = new Blob([response.data], {
      type: response.headers['content-type'] || 'application/octet-stream'
    });
    
    // Create temporary download link using Blob URL
    const blobUrl = window.URL.createObjectURL(blob);
    
    // Create temporary anchor element for download
    const link = document.createElement('a');
    link.href = blobUrl;
    link.download = fileName; // Set suggested filename
    link.style.display = 'none'; // Hide link element
    
    // Append link to DOM (required for Firefox)
    document.body.appendChild(link);
    
    // Trigger download by programmatically clicking the link
    link.click();
    
    // Clean up: Remove link from DOM
    document.body.removeChild(link);
    
    // Clean up: Revoke Blob URL to free memory
    // Delay slightly to ensure download starts before URL is revoked
    setTimeout(() => {
      window.URL.revokeObjectURL(blobUrl);
    }, 100);
    
  } catch (error) {
    // Log error for debugging
    console.error('[Report Service] Failed to download report:', error);
    
    // Re-throw error to be handled by caller
    throw error;
  }
};

/**
 * Validate Report Generation Request
 * 
 * Converted from: COBOL date validation logic in CORPT00C.cbl (lines 259-427)
 * Original COBOL logic: Field-level validation with specific error messages
 * 
 * Validates report request parameters before sending to backend.
 * Replicates COBOL validation rules from CORPT00C.cbl:
 * 
 * COBOL validation mapping:
 * - Empty month check (lines 259-265) → startDate/endDate presence validation
 * - Empty day check (lines 266-272) → startDate/endDate presence validation
 * - Empty year check (lines 273-279) → startDate/endDate presence validation
 * - Month range 1-12 (lines 329-336, 355-362) → Date format validation
 * - Day range 1-31 (lines 338-345, 364-371) → Date format validation
 * - Numeric year check (lines 347-353, 373-379) → Date format validation
 * - CSUTLDTC date utility validation (lines 388-426) → Date parsing validation
 * 
 * @param request - Report generation request to validate
 * @throws Error with descriptive message if validation fails
 * 
 * @private
 */
const validateReportRequest = (request: ReportGenerationRequest): void => {
  // Validate report type is provided
  if (!request.reportType) {
    throw new Error('Report type is required');
  }
  
  // Validate report type is a valid enum value
  if (!Object.values(ReportType).includes(request.reportType)) {
    throw new Error(`Invalid report type: ${request.reportType}`);
  }
  
  // Custom reports require date range validation (COBOL lines 256-436)
  if (request.reportType === ReportType.CUSTOM) {
    // Validate start date is provided (COBOL lines 259-279)
    if (!request.startDate || !request.startDate.trim()) {
      throw new Error('Start date is required for custom reports');
    }
    
    // Validate end date is provided (COBOL lines 280-300)
    if (!request.endDate || !request.endDate.trim()) {
      throw new Error('End date is required for custom reports');
    }
    
    // Validate date format and range
    validateDateFormat(request.startDate, 'Start date');
    validateDateFormat(request.endDate, 'End date');
    
    // Validate start date is before or equal to end date
    const startDate = new Date(request.startDate);
    const endDate = new Date(request.endDate);
    
    if (startDate > endDate) {
      throw new Error('Start date must be before or equal to end date');
    }
  }
  
  // Validate account ID if provided
  if (request.accountId !== undefined && request.accountId !== null) {
    if (request.accountId <= 0) {
      throw new Error('Account ID must be a positive number');
    }
  }
  
  // Validate format if provided
  if (request.format !== undefined && request.format !== null) {
    if (!Object.values(ReportFormat).includes(request.format)) {
      throw new Error(`Invalid report format: ${request.format}`);
    }
  }
};

/**
 * Validate Date Format
 * 
 * Converted from: COBOL date validation in CORPT00C.cbl (lines 305-379, 388-426)
 * Original COBOL logic: Numeric validation and range checks for month, day, year
 * 
 * Validates date string is in YYYY-MM-DD format and represents a valid date.
 * Replicates COBOL date validation rules:
 * 
 * COBOL validation mapping:
 * - Month 1-12 (lines 329-336, 355-362)
 * - Day 1-31 (lines 338-345, 364-371)
 * - Numeric year (lines 347-353, 373-379)
 * - CSUTLDTC utility call (lines 388-426) → JavaScript Date validation
 * 
 * @param dateString - Date string in YYYY-MM-DD format
 * @param fieldName - Field name for error messages (e.g., "Start date")
 * @throws Error with descriptive message if validation fails
 * 
 * @private
 */
const validateDateFormat = (dateString: string, fieldName: string): void => {
  // Validate date format matches YYYY-MM-DD pattern (COBOL WS-DATE-FORMAT line 72)
  const dateFormatPattern = /^\d{4}-\d{2}-\d{2}$/;
  if (!dateFormatPattern.test(dateString)) {
    throw new Error(`${fieldName} must be in YYYY-MM-DD format`);
  }
  
  // Parse date components
  const dateParts = dateString.split('-');
  
  // Validate we have exactly 3 parts (year, month, day)
  if (dateParts.length !== 3) {
    throw new Error(`${fieldName} must be in YYYY-MM-DD format`);
  }
  
  // Convert to numbers and validate they are valid numbers
  const year = Number(dateParts[0]);
  const month = Number(dateParts[1]);
  const day = Number(dateParts[2]);
  
  // Validate all parts are valid numbers
  if (isNaN(year) || isNaN(month) || isNaN(day)) {
    throw new Error(`${fieldName} must contain valid numeric date components`);
  }
  
  // Validate month range 1-12 (COBOL lines 329-336, 355-362)
  if (month < 1 || month > 12) {
    throw new Error(`${fieldName} - Month must be between 1 and 12`);
  }
  
  // Validate day range 1-31 (COBOL lines 338-345, 364-371)
  if (day < 1 || day > 31) {
    throw new Error(`${fieldName} - Day must be between 1 and 31`);
  }
  
  // Validate year is reasonable (COBOL lines 347-353, 373-379)
  if (year < 1900 || year > 2100) {
    throw new Error(`${fieldName} - Year must be between 1900 and 2100`);
  }
  
  // Validate date is valid using JavaScript Date object
  // This replicates CSUTLDTC utility validation (COBOL lines 388-426)
  const date = new Date(dateString);
  
  // Check if date is valid (invalid dates become "Invalid Date")
  if (isNaN(date.getTime())) {
    throw new Error(`${fieldName} - Not a valid date`);
  }
  
  // Verify date components match input (handles invalid dates like Feb 31)
  const parsedYear = date.getFullYear();
  const parsedMonth = date.getMonth() + 1; // JavaScript months are 0-indexed
  const parsedDay = date.getDate();
  
  if (parsedYear !== year || parsedMonth !== month || parsedDay !== day) {
    throw new Error(`${fieldName} - Not a valid date (e.g., month/day combination doesn't exist)`);
  }
};

/**
 * Check Report Generation Status
 * 
 * Additional functionality not present in COBOL CORPT00C.cbl
 * Modern enhancement: Polls report generation status for long-running reports
 * 
 * Queries backend for current status of report generation job.
 * Used for progress tracking when reports take significant time to generate.
 * 
 * Backend endpoint: GET /api/reports/status/{reportId}
 * 
 * @param reportId - Report ID from ReportGenerationResponse
 * @returns Promise resolving to updated ReportGenerationResponse with current status
 * @throws ApiError if status check fails or report not found
 * 
 * @example
 * ```typescript
 * const reportResponse = await generateReport(request);
 * 
 * // Poll status every 2 seconds until complete
 * const interval = setInterval(async () => {
 *   const status = await checkReportStatus(reportResponse.reportId);
 *   
 *   if (status.status === 'COMPLETED') {
 *     clearInterval(interval);
 *     await downloadReport(status.downloadUrl, status.fileName);
 *   } else if (status.status === 'FAILED') {
 *     clearInterval(interval);
 *     console.error('Report generation failed');
 *   }
 * }, 2000);
 * ```
 */
export const checkReportStatus = async (
  reportId: string
): Promise<ReportGenerationResponse> => {
  try {
    // Validate report ID
    if (!reportId || !reportId.trim()) {
      throw new Error('Report ID is required');
    }
    
    // Query backend for report status
    const response = await api.get<ReportGenerationResponse>(
      `/reports/status/${reportId}`
    );
    
    return response.data;
  } catch (error) {
    console.error('[Report Service] Failed to check report status:', error);
    throw error;
  }
};

/**
 * Report Service Object
 * 
 * Default export providing all report-related API operations.
 * Encapsulates report menu retrieval, report generation, and file download.
 * 
 * Exported as default for convenient import:
 * ```typescript
 * import reportService from './services/reportService';
 * 
 * const menuItems = await reportService.getReportMenu();
 * const report = await reportService.generateReport(request);
 * await reportService.downloadReport(report.downloadUrl, report.fileName);
 * ```
 * 
 * Also supports named imports for individual functions:
 * ```typescript
 * import { getReportMenu, generateReport, downloadReport } from './services/reportService';
 * ```
 */
const reportService = {
  /**
   * Retrieve available report menu options
   * @see getReportMenu
   */
  getReportMenu,
  
  /**
   * Generate report with specified parameters
   * @see generateReport
   */
  generateReport,
  
  /**
   * Download generated report file
   * @see downloadReport
   */
  downloadReport,
  
  /**
   * Check report generation status (bonus feature)
   * @see checkReportStatus
   */
  checkReportStatus
};

/**
 * Export report service as default export
 */
export default reportService;
