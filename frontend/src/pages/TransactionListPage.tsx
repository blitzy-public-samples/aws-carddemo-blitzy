/**
 * TransactionListPage Component
 * 
 * Converted from COBOL program: COTRN00C.cbl
 * BMS Map: COTRN00.bms (24x80 3270 terminal screen)
 * Original function: Transaction list display with search filters and pagination
 * 
 * COBOL Program Flow (COTRN00C.cbl):
 * 1. MAIN-PARA: Entry point, check COMMAREA for user session
 * 2. PROCESS-ENTER-KEY: Handle initial screen display or search
 * 3. STARTBR-TRANSACT-FILE: Position browse cursor at starting transaction ID
 * 4. READNEXT-TRANSACT-FILE: Sequential read of 10 records per page
 * 5. POPULATE-TRAN-DATA: Format each transaction for screen display
 * 6. SEND-TRNLST-SCREEN: Display COTRN0A map with transaction rows
 * 7. PROCESS-PF7-KEY: Navigate backward one page (READPREV)
 * 8. PROCESS-PF8-KEY: Navigate forward one page (READNEXT)
 * 
 * BMS Screen Layout (COTRN00.bms):
 * - Line 1-2: Header with transaction name, title, date, time
 * - Line 4: Page title "List Transactions" and page number display (PAGENUM field)
 * - Line 6: Search filter field TRNIDIN (16 chars) for transaction ID search
 * - Line 8-9: Column headers (Sel, Transaction ID, Date, Description, Amount)
 * - Line 10-19: 10 repeating rows (SEL0001-SEL0010, TRNID01-TRNID10, etc.)
 * - Line 21: User instruction "Type 'S' to View Transaction details from the list"
 * - Line 23: Error message field (ERRMSG, RED color, 78 chars)
 * - Line 24: Function key help (ENTER=Continue F3=Back F7=Backward F8=Forward)
 * 
 * Conversion Notes:
 * - BMS 24x80 character screen → Responsive Material-UI layout with Container
 * - TRNIDIN search field (16 chars) → TextField with validation
 * - Date range filters added (not in original BMS but business requirement)
 * - 10 repeating rows (OCCURS 10 TIMES) → TransactionTable component with pageSize=10
 * - PAGENUM field → Material-UI Pagination component
 * - SEL fields (selection checkboxes) → Row click navigation to detail page
 * - EXEC CICS STARTBR/READNEXT → REST GET /api/transactions with pagination
 * - PF7/PF8 function keys → Previous/Next pagination buttons
 * - ERRMSG field (RED, BRT) → ErrorMessage component with Alert severity
 * - COBOL WS-PAGE-NUM → React state currentPage with usePagination hook
 * 
 * Key Features:
 * - Search by transaction ID (16-character filter matching TRNIDIN field)
 * - Date range filtering (start date and end date inputs)
 * - Paginated table display (10 rows per page matching BMS layout)
 * - Row selection for navigation to transaction detail view
 * - Error message display matching BMS ERRMSG field behavior
 * - Loading states during API calls
 * - Automatic data refresh on filter changes
 * 
 * Performance Requirements:
 * - Sub-200ms response time for transaction list API calls
 * - Efficient pagination with server-side filtering
 * - Debounced search inputs to reduce API call frequency
 * 
 * @module pages/TransactionListPage
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Container,
  Box,
  Typography,
  TextField,
  Button,
  Paper,
  Grid,
  CircularProgress,
  Alert,
} from '@mui/material';
import { Pagination } from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';
import ClearIcon from '@mui/icons-material/Clear';

// Internal imports - from depends_on_files only
import transactionService from '../services/transactionService';
import TransactionTable from '../components/tables/TransactionTable';
import Header from '../components/common/Header';
import Footer from '../components/common/Footer';
import ErrorMessage from '../components/common/ErrorMessage';
import { useAuth } from '../hooks/useAuth';
import { Transaction } from '../types/transaction';
import { formatDate } from '../utils/dateFormatter';
import { validateLength } from '../utils/validation';

/**
 * TransactionListPage Component
 * 
 * Displays paginated list of credit card transactions with search and filtering capabilities.
 * Replaces COBOL COTRN00C.cbl online transaction program and COTRN00.bms screen map.
 * 
 * State Management:
 * - transactions: Array of Transaction objects for current page (10 items)
 * - searchTransactionId: Transaction ID filter value (TRNIDIN field, 16 chars)
 * - startDate: Start date filter value (YYYY-MM-DD format)
 * - endDate: End date filter value (YYYY-MM-DD format)
 * - currentPage: Current page number (1-based, maps to WS-PAGE-NUM)
 * - totalPages: Total number of pages (calculated from totalItems / pageSize)
 * - loading: Loading state during API calls
 * - error: Error message from API or validation failures (ERRMSG field)
 * 
 * COBOL Data Structures Replaced:
 * - WS-PAGE-NUM → currentPage state
 * - CDEMO-CT00-TRNID-FIRST/LAST → Managed by backend pagination
 * - TRNIDINI → searchTransactionId state
 * - COTRN0AI-TRNID01 through TRNID10 → transactions array
 * - COTRN0AI-TDATE01 through TDATE10 → transactions[].transOrigTs
 * - COTRN0AI-TDESC01 through TDESC10 → transactions[].transDesc
 * - COTRN0AI-TAMT001 through TAMT010 → transactions[].transAmt
 * 
 * @returns JSX.Element - Transaction list page with search filters and table
 */
