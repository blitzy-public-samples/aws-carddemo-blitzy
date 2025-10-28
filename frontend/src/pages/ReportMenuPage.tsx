/**
 * Report Menu Page Component
 * 
 * Converted from BMS map: CORPT00.bms (3270 terminal screen 24x80)
 * Original COBOL program: CORPT00C.cbl (transaction report selection)
 * Original function: Submit batch job to generate transaction reports
 * 
 * BMS Screen Layout (CORPT00.bms):
 * Line 1-2: Header (transaction name, program name, date, time)
 * Line 4: Screen title "Transaction Reports"
 * Line 7: MONTHLY option with checkbox (POS=(7,10))
 * Line 9: YEARLY option with checkbox (POS=(9,10))
 * Line 11: CUSTOM option with checkbox (POS=(11,10))
 * Line 13: Start Date label and input fields SDTMM/SDTDD/SDTYYYY (POS=(13,15))
 * Line 14: End Date label and input fields EDTMM/EDTDD/EDTYYYY (POS=(14,15))
 * Line 19: Confirmation prompt and CONFIRM field (Y/N) (POS=(19,6))
 * Line 23: ERRMSG field for error display (POS=(23,1), LENGTH=78)
 * Line 24: Function keys "ENTER=Continue  F3=Back"
 * 
 * React Implementation:
 * - Material-UI RadioGroup for report type selection (replaces BMS checkboxes)
 * - Separate TextField components for MM/DD/YYYY date input (replaces BMS SDTMM/SDTDD/SDTYYYY)
 * - TextField for confirmation Y/N (replaces BMS CONFIRM field)
 * - ErrorMessage component for validation errors (replaces BMS ERRMSG)
 * - Formik for form state management
 * - Yup schema for field validation with NUM attribute enforcement
 * - Conditional display of date fields based on Custom report selection
 * - REST API call to reportService.generateReport()
 * 
 * COBOL Validation Logic Preserved (CORPT00C.cbl):
 * - Empty field checks (lines 259-279): Requires month, day, year for custom reports
 * - Numeric validation (NUM attribute): Month, day, year must be numeric
 * - Month range 1-12 (lines 329-336, 355-362)
 * - Day range 1-31 (lines 338-345, 364-371)
 * - Year numeric check (lines 347-353, 373-379)
 * - CSUTLDTC date utility validation (lines 388-426): Valid date check
 * - Start date before end date validation
 * - Confirmation Y/N validation
 * 
 * Per Agent Action Plan Section 0.7.1 MINIMAL CHANGE CLAUSE:
 * This component preserves exact business logic from CORPT00C.cbl while
 * transforming BMS 3270 terminal screen to modern React web interface with
 * Material-UI components for responsive design.
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 * 
 * @see app/bms/CORPT00.bms - Original BMS map definition
 * @see app/cpy-bms/CORPT00.CPY - BMS-generated copybook
 * @see app/cbl/CORPT00C.cbl - Original COBOL program logic
 * @see frontend/src/services/reportService.ts - Report generation API
 * @see frontend/src/types/report.ts - TypeScript type definitions
 */

import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box,
  Container,
  Typography,
  Paper,
  RadioGroup,
  FormControlLabel,
  Radio,
  TextField,
  Button,
  Grid,
  FormControl,
  FormLabel,
  CircularProgress
} from '@mui/material';
import { useFormik } from 'formik';
import * as yup from 'yup';

// Internal imports from depends_on_files
import reportService from '../services/reportService';
import Header from '../components/common/Header';
import Footer from '../components/common/Footer';
import ErrorMessage from '../components/common/ErrorMessage';
import { useAuth } from '../hooks/useAuth';
import { formatDateForApi } from '../utils/dateFormatter';
import { validateDate } from '../utils/validation';
import { ReportGenerationRequest, ReportType } from '../types/report';

