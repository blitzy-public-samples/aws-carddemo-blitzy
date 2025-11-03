/**
 * CardDemo Bill Payment Component
 * 
 * Transforms COBIL00C.cbl COBOL program and COBIL00.bms BMS mapset into
 * modern React component with multi-step payment workflow.
 * 
 * Business Function: Pay account balance in full with online bill payment
 * 
 * Source Mappings:
 * - COBOL Program: app/cbl/COBIL00C.cbl (Bill Payment Processing)
 * - BMS Mapset: app/bms/COBIL00.bms (Bill Payment Screen)
 * - BMS Copybook: app/cpy-bms/COBIL00.CPY (Screen Field Definitions)
 * 
 * Three-Step Workflow:
 * 1. Account Selection: Input account ID and fetch current balance
 * 2. Confirmation: Review payment details and confirm payment (Y/N)
 * 3. Payment Processing: Create transaction and update account balance
 * 
 * COBOL Business Logic Preservation:
 * - Account ID validation: PIC 9(11) from CVACT01Y.cpy
 * - Balance validation: ACCT-CURR-BAL > 0 (COBIL00C line 198)
 * - Transaction creation: Type '02' (Payment), Category 2 (COBIL00C lines 220-221)
 * - Balance update: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT (line 234)
 * - Atomic transaction: CICS SYNCPOINT equivalent via single API call
 * - Error handling: File-status codes mapped to HTTP error responses
 * 
 * React Implementation:
 * - Material-UI Stepper for visual workflow progress
 * - Formik for form state management across steps
 * - Yup schema for validation matching COBOL PIC constraints
 * - Axios API client for REST endpoint integration
 * - Snackbar for error messages matching BMS ERRMSG field (position 23,1, RED)
 * 
 * Per Agent Action Plan Section 0.6 and 0.9:
 * - Preserves exact business logic from COBIL00C.cbl
 * - Maintains COMP-3 precision (2 decimal places) for balance display
 * - Transforms pseudo-conversational processing to stateless React component
 * - Maps PF keys to button actions (PF3=Cancel, F4=Reset, ENTER=Next/Confirm)
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import {
  Stepper,
  Step,
  StepLabel,
  StepContent,
  TextField,
  Button,
  Snackbar,
  Alert,
  Box,
  Typography,
  Paper,
  RadioGroup,
  FormControlLabel,
  Radio,
  CircularProgress
} from '@mui/material';
import { Formik, Form } from 'formik';
import * as Yup from 'yup';

// Internal imports - from depends_on_files
import apiClient from '../../services/apiClient';
import { formatCurrency } from '../../utils/formatters';
import { FIELD_LENGTHS } from '../../utils/constants';

/**
 * Validation Schema for Bill Payment Form
 * 
 * Preserves COBOL validation logic from COBIL00C.cbl:
 * - Account ID: ACTIDINI validation (PIC 9(11), lines 159-164)
 * - Confirmation: CONFIRMI validation (Y/y/N/n, lines 173-191)
 * - Balance check: ACCT-CURR-BAL <= ZEROS validation (line 198)
 * 
 * Yup schema matches COBOL PIC clause constraints and business rules
 */
const billPaymentValidationSchema = Yup.object().shape({
  // Account ID: PIC 9(11) from CVACT01Y.cpy
  // COBOL: WHEN ACTIDINI OF COBIL0AI = SPACES OR LOW-VALUES (line 159)
  accountId: Yup.string()
    .matches(/^\d{11}$/, 'Account ID must be 11 digits')
    .length(FIELD_LENGTHS.ACCOUNT_ID, `Account ID must be exactly ${FIELD_LENGTHS.ACCOUNT_ID} digits`)
    .required('Acct ID can NOT be empty...'), // COBOL line 161
  
  // Confirmation: Y/N validation
  // COBOL: EVALUATE CONFIRMI OF COBIL0AI (lines 173-191)
  confirmation: Yup.string()
    .oneOf(['Y', 'N'], 'Invalid value. Valid values are (Y/N)...') // COBOL lines 187-188
    .when('$activeStep', {
      is: (step) => step >= 1,
      then: (schema) => schema.required('Confirm to make a bill payment...') // COBOL line 237
    }),
  
  // Account balance: Read-only field, validated after fetch
  accountBalance: Yup.number()
    .min(0.01, 'You have nothing to pay...') // COBOL lines 198-201
    .nullable()
});

