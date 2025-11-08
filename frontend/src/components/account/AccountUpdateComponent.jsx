/**
 * AccountUpdateComponent - Account Update Screen
 * 
 * React component for updating account credit limits and details.
 * Transformed from BMS screen COACTUPM.bms to modern React web application.
 * 
 * Original COBOL Sources:
 * - app/bms/COACTUP.bms: BMS 3270 terminal screen definition (513 lines)
 * - app/cpy/CVACT01Y.cpy: ACCOUNT-RECORD data structure
 * - app/cbl/COACTUPC.cbl: Account update business logic
 * 
 * BMS Screen Layout (80x24 terminal):
 * - Lines 1-3: Header (transaction, program, date/time, user info)
 * - Line 4: Screen title "Update Account"
 * - Lines 5-22: Account and customer details form fields
 * - Line 23: Error message area (ERRMSG)
 * - Line 24: Function key help (FKEYS)
 * 
 * Field Types:
 * - UNPROT (editable): Account number, active status, credit limits, dates
 * - ASKIP (protected): Customer details, balances, calculated fields
 * 
 * Navigation Patterns:
 * - PF3: Back to previous screen
 * - ENTER: Validate and process update
 * - F5: Save and stay on screen
 * - F12: Cancel and reset form
 * 
 * React Transformation:
 * - Formik for form state management
 * - Yup for validation schema (matching COBOL PIC clauses)
 * - Material-UI for consistent UI components
 * - Axios via accountService for REST API calls
 * - React Router for navigation
 * 
 * Validation Rules (from COBOL):
 * - Account ID: PIC 9(11) - exactly 11 digits
 * - Credit Limit: PIC S9(10)V99 - monetary with 2 decimals, min $1,000
 * - Cash Limit: PIC S9(10)V99 - monetary with 2 decimals
 * - Active Status: PIC X(01) - Y or N only
 * - Dates: YYYY-MM-DD format validation
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import React, { useState, useEffect, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useFormik } from 'formik';
import * as yup from 'yup';
import {
  Box,
  Container,
  Grid,
  TextField,
  Button,
  Typography,
  Paper,
  CircularProgress,
  Alert,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogContentText,
  DialogActions,
  Divider,
  InputAdornment,
  FormControlLabel,
  Radio,
  RadioGroup,
  FormControl,
  FormLabel,
  FormHelperText
} from '@mui/material';
import SaveIcon from '@mui/icons-material/Save';
import CancelIcon from '@mui/icons-material/Cancel';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';

// Internal imports from depends_on_files
import { getAccountById, updateAccount } from '../../services/accountService.js';
import { useAuth } from '../../context/AuthContext.js';
import {
  validateAccountId,
  validateCreditLimit,
  validateDate,
  validateName,
  validateSSN,
  validatePhoneNumber,
  validateStateCode,
  validateZipCode,
  validateRequired
} from '../../utils/validators.js';
import Header from '../common/Header.jsx';
import Footer from '../common/Footer.jsx';
import {
  ERROR_REQUIRED,
  ERROR_INVALID_FORMAT,
  MAX_LENGTH_ACCOUNT_ID,
  MAX_LENGTH_NAME,
  MAX_LENGTH_ADDRESS,
  MAX_LENGTH_PHONE,
  MAX_LENGTH_SSN,
  MAX_LENGTH_ZIP,
  DATE_FORMAT_DISPLAY,
  DATE_FORMAT_API,
  MSG_SERVER_ERROR
} from '../../utils/constants.js';

/**
 * Yup validation schema matching COBOL validation rules
 * 
 * This schema replicates the validation patterns from COBOL:
 * - VALIDN=(MUSTFILL) → required()
 * - PIC 9(11) → matches(/^\d{11}$/)
 * - PIC S9(10)V99 → number() with min/max and decimal precision
 * - PIC X(n) → string() with max length
 * - 88-level conditions → oneOf() or matches()
 */
