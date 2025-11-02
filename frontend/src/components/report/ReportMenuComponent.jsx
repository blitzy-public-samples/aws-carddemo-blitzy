/**
 * ReportMenuComponent.jsx
 * 
 * React functional component implementing report generation menu for CardDemo application.
 * Transforms BMS mapset CORPT00.bms and COBOL program CORPT00C.cbl to modern web UI.
 * 
 * Features:
 * - Three report type options: Monthly (current month), Yearly (current year), Custom (date range)
 * - Date range inputs with validation matching COBOL logic
 * - Confirmation requirement (Y/N)
 * - Material-UI styling with responsive grid layout
 * - Integration with reportService for backend communication
 * - Field validation preserving COBOL validation rules exactly
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import { useState, useEffect } from 'react';
import {
  Box,
  Card,
  CardContent,
  Grid,
  Typography,
  Radio,
  RadioGroup,
  FormControlLabel,
  TextField,
  Button,
  Alert,
  Paper,
  Divider,
  FormControl,
  FormLabel,
  CircularProgress
} from '@mui/material';
import { DatePicker } from '@mui/x-date-pickers/DatePicker';
import { LocalizationProvider } from '@mui/x-date-pickers/LocalizationProvider';
import { AdapterDateFns } from '@mui/x-date-pickers/AdapterDateFns';
import { useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import reportService from '../../services/reportService';

/**
 * ReportMenuComponent
 * 
 * Main report generation interface component.
 * Maps to COBOL program CORPT00C.cbl and BMS mapset CORPT00.bms
 */