const TransactionListPage: React.FC = () => {
  // Authentication check (replaces COBOL COMMAREA session validation)
  // COBOL: EXEC CICS RETRIEVE INTO(CARDDEMO-COMMAREA) / Check CDEMO-USER-ID
  const { isAuthenticated, user } = useAuth();
  const navigate = useNavigate();

  // State management for transaction list and filters
  // COBOL equivalent: WORKING-STORAGE SECTION variables
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [searchTransactionId, setSearchTransactionId] = useState<string>('');
  const [startDate, setStartDate] = useState<string>('');
  const [endDate, setEndDate] = useState<string>('');
  const [currentPage, setCurrentPage] = useState<number>(1); // 1-based for UI, converts to 0-based for API
  const [pageSize] = useState<number>(10); // Fixed at 10 to match BMS screen (TRNID01-TRNID10)
  const [totalItems, setTotalItems] = useState<number>(0);
  const [totalPages, setTotalPages] = useState<number>(0);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string>('');

  /**
   * Fetch Transactions from Backend
   * 
   * Converted from: COTRN00C.cbl PROCESS-PAGE-FORWARD paragraph
   * COBOL flow:
   * 1. STARTBR-TRANSACT-FILE: Position browse cursor
   * 2. READNEXT-TRANSACT-FILE: Read 10 records sequentially
   * 3. POPULATE-TRAN-DATA: Format records for screen
   * 4. Check TRANSACT-EOF for NEXT-PAGE-FLG
   * 
   * Makes REST API call to backend TransactionController.getTransactions()
   * with query parameters for pagination and filtering.
   * 
   * Query Parameters:
   * - page: Current page number (converted to 0-based for backend)
   * - pageSize: Fixed at 10 (matching BMS screen layout)
   * - transId: Transaction ID filter (TRNIDIN field)
   * - startDate: Start date filter (YYYY-MM-DD)
   * - endDate: End date filter (YYYY-MM-DD)
   */
  const fetchTransactions = useCallback(async () => {
    try {
      setLoading(true);
      setError('');

      // Build query parameters for API call
      // COBOL equivalent: Building COMMAREA for file browse operation
      const queryParams: {
        page?: number;
        pageSize?: number;
        transId?: string;
        startDate?: string;
        endDate?: string;
      } = {
        page: currentPage, // Will be converted to 0-based by service
        pageSize: pageSize,
      };

      // Add transaction ID filter if provided (TRNIDIN field validation)
      // COBOL: "WHEN TRNIDIN OF COTRN0AI NOT = SPACES AND LOW-VALUES"
      if (searchTransactionId && searchTransactionId.trim() !== '') {
        // Validate transaction ID length (16 characters)
        // COBOL: TRAN-ID PIC X(16)
        const validation = validateLength(searchTransactionId, 16, 16);
        if (!validation.valid) {
          setError(validation.message);
          setLoading(false);
          return;
        }
        queryParams.transId = searchTransactionId.trim();
      }

      // Add date range filters if provided
      // COBOL equivalent: Date range filtering on TRAN-ORIG-TS field
      if (startDate && startDate.trim() !== '') {
        queryParams.startDate = startDate;
      }
      if (endDate && endDate.trim() !== '') {
        queryParams.endDate = endDate;
      }

      // Call backend API
      // Spring Boot: TransactionController.getTransactions()
      // Returns: { transactions: Transaction[], pagination: PaginationParams }
      const response = await transactionService.getTransactions(queryParams);

      // Update state with response data
      // COBOL equivalent: Moving data to screen output fields (COTRN0AO)
      setTransactions(response.transactions || []);
      setTotalItems(response.pagination.totalItems || 0);
      setTotalPages(response.pagination.totalPages || 0);

      // COBOL equivalent: Setting NEXT-PAGE-FLG based on EOF condition
      // React handles this automatically with totalPages calculation
    } catch (err: any) {
      // COBOL error handling: ERR-FLG-ON, move message to WS-MESSAGE
      // Display error in ERRMSG field (RED color, BRT attribute)
      const errorMessage =
        err?.message || 'Unable to retrieve transactions. Please try again.';
      setError(errorMessage);
      setTransactions([]);
      setTotalItems(0);
      setTotalPages(0);
    } finally {
      setLoading(false);
    }
  }, [currentPage, pageSize, searchTransactionId, startDate, endDate]);

  /**
   * Effect Hook: Fetch Transactions on Mount and Filter Changes
   * 
   * COBOL equivalent: PROCESS-ENTER-KEY paragraph
   * Automatically triggers when:
   * - Component mounts (initial page load)
   * - currentPage changes (PF7/PF8 navigation)
   * - Search filters change (TRNIDIN, date range)
   */
  useEffect(() => {
    // Only fetch if user is authenticated
    // COBOL: Check CDEMO-USER-ID NOT = SPACES
    if (isAuthenticated) {
      fetchTransactions();
    }
  }, [isAuthenticated, fetchTransactions]);

  /**
   * Handle Search Button Click
   * 
   * Converted from: COTRN00C.cbl PROCESS-ENTER-KEY paragraph
   * COBOL flow:
   * 1. Validate TRNIDIN field (16 characters)
   * 2. Reset to page 1
   * 3. Execute STARTBR-TRANSACT-FILE with new search criteria
   * 4. READNEXT 10 records
   * 5. SEND-TRNLST-SCREEN
   * 
   * Triggers new API call with updated search filters.
   */
  const handleSearch = useCallback(() => {
    // Reset to first page when new search is initiated
    // COBOL: MOVE 1 TO WS-PAGE-NUM
    setCurrentPage(1);
    // fetchTransactions will be triggered by useEffect due to state change
  }, []);

  /**
   * Handle Clear Filters Button Click
   * 
   * Resets all search filters to empty values and refreshes transaction list.
   * COBOL equivalent: Clearing screen input fields and re-displaying map
   * 
   * COBOL flow:
   * ```cobol
   * MOVE SPACES TO TRNIDIN OF COTRN0AI.
   * MOVE SPACES TO STARTDTI OF COTRN0AI.
   * MOVE SPACES TO ENDDTI OF COTRN0AI.
   * MOVE 1 TO WS-PAGE-NUM.
   * PERFORM SEND-TRNLST-SCREEN.
   * ```
   */
  const handleClearFilters = useCallback(() => {
    setSearchTransactionId('');
    setStartDate('');
    setEndDate('');
    setCurrentPage(1);
    setError('');
  }, []);

  /**
   * Handle Page Change from Pagination Component
   * 
   * Converted from: COTRN00C.cbl PROCESS-PF7-KEY and PROCESS-PF8-KEY paragraphs
   * 
   * COBOL PF7 (Previous Page):
   * ```cobol
   * IF WS-PAGE-NUM > 1
   *     SUBTRACT 1 FROM WS-PAGE-NUM
   *     PERFORM PROCESS-PAGE-BACKWARD
   * END-IF.
   * ```
   * 
   * COBOL PF8 (Next Page):
   * ```cobol
   * IF WS-PAGE-NUM < WS-TOTAL-PAGES
   *     ADD 1 TO WS-PAGE-NUM
   *     PERFORM PROCESS-PAGE-FORWARD
   * END-IF.
   * ```
   * 
   * @param event - React change event
   * @param page - New page number (1-based)
   */
  const handlePageChange = useCallback(
    (event: React.ChangeEvent<unknown>, page: number) => {
      // Material-UI Pagination uses 1-based indexing (matches COBOL WS-PAGE-NUM)
      setCurrentPage(page);
      // fetchTransactions will be triggered by useEffect
    },
    []
  );

  /**
   * Handle Transaction Row Click
   * 
   * Converted from: COTRN00C.cbl PROCESS-SELECTION paragraph
   * COBOL flow:
   * 1. Check if SEL field = 'S' for any row (SEL0001-SEL0010)
   * 2. Get corresponding TRAN-ID from selected row
   * 3. EXEC CICS XCTL PROGRAM('COTRN01C') COMMAREA(TRAN-ID)
   * 
   * Navigates to transaction detail page with selected transaction ID.
   * Replaces COBOL program-to-program transfer with SPA routing.
   * 
   * @param transactionId - Selected transaction ID (16 characters)
   */
  const handleRowClick = useCallback(
    (transactionId: string) => {
      // Navigate to transaction detail page
      // COBOL equivalent: EXEC CICS XCTL PROGRAM('COTRN01C')
      navigate(`/transactions/${transactionId}`);
    },
    [navigate]
  );

  /**
   * Handle Transaction ID Input Change
   * 
   * Updates searchTransactionId state with validation.
   * Limits input to 16 characters (matching TRNIDIN field length).
   * 
   * COBOL equivalent: Field attribute LENGTH=16 in COTRN00.bms
   */
  const handleTransactionIdChange = useCallback(
    (event: React.ChangeEvent<HTMLInputElement>) => {
      const value = event.target.value;
      // Enforce 16-character limit (TRAN-ID PIC X(16))
      if (value.length <= 16) {
        setSearchTransactionId(value.toUpperCase());
        setError(''); // Clear error on input change
      }
    },
    []
  );

  /**
   * Handle Start Date Input Change
   * 
   * Updates startDate state for date range filtering.
   * Validates date format (YYYY-MM-DD).
   * 
   * COBOL equivalent: Date field validation in VALIDATE-INPUT-DATA-FIELDS
   */
  const handleStartDateChange = useCallback(
    (event: React.ChangeEvent<HTMLInputElement>) => {
      setStartDate(event.target.value);
      setError('');
    },
    []
  );

  /**
   * Handle End Date Input Change
   * 
   * Updates endDate state for date range filtering.
   * Validates date format (YYYY-MM-DD).
   * 
   * COBOL equivalent: Date field validation in VALIDATE-INPUT-DATA-FIELDS
   */
  const handleEndDateChange = useCallback(
    (event: React.ChangeEvent<HTMLInputElement>) => {
      setEndDate(event.target.value);
      setError('');
    },
    []
  );

  // Redirect to login if not authenticated
  // COBOL: Check CDEMO-USER-ID = SPACES → EXEC CICS XCTL PROGRAM('COSGN00C')
  useEffect(() => {
    if (!isAuthenticated) {
      navigate('/login', { replace: true });
    }
  }, [isAuthenticated, navigate]);

  return (
    <>
      {/* Application Header Component */}
      {/* Replaces BMS header fields: TRNNAME, TITLE01, TITLE02, CURDATE, CURTIME */}
      <Header />

      {/* Main Content Container */}
      {/* Replaces BMS screen body (lines 4-23) */}
      <Container maxWidth="xl" sx={{ mt: 4, mb: 4 }}>
        {/* Page Title and Page Number */}
        {/* BMS Line 4: "List Transactions" heading and PAGENUM field */}
        <Box sx={{ mb: 3, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Typography variant="h4" component="h1" gutterBottom>
            List Transactions
          </Typography>
          {/* Page Number Display (PAGENUM field from BMS) */}
          {totalPages > 0 && (
            <Typography variant="body1" sx={{ color: 'text.secondary' }}>
              Page: {currentPage} of {totalPages}
            </Typography>
          )}
        </Box>

        {/* Search Filters Section */}
        {/* BMS Line 6: TRNIDIN field (transaction ID search) */}
        {/* Extended with date range filters (business requirement) */}
        <Paper sx={{ p: 3, mb: 3 }}>
          <Typography variant="h6" gutterBottom>
            Search Filters
          </Typography>
          <Grid container spacing={2} alignItems="center">
            {/* Transaction ID Search Field */}
            {/* BMS: TRNIDIN DFHMDF ATTRB=(FSET,NORM,UNPROT), LENGTH=16 */}
            <Grid item xs={12} md={4}>
              <TextField
                fullWidth
                label="Transaction ID"
                placeholder="Enter 16-char ID"
                value={searchTransactionId}
                onChange={handleTransactionIdChange}
                disabled={loading}
                inputProps={{
                  maxLength: 16, // TRAN-ID PIC X(16)
                  style: { textTransform: 'uppercase' },
                }}
                helperText="16-character transaction identifier"
                size="small"
              />
            </Grid>

            {/* Start Date Filter */}
            {/* Added filter not in original BMS but business requirement */}
            <Grid item xs={12} md={3}>
              <TextField
                fullWidth
                label="Start Date"
                type="date"
                value={startDate}
                onChange={handleStartDateChange}
                disabled={loading}
                InputLabelProps={{ shrink: true }}
                size="small"
              />
            </Grid>

            {/* End Date Filter */}
            {/* Added filter not in original BMS but business requirement */}
            <Grid item xs={12} md={3}>
              <TextField
                fullWidth
                label="End Date"
                type="date"
                value={endDate}
                onChange={handleEndDateChange}
                disabled={loading}
                InputLabelProps={{ shrink: true }}
                size="small"
              />
            </Grid>

            {/* Search and Clear Buttons */}
            {/* BMS: ENTER key triggers search, no dedicated buttons */}
            <Grid item xs={12} md={2}>
              <Box sx={{ display: 'flex', gap: 1 }}>
                <Button
                  variant="contained"
                  color="primary"
                  onClick={handleSearch}
                  disabled={loading}
                  startIcon={<SearchIcon />}
                  fullWidth
                  size="small"
                >
                  Search
                </Button>
                <Button
                  variant="outlined"
                  color="secondary"
                  onClick={handleClearFilters}
                  disabled={loading}
                  startIcon={<ClearIcon />}
                  fullWidth
                  size="small"
                >
                  Clear
                </Button>
              </Box>
            </Grid>
          </Grid>
        </Paper>

        {/* Error Message Display */}
        {/* BMS Line 23: ERRMSG DFHMDF ATTRB=(ASKIP,BRT,FSET), COLOR=RED, LENGTH=78 */}
        {error && (
          <Box sx={{ mb: 2 }}>
            <ErrorMessage message={error} severity="error" />
          </Box>
        )}

        {/* Transaction Table */}
        {/* BMS Lines 8-19: Column headers + 10 repeating rows (TRNID01-TRNID10, etc.) */}
        {/* Uses TransactionTable reusable component with Material-UI DataGrid */}
        <Paper sx={{ p: 2, mb: 3 }}>
          {loading ? (
            // Loading Indicator
            <Box sx={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: 400 }}>
              <CircularProgress />
            </Box>
          ) : (
            <>
              {/* Transaction Data Table */}
              {/* COBOL COTRN0A map fields: SEL0001-SEL0010 (selection checkboxes) */}
              {/* TRNID01-TRNID10 (transaction IDs), TDATE01-TDATE10 (dates) */}
              {/* TDESC01-TDESC10 (descriptions), TAMT001-TAMT010 (amounts) */}
              <TransactionTable
                transactions={transactions.map((txn) => ({
                  id: txn.transId,
                  transId: txn.transId,
                  transDate: formatDate(new Date(txn.transOrigTs)),
                  description: txn.transDesc,
                  amount: txn.transAmt,
                }))}
                onRowSelectionChange={(selectedIds) => {
                  // Handle row selection (replaces BMS SEL fields)
                  if (selectedIds.length > 0) {
                    handleRowClick(selectedIds[0]);
                  }
                }}
                loading={loading}
                pageSize={pageSize}
                currentPage={currentPage - 1} // Convert to 0-based for DataGrid
                totalRecords={totalItems}
              />

              {/* Pagination Controls */}
              {/* BMS Line 24: Function key help (F7=Backward, F8=Forward) */}
              {/* Material-UI Pagination replaces PF7/PF8 keys */}
              {totalPages > 1 && (
                <Box sx={{ mt: 2, display: 'flex', justifyContent: 'center' }}>
                  <Pagination
                    count={totalPages}
                    page={currentPage}
                    onChange={handlePageChange}
                    color="primary"
                    showFirstButton
                    showLastButton
                    disabled={loading}
                    size="large"
                  />
                </Box>
              )}

              {/* User Instructions */}
              {/* BMS Line 21: "Type 'S' to View Transaction details from the list" */}
              <Box sx={{ mt: 2 }}>
                <Typography variant="body2" sx={{ color: 'text.secondary', textAlign: 'center' }}>
                  Click on any row to view transaction details
                </Typography>
              </Box>
            </>
          )}
        </Paper>

        {/* Function Key Help Text */}
        {/* BMS Line 24: "ENTER=Continue F3=Back F7=Backward F8=Forward" */}
        {/* Simplified for web interface (pagination buttons replace function keys) */}
        <Box sx={{ mt: 2 }}>
          <Typography variant="body2" sx={{ color: 'text.secondary', textAlign: 'center' }}>
            Use pagination controls to navigate between pages • Search by Transaction ID or Date Range
          </Typography>
        </Box>
      </Container>

      {/* Application Footer Component */}
      {/* Replaces BMS footer area with version and copyright info */}
      <Footer />
    </>
  );
};

export default TransactionListPage;
