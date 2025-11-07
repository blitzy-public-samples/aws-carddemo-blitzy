/**
 * TransactionListComponent.jsx
 * 
 * React component for displaying paginated list of transactions.
 * Transforms BMS COTRN00M.bms 3270 terminal screen to modern web interface.
 * 
 * Features:
 * - Displays 10 transactions per page (matching COBOL COTRN00C.cbl pagination)
 * - Search by transaction ID functionality
 * - Selection mechanism to view transaction details
 * - Previous/Next pagination (mapping F7=Backward, F8=Forward)
 * - Error message display in red
 * - BigDecimal amount formatting
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import React, { useState, useEffect } from 'react';
import {
  Box,
  Paper,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Button,
  Typography,
  Alert,
  IconButton,
  Tooltip,
  Grid,
  CircularProgress,
  Divider
} from '@mui/material';
import {
  Search as SearchIcon,
  NavigateBefore as NavigateBeforeIcon,
  NavigateNext as NavigateNextIcon,
  Visibility as VisibilityIcon,
  ArrowBack as ArrowBackIcon
} from '@mui/icons-material';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';

/**
 * TransactionListComponent
 * 
 * Displays a paginated list of transactions with search and selection capabilities.
 * Matches the functionality of COBOL program COTRN00C.cbl and BMS map COTRN00M.bms.
 */
