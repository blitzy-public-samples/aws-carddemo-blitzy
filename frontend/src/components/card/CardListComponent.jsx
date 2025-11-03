/**
 * CardDemo - Card List Component
 * 
 * React functional component implementing card list display with pagination, filtering, 
 * and selection functionality. This component transforms the COBOL COCRDLIC program and 
 * BMS COCRDLI mapset into a modern React UI with Material-UI components.
 * 
 * COBOL Source Mapping:
 * - COBOL Program: COCRDLIC.cbl (Card List Display)
 * - BMS Mapset: COCRDLI.bms (3270 Terminal Screen Layout)
 * - BMS Copybook: COCRDLI.CPY (Screen Field Definitions)
 * - Transaction ID: CCLI (Card List Transaction)
 * 
 * Business Logic Preservation:
 * - Displays exactly 7 cards per page (WS-MAX-SCREEN-LINES = 7 from COCRDLIC line 177)
 * - Account number filter: 11 digits (ACCTSID field)
 * - Card number filter: 16 digits (CARDSID field)
 * - Selection indicators: 'S' for view details, 'U' for update (lines 77-79 COCRDLIC.cbl)
 * - Role-based filtering: Admin users see all cards, regular users see only their cards
 * - Pagination: F7=Backward (PF7), F8=Forward (PF8), F3=Exit to menu (PF3)
 * - Error handling: Validation messages for invalid filter inputs
 * - Info message: 'TYPE S FOR DETAIL, U TO UPDATE ANY RECORD' (line 116 COCRDLIC.cbl)
 * 
 * CICS Transaction Flow:
 * - ENTER key → Search cards with filters
 * - PF3 → Return to main menu (COMEN01C program)
 * - PF7 → Previous page (9100-READ-BACKWARDS section)
 * - PF8 → Next page (9000-READ-FORWARD section)
 * - 'S' selection → Transfer to COCRDSLC (card detail view)
 * - 'U' selection → Transfer to COCRDUPC (card update)
 * 
 * React Component Features:
 * - Material-UI Table component replaces BMS 7-row screen layout
 * - TextField components for ACCTSID and CARDSID search filters
 * - Button components for PF key functionality (F3/F7/F8)
 * - IconButton components for view (Visibility) and edit (Edit) actions
 * - Alert components for INFOMSG and ERRMSG display
 * - React Router navigation replaces CICS XCTL commands
 * - useState hooks for component state management
 * - useEffect hook for data fetching lifecycle
 * 
 * @component
 * @example
 * // Usage in React Router
 * <Route path="/cards" element={<CardListComponent />} />
 * 
 * @example
 * // Navigation from menu
 * navigate('/cards');
 * 
 * @example
 * // Navigation with account filter
 * navigate('/cards', { state: { accountId: '00000000001' } });
 */

