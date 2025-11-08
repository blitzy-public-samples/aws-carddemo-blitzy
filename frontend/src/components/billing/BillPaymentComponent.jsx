/**
 * CardDemo - Bill Payment Component
 * 
 * Purpose: React functional component for bill payment processing matching BMS screen 
 * COBIL00M.bms layout and business logic from COBIL00C.cbl. Provides form for account ID 
 * input with validation (required, not empty), displays current account balance in 
 * read-only format with BigDecimal 2-decimal precision, accepts payment confirmation (Y/N), 
 * and processes full balance payment.
 * 
 * Original COBOL Sources:
 * - app/bms/COBIL00.bms: Lines 19-137 (Screen layout definition)
 * - app/cbl/COBIL00C.cbl: Lines 1-450 (Business logic implementation)
 * - app/cpy/COCOM01Y.cpy: COMMAREA session structure
 * - app/cpy/CVACT01Y.cpy: Account record structure
 * - app/cpy/CVTRA05Y.cpy: Transaction record structure
 * 
 * BMS Screen Layout Mapping (80-column terminal):
 * Line 4: "Bill Payment" title (NEUTRAL, BRT) at POS=(4,35)
 * Line 6: "Enter Acct ID:" label (GREEN) at POS=(6,6)
 *         ACTIDIN field (11 chars, GREEN, UNDERLINE, IC) at POS=(6,21)
 * Line 8: Yellow separator line
 * Line 11: "Your current balance is: " (TURQUOISE) at POS=(11,6)
 *          CURBAL field (14 chars, BLUE) at POS=(11,32)
 * Line 15: "Do you want to pay your balance now. Please confirm: " at POS=(15,6)
 *          CONFIRM field (1 char, GREEN, UNDERLINE) at POS=(15,60)
 *          "(Y/N)" hint (NEUTRAL) at POS=(15,63)
 * Line 23: ERRMSG field (78 chars, RED, BRT) at POS=(23,1)
 * Line 24: "ENTER=Continue  F3=Back  F4=Clear" (YELLOW) at POS=(24,1)
 * 
 * COBOL Business Logic Flow (COBIL00C.cbl):
 * 1. PROCESS-ENTER-KEY (lines 154-244):
 *    - Validate Account ID not empty (lines 159-167)
 *    - Validate confirmation value Y/N (lines 173-191)
 *    - Read account record (lines 177, 184)
 *    - Validate balance > 0 (lines 198-205)
 *    - If confirmed, generate transaction and update balance (lines 210-235)
 * 2. Payment Processing (lines 210-235):
 *    - Generate new transaction ID (lines 212-217)
 *    - Create transaction record for full balance (line 224: MOVE ACCT-CURR-BAL TO TRAN-AMT)
 *    - Write transaction (line 233)
 *    - Update account balance to zero (line 234)
 * 3. Validation Messages:
 *    - "Acct ID can NOT be empty..." (lines 161-162)
 *    - "Account ID NOT found..." (lines 361-362)
 *    - "You have nothing to pay..." (lines 201-202)
 *    - "Invalid value. Valid values are (Y/N)..." (lines 187-188)
 *    - "Confirm to make a bill payment..." (lines 237-238)
 * 
 * React Component Features:
 * - Account ID input field with validation (required, not empty)
 * - Current balance display (read-only, 2 decimal precision)
 * - Confirmation input (Y/N validation)
 * - Error message display area (RED alert)
 * - Success message with transaction ID
 * - Form submission calling /api/billing/payment endpoint
 * - Navigation: Submit (ENTER), Cancel (F3), Clear (F4)
 * - Material-UI components for modern web styling
 * - Responsive layout using Grid system
 * - Integration with Header and Footer components
 * - AuthContext integration for user session data
 * 
 * API Integration:
 * - POST /api/billing/payment
 *   Request: { accountId: string, confirmation: string }
 *   Response: { transactionId: string, message: string, newBalance: number }
 * 
 * Color Coding (matching BMS attributes):
 * - GREEN: Input fields (ACTIDIN, CONFIRM)
 * - BLUE: Labels and read-only data (balance)
 * - YELLOW: Section dividers
 * - RED: Error messages
 * - TURQUOISE/NEUTRAL: Informational text
 * 
 * Transformation Requirements (Agent Action Plan sections 0.1, 0.6, 0.10):
 * - Complete functional equivalence with COBIL00C.cbl logic
 * - BigDecimal precision (2 decimals) for all monetary values
 * - Validation matching COBOL patterns exactly
 * - Full balance payment (not partial) per line 224 behavior
 * - Transaction ID display upon successful payment
 * - Zero placeholders or TODOs (production-ready implementation)
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import { useState, useEffect } from 'react';
import PropTypes from 'prop-types';
import { useNavigate } from 'react-router-dom';
import {
  Container,
  Box,
  Grid,
  TextField,
  Button,
  Typography,
  Alert,
  CircularProgress,
  Divider,
  Paper
} from '@mui/material';
import Header from '../common/Header.jsx';
import Footer from '../common/Footer.jsx';
import { useAuth } from '../../context/AuthContext.jsx';
import axios from 'axios';

/**
 * Format currency value to 2 decimal places
 * Matches COBOL COMP-3 decimal precision with V99 scale
 * 
 * COBOL Equivalent: PIC S9(10)V99 display formatting
 * Example: 1234567.89 => "$1,234,567.89"
 * 
 * @param {number|string} value - Numeric value to format
 * @returns {string} Formatted currency string
 */