const TransactionListComponent = () => {
  // ============================================================================
  // State Management
  // ============================================================================
  
  // Transaction data and pagination state
  const [transactions, setTransactions] = useState([]);
  const [pageNumber, setPageNumber] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [totalRecords, setTotalRecords] = useState(0);
  const [loading, setLoading] = useState(false);
  
  // Search functionality state
  const [searchTransactionId, setSearchTransactionId] = useState('');
  const [searchInput, setSearchInput] = useState('');
  
  // Selection state (matching BMS SEL field functionality)
  const [selectedTransactionId, setSelectedTransactionId] = useState('');
  
  // Error handling state
  const [errorMessage, setErrorMessage] = useState('');
  const [validationError, setValidationError] = useState('');
  
  // React Router navigation
  const navigate = useNavigate();
  
  // ============================================================================
  // Constants (matching COBOL COTRN00C.cbl constants)
  // ============================================================================
  
  const TRANSACTIONS_PER_PAGE = 10; // Matches BMS screen layout (10 rows)
  const API_BASE_URL = '/api/transactions';
  
  // ============================================================================
  // Data Fetching - useEffect Hook
  // ============================================================================
  
  /**
   * Fetch transactions when component mounts or when page/search changes
   * Matches COBOL PROCESS-ENTER-KEY and pagination logic
   */
  useEffect(() => {
    fetchTransactions();
  }, [pageNumber, searchTransactionId]);
  
  /**
   * fetchTransactions
   * 
   * Retrieves paginated transaction data from REST API endpoint.
   * Maps to COBOL file I/O operations (READ TRANSACT file with STARTBR/READNEXT).
   * 
   * API Endpoint: GET /api/transactions
   * Query Parameters:
   *   - page: Page number (1-based)
   *   - size: Number of records per page (10)
   *   - transactionId: Optional search filter
   */
  const fetchTransactions = async () => {
    setLoading(true);
    setErrorMessage('');
    setValidationError('');
    
    try {
      // Build query parameters
      const params = {
        page: pageNumber - 1, // Convert to 0-based for backend
        size: TRANSACTIONS_PER_PAGE
      };
      
      // Add search filter if transaction ID is provided
      if (searchTransactionId && searchTransactionId.trim() !== '') {
        params.transactionId = searchTransactionId.trim();
      }
      
      // Execute API call
      const response = await axios.get(API_BASE_URL, { params });
      
      // Process response data
      if (response.data) {
        setTransactions(response.data.content || []);
        setTotalPages(response.data.totalPages || 1);
        setTotalRecords(response.data.totalElements || 0);
        setPageNumber(response.data.number + 1); // Convert back to 1-based
        
        // Clear any previous error messages on successful fetch
        setErrorMessage('');
      }
    } catch (error) {
      // Error handling matching COBOL error flag logic
      handleApiError(error);
      setTransactions([]);
      setTotalPages(1);
      setTotalRecords(0);
    } finally {
      setLoading(false);
    }
  };
  
  /**
   * handleApiError
   * 
   * Processes API errors and sets appropriate error messages.
   * Matches COBOL WS-ERR-FLG and WS-MESSAGE error handling.
   * 
   * @param {Error} error - The error object from axios
   */
  const handleApiError = (error) => {
    if (error.response) {
      // Server responded with error status
      const status = error.response.status;
      const data = error.response.data;
      
      if (status === 404) {
        setErrorMessage('No transactions found matching your criteria.');
      } else if (status === 400) {
        setErrorMessage(data.message || 'Invalid request parameters.');
      } else if (status === 401) {
        setErrorMessage('Authentication required. Please log in again.');
      } else if (status === 403) {
        setErrorMessage('You do not have permission to view transactions.');
      } else if (status === 500) {
        setErrorMessage('Server error occurred. Please try again later.');
      } else {
        setErrorMessage(data.message || 'An error occurred while fetching transactions.');
      }
    } else if (error.request) {
      // Request made but no response received
      setErrorMessage('Unable to connect to server. Please check your connection.');
    } else {
      // Error in request setup
      setErrorMessage('An unexpected error occurred: ' + error.message);
    }
  };
  
  // ============================================================================
  // Event Handlers
  // ============================================================================
  
  /**
   * handleSearchInputChange
   * 
   * Updates search input field state as user types.
   * Matches BMS TRNIDIN field input handling.
   * 
   * @param {Event} event - Input change event
   */
  const handleSearchInputChange = (event) => {
    const value = event.target.value;
    setSearchInput(value);
    setValidationError('');
  };
  
  /**
   * handleSearchSubmit
   * 
   * Validates and executes transaction ID search.
   * Matches COBOL transaction ID validation logic.
   */
  const handleSearchSubmit = () => {
    const trimmedInput = searchInput.trim();
    
    // Validation: Transaction ID must be numeric (matching COBOL validation)
    if (trimmedInput !== '' && !/^\d+$/.test(trimmedInput)) {
      setValidationError('Transaction ID must be numeric.');
      return;
    }
    
    // Reset to first page and execute search
    setSearchTransactionId(trimmedInput);
    setPageNumber(1);
    setValidationError('');
  };
  
  /**
   * handleClearSearch
   * 
   * Clears search filter and resets to show all transactions.
   */
  const handleClearSearch = () => {
    setSearchInput('');
    setSearchTransactionId('');
    setPageNumber(1);
    setValidationError('');
  };
  
  /**
   * handlePreviousPage
   * 
   * Navigates to previous page (matching COBOL PROCESS-PF7-KEY / F7=Backward).
   */
  const handlePreviousPage = () => {
    if (pageNumber > 1) {
      setPageNumber(pageNumber - 1);
    }
  };
  
  /**
   * handleNextPage
   * 
   * Navigates to next page (matching COBOL PROCESS-PF8-KEY / F8=Forward).
   */
  const handleNextPage = () => {
    if (pageNumber < totalPages) {
      setPageNumber(pageNumber + 1);
    }
  };
  
  /**
   * handleViewTransaction
   * 
   * Navigates to transaction detail view.
   * Matches COBOL transaction selection logic (SEL field = 'S').
   * 
   * @param {string} transactionId - The ID of the transaction to view
   */
  const handleViewTransaction = (transactionId) => {
    if (transactionId && transactionId.trim() !== '') {
      // Navigate to transaction detail view (COTRN01C equivalent)
      navigate(`/transactions/${transactionId}`);
    }
  };
  
  /**
   * handleBackToMenu
   * 
   * Returns to main menu (matching COBOL F3=Back functionality).
   */
  const handleBackToMenu = () => {
    navigate('/menu');
  };
  
  // ============================================================================
  // Utility Functions
  // ============================================================================
  
  /**
   * formatAmount
   * 
   * Formats transaction amount as currency with BigDecimal precision.
   * Matches COBOL PIC S9(09)V99 format with proper sign and decimal handling.
   * 
   * @param {number|string} amount - The transaction amount
   * @returns {string} Formatted amount string (e.g., "$1,234.56" or "($1,234.56)")
   */
  const formatAmount = (amount) => {
    if (amount === null || amount === undefined) {
      return '$0.00';
    }
    
    const numAmount = typeof amount === 'string' ? parseFloat(amount) : amount;
    
    if (isNaN(numAmount)) {
      return '$0.00';
    }
    
    // Handle negative amounts (show in parentheses matching financial convention)
    const isNegative = numAmount < 0;
    const absAmount = Math.abs(numAmount);
    
    // Format with 2 decimal places (BigDecimal precision)
    const formatted = absAmount.toLocaleString('en-US', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    });
    
    if (isNegative) {
      return `($${formatted})`;
    } else {
      return `$${formatted}`;
    }
  };
  
  /**
   * formatDate
   * 
   * Formats transaction date/timestamp to display format.
   * Matches BMS TDATE field format (8 characters).
   * 
   * @param {string} timestamp - ISO timestamp or date string
   * @returns {string} Formatted date (MM/DD/YY format)
   */
  const formatDate = (timestamp) => {
    if (!timestamp) {
      return '';
    }
    
    try {
      const date = new Date(timestamp);
      if (isNaN(date.getTime())) {
        return timestamp.substring(0, 8); // Return first 8 chars if invalid
      }
      
      const month = String(date.getMonth() + 1).padStart(2, '0');
      const day = String(date.getDate()).padStart(2, '0');
      const year = String(date.getFullYear()).substring(2); // Last 2 digits
      
      return `${month}/${day}/${year}`;
    } catch (error) {
      return timestamp.substring(0, 8);
    }
  };
  
  /**
   * truncateDescription
   * 
   * Truncates transaction description to fit display width.
   * Matches BMS TDESC field length (26 characters).
   * 
   * @param {string} description - Full description text
   * @returns {string} Truncated description with ellipsis if needed
   */
  const truncateDescription = (description) => {
    if (!description) {
      return '';
    }
    
    const MAX_LENGTH = 26;
    if (description.length <= MAX_LENGTH) {
      return description;
    }
    
    return description.substring(0, MAX_LENGTH - 3) + '...';
  };
  
  // ============================================================================
  // Render Component
  // ============================================================================
  
  return (
    <Box sx={{ p: 3 }}>
      {/* Header Section - Matches BMS screen header (TITLE01, TITLE02) */}
      <Paper elevation={3} sx={{ p: 2, mb: 3, backgroundColor: '#1976d2', color: 'white' }}>
        <Grid container alignItems="center" justifyContent="space-between">
          <Grid item>
            <Typography variant="h5" component="h1">
              List Transactions
            </Typography>
            <Typography variant="body2">
              Transaction: CT00 | Program: COTRN00C
            </Typography>
          </Grid>
          <Grid item>
            <Typography variant="body2">
              Page: {pageNumber} of {totalPages}
            </Typography>
            <Typography variant="body2">
              Total Records: {totalRecords}
            </Typography>
          </Grid>
        </Grid>
      </Paper>
      
      {/* Search Section - Matches BMS TRNIDIN field */}
      <Paper elevation={2} sx={{ p: 2, mb: 2 }}>
        <Grid container spacing={2} alignItems="center">
          <Grid item xs={12} sm={6} md={4}>
            <TextField
              fullWidth
              label="Search Transaction ID"
              value={searchInput}
              onChange={handleSearchInputChange}
              placeholder="Enter numeric transaction ID"
              error={!!validationError}
              helperText={validationError}
              InputProps={{
                endAdornment: (
                  <IconButton
                    onClick={handleSearchSubmit}
                    disabled={loading}
                    color="primary"
                  >
                    <SearchIcon />
                  </IconButton>
                )
              }}
              onKeyPress={(event) => {
                if (event.key === 'Enter') {
                  handleSearchSubmit();
                }
              }}
            />
          </Grid>
          <Grid item xs={12} sm={3} md={2}>
            <Button
              fullWidth
              variant="contained"
              onClick={handleSearchSubmit}
              disabled={loading}
              startIcon={<SearchIcon />}
            >
              Search
            </Button>
          </Grid>
          <Grid item xs={12} sm={3} md={2}>
            <Button
              fullWidth
              variant="outlined"
              onClick={handleClearSearch}
              disabled={loading || (searchInput === '' && searchTransactionId === '')}
            >
              Clear
            </Button>
          </Grid>
        </Grid>
      </Paper>
      
      {/* Error Message Display - Matches BMS ERRMSG field (COLOR=RED) */}
      {errorMessage && (
        <Alert severity="error" sx={{ mb: 2 }} onClose={() => setErrorMessage('')}>
          {errorMessage}
        </Alert>
      )}
      
      {/* Loading Indicator */}
      {loading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', my: 4 }}>
          <CircularProgress />
        </Box>
      )}
      
      {/* Transaction List Table - Matches BMS table rows (10 transactions) */}
      {!loading && (
        <TableContainer component={Paper} elevation={2}>
          <Table>
            {/* Table Header - Matches BMS column headers */}
            <TableHead>
              <TableRow sx={{ backgroundColor: '#f5f5f5' }}>
                <TableCell sx={{ fontWeight: 'bold' }}>Sel</TableCell>
                <TableCell sx={{ fontWeight: 'bold' }}>Transaction ID</TableCell>
                <TableCell sx={{ fontWeight: 'bold' }}>Date</TableCell>
                <TableCell sx={{ fontWeight: 'bold' }}>Description</TableCell>
                <TableCell sx={{ fontWeight: 'bold', textAlign: 'right' }}>Amount</TableCell>
              </TableRow>
            </TableHead>
            
            {/* Table Body - Displays up to 10 transactions matching BMS rows */}
            <TableBody>
              {transactions.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5} align="center" sx={{ py: 4 }}>
                    <Typography variant="body1" color="text.secondary">
                      {searchTransactionId 
                        ? 'No transactions found matching your search criteria.'
                        : 'No transactions available.'}
                    </Typography>
                  </TableCell>
                </TableRow>
              ) : (
                transactions.map((transaction, index) => (
                  <TableRow
                    key={transaction.transactionId || index}
                    hover
                    sx={{
                      '&:hover': {
                        backgroundColor: '#f0f7ff',
                        cursor: 'pointer'
                      }
                    }}
                  >
                    {/* Selection Column - Matches BMS SEL field */}
                    <TableCell>
                      <Tooltip title="View transaction details">
                        <IconButton
                          size="small"
                          color="primary"
                          onClick={() => handleViewTransaction(transaction.transactionId)}
                          aria-label="View transaction details"
                        >
                          <VisibilityIcon fontSize="small" />
                        </IconButton>
                      </Tooltip>
                    </TableCell>
                    
                    {/* Transaction ID - Matches BMS TRNID field (16 chars) */}
                    <TableCell
                      onClick={() => handleViewTransaction(transaction.transactionId)}
                      sx={{ color: '#1976d2' }}
                    >
                      {transaction.transactionId || ''}
                    </TableCell>
                    
                    {/* Transaction Date - Matches BMS TDATE field (8 chars) */}
                    <TableCell
                      onClick={() => handleViewTransaction(transaction.transactionId)}
                    >
                      {formatDate(transaction.transactionTimestamp)}
                    </TableCell>
                    
                    {/* Description - Matches BMS TDESC field (26 chars) */}
                    <TableCell
                      onClick={() => handleViewTransaction(transaction.transactionId)}
                    >
                      {truncateDescription(transaction.transactionDescription || 
                        transaction.merchantName || 'N/A')}
                    </TableCell>
                    
                    {/* Amount - Matches BMS TAMT field (12 chars, BigDecimal) */}
                    <TableCell
                      onClick={() => handleViewTransaction(transaction.transactionId)}
                      sx={{
                        textAlign: 'right',
                        fontWeight: 'bold',
                        color: transaction.transactionAmount < 0 ? '#d32f2f' : '#2e7d32'
                      }}
                    >
                      {formatAmount(transaction.transactionAmount)}
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      
      <Divider sx={{ my: 2 }} />
      
      {/* Instructions - Matches BMS instruction text */}
      <Box sx={{ mb: 2 }}>
        <Typography variant="body2" color="text.secondary" align="center">
          Click the eye icon or transaction row to view transaction details
        </Typography>
      </Box>
      
      {/* Navigation Controls - Matches BMS PF keys (F3, F7, F8) */}
      <Paper elevation={1} sx={{ p: 2 }}>
        <Grid container spacing={2} justifyContent="space-between" alignItems="center">
          {/* Back Button - Matches F3=Back */}
          <Grid item>
            <Button
              variant="outlined"
              startIcon={<ArrowBackIcon />}
              onClick={handleBackToMenu}
            >
              Back to Menu
            </Button>
          </Grid>
          
          {/* Pagination Controls - Match F7=Backward, F8=Forward */}
          <Grid item>
            <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
              <Button
                variant="contained"
                startIcon={<NavigateBeforeIcon />}
                onClick={handlePreviousPage}
                disabled={pageNumber <= 1 || loading}
              >
                Previous
              </Button>
              
              <Typography variant="body2" sx={{ mx: 2 }}>
                Page {pageNumber} of {totalPages}
              </Typography>
              
              <Button
                variant="contained"
                endIcon={<NavigateNextIcon />}
                onClick={handleNextPage}
                disabled={pageNumber >= totalPages || loading}
              >
                Next
              </Button>
            </Box>
          </Grid>
        </Grid>
      </Paper>
      
      {/* Footer Help Text - Matches BMS footer keys */}
      <Box sx={{ mt: 2 }}>
        <Typography variant="caption" color="text.secondary" align="center" display="block">
          ENTER=Continue | F3=Back | F7=Backward | F8=Forward
        </Typography>
      </Box>
    </Box>
  );
};

export default TransactionListComponent;
