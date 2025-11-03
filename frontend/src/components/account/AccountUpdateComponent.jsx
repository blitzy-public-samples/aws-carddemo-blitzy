/**
 * AccountUpdateComponent
 * 
 * React functional component for editing existing credit card account information,
 * transforming COACTUP BMS mapset (mapset COACTUP / DFHMDI CACTUPA) from COBOL
 * COACTUPC.cbl program into a modern Material-UI form interface.
 * 
 * Transformation Context:
 * Maps COBOL COACTUPC.cbl account update transaction to React component with
 * Material-UI form, Formik validation, and REST API integration. Preserves all
 * COBOL field validation rules and business logic while modernizing the user
 * interface from 3270 terminal screen to responsive web form.
 * 
 * COBOL Source Program:
 * - COACTUPC.cbl: Account update program with field validation and VSAM update
 * - COACTUP.bms: 3270 BMS mapset defining screen layout and field attributes
 * - COACTUP.CPY: Generated copybook with field definitions
 * 
 * BMS Screen Mapping (3270 → Material-UI):
 * - ACCTSID (POS 5,38, IC, UNPROT) → TextField (disabled) for account lookup
 * - ACSTTUS (POS 5,70, UNPROT) → Select dropdown for Active Y/N
 * - OPNYEAR/OPNMON/OPNDAY → Separate TextField components for date entry
 * - ACRDLIM (POS 6,61, FSET, UNPROT) → TextField with number validation
 * - EXPYEAR/EXPMON/EXPDAY → Separate TextField components for date entry
 * - ACSHLIM (POS 7,61, UNPROT) → TextField for cash credit limit
 * - RISYEAR/RISMON/RISDAY → Optional separate TextField components
 * - ACURBAL (POS 8,61, read-only) → TextField (disabled) with currency format
 * - ACRCYCR/ACRCYDB (read-only) → TextField (disabled) for cycle totals
 * - AADDGRP (POS 10,23, UNPROT) → TextField for account group
 * - Customer fields → All disabled TextFields for display-only data
 * 
 * Field Attribute Transformations:
 * - ATTRB=IC (initial cursor) → autoFocus={true}
 * - ATTRB=UNPROT (unprotected/editable) → enabled Material-UI TextField
 * - ATTRB=PROT (protected/display-only) → disabled={true}
 * - HILIGHT=UNDERLINE → Material-UI variant="outlined" with focus styling
 * - ATTRB=FSET (field set) → Formik field management
 * 
 * COBOL Validation Mappings:
 * - WS-EDIT-ACCT-STATUS (88 VALUES 'Y', 'N') → Yup oneOf(['Y', 'N'])
 * - WS-EDIT-OPEN-DATE-FLGS → Yup date validation (year 4 digits, month 1-12, day 1-31)
 * - WS-EDIT-CREDIT-LIMIT → Yup number with min(0) and max(999999999.99)
 * - WS-EXPIRY-DATE-FLGS → Yup date validation for expiry fields
 * - WS-EDIT-REISSUE-DATE-FLGS → Optional Yup date validation
 * - WS-EDIT-CASH-CREDIT-LIMIT → Yup number with min(0) and max(creditLimit)
 * 
 * REST API Integration:
 * - GET /api/accounts/{accountId} → Fetch account data for form initialization
 * - PUT /api/accounts/{accountId} → Submit account updates
 * 
 * Business Logic Preservation:
 * - COMP-3 decimal precision maintained via toFixed(2) for currency fields
 * - Date component validation matches COBOL CCYYMMDD format checks
 * - Field validation errors displayed inline matching BMS ERRMSG field
 * - Success/error notifications replace BMS INFOMSG field
 * 
 * Navigation:
 * - FKEY05 (F5=Save) → Save button triggering form submission
 * - FKEY12 (F12=Cancel) → Cancel button navigating back to account view
 * - FKEY03 (F3=Exit) → Implicit via Cancel button
 * 
 * @component
 * @example
 * // Usage in React Router
 * <Route path="/accounts/:accountId/edit" element={<AccountUpdateComponent />} />
 */

import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Formik, Form, Field } from 'formik';
import * as Yup from 'yup';
import {
  Box,
  Button,
  TextField,
  Typography,
  Paper,
  Grid,
  MenuItem,
  CircularProgress,
  Divider
} from '@mui/material';
import { toast } from 'react-toastify';
import accountService from '../../services/accountService';

