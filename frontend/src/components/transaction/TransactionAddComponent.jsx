/**
 * TransactionAddComponent.jsx
 * 
 * React component for adding new transactions to the CardDemo application.
 * Transformed from COBOL program COTRN02C.cbl and BMS mapset COTRN02M.bms.
 * 
 * This component provides a comprehensive form for transaction entry with:
 * - Account ID or Card Number input (mutually exclusive)
 * - Transaction details (type, category, source, description, amount)
 * - Date fields (origin date, process date)
 * - Merchant information (ID, name, city, zip)
 * - Full validation matching COBOL business logic
 * - Confirmation prompt before submission
 * 
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useFormik } from 'formik';
import * as Yup from 'yup';
import {
  Box,
  Container,
  TextField,
  Button,
  Typography,
  Paper,
  Grid,
  Alert,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogContentText,
  DialogActions,
  Divider,
  Snackbar,
} from '@mui/material';
import axios from 'axios';

/**
 * Validation schema using Yup
 * Matches validation logic from COTRN02C.cbl lines 193-430
 */
const validationSchema = Yup.object({
  accountId: Yup.string()
    .test('accountId-or-cardNumber', 'Account ID or Card Number must be entered', function(value) {
      const { cardNumber } = this.parent;
      // At least one must be provided
      if (!value && !cardNumber) {
        return false;
      }
      // If provided, must be 11 digits
      if (value && !/^\d{11}$/.test(value)) {
        return this.createError({ message: 'Account ID must be 11 numeric digits' });
      }
      return true;
    }),
  
  cardNumber: Yup.string()
    .test('cardNumber-or-accountId', 'Account ID or Card Number must be entered', function(value) {
      const { accountId } = this.parent;
      // At least one must be provided
      if (!value && !accountId) {
        return false;
      }
      // If provided, must be 16 digits
      if (value && !/^\d{16}$/.test(value)) {
        return this.createError({ message: 'Card Number must be 16 numeric digits' });
      }
      return true;
    }),
  
  transactionTypeCode: Yup.string()
    .required('Type CD can NOT be empty')
    .matches(/^\d{2}$/, 'Type CD must be 2 numeric digits'),
  
  transactionCategoryCode: Yup.string()
    .required('Category CD can NOT be empty')
    .matches(/^\d{1,4}$/, 'Category CD must be numeric (1-4 digits)'),
  
  source: Yup.string()
    .required('Source can NOT be empty')
    .max(10, 'Source must not exceed 10 characters'),
  
  description: Yup.string()
    .required('Description can NOT be empty')
    .max(100, 'Description must not exceed 100 characters'),
  
  amount: Yup.string()
    .required('Amount can NOT be empty')
    .test('amount-format', 'Amount must be a positive number', function(value) {
      if (!value) return false;
      
      // First, check if it's a valid number format (more lenient)
      // Allow optional sign, any number of digits, optional decimal point with any number of digits
      const basicNumberRegex = /^[+-]?\d+(\.\d+)?$/;
      if (!basicNumberRegex.test(value)) {
        return this.createError({ message: 'Amount should be in format 99999999.99' });
      }
      
      // Parse the numeric value
      const numValue = parseFloat(value);
      if (isNaN(numValue)) {
        return this.createError({ message: 'Amount should be in format 99999999.99' });
      }
      
      // Check if positive (matching COBOL logic - negative amounts would fail authorization)
      if (numValue <= 0) {
        return this.createError({ message: 'Amount must be a positive number' });
      }
      
      // Check if exceeds 2 decimal places (check this before max value)
      const decimalMatch = value.match(/\.(\d+)$/);
      if (decimalMatch && decimalMatch[1].length > 2) {
        return this.createError({ message: 'Amount must have at most 2 decimal places' });
      }
      
      // Check maximum value (check this after decimal places)
      if (numValue > 99999999.99) {
        return this.createError({ message: 'Amount cannot exceed 99999999.99' });
      }
      
      // Check if more than 8 digits before decimal
      const beforeDecimal = value.split('.')[0].replace(/^[+-]/, '');
      if (beforeDecimal.length > 8) {
        return this.createError({ message: 'Amount cannot exceed 99999999.99' });
      }
      
      return true;
    }),
  
  originDate: Yup.string()
    .required('Orig Date can NOT be empty')
    .matches(/^\d{4}-\d{2}-\d{2}$/, 'Date must be in YYYY-MM-DD format')
    .test('valid-date', 'Invalid date', function(value) {
      if (!value) return false;
      const date = new Date(value);
      if (!(date instanceof Date) || isNaN(date)) {
        return false;
      }
      // Check if the date components match what was entered
      // This catches cases like 2024-02-30 which JS converts to 2024-03-01
      const [year, month, day] = value.split('-').map(Number);
      return date.getFullYear() === year && 
             date.getMonth() === month - 1 && 
             date.getDate() === day;
    }),
  
  processDate: Yup.string()
    .required('Proc Date can NOT be empty')
    .matches(/^\d{4}-\d{2}-\d{2}$/, 'Date must be in YYYY-MM-DD format')
    .test('valid-date', 'Invalid date', function(value) {
      if (!value) return false;
      const date = new Date(value);
      if (!(date instanceof Date) || isNaN(date)) {
        return false;
      }
      // Check if the date components match what was entered
      // This catches cases like 2024-02-30 which JS converts to 2024-03-01
      const [year, month, day] = value.split('-').map(Number);
      return date.getFullYear() === year && 
             date.getMonth() === month - 1 && 
             date.getDate() === day;
    }),
  
  merchantId: Yup.string()
    .required('Merchant ID can NOT be empty')
    .matches(/^\d{9}$/, 'Merchant ID must be 9 numeric digits'),
  
  merchantName: Yup.string()
    .required('Merchant Name can NOT be empty')
    .max(50, 'Merchant Name must not exceed 50 characters'),
  
  merchantCity: Yup.string()
    .required('Merchant City can NOT be empty')
    .max(50, 'Merchant City must not exceed 50 characters'),
  
  merchantZip: Yup.string()
    .required('Merchant Zip can NOT be empty')
    .max(10, 'Merchant Zip must not exceed 10 characters'),
});