const formatCurrency = (value) => {
  if (value === null || value === undefined || value === '') {
    return '$0.00';
  }
  const numValue = parseFloat(value);
  if (isNaN(numValue)) {
    return '$0.00';
  }
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(numValue);
};

/**
 * Validate confirmation input (Y/N values only)
 * Matches COBOL EVALUATE statement lines 173-191
 * 
 * COBOL Logic:
 * WHEN 'Y' WHEN 'y' => Valid, proceed
 * WHEN 'N' WHEN 'n' => Valid, cancel
 * WHEN SPACES WHEN LOW-VALUES => Valid, no confirmation yet
 * WHEN OTHER => Invalid
 * 
 * @param {string} value - Confirmation input value
 * @returns {boolean} True if valid Y/N/empty, false otherwise
 */
const isValidConfirmation = (value) => {
  if (!value || value.trim() === '') {
    return true; // Empty is valid (no confirmation yet)
  }
  const upperValue = value.trim().toUpperCase();
  return upperValue === 'Y' || upperValue === 'N';
};

/**
 * BillPaymentComponent
 * 
 * Main React functional component implementing bill payment screen.
 * Provides form interface for paying account balance in full with validation
 * and confirmation workflow matching COBIL00C.cbl business logic.
 * 
 * Component State:
 * - accountId: Account identifier input (11 digits max)
 * - balance: Current account balance (BigDecimal 2 decimals)
 * - confirmation: Y/N confirmation value
 * - error: Error message text (displayed in RED alert)
 * - success: Success message text (displayed in GREEN alert)
 * - loading: Loading state for async operations
 * - transactionId: Generated transaction ID upon successful payment
 * 
 * Workflow:
 * 1. User enters Account ID
 * 2. System retrieves and displays current balance
 * 3. User enters Y to confirm payment or N to cancel
 * 4. System validates input and processes payment if confirmed
 * 5. System displays success message with transaction ID
 * 
 * @returns {JSX.Element} Bill payment form component
 */