/**
 * Form values interface matching BMS map fields
 * CORPT00.bms field mapping:
 * - reportType: MONTHLY/YEARLY/CUSTOM checkboxes (lines 80-121)
 * - startMonth: SDTMM field (PIC X(2), NUM attribute, lines 127-132)
 * - startDay: SDTDD field (PIC X(2), NUM attribute, lines 138-143)
 * - startYear: SDTYYYY field (PIC X(4), NUM attribute, lines 149-154)
 * - endMonth: EDTMM field (PIC X(2), NUM attribute, lines 166-171)
 * - endDay: EDTDD field (PIC X(2), NUM attribute, lines 177-182)
 * - endYear: EDTYYYY field (PIC X(4), NUM attribute, lines 188-193)
 * - confirmation: CONFIRM field (PIC X(1), lines 206-210)
 */
interface ReportFormValues {
  reportType: ReportType;
  startMonth: string;
  startDay: string;
  startYear: string;
  endMonth: string;
  endDay: string;
  endYear: string;
  confirmation: string;
}

/**
 * Yup validation schema matching COBOL validation logic from CORPT00C.cbl
 * 
 * COBOL Validation Rules Preserved:
 * 1. Report type required selection
 * 2. For CUSTOM reports (COBOL lines 256-436):
 *    - Start date fields required (month, day, year)
 *    - End date fields required (month, day, year)
 *    - Month must be 1-12 (COBOL lines 329-336, 355-362)
 *    - Day must be 1-31 (COBOL lines 338-345, 364-371)
 *    - Year must be 1900-2100 (COBOL lines 347-353, 373-379)
 *    - All date fields must be numeric (BMS NUM attribute)
 *    - Start date must be before or equal to end date
 * 3. Confirmation must be Y or N (COBOL line 464-494)
 * 
 * Per Section 0.7.2: All field-level validations exactly as implemented in COBOL
 */
const validationSchema = yup.object().shape({
  reportType: yup
    .string()
    .required('Please select a report type')
    .oneOf(
      [ReportType.MONTHLY, ReportType.YEARLY, ReportType.CUSTOM],
      'Invalid report type'
    ),
  
  // Start date fields (required only for CUSTOM reports)
  // COBOL validation: lines 259-279 (empty checks)
  startMonth: yup.string().when('reportType', {
    is: ReportType.CUSTOM,
    then: (schema) => schema
      .required('Start month is required')
      .matches(/^\d{1,2}$/, 'Month must be numeric')
      .test('valid-month', 'Month must be between 1 and 12', (value) => {
        if (!value) return false;
        const month = parseInt(value, 10);
        return month >= 1 && month <= 12;
      }),
    otherwise: (schema) => schema.notRequired()
  }),
  
  startDay: yup.string().when('reportType', {
    is: ReportType.CUSTOM,
    then: (schema) => schema
      .required('Start day is required')
      .matches(/^\d{1,2}$/, 'Day must be numeric')
      .test('valid-day', 'Day must be between 1 and 31', (value) => {
        if (!value) return false;
        const day = parseInt(value, 10);
        return day >= 1 && day <= 31;
      }),
    otherwise: (schema) => schema.notRequired()
  }),
  
  startYear: yup.string().when('reportType', {
    is: ReportType.CUSTOM,
    then: (schema) => schema
      .required('Start year is required')
      .matches(/^\d{4}$/, 'Year must be 4 digits')
      .test('valid-year', 'Year must be between 1900 and 2100', (value) => {
        if (!value) return false;
        const year = parseInt(value, 10);
        return year >= 1900 && year <= 2100;
      }),
    otherwise: (schema) => schema.notRequired()
  }),
  
  // End date fields (required only for CUSTOM reports)
  // COBOL validation: lines 280-300 (empty checks)
  endMonth: yup.string().when('reportType', {
    is: ReportType.CUSTOM,
    then: (schema) => schema
      .required('End month is required')
      .matches(/^\d{1,2}$/, 'Month must be numeric')
      .test('valid-month', 'Month must be between 1 and 12', (value) => {
        if (!value) return false;
        const month = parseInt(value, 10);
        return month >= 1 && month <= 12;
      }),
    otherwise: (schema) => schema.notRequired()
  }),
  
  endDay: yup.string().when('reportType', {
    is: ReportType.CUSTOM,
    then: (schema) => schema
      .required('End day is required')
      .matches(/^\d{1,2}$/, 'Day must be numeric')
      .test('valid-day', 'Day must be between 1 and 31', (value) => {
        if (!value) return false;
        const day = parseInt(value, 10);
        return day >= 1 && day <= 31;
      }),
    otherwise: (schema) => schema.notRequired()
  }),
  
  endYear: yup.string().when('reportType', {
    is: ReportType.CUSTOM,
    then: (schema) => schema
      .required('End year is required')
      .matches(/^\d{4}$/, 'Year must be 4 digits')
      .test('valid-year', 'Year must be between 1900 and 2100', (value) => {
        if (!value) return false;
        const year = parseInt(value, 10);
        return year >= 1900 && year <= 2100;
      }),
    otherwise: (schema) => schema.notRequired()
  }),
  
  // Confirmation field (Y/N validation)
  // COBOL validation: lines 464-494
  confirmation: yup
    .string()
    .required('Please confirm report submission')
    .matches(/^[YyNn]$/, 'Confirmation must be Y or N')
    .uppercase()
});

