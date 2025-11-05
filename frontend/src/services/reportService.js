/**
 * Report Service
 * 
 * Service module for report generation operations in CardDemo application.
 * Provides API communication for monthly, yearly, and custom date range transaction reports.
 * 
 * Transformation Context:
 * Maps COBOL program CORPT00C.cbl report generation logic to REST API calls,
 * transforming mainframe batch report submission into modern asynchronous API patterns.
 * 
 * Key Features:
 * - Monthly report generation (current month)
 * - Yearly report generation (current year)
 * - Custom date range report generation
 * - Date validation matching COBOL validation rules
 * - Error handling with user-friendly messages
 * 
 * COBOL Equivalence:
 * - CORPT00C.cbl PROCESS-ENTER-KEY paragraph → generateMonthlyReport/generateYearlyReport/generateCustomReport
 * - CORPT00C.cbl SUBMIT-JOB-TO-INTRDR paragraph → API submission logic
 * - Date validation → validateDateRange function
 * 
 * @module services/reportService
 */

import apiClient from './apiClient';

/**
 * Generate Monthly Report
 * 
 * Submits request for monthly transaction report covering the current month.
 * Maps to COBOL CORPT00C.cbl lines 213-238 (WHEN MONTHLYI processing).
 * 
 * @returns {Promise<Object>} Promise resolving to report submission confirmation
 * @throws {Error} If report submission fails
 * 
 * @example
 * try {
 *   const result = await reportService.generateMonthlyReport();
 *   console.log('Monthly report submitted:', result);
 * } catch (error) {
 *   console.error('Failed to generate monthly report:', error.message);
 * }
 */
const generateMonthlyReport = async () => {
  try {
    // Calculate current month start and end dates
    const now = new Date();
    const year = now.getFullYear();
    const month = now.getMonth() + 1; // JavaScript months are 0-indexed
    
    // Format dates as YYYY-MM-DD (ISO 8601 format)
    const startDate = `${year}-${String(month).padStart(2, '0')}-01`;
    
    // Calculate last day of current month
    const lastDay = new Date(year, month, 0).getDate();
    const endDate = `${year}-${String(month).padStart(2, '0')}-${String(lastDay).padStart(2, '0')}`;
    
    // Submit report generation request to backend
    // Maps to COBOL EXEC CICS LINK to batch job submission (lines 498-510)
    const response = await apiClient.post('/reports/monthly', {
      reportType: 'MONTHLY',
      startDate,
      endDate,
      generatedDate: new Date().toISOString()
    });
    
    return response.data;
  } catch (error) {
    // Transform backend error to user-friendly message
    // Maps COBOL error handling at lines 513-527
    throw new Error(error.message || 'Unable to submit monthly report for generation');
  }
};

/**
 * Generate Yearly Report
 * 
 * Submits request for yearly transaction report covering the current year.
 * Maps to COBOL CORPT00C.cbl lines 239-255 (WHEN YEARLYI processing).
 * 
 * @returns {Promise<Object>} Promise resolving to report submission confirmation
 * @throws {Error} If report submission fails
 * 
 * @example
 * try {
 *   const result = await reportService.generateYearlyReport();
 *   console.log('Yearly report submitted:', result);
 * } catch (error) {
 *   console.error('Failed to generate yearly report:', error.message);
 * }
 */
const generateYearlyReport = async () => {
  try {
    // Calculate current year start and end dates
    const now = new Date();
    const year = now.getFullYear();
    
    // Format dates as YYYY-MM-DD (ISO 8601 format)
    const startDate = `${year}-01-01`;
    const endDate = `${year}-12-31`;
    
    // Submit report generation request to backend
    // Maps to COBOL EXEC CICS LINK to batch job submission (lines 498-510)
    const response = await apiClient.post('/reports/yearly', {
      reportType: 'YEARLY',
      startDate,
      endDate,
      generatedDate: new Date().toISOString()
    });
    
    return response.data;
  } catch (error) {
    // Transform backend error to user-friendly message
    throw new Error(error.message || 'Unable to submit yearly report for generation');
  }
};