/**
 * Validation Schema
 * 
 * Yup validation schema matching COBOL COACTUPC.cbl field validation rules.
 * Preserves all business logic from COBOL WS-NON-KEY-FLAGS and date validation
 * flags, ensuring identical validation behavior in React form.
 * 
 * COBOL Validation Mappings:
 * - accountStatus: 88 FLG-ACCT-STATUS-ISVALID VALUES 'Y', 'N'
 * - openYear: WS-EDIT-OPEN-YEAR-FLG (4 digits, 1900-2099)
 * - openMonth: WS-EDIT-OPEN-MONTH (1-12)
 * - openDay: WS-EDIT-OPEN-DAY (1-31)
 * - creditLimit: WS-EDIT-CREDIT-LIMIT (positive, max 999999999.99)
 * - expiryYear/Month/Day: WS-EXPIRY-DATE-FLGS validation
 * - cashCreditLimit: WS-EDIT-CASH-CREDIT-LIMIT (positive, ≤ creditLimit)
 * - reissueYear/Month/Day: WS-EDIT-REISSUE-DATE-FLGS (optional)
 * - accountGroup: String max 10 chars (from AADDGRP LENGTH=10)
 */
const validationSchema = Yup.object({
  accountStatus: Yup.string()
    .required('Account status is required')
    .oneOf(['Y', 'N'], 'Account status must be Y (Active) or N (Inactive)'),
  
  openYear: Yup.number()
    .required('Open year is required')
    .integer('Year must be a whole number')
    .min(1900, 'Year must be 1900 or later')
    .max(2099, 'Year must be 2099 or earlier')
    .test('len', 'Year must be 4 digits', val => val && val.toString().length === 4),
  
  openMonth: Yup.number()
    .required('Open month is required')
    .integer('Month must be a whole number')
    .min(1, 'Month must be between 1 and 12')
    .max(12, 'Month must be between 1 and 12'),
  
  openDay: Yup.number()
    .required('Open day is required')
    .integer('Day must be a whole number')
    .min(1, 'Day must be between 1 and 31')
    .max(31, 'Day must be between 1 and 31'),
  
  creditLimit: Yup.number()
    .required('Credit limit is required')
    .min(0, 'Credit limit must be positive')
    .max(999999999.99, 'Credit limit exceeds maximum allowed')
    .test('decimal', 'Credit limit must have at most 2 decimal places', val => {
      if (val === undefined || val === null) return true;
      return /^\d+(\.\d{1,2})?$/.test(val.toString());
    }),
  
  expiryYear: Yup.number()
    .required('Expiry year is required')
    .integer('Year must be a whole number')
    .min(1900, 'Year must be 1900 or later')
    .max(2099, 'Year must be 2099 or earlier')
    .test('len', 'Year must be 4 digits', val => val && val.toString().length === 4),
  
  expiryMonth: Yup.number()
    .required('Expiry month is required')
    .integer('Month must be a whole number')
    .min(1, 'Month must be between 1 and 12')
    .max(12, 'Month must be between 1 and 12'),
  
  expiryDay: Yup.number()
    .required('Expiry day is required')
    .integer('Day must be a whole number')
    .min(1, 'Day must be between 1 and 31')
    .max(31, 'Day must be between 1 and 31'),
  
  cashCreditLimit: Yup.number()
    .nullable()
    .min(0, 'Cash credit limit must be positive')
    .test('max-credit-limit', 'Cash credit limit cannot exceed credit limit', function(value) {
      if (value === undefined || value === null) return true;
      const { creditLimit } = this.parent;
      return value <= creditLimit;
    })
    .test('decimal', 'Cash credit limit must have at most 2 decimal places', val => {
      if (val === undefined || val === null) return true;
      return /^\d+(\.\d{1,2})?$/.test(val.toString());
    }),
  
  reissueYear: Yup.number()
    .nullable()
    .integer('Year must be a whole number')
    .min(1900, 'Year must be 1900 or later')
    .max(2099, 'Year must be 2099 or earlier')
    .test('len', 'Year must be 4 digits', val => {
      if (!val) return true;
      return val.toString().length === 4;
    }),
  
  reissueMonth: Yup.number()
    .nullable()
    .integer('Month must be a whole number')
    .min(1, 'Month must be between 1 and 12')
    .max(12, 'Month must be between 1 and 12'),
  
  reissueDay: Yup.number()
    .nullable()
    .integer('Day must be a whole number')
    .min(1, 'Day must be between 1 and 31')
    .max(31, 'Day must be between 1 and 31'),
  
  accountGroup: Yup.string()
    .nullable()
    .max(10, 'Account group must be 10 characters or less')
});