/**
 * BillPaymentComponent
 * 
 * Multi-step bill payment form component transforming COBIL00C.cbl business logic
 * 
 * Component State:
 * - activeStep: Current step in workflow (0=Account Selection, 1=Confirmation, 2=Processing)
 * - accountData: Fetched account information from GET /api/accounts/{accountId}
 * - paymentResult: Payment transaction result from POST /api/payments/bill
 * - snackbar: Error/success message display state
 * - loading: API call in-progress indicator
 * 
 * COBOL Equivalents:
 * - activeStep maps to CDEMO-PGM-CONTEXT (transaction state)
 * - accountData maps to ACCOUNT-RECORD from VSAM ACCTDAT file
 * - paymentResult maps to TRAN-RECORD from WRITE-TRANSACT-FILE
 * - snackbar maps to ERRMSG BMS field (RED color, position 23,1)
 * 
 * Props: None (standalone component)
 * Returns: JSX.Element - Multi-step payment form with stepper
 */
const BillPaymentComponent = () => {
  // React Router hooks for navigation and state passing
  const navigate = useNavigate();
  const location = useLocation();
  
  // Component state management
  // Maps to COBOL WORKING-STORAGE SECTION variables
  const [activeStep, setActiveStep] = useState(0); // Current workflow step
  const [accountData, setAccountData] = useState(null); // Fetched account record
  const [paymentResult, setPaymentResult] = useState(null); // Payment transaction result
  const [loading, setLoading] = useState(false); // API loading state
  
  // Snackbar state for error/success messages
  // Maps to COBOL WS-MESSAGE and ERRMSG BMS field
  const [snackbar, setSnackbar] = useState({
    open: false,
    message: '',
    severity: 'info' // 'error', 'success', 'info', 'warning'
  });
  
  // Extract pre-filled account ID from route state (if navigated from account/transaction view)
  // Maps to COBOL CDEMO-CB00-TRN-SELECTED (lines 116-119)
  const prefilledAccountId = location.state?.accountId || '';
  
  // Initial form values
  // Maps to COBOL WORKING-STORAGE initialized variables
  const initialValues = {
    accountId: prefilledAccountId, // Pre-populate if passed from another screen
    confirmation: '',              // Y/N confirmation (WS-CONF-PAY-FLG)
    accountBalance: null           // Fetched balance (WS-CURR-BAL)
  };
  
  /**
   * Handle snackbar close event
   * Closes error/success message display
   */
  const handleCloseSnackbar = () => {
    setSnackbar({ ...snackbar, open: false });
  };
  
  /**
   * Show error message in snackbar
   * Maps to COBOL PERFORM SEND-BILLPAY-SCREEN with WS-ERR-FLG = 'Y'
   * 
   * @param {string} message - Error message to display (maps to WS-MESSAGE)
   */
  const showError = (message) => {
    setSnackbar({
      open: true,
      message,
      severity: 'error'
    });
  };
  
  /**
   * Show success message in snackbar
   * Maps to COBOL successful transaction with DFHGREEN color
   * 
   * @param {string} message - Success message to display
   */
  const showSuccess = (message) => {
    setSnackbar({
      open: true,
      message,
      severity: 'success'
    });
  };
  
  /**
   * Fetch Account Balance
   * 
   * Retrieves account information and current balance using REST API
   * Maps to COBOL READ-ACCTDAT-FILE paragraph (lines 343-372)
   * 
   * COBOL Equivalents:
   * - EXEC CICS READ DATASET(WS-ACCTDAT-FILE) INTO(ACCOUNT-RECORD) (lines 345-354)
   * - RIDFLD(ACCT-ID) KEYLENGTH(LENGTH OF ACCT-ID) UPDATE
   * - Response handling: DFHRESP(NORMAL), DFHRESP(NOTFND) (lines 356-372)
   * 
   * API Call: GET /api/accounts/{accountId}
   * Response: { accountId, currentBalance, creditLimit, ... }
   * 
   * @param {string} accountId - 11-digit account identifier
   * @param {object} formikBag - Formik helpers for field updates
   * @returns {Promise<boolean>} Success indicator
   */
  const fetchAccountBalance = async (accountId, formikBag) => {
    setLoading(true);
    
    try {
      // API call: GET /api/accounts/{accountId}
      // Maps to EXEC CICS READ DATASET(WS-ACCTDAT-FILE)
      const response = await apiClient.get(`/accounts/${accountId}`);
      const account = response.data;
      
      // Store account data in component state
      // Maps to MOVE statements populating ACCOUNT-RECORD
      setAccountData(account);
      
      // Update Formik field with fetched balance
      // COBOL: MOVE ACCT-CURR-BAL TO WS-CURR-BAL (line 193)
      // COBOL: MOVE WS-CURR-BAL TO CURBALI OF COBIL0AI (line 194)
      formikBag.setFieldValue('accountBalance', account.currentBalance);
      
      // Validate balance > 0
      // COBOL: IF ACCT-CURR-BAL <= ZEROS (line 198)
      if (account.currentBalance <= 0) {
        // COBOL: MOVE 'You have nothing to pay...' TO WS-MESSAGE (line 201)
        showError('You have nothing to pay...');
        setLoading(false);
        return false;
      }
      
      // Success - proceed to next step
      setLoading(false);
      return true;
      
    } catch (error) {
      setLoading(false);
      
      // Handle different error responses
      // Maps to COBOL EVALUATE WS-RESP-CD (lines 356-372)
      if (error.status === 404) {
        // COBOL: WHEN DFHRESP(NOTFND) (line 359)
        // COBOL: MOVE 'Account ID NOT found...' TO WS-MESSAGE (line 361)
        showError('Account ID NOT found...');
      } else {
        // COBOL: WHEN OTHER (line 365)
        // COBOL: MOVE 'Unable to lookup Account...' TO WS-MESSAGE (line 368)
        showError(error.message || 'Unable to lookup Account...');
      }
      
      return false;
    }
  };
  
  /**
   * Process Bill Payment
   * 
   * Creates payment transaction and updates account balance via REST API
   * Maps to COBOL payment processing logic (lines 208-244)
   * 
   * COBOL Equivalents:
   * - Read CXACAIX file for card cross-reference (lines 211)
   * - Generate transaction ID (lines 216-217)
   * - Create transaction record (lines 218-232)
   * - Write transaction to TRANSACT file (line 233)
   * - Update account balance (line 234)
   * - Rewrite account record (line 235)
   * 
   * Transaction Fields (COBIL00C lines 220-228):
   * - TRAN-TYPE-CD = '02' (Payment type)
   * - TRAN-CAT-CD = 2 (Payment category)
   * - TRAN-SOURCE = 'POS TERM'
   * - TRAN-DESC = 'BILL PAYMENT - ONLINE'
   * - TRAN-AMT = ACCT-CURR-BAL (full balance payment)
   * - TRAN-MERCHANT-NAME = 'BILL PAYMENT'
   * 
   * API Call: POST /api/payments/bill
   * Request: { accountId, paymentAmount }
   * Response: { transactionId, newBalance, message }
   * 
   * @param {object} values - Form values containing accountId and confirmation
   * @returns {Promise<boolean>} Success indicator
   */
  const processPayment = async (values) => {
    setLoading(true);
    
    try {
      // Prepare payment request payload
      // Maps to COBOL transaction record initialization (lines 218-228)
      const paymentRequest = {
        accountId: values.accountId,
        paymentAmount: accountData.currentBalance // Full balance payment
      };
      
      // API call: POST /api/payments/bill
      // Maps to COBOL PERFORM WRITE-TRANSACT-FILE and UPDATE-ACCTDAT-FILE
      const response = await apiClient.post('/payments/bill', paymentRequest);
      const result = response.data;
      
      // Store payment result
      setPaymentResult(result);
      
      // Display success message with transaction ID
      // COBOL: STRING 'Payment successful. Your Transaction ID is ' ... (lines 527-531)
      const successMessage = `Payment successful. Your Transaction ID is ${result.transactionId}.`;
      showSuccess(successMessage);
      
      setLoading(false);
      return true;
      
    } catch (error) {
      setLoading(false);
      
      // Handle payment processing errors
      // Maps to COBOL error handling in WRITE-TRANSACT-FILE (lines 522-547)
      if (error.status === 400) {
        showError(error.message || 'Invalid payment request');
      } else if (error.status === 409) {
        // COBOL: WHEN DFHRESP(DUPKEY) or DFHRESP(DUPREC) (lines 533-539)
        showError('Tran ID already exist...');
      } else {
        // COBOL: WHEN OTHER (line 540)
        // COBOL: MOVE 'Unable to Add Bill pay Transaction...' (line 543)
        showError(error.message || 'Unable to Add Bill pay Transaction...');
      }
      
      return false;
    }
  };
  
  /**
   * Handle form submission based on current step
   * 
   * Step-specific submission handlers:
   * - Step 0: Fetch account balance and proceed to confirmation
   * - Step 1: Process payment if confirmed, or cancel if declined
   * - Step 2: Navigate to account view or menu after payment completion
   * 
   * Maps to COBOL PROCESS-ENTER-KEY paragraph (lines 154-244)
   * 
   * @param {object} values - Current form values
   * @param {object} formikBag - Formik helpers (setFieldValue, resetForm, etc.)
   */
  const handleStepSubmit = async (values, formikBag) => {
    // Step 0: Account Selection
    // COBOL: Validate ACTIDINI and READ-ACCTDAT-FILE (lines 159-206)
    if (activeStep === 0) {
      const success = await fetchAccountBalance(values.accountId, formikBag);
      if (success) {
        setActiveStep(1); // Proceed to confirmation step
      }
    }
    
    // Step 1: Confirmation
    // COBOL: EVALUATE CONFIRMI (lines 173-191)
    else if (activeStep === 1) {
      if (values.confirmation === 'Y') {
        // COBOL: SET CONF-PAY-YES TO TRUE, PERFORM payment processing (lines 176-235)
        const success = await processPayment(values);
        if (success) {
          setActiveStep(2); // Proceed to completion step
        }
      } else if (values.confirmation === 'N') {
        // COBOL: Payment cancelled, PERFORM CLEAR-CURRENT-SCREEN (lines 179-180)
        showError('Payment cancelled');
        handleReset(formikBag);
      }
    }
    
    // Step 2: Payment Processing (completion)
    // Navigate to account view or menu
    // COBOL: RETURN-TO-PREV-SCREEN (lines 273-284)
    else if (activeStep === 2) {
      // Navigate to account view with the account ID
      navigate(`/accounts/${values.accountId}`);
    }
  };
  
  /**
   * Handle Back button click
   * 
   * Returns to previous step or cancels payment workflow
   * Maps to COBOL PF3 (Back) key handling (lines 128-135)
   * 
   * COBOL: WHEN DFHPF3
   *        IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES
   *            MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM
   */
  const handleBack = () => {
    if (activeStep === 0) {
      // Return to menu or previous screen
      // COBOL: MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM (line 130)
      navigate('/menu');
    } else {
      // Go back to previous step
      setActiveStep((prevStep) => prevStep - 1);
    }
  };
  
  /**
   * Handle Reset button click
   * 
   * Clears all form fields and returns to initial step
   * Maps to COBOL PF4 (Clear) key handling (lines 136-137)
   * 
   * COBOL: WHEN DFHPF4
   *        PERFORM CLEAR-CURRENT-SCREEN (line 137)
   * 
   * CLEAR-CURRENT-SCREEN paragraph (lines 552-555):
   *   - PERFORM INITIALIZE-ALL-FIELDS
   *   - PERFORM SEND-BILLPAY-SCREEN
   * 
   * INITIALIZE-ALL-FIELDS paragraph (lines 560-566):
   *   - MOVE -1 TO ACTIDINL OF COBIL0AI (set cursor position)
   *   - MOVE SPACES TO input fields
   * 
   * @param {object} formikBag - Formik helpers
   */
  const handleReset = (formikBag) => {
    // Reset Formik form to initial values
    formikBag.resetForm();
    
    // Reset component state
    setActiveStep(0);
    setAccountData(null);
    setPaymentResult(null);
    
    // COBOL: MOVE SPACES TO WS-MESSAGE (line 566)
    // Clear any displayed error messages
    setSnackbar({ open: false, message: '', severity: 'info' });
  };
  
  /**
   * Handle Cancel button click
   * 
   * Exits bill payment flow and returns to menu
   * Maps to COBOL PF3=Back from initial screen
   * 
   * @param {object} formikBag - Formik helpers for form reset
   */
  const handleCancel = (formikBag) => {
    handleReset(formikBag);
    navigate('/menu');
  };
  
  // Component render
  return (
    <Box sx={{ maxWidth: 800, margin: '0 auto', padding: 3 }}>
      {/* Page Title */}
      {/* Maps to BMS screen title at position 4,35: 'Bill Payment' */}
      <Typography variant="h4" gutterBottom align="center">
        Bill Payment
      </Typography>
      
      {/* Formik Form Container */}
      {/* Maps to COBOL COBIL0AI structure from COBIL00 copybook */}
      <Formik
        initialValues={initialValues}
        validationSchema={billPaymentValidationSchema}
        onSubmit={handleStepSubmit}
        context={{ activeStep }} // Pass activeStep to validation context
        validateOnChange={true}
        validateOnBlur={true}
      >
        {(formikProps) => {
          const { values, errors, touched, handleChange, handleBlur } = formikProps;
          
          return (
            <Form>
              <Paper elevation={3} sx={{ padding: 3, marginTop: 2 }}>
                {/* Material-UI Stepper for workflow visualization */}
                {/* Displays current step and progress through payment flow */}
                <Stepper activeStep={activeStep} orientation="vertical">
                  
                  {/* STEP 0: Account Selection */}
                  {/* Maps to COBOL screen section with ACTIDIN field (BMS position 6,21) */}
                  <Step>
                    <StepLabel>Account Selection</StepLabel>
                    <StepContent>
                      <Typography variant="body2" gutterBottom>
                        Enter your 11-digit account ID to view your current balance.
                      </Typography>
                      
                      {/* Account ID Input Field */}
                      {/* BMS: ACTIDIN DFHMDF ATTRB=(FSET,IC,NORM,UNPROT), POS=(6,21), LENGTH=11 */}
                      {/* COBOL: MOVE ACTIDINI OF COBIL0AI TO ACCT-ID (line 170) */}
                      <TextField
                        fullWidth
                        name="accountId"
                        label="Enter Acct ID"
                        value={values.accountId}
                        onChange={handleChange}
                        onBlur={handleBlur}
                        error={touched.accountId && Boolean(errors.accountId)}
                        helperText={touched.accountId && errors.accountId}
                        margin="normal"
                        inputProps={{
                          maxLength: FIELD_LENGTHS.ACCOUNT_ID,
                          pattern: '[0-9]*'
                        }}
                        autoFocus // Maps to IC (Initial Cursor) attribute
                        disabled={loading}
                      />
                      
                      {/* Current Balance Display (if fetched) */}
                      {/* BMS: CURBAL DFHMDF ATTRB=(ASKIP,FSET,NORM), POS=(11,32), LENGTH=14 */}
                      {/* COBOL: MOVE WS-CURR-BAL TO CURBALI OF COBIL0AI (line 194) */}
                      {accountData && (
                        <Box sx={{ marginTop: 2, marginBottom: 2 }}>
                          <Typography variant="body1" color="text.secondary">
                            Your current balance is:
                          </Typography>
                          <Typography variant="h5" color="primary" sx={{ fontWeight: 'bold' }}>
                            {formatCurrency(accountData.currentBalance)}
                          </Typography>
                        </Box>
                      )}
                      
                      {/* Step 0 Action Buttons */}
                      <Box sx={{ marginTop: 2 }}>
                        <Button
                          variant="contained"
                          onClick={() => handleStepSubmit(values, formikProps)}
                          disabled={!values.accountId || values.accountId.length !== 11 || loading}
                          sx={{ marginRight: 1 }}
                        >
                          {loading ? <CircularProgress size={24} /> : 'Continue'}
                        </Button>
                        <Button
                          variant="outlined"
                          onClick={() => handleCancel(formikProps)}
                          disabled={loading}
                          sx={{ marginRight: 1 }}
                        >
                          Cancel
                        </Button>
                        <Button
                          variant="text"
                          onClick={() => handleReset(formikProps)}
                          disabled={loading}
                        >
                          Clear
                        </Button>
                      </Box>
                    </StepContent>
                  </Step>
                  
                  {/* STEP 1: Confirmation */}
                  {/* Maps to COBOL confirmation section with CONFIRM field (BMS position 15,60) */}
                  <Step>
                    <StepLabel>Confirmation</StepLabel>
                    <StepContent>
                      {/* Payment Summary */}
                      <Box sx={{ marginBottom: 3 }}>
                        <Typography variant="h6" gutterBottom>
                          Payment Summary
                        </Typography>
                        <Typography variant="body1">
                          <strong>Account ID:</strong> {values.accountId}
                        </Typography>
                        <Typography variant="body1">
                          <strong>Current Balance:</strong> {formatCurrency(accountData?.currentBalance || 0)}
                        </Typography>
                        <Typography variant="body1">
                          <strong>Payment Amount:</strong> {formatCurrency(accountData?.currentBalance || 0)}
                        </Typography>
                        <Typography variant="body2" color="text.secondary" sx={{ marginTop: 1 }}>
                          (Full balance payment)
                        </Typography>
                      </Box>
                      
                      {/* Confirmation Prompt */}
                      {/* BMS: 'Do you want to pay your balance now. Please confirm: ' (position 15,6) */}
                      {/* BMS: CONFIRM field at position 15,60, length 1, with (Y/N) label */}
                      {/* COBOL: EVALUATE CONFIRMI OF COBIL0AI (lines 173-191) */}
                      <Typography variant="body1" gutterBottom>
                        Do you want to pay your balance now? Please confirm:
                      </Typography>
                      
                      <RadioGroup
                        name="confirmation"
                        value={values.confirmation}
                        onChange={handleChange}
                      >
                        <FormControlLabel
                          value="Y"
                          control={<Radio />}
                          label="Yes - Proceed with payment"
                          disabled={loading}
                        />
                        <FormControlLabel
                          value="N"
                          control={<Radio />}
                          label="No - Cancel payment"
                          disabled={loading}
                        />
                      </RadioGroup>
                      
                      {/* Display error if confirmation not selected */}
                      {touched.confirmation && errors.confirmation && (
                        <Typography variant="body2" color="error" sx={{ marginTop: 1 }}>
                          {errors.confirmation}
                        </Typography>
                      )}
                      
                      {/* Step 1 Action Buttons */}
                      <Box sx={{ marginTop: 2 }}>
                        <Button
                          variant="contained"
                          onClick={() => handleStepSubmit(values, formikProps)}
                          disabled={!values.confirmation || loading}
                          sx={{ marginRight: 1 }}
                        >
                          {loading ? <CircularProgress size={24} /> : 'Confirm Payment'}
                        </Button>
                        <Button
                          variant="outlined"
                          onClick={handleBack}
                          disabled={loading}
                          sx={{ marginRight: 1 }}
                        >
                          Back
                        </Button>
                        <Button
                          variant="text"
                          onClick={() => handleCancel(formikProps)}
                          disabled={loading}
                        >
                          Cancel
                        </Button>
                      </Box>
                    </StepContent>
                  </Step>
                  
                  {/* STEP 2: Payment Processing Complete */}
                  {/* Maps to COBOL success message display (lines 524-532) */}
                  <Step>
                    <StepLabel>Payment Processing</StepLabel>
                    <StepContent>
                      {/* Payment Success Message */}
                      {/* COBOL: STRING 'Payment successful. Your Transaction ID is ' ... (lines 527-531) */}
                      {paymentResult && (
                        <Box sx={{ marginBottom: 3 }}>
                          <Typography variant="h6" color="success.main" gutterBottom>
                            ✓ Payment Successful
                          </Typography>
                          <Typography variant="body1">
                            Your Transaction ID: <strong>{paymentResult.transactionId}</strong>
                          </Typography>
                          <Typography variant="body1">
                            New Balance: <strong>{formatCurrency(paymentResult.newBalance || 0)}</strong>
                          </Typography>
                          <Typography variant="body2" color="text.secondary" sx={{ marginTop: 1 }}>
                            Payment has been processed successfully. Your account balance has been updated.
                          </Typography>
                        </Box>
                      )}
                      
                      {/* Completion Action Buttons */}
                      <Box sx={{ marginTop: 2 }}>
                        <Button
                          variant="contained"
                          onClick={() => navigate(`/accounts/${values.accountId}`)}
                          sx={{ marginRight: 1 }}
                        >
                          View Account
                        </Button>
                        <Button
                          variant="outlined"
                          onClick={() => navigate('/menu')}
                        >
                          Return to Menu
                        </Button>
                      </Box>
                    </StepContent>
                  </Step>
                  
                </Stepper>
              </Paper>
            </Form>
          );
        }}
      </Formik>
      
      {/* Snackbar for Error/Success Messages */}
      {/* Maps to BMS ERRMSG field (position 23,1, length 78, COLOR=RED) */}
      {/* COBOL: MOVE WS-MESSAGE TO ERRMSGO OF COBIL0AO (line 293) */}
      <Snackbar
        open={snackbar.open}
        autoHideDuration={6000}
        onClose={handleCloseSnackbar}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert 
          onClose={handleCloseSnackbar} 
          severity={snackbar.severity}
          sx={{ width: '100%' }}
        >
          {snackbar.message}
        </Alert>
      </Snackbar>
      
      {/* Help Text / Instructions */}
      {/* Maps to BMS function key instructions at position 24,1 */}
      {/* 'ENTER=Continue  F3=Back  F4=Clear' */}
      <Box sx={{ marginTop: 3, padding: 2, backgroundColor: '#f5f5f5', borderRadius: 1 }}>
        <Typography variant="body2" color="text.secondary">
          <strong>Instructions:</strong> Enter your 11-digit account ID to view your balance. 
          Confirm payment to pay your full balance. Use Cancel to return to the menu, 
          or Clear to reset the form.
        </Typography>
      </Box>
    </Box>
  );
};

// Export component as default export per schema specification
export default BillPaymentComponent;