/**
 * Generate Custom Date Range Report
 * 
 * Submits request for transaction report covering custom date range specified by user.
 * Maps to COBOL CORPT00C.cbl lines 256-436 (WHEN CUSTOMI processing).
 * 
 * @param {string} startDate - Report start date in YYYY-MM-DD format
 * @param {string} endDate - Report end date in YYYY-MM-DD format
 * @returns {Promise<Object>} Promise resolving to report submission confirmation
 * @throws {Error} If date validation fails or report submission fails
 * 
 * @example
 * try {
 *   const result = await reportService.generateCustomReport('2024-01-01', '2024-03-31');
 *   console.log('Custom report submitted:', result);
 * } catch (error) {
 *   console.error('Failed to generate custom report:', error.message);
 * }
 */
const generateCustomReport = async (startDate, endDate) => {
  try {
    // Validate date range
    // Maps to COBOL date validation at lines 305-427
    const validation = validateDateRange(startDate, endDate);
    if (!validation.isValid) {
      throw new Error(validation.error);
    }
    
    // Submit report generation request to backend
    // Maps to COBOL EXEC CICS LINK to batch job submission (lines 498-510)
    const response = await apiClient.post('/reports/custom', {
      reportType: 'CUSTOM',
      startDate,
      endDate,
      generatedDate: new Date().toISOString()
    });
    
    return response.data;
  } catch (error) {
    // Transform backend error to user-friendly message
    throw new Error(error.message || 'Unable to submit custom report for generation');
  }
};

/**
 * Validate Date Range
 * 
 * Validates custom report date range ensuring start date is before end date
 * and both dates are valid calendar dates.
 * 
 * Maps to COBOL date validation logic at lines 305-427 in CORPT00C.cbl,
 * including CSUTLDTC call validation.
 * 
 * @param {string} startDate - Start date in YYYY-MM-DD format
 * @param {string} endDate - End date in YYYY-MM-DD format
 * @returns {Object} Validation result with isValid flag and error message
 * 
 * @example
 * const validation = validateDateRange('2024-01-01', '2024-12-31');
 * if (!validation.isValid) {
 *   console.error(validation.error);
 * }
 */
const validateDateRange = (startDate, endDate) => {
  // Validate start date is provided
  if (!startDate || startDate.trim() === '') {
    return {
      isValid: false,
      error: 'Start Date is required for custom reports...'
    };
  }
  
  // Validate end date is provided
  if (!endDate || endDate.trim() === '') {
    return {
      isValid: false,
      error: 'End Date is required for custom reports...'
    };
  }
  
  // Parse dates
  const start = new Date(startDate);
  const end = new Date(endDate);
  
  // Validate start date is a valid date
  // Maps to COBOL date validation at lines 329-353
  if (isNaN(start.getTime())) {
    return {
      isValid: false,
      error: 'Start Date - Not a valid date...'
    };
  }
  
  // Validate end date is a valid date
  // Maps to COBOL date validation at lines 355-379
  if (isNaN(end.getTime())) {
    return {
      isValid: false,
      error: 'End Date - Not a valid date...'
    };
  }
  
  // Validate start date is before or equal to end date
  // Maps to COBOL date comparison at lines 388-427
  if (start > end) {
    return {
      isValid: false,
      error: 'Start Date must be before or equal to End Date...'
    };
  }
  
  // Validate date range is not in the future
  const now = new Date();
  now.setHours(23, 59, 59, 999); // End of today
  
  if (start > now) {
    return {
      isValid: false,
      error: 'Start Date cannot be in the future...'
    };
  }
  
  if (end > now) {
    return {
      isValid: false,
      error: 'End Date cannot be in the future...'
    };
  }
  
  // All validations passed
  return {
    isValid: true
  };
};

/**
 * Report Service Export
 * 
 * Exports all report generation functions for use by ReportMenuComponent
 * and other components requiring report functionality.
 * 
 * Exported Members:
 * - generateMonthlyReport: Generate report for current month
 * - generateYearlyReport: Generate report for current year
 * - generateCustomReport: Generate report for custom date range
 * - validateDateRange: Validate custom date range (utility function)
 * 
 * Usage Pattern:
 * Import this service in React components to trigger backend report generation
 * with validation and error handling consistent with COBOL CORPT00C.cbl logic.
 */
export default {
  generateMonthlyReport,
  generateYearlyReport,
  generateCustomReport,
  validateDateRange
};