/**
 * Format currency value for display
 * 
 * Preserves COBOL COMP-3 decimal precision by formatting to 2 decimal places.
 * Maps COBOL currency display logic for read-only balance fields.
 * 
 * @param {number} value - Numeric value to format
 * @returns {string} Formatted currency string with 2 decimal places
 */
const formatCurrency = (value) => {
  if (value === undefined || value === null || isNaN(value)) {
    return '0.00';
  }
  return Number(value).toFixed(2);
};

/**
 * AccountUpdateComponent
 * 
 * Main functional component implementing account update form.
 * Manages form state, data fetching, validation, and submission.
 */
const AccountUpdateComponent = () => {
  // React Router hooks for navigation and URL parameters
  const params = useParams();
  const navigate = useNavigate();
  
  // Component state management
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [account, setAccount] = useState(null);
  const [initialValues, setInitialValues] = useState({
    accountId: '',
    accountStatus: 'Y',
    openYear: '',
    openMonth: '',
    openDay: '',
    creditLimit: '',
    expiryYear: '',
    expiryMonth: '',
    expiryDay: '',
    cashCreditLimit: '',
    reissueYear: '',
    reissueMonth: '',
    reissueDay: '',
    currentBalance: 0,
    currentCycleCredit: 0,
    currentCycleDebit: 0,
    accountGroup: '',
    // Customer details (read-only)
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
    customerCountry: '',
    customerPhone1: '',
    customerPhone2: '',
    customerGovtID: '',
    customerEFT: '',
    customerPrimary: ''
  });

  /**
   * Fetch Account Data on Component Mount
   * 
   * Maps COBOL COACTUPC.cbl 9300-GETACCTDATA-BYACCT paragraph.
   * Retrieves account data from backend and parses dates into separate
   * year/month/day components for form fields.
   * 
   * COBOL Equivalent:
   * ```cobol
   * 9300-GETACCTDATA-BYACCT.
   *     EXEC CICS READ
   *         DATASET('ACCTDAT')
   *         RIDFLD(ACCTSIDI)
   *         INTO(ACCOUNT-RECORD)
   *         RESP(WS-RESP-CD)
   *         RESP2(WS-REAS-CD)
   *     END-EXEC.
   * ```
   */
  useEffect(() => {
    const fetchAccount = async () => {
      try {
        setLoading(true);
        setError(null);
        
        // Extract accountId from URL parameters
        const accountId = params.accountId;
        
        // Validate account ID is present
        // Maps COBOL: 2210-EDIT-ACCOUNT validation
        if (!accountId) {
          throw new Error('Account ID is required');
        }
        
        // Call accountService.getAccount() to retrieve account data
        // Maps COBOL: EXEC CICS READ DATASET('ACCTDAT')
        const data = await accountService.getAccount(accountId);
        setAccount(data);
        
        // Parse date fields into separate year/month/day components
        // Maps COBOL date field parsing from CCYYMMDD format
        
        // Parse openDate (YYYY-MM-DD) → separate components
        let openYear = '', openMonth = '', openDay = '';
        if (data.openDate) {
          const openDateParts = data.openDate.split('-');
          if (openDateParts.length === 3) {
            openYear = parseInt(openDateParts[0], 10);
            openMonth = parseInt(openDateParts[1], 10);
            openDay = parseInt(openDateParts[2], 10);
          }
        }
        
        // Parse expiryDate (YYYY-MM-DD) → separate components
        let expiryYear = '', expiryMonth = '', expiryDay = '';
        if (data.expiryDate) {
          const expiryDateParts = data.expiryDate.split('-');
          if (expiryDateParts.length === 3) {
            expiryYear = parseInt(expiryDateParts[0], 10);
            expiryMonth = parseInt(expiryDateParts[1], 10);
            expiryDay = parseInt(expiryDateParts[2], 10);
          }
        }
        
        // Parse reissueDate (YYYY-MM-DD) → separate components (optional)
        let reissueYear = '', reissueMonth = '', reissueDay = '';
        if (data.reissueDate) {
          const reissueDateParts = data.reissueDate.split('-');
          if (reissueDateParts.length === 3) {
            reissueYear = parseInt(reissueDateParts[0], 10);
            reissueMonth = parseInt(reissueDateParts[1], 10);
            reissueDay = parseInt(reissueDateParts[2], 10);
          }
        }
        
        // Set initial form values with parsed data
        // Maps all BMS fields to Formik initial values
        setInitialValues({
          accountId: data.accountId || '',
          accountStatus: data.accountStatus || 'Y',
          openYear,
          openMonth,
          openDay,
          creditLimit: data.creditLimit || '',
          expiryYear,
          expiryMonth,
          expiryDay,
          cashCreditLimit: data.cashCreditLimit || '',
          reissueYear,
          reissueMonth,
          reissueDay,
          currentBalance: data.currentBalance || 0,
          currentCycleCredit: data.currentCycleCredit || 0,
          currentCycleDebit: data.currentCycleDebit || 0,
          accountGroup: data.accountGroup || '',
          // Customer details from nested customer object or account data
          customerId: data.customerId || data.customer?.customerId || '',
          customerFirstName: data.customer?.firstName || '',
          customerMiddleName: data.customer?.middleName || '',
          customerLastName: data.customer?.lastName || '',
          customerSSN: data.customer?.ssn || '',
          customerDOB: data.customer?.dateOfBirth || '',
          customerFICO: data.customer?.ficoScore || '',
          customerAddress1: data.customer?.addressLine1 || '',
          customerAddress2: data.customer?.addressLine2 || '',
          customerCity: data.customer?.city || '',
          customerState: data.customer?.state || '',
          customerZip: data.customer?.zipCode || '',
          customerCountry: data.customer?.country || '',
          customerPhone1: data.customer?.phone1 || '',
          customerPhone2: data.customer?.phone2 || '',
          customerGovtID: data.customer?.governmentId || '',
          customerEFT: data.customer?.eftAccountId || '',
          customerPrimary: data.customer?.primaryCardHolder || ''
        });
        
      } catch (err) {
        // Handle errors matching COBOL error handling patterns
        // Maps COBOL: 9300-GETACCTDATA-BYACCT-EXIT error conditions
        const errorMessage = err.message || 'Failed to load account';
        setError(errorMessage);
        toast.error(errorMessage);
      } finally {
        setLoading(false);
      }
    };
    
    fetchAccount();
  }, [params.accountId]);

  /**
   * Handle Form Submission
   * 
   * Maps COBOL COACTUPC.cbl account update logic with validation and
   * VSAM REWRITE operation. Reconstructs dates from separate components
   * and formats currency values to 2 decimal places before API call.
   * 
   * COBOL Equivalent:
   * ```cobol
   * 2000-UPDATE-ACCOUNT-INFO.
   *     PERFORM 2210-EDIT-ACCOUNT-FIELDS.
   *     IF INPUT-OK THEN
   *         EXEC CICS REWRITE
   *             DATASET('ACCTDAT')
   *             FROM(ACCOUNT-RECORD)
   *             RESP(WS-RESP-CD)
   *         END-EXEC
   *         PERFORM 2300-SEND-ACCOUNT-SCREEN
   *     END-IF.
   * ```
   * 
   * @param {Object} values - Form values from Formik
   * @param {Object} formikBag - Formik helper methods
   */
  const handleSubmit = async (values, { setSubmitting, setErrors }) => {
    try {
      // Reconstruct dates from separate year/month/day components
      // Maps COBOL date field assembly into CCYYMMDD format
      
      // Assemble openDate: YYYY-MM-DD
      const openDateStr = `${values.openYear}-${String(values.openMonth).padStart(2, '0')}-${String(values.openDay).padStart(2, '0')}`;
      
      // Assemble expiryDate: YYYY-MM-DD
      const expiryDateStr = `${values.expiryYear}-${String(values.expiryMonth).padStart(2, '0')}-${String(values.expiryDay).padStart(2, '0')}`;
      
      // Assemble reissueDate: YYYY-MM-DD (optional)
      let reissueDateStr = null;
      if (values.reissueYear && values.reissueMonth && values.reissueDay) {
        reissueDateStr = `${values.reissueYear}-${String(values.reissueMonth).padStart(2, '0')}-${String(values.reissueDay).padStart(2, '0')}`;
      }
      
      // Format currency values to 2 decimal places
      // Preserves COBOL COMP-3 decimal precision
      const accountData = {
        accountStatus: values.accountStatus,
        creditLimit: parseFloat(values.creditLimit).toFixed(2),
        cashCreditLimit: values.cashCreditLimit ? parseFloat(values.cashCreditLimit).toFixed(2) : null,
        openDate: openDateStr,
        expiryDate: expiryDateStr,
        reissueDate: reissueDateStr,
        accountGroup: values.accountGroup || null
      };
      
      // Call accountService.updateAccount() to submit changes
      // Maps COBOL: EXEC CICS REWRITE DATASET('ACCTDAT')
      await accountService.updateAccount(params.accountId, accountData);
      
      // Display success notification
      // Maps BMS INFOMSG field: "Account updated successfully"
      toast.success('Account updated successfully!');
      
      // Navigate back to account view on success
      // Maps COBOL: EXEC CICS XCTL PROGRAM('COACTVWC')
      navigate(`/accounts/${params.accountId}`);
      
    } catch (error) {
      // Handle validation and server errors
      // Maps COBOL error handling with BMS ERRMSG field display
      
      // Display error toast notification
      toast.error(error.message || 'Failed to update account');
      
      // Set field-specific errors if provided by backend
      // Maps COBOL field validation flag errors (FLG-*-NOT-OK)
      if (error.errors && typeof error.errors === 'object') {
        setErrors(error.errors);
      }
      
    } finally {
      setSubmitting(false);
    }
  };

  /**
   * Handle Cancel Button Click
   * 
   * Navigate back to account view without saving changes.
   * Maps BMS FKEY12 (F12=Cancel) function key.
   * 
   * COBOL Equivalent:
   * ```cobol
   * WHEN DFHAID(PF12)
   *     EXEC CICS XCTL PROGRAM('COACTVWC')
   *         COMMAREA(CARDDEMO-COMMAREA)
   *     END-EXEC
   * END-EVALUATE.
   * ```
   */
  const handleCancel = () => {
    navigate(`/accounts/${params.accountId}`);
  };

  /**
   * Loading State Display
   * 
   * Shows circular progress indicator while fetching account data.
   * Maps COBOL "Please wait..." message display during file I/O.
   */
  if (loading) {
    return (
      <Box 
        display="flex" 
        justifyContent="center" 
        alignItems="center" 
        minHeight="400px"
      >
        <CircularProgress />
      </Box>
    );
  }

  /**
   * Error State Display
   * 
   * Shows error message if account fetch failed.
   * Maps BMS ERRMSG field display (POS 23,1, COLOR=RED).
   */
  if (error) {
    return (
      <Box 
        display="flex" 
        justifyContent="center" 
        alignItems="center" 
        minHeight="400px"
      >
        <Paper elevation={3} sx={{ p: 4, maxWidth: 600 }}>
          <Typography variant="h6" color="error" gutterBottom>
            Error Loading Account
          </Typography>
          <Typography variant="body1" color="text.secondary">
            {error}
          </Typography>
          <Box mt={3}>
            <Button 
              variant="contained" 
              onClick={() => navigate('/accounts')}
            >
              Return to Account List
            </Button>
          </Box>
        </Paper>
      </Box>
    );
  }

  /**
   * Main Form Render
   * 
   * Renders Material-UI form with Formik integration.
   * Maps complete BMS COACTUP screen layout to responsive web form.
   */
  return (
    <Box sx={{ maxWidth: 1200, margin: '0 auto', p: 3 }}>
      <Paper elevation={3} sx={{ p: 4 }}>
        {/* Screen Title */}
        {/* Maps BMS POS=(4,33) INITIAL='Update Account' */}
        <Typography variant="h4" component="h1" gutterBottom align="center">
          Update Account
        </Typography>
        
        <Divider sx={{ my: 3 }} />
        
        {/* Formik Form */}
        <Formik
          initialValues={initialValues}
          validationSchema={validationSchema}
          onSubmit={handleSubmit}
          enableReinitialize={true}
        >
          {({ values, errors, touched, isSubmitting, setFieldValue }) => (
            <Form>
              {/* Account Information Section */}
              <Typography variant="h6" gutterBottom color="primary">
                Account Information
              </Typography>
              
              <Grid container spacing={3}>
                {/* Account Number (Read-Only) */}
                {/* Maps BMS ACCTSID: POS=(5,38), LENGTH=11, ATTRB=(IC,UNPROT) */}
                {/* Displayed as disabled field instead of editable per requirements */}
                <Grid item xs={12} md={6}>
                  <TextField
                    label="Account Number"
                    name="accountId"
                    value={values.accountId}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* Account Status (Active Y/N) */}
                {/* Maps BMS ACSTTUS: POS=(5,70), LENGTH=1, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={6}>
                  <Field
                    as={TextField}
                    select
                    label="Active Y/N"
                    name="accountStatus"
                    fullWidth
                    variant="outlined"
                    error={touched.accountStatus && Boolean(errors.accountStatus)}
                    helperText={touched.accountStatus && errors.accountStatus}
                  >
                    <MenuItem value="Y">Y - Active</MenuItem>
                    <MenuItem value="N">N - Inactive</MenuItem>
                  </Field>
                </Grid>
                
                {/* Opened Date (Year-Month-Day) */}
                {/* Maps BMS OPNYEAR/OPNMON/OPNDAY: POS=(6,17)-(6,29) */}
                <Grid item xs={12} md={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Opened Date
                  </Typography>
                  <Grid container spacing={1}>
                    <Grid item xs={5}>
                      <Field
                        as={TextField}
                        label="Year"
                        name="openYear"
                        type="number"
                        variant="outlined"
                        fullWidth
                        error={touched.openYear && Boolean(errors.openYear)}
                        helperText={touched.openYear && errors.openYear}
                        InputProps={{ inputProps: { min: 1900, max: 2099 } }}
                      />
                    </Grid>
                    <Grid item xs={3}>
                      <Field
                        as={TextField}
                        label="Month"
                        name="openMonth"
                        type="number"
                        variant="outlined"
                        fullWidth
                        error={touched.openMonth && Boolean(errors.openMonth)}
                        helperText={touched.openMonth && errors.openMonth}
                        InputProps={{ inputProps: { min: 1, max: 12 } }}
                      />
                    </Grid>
                    <Grid item xs={4}>
                      <Field
                        as={TextField}
                        label="Day"
                        name="openDay"
                        type="number"
                        variant="outlined"
                        fullWidth
                        error={touched.openDay && Boolean(errors.openDay)}
                        helperText={touched.openDay && errors.openDay}
                        InputProps={{ inputProps: { min: 1, max: 31 } }}
                      />
                    </Grid>
                  </Grid>
                </Grid>
                
                {/* Credit Limit */}
                {/* Maps BMS ACRDLIM: POS=(6,61), LENGTH=15, ATTRB=(FSET,UNPROT) */}
                <Grid item xs={12} md={6}>
                  <Field
                    as={TextField}
                    label="Credit Limit"
                    name="creditLimit"
                    type="number"
                    fullWidth
                    variant="outlined"
                    InputProps={{ inputProps: { step: '0.01', min: 0 } }}
                    error={touched.creditLimit && Boolean(errors.creditLimit)}
                    helperText={touched.creditLimit && errors.creditLimit}
                  />
                </Grid>
                
                {/* Expiry Date (Year-Month-Day) */}
                {/* Maps BMS EXPYEAR/EXPMON/EXPDAY: POS=(7,17)-(7,29) */}
                <Grid item xs={12} md={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Expiry Date
                  </Typography>
                  <Grid container spacing={1}>
                    <Grid item xs={5}>
                      <Field
                        as={TextField}
                        label="Year"
                        name="expiryYear"
                        type="number"
                        variant="outlined"
                        fullWidth
                        error={touched.expiryYear && Boolean(errors.expiryYear)}
                        helperText={touched.expiryYear && errors.expiryYear}
                        InputProps={{ inputProps: { min: 1900, max: 2099 } }}
                      />
                    </Grid>
                    <Grid item xs={3}>
                      <Field
                        as={TextField}
                        label="Month"
                        name="expiryMonth"
                        type="number"
                        variant="outlined"
                        fullWidth
                        error={touched.expiryMonth && Boolean(errors.expiryMonth)}
                        helperText={touched.expiryMonth && errors.expiryMonth}
                        InputProps={{ inputProps: { min: 1, max: 12 } }}
                      />
                    </Grid>
                    <Grid item xs={4}>
                      <Field
                        as={TextField}
                        label="Day"
                        name="expiryDay"
                        type="number"
                        variant="outlined"
                        fullWidth
                        error={touched.expiryDay && Boolean(errors.expiryDay)}
                        helperText={touched.expiryDay && errors.expiryDay}
                        InputProps={{ inputProps: { min: 1, max: 31 } }}
                      />
                    </Grid>
                  </Grid>
                </Grid>
                
                {/* Cash Credit Limit */}
                {/* Maps BMS ACSHLIM: POS=(7,61), LENGTH=15, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={6}>
                  <Field
                    as={TextField}
                    label="Cash Credit Limit"
                    name="cashCreditLimit"
                    type="number"
                    fullWidth
                    variant="outlined"
                    InputProps={{ inputProps: { step: '0.01', min: 0 } }}
                    error={touched.cashCreditLimit && Boolean(errors.cashCreditLimit)}
                    helperText={touched.cashCreditLimit && errors.cashCreditLimit}
                  />
                </Grid>
                
                {/* Reissue Date (Year-Month-Day, Optional) */}
                {/* Maps BMS RISYEAR/RISMON/RISDAY: POS=(8,17)-(8,29) */}
                <Grid item xs={12} md={6}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Reissue Date (Optional)
                  </Typography>
                  <Grid container spacing={1}>
                    <Grid item xs={5}>
                      <Field
                        as={TextField}
                        label="Year"
                        name="reissueYear"
                        type="number"
                        variant="outlined"
                        fullWidth
                        error={touched.reissueYear && Boolean(errors.reissueYear)}
                        helperText={touched.reissueYear && errors.reissueYear}
                        InputProps={{ inputProps: { min: 1900, max: 2099 } }}
                      />
                    </Grid>
                    <Grid item xs={3}>
                      <Field
                        as={TextField}
                        label="Month"
                        name="reissueMonth"
                        type="number"
                        variant="outlined"
                        fullWidth
                        error={touched.reissueMonth && Boolean(errors.reissueMonth)}
                        helperText={touched.reissueMonth && errors.reissueMonth}
                        InputProps={{ inputProps: { min: 1, max: 12 } }}
                      />
                    </Grid>
                    <Grid item xs={4}>
                      <Field
                        as={TextField}
                        label="Day"
                        name="reissueDay"
                        type="number"
                        variant="outlined"
                        fullWidth
                        error={touched.reissueDay && Boolean(errors.reissueDay)}
                        helperText={touched.reissueDay && errors.reissueDay}
                        InputProps={{ inputProps: { min: 1, max: 31 } }}
                      />
                    </Grid>
                  </Grid>
                </Grid>
                
                {/* Current Balance (Read-Only) */}
                {/* Maps BMS ACURBAL: POS=(8,61), LENGTH=15, ATTRB=(FSET,UNPROT) */}
                {/* Displayed as read-only per business requirements */}
                <Grid item xs={12} md={6}>
                  <TextField
                    label="Current Balance"
                    value={formatCurrency(values.currentBalance)}
                    disabled
                    fullWidth
                    variant="outlined"
                    InputProps={{
                      startAdornment: '$'
                    }}
                  />
                </Grid>
                
                {/* Current Cycle Credit (Read-Only) */}
                {/* Maps BMS ACRCYCR: POS=(9,61), LENGTH=15, ATTRB=(FSET,UNPROT) */}
                <Grid item xs={12} md={6}>
                  <TextField
                    label="Current Cycle Credit"
                    value={formatCurrency(values.currentCycleCredit)}
                    disabled
                    fullWidth
                    variant="outlined"
                    InputProps={{
                      startAdornment: '$'
                    }}
                  />
                </Grid>
                
                {/* Account Group */}
                {/* Maps BMS AADDGRP: POS=(10,23), LENGTH=10, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={6}>
                  <Field
                    as={TextField}
                    label="Account Group"
                    name="accountGroup"
                    fullWidth
                    variant="outlined"
                    error={touched.accountGroup && Boolean(errors.accountGroup)}
                    helperText={touched.accountGroup && errors.accountGroup}
                  />
                </Grid>
                
                {/* Current Cycle Debit (Read-Only) */}
                {/* Maps BMS ACRCYDB: POS=(10,61), LENGTH=15, ATTRB=(FSET,UNPROT) */}
                <Grid item xs={12} md={6}>
                  <TextField
                    label="Current Cycle Debit"
                    value={formatCurrency(values.currentCycleDebit)}
                    disabled
                    fullWidth
                    variant="outlined"
                    InputProps={{
                      startAdornment: '$'
                    }}
                  />
                </Grid>
              </Grid>
              
              <Divider sx={{ my: 4 }} />
              
              {/* Customer Details Section (All Read-Only) */}
              {/* Maps BMS Customer Details section starting at POS=(11,32) */}
              <Typography variant="h6" gutterBottom color="primary">
                Customer Details
              </Typography>
              
              <Grid container spacing={3}>
                {/* Customer ID */}
                {/* Maps BMS ACSTNUM: POS=(12,23), LENGTH=9, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={6}>
                  <TextField
                    label="Customer ID"
                    value={values.customerId}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* SSN */}
                {/* Maps BMS ACTSSN1/2/3: POS=(12,55)-(12,66), ATTRB=(UNPROT) */}
                <Grid item xs={12} md={6}>
                  <TextField
                    label="SSN"
                    value={values.customerSSN}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* Date of Birth */}
                {/* Maps BMS DOBYEAR/DOBMON/DOBDAY: POS=(13,23)-(13,35) */}
                <Grid item xs={12} md={6}>
                  <TextField
                    label="Date of Birth"
                    value={values.customerDOB}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* FICO Score */}
                {/* Maps BMS ACSTFCO: POS=(13,62), LENGTH=3, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={6}>
                  <TextField
                    label="FICO Score"
                    value={values.customerFICO}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* First Name */}
                {/* Maps BMS ACSFNAM: POS=(15,1), LENGTH=25, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={4}>
                  <TextField
                    label="First Name"
                    value={values.customerFirstName}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* Middle Name */}
                {/* Maps BMS ACSMNAM: POS=(15,28), LENGTH=25, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={4}>
                  <TextField
                    label="Middle Name"
                    value={values.customerMiddleName}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* Last Name */}
                {/* Maps BMS ACSLNAM: POS=(15,55), LENGTH=25, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={4}>
                  <TextField
                    label="Last Name"
                    value={values.customerLastName}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* Address Line 1 */}
                {/* Maps BMS ACSADL1: POS=(16,10), LENGTH=50, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={9}>
                  <TextField
                    label="Address Line 1"
                    value={values.customerAddress1}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* State */}
                {/* Maps BMS ACSSTTE: POS=(16,73), LENGTH=2, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={3}>
                  <TextField
                    label="State"
                    value={values.customerState}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* Address Line 2 */}
                {/* Maps BMS ACSADL2: POS=(17,10), LENGTH=50, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={9}>
                  <TextField
                    label="Address Line 2"
                    value={values.customerAddress2}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* Zip Code */}
                {/* Maps BMS ACSZIPC: POS=(17,73), LENGTH=5, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={3}>
                  <TextField
                    label="Zip"
                    value={values.customerZip}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* City */}
                {/* Maps BMS ACSCITY: POS=(18,10), LENGTH=50, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={9}>
                  <TextField
                    label="City"
                    value={values.customerCity}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* Country */}
                {/* Maps BMS ACSCTRY: POS=(18,73), LENGTH=3, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={3}>
                  <TextField
                    label="Country"
                    value={values.customerCountry}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* Phone 1 */}
                {/* Maps BMS ACSPH1A/B/C: POS=(19,10)-(19,18) */}
                <Grid item xs={12} md={6}>
                  <TextField
                    label="Phone 1"
                    value={values.customerPhone1}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* Government Issued ID */}
                {/* Maps BMS ACSGOVT: POS=(19,58), LENGTH=20, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={6}>
                  <TextField
                    label="Government Issued ID Ref"
                    value={values.customerGovtID}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* Phone 2 */}
                {/* Maps BMS ACSPH2A/B/C: POS=(20,10)-(20,18) */}
                <Grid item xs={12} md={4}>
                  <TextField
                    label="Phone 2"
                    value={values.customerPhone2}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* EFT Account ID */}
                {/* Maps BMS ACSEFTC: POS=(20,41), LENGTH=10, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={4}>
                  <TextField
                    label="EFT Account ID"
                    value={values.customerEFT}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
                
                {/* Primary Card Holder Y/N */}
                {/* Maps BMS ACSPFLG: POS=(20,78), LENGTH=1, ATTRB=(UNPROT) */}
                <Grid item xs={12} md={4}>
                  <TextField
                    label="Primary Card Holder Y/N"
                    value={values.customerPrimary}
                    disabled
                    fullWidth
                    variant="outlined"
                  />
                </Grid>
              </Grid>
              
              <Divider sx={{ my: 4 }} />
              
              {/* Action Buttons */}
              {/* Maps BMS FKEY05 (F5=Save) and FKEY12 (F12=Cancel) */}
              <Box display="flex" justifyContent="flex-end" gap={2}>
                <Button
                  variant="outlined"
                  onClick={handleCancel}
                  disabled={isSubmitting}
                  size="large"
                >
                  Cancel
                </Button>
                <Button
                  type="submit"
                  variant="contained"
                  color="primary"
                  disabled={isSubmitting}
                  size="large"
                >
                  {isSubmitting ? 'Saving...' : 'Save'}
                </Button>
              </Box>
            </Form>
          )}
        </Formik>
      </Paper>
    </Box>
  );
};

export default AccountUpdateComponent;