const validationSchema = yup.object({
  accountId: yup
    .string()
    .required(ERROR_REQUIRED)
    .length(MAX_LENGTH_ACCOUNT_ID, `Account ID must be exactly ${MAX_LENGTH_ACCOUNT_ID} digits`)
    .matches(/^\d{11}$/, 'Account ID must contain only digits')
    .test('valid-account-id', 'Invalid account ID', function(value) {
      if (!value) return false;
      const result = validateAccountId(value);
      if (!result.isValid) {
        return this.createError({ message: result.errorMessage });
      }
      return true;
    }),
  
  activeStatus: yup
    .string()
    .required(ERROR_REQUIRED)
    .oneOf(['Y', 'N'], 'Active status must be Y or N'),
  
  creditLimit: yup
    .number()
    .required(ERROR_REQUIRED)
    .min(1000, 'Credit limit must be at least $1,000.00')
    .max(9999999999.99, 'Credit limit cannot exceed $9,999,999,999.99')
    .test('decimal-precision', 'Credit limit must have at most 2 decimal places', function(value) {
      if (value === null || value === undefined) return false;
      const decimalPart = value.toString().split('.')[1];
      return !decimalPart || decimalPart.length <= 2;
    })
    .test('valid-credit-limit', 'Invalid credit limit', function(value) {
      if (value === null || value === undefined) return false;
      const result = validateCreditLimit(value);
      if (!result.isValid) {
        return this.createError({ message: result.errorMessage });
      }
      return true;
    }),
  
  cashCreditLimit: yup
    .number()
    .required(ERROR_REQUIRED)
    .min(0, 'Cash credit limit must be non-negative')
    .max(9999999999.99, 'Cash credit limit cannot exceed $9,999,999,999.99')
    .test('decimal-precision', 'Cash credit limit must have at most 2 decimal places', function(value) {
      if (value === null || value === undefined) return false;
      const decimalPart = value.toString().split('.')[1];
      return !decimalPart || decimalPart.length <= 2;
    })
    .test('not-exceed-credit-limit', 'Cash credit limit cannot exceed credit limit', function(value) {
      const creditLimit = this.parent.creditLimit;
      if (value && creditLimit && value > creditLimit) {
        return this.createError({ message: 'Cash credit limit cannot exceed credit limit' });
      }
      return true;
    }),
  
  expirationDate: yup
    .string()
    .required(ERROR_REQUIRED)
    .matches(/^\d{4}-\d{2}-\d{2}$/, 'Date must be in YYYY-MM-DD format')
    .test('valid-date', ERROR_INVALID_FORMAT, function(value) {
      if (!value) return false;
      const result = validateDate(value);
      if (!result.isValid) {
        return this.createError({ message: result.errorMessage });
      }
      return true;
    })
    .test('future-date', 'Expiration date must be in the future', function(value) {
      if (!value) return false;
      const expDate = new Date(value);
      const today = new Date();
      today.setHours(0, 0, 0, 0);
      return expDate >= today;
    }),
  
  // Customer fields (most are read-only but included for form structure)
  customerFirstName: yup
    .string()
    .max(MAX_LENGTH_NAME, `First name cannot exceed ${MAX_LENGTH_NAME} characters`),
  
  customerLastName: yup
    .string()
    .max(MAX_LENGTH_NAME, `Last name cannot exceed ${MAX_LENGTH_NAME} characters`)
});

/**
 * AccountUpdateComponent Main Component
 * 
 * Manages account update form with the following features:
 * - Fetches account data on mount using accountId from URL params
 * - Provides editable fields for credit limits and account status
 * - Validates all inputs matching COBOL validation rules
 * - Submits updates via PUT /api/accounts/{id} REST endpoint
 * - Handles errors with user-friendly messages
 * - Supports navigation patterns (Back, Save, Cancel)
 * - Displays confirmation dialog for destructive changes
 * - Shows loading states during async operations
 * 
 * @returns {JSX.Element} Account update form component
 */
