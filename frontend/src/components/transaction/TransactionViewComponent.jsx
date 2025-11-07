/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * TransactionViewComponent.jsx
 * 
 * React component for displaying detailed single transaction information.
 * Transforms BMS COTRN01M.bms screen to modern Material-UI interface.
 * 
 * Features:
 * - Transaction ID input field with fetch functionality
 * - Display all transaction fields in read-only format
 * - Complete merchant details display
 * - BigDecimal amount formatting (2 decimal precision)
 * - Date formatting for origin and process dates
 * - Error message display
 * - Navigation controls (Back, Clear, Browse)
 * 
 * REST API Integration:
 * - GET /api/transactions/{id} - Fetch transaction details
 */

import React, { useState, useEffect } from 'react';
import {
  Box,
  Button,
  Container,
  TextField,
  Typography,
  Paper,
  Grid,
  Alert,
  CircularProgress,
  Divider,
  InputAdornment,
  IconButton
} from '@mui/material';
import {
  ArrowBack as ArrowBackIcon,
  Clear as ClearIcon,
  List as ListIcon,
  Search as SearchIcon
} from '@mui/icons-material';
import { useNavigate, useLocation } from 'react-router-dom';
import axios from 'axios';

/**
 * TransactionViewComponent - Main functional component
 * 
 * @returns {JSX.Element} Rendered transaction view component
 */
