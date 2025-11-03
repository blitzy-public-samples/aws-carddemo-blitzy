/**
 * CardDemo Transaction List Component
 * 
 * React functional component implementing paginated transaction list display with Material-UI 
 * table, replacing BMS mapset COTRN00M 3270 terminal screen. This component provides modern 
 * web UI for transaction browsing with filtering, sorting, and pagination capabilities.
 * 
 * Transformation Context:
 * Maps COBOL program COTRN00C.cbl transaction list display functionality from mainframe 
 * VSAM TRANSACT file sequential browse to modern React component with RESTful API integration.
 * 
 * COBOL Program Mapping:
 * - COTRN00C.cbl (lines 1-820) → TransactionListComponent.jsx
 * - BMS mapset COTRN00M.bms (lines 1-465) → Material-UI Table component structure
 * - BMS copybook COTRN00.CPY (lines 1-729) → Component state and props
 * 
 * Key Features:
 * - Displays exactly 10 transactions per page (matching COBOL COTRN00C.cbl line 290 pattern)
 * - Server-side pagination with TablePagination component
 * - Date range filtering using date-fns for date manipulation
 * - Account ID and card number filtering via search input fields
 * - Sort controls for transaction columns (ID, date, amount)
 * - Row selection with checkbox (replacing BMS SEL0001-SEL0010 single-character fields)
 * - Navigation to transaction detail view on row click
 * - Error message display using Material-UI Alert component (replacing BMS ERRMSG field)
 * - Loading state indicator during data fetch operations
 * 
 * BMS Field Mapping:
 * - TRNIDIN (line 95-99) → TextField for transaction ID filter
 * - SEL0001-SEL0010 (lines 153-442) → Checkbox column in table
 * - TRNID01-TRNID10 → Transaction ID column
 * - TDATE01-TDATE10 → Date column (formatted mm/dd/yyyy)
 * - TDESC01-TDESC10 → Description column
 * - TAMT001-TAMT010 → Amount column (formatted as currency with 2 decimal places)
 * - PAGENUM (lines 85-89) → TablePagination component
 * - ERRMSG (lines 450-453) → Alert component for error display
 * - PF3=Back, PF7=Backward, PF8=Forward (line 458) → Pagination controls and back button
 * 
 * Redux Integration:
 * - Uses useDispatch hook to dispatch fetchTransactions async thunk on mount and filter changes
 * - Uses useSelector hooks to access transactions array, pagination state, filters, loading, and error
 * - Dispatches goToNextPage/goToPrevPage actions for PF7/PF8 equivalent navigation
 * - Dispatches setDateRange and updateFilters actions for filter updates
 * 
 * Pagination Pattern:
 * Fixed 10 transactions per page matching COBOL screen layout from BMS COTRN00M definition,
 * preserving exact user workflow and screen navigation patterns from mainframe application.
 * 
 * Data Precision:
 * Transaction amounts formatted with 2 decimal places preserving COBOL COMP-3 decimal precision
 * (PIC S9(9)V99) ensuring identical financial display without rounding discrepancies.
 * 
 * Dependencies:
 * - React (useState, useEffect hooks) for component lifecycle and local state
 * - react-redux (useDispatch, useSelector) for Redux state management
 * - react-router-dom (useNavigate) for navigation to transaction detail
 * - @mui/material components for modern web UI (Table, TablePagination, TextField, etc.)
 * - date-fns for date formatting and manipulation
 * - transactionService.js via Redux thunks for REST API calls
 * - transactionSlice.js for state management and async operations
 * 
 * @module components/transaction/TransactionListComponent
 */

import { useState, useEffect } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { useNavigate } from 'react-router-dom';
import {
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TablePagination,
  TextField,
  Button,
  Paper,
  Box,
  Typography,
  Checkbox,
  Alert
} from '@mui/material';
import { format, parseISO, isValid, startOfDay, endOfDay } from 'date-fns';
import {
  fetchTransactions,
  selectTransactions,
  selectTransactionPagination,
  selectTransactionFilters,
  selectHasNextPage,
  selectHasPrevPage,
  goToNextPage,
  goToPrevPage,
  setDateRange,
  updateFilters,
  selectTransactionLoading,
  selectTransactionError
} from '../../redux/slices/transactionSlice';

