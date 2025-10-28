/**
 * BillingPage Component
 * 
 * Converted from: COBIL00.bms BMS map and COBIL00C.cbl COBOL program
 * Original function: Bill Payment - Display billing information and process payment
 * 
 * Purpose: Displays account billing information including current balance, payment
 * due date, minimum payment, and statement date. Allows users to post payments
 * to their account with proper validation and balance updates.
 * 
 * Conversion notes:
 * - COBIL00.bms 24x80 3270 terminal screen → React component with responsive layout
 * - ACTIDIN input field (POS=6,21) → Account ID from route parameter
 * - CURBAL display field (POS=11,32) → currentBalance with currency formatting
 * - CONFIRM input field (POS=15,60) → Payment form with amount input
 * - ERRMSG field (POS=23,1) → ErrorMessage component
 * - BMS field attributes (ASKIP, NUM, BRT) → Material-UI TextField with validation
 * - COBIL00C.cbl READ-ACCTDAT-FILE → GET /api/billing/{accountId}
 * - COBIL00C.cbl WRITE-TRANSACT-FILE + UPDATE-ACCTDAT-FILE → POST /api/billing/payment
 * - COMP-3 precision (PIC S9(10)V99) → BigDecimal validation with 2 decimal places
 * - COBOL PROCEDURE DIVISION → React hooks and event handlers
 * 
 * Copyright: Migrated from AWS CardDemo mainframe application
 * License: Apache 2.0
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Box,
  Card,
  CardContent,
  Typography,
  TextField,
  Button,
  Grid,
  Container,
  Divider,
  Alert,
} from '@mui/material';
import { Formik, Form, Field, FormikHelpers } from 'formik';
import * as yup from 'yup';

// Internal imports - Services
import { getBillingInfo, postPayment, BillingInfo, PaymentRequest, PaymentResponse } from '../services/billingService';

// Internal imports - Components
import Header from '../components/common/Header';
import Footer from '../components/common/Footer';
import ErrorMessage from '../components/common/ErrorMessage';
import LoadingSpinner from '../components/common/LoadingSpinner';

// Internal imports - Hooks
import { useAuth } from '../hooks/useAuth';

// Internal imports - Utilities
import { formatCurrency } from '../utils/currencyFormatter';
import { formatDate } from '../utils/dateFormatter';

/**
 * Payment Form Values Interface
 * 
 * Represents the payment form state
 * COBOL equivalent: Screen input from COBIL00.bms CONFIRM field
 */
interface PaymentFormValues {
  /**
   * Payment amount entered by user
   * COBOL: TRAN-AMT PIC S9(10)V99 COMP-3
   * Must be positive with max 2 decimal places
   */
  paymentAmount: string;
}

/**
 * Payment Validation Schema
 * 
 * Yup schema enforcing COBOL COMP-3 PIC S9(10)V99 validation rules:
 * - Required field (cannot be empty)
 * - Must be positive number greater than 0
 * - Maximum 2 decimal places (COMP-3 precision)
 * - Maximum value 99999999.99 (COBOL PIC S9(10)V99 limit)
 * 
 * COBOL equivalent validation from COBIL00C.cbl:
 * - IF ACCT-CURR-BAL <= ZEROS: Payment amount validation
 * - PIC S9(10)V99 COMP-3: 10 digits before decimal, 2 after
 */
const paymentValidationSchema = yup.object({
  paymentAmount: yup
    .string()
    .required('Payment amount is required')
    .test(
      'is-valid-number',
      'Payment amount must be a valid number',
      (value) => {
        if (!value) return false;
        const numValue = parseFloat(value);
        return !isNaN(numValue);
      }
    )
    .test(
      'is-positive',
      'Payment amount must be greater than zero',
      (value) => {
        if (!value) return false;
        const numValue = parseFloat(value);
        return numValue > 0;
      }
    )
    .test(
      'max-decimals',
      'Payment amount can have at most 2 decimal places',
      (value) => {
        if (!value) return false;
        const parts = value.split('.');
        return parts.length === 1 || (parts.length === 2 && (parts[1]?.length || 0) <= 2);
      }
    )
    .test(
      'max-value',
      'Payment amount cannot exceed $99,999,999.99',
      (value) => {
        if (!value) return false;
        const numValue = parseFloat(value);
        return numValue <= 99999999.99;
      }
    ),
});

/**
 * BillingPage Component
 * 
 * Main billing page component that displays account billing information
 * and allows payment posting.
 * 
 * COBOL Program Flow (COBIL00C.cbl):
 * 1. MAIN-PARA → useEffect hook for initialization
 * 2. PROCESS-ENTER-KEY → handlePaymentSubmit
 * 3. READ-ACCTDAT-FILE → getBillingInfo API call
 * 4. WRITE-TRANSACT-FILE + UPDATE-ACCTDAT-FILE → postPayment API call
 * 
 * @returns Billing page JSX
 */