const BillPaymentComponent = () => {
  // Navigation hook for programmatic routing (F3 Back button)
  const navigate = useNavigate();

  // Authentication context for user session data
  // Replaces COBOL COMMAREA CDEMO-USER-ID, CDEMO-USER-TYPE
  const { user, isAuthenticated } = useAuth();

  // Component state management
  // Replaces COBOL WORKING-STORAGE SECTION variables
  const [accountId, setAccountId] = useState(''); // WS-ACCT-ID equivalent
  const [balance, setBalance] = useState(''); // WS-CURR-BAL equivalent
  const [confirmation, setConfirmation] = useState(''); // WS-CONF-PAY-FLG equivalent
  const [error, setError] = useState(''); // WS-MESSAGE (error) equivalent
  const [success, setSuccess] = useState(''); // Success message display
  const [loading, setLoading] = useState(false); // Async operation indicator
  const [transactionId, setTransactionId] = useState(''); // WS-TRAN-ID-NUM equivalent

  /**
   * Component initialization effect
   * 
   * Redirects to login if user is not authenticated.
   * Matches COBOL logic checking CDEMO-USER-ID for valid session.
   * 
   * COBOL Equivalent: Lines 107-109
   * IF EIBCALEN = 0
   *     MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
   *     PERFORM RETURN-TO-PREV-SCREEN
   */
  useEffect(() => {
    if (!isAuthenticated) {
      navigate('/login');
    }
  }, [isAuthenticated, navigate]);

  /**
   * Fetch account balance when account ID changes
   * 
   * Retrieves current account balance from backend when user enters valid account ID.
   * Matches COBOL READ-ACCTDAT-FILE paragraph (lines 343-372).
   * 
   * COBOL Equivalent:
   * EXEC CICS READ
   *      DATASET   (WS-ACCTDAT-FILE)
   *      INTO      (ACCOUNT-RECORD)
   *      RIDFLD    (ACCT-ID)
   * 
   * Validation:
   * - Account ID not empty
   * - Account ID length 11 characters
   * 
   * Error Handling:
   * - NOTFND: "Account ID NOT found..." (lines 361-362)
   * - OTHER: "Unable to lookup Account..." (lines 368-369)
   */
  useEffect(() => {
    const fetchBalance = async () => {
      // Only fetch if account ID is valid (not empty, proper length)
      if (!accountId || accountId.trim() === '' || accountId.length !== 11) {
        setBalance('');
        return;
      }

      setLoading(true);
      setError('');
      setSuccess('');

      try {
        // Call backend API to retrieve account information
        const response = await axios.get(`/api/accounts/${accountId}`, {
          headers: {
            'Authorization': `Bearer ${localStorage.getItem('token')}`
          }
        });

        // Extract current balance from response
        // Maintain 2-decimal precision matching COBOL COMP-3 PIC S9(10)V99
        const currentBalance = response.data.currentBalance || 0;
        setBalance(currentBalance.toFixed(2));

        // Clear any previous error messages
        setError('');
      } catch (err) {
        // Handle errors matching COBOL RESP code evaluation
        if (err.response && err.response.status === 404) {
          // DFHRESP(NOTFND) equivalent
          setError('Account ID NOT found...');
          setBalance('');
        } else {
          // WHEN OTHER equivalent
          setError('Unable to lookup Account...');
          setBalance('');
        }
      } finally {
        setLoading(false);
      }
    };

    fetchBalance();
  }, [accountId]);

  /**
   * Handle Submit button click (ENTER key equivalent)
   * 
   * Implements PROCESS-ENTER-KEY paragraph from COBIL00C.cbl (lines 154-244).
   * Performs validation, confirmation check, and payment processing.
   * 
   * COBOL Flow:
   * 1. Validate Account ID not empty (lines 159-167)
   * 2. Validate confirmation Y/N (lines 173-191)
   * 3. Validate balance > 0 (lines 198-205)
   * 4. If confirmed Y/y, process payment (lines 210-235)
   * 5. Display success message with transaction ID
   * 
   * Payment Processing:
   * - Generate transaction ID (lines 212-217: WS-TRAN-ID-NUM + 1)
   * - Create transaction record paying FULL balance (line 224: MOVE ACCT-CURR-BAL TO TRAN-AMT)
   * - Write transaction to TRANSACT file (line 233)
   * - Update account balance to zero (line 234: COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT)
   * 
   * Error Messages (matching COBOL):
   * - "Acct ID can NOT be empty..." (lines 161-162)
   * - "Invalid value. Valid values are (Y/N)..." (lines 187-188)
   * - "You have nothing to pay..." (lines 201-202)
   * - "Confirm to make a bill payment..." (lines 237-238)
   * 
   * @param {Event} event - Form submit event
   */
  const handleSubmit = async (event) => {
    if (event) {
      event.preventDefault();
    }

    // Clear previous messages
    setError('');
    setSuccess('');

    // Validation 1: Account ID not empty
    // Matches COBOL lines 159-167
    if (!accountId || accountId.trim() === '') {
      setError('Acct ID can NOT be empty...');
      return;
    }

    // Validation 2: Valid confirmation value (Y/N)
    // Matches COBOL lines 173-191
    if (!isValidConfirmation(confirmation)) {
      setError('Invalid value. Valid values are (Y/N)...');
      return;
    }

    // Validation 3: Balance must be > 0
    // Matches COBOL lines 198-205
    const balanceValue = parseFloat(balance);
    if (isNaN(balanceValue) || balanceValue <= 0) {
      setError('You have nothing to pay...');
      return;
    }

    // Check confirmation status
    // Matches COBOL lines 173-191
    const confirmValue = confirmation.trim().toUpperCase();

    if (confirmValue === 'N') {
      // User declined payment
      // Matches COBOL lines 178-180: WHEN 'N' WHEN 'n' PERFORM CLEAR-CURRENT-SCREEN
      handleClear();
      setError('Payment cancelled.');
      return;
    }

    if (confirmValue !== 'Y') {
      // No confirmation yet, prompt user
      // Matches COBOL lines 237-238
      setError('Confirm to make a bill payment...');
      return;
    }

    // Process payment (confirmation = 'Y')
    // Matches COBOL lines 210-235
    setLoading(true);

    try {
      // Call backend API to process payment
      // POST /api/billing/payment
      // Request body: { accountId, confirmation: 'Y' }
      const response = await axios.post(
        '/api/billing/payment',
        {
          accountId: accountId.trim(),
          confirmation: confirmValue
        },
        {
          headers: {
            'Authorization': `Bearer ${localStorage.getItem('token')}`,
            'Content-Type': 'application/json'
          }
        }
      );

      // Extract transaction ID from response
      // Matches COBOL lines 216-217: MOVE TRAN-ID TO WS-TRAN-ID-NUM
      const txnId = response.data.transactionId || '';
      setTransactionId(txnId);

      // Display success message
      setSuccess(
        `Payment processed successfully. Transaction ID: ${txnId}. Your new balance is ${formatCurrency(response.data.newBalance)}.`
      );

      // Update balance display to new balance (should be 0.00 for full payment)
      setBalance(response.data.newBalance.toFixed(2));

      // Clear form fields after successful payment
      setConfirmation('');
    } catch (err) {
      // Handle payment processing errors
      if (err.response && err.response.data && err.response.data.message) {
        setError(err.response.data.message);
      } else if (err.response && err.response.status === 404) {
        setError('Account ID NOT found...');
      } else if (err.response && err.response.status === 400) {
        setError(err.response.data.message || 'Unable to process payment...');
      } else {
        setError('Unable to process payment. Please try again.');
      }
    } finally {
      setLoading(false);
    }
  };

  /**
   * Handle Cancel button click (F3 key equivalent)
   * 
   * Implements RETURN-TO-PREV-SCREEN paragraph from COBIL00C.cbl (lines 273-284).
   * Returns user to previous screen or main menu.
   * 
   * COBOL Logic: Lines 129-135
   * WHEN DFHPF3
   *     IF CDEMO-FROM-PROGRAM = SPACES OR LOW-VALUES
   *         MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM
   *     ELSE
   *         MOVE CDEMO-FROM-PROGRAM TO CDEMO-TO-PROGRAM
   *     END-IF
   *     PERFORM RETURN-TO-PREV-SCREEN
   */
  const handleCancel = () => {
    // Navigate back to main menu (COMEN01C equivalent)
    navigate('/menu');
  };

  /**
   * Handle Clear button click (F4 key equivalent)
   * 
   * Implements CLEAR-CURRENT-SCREEN paragraph from COBIL00C.cbl (line 137).
   * Clears all form fields and messages, resets to initial state.
   * 
   * COBOL Logic: Lines 136-137
   * WHEN DFHPF4
   *     PERFORM CLEAR-CURRENT-SCREEN
   */
  const handleClear = () => {
    setAccountId('');
    setBalance('');
    setConfirmation('');
    setError('');
    setSuccess('');
    setTransactionId('');
  };

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      {/* Header Component - Lines 1-3 of BMS screen */}
      <Header 
        transactionId="CB00"
        programName="COBIL00C"
        screenTitle="Bill Payment"
      />

      {/* Main Content Area - Lines 4-23 of BMS screen */}
      <Container component="main" maxWidth="md" sx={{ mt: 4, mb: 4, flexGrow: 1 }}>
        <Paper elevation={3} sx={{ p: 4 }}>
          {/* Screen Title - Line 4: "Bill Payment" at POS=(4,35) */}
          <Typography
            variant="h4"
            component="h1"
            align="center"
            gutterBottom
            sx={{ 
              color: 'text.primary',
              fontWeight: 'bold',
              mb: 3
            }}
          >
            Bill Payment
          </Typography>

          {/* Form */}
          <Box component="form" onSubmit={handleSubmit} noValidate>
            <Grid container spacing={3}>
              {/* Account ID Input - Line 6 at POS=(6,6) and POS=(6,21) */}
              <Grid item xs={12}>
                <TextField
                  required
                  fullWidth
                  id="accountId"
                  label="Enter Acct ID"
                  name="accountId"
                  value={accountId}
                  onChange={(e) => {
                    // Limit to 11 characters (COBOL PIC X(11) equivalent)
                    const value = e.target.value.slice(0, 11);
                    setAccountId(value);
                  }}
                  disabled={loading}
                  inputProps={{
                    maxLength: 11,
                    pattern: '[0-9]{11}',
                    style: { color: 'green' }
                  }}
                  sx={{
                    '& .MuiInputLabel-root': {
                      color: 'green'
                    },
                    '& .MuiOutlinedInput-root': {
                      '& fieldset': {
                        borderColor: 'green'
                      },
                      '&:hover fieldset': {
                        borderColor: 'darkgreen'
                      },
                      '&.Mui-focused fieldset': {
                        borderColor: 'green'
                      }
                    }
                  }}
                  helperText="11-digit account identifier"
                />
              </Grid>

              {/* Yellow Separator - Line 8 */}
              <Grid item xs={12}>
                <Divider sx={{ borderColor: 'warning.main', borderWidth: 2 }} />
              </Grid>

              {/* Current Balance Display - Line 11 at POS=(11,6) and POS=(11,32) */}
              <Grid item xs={12}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
                  <Typography
                    variant="body1"
                    sx={{ color: 'info.dark', fontWeight: 'medium' }}
                  >
                    Your current balance is:
                  </Typography>
                  <Typography
                    variant="h6"
                    sx={{ 
                      color: 'primary.main',
                      fontWeight: 'bold',
                      fontFamily: 'monospace'
                    }}
                  >
                    {balance ? formatCurrency(balance) : '$0.00'}
                  </Typography>
                  {loading && <CircularProgress size={20} />}
                </Box>
              </Grid>

              {/* Confirmation Input - Line 15 at POS=(15,6) and POS=(15,60) */}
              <Grid item xs={12}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
                  <Typography
                    variant="body1"
                    sx={{ color: 'info.dark', flexGrow: 1 }}
                  >
                    Do you want to pay your balance now. Please confirm:
                  </Typography>
                  <TextField
                    id="confirmation"
                    name="confirmation"
                    value={confirmation}
                    onChange={(e) => {
                      // Limit to 1 character (COBOL PIC X(01) equivalent)
                      const value = e.target.value.slice(0, 1).toUpperCase();
                      setConfirmation(value);
                    }}
                    disabled={loading || !balance || parseFloat(balance) <= 0}
                    inputProps={{
                      maxLength: 1,
                      style: { 
                        color: 'green',
                        textAlign: 'center',
                        width: '40px'
                      }
                    }}
                    sx={{
                      width: '80px',
                      '& .MuiOutlinedInput-root': {
                        '& fieldset': {
                          borderColor: 'green'
                        },
                        '&:hover fieldset': {
                          borderColor: 'darkgreen'
                        },
                        '&.Mui-focused fieldset': {
                          borderColor: 'green'
                        }
                      }
                    }}
                  />
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    (Y/N)
                  </Typography>
                </Box>
              </Grid>

              {/* Action Buttons */}
              <Grid item xs={12}>
                <Box sx={{ display: 'flex', gap: 2, justifyContent: 'center', mt: 2 }}>
                  {/* Submit Button - ENTER key equivalent */}
                  <Button
                    type="submit"
                    variant="contained"
                    color="primary"
                    disabled={loading || !accountId || !balance || parseFloat(balance) <= 0}
                    sx={{ minWidth: 150 }}
                  >
                    {loading ? <CircularProgress size={24} /> : 'Submit Payment'}
                  </Button>

                  {/* Clear Button - F4 key equivalent */}
                  <Button
                    variant="outlined"
                    color="secondary"
                    onClick={handleClear}
                    disabled={loading}
                    sx={{ minWidth: 150 }}
                  >
                    Clear
                  </Button>

                  {/* Cancel Button - F3 key equivalent */}
                  <Button
                    variant="outlined"
                    color="warning"
                    onClick={handleCancel}
                    disabled={loading}
                    sx={{ minWidth: 150 }}
                  >
                    Cancel
                  </Button>
                </Box>
              </Grid>

              {/* Error Message Display - Line 23 at POS=(23,1) */}
              {error && (
                <Grid item xs={12}>
                  <Alert 
                    severity="error"
                    sx={{ 
                      backgroundColor: '#ffebee',
                      color: 'error.main',
                      fontWeight: 'bold'
                    }}
                  >
                    {error}
                  </Alert>
                </Grid>
              )}

              {/* Success Message Display */}
              {success && (
                <Grid item xs={12}>
                  <Alert 
                    severity="success"
                    sx={{ 
                      backgroundColor: '#e8f5e9',
                      color: 'success.main',
                      fontWeight: 'bold'
                    }}
                  >
                    {success}
                  </Alert>
                </Grid>
              )}
            </Grid>
          </Box>
        </Paper>
      </Container>

      {/* Footer Component - Line 24 of BMS screen */}
      <Footer
        helpText="Enter account ID, confirm payment with Y/N"
        showEnter={true}
        showF3={true}
        onBack={handleCancel}
        onSubmit={handleSubmit}
      />
    </Box>
  );
};

// PropTypes validation for component props
BillPaymentComponent.propTypes = {
  // No external props expected for this component
  // All state managed internally and via context
};

// Export as default export per schema requirements
export default BillPaymentComponent;