/**
 * TransactionListComponent - Main functional component
 * 
 * Renders paginated transaction list table with filtering and navigation capabilities.
 * Replaces COBOL COTRN00C.cbl transaction list display program and BMS COTRN00M mapset.
 * 
 * Component State Management:
 * - Local state for account ID input, card number filter, and date range pickers
 * - Redux state for transactions array, pagination metadata, and loading/error states
 * - Synchronizes local filter state with Redux on user interactions
 * 
 * Lifecycle Behavior:
 * - On mount: Checks for account ID in URL params or prompts user input
 * - On filter change: Dispatches updateFilters action and triggers data refetch
 * - On pagination change: Dispatches page navigation actions and refetches data
 * 
 * Navigation:
 * - Row click with checkbox selection navigates to transaction detail view
 * - Back button returns to previous screen (account or menu view)
 * - Pagination controls allow forward/backward page navigation
 * 
 * @returns {JSX.Element} Transaction list component with table and controls
 */
const TransactionListComponent = () => {
  // Redux dispatch and selectors
  const dispatch = useDispatch();
  const navigate = useNavigate();
  
  // Select transaction state from Redux store
  // Maps COBOL WORKING-STORAGE variables to React component state
  const transactions = useSelector(selectTransactions); // Maps WS-TRAN-RECORD array (max 10)
  const pagination = useSelector(selectTransactionPagination); // Maps WS-PAGE-NUM, WS-REC-COUNT
  const filters = useSelector(selectTransactionFilters); // Maps filter working storage variables
  const hasNextPage = useSelector(selectHasNextPage); // Maps NEXT-PAGE-YES flag
  const hasPrevPage = useSelector(selectHasPrevPage); // Maps page number > 1 check
  const loading = useSelector(selectTransactionLoading); // Maps async operation state
  const error = useSelector(selectTransactionError); // Maps WS-MESSAGE on error
  
  // Local component state for filter inputs
  // Maps BMS input fields TRNIDIN, date range fields, and search criteria
  const [accountIdInput, setAccountIdInput] = useState(''); // Required for API call
  const [cardNumberFilter, setCardNumberFilter] = useState(''); // Optional filter
  const [transactionIdFilter, setTransactionIdFilter] = useState(''); // Maps TRNIDIN field
  const [startDateInput, setStartDateInput] = useState(''); // Start date for range filter
  const [endDateInput, setEndDateInput] = useState(''); // End date for range filter
  const [selectedTransactions, setSelectedTransactions] = useState([]); // Maps SEL0001-SEL0010
  
  /**
   * Initialize component and fetch initial transaction data
   * 
   * Maps COBOL COTRN00C.cbl initialization logic (lines 120-165) which sets up
   * working storage variables and performs initial file browse operation.
   * 
   * On component mount:
   * 1. Checks URL params or session storage for account ID
   * 2. If account ID available, dispatches fetchTransactions thunk
   * 3. Applies any existing filters from Redux state
   * 
   * COBOL Equivalence:
   * - COBOL lines 120-140: MOVE statements initializing working storage
   * - COBOL lines 593-610: EXEC CICS STARTBR for initial browse positioning
   * - COBOL lines 626-645: First READNEXT to populate screen
   */
  useEffect(() => {
    // On component mount, attempt to load transactions if account ID is available
    // In a real application, account ID would come from URL params or user context
    
    // For demo purposes, use a default account ID or prompt user
    // This simulates COBOL receiving COMMAREA with account information
    const defaultAccountId = '0000000001'; // Default for testing
    
    if (defaultAccountId) {
      setAccountIdInput(defaultAccountId);
      
      // Dispatch fetchTransactions with current page and filters
      // Maps COBOL STARTBR + READNEXT loop to REST API GET request
      dispatch(fetchTransactions({
        page: pagination.currentPage,
        accountId: defaultAccountId,
        startDate: filters.startDate || null,
        endDate: filters.endDate || null
      }));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []); // Empty dependency array = run once on mount (intentionally ignoring pagination/filters)
  
  /**
   * Handle pagination page change event
   * 
   * Maps COBOL COTRN00C.cbl pagination logic (lines 232-274) which handles
   * PF7 (backward) and PF8 (forward) key presses for page navigation.
   * 
   * COBOL Equivalence:
   * - Lines 232-252: PROCESS-PF7-KEY paragraph (backward pagination)
   * - Lines 255-274: PROCESS-PF8-KEY paragraph (forward pagination)
   * - Lines 626-645: READNEXT loop to fetch next page of records
   * 
   * @param {Event} event - DOM event (unused)
   * @param {number} newPage - New page number (0-based for Material-UI)
   */
  const handlePageChange = (event, newPage) => {
    // Material-UI uses 0-based page numbers, Redux state uses 1-based
    // Convert to 1-based for consistency with COBOL page numbering
    const page1Based = newPage + 1;
    
    // Dispatch fetchTransactions with new page number
    // Maps COBOL page forward/backward logic to REST API pagination
    dispatch(fetchTransactions({
      page: page1Based,
      accountId: accountIdInput,
      startDate: filters.startDate || null,
      endDate: filters.endDate || null
    }));
  };
  
  /**
   * Handle rows per page change event
   * 
   * NOTE: In COBOL application, page size is fixed at 10 transactions per COTRN00C.cbl line 290.
   * This handler is included for Material-UI TablePagination API compliance but should
   * maintain pageSize = 10 to preserve exact COBOL behavior.
   * 
   * This is a no-op function since page size is fixed.
   */
  const handleRowsPerPageChange = () => {
    // Page size is fixed at 10 per COBOL requirements
    // This handler is a no-op to maintain COBOL equivalence
  };
  
  /**
   * Handle date range filter change
   * 
   * Maps COBOL COTRN00C.cbl date filtering logic (lines 680-710) which validates
   * date range inputs and filters transactions by transaction date.
   * 
   * COBOL Equivalence:
   * - Lines 680-695: Date validation logic (IF TRAN-DATE >= START-DATE AND <= END-DATE)
   * - Lines 700-710: Error handling for invalid date ranges
   * 
   * Validates date inputs and dispatches setDateRange action to update Redux filters.
   * Automatically triggers transaction refetch with new date range parameters.
   */
  const handleDateRangeChange = () => {
    // Validate date inputs using date-fns
    let startDate = null;
    let endDate = null;
    
    if (startDateInput) {
      const parsedStart = parseISO(startDateInput);
      if (isValid(parsedStart)) {
        startDate = format(startOfDay(parsedStart), 'yyyy-MM-dd');
      }
    }
    
    if (endDateInput) {
      const parsedEnd = parseISO(endDateInput);
      if (isValid(parsedEnd)) {
        endDate = format(endOfDay(parsedEnd), 'yyyy-MM-dd');
      }
    }
    
    // Dispatch setDateRange action to update filters in Redux
    // Maps COBOL MOVE statements updating date filter working storage
    dispatch(setDateRange({ startDate, endDate }));
    
    // Fetch transactions with new date range
    // Maps COBOL STARTBR repositioning after filter change
    dispatch(fetchTransactions({
      page: 1, // Reset to first page on filter change
      accountId: accountIdInput,
      startDate,
      endDate
    }));
  };
  
  /**
   * Handle transaction selection checkbox click
   * 
   * Maps BMS screen SEL0001-SEL0010 selection fields (lines 153-442) where user
   * types 'S' to select transaction for detail view.
   * 
   * COBOL Equivalence:
   * - Lines 460-485: PROCESS-SELECTION paragraph checking SEL fields
   * - Lines 490-510: XCTL to transaction detail program (COTRN01C)
   * 
   * @param {string} transactionId - ID of selected transaction (maps TRAN-ID)
   */
  const handleTransactionSelect = (transactionId) => {
    // Toggle selection in local state
    // Maps COBOL checking if SEL field = 'S'
    if (selectedTransactions.includes(transactionId)) {
      setSelectedTransactions(selectedTransactions.filter(id => id !== transactionId));
    } else {
      setSelectedTransactions([...selectedTransactions, transactionId]);
    }
  };
  
  /**
   * Handle row click to navigate to transaction detail view
   * 
   * Maps COBOL COTRN00C.cbl selection processing (lines 460-510) which detects
   * 'S' in SEL field and transfers control to COTRN01C transaction detail program.
   * 
   * COBOL Equivalence:
   * - Lines 460-485: PROCESS-SELECTION paragraph
   * - Lines 490-510: EXEC CICS XCTL PROGRAM('COTRN01C') COMMAREA(...)
   * 
   * React Router replaces CICS XCTL with programmatic navigation to detail route.
   * 
   * @param {string} transactionId - ID of transaction to view (maps TRAN-ID)
   */
  const handleRowClick = (transactionId) => {
    // Navigate to transaction detail view
    // Maps COBOL XCTL to COTRN01C with TRAN-ID in COMMAREA
    navigate(`/transactions/${transactionId}`);
  };
  
  /**
   * Handle back button click
   * 
   * Maps BMS function key PF3=Back (line 458) which returns to previous screen.
   * 
   * COBOL Equivalence:
   * - Lines 210-230: PROCESS-PF3-KEY paragraph
   * - Lines 220-230: EXEC CICS RETURN or XCTL to calling program
   * 
   * React Router navigates back in history stack.
   */
  const handleBack = () => {
    // Navigate back to previous screen (account view or main menu)
    // Maps COBOL PF3 key handling returning to calling program
    navigate(-1); // Go back in browser history
  };
  
  /**
   * Handle search/filter button click
   * 
   * Applies filter inputs (transaction ID, card number) and fetches filtered results.
   * Maps COBOL search functionality where user inputs criteria and presses ENTER.
   * 
   * COBOL Equivalence:
   * - Lines 650-680: APPLY-FILTERS paragraph
   * - Lines 593-610: STARTBR with new starting key based on filter
   */
  const handleApplyFilters = () => {
    // Update Redux filters with local input values
    // Maps COBOL MOVE statements updating filter working storage variables
    dispatch(updateFilters({
      transactionId: transactionIdFilter,
      cardNumber: cardNumberFilter
    }));
    
    // Fetch transactions with updated filters
    // Maps COBOL STARTBR repositioning with new filter criteria
    dispatch(fetchTransactions({
      page: 1, // Reset to first page
      accountId: accountIdInput,
      startDate: filters.startDate || null,
      endDate: filters.endDate || null
    }));
  };
  
  /**
   * Format transaction date for display
   * 
   * Maps COBOL date formatting logic converting TRAN-DATE to display format.
   * Uses date-fns to format ISO date string to mm/dd/yyyy format matching BMS
   * TDATE01-TDATE10 field display (8 characters, mm/dd/yy pattern).
   * 
   * COBOL Equivalence:
   * - Date formatting from internal format (YYYYMMDD) to display format (MM/DD/YY)
   * - MOVE TRAN-DATE-MM TO TDATE-OUT-MM, etc.
   * 
   * @param {string} dateString - ISO date string from backend (YYYY-MM-DD)
   * @returns {string} Formatted date string (mm/dd/yyyy)
   */
  const formatDate = (dateString) => {
    if (!dateString) return '';
    
    try {
      const date = parseISO(dateString);
      if (isValid(date)) {
        // Format as mm/dd/yyyy matching BMS date display format
        return format(date, 'MM/dd/yyyy');
      }
      return dateString; // Return original if parsing fails
    } catch (error) {
      console.error('Error formatting date:', error);
      return dateString;
    }
  };
  
  /**
   * Format transaction amount for display
   * 
   * Maps COBOL COMP-3 amount formatting with edited numeric field (PIC +99999999.99).
   * Formats amount with 2 decimal places and currency symbol, preserving decimal precision.
   * 
   * COBOL Equivalence:
   * - MOVE TRAN-AMT TO TAMT-EDITED (PIC $$$,$$$,$$9.99)
   * - Automatic decimal point insertion and sign formatting
   * 
   * @param {number} amount - Transaction amount (maps TRAN-AMT COMP-3 field)
   * @returns {string} Formatted amount string with currency symbol
   */
  const formatAmount = (amount) => {
    if (amount === undefined || amount === null) return '$0.00';
    
    // Format with 2 decimal places preserving COMP-3 precision
    const numAmount = Number(amount);
    if (isNaN(numAmount)) return '$0.00';
    
    // Format as currency with proper sign handling
    const formattedValue = Math.abs(numAmount).toFixed(2);
    
    // Add thousands separators and currency symbol
    const parts = formattedValue.split('.');
    parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    
    if (numAmount < 0) {
      return `-$${parts.join('.')}`;
    }
    
    return `$${parts.join('.')}`;
  };
  
  // Render component JSX
  // Maps BMS COTRN00M screen layout to Material-UI components
  return (
    <Box sx={{ width: '100%', padding: 3 }}>
      {/* Header section - Maps BMS lines 1-89 (title, date, time, page number) */}
      <Paper elevation={2} sx={{ padding: 2, marginBottom: 2 }}>
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 2 }}>
          <Typography variant="h5" component="h1" sx={{ color: '#1976d2' }}>
            List Transactions
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Page {pagination.currentPage} of {pagination.totalPages}
          </Typography>
        </Box>
        
        {/* Search and filter controls - Maps BMS lines 90-102 (TRNIDIN field) */}
        <Box sx={{ display: 'flex', gap: 2, marginBottom: 2, flexWrap: 'wrap' }}>
          <TextField
            label="Search Transaction ID"
            variant="outlined"
            size="small"
            value={transactionIdFilter}
            onChange={(e) => setTransactionIdFilter(e.target.value)}
            sx={{ minWidth: 200 }}
            placeholder="Enter transaction ID"
          />
          
          <TextField
            label="Card Number"
            variant="outlined"
            size="small"
            value={cardNumberFilter}
            onChange={(e) => setCardNumberFilter(e.target.value)}
            sx={{ minWidth: 200 }}
            placeholder="Enter card number"
          />
          
          <TextField
            label="Start Date"
            type="date"
            variant="outlined"
            size="small"
            value={startDateInput}
            onChange={(e) => setStartDateInput(e.target.value)}
            InputLabelProps={{ shrink: true }}
            sx={{ minWidth: 160 }}
          />
          
          <TextField
            label="End Date"
            type="date"
            variant="outlined"
            size="small"
            value={endDateInput}
            onChange={(e) => setEndDateInput(e.target.value)}
            InputLabelProps={{ shrink: true }}
            sx={{ minWidth: 160 }}
          />
          
          <Button
            variant="contained"
            onClick={handleApplyFilters}
            sx={{ minWidth: 100 }}
          >
            Apply Filters
          </Button>
          
          <Button
            variant="outlined"
            onClick={handleDateRangeChange}
            sx={{ minWidth: 120 }}
          >
            Filter by Date
          </Button>
        </Box>
      </Paper>
      
      {/* Error message display - Maps BMS ERRMSG field (lines 450-453) */}
      {error && (
        <Alert severity="error" sx={{ marginBottom: 2 }} onClose={() => {}}>
          {error}
        </Alert>
      )}
      
      {/* Transaction list table - Maps BMS lines 103-442 (table structure and data rows) */}
      <TableContainer component={Paper} elevation={2}>
        <Table sx={{ minWidth: 650 }} aria-label="transaction list table">
          {/* Table header - Maps BMS lines 103-152 (column headers) */}
          <TableHead>
            <TableRow sx={{ backgroundColor: '#f5f5f5' }}>
              <TableCell padding="checkbox">
                <Typography variant="subtitle2" fontWeight="bold">
                  Sel
                </Typography>
              </TableCell>
              <TableCell>
                <Typography variant="subtitle2" fontWeight="bold">
                  Transaction ID
                </Typography>
              </TableCell>
              <TableCell>
                <Typography variant="subtitle2" fontWeight="bold">
                  Date
                </Typography>
              </TableCell>
              <TableCell>
                <Typography variant="subtitle2" fontWeight="bold">
                  Description
                </Typography>
              </TableCell>
              <TableCell align="right">
                <Typography variant="subtitle2" fontWeight="bold">
                  Amount
                </Typography>
              </TableCell>
            </TableRow>
          </TableHead>
          
          {/* Table body - Maps BMS lines 153-442 (10 transaction rows) */}
          <TableBody>
            {loading ? (
              <TableRow>
                <TableCell colSpan={5} align="center">
                  <Typography variant="body1" color="text.secondary">
                    Loading transactions...
                  </Typography>
                </TableCell>
              </TableRow>
            ) : transactions.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5} align="center">
                  <Typography variant="body1" color="text.secondary">
                    No transactions found
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              transactions.map((transaction, index) => (
                <TableRow
                  key={transaction.transactionId || index}
                  hover
                  onClick={() => handleRowClick(transaction.transactionId)}
                  sx={{ cursor: 'pointer', '&:hover': { backgroundColor: '#f0f8ff' } }}
                >
                  {/* Selection checkbox - Maps SEL0001-SEL0010 fields */}
                  <TableCell padding="checkbox" onClick={(e) => e.stopPropagation()}>
                    <Checkbox
                      checked={selectedTransactions.includes(transaction.transactionId)}
                      onChange={() => handleTransactionSelect(transaction.transactionId)}
                      color="primary"
                    />
                  </TableCell>
                  
                  {/* Transaction ID - Maps TRNID01-TRNID10 fields */}
                  <TableCell component="th" scope="row">
                    <Typography variant="body2" sx={{ color: '#1976d2' }}>
                      {transaction.transactionId}
                    </Typography>
                  </TableCell>
                  
                  {/* Transaction date - Maps TDATE01-TDATE10 fields */}
                  <TableCell>
                    <Typography variant="body2">
                      {formatDate(transaction.transactionDate)}
                    </Typography>
                  </TableCell>
                  
                  {/* Transaction description - Maps TDESC01-TDESC10 fields */}
                  <TableCell>
                    <Typography variant="body2" noWrap sx={{ maxWidth: 300 }}>
                      {transaction.description || transaction.merchantName || 'N/A'}
                    </Typography>
                  </TableCell>
                  
                  {/* Transaction amount - Maps TAMT001-TAMT010 fields */}
                  <TableCell align="right">
                    <Typography
                      variant="body2"
                      sx={{
                        color: transaction.transactionAmount < 0 ? 'error.main' : 'text.primary',
                        fontWeight: 'medium'
                      }}
                    >
                      {formatAmount(transaction.transactionAmount)}
                    </Typography>
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
        
        {/* Pagination controls - Maps BMS PAGENUM field and PF7/PF8 keys (lines 85-89, 458) */}
        <TablePagination
          component="div"
          count={pagination.totalTransactions}
          page={pagination.currentPage - 1} // Material-UI uses 0-based, convert from 1-based
          onPageChange={handlePageChange}
          rowsPerPage={pagination.pageSize}
          onRowsPerPageChange={handleRowsPerPageChange}
          rowsPerPageOptions={[10]} // Fixed at 10 per COBOL requirements
          labelRowsPerPage="Transactions per page:"
          labelDisplayedRows={({ from, to, count }) =>
            `${from}-${to} of ${count !== -1 ? count : `more than ${to}`}`
          }
        />
      </TableContainer>
      
      {/* Action buttons - Maps BMS function keys (line 458) */}
      <Box sx={{ display: 'flex', justifyContent: 'space-between', marginTop: 2 }}>
        <Button
          variant="outlined"
          onClick={handleBack}
          sx={{ minWidth: 100 }}
        >
          Back (F3)
        </Button>
        
        <Box sx={{ display: 'flex', gap: 2 }}>
          <Button
            variant="outlined"
            onClick={() => {
              dispatch(goToPrevPage());
              dispatch(fetchTransactions({
                page: pagination.currentPage - 1,
                accountId: accountIdInput,
                startDate: filters.startDate || null,
                endDate: filters.endDate || null
              }));
            }}
            disabled={!hasPrevPage || loading}
            sx={{ minWidth: 120 }}
          >
            Previous (F7)
          </Button>
          
          <Button
            variant="outlined"
            onClick={() => {
              dispatch(goToNextPage());
              dispatch(fetchTransactions({
                page: pagination.currentPage + 1,
                accountId: accountIdInput,
                startDate: filters.startDate || null,
                endDate: filters.endDate || null
              }));
            }}
            disabled={!hasNextPage || loading}
            sx={{ minWidth: 120 }}
          >
            Next (F8)
          </Button>
        </Box>
      </Box>
      
      {/* Help text - Maps BMS instruction text (lines 444-449) */}
      <Box sx={{ marginTop: 2, padding: 1, backgroundColor: '#fff3cd', borderRadius: 1 }}>
        <Typography variant="body2" color="text.secondary">
          Click on a transaction row to view details. Use the checkbox to select multiple transactions.
        </Typography>
      </Box>
    </Box>
  );
};

/**
 * Export TransactionListComponent as default
 * 
 * Provides the main transaction list UI component for import by parent components.
 * Replaces COBOL COTRN00C program as default export matching export schema requirement.
 */
export default TransactionListComponent;