const AccountUpdateComponent = () => {
  // URL parameter extraction
  const { accountId } = useParams();
  const navigate = useNavigate();
  
  // Authentication context
  const { isAuthenticated, isAdmin } = useAuth();
  
  // Component state
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState(null);
  const [successMessage, setSuccessMessage] = useState(null);
  const [accountData, setAccountData] = useState(null);
  const [showConfirmDialog, setShowConfirmDialog] = useState(false);
  const [pendingValues, setPendingValues] = useState(null);
  
  /**
   * Fetch account data on component mount
   * Replicates COBOL: EXEC CICS READ DATASET('ACCTDAT') INTO(ACCOUNT-RECORD)
   */
  useEffect(() => {
    const fetchAccountData = async () => {
      try {
        setLoading(true);
        setError(null);
        
        // Validate account ID before fetching
        const accountIdValidation = validateAccountId(accountId);
        if (!accountIdValidation.isValid) {
          setError(accountIdValidation.errorMessage);
          setLoading(false);
          return;
        }
        
        // Call API to fetch account details
        const data = await getAccountById(accountId);
        setAccountData(data);
        
        // Initialize form with fetched data
        formik.setValues({
          accountId: data.accountId || '',
          activeStatus: data.activeStatus || 'Y',
          creditLimit: data.creditLimit || 0,
          cashCreditLimit: data.cashCreditLimit || 0,
          expirationDate: data.expirationDate || '',
          openDate: data.openDate || '',
          currentBalance: data.currentBalance || 0,
          cycleCreditLimit: data.cycleCreditLimit || 0,
          cycleDebitLimit: data.cycleDebitLimit || 0,
          reissueDate: data.reissueDate || '',
          customerId: data.customerId || '',
          customerFirstName: data.customerFirstName || '',
          customerMiddleName: data.customerMiddleName || '',
          customerLastName: data.customerLastName || '',
          customerSSN: data.customerSSN || '',
          customerDOB: data.customerDOB || '',
          customerFICO: data.customerFICO || '',
          customerAddress1: data.customerAddress1 || '',
          customerAddress2: data.customerAddress2 || '',
          customerCity: data.customerCity || '',
          customerState: data.customerState || '',
          customerZip: data.customerZip || '',
          customerPhone1: data.customerPhone1 || '',
          customerPhone2: data.customerPhone2 || '',
          customerGovtId: data.customerGovtId || '',
          customerEFTAccount: data.customerEFTAccount || ''
        });
        
      } catch (err) {
        console.error('Error fetching account data:', err);
        setError(err.message || MSG_SERVER_ERROR);
      } finally {
        setLoading(false);
      }
    };
    
    if (accountId && isAuthenticated) {
      fetchAccountData();
    } else if (!isAuthenticated) {
      setError('You must be logged in to view this page');
      setLoading(false);
    } else {
      setError('Account ID is required');
      setLoading(false);
    }
  }, [accountId, isAuthenticated]);
  
  /**
   * Handle form submission
   * Replicates COBOL: EXEC CICS REWRITE DATASET('ACCTDAT') FROM(ACCOUNT-RECORD)
   * 
   * @param {Object} values - Form values from Formik
   */
  const handleSubmit = async (values) => {
    try {
      setSubmitting(true);
      setError(null);
      setSuccessMessage(null);
      
      // Prepare update request payload
      const updateRequest = {
        accountId: values.accountId,
        activeStatus: values.activeStatus,
        creditLimit: parseFloat(values.creditLimit),
        cashCreditLimit: parseFloat(values.cashCreditLimit),
        expirationDate: values.expirationDate
      };
      
      // Call API to update account
      const updatedAccount = await updateAccount(accountId, updateRequest);
      
      // Update local state with response
      setAccountData(updatedAccount);
      setSuccessMessage('Account updated successfully');
      
      // Scroll to top to show success message
      window.scrollTo({ top: 0, behavior: 'smooth' });
      
    } catch (err) {
      console.error('Error updating account:', err);
      setError(err.message || MSG_SERVER_ERROR);
      // Scroll to top to show error message
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } finally {
      setSubmitting(false);
      setShowConfirmDialog(false);
    }
  };
  
  /**
   * Show confirmation dialog before submitting
   * Implements safety check for destructive changes
   * 
   * @param {Object} values - Form values to be submitted
   */
  const handleConfirmSubmit = (values) => {
    setPendingValues(values);
    setShowConfirmDialog(true);
  };
  
  /**
   * Handle dialog confirmation
   */
  const handleDialogConfirm = () => {
    if (pendingValues) {
      handleSubmit(pendingValues);
    }
  };
  
  /**
   * Handle dialog cancellation
   */
  const handleDialogCancel = () => {
    setShowConfirmDialog(false);
    setPendingValues(null);
  };
  
  /**
   * Handle cancel button - reset form to original values
   * Replicates BMS PF12=Cancel behavior
   */
  const handleCancel = () => {
    if (accountData) {
      formik.resetForm();
      setError(null);
      setSuccessMessage(null);
    }
  };
  
  /**
   * Handle back navigation
   * Replicates BMS PF3=Back behavior
   */
  const handleBack = () => {
    navigate('/account/view/' + accountId);
  };
  
  /**
   * Formik form management
   */
  const formik = useFormik({
    initialValues: {
      accountId: '',
      activeStatus: 'Y',
      creditLimit: 0,
      cashCreditLimit: 0,
      expirationDate: '',
      openDate: '',
      currentBalance: 0,
      cycleCreditLimit: 0,
      cycleDebitLimit: 0,
      reissueDate: '',
      customerId: '',
      customerFirstName: '',
      customerMiddleName: '',
      customerLastName: '',
      customerSSN: '',
      customerDOB: '',
      customerFICO: '',
      customerAddress1: '',
      customerAddress2: '',
      customerCity: '',
      customerState: '',
      customerZip: '',
      customerPhone1: '',
      customerPhone2: '',
      customerGovtId: '',
      customerEFTAccount: ''
    },
    validationSchema: validationSchema,
    onSubmit: handleConfirmSubmit,
    enableReinitialize: false
  });
  
  /**
   * Format currency for display
   * Matches COBOL: MOVE ACCT-CURR-BAL TO ACURBAL pattern
   * 
   * @param {number} value - Numeric value to format
   * @returns {string} Formatted currency string
   */
  const formatCurrency = (value) => {
    if (value === null || value === undefined || isNaN(value)) {
      return '$0.00';
    }
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    }).format(value);
  };
  
  /**
   * Format date for display
   * Matches BMS date field format MM/DD/YY
   * 
   * @param {string} dateStr - Date string in YYYY-MM-DD format
   * @returns {string} Formatted date string
   */
  const formatDateDisplay = (dateStr) => {
    if (!dateStr) return '';
    try {
      const date = new Date(dateStr);
      const month = String(date.getMonth() + 1).padStart(2, '0');
      const day = String(date.getDate()).padStart(2, '0');
      const year = String(date.getFullYear());
      return `${month}/${day}/${year}`;
    } catch (err) {
      return dateStr;
    }
  };
  
  /**
   * Format SSN for display (with masking)
   * Matches COBOL: CUST-SSN PIC 9(09) with masking for security
   * 
   * @param {string} ssn - SSN string
   * @returns {string} Masked SSN (XXX-XX-1234)
   */
  const formatSSN = (ssn) => {
    if (!ssn || ssn.length !== 9) return '';
    return `XXX-XX-${ssn.substring(5)}`;
  };
  
  /**
   * Render loading state
   */
  if (loading) {
    return (
      <Box display="flex" justifyContent="center" alignItems="center" minHeight="400px">
        <CircularProgress />
      </Box>
    );
  }
  
  /**
   * Render error state if no data loaded
   */
  if (error && !accountData) {
    return (
      <Container maxWidth="lg">
        <Box mt={4}>
          <Alert severity="error">{error}</Alert>
          <Box mt={2}>
            <Button
              variant="contained"
              color="primary"
              startIcon={<ArrowBackIcon />}
              onClick={() => navigate('/account/list')}
            >
              Back to Account List
            </Button>
          </Box>
        </Box>
      </Container>
    );
  }
  
  /**
   * Main component render
   */
  return (
    <>
      {/* Header component - BMS lines 1-3 */}
      <Header
        transactionName="CAUP"
        programName="COACTUPC"
        title1="AWS Mainframe Modernization"
        title2="CardDemo - Update Account"
      />
      
      <Container maxWidth="lg" sx={{ mt: 4, mb: 4 }}>
        {/* Page Title - BMS line 4 */}
        <Typography variant="h4" component="h1" gutterBottom align="center" color="primary">
          Update Account
        </Typography>
        
        {/* Success Message */}
        {successMessage && (
          <Alert severity="success" sx={{ mb: 2 }} onClose={() => setSuccessMessage(null)}>
            {successMessage}
          </Alert>
        )}
        
        {/* Error Message - BMS line 23 ERRMSG field */}
        {error && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setError(null)}>
            {error}
          </Alert>
        )}
        
        <Paper elevation={3} sx={{ p: 3 }}>
          <form onSubmit={formik.handleSubmit}>
            {/* Account Details Section - BMS lines 5-11 */}
            <Typography variant="h6" gutterBottom color="primary">
              Account Details
            </Typography>
            
            <Grid container spacing={3}>
              {/* Account Number - BMS line 5 ACCTSID (UNPROT) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  disabled
                  id="accountId"
                  name="accountId"
                  label="Account Number"
                  value={formik.values.accountId}
                  InputProps={{
                    readOnly: true,
                  }}
                  helperText="Account ID cannot be changed"
                />
              </Grid>
              
              {/* Active Status - BMS line 5 ACSTTUS (UNPROT) */}
              <Grid item xs={12} md={6}>
                <FormControl component="fieldset" error={formik.touched.activeStatus && Boolean(formik.errors.activeStatus)}>
                  <FormLabel component="legend">Active Status *</FormLabel>
                  <RadioGroup
                    row
                    id="activeStatus"
                    name="activeStatus"
                    value={formik.values.activeStatus}
                    onChange={formik.handleChange}
                  >
                    <FormControlLabel value="Y" control={<Radio />} label="Yes" />
                    <FormControlLabel value="N" control={<Radio />} label="No" />
                  </RadioGroup>
                  {formik.touched.activeStatus && formik.errors.activeStatus && (
                    <FormHelperText>{formik.errors.activeStatus}</FormHelperText>
                  )}
                </FormControl>
              </Grid>
              
              {/* Account Open Date - BMS line 6 OPNYEAR/OPNMON/OPNDAY (ASKIP) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  disabled
                  id="openDate"
                  name="openDate"
                  label="Account Open Date"
                  value={formatDateDisplay(formik.values.openDate)}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* Credit Limit - BMS line 7 ACRDLIM (UNPROT) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  required
                  id="creditLimit"
                  name="creditLimit"
                  label="Credit Limit"
                  type="number"
                  value={formik.values.creditLimit}
                  onChange={formik.handleChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.creditLimit && Boolean(formik.errors.creditLimit)}
                  helperText={formik.touched.creditLimit && formik.errors.creditLimit}
                  InputProps={{
                    startAdornment: <InputAdornment position="start">$</InputAdornment>,
                  }}
                  inputProps={{
                    min: 1000,
                    max: 9999999999.99,
                    step: 0.01
                  }}
                />
              </Grid>
              
              {/* Expiration Date - BMS line 8 EXPYEAR/EXPMON/EXPDAY (UNPROT) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  required
                  id="expirationDate"
                  name="expirationDate"
                  label="Expiration Date"
                  type="date"
                  value={formik.values.expirationDate}
                  onChange={formik.handleChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.expirationDate && Boolean(formik.errors.expirationDate)}
                  helperText={formik.touched.expirationDate && formik.errors.expirationDate}
                  InputLabelProps={{
                    shrink: true,
                  }}
                />
              </Grid>
              
              {/* Cash Credit Limit - BMS line 9 ACSHLIM (UNPROT) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  required
                  id="cashCreditLimit"
                  name="cashCreditLimit"
                  label="Cash Credit Limit"
                  type="number"
                  value={formik.values.cashCreditLimit}
                  onChange={formik.handleChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.cashCreditLimit && Boolean(formik.errors.cashCreditLimit)}
                  helperText={formik.touched.cashCreditLimit && formik.errors.cashCreditLimit}
                  InputProps={{
                    startAdornment: <InputAdornment position="start">$</InputAdornment>,
                  }}
                  inputProps={{
                    min: 0,
                    max: 9999999999.99,
                    step: 0.01
                  }}
                />
              </Grid>
              
              {/* Reissue Date - BMS line 10 RISYEAR/RISMON/RISDAY (ASKIP) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  disabled
                  id="reissueDate"
                  name="reissueDate"
                  label="Reissue Date"
                  value={formatDateDisplay(formik.values.reissueDate)}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* Current Balance - BMS line 11 ACURBAL (ASKIP) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  disabled
                  id="currentBalance"
                  name="currentBalance"
                  label="Current Balance"
                  value={formatCurrency(formik.values.currentBalance)}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* Cycle Credit - BMS line 12 ACRCYCR (ASKIP) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  disabled
                  id="cycleCreditLimit"
                  name="cycleCreditLimit"
                  label="Cycle Credit"
                  value={formatCurrency(formik.values.cycleCreditLimit)}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* Cycle Debit - BMS line 12 ACRCYDB (ASKIP) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  disabled
                  id="cycleDebitLimit"
                  name="cycleDebitLimit"
                  label="Cycle Debit"
                  value={formatCurrency(formik.values.cycleDebitLimit)}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
            </Grid>
            
            <Divider sx={{ my: 3 }} />
            
            {/* Customer Details Section - BMS lines 13-22 (all ASKIP) */}
            <Typography variant="h6" gutterBottom color="primary">
              Customer Details
            </Typography>
            
            <Grid container spacing={3}>
              {/* Customer Number - BMS line 14 ACSTNUM (ASKIP) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  disabled
                  id="customerId"
                  name="customerId"
                  label="Customer Number"
                  value={formik.values.customerId}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* SSN - BMS line 15 ACTSSN1/ACTSSN2/ACTSSN3 (ASKIP) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  disabled
                  id="customerSSN"
                  name="customerSSN"
                  label="Social Security Number"
                  value={formatSSN(formik.values.customerSSN)}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* Date of Birth - BMS line 16 ADOBMON/ADOBDAY/ADOBYEAR (ASKIP) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  disabled
                  id="customerDOB"
                  name="customerDOB"
                  label="Date of Birth"
                  value={formatDateDisplay(formik.values.customerDOB)}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* FICO Score - BMS line 17 AFICOSC (ASKIP) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  disabled
                  id="customerFICO"
                  name="customerFICO"
                  label="FICO Credit Score"
                  value={formik.values.customerFICO}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* First Name - BMS line 18 ACSFNAM (ASKIP) */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  disabled
                  id="customerFirstName"
                  name="customerFirstName"
                  label="First Name"
                  value={formik.values.customerFirstName}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* Middle Name - BMS line 18 ACSMNAM (ASKIP) */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  disabled
                  id="customerMiddleName"
                  name="customerMiddleName"
                  label="Middle Name"
                  value={formik.values.customerMiddleName}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* Last Name - BMS line 18 ACSLNAM (ASKIP) */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  disabled
                  id="customerLastName"
                  name="customerLastName"
                  label="Last Name"
                  value={formik.values.customerLastName}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* Address Line 1 - BMS line 19 ACSADR1 (ASKIP) */}
              <Grid item xs={12}>
                <TextField
                  fullWidth
                  disabled
                  id="customerAddress1"
                  name="customerAddress1"
                  label="Address Line 1"
                  value={formik.values.customerAddress1}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* Address Line 2 - BMS line 19 ACSADR2 (ASKIP) */}
              <Grid item xs={12}>
                <TextField
                  fullWidth
                  disabled
                  id="customerAddress2"
                  name="customerAddress2"
                  label="Address Line 2"
                  value={formik.values.customerAddress2}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* City - BMS line 20 ACSCITY (ASKIP) */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  disabled
                  id="customerCity"
                  name="customerCity"
                  label="City"
                  value={formik.values.customerCity}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* State - BMS line 20 ACSSTAT (ASKIP) */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  disabled
                  id="customerState"
                  name="customerState"
                  label="State"
                  value={formik.values.customerState}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* ZIP Code - BMS line 20 ACSZIPCD (ASKIP) */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  disabled
                  id="customerZip"
                  name="customerZip"
                  label="ZIP Code"
                  value={formik.values.customerZip}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* Phone 1 - BMS line 21 ACSPHN1 (ASKIP) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  disabled
                  id="customerPhone1"
                  name="customerPhone1"
                  label="Phone Number 1"
                  value={formik.values.customerPhone1}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* Phone 2 - BMS line 21 ACSPHN2 (ASKIP) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  disabled
                  id="customerPhone2"
                  name="customerPhone2"
                  label="Phone Number 2"
                  value={formik.values.customerPhone2}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* Government ID - BMS line 22 ACSGVID (ASKIP) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  disabled
                  id="customerGovtId"
                  name="customerGovtId"
                  label="Government Issued ID"
                  value={formik.values.customerGovtId}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
              
              {/* EFT Account - BMS line 22 ACSEFTACC (ASKIP) */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  disabled
                  id="customerEFTAccount"
                  name="customerEFTAccount"
                  label="EFT Account"
                  value={formik.values.customerEFTAccount}
                  InputProps={{
                    readOnly: true,
                  }}
                />
              </Grid>
            </Grid>
            
            <Divider sx={{ my: 3 }} />
            
            {/* Action Buttons */}
            <Box display="flex" justifyContent="space-between" flexWrap="wrap" gap={2}>
              <Button
                variant="outlined"
                color="secondary"
                startIcon={<ArrowBackIcon />}
                onClick={handleBack}
                disabled={submitting}
              >
                Back
              </Button>
              
              <Box display="flex" gap={2}>
                <Button
                  variant="outlined"
                  color="error"
                  startIcon={<CancelIcon />}
                  onClick={handleCancel}
                  disabled={submitting || !formik.dirty}
                >
                  Cancel
                </Button>
                
                <Button
                  type="submit"
                  variant="contained"
                  color="primary"
                  startIcon={submitting ? <CircularProgress size={20} /> : <SaveIcon />}
                  disabled={submitting || !formik.dirty || !formik.isValid}
                >
                  {submitting ? 'Saving...' : 'Save Changes'}
                </Button>
              </Box>
            </Box>
          </form>
        </Paper>
      </Container>
      
      {/* Confirmation Dialog */}
      <Dialog
        open={showConfirmDialog}
        onClose={handleDialogCancel}
        aria-labelledby="confirm-dialog-title"
        aria-describedby="confirm-dialog-description"
      >
        <DialogTitle id="confirm-dialog-title">
          Confirm Account Update
        </DialogTitle>
        <DialogContent>
          <DialogContentText id="confirm-dialog-description">
            Are you sure you want to update this account? This action will modify the account's credit limits and status.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={handleDialogCancel} color="secondary" disabled={submitting}>
            Cancel
          </Button>
          <Button onClick={handleDialogConfirm} color="primary" variant="contained" disabled={submitting} autoFocus>
            Confirm
          </Button>
        </DialogActions>
      </Dialog>
      
      {/* Footer component - BMS line 24 */}
      <Footer
        helpText="Update account credit limits and status"
        showF3={true}
        showEnter={true}
        onBack={handleBack}
        onSubmit={formik.handleSubmit}
      />
    </>
  );
};

// PropTypes for better type checking
AccountUpdateComponent.propTypes = {};

// Export as default
export default AccountUpdateComponent;