const TransactionViewComponent = () => {
  // Navigation hooks
  const navigate = useNavigate();
  const location = useLocation();

  // Component state management
  const [transactionId, setTransactionId] = useState('');
  const [transactionData, setTransactionData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [inputError, setInputError] = useState('');

  /**
   * Initialize component with transaction ID from navigation state if available
   */
  useEffect(() => {
    if (location.state?.transactionId) {
      const preloadedId = location.state.transactionId;
      setTransactionId(preloadedId);
      fetchTransactionDetails(preloadedId);
    }
  }, [location.state]);

  /**
   * Format amount with BigDecimal precision (2 decimal places)
   * Matches COBOL PIC S9(09)V99 format
   * 
   * @param {number|string} amount - Transaction amount
   * @returns {string} Formatted amount string
   */
  const formatAmount = (amount) => {
    if (amount === null || amount === undefined) {
      return '0.00';
    }
    const numericAmount = typeof amount === 'string' ? parseFloat(amount) : amount;
    return numericAmount.toFixed(2);
  };

  /**
   * Format timestamp to display date
   * Converts ISO timestamp to YYYY-MM-DD format
   * 
   * @param {string} timestamp - ISO timestamp string
   * @returns {string} Formatted date string
   */
  const formatDate = (timestamp) => {
    if (!timestamp) {
      return '';
    }
    try {
      const date = new Date(timestamp);
      return date.toISOString().split('T')[0]; // YYYY-MM-DD format
    } catch (error) {
      return timestamp.substring(0, 10); // Fallback to first 10 characters
    }
  };

  /**
   * Validate transaction ID input
   * Transaction ID must be non-empty and up to 16 characters
   * 
   * @param {string} id - Transaction ID to validate
   * @returns {boolean} True if valid, false otherwise
   */
  const validateTransactionId = (id) => {
    if (!id || id.trim() === '') {
      setInputError('Tran ID can NOT be empty...');
      return false;
    }
    if (id.length > 16) {
      setInputError('Tran ID must be 16 characters or less');
      return false;
    }
    setInputError('');
    return true;
  };

  /**
   * Fetch transaction details from REST API
   * GET /api/transactions/{transactionId}
   * 
   * Maps to COTRN01C.cbl PROCESS-ENTER-KEY and READ-TRANSACT-FILE logic
   * 
   * @param {string} id - Transaction ID to fetch (optional, uses state if not provided)
   */
  const fetchTransactionDetails = async (id = null) => {
    const targetId = id || transactionId;

    // Validate input
    if (!validateTransactionId(targetId)) {
      return;
    }

    setLoading(true);
    setError('');
    setTransactionData(null);

    try {
      // API call to fetch transaction details
      const response = await axios.get(`/api/transactions/${targetId.trim()}`, {
        headers: {
          'Content-Type': 'application/json',
          // JWT token would be added here by axios interceptor
        }
      });

      // Successful response - populate transaction data
      setTransactionData(response.data);
      setError('');
    } catch (err) {
      // Error handling matching COBOL error scenarios
      if (err.response) {
        switch (err.response.status) {
          case 404:
            setError('Transaction ID NOT found...');
            break;
          case 401:
            setError('Unauthorized. Please login again.');
            navigate('/login');
            break;
          case 403:
            setError('Access denied. Insufficient privileges.');
            break;
          default:
            setError('Unable to lookup Transaction...');
        }
      } else if (err.request) {
        setError('Network error. Unable to connect to server.');
      } else {
        setError('An unexpected error occurred.');
      }
      setTransactionData(null);
    } finally {
      setLoading(false);
    }
  };

  /**
   * Handle Enter key press in transaction ID input field
   * Triggers fetch operation
   * 
   * @param {KeyboardEvent} event - Keyboard event
   */
  const handleKeyPress = (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      fetchTransactionDetails();
    }
  };

  /**
   * Clear current screen - reset all fields
   * Maps to COTRN01C.cbl CLEAR-CURRENT-SCREEN logic (PF4)
   */
  const handleClear = () => {
    setTransactionId('');
    setTransactionData(null);
    setError('');
    setInputError('');
  };

  /**
   * Navigate back to previous screen
   * Maps to COTRN01C.cbl RETURN-TO-PREV-SCREEN logic (PF3)
   */
  const handleBack = () => {
    // Navigate to transaction list or menu based on navigation history
    if (location.state?.from) {
      navigate(location.state.from);
    } else {
      navigate('/menu');
    }
  };

  /**
   * Navigate to transaction list/browse screen
   * Maps to PF5 functionality in COTRN01C.cbl
   */
  const handleBrowseTransactions = () => {
    navigate('/transactions');
  };

  return (
    <Container maxWidth="lg" sx={{ mt: 4, mb: 4 }}>
      <Paper elevation={3} sx={{ p: 4 }}>
        {/* Header Section - Matches BMS screen title */}
        <Box sx={{ mb: 3, textAlign: 'center' }}>
          <Typography variant="h4" component="h1" gutterBottom>
            View Transaction
          </Typography>
          <Typography variant="subtitle1" color="text.secondary">
            Transaction Detail Information
          </Typography>
        </Box>

        <Divider sx={{ mb: 3 }} />

        {/* Transaction ID Input Section - Maps to TRNIDIN field */}
        <Box sx={{ mb: 4 }}>
          <Grid container spacing={2} alignItems="center">
            <Grid item xs={12} md={8}>
              <TextField
                fullWidth
                label="Enter Tran ID"
                value={transactionId}
                onChange={(e) => setTransactionId(e.target.value)}
                onKeyPress={handleKeyPress}
                error={!!inputError}
                helperText={inputError}
                placeholder="Enter transaction ID (up to 16 characters)"
                autoFocus
                InputProps={{
                  endAdornment: (
                    <InputAdornment position="end">
                      <IconButton
                        onClick={() => fetchTransactionDetails()}
                        edge="end"
                        disabled={loading}
                        color="primary"
                      >
                        <SearchIcon />
                      </IconButton>
                    </InputAdornment>
                  ),
                }}
                sx={{
                  '& .MuiInputBase-input': {
                    fontFamily: 'monospace',
                  }
                }}
              />
            </Grid>
            <Grid item xs={12} md={4}>
              <Button
                fullWidth
                variant="contained"
                onClick={() => fetchTransactionDetails()}
                disabled={loading}
                startIcon={loading ? <CircularProgress size={20} /> : <SearchIcon />}
              >
                {loading ? 'Fetching...' : 'Fetch Transaction'}
              </Button>
            </Grid>
          </Grid>
        </Box>

        {/* Error Message Display - Maps to ERRMSG field */}
        {error && (
          <Alert severity="error" sx={{ mb: 3 }}>
            {error}
          </Alert>
        )}

        {/* Loading Indicator */}
        {loading && (
          <Box sx={{ display: 'flex', justifyContent: 'center', my: 4 }}>
            <CircularProgress />
          </Box>
        )}

        {/* Transaction Details Section - Maps to all display fields */}
        {transactionData && !loading && (
          <Box>
            <Typography variant="h6" gutterBottom sx={{ mt: 2, mb: 2 }}>
              Transaction Details
            </Typography>
            
            <Grid container spacing={3}>
              {/* Transaction ID and Card Number - Row 1 */}
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  label="Transaction ID"
                  value={transactionData.transactionId || ''}
                  InputProps={{
                    readOnly: true,
                  }}
                  sx={{
                    '& .MuiInputBase-input': {
                      fontFamily: 'monospace',
                      color: 'primary.main',
                    }
                  }}
                />
              </Grid>
              <Grid item xs={12} md={6}>
                <TextField
                  fullWidth
                  label="Card Number"
                  value={transactionData.cardNumber || ''}
                  InputProps={{
                    readOnly: true,
                  }}
                  sx={{
                    '& .MuiInputBase-input': {
                      fontFamily: 'monospace',
                      color: 'primary.main',
                    }
                  }}
                />
              </Grid>

              {/* Type Code, Category Code, and Source - Row 2 */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  label="Type CD"
                  value={transactionData.typeCode || ''}
                  InputProps={{
                    readOnly: true,
                  }}
                  sx={{
                    '& .MuiInputBase-input': {
                      color: 'primary.main',
                    }
                  }}
                />
              </Grid>
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  label="Category CD"
                  value={transactionData.categoryCode || ''}
                  InputProps={{
                    readOnly: true,
                  }}
                  sx={{
                    '& .MuiInputBase-input': {
                      color: 'primary.main',
                    }
                  }}
                />
              </Grid>
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  label="Source"
                  value={transactionData.source || ''}
                  InputProps={{
                    readOnly: true,
                  }}
                  sx={{
                    '& .MuiInputBase-input': {
                      color: 'primary.main',
                    }
                  }}
                />
              </Grid>

              {/* Description - Row 3 */}
              <Grid item xs={12}>
                <TextField
                  fullWidth
                  label="Description"
                  value={transactionData.description || ''}
                  InputProps={{
                    readOnly: true,
                  }}
                  multiline
                  rows={2}
                  sx={{
                    '& .MuiInputBase-input': {
                      color: 'primary.main',
                    }
                  }}
                />
              </Grid>

              {/* Amount and Dates - Row 4 */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  label="Amount"
                  value={`$${formatAmount(transactionData.amount)}`}
                  InputProps={{
                    readOnly: true,
                  }}
                  sx={{
                    '& .MuiInputBase-input': {
                      fontFamily: 'monospace',
                      color: 'primary.main',
                      fontWeight: 'bold',
                    }
                  }}
                />
              </Grid>
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  label="Orig Date"
                  value={formatDate(transactionData.originDate)}
                  InputProps={{
                    readOnly: true,
                  }}
                  sx={{
                    '& .MuiInputBase-input': {
                      fontFamily: 'monospace',
                      color: 'primary.main',
                    }
                  }}
                />
              </Grid>
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  label="Proc Date"
                  value={formatDate(transactionData.processDate)}
                  InputProps={{
                    readOnly: true,
                  }}
                  sx={{
                    '& .MuiInputBase-input': {
                      fontFamily: 'monospace',
                      color: 'primary.main',
                    }
                  }}
                />
              </Grid>

              {/* Merchant Details Section */}
              <Grid item xs={12}>
                <Divider sx={{ my: 2 }} />
                <Typography variant="h6" gutterBottom>
                  Merchant Information
                </Typography>
              </Grid>

              {/* Merchant ID and Name - Row 5 */}
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  label="Merchant ID"
                  value={transactionData.merchantId || ''}
                  InputProps={{
                    readOnly: true,
                  }}
                  sx={{
                    '& .MuiInputBase-input': {
                      fontFamily: 'monospace',
                      color: 'primary.main',
                    }
                  }}
                />
              </Grid>
              <Grid item xs={12} md={8}>
                <TextField
                  fullWidth
                  label="Merchant Name"
                  value={transactionData.merchantName || ''}
                  InputProps={{
                    readOnly: true,
                  }}
                  sx={{
                    '& .MuiInputBase-input': {
                      color: 'primary.main',
                    }
                  }}
                />
              </Grid>

              {/* Merchant City and Zip - Row 6 */}
              <Grid item xs={12} md={8}>
                <TextField
                  fullWidth
                  label="Merchant City"
                  value={transactionData.merchantCity || ''}
                  InputProps={{
                    readOnly: true,
                  }}
                  sx={{
                    '& .MuiInputBase-input': {
                      color: 'primary.main',
                    }
                  }}
                />
              </Grid>
              <Grid item xs={12} md={4}>
                <TextField
                  fullWidth
                  label="Merchant Zip"
                  value={transactionData.merchantZip || ''}
                  InputProps={{
                    readOnly: true,
                  }}
                  sx={{
                    '& .MuiInputBase-input': {
                      fontFamily: 'monospace',
                      color: 'primary.main',
                    }
                  }}
                />
              </Grid>
            </Grid>
          </Box>
        )}

        {/* Navigation Buttons - Maps to PF keys */}
        <Box sx={{ mt: 4, display: 'flex', gap: 2, flexWrap: 'wrap' }}>
          <Button
            variant="outlined"
            startIcon={<ArrowBackIcon />}
            onClick={handleBack}
          >
            Back (F3)
          </Button>
          <Button
            variant="outlined"
            startIcon={<ClearIcon />}
            onClick={handleClear}
          >
            Clear (F4)
          </Button>
          <Button
            variant="outlined"
            startIcon={<ListIcon />}
            onClick={handleBrowseTransactions}
          >
            Browse Transactions (F5)
          </Button>
        </Box>

        {/* Footer Help Text */}
        <Box sx={{ mt: 3, p: 2, bgcolor: 'grey.100', borderRadius: 1 }}>
          <Typography variant="body2" color="text.secondary">
            <strong>Instructions:</strong> Enter a transaction ID and press ENTER or click "Fetch Transaction" to view details.
            Use navigation buttons to go back, clear the form, or browse all transactions.
          </Typography>
        </Box>
      </Paper>
    </Container>
  );
};

export default TransactionViewComponent;