/**
 * TransactionAddComponent
 * 
 * Main component for adding new transactions.
 * Matches functionality from COTRN02C.cbl COBOL program.
 */
const TransactionAddComponent = () => {
  const navigate = useNavigate();
  const [errorMessage, setErrorMessage] = useState('');
  const [successMessage, setSuccessMessage] = useState('');
  const [confirmDialogOpen, setConfirmDialogOpen] = useState(false);
  const [lastTransaction, setLastTransaction] = useState(null);
  const [snackbarOpen, setSnackbarOpen] = useState(false);

  /**
   * Formik form management
   * Initial values match BMS screen field defaults
   */
  const formik = useFormik({
    initialValues: {
      accountId: '',
      cardNumber: '',
      transactionTypeCode: '',
      transactionCategoryCode: '',
      source: '',
      description: '',
      amount: '',
      originDate: new Date().toISOString().split('T')[0], // Default to today
      processDate: new Date().toISOString().split('T')[0], // Default to today
      merchantId: '',
      merchantName: '',
      merchantCity: '',
      merchantZip: '',
    },
    validationSchema: validationSchema,
    validateOnChange: true,
    validateOnBlur: true,
    onSubmit: () => {
      // Open confirmation dialog instead of submitting directly
      // Matches COBOL logic at lines 169-188 (PROCESS-ENTER-KEY)
      console.log('onSubmit called - opening confirmation dialog');
      setConfirmDialogOpen(true);
    },
  });

  /**
   * Custom submit handler that ensures all fields are touched
   * This allows validation errors to display when submit is clicked
   */
  const handleFormSubmit = async (e) => {
    console.log('handleFormSubmit called');
    e.preventDefault();
    
    // First validate the form to get current errors
    const errors = await formik.validateForm();
    console.log('Validation errors:', errors);
    console.log('Current formik.errors:', formik.errors);
    console.log('Current formik.touched:', formik.touched);
    
    // Set the errors in formik state
    formik.setErrors(errors);
    
    // Mark all fields as touched so validation errors will display
    const touchedFields = Object.keys(formik.values).reduce((acc, key) => {
      acc[key] = true;
      return acc;
    }, {});
    await formik.setTouched(touchedFields, true); // validateOnMount=true triggers validation
    
    console.log('After setErrors - formik.errors:', formik.errors);
    console.log('After setTouched - formik.touched:', formik.touched);
    
    // If validation passed, open confirmation dialog
    if (Object.keys(errors).length === 0) {
      console.log('Form is valid, opening confirmation dialog');
      setConfirmDialogOpen(true);
    } else {
      console.log('Form has errors, not submitting');
    }
  };

  /**
   * Handle confirmation dialog acceptance
   * Matches COBOL EVALUATE CONFIRMI logic (lines 169-188)
   */
  const handleConfirmAdd = async () => {
    setConfirmDialogOpen(false);
    setErrorMessage('');
    setSuccessMessage('');

    try {
      // Format amount to proper BigDecimal format
      const formattedAmount = parseFloat(formik.values.amount).toFixed(2);

      // Prepare request body matching Transaction entity structure
      const transactionRequest = {
        accountId: formik.values.accountId || null,
        cardNumber: formik.values.cardNumber || null,
        transactionTypeCode: formik.values.transactionTypeCode,
        transactionCategoryCode: parseInt(formik.values.transactionCategoryCode),
        source: formik.values.source,
        description: formik.values.description,
        amount: formattedAmount,
        originDate: formik.values.originDate,
        processDate: formik.values.processDate,
        merchantId: parseInt(formik.values.merchantId),
        merchantName: formik.values.merchantName,
        merchantCity: formik.values.merchantCity,
        merchantZip: formik.values.merchantZip,
      };

      // POST request to /api/transactions endpoint
      const response = await axios.post('/api/transactions', transactionRequest, {
        headers: {
          'Content-Type': 'application/json',
        },
      });

      // Store last transaction for copy functionality (F5 equivalent)
      setLastTransaction(formik.values);

      // Show success message
      setSuccessMessage(`Transaction added successfully! Transaction ID: ${response.data.transactionId || 'Generated'}`);
      setSnackbarOpen(true);

      // Reset form after successful submission
      formik.resetForm();

      // Navigate back to transaction list after a delay
      setTimeout(() => {
        navigate('/transactions');
      }, 2000);

    } catch (error) {
      // Handle error response matching COBOL error handling
      if (error.response) {
        // Server responded with error
        const errorMsg = error.response.data.message || 
                        error.response.data.error || 
                        'Failed to add transaction. Please try again.';
        setErrorMessage(errorMsg);
      } else if (error.request) {
        // Request made but no response
        setErrorMessage('Network error. Unable to reach server. Please check your connection.');
      } else {
        // Error in request setup
        setErrorMessage('Error: ' + error.message);
      }
    }
  };

  /**
   * Handle confirmation dialog cancellation
   * Matches COBOL logic for 'N' or 'n' confirmation (lines 173-174)
   */
  const handleConfirmCancel = () => {
    setConfirmDialogOpen(false);
    setErrorMessage('Transaction not added. Please review and confirm again.');
  };

  /**
   * Handle Clear button (F4 equivalent)
   * Matches COBOL F4 key processing
   */
  const handleClear = () => {
    formik.resetForm();
    setErrorMessage('');
    setSuccessMessage('');
  };

  /**
   * Handle Copy Last Transaction button (F5 equivalent)
   * Matches COBOL F5 key processing
   */
  const handleCopyLast = () => {
    if (lastTransaction) {
      formik.setValues(lastTransaction);
      setSuccessMessage('Last transaction copied successfully!');
      setSnackbarOpen(true);
    } else {
      setErrorMessage('No previous transaction to copy.');
    }
  };

  /**
   * Handle Back button (F3 equivalent)
   * Navigates back to transaction list
   */
  const handleBack = () => {
    navigate('/transactions');
  };

  /**
   * Handle account ID change
   * When account ID is entered, clear card number (mutually exclusive)
   * Matches COBOL logic at lines 196-208
   */
  const handleAccountIdChange = (e) => {
    formik.handleChange(e);
    if (e.target.value) {
      formik.setFieldValue('cardNumber', '');
    }
  };

  /**
   * Handle card number change
   * When card number is entered, clear account ID (mutually exclusive)
   * Matches COBOL logic at lines 210-223
   */
  const handleCardNumberChange = (e) => {
    formik.handleChange(e);
    if (e.target.value) {
      formik.setFieldValue('accountId', '');
    }
  };

  /**
   * Format amount field on blur to ensure proper format
   * Matches COBOL NUMVAL-C function logic (lines 383-386)
   */
  const handleAmountBlur = (e) => {
    formik.handleBlur(e);
    const value = e.target.value;
    
    if (value) {
      try {
        // Only format if the value is valid (passes all validation rules)
        // Check basic format
        const basicNumberRegex = /^[+-]?\d+(\.\d+)?$/;
        if (!basicNumberRegex.test(value)) {
          return; // Invalid format, don't reformat
        }
        
        // Parse value
        const numValue = parseFloat(value);
        if (isNaN(numValue)) {
          return; // Not a number, don't reformat
        }
        
        // Check if positive
        if (numValue <= 0) {
          return; // Negative or zero, don't reformat
        }
        
        // Check decimal places
        const decimalMatch = value.match(/\.(\d+)$/);
        if (decimalMatch && decimalMatch[1].length > 2) {
          return; // Too many decimal places, don't reformat
        }
        
        // Check max value
        if (numValue > 99999999.99) {
          return; // Too large, don't reformat
        }
        
        // Value is valid, format it with sign
        const sign = numValue >= 0 ? '+' : '';
        const formatted = sign + numValue.toFixed(2);
        formik.setFieldValue('amount', formatted);
      } catch (err) {
        // Leave value as-is if parsing fails
      }
    }
  };

  /**
   * Close success snackbar
   */
  const handleSnackbarClose = () => {
    setSnackbarOpen(false);
  };

  return (
    <Container maxWidth="lg" sx={{ mt: 4, mb: 4 }}>
      <Paper elevation={3} sx={{ p: 4 }}>
        {/* Header matching BMS screen title */}
        <Box sx={{ mb: 3 }}>
          <Typography variant="h4" component="h1" gutterBottom color="primary">
            Add Transaction
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Transaction: CT02 | Program: COTRN02C
          </Typography>
        </Box>

        <Divider sx={{ mb: 3 }} />

        {/* Error message display matching COBOL ERRMSG field */}
        {errorMessage && (
          <Alert severity="error" sx={{ mb: 3 }} onClose={() => setErrorMessage('')}>
            {errorMessage}
          </Alert>
        )}

        {/* Form starts here */}
        <form onSubmit={handleFormSubmit}>
          {/* Account ID or Card Number section */}
          <Box sx={{ mb: 3 }}>
            <Typography variant="h6" gutterBottom color="text.secondary">
              Account Identification
            </Typography>
            <Grid container spacing={2} alignItems="center">
              <Grid item xs={12} sm={5}>
                <TextField
                  fullWidth
                  id="accountId"
                  name="accountId"
                  label="Enter Acct #"
                  value={formik.values.accountId}
                  onChange={handleAccountIdChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.accountId && Boolean(formik.errors.accountId)}
                  helperText={formik.touched.accountId && formik.errors.accountId}
                  placeholder="11 digits"
                  inputProps={{ maxLength: 11 }}
                  sx={{ bgcolor: 'background.paper' }}
                />
              </Grid>
              <Grid item xs={12} sm={1}>
                <Typography variant="body1" align="center" color="text.secondary">
                  (or)
                </Typography>
              </Grid>
              <Grid item xs={12} sm={6}>
                <TextField
                  fullWidth
                  id="cardNumber"
                  name="cardNumber"
                  label="Card #"
                  value={formik.values.cardNumber}
                  onChange={handleCardNumberChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.cardNumber && Boolean(formik.errors.cardNumber)}
                  helperText={formik.touched.cardNumber && formik.errors.cardNumber}
                  placeholder="16 digits"
                  inputProps={{ maxLength: 16 }}
                  sx={{ bgcolor: 'background.paper' }}
                />
              </Grid>
            </Grid>
          </Box>

          <Divider sx={{ mb: 3 }} />

          {/* Transaction Details section */}
          <Box sx={{ mb: 3 }}>
            <Typography variant="h6" gutterBottom color="text.secondary">
              Transaction Details
            </Typography>
            <Grid container spacing={2}>
              <Grid item xs={12} sm={4}>
                <TextField
                  fullWidth
                  id="transactionTypeCode"
                  name="transactionTypeCode"
                  label="Type CD"
                  value={formik.values.transactionTypeCode}
                  onChange={formik.handleChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.transactionTypeCode && Boolean(formik.errors.transactionTypeCode)}
                  helperText={formik.touched.transactionTypeCode && formik.errors.transactionTypeCode}
                  placeholder="2 digits"
                  inputProps={{ maxLength: 2 }}
                  required
                  sx={{ bgcolor: 'background.paper' }}
                />
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  fullWidth
                  id="transactionCategoryCode"
                  name="transactionCategoryCode"
                  label="Category CD"
                  value={formik.values.transactionCategoryCode}
                  onChange={formik.handleChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.transactionCategoryCode && Boolean(formik.errors.transactionCategoryCode)}
                  helperText={formik.touched.transactionCategoryCode && formik.errors.transactionCategoryCode}
                  placeholder="4 digits"
                  inputProps={{ maxLength: 4 }}
                  required
                  sx={{ bgcolor: 'background.paper' }}
                />
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  fullWidth
                  id="source"
                  name="source"
                  label="Source"
                  value={formik.values.source}
                  onChange={formik.handleChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.source && Boolean(formik.errors.source)}
                  helperText={formik.touched.source && formik.errors.source}
                  placeholder="Max 10 chars"
                  inputProps={{ maxLength: 10 }}
                  required
                  sx={{ bgcolor: 'background.paper' }}
                />
              </Grid>
              <Grid item xs={12}>
                <TextField
                  fullWidth
                  id="description"
                  name="description"
                  label="Description"
                  value={formik.values.description}
                  onChange={formik.handleChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.description && Boolean(formik.errors.description)}
                  helperText={formik.touched.description && formik.errors.description}
                  placeholder="Max 100 characters"
                  inputProps={{ maxLength: 100 }}
                  required
                  sx={{ bgcolor: 'background.paper' }}
                />
              </Grid>
            </Grid>
          </Box>

          {/* Amount and Date section */}
          <Box sx={{ mb: 3 }}>
            <Grid container spacing={2}>
              <Grid item xs={12} sm={4}>
                <TextField
                  fullWidth
                  id="amount"
                  name="amount"
                  label="Amount"
                  value={formik.values.amount}
                  onChange={formik.handleChange}
                  onBlur={handleAmountBlur}
                  error={formik.touched.amount && Boolean(formik.errors.amount)}
                  helperText={formik.touched.amount && formik.errors.amount || '(-99999999.99)'}
                  placeholder="+1234.56"
                  inputProps={{ maxLength: 12 }}
                  required
                  sx={{ bgcolor: 'background.paper' }}
                />
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  fullWidth
                  id="originDate"
                  name="originDate"
                  label="Orig Date"
                  type="text"
                  value={formik.values.originDate}
                  onChange={formik.handleChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.originDate && Boolean(formik.errors.originDate)}
                  helperText={formik.touched.originDate && formik.errors.originDate || '(YYYY-MM-DD)'}
                  placeholder="YYYY-MM-DD"
                  required
                  sx={{ bgcolor: 'background.paper' }}
                />
              </Grid>
              <Grid item xs={12} sm={4}>
                <TextField
                  fullWidth
                  id="processDate"
                  name="processDate"
                  label="Proc Date"
                  type="text"
                  value={formik.values.processDate}
                  onChange={formik.handleChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.processDate && Boolean(formik.errors.processDate)}
                  helperText={formik.touched.processDate && formik.errors.processDate || '(YYYY-MM-DD)'}
                  placeholder="YYYY-MM-DD"
                  required
                  sx={{ bgcolor: 'background.paper' }}
                />
              </Grid>
            </Grid>
          </Box>

          <Divider sx={{ mb: 3 }} />

          {/* Merchant Information section */}
          <Box sx={{ mb: 3 }}>
            <Typography variant="h6" gutterBottom color="text.secondary">
              Merchant Information
            </Typography>
            <Grid container spacing={2}>
              <Grid item xs={12} sm={3}>
                <TextField
                  fullWidth
                  id="merchantId"
                  name="merchantId"
                  label="Merchant ID"
                  value={formik.values.merchantId}
                  onChange={formik.handleChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.merchantId && Boolean(formik.errors.merchantId)}
                  helperText={formik.touched.merchantId && formik.errors.merchantId}
                  placeholder="9 digits"
                  inputProps={{ maxLength: 9 }}
                  required
                  sx={{ bgcolor: 'background.paper' }}
                />
              </Grid>
              <Grid item xs={12} sm={9}>
                <TextField
                  fullWidth
                  id="merchantName"
                  name="merchantName"
                  label="Merchant Name"
                  value={formik.values.merchantName}
                  onChange={formik.handleChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.merchantName && Boolean(formik.errors.merchantName)}
                  helperText={formik.touched.merchantName && formik.errors.merchantName}
                  placeholder="Max 50 characters"
                  inputProps={{ maxLength: 50 }}
                  required
                  sx={{ bgcolor: 'background.paper' }}
                />
              </Grid>
              <Grid item xs={12} sm={6}>
                <TextField
                  fullWidth
                  id="merchantCity"
                  name="merchantCity"
                  label="Merchant City"
                  value={formik.values.merchantCity}
                  onChange={formik.handleChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.merchantCity && Boolean(formik.errors.merchantCity)}
                  helperText={formik.touched.merchantCity && formik.errors.merchantCity}
                  placeholder="Max 50 characters"
                  inputProps={{ maxLength: 50 }}
                  required
                  sx={{ bgcolor: 'background.paper' }}
                />
              </Grid>
              <Grid item xs={12} sm={6}>
                <TextField
                  fullWidth
                  id="merchantZip"
                  name="merchantZip"
                  label="Merchant Zip"
                  value={formik.values.merchantZip}
                  onChange={formik.handleChange}
                  onBlur={formik.handleBlur}
                  error={formik.touched.merchantZip && Boolean(formik.errors.merchantZip)}
                  helperText={formik.touched.merchantZip && formik.errors.merchantZip}
                  placeholder="Max 10 characters"
                  inputProps={{ maxLength: 10 }}
                  required
                  sx={{ bgcolor: 'background.paper' }}
                />
              </Grid>
            </Grid>
          </Box>

          <Divider sx={{ mb: 3 }} />

          {/* Action buttons matching BMS PF keys */}
          <Box sx={{ display: 'flex', gap: 2, justifyContent: 'space-between', flexWrap: 'wrap' }}>
            <Box sx={{ display: 'flex', gap: 2 }}>
              <Button
                type="submit"
                variant="contained"
                color="primary"
                size="large"
                disabled={formik.isSubmitting}
                onClick={async () => {
                  // Mark all fields as touched to show validation errors
                  const touchedFields = Object.keys(formik.values).reduce((acc, key) => {
                    acc[key] = true;
                    return acc;
                  }, {});
                  await formik.setTouched(touchedFields, true);
                }}
              >
                Add Transaction (ENTER)
              </Button>
              <Button
                variant="outlined"
                color="secondary"
                size="large"
                onClick={handleClear}
              >
                Clear (F4)
              </Button>
              <Button
                variant="outlined"
                color="info"
                size="large"
                onClick={handleCopyLast}
                disabled={!lastTransaction}
              >
                Copy Last (F5)
              </Button>
            </Box>
            <Button
              variant="outlined"
              size="large"
              onClick={handleBack}
            >
              Back (F3)
            </Button>
          </Box>
        </form>

        {/* Confirmation Dialog */}
        <Dialog
          open={confirmDialogOpen}
          onClose={handleConfirmCancel}
          aria-labelledby="confirm-dialog-title"
          aria-describedby="confirm-dialog-description"
        >
          <DialogTitle id="confirm-dialog-title">
            Confirm Transaction Addition
          </DialogTitle>
          <DialogContent>
            <DialogContentText id="confirm-dialog-description">
              You are about to add this transaction. Please confirm:
              <Box sx={{ mt: 2, p: 2, bgcolor: 'background.default', borderRadius: 1 }}>
                <Typography variant="body2"><strong>Amount:</strong> {formik.values.amount}</Typography>
                <Typography variant="body2"><strong>Description:</strong> {formik.values.description}</Typography>
                <Typography variant="body2"><strong>Merchant:</strong> {formik.values.merchantName}</Typography>
                <Typography variant="body2"><strong>Date:</strong> {formik.values.originDate}</Typography>
              </Box>
            </DialogContentText>
          </DialogContent>
          <DialogActions>
            <Button onClick={handleConfirmCancel} color="secondary">
              No (N)
            </Button>
            <Button onClick={handleConfirmAdd} color="primary" variant="contained" autoFocus>
              Yes (Y)
            </Button>
          </DialogActions>
        </Dialog>

        {/* Success Snackbar */}
        <Snackbar
          open={snackbarOpen}
          autoHideDuration={6000}
          onClose={handleSnackbarClose}
          anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
        >
          <Alert onClose={handleSnackbarClose} severity="success" sx={{ width: '100%' }}>
            {successMessage}
          </Alert>
        </Snackbar>
      </Paper>
    </Container>
  );
};

export default TransactionAddComponent;