/**
 * ReportMenuPage Component
 * 
 * React functional component for report generation menu.
 * Replaces COBOL CORPT00C.cbl online transaction program.
 * 
 * Features:
 * - Report type selection (Monthly/Yearly/Custom)
 * - Conditional date range inputs for custom reports
 * - Form validation matching COBOL business rules
 * - Error message display
 * - API integration for report generation
 * - Loading state during API calls
 * - Success/error feedback
 * - Navigation back to main menu (F3=Back equivalent)
 * 
 * Per Section 0.4.18: Transforms BMS screen to React page with responsive design
 * 
 * @returns React component rendering report menu page
 */
const ReportMenuPage: React.FC = () => {
  // Authentication context - replaces COBOL COMMAREA user validation
  const { isAuthenticated } = useAuth();
  
  // React Router navigation - replaces COBOL EXEC CICS RETURN TRANSID
  const navigate = useNavigate();
  
  // Error message state - replaces BMS ERRMSG field
  const [errorMessage, setErrorMessage] = useState<string>('');
  
  // Success message state (enhancement beyond COBOL)
  const [successMessage, setSuccessMessage] = useState<string>('');
  
  // Loading state during API call (enhancement beyond COBOL)
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);

  /**
   * Formik form management
   * Initial values matching BMS map field defaults
   * CORPT00.bms INITIAL=' ' for all input fields
   */
  const formik = useFormik<ReportFormValues>({
    initialValues: {
      reportType: ReportType.MONTHLY, // Default selection (first option)
      startMonth: '',
      startDay: '',
      startYear: '',
      endMonth: '',
      endDay: '',
      endYear: '',
      confirmation: ''
    },
    validationSchema: validationSchema,
    onSubmit: handleFormSubmit
  });

  /**
   * Form submission handler
   * Replaces COBOL SUBMIT-JOB-TO-INTRDR paragraph (lines 462-510)
   * 
   * Original COBOL logic:
   * - Validates confirmation field (CONFIRMI = 'Y')
   * - Constructs date strings from MM/DD/YYYY fields
   * - Validates dates using CSUTLDTC utility
   * - Submits JCL batch job via TDQ (Transient Data Queue)
   * - Displays success or error message
   * 
   * React implementation:
   * - Validates form fields using Yup schema
   * - Constructs date strings in YYYY-MM-DD format for API
   * - Calls reportService.generateReport() REST API
   * - Displays success message with download link
   * - Handles API errors and displays error message
   * 
   * @param values - Form values from Formik
   */
  async function handleFormSubmit(values: ReportFormValues): Promise<void> {
    // Clear previous messages
    setErrorMessage('');
    setSuccessMessage('');
    
    // Confirmation validation (COBOL lines 464-494)
    if (values.confirmation.toUpperCase() !== 'Y') {
      setErrorMessage('Report submission cancelled. Confirmation must be Y to continue.');
      return;
    }
    
    try {
      setIsSubmitting(true);
      
      // Prepare API request payload
      const request: ReportGenerationRequest = {
        reportType: values.reportType
      };
      
      // For CUSTOM reports, construct and validate date range
      // COBOL date validation: lines 259-427
      if (values.reportType === ReportType.CUSTOM) {
        // Construct start date string in MM/DD/YYYY format for validation
        const startDateStr = `${values.startMonth.padStart(2, '0')}/${values.startDay.padStart(2, '0')}/${values.startYear}`;
        
        // Construct end date string in MM/DD/YYYY format for validation
        const endDateStr = `${values.endMonth.padStart(2, '0')}/${values.endDay.padStart(2, '0')}/${values.endYear}`;
        
        // Validate start date using CSUTLDTC equivalent (lines 388-426)
        const startDateValidation = validateDate(startDateStr);
        if (!startDateValidation.valid) {
          setErrorMessage(`Start date error: ${startDateValidation.message}`);
          setIsSubmitting(false);
          return;
        }
        
        // Validate end date using CSUTLDTC equivalent
        const endDateValidation = validateDate(endDateStr);
        if (!endDateValidation.valid) {
          setErrorMessage(`End date error: ${endDateValidation.message}`);
          setIsSubmitting(false);
          return;
        }
        
        // Parse dates for range validation
        const startDate = new Date(parseInt(values.startYear), parseInt(values.startMonth) - 1, parseInt(values.startDay));
        const endDate = new Date(parseInt(values.endYear), parseInt(values.endMonth) - 1, parseInt(values.endDay));
        
        // Validate start date is before or equal to end date
        if (startDate > endDate) {
          setErrorMessage('Start date must be before or equal to end date');
          setIsSubmitting(false);
          return;
        }
        
        // Convert dates to YYYY-MM-DD API format (COBOL WS-DATE-FORMAT line 72)
        request.startDate = formatDateForApi(startDate);
        request.endDate = formatDateForApi(endDate);
      }
      
      // Call report generation API
      // Replaces COBOL JCL submission (EXEC CICS WRITEQ TD, lines 462-510)
      const response = await reportService.generateReport(request);
      
      // Display success message
      // Replaces COBOL success message (MOVE 'REPORT SUBMITTED' TO ERRMSGO)
      setSuccessMessage(
        `Report generated successfully! Report ID: ${response.reportId}. ` +
        `The report will be available for download.`
      );
      
      // Reset form after successful submission
      formik.resetForm();
      
      // Optional: Navigate to report download page or show download link
      // Could add: navigate(`/reports/download/${response.reportId}`);
      
    } catch (error: unknown) {
      // Handle API errors
      // Replaces COBOL error handling (MOVE error-message TO ERRMSGO)
      const errorMsg = error instanceof Error ? error.message : 'Failed to generate report';
      setErrorMessage(`Report generation failed: ${errorMsg}`);
    } finally {
      setIsSubmitting(false);
    }
  }

  /**
   * Handle cancel/back button
   * Replaces COBOL F3=Back function key (BMS line 226)
   * 
   * COBOL equivalent:
   * IF EIBAID = DFHPF3
   *    EXEC CICS RETURN TRANSID('CC00') END-EXEC
   * END-IF
   */
  const handleCancel = (): void => {
    navigate('/');
  };

  // Authentication check - redirect to login if not authenticated
  // Replaces COBOL security check (EXEC CICS VERIFY PASSWORD)
  if (!isAuthenticated) {
    navigate('/login');
    return null;
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      {/* Header - replaces BMS header lines 1-2 */}
      <Header />
      
      {/* Main content area */}
      <Box component="main" sx={{ flexGrow: 1, py: 4, bgcolor: 'background.default' }}>
        <Container maxWidth="md">
          <Paper elevation={3} sx={{ p: 4 }}>
            {/* Page title - replaces BMS line 4 "Transaction Reports" */}
            <Typography
              variant="h4"
              component="h1"
              gutterBottom
              align="center"
              sx={{ mb: 4, fontWeight: 'bold' }}
            >
              Transaction Reports
            </Typography>

            {/* Error message display - replaces BMS ERRMSG field (line 23) */}
            {errorMessage && (
              <ErrorMessage
                message={errorMessage}
                severity="error"
                onClose={() => setErrorMessage('')}
              />
            )}

            {/* Success message display (enhancement beyond COBOL) */}
            {successMessage && (
              <ErrorMessage
                message={successMessage}
                severity="success"
                autoHideDuration={10000}
                onClose={() => setSuccessMessage('')}
              />
            )}

            {/* Report generation form */}
            <form onSubmit={formik.handleSubmit}>
              {/* Report Type Selection - replaces BMS lines 7-11 (MONTHLY/YEARLY/CUSTOM) */}
              <FormControl component="fieldset" fullWidth sx={{ mb: 4 }}>
                <FormLabel component="legend" sx={{ mb: 2, fontWeight: 'bold' }}>
                  Select Report Type
                </FormLabel>
                <RadioGroup
                  name="reportType"
                  value={formik.values.reportType}
                  onChange={formik.handleChange}
                >
                  {/* Monthly option - BMS lines 80-93 */}
                  <FormControlLabel
                    value={ReportType.MONTHLY}
                    control={<Radio />}
                    label="Monthly (Current Month)"
                    sx={{ mb: 1 }}
                  />
                  
                  {/* Yearly option - BMS lines 94-107 */}
                  <FormControlLabel
                    value={ReportType.YEARLY}
                    control={<Radio />}
                    label="Yearly (Current Year)"
                    sx={{ mb: 1 }}
                  />
                  
                  {/* Custom option - BMS lines 108-121 */}
                  <FormControlLabel
                    value={ReportType.CUSTOM}
                    control={<Radio />}
                    label="Custom (Date Range)"
                  />
                </RadioGroup>
                {formik.touched.reportType && formik.errors.reportType && (
                  <Typography color="error" variant="caption" sx={{ mt: 1 }}>
                    {formik.errors.reportType}
                  </Typography>
                )}
              </FormControl>

              {/* Custom date range fields - conditional display */}
              {/* Replaces BMS lines 13-14 (SDTMM/SDTDD/SDTYYYY and EDTMM/EDTDD/EDTYYYY) */}
              {formik.values.reportType === ReportType.CUSTOM && (
                <Box sx={{ mb: 4, p: 3, bgcolor: 'grey.50', borderRadius: 1 }}>
                  {/* Start Date section */}
                  <Typography variant="subtitle1" gutterBottom sx={{ fontWeight: 'bold', mb: 2 }}>
                    Start Date (MM/DD/YYYY)
                  </Typography>
                  <Grid container spacing={2} sx={{ mb: 3 }}>
                    {/* Start Month - BMS SDTMM field (lines 127-132) */}
                    <Grid item xs={12} sm={4}>
                      <TextField
                        fullWidth
                        label="Month"
                        name="startMonth"
                        value={formik.values.startMonth}
                        onChange={formik.handleChange}
                        onBlur={formik.handleBlur}
                        error={formik.touched.startMonth && Boolean(formik.errors.startMonth)}
                        helperText={formik.touched.startMonth && formik.errors.startMonth}
                        inputProps={{ 
                          maxLength: 2,
                          placeholder: 'MM'
                        }}
                      />
                    </Grid>
                    
                    {/* Start Day - BMS SDTDD field (lines 138-143) */}
                    <Grid item xs={12} sm={4}>
                      <TextField
                        fullWidth
                        label="Day"
                        name="startDay"
                        value={formik.values.startDay}
                        onChange={formik.handleChange}
                        onBlur={formik.handleBlur}
                        error={formik.touched.startDay && Boolean(formik.errors.startDay)}
                        helperText={formik.touched.startDay && formik.errors.startDay}
                        inputProps={{ 
                          maxLength: 2,
                          placeholder: 'DD'
                        }}
                      />
                    </Grid>
                    
                    {/* Start Year - BMS SDTYYYY field (lines 149-154) */}
                    <Grid item xs={12} sm={4}>
                      <TextField
                        fullWidth
                        label="Year"
                        name="startYear"
                        value={formik.values.startYear}
                        onChange={formik.handleChange}
                        onBlur={formik.handleBlur}
                        error={formik.touched.startYear && Boolean(formik.errors.startYear)}
                        helperText={formik.touched.startYear && formik.errors.startYear}
                        inputProps={{ 
                          maxLength: 4,
                          placeholder: 'YYYY'
                        }}
                      />
                    </Grid>
                  </Grid>

                  {/* End Date section */}
                  <Typography variant="subtitle1" gutterBottom sx={{ fontWeight: 'bold', mb: 2 }}>
                    End Date (MM/DD/YYYY)
                  </Typography>
                  <Grid container spacing={2}>
                    {/* End Month - BMS EDTMM field (lines 166-171) */}
                    <Grid item xs={12} sm={4}>
                      <TextField
                        fullWidth
                        label="Month"
                        name="endMonth"
                        value={formik.values.endMonth}
                        onChange={formik.handleChange}
                        onBlur={formik.handleBlur}
                        error={formik.touched.endMonth && Boolean(formik.errors.endMonth)}
                        helperText={formik.touched.endMonth && formik.errors.endMonth}
                        inputProps={{ 
                          maxLength: 2,
                          placeholder: 'MM'
                        }}
                      />
                    </Grid>
                    
                    {/* End Day - BMS EDTDD field (lines 177-182) */}
                    <Grid item xs={12} sm={4}>
                      <TextField
                        fullWidth
                        label="Day"
                        name="endDay"
                        value={formik.values.endDay}
                        onChange={formik.handleChange}
                        onBlur={formik.handleBlur}
                        error={formik.touched.endDay && Boolean(formik.errors.endDay)}
                        helperText={formik.touched.endDay && formik.errors.endDay}
                        inputProps={{ 
                          maxLength: 2,
                          placeholder: 'DD'
                        }}
                      />
                    </Grid>
                    
                    {/* End Year - BMS EDTYYYY field (lines 188-193) */}
                    <Grid item xs={12} sm={4}>
                      <TextField
                        fullWidth
                        label="Year"
                        name="endYear"
                        value={formik.values.endYear}
                        onChange={formik.handleChange}
                        onBlur={formik.handleBlur}
                        error={formik.touched.endYear && Boolean(formik.errors.endYear)}
                        helperText={formik.touched.endYear && formik.errors.endYear}
                        inputProps={{ 
                          maxLength: 4,
                          placeholder: 'YYYY'
                        }}
                      />
                    </Grid>
                  </Grid>
                </Box>
              )}

              {/* Confirmation section - replaces BMS line 19 */}
              <Box sx={{ mb: 4 }}>
                <Typography variant="body1" gutterBottom sx={{ mb: 2 }}>
                  The Report will be submitted for printing. Please confirm:
                </Typography>
                <TextField
                  fullWidth
                  label="Confirmation (Y/N)"
                  name="confirmation"
                  value={formik.values.confirmation}
                  onChange={formik.handleChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.confirmation && Boolean(formik.errors.confirmation)}
                  helperText={
                    formik.touched.confirmation && formik.errors.confirmation
                      ? formik.errors.confirmation
                      : 'Enter Y to confirm or N to cancel'
                  }
                  inputProps={{ 
                    maxLength: 1,
                    style: { textTransform: 'uppercase' }
                  }}
                  sx={{ maxWidth: 200 }}
                />
              </Box>

              {/* Action buttons - replaces BMS line 24 "ENTER=Continue  F3=Back" */}
              <Box sx={{ display: 'flex', gap: 2, justifyContent: 'flex-end' }}>
                {/* Cancel button - F3=Back equivalent */}
                <Button
                  variant="outlined"
                  onClick={handleCancel}
                  disabled={isSubmitting}
                  size="large"
                >
                  Back
                </Button>
                
                {/* Submit button - ENTER key equivalent */}
                <Button
                  type="submit"
                  variant="contained"
                  disabled={isSubmitting}
                  size="large"
                  startIcon={isSubmitting ? <CircularProgress size={20} /> : null}
                >
                  {isSubmitting ? 'Submitting...' : 'Generate Report'}
                </Button>
              </Box>
            </form>
          </Paper>
        </Container>
      </Box>
      
      {/* Footer - enhancement beyond BMS terminal screens */}
      <Footer />
    </Box>
  );
};

/**
 * Default export for ReportMenuPage component
 * Per Agent Action Plan Section 0.4.18 export requirements
 */
export default ReportMenuPage;