const ReportMenuComponent = () => {
  const navigate = useNavigate();

  // Component State - Maps to COBOL CORPT00 screen fields and working storage
  const [selectedReportType, setSelectedReportType] = useState(''); // MONTHLYI, YEARLYI, CUSTOMI
  const [startDate, setStartDate] = useState(null); // SDTMM, SDTDD, SDTYYYY
  const [endDate, setEndDate] = useState(null); // EDTMM, EDTDD, EDTYYYY
  const [confirm, setConfirm] = useState(''); // CONFIRMI
  const [errorMessage, setErrorMessage] = useState(''); // ERRMSGO
  const [loading, setLoading] = useState(false);

  // Current date and time for header display - Maps to CURDATE, CURTIME in BMS
  const [currentDate, setCurrentDate] = useState('');
  const [currentTime, setCurrentTime] = useState('');

  /**
   * Initialize current date and time on component mount
   * Maps to POPULATE-HEADER-INFO paragraph (lines 609-628 in CORPT00C.cbl)
   */
  useEffect(() => {
    const updateDateTime = () => {
      const now = new Date();
      
      // Format date as MM/DD/YY (matching BMS CURDATE format)
      const month = String(now.getMonth() + 1).padStart(2, '0');
      const day = String(now.getDate()).padStart(2, '0');
      const year = String(now.getFullYear()).substring(2);
      setCurrentDate(`${month}/${day}/${year}`);
      
      // Format time as HH:MM:SS (matching BMS CURTIME format)
      const hours = String(now.getHours()).padStart(2, '0');
      const minutes = String(now.getMinutes()).padStart(2, '0');
      const seconds = String(now.getSeconds()).padStart(2, '0');
      setCurrentTime(`${hours}:${minutes}:${seconds}`);
    };

    updateDateTime();
    // Update time every second to match mainframe behavior
    const interval = setInterval(updateDateTime, 1000);

    return () => clearInterval(interval);
  }, []);

  /**
   * Validate date input fields
   * Maps to date validation logic in COBOL (lines 305-427 in CORPT00C.cbl)
   * 
   * @param {Date} dateValue - The date to validate
   * @param {string} fieldName - Name of the field for error messages
   * @returns {Object} Validation result with valid flag and error message
   */
  const validateDateInput = (dateValue, fieldName) => {
    if (!dateValue) {
      return { valid: false, error: `${fieldName} can NOT be empty...` };
    }

    const month = dateValue.getMonth() + 1;
    const day = dateValue.getDate();
    const year = dateValue.getFullYear();
    
    // Validate month (lines 329-336 in CORPT00C.cbl)
    if (month < 1 || month > 12) {
      return { valid: false, error: `${fieldName} - Not a valid Month...` };
    }
    
    // Validate day (lines 338-345 in CORPT00C.cbl)
    if (day < 1 || day > 31) {
      return { valid: false, error: `${fieldName} - Not a valid Day...` };
    }
    
    // Validate year (lines 347-353 in CORPT00C.cbl)
    if (isNaN(year) || year < 1900) {
      return { valid: false, error: `${fieldName} - Not a valid Year...` };
    }

    // Additional validation for valid calendar date
    // This mimics the CSUTLDTC call validation (lines 388-426 in CORPT00C.cbl)
    const testDate = new Date(year, month - 1, day);
    if (testDate.getFullYear() !== year || 
        testDate.getMonth() !== month - 1 || 
        testDate.getDate() !== day) {
      return { valid: false, error: `${fieldName} - Not a valid date...` };
    }
    
    return { valid: true };
  };

  /**
   * Reset all form fields
   * Maps to INITIALIZE-ALL-FIELDS paragraph (lines 633-646 in CORPT00C.cbl)
   */
  const handleReset = () => {
    setSelectedReportType('');
    setStartDate(null);
    setEndDate(null);
    setConfirm('');
    setErrorMessage('');
  };

  /**
   * Handle Back button click
   * Maps to DFHPF3 processing (lines 187-189 in CORPT00C.cbl)
   * Returns to main menu (COMEN01C)
   */
  const handleBack = () => {
    navigate('/menu');
  };

  /**
   * Handle form submission
   * Maps to PROCESS-ENTER-KEY paragraph (lines 208-456 in CORPT00C.cbl)
   */
  const handleSubmit = async () => {
    // Clear previous error message
    setErrorMessage('');

    // Validation: Check if report type is selected (lines 437-442 in CORPT00C.cbl)
    if (!selectedReportType) {
      setErrorMessage('Select a report type to print report...');
      return;
    }

    // Process based on selected report type (EVALUATE TRUE at line 212)
    if (selectedReportType === 'monthly') {
      // Maps to WHEN MONTHLYI (lines 213-238 in CORPT00C.cbl)
      await handleMonthlyReport();
    } else if (selectedReportType === 'yearly') {
      // Maps to WHEN YEARLYI (lines 239-255 in CORPT00C.cbl)
      await handleYearlyReport();
    } else if (selectedReportType === 'custom') {
      // Maps to WHEN CUSTOMI (lines 256-436 in CORPT00C.cbl)
      await handleCustomReport();
    }
  };

  /**
   * Handle Monthly report submission
   * Maps to lines 213-238 in CORPT00C.cbl
   */
  const handleMonthlyReport = async () => {
    // Check confirmation (maps to SUBMIT-JOB-TO-INTRDR, lines 464-510)
    const confirmResult = validateConfirmation('Monthly');
    if (!confirmResult.valid) {
      setErrorMessage(confirmResult.error);
      return;
    }

    if (confirm.toUpperCase() === 'N') {
      handleReset();
      return;
    }

    try {
      setLoading(true);
      
      // Submit report to backend (service calculates current month dates)
      await reportService.generateMonthlyReport();
      
      // Success message (lines 448-454 in CORPT00C.cbl)
      toast.success('Monthly report submitted for printing...');
      handleReset();
    } catch (error) {
      const errorMsg = error.message || 'Unable to submit report for generation';
      setErrorMessage(errorMsg);
      toast.error(errorMsg);
    } finally {
      setLoading(false);
    }
  };

  /**
   * Handle Yearly report submission
   * Maps to lines 239-255 in CORPT00C.cbl
   */
  const handleYearlyReport = async () => {
    // Check confirmation
    const confirmResult = validateConfirmation('Yearly');
    if (!confirmResult.valid) {
      setErrorMessage(confirmResult.error);
      return;
    }

    if (confirm.toUpperCase() === 'N') {
      handleReset();
      return;
    }

    try {
      setLoading(true);
      
      // Submit report to backend (service calculates current year dates)
      await reportService.generateYearlyReport();
      
      // Success message
      toast.success('Yearly report submitted for printing...');
      handleReset();
    } catch (error) {
      const errorMsg = error.message || 'Unable to submit report for generation';
      setErrorMessage(errorMsg);
      toast.error(errorMsg);
    } finally {
      setLoading(false);
    }
  };

  /**
   * Handle Custom report submission
   * Maps to lines 256-436 in CORPT00C.cbl
   */
  const handleCustomReport = async () => {
    // Validate start date fields (lines 258-279 in CORPT00C.cbl)
    if (!startDate) {
      setErrorMessage('Start Date - Month can NOT be empty...');
      return;
    }

    // Validate end date fields (lines 280-300 in CORPT00C.cbl)
    if (!endDate) {
      setErrorMessage('End Date - Month can NOT be empty...');
      return;
    }

    // Validate start date values (lines 305-353 in CORPT00C.cbl)
    const startDateValidation = validateDateInput(startDate, 'Start Date');
    if (!startDateValidation.valid) {
      setErrorMessage(startDateValidation.error);
      return;
    }

    // Validate end date values (lines 355-379 in CORPT00C.cbl)
    const endDateValidation = validateDateInput(endDate, 'End Date');
    if (!endDateValidation.valid) {
      setErrorMessage(endDateValidation.error);
      return;
    }

    // Check confirmation
    const confirmResult = validateConfirmation('Custom');
    if (!confirmResult.valid) {
      setErrorMessage(confirmResult.error);
      return;
    }

    if (confirm.toUpperCase() === 'N') {
      handleReset();
      return;
    }

    try {
      setLoading(true);
      
      // Format dates for API (YYYY-MM-DD format matching lines 381-386 in CORPT00C.cbl)
      const formattedStartDate = formatDateForAPI(startDate);
      const formattedEndDate = formatDateForAPI(endDate);

      // Submit report to backend
      await reportService.generateCustomReport(formattedStartDate, formattedEndDate);
      
      // Success message
      toast.success('Custom report submitted for printing...');
      handleReset();
    } catch (error) {
      const errorMsg = error.message || 'Unable to submit report for generation';
      setErrorMessage(errorMsg);
      toast.error(errorMsg);
    } finally {
      setLoading(false);
    }
  };

  /**
   * Validate confirmation input
   * Maps to SUBMIT-JOB-TO-INTRDR paragraph (lines 464-494 in CORPT00C.cbl)
   * 
   * @param {string} reportName - Name of the report type
   * @returns {Object} Validation result
   */
  const validateConfirmation = (reportName) => {
    // Check if confirmation is empty (lines 464-474 in CORPT00C.cbl)
    if (!confirm || confirm === '') {
      return {
        valid: false,
        error: `Please confirm to print the ${reportName} report...`
      };
    }

    // Check if confirmation is Y or N (lines 477-494 in CORPT00C.cbl)
    const confirmUpper = confirm.toUpperCase();
    if (confirmUpper !== 'Y' && confirmUpper !== 'N') {
      return {
        valid: false,
        error: `"${confirm}" is not a valid value to confirm...`
      };
    }

    return { valid: true };
  };

  /**
   * Format date for API submission
   * Maps to WS-START-DATE and WS-END-DATE formatting (lines 60-71 in CORPT00C.cbl)
   * 
   * @param {Date} date - Date to format
   * @returns {string} Formatted date string (YYYY-MM-DD)
   */
  const formatDateForAPI = (date) => {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  };

  /**
   * Render the component
   * Maps to BMS mapset CORPT00.bms structure
   */
  return (
    <Box sx={{ p: 3, maxWidth: 1200, mx: 'auto' }}>
      {/* Header Section - Maps to BMS fields TRNNAME, TITLE01, TITLE02, CURDATE, PGMNAME, CURTIME */}
      <Paper elevation={2} sx={{ p: 2, mb: 3, backgroundColor: '#f5f5f5' }}>
        <Grid container spacing={2}>
          <Grid item xs={12} md={6}>
            <Typography variant="body2" color="primary">
              Tran: CR00 | Prog: CORPT00C
            </Typography>
          </Grid>
          <Grid item xs={12} md={6} sx={{ textAlign: { md: 'right' } }}>
            <Typography variant="body2" color="primary">
              Date: {currentDate} | Time: {currentTime}
            </Typography>
          </Grid>
        </Grid>
        <Typography variant="h5" sx={{ mt: 1, fontWeight: 'bold', textAlign: 'center' }}>
          Transaction Reports
        </Typography>
      </Paper>

      {/* Main Content Card */}
      <Card elevation={3}>
        <CardContent sx={{ p: 4 }}>
          {/* Report Type Selection - Maps to BMS MONTHLY, YEARLY, CUSTOM fields */}
          <FormControl component="fieldset" fullWidth sx={{ mb: 4 }}>
            <FormLabel component="legend" sx={{ mb: 2, fontWeight: 'bold', fontSize: '1.1rem' }}>
              Select Report Type
            </FormLabel>
            <RadioGroup
              value={selectedReportType}
              onChange={(e) => {
                setSelectedReportType(e.target.value);
                setErrorMessage('');
              }}
            >
              <FormControlLabel
                value="monthly"
                control={<Radio />}
                label="Monthly (Current Month)"
                sx={{ mb: 1 }}
              />
              <FormControlLabel
                value="yearly"
                control={<Radio />}
                label="Yearly (Current Year)"
                sx={{ mb: 1 }}
              />
              <FormControlLabel
                value="custom"
                control={<Radio />}
                label="Custom (Date Range)"
              />
            </RadioGroup>
          </FormControl>

          <Divider sx={{ my: 3 }} />

          {/* Date Range Inputs (Custom Report) - Maps to BMS SDTMM, SDTDD, SDTYYYY, EDTMM, EDTDD, EDTYYYY */}
          {selectedReportType === 'custom' && (
            <Box sx={{ mb: 4 }}>
              <Typography variant="body1" sx={{ mb: 2, fontWeight: 'bold' }}>
                Date Range
              </Typography>
              <LocalizationProvider dateAdapter={AdapterDateFns}>
                <Grid container spacing={3}>
                  <Grid item xs={12} md={6}>
                    <DatePicker
                      label="Start Date"
                      value={startDate}
                      onChange={(newValue) => {
                        setStartDate(newValue);
                        setErrorMessage('');
                      }}
                      slotProps={{
                        textField: {
                          fullWidth: true,
                          helperText: 'MM/DD/YYYY',
                          error: false
                        }
                      }}
                      format="MM/dd/yyyy"
                    />
                  </Grid>
                  <Grid item xs={12} md={6}>
                    <DatePicker
                      label="End Date"
                      value={endDate}
                      onChange={(newValue) => {
                        setEndDate(newValue);
                        setErrorMessage('');
                      }}
                      slotProps={{
                        textField: {
                          fullWidth: true,
                          helperText: 'MM/DD/YYYY',
                          error: false
                        }
                      }}
                      format="MM/dd/yyyy"
                    />
                  </Grid>
                </Grid>
              </LocalizationProvider>
            </Box>
          )}

          <Divider sx={{ my: 3 }} />

          {/* Confirmation Section - Maps to BMS CONFIRM field */}
          <Box sx={{ mb: 4 }}>
            <Typography variant="body1" sx={{ mb: 2 }}>
              The Report will be submitted for printing. Please confirm:
            </Typography>
            <Grid container spacing={2} alignItems="center">
              <Grid item>
                <TextField
                  value={confirm}
                  onChange={(e) => {
                    const value = e.target.value.toUpperCase();
                    if (value.length <= 1) {
                      setConfirm(value);
                      setErrorMessage('');
                    }
                  }}
                  placeholder="Y/N"
                  size="small"
                  inputProps={{ 
                    maxLength: 1,
                    style: { textTransform: 'uppercase' }
                  }}
                  sx={{ width: '80px' }}
                />
              </Grid>
              <Grid item>
                <Typography variant="body2" color="text.secondary">
                  (Y/N)
                </Typography>
              </Grid>
            </Grid>
          </Box>

          {/* Error Message Display - Maps to BMS ERRMSG field */}
          {errorMessage && (
            <Alert severity="error" sx={{ mb: 3 }}>
              {errorMessage}
            </Alert>
          )}

          {/* Action Buttons - Maps to BMS "ENTER=Continue  F3=Back" */}
          <Box sx={{ display: 'flex', gap: 2, justifyContent: 'center', mt: 4 }}>
            <Button
              variant="contained"
              color="primary"
              onClick={handleSubmit}
              disabled={loading}
              sx={{ minWidth: 150 }}
            >
              {loading ? <CircularProgress size={24} color="inherit" /> : 'ENTER=Continue'}
            </Button>
            <Button
              variant="outlined"
              onClick={handleBack}
              disabled={loading}
              sx={{ minWidth: 150 }}
            >
              F3=Back
            </Button>
          </Box>
        </CardContent>
      </Card>
    </Box>
  );
};

export default ReportMenuComponent;