const BillingPage: React.FC = () => {
  // React Router hooks
  const navigate = useNavigate();
  const { accountId } = useParams<{ accountId: string }>();

  // Authentication context
  const { isAuthenticated } = useAuth();

  // Component state
  const [billingInfo, setBillingInfo] = useState<BillingInfo | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string>('');
  const [successMessage, setSuccessMessage] = useState<string>('');
  const [submitting, setSubmitting] = useState<boolean>(false);

  /**
   * Fetch billing information on component mount
   * 
   * COBOL equivalent: COBIL00C.cbl MAIN-PARA → PROCESS-ENTER-KEY → READ-ACCTDAT-FILE
   * 
   * Performs:
   * 1. Authentication check (replaces COBOL EXEC CICS ASSIGN USERID)
   * 2. Account ID validation (replaces COBOL IF CC-ACCT-ID IS NOT NUMERIC)
   * 3. API call to retrieve billing data (replaces EXEC CICS READ FILE('ACCTDAT'))
   */
  useEffect(() => {
    // Redirect to login if not authenticated
    // COBOL equivalent: RACF authentication check
    if (!isAuthenticated) {
      navigate('/login');
      return;
    }

    // Validate account ID from route parameter
    if (!accountId || accountId.trim() === '') {
      setError('Account ID is required');
      setLoading(false);
      return;
    }

    // Fetch billing information
    const fetchBillingInfo = async () => {
      try {
        setLoading(true);
        setError('');

        // COBOL: EXEC CICS READ FILE('ACCTDAT') RIDFLD(ACCT-ID) INTO(ACCOUNT-RECORD)
        const info = await getBillingInfo(accountId);
        setBillingInfo(info);
      } catch (err: any) {
        // COBOL equivalent: HANDLE CONDITION NOTFND, ERROR handling
        const errorMessage = err.response?.data?.message || err.message || 'Failed to retrieve billing information';
        setError(errorMessage);
        console.error('Error fetching billing information:', err);
      } finally {
        setLoading(false);
      }
    };

    fetchBillingInfo();
  }, [accountId, isAuthenticated, navigate]);

  /**
   * Handle payment form submission
   * 
   * COBOL equivalent: COBIL00C.cbl PROCESS-ENTER-KEY when CONF-PAY-YES
   * 
   * Performs:
   * 1. Validate payment amount (replaces COBOL IF ACCT-CURR-BAL <= ZEROS)
   * 2. Post payment transaction (replaces WRITE-TRANSACT-FILE)
   * 3. Update account balance (replaces UPDATE-ACCTDAT-FILE)
   * 4. Display success message and navigate
   * 
   * COBOL WRITE-TRANSACT-FILE logic:
   * - MOVE WS-TRAN-ID-NUM TO TRAN-ID
   * - MOVE '02' TO TRAN-TYPE-CD (Payment transaction type)
   * - MOVE 2 TO TRAN-CAT-CD
   * - MOVE 'BILL PAYMENT - ONLINE' TO TRAN-DESC
   * - MOVE payment amount TO TRAN-AMT
   * - EXEC CICS WRITE DATASET('TRANSACT') FROM(TRAN-RECORD) RIDFLD(TRAN-ID)
   * 
   * COBOL UPDATE-ACCTDAT-FILE logic:
   * - COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT
   * - EXEC CICS REWRITE DATASET('ACCTDAT') FROM(ACCOUNT-RECORD)
   * 
   * @param values - Payment form values with paymentAmount
   * @param formikHelpers - Formik helpers for form manipulation
   */
  const handlePaymentSubmit = async (
    values: PaymentFormValues,
    { setSubmitting: setFormSubmitting, resetForm }: FormikHelpers<PaymentFormValues>
  ) => {
    try {
      setSubmitting(true);
      setError('');
      setSuccessMessage('');

      // Validate billing info is loaded
      if (!billingInfo || !accountId) {
        setError('Billing information not available');
        return;
      }

      // Parse payment amount
      const paymentAmount = parseFloat(values.paymentAmount);

      // Additional validation: payment cannot exceed current balance
      // COBOL equivalent: IF TRAN-AMT > ACCT-CURR-BAL validation
      if (paymentAmount > billingInfo.currentBalance) {
        setError(`Payment amount cannot exceed current balance of ${formatCurrency(billingInfo.currentBalance)}`);
        return;
      }

      // Create payment request
      // COBOL equivalent: Moving values to TRAN-RECORD fields
      const paymentRequest: PaymentRequest = {
        accountId: accountId,
        paymentAmount: paymentAmount,
      };

      // Post payment to backend
      // COBOL: EXEC CICS WRITE FILE('TRANSACT') + EXEC CICS REWRITE FILE('ACCTDAT')
      const paymentResponse: PaymentResponse = await postPayment(paymentRequest);

      // Check payment success
      // COBOL equivalent: IF DFHRESP(NORMAL) after WRITE and REWRITE
      if (paymentResponse.success) {
        // Update local billing info with new balance
        setBillingInfo({
          ...billingInfo,
          currentBalance: paymentResponse.newBalance,
        });

        // Display success message
        // COBOL equivalent: MOVE 'Payment successful...' TO WS-MESSAGE
        setSuccessMessage(
          `Payment successful! Transaction ID: ${paymentResponse.transactionId}. New balance: ${formatCurrency(paymentResponse.newBalance)}`
        );

        // Reset form
        resetForm();

        // Navigate to account view after 3 seconds
        // COBOL equivalent: EXEC CICS RETURN TRANSID('ACVW')
        setTimeout(() => {
          navigate(`/accounts/${accountId}`);
        }, 3000);
      } else {
        // Payment failed
        setError(paymentResponse.message || 'Payment processing failed');
      }
    } catch (err: any) {
      // COBOL equivalent: HANDLE CONDITION ERROR
      const errorMessage = err.response?.data?.message || err.message || 'Failed to process payment';
      setError(errorMessage);
      console.error('Error processing payment:', err);
    } finally {
      setSubmitting(false);
      setFormSubmitting(false);
    }
  };

  /**
   * Handle cancel button click
   * 
   * COBOL equivalent: EXEC CICS RETURN when F3=Back pressed
   */
  const handleCancel = useCallback(() => {
    navigate('/accounts');
  }, [navigate]);

  /**
   * Render loading state
   * 
   * COBOL equivalent: Processing indicator during file I/O
   */
  if (loading) {
    return (
      <>
        <Header />
        <Container maxWidth="lg" sx={{ mt: 4, mb: 4 }}>
          <LoadingSpinner />
        </Container>
        <Footer />
      </>
    );
  }

  /**
   * Render error state without billing info
   * 
   * COBOL equivalent: ERRMSG field display after NOTFND or ERROR condition
   */
  if (error && !billingInfo) {
    return (
      <>
        <Header />
        <Container maxWidth="lg" sx={{ mt: 4, mb: 4 }}>
          <ErrorMessage message={error} />
          <Box sx={{ mt: 2 }}>
            <Button variant="contained" onClick={handleCancel}>
              Back to Accounts
            </Button>
          </Box>
        </Container>
        <Footer />
      </>
    );
  }

  /**
   * Main component render
   * 
   * COBOL equivalent: COBIL00.bms screen layout with billing fields and payment form
   */
  return (
    <>
      <Header />
      <Container maxWidth="lg" sx={{ mt: 4, mb: 4 }}>
        {/* Page Title - BMS POS=(4,35) 'Bill Payment' */}
        <Typography variant="h4" component="h1" gutterBottom sx={{ mb: 3, textAlign: 'center' }}>
          Bill Payment
        </Typography>

        {/* Success Message Display */}
        {successMessage && (
          <Alert severity="success" sx={{ mb: 3 }}>
            {successMessage}
          </Alert>
        )}

        {/* Error Message Display - BMS ERRMSG field POS=(23,1) */}
        {error && <ErrorMessage message={error} />}

        {/* Account Information Section */}
        <Card sx={{ mb: 3 }}>
          <CardContent>
            <Typography variant="h6" gutterBottom color="primary">
              Account Information
            </Typography>
            <Divider sx={{ mb: 2 }} />

            {billingInfo && (
              <Grid container spacing={3}>
                {/* Account ID Display */}
                <Grid item xs={12} sm={6}>
                  <Typography variant="body2" color="text.secondary">
                    Account ID
                  </Typography>
                  <Typography variant="body1" fontWeight="bold">
                    {billingInfo.accountId}
                  </Typography>
                </Grid>

                {/* Current Balance Display - BMS CURBAL field POS=(11,32) */}
                <Grid item xs={12} sm={6}>
                  <Typography variant="body2" color="text.secondary">
                    Current Balance
                  </Typography>
                  <Typography variant="h5" color="primary" fontWeight="bold">
                    {formatCurrency(billingInfo.currentBalance)}
                  </Typography>
                </Grid>

                {/* Payment Due Date Display */}
                <Grid item xs={12} sm={6}>
                  <Typography variant="body2" color="text.secondary">
                    Payment Due Date
                  </Typography>
                  <Typography variant="body1" fontWeight="bold">
                    {billingInfo.paymentDueDate ? formatDate(new Date(billingInfo.paymentDueDate)) : 'N/A'}
                  </Typography>
                </Grid>

                {/* Minimum Payment Display */}
                <Grid item xs={12} sm={6}>
                  <Typography variant="body2" color="text.secondary">
                    Minimum Payment
                  </Typography>
                  <Typography variant="body1" fontWeight="bold">
                    {formatCurrency(billingInfo.minimumPayment)}
                  </Typography>
                </Grid>

                {/* Statement Date Display */}
                <Grid item xs={12} sm={6}>
                  <Typography variant="body2" color="text.secondary">
                    Statement Date
                  </Typography>
                  <Typography variant="body1" fontWeight="bold">
                    {billingInfo.statementDate ? formatDate(new Date(billingInfo.statementDate)) : 'N/A'}
                  </Typography>
                </Grid>

                {/* Last Payment Information (Optional) */}
                {billingInfo.lastPaymentDate && (
                  <>
                    <Grid item xs={12} sm={6}>
                      <Typography variant="body2" color="text.secondary">
                        Last Payment Date
                      </Typography>
                      <Typography variant="body1" fontWeight="bold">
                        {formatDate(new Date(billingInfo.lastPaymentDate))}
                      </Typography>
                    </Grid>
                    <Grid item xs={12} sm={6}>
                      <Typography variant="body2" color="text.secondary">
                        Last Payment Amount
                      </Typography>
                      <Typography variant="body1" fontWeight="bold">
                        {formatCurrency(billingInfo.lastPaymentAmount || 0)}
                      </Typography>
                    </Grid>
                  </>
                )}
              </Grid>
            )}
          </CardContent>
        </Card>

        {/* Payment Form Section - BMS CONFIRM field POS=(15,60) */}
        <Card>
          <CardContent>
            <Typography variant="h6" gutterBottom color="primary">
              Make a Payment
            </Typography>
            <Divider sx={{ mb: 3 }} />

            <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
              Enter the payment amount below. Payment will be applied to your account balance immediately.
            </Typography>

            {/* Formik Payment Form */}
            <Formik
              initialValues={{ paymentAmount: '' }}
              validationSchema={paymentValidationSchema}
              onSubmit={handlePaymentSubmit}
              validateOnBlur={true}
              validateOnChange={false}
            >
              {({ errors, touched, isSubmitting }) => (
                <Form>
                  <Grid container spacing={3}>
                    {/* Payment Amount Input Field */}
                    <Grid item xs={12} sm={6}>
                      <Field name="paymentAmount">
                        {({ field }: any) => (
                          <TextField
                            {...field}
                            label="Payment Amount"
                            placeholder="0.00"
                            fullWidth
                            required
                            type="text"
                            inputProps={{
                              inputMode: 'decimal',
                              pattern: '[0-9]*[.]?[0-9]{0,2}',
                            }}
                            error={touched.paymentAmount && Boolean(errors.paymentAmount)}
                            helperText={
                              touched.paymentAmount && errors.paymentAmount
                                ? errors.paymentAmount
                                : 'Enter amount with up to 2 decimal places'
                            }
                            disabled={submitting || isSubmitting}
                            sx={{ backgroundColor: 'background.paper' }}
                          />
                        )}
                      </Field>
                    </Grid>

                    {/* Form Action Buttons */}
                    <Grid item xs={12}>
                      <Box sx={{ display: 'flex', gap: 2 }}>
                        {/* Submit Payment Button - COBOL ENTER=Continue */}
                        <Button
                          type="submit"
                          variant="contained"
                          color="primary"
                          disabled={submitting || isSubmitting || !billingInfo}
                          sx={{ minWidth: 120 }}
                        >
                          {submitting || isSubmitting ? 'Processing...' : 'Submit Payment'}
                        </Button>

                        {/* Cancel Button - COBOL F3=Back */}
                        <Button
                          variant="outlined"
                          color="secondary"
                          onClick={handleCancel}
                          disabled={submitting || isSubmitting}
                          sx={{ minWidth: 120 }}
                        >
                          Cancel
                        </Button>
                      </Box>
                    </Grid>

                    {/* Payment Confirmation Instructions - BMS POS=(15,6) */}
                    <Grid item xs={12}>
                      <Alert severity="info">
                        Please verify the payment amount before submitting. Once processed, the payment will be
                        posted to your account and cannot be reversed through this system.
                      </Alert>
                    </Grid>
                  </Grid>
                </Form>
              )}
            </Formik>
          </CardContent>
        </Card>

        {/* Help Text - BMS POS=(24,1) Function key instructions */}
        <Box sx={{ mt: 3 }}>
          <Typography variant="caption" color="text.secondary">
            ENTER=Submit Payment | Cancel=Return to Accounts
          </Typography>
        </Box>
      </Container>
      <Footer />
    </>
  );
};

// Default export for component
export default BillingPage;