import React, { useState, useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import {
  Box,
  Paper,
  TextField,
  Button,
  Typography,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Alert,
  CircularProgress,
  IconButton,
  Tooltip
} from '@mui/material';
import {
  Search,
  Visibility,
  Edit,
  ArrowBack,
  NavigateBefore,
  NavigateNext
} from '@mui/icons-material';
import cardService from '../../services/cardService';

/**
 * CardListComponent - Main functional component
 * 
 * Implements card list display with search, pagination, and selection capabilities.
 * Preserves exact business logic and user experience from COBOL COCRDLIC program.
 * 
 * @returns {JSX.Element} Rendered card list component
 */
const CardListComponent = () => {
  // Navigation hook for React Router
  const navigate = useNavigate();
  const location = useLocation();

  // Component State - Maps COBOL WORKING-STORAGE variables
  // WS-SCREEN-DATA (lines 252-261 COCRDLIC.cbl) → cards state
  const [cards, setCards] = useState([]);
  
  // Loading state for async operations (CICS READ operations)
  const [loading, setLoading] = useState(false);
  
  // WS-ERROR-MSG (line 117 COCRDLIC.cbl) → error state
  const [error, setError] = useState('');
  
  // WS-INFO-MSG (lines 112-116 COCRDLIC.cbl) → infoMessage state
  const [infoMessage, setInfoMessage] = useState('TYPE S FOR DETAIL, U TO UPDATE ANY RECORD');
  
  // CC-ACCT-ID (ACCTSID BMS field) → accountFilter state
  const [accountFilter, setAccountFilter] = useState('');
  
  // CC-CARD-NUM (CARDSID BMS field) → cardFilter state
  const [cardFilter, setCardFilter] = useState('');
  
  // WS-CA-SCREEN-NUM (line 237 COCRDLIC.cbl) → currentPage state (0-based for Material-UI)
  const [currentPage, setCurrentPage] = useState(0);
  
  // Total count for pagination calculation
  const [totalCount, setTotalCount] = useState(0);
  
  // WS-MAX-SCREEN-LINES = 7 (line 177 COCRDLIC.cbl) → rowsPerPage constant
  const [rowsPerPage] = useState(7); // Fixed at 7 to match COBOL COCRDLIC

  /**
   * Data Fetching Effect
   * 
   * Maps COBOL paragraphs:
   * - 9000-READ-FORWARD (lines 1123-1261 COCRDLIC.cbl)
   * - 9500-FILTER-RECORDS (lines 1382-1410 COCRDLIC.cbl)
   * 
   * Executes on component mount and when pagination or filters change.
   * Implements CICS STARTBR, READNEXT sequential access pattern using REST API.
   */
  useEffect(() => {
    // Check if navigation state contains account ID filter
    if (location.state && location.state.accountId) {
      setAccountFilter(location.state.accountId);
    }
  }, [location]);

  useEffect(() => {
    fetchCards();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentPage, accountFilter, cardFilter]);

  /**
   * Fetch Cards Function
   * 
   * Maps COBOL paragraph 9000-READ-FORWARD (lines 1123-1261 COCRDLIC.cbl).
   * Implements sequential card retrieval with pagination and filtering.
   * 
   * COBOL Equivalence:
   * - EXEC CICS STARTBR DATASET('CARDDAT') → cardService.getCards() API call
   * - EXEC CICS READNEXT loop → Paginated response handling
   * - WS-SCRN-COUNTER increment → Page and row tracking
   * - Filter logic (lines 1385-1405) → Backend accountFilter parameter
   * - CA-NEXT-PAGE-EXISTS flag → totalCount for pagination
   * 
   * Error Handling:
   * - DFHRESP(ENDFILE) → 'NO RECORDS FOUND FOR THIS SEARCH CONDITION.'
   * - DFHRESP(NOTFND) → Card not found message
   * - Other errors → Generic error message display
   */
  const fetchCards = async () => {
    setLoading(true);
    setError('');
    
    try {
      // Call card service with pagination and filter parameters
      // Maps COBOL EXEC CICS STARTBR/READNEXT to REST API GET /api/cards
      const response = await cardService.getCards(
        accountFilter || null,      // ACCTSID filter (11 digits)
        currentPage + 1,             // Convert 0-based to 1-based for API
        rowsPerPage                  // WS-MAX-SCREEN-LINES = 7
      );
      
      // Update state with response data
      // Maps COBOL WS-SCREEN-ROWS array (lines 255-261)
      setCards(response.cards || []);
      setTotalCount(response.totalCount || 0);
      
      // Check if no records found - maps COBOL WS-NO-RECORDS-FOUND (line 121-122)
      if (response.cards.length === 0) {
        setError('NO RECORDS FOUND FOR THIS SEARCH CONDITION.');
        setInfoMessage(''); // Clear info message when showing error
      } else {
        // Show info message when records are found
        // Maps WS-INFORM-REC-ACTIONS (lines 115-116 COCRDLIC.cbl)
        setInfoMessage('TYPE S FOR DETAIL, U TO UPDATE ANY RECORD');
      }
    } catch (err) {
      // Error handling - maps COBOL file error handling (lines 1246-1254)
      setError(err.message || 'Error loading cards');
      setCards([]);
      setInfoMessage(''); // Clear info message on error
    } finally {
      setLoading(false);
    }
  };

  /**
   * Search Handler
   * 
   * Maps COBOL EVALUATE statement WHEN CCARD-AID-ENTER (line 371 COCRDLIC.cbl).
   * Triggered when user clicks Search button (ENTER key equivalent).
   * 
   * Business Logic:
   * - Resets to first page (SET CA-FIRST-PAGE TO TRUE, line 324)
   * - Triggers new card fetch with updated filters
   * - Validates filter inputs (handled in backend and cardService)
   */
  const handleSearch = () => {
    // Reset to first page on new search
    // Maps COBOL: SET CA-FIRST-PAGE TO TRUE (line 324 COCRDLIC.cbl)
    setCurrentPage(0);
    
    // fetchCards will be triggered by useEffect dependency on currentPage
    // This implements COBOL PERFORM 9000-READ-FORWARD (line 433 COCRDLIC.cbl)
  };

  /**
   * Clear Filters Handler
   * 
   * Resets all search filters and returns to first page.
   * Maps COBOL initialization logic (lines 315-325 COCRDLIC.cbl).
   */
  const handleClearFilters = () => {
    // Clear filter fields - maps COBOL INITIALIZE statements
    setAccountFilter('');
    setCardFilter('');
    setCurrentPage(0);
    setError('');
    
    // Info message will be set by fetchCards when data loads
  };

  /**
   * View Card Handler
   * 
   * Maps COBOL paragraph transferring to card detail view:
   * WHEN CCARD-AID-ENTER AND VIEW-REQUESTED-ON (lines 517-541 COCRDLIC.cbl)
   * 
   * COBOL Equivalence:
   * - Selection indicator: VIEW-REQUESTED-ON(I-SELECTED) → 'S' selection
   * - MOVE WS-ROW-CARD-NUM TO CDEMO-CARD-NUM (line 533-534)
   * - EXEC CICS XCTL PROGRAM('COCRDSLC') → navigate('/cards/:cardNumber')
   * 
   * @param {string} cardNumber - Card number to view (16 digits)
   */
  const handleViewCard = (cardNumber) => {
    // Navigate to card detail view
    // Maps COBOL EXEC CICS XCTL PROGRAM(LIT-CARDDTLPGM) (line 538-541)
    navigate(`/cards/${cardNumber}`);
  };

  /**
   * Update Card Handler
   * 
   * Maps COBOL paragraph transferring to card update:
   * WHEN CCARD-AID-ENTER AND UPDATE-REQUESTED-ON (lines 545-569 COCRDLIC.cbl)
   * 
   * COBOL Equivalence:
   * - Selection indicator: UPDATE-REQUESTED-ON(I-SELECTED) → 'U' selection
   * - MOVE WS-ROW-CARD-NUM TO CDEMO-CARD-NUM (line 561-562)
   * - EXEC CICS XCTL PROGRAM('COCRDUPC') → navigate('/cards/:cardNumber/edit')
   * 
   * @param {string} cardNumber - Card number to update (16 digits)
   */
  const handleUpdateCard = (cardNumber) => {
    // Navigate to card update view
    // Maps COBOL EXEC CICS XCTL PROGRAM(LIT-CARDUPDPGM) (line 566-569)
    navigate(`/cards/${cardNumber}/edit`);
  };

  /**
   * Page Change Handler
   * 
   * Maps COBOL screen number tracking (WS-CA-SCREEN-NUM, line 237 COCRDLIC.cbl).
   * Called by Material-UI TablePagination component.
   * 
   * @param {Event} event - Change event
   * @param {number} newPage - New page number (0-based)
   */
  const handlePageChange = (event, newPage) => {
    // Update current page - maps WS-CA-SCREEN-NUM
    setCurrentPage(newPage);
    
    // fetchCards will be triggered by useEffect
  };

  /**
   * Previous Page Handler
   * 
   * Maps COBOL PF7 key handling:
   * WHEN CCARD-AID-PFK07 (lines 439-513 COCRDLIC.cbl)
   * PERFORM 9100-READ-BACKWARDS (line 509)
   * 
   * Business Logic:
   * - Only enabled if not on first page (NOT CA-FIRST-PAGE, line 502)
   * - Decrements screen number: SUBTRACT 1 FROM WS-CA-SCREEN-NUM (line 508)
   * - Reads previous page of cards
   */
  const handlePreviousPage = () => {
    // Check if not on first page - maps CA-FIRST-PAGE condition (line 502)
    if (currentPage > 0) {
      // Decrement page - maps SUBTRACT 1 FROM WS-CA-SCREEN-NUM (line 508)
      setCurrentPage(currentPage - 1);
    }
  };

  /**
   * Next Page Handler
   * 
   * Maps COBOL PF8 key handling:
   * WHEN CCARD-AID-PFK08 (lines 486-497 COCRDLIC.cbl)
   * PERFORM 9000-READ-FORWARD (line 493)
   * 
   * Business Logic:
   * - Only enabled if more pages exist (CA-NEXT-PAGE-EXISTS, line 487)
   * - Increments screen number: ADD +1 TO WS-CA-SCREEN-NUM (line 492)
   * - Reads next page of cards
   */
  const handleNextPage = () => {
    // Calculate total pages for boundary check
    const totalPages = Math.ceil(totalCount / rowsPerPage);
    
    // Check if next page exists - maps CA-NEXT-PAGE-EXISTS condition (line 487)
    if (currentPage < totalPages - 1) {
      // Increment page - maps ADD +1 TO WS-CA-SCREEN-NUM (line 492)
      setCurrentPage(currentPage + 1);
    }
  };

  /**
   * Exit to Menu Handler
   * 
   * Maps COBOL PF3 key handling:
   * WHEN CCARD-AID-PFK03 (lines 384-406 COCRDLIC.cbl)
   * EXEC CICS XCTL PROGRAM(LIT-MENUPGM) (line 402-405)
   * 
   * Returns user to main menu (COMEN01C program).
   */
  const handleExit = () => {
    // Navigate back to main menu
    // Maps COBOL EXEC CICS XCTL PROGRAM('COMEN01C') (line 402-405)
    navigate('/menu');
  };

  /**
   * JSX Render
   * 
   * Transforms BMS COCRDLI screen layout to Material-UI components.
   * Preserves exact field positions, labels, and functionality from mainframe screen.
   */
  return (
    <Box sx={{ p: 3 }}>
      {/* Screen Title - Maps TITLE01 and TITLE02 BMS fields (lines 38-64 COCRDLI.bms) */}
      <Typography variant="h4" gutterBottom sx={{ color: '#1976d2', fontWeight: 'bold' }}>
        List Credit Cards
      </Typography>
      
      <Typography variant="subtitle2" gutterBottom sx={{ color: '#666', mb: 3 }}>
        Transaction: CCLI | Program: COCRDLIC
      </Typography>

      {/* Search Filters Paper - Maps BMS fields ACCTSID and CARDSID (lines 89-105 COCRDLI.bms) */}
      <Paper sx={{ p: 2, mb: 2 }}>
        <Box sx={{ display: 'flex', gap: 2, mb: 2, alignItems: 'flex-end' }}>
          {/* Account Number Filter - Maps ACCTSID field (line 89-95 COCRDLI.bms) */}
          {/* COBOL: CC-ACCT-ID PIC X(11) (lines 99-101 COCRDLIC.cbl) */}
          <TextField
            label="Account Number"
            value={accountFilter}
            onChange={(e) => setAccountFilter(e.target.value)}
            placeholder="11 digits"
            helperText="Optional: Filter by 11-digit account number"
            inputProps={{ 
              maxLength: 11,
              pattern: '[0-9]*',
              inputMode: 'numeric'
            }}
            fullWidth
            size="small"
          />
          
          {/* Card Number Filter - Maps CARDSID field (line 101-107 COCRDLI.bms) */}
          {/* COBOL: CC-CARD-NUM PIC X(16) (lines 102-104 COCRDLIC.cbl) */}
          <TextField
            label="Credit Card Number"
            value={cardFilter}
            onChange={(e) => setCardFilter(e.target.value)}
            placeholder="16 digits"
            helperText="Optional: Filter by 16-digit card number"
            inputProps={{ 
              maxLength: 16,
              pattern: '[0-9]*',
              inputMode: 'numeric'
            }}
            fullWidth
            size="small"
          />
        </Box>
        
        {/* Action Buttons - Maps CICS ENTER key and clear functionality */}
        <Box sx={{ display: 'flex', gap: 2 }}>
          {/* Search Button - Maps CCARD-AID-ENTER (line 371 COCRDLIC.cbl) */}
          <Button
            variant="contained"
            startIcon={<Search />}
            onClick={handleSearch}
            disabled={loading}
          >
            Search
          </Button>
          
          {/* Clear Button - Resets filters */}
          <Button 
            variant="outlined" 
            onClick={handleClearFilters}
            disabled={loading}
          >
            Clear
          </Button>
        </Box>
      </Paper>

      {/* Info Message - Maps INFOMSG BMS field (lines 669-671 COCRDLIC.cbl) */}
      {/* WS-INFORM-REC-ACTIONS (lines 115-116 COCRDLIC.cbl) */}
      {infoMessage && !error && (
        <Alert severity="info" sx={{ mb: 2 }}>
          {infoMessage}
        </Alert>
      )}

      {/* Error Message - Maps ERRMSG BMS field (lines 924 COCRDLIC.cbl) */}
      {/* WS-ERROR-MSG (line 117 COCRDLIC.cbl) */}
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {/* Card List Table - Maps BMS 7-row card layout (lines 140-300 COCRDLI.bms) */}
      {/* COBOL: WS-SCREEN-ROWS OCCURS 7 TIMES (line 255 COCRDLIC.cbl) */}
      <TableContainer component={Paper}>
        <Table>
          {/* Table Header - Maps BMS column headers (lines 108-139 COCRDLI.bms) */}
          <TableHead>
            <TableRow sx={{ backgroundColor: '#f5f5f5' }}>
              <TableCell sx={{ fontWeight: 'bold' }}>Select</TableCell>
              <TableCell sx={{ fontWeight: 'bold' }}>Account Number</TableCell>
              <TableCell sx={{ fontWeight: 'bold' }}>Card Number</TableCell>
              <TableCell sx={{ fontWeight: 'bold' }}>Active</TableCell>
              <TableCell sx={{ fontWeight: 'bold' }}>Actions</TableCell>
            </TableRow>
          </TableHead>
          
          {/* Table Body - Maps BMS repeating fields CRDSEL1-7, ACCTNO1-7, CRDNUM1-7, CRDSTS1-7 */}
          <TableBody>
            {loading ? (
              // Loading indicator during CICS READ operations
              <TableRow>
                <TableCell colSpan={5} align="center" sx={{ py: 4 }}>
                  <CircularProgress />
                  <Typography variant="body2" sx={{ mt: 2 }}>
                    Loading cards...
                  </Typography>
                </TableCell>
              </TableRow>
            ) : cards.length === 0 ? (
              // No data row - shown when WS-NO-RECORDS-FOUND (line 121-122)
              <TableRow>
                <TableCell colSpan={5} align="center" sx={{ py: 4 }}>
                  <Typography variant="body1" color="text.secondary">
                    No cards found. Try adjusting your search filters.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              // Card rows - Maps COBOL WS-SCREEN-ROWS array (lines 255-261 COCRDLIC.cbl)
              // Each row maps to BMS fields: CRDSEL#, ACCTNO#, CRDNUM#, CRDSTS# (lines 140-300)
              cards.map((card, index) => (
                <TableRow 
                  key={card.cardNumber || index}
                  sx={{ '&:hover': { backgroundColor: '#f9f9f9' } }}
                >
                  {/* Select Column - Row number display */}
                  <TableCell>{index + 1}</TableCell>
                  
                  {/* Account Number - Maps ACCTNO# fields (e.g., line 147-151 COCRDLI.bms) */}
                  {/* COBOL: WS-ROW-ACCTNO PIC X(11) (line 258 COCRDLIC.cbl) */}
                  <TableCell>{card.accountId}</TableCell>
                  
                  {/* Card Number - Maps CRDNUM# fields (e.g., line 152-156 COCRDLI.bms) */}
                  {/* COBOL: WS-ROW-CARD-NUM PIC X(16) (line 259 COCRDLIC.cbl) */}
                  <TableCell sx={{ fontFamily: 'monospace' }}>
                    {card.cardNumber}
                  </TableCell>
                  
                  {/* Active Status - Maps CRDSTS# fields (e.g., line 157-161 COCRDLI.bms) */}
                  {/* COBOL: WS-ROW-CARD-STATUS PIC X(1) (line 260 COCRDLIC.cbl) */}
                  {/* Display 'Y' for Active status 'A', 'N' otherwise */}
                  <TableCell>
                    {card.cardStatus === 'A' ? 'Y' : 'N'}
                  </TableCell>
                  
                  {/* Actions Column - Maps COBOL SELECT-OK indicators (lines 77-82 COCRDLIC.cbl) */}
                  {/* 'S' = VIEW-REQUESTED-ON → Visibility icon */}
                  {/* 'U' = UPDATE-REQUESTED-ON → Edit icon */}
                  <TableCell>
                    {/* View Action - Maps 'S' selection (line 78 COCRDLIC.cbl) */}
                    {/* EXEC CICS XCTL PROGRAM('COCRDSLC') (line 538-541) */}
                    <Tooltip title="View Details (S)">
                      <IconButton
                        size="small"
                        onClick={() => handleViewCard(card.cardNumber)}
                        sx={{ mr: 1 }}
                        color="primary"
                      >
                        <Visibility fontSize="small" />
                      </IconButton>
                    </Tooltip>
                    
                    {/* Update Action - Maps 'U' selection (line 79 COCRDLIC.cbl) */}
                    {/* EXEC CICS XCTL PROGRAM('COCRDUPC') (line 566-569) */}
                    <Tooltip title="Update Card (U)">
                      <IconButton
                        size="small"
                        onClick={() => handleUpdateCard(card.cardNumber)}
                        color="secondary"
                      >
                        <Edit fontSize="small" />
                      </IconButton>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>

      {/* Navigation Controls - Maps BMS FKEYS and pagination */}
      {/* PF3, PF7, PF8 functionality (lines 384-497 COCRDLIC.cbl) */}
      <Box sx={{ 
        display: 'flex', 
        justifyContent: 'space-between', 
        alignItems: 'center', 
        mt: 2,
        pt: 2,
        borderTop: '1px solid #e0e0e0'
      }}>
        {/* F3: Exit Button - Maps CCARD-AID-PFK03 (lines 384-406 COCRDLIC.cbl) */}
        {/* EXEC CICS XCTL PROGRAM('COMEN01C') to return to menu */}
        <Button
          variant="outlined"
          startIcon={<ArrowBack />}
          onClick={handleExit}
        >
          F3: Exit
        </Button>
        
        {/* Pagination Controls - Maps page navigation logic */}
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
          {/* F7: Backward Button - Maps CCARD-AID-PFK07 (lines 439-513 COCRDLIC.cbl) */}
          {/* PERFORM 9100-READ-BACKWARDS for previous page */}
          <Button
            variant="outlined"
            startIcon={<NavigateBefore />}
            onClick={handlePreviousPage}
            disabled={currentPage === 0 || loading}
          >
            F7: Backward
          </Button>
          
          {/* Page Number Display - Maps PAGENO BMS field (line 82-83 COCRDLI.bms) */}
          {/* COBOL: MOVE WS-CA-SCREEN-NUM TO PAGENOO (line 667 COCRDLIC.cbl) */}
          <Typography variant="body2" sx={{ minWidth: 120, textAlign: 'center' }}>
            Page {currentPage + 1} of {Math.max(1, Math.ceil(totalCount / rowsPerPage))}
          </Typography>
          
          {/* F8: Forward Button - Maps CCARD-AID-PFK08 (lines 486-497 COCRDLIC.cbl) */}
          {/* PERFORM 9000-READ-FORWARD for next page */}
          <Button
            variant="outlined"
            endIcon={<NavigateNext />}
            onClick={handleNextPage}
            disabled={currentPage >= Math.ceil(totalCount / rowsPerPage) - 1 || loading}
          >
            F8: Forward
          </Button>
        </Box>
      </Box>

      {/* Record Count Display - Additional info for user */}
      {!loading && cards.length > 0 && (
        <Typography variant="caption" sx={{ display: 'block', mt: 1, textAlign: 'center', color: '#666' }}>
          Showing {cards.length} of {totalCount} total cards
        </Typography>
      )}
    </Box>
  );
};

/**
 * Component Export
 * 
 * Exports CardListComponent as default export per schema requirements.
 * 
 * Export Schema Compliance:
 * - name: 'CardListComponent'
 * - kind: 'function'
 * - members_exposed: [] (no sub-exports)
 * - is_default: true
 */
export default CardListComponent;
