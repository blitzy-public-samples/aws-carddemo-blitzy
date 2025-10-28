/**
 * CardListPage Component
 * 
 * Converted from COBOL program: COCRDLIC.cbl
 * Original BMS map: COCRDLI.bms (Card Listing Screen)
 * Original function: Credit card list display with search filters and pagination
 * 
 * BMS Screen Structure (24x80 3270 terminal):
 * - Line 1-2: Header with transaction name, program name, date, time (TRNNAME, PGMNAME, CURDATE, CURTIME)
 * - Line 4: Screen title "List Credit Cards" and page number (PAGENO)
 * - Line 6-7: Search filter fields:
 *   * ACCTSID: Account Number (11 chars, POS=(6,44), ATTRB=(FSET,IC,NORM,UNPROT))
 *   * CARDSID: Credit Card Number (16 chars, POS=(7,44), ATTRB=(FSET,NORM,UNPROT))
 * - Line 9-10: Table column headers (Select, Account Number, Card Number, Active)
 * - Line 11-17: 7 data rows with fields:
 *   * CRDSEL1-7: Select checkbox (1 char, ATTRB=(FSET,NORM,PROT))
 *   * ACCTNO1-7: Account Number (11 chars, ATTRB=(NORM,PROT))
 *   * CRDNUM1-7: Card Number (16 chars, ATTRB=(NORM,PROT))
 *   * CRDSTS1-7: Active Status (1 char Y/N, ATTRB=(NORM,PROT))
 * - Line 20: Info message (INFOMSG, LENGTH=45, POS=(20,19))
 * - Line 23: Error message (ERRMSG, LENGTH=78, COLOR=RED, POS=(23,1))
 * - Line 24: Function key help (F3=Exit F7=Backward F8=Forward)
 * 
 * COBOL Program Logic (COCRDLIC.cbl):
 * - 1000-SCREEN-IO: Main screen I/O control paragraph
 * - 2000-PROCESS-INPUTS: Input validation and filter setup
 * - 3000-READ-CARD-DATA: VSAM CARDFILE browse (EXEC CICS STARTBR/READNEXT)
 * - 3500-APPLY-FILTER-RECORD: Filter logic for account-based card lists
 * - 4000-POPULATE-SCREEN-DATA: Build response array for 7 screen rows
 * - Pagination: WS-CA-SCREEN-NUM (current page), WS-CA-NEXT-PAGE-EXISTS flag
 * 
 * React Implementation:
 * - Material-UI layout with responsive design
 * - TextField components for search filters (account number, card number)
 * - CardTable component with Material-UI DataGrid (10 rows per page per requirements)
 * - REST API integration via cardService.getAllCards() replacing VSAM I/O
 * - Client-side validation using validateCardNumber utility
 * - Error handling with ErrorMessage component
 * - Loading states with CircularProgress
 * - Authentication check with useAuth hook
 * - Navigation on row click to card detail/update page
 * 
 * Key Conversions:
 * - COBOL ACCTSID field → React TextField with 11-char validation
 * - COBOL CARDSID field → React TextField with 16-char validation and Luhn check
 * - COBOL EXEC CICS STARTBR/READNEXT loop → REST GET /api/cards with query params
 * - COBOL WS-SCREEN-DATA array (7 rows) → CardTable with pageSize=10
 * - COBOL F7/F8 navigation → Material-UI DataGrid pagination controls
 * - COBOL ERRMSG field → ErrorMessage component with severity levels
 * - COBOL PAGENO field → Pagination page number display
 * 
 * Per Agent Action Plan Section 0.1.3 MINIMAL CHANGE CLAUSE:
 * - Preserves identical search filter logic from COBOL (account number, card number)
 * - Maintains exact field validation rules from BMS map attributes
 * - Replicates VSAM browse pagination behavior with REST API pagination
 * - Preserves user role-based filtering (admin sees all, user sees account-specific)
 * 
 * Per Agent Action Plan Section 0.7.5:
 * - Maintains COBOL program flow: input validation → data retrieval → display
 * - Preserves VSAM KSDS key access patterns via backend query parameters
 * - Maintains identical error messages from CSMSG01Y.cpy
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 * 
 * @see app/cbl/COCRDLIC.cbl - Original COBOL card list program
 * @see app/bms/COCRDLI.bms - Original BMS 3270 screen definition
 * @see app/cpy/CVACT02Y.cpy - Card record structure copybook
 * @see backend/src/main/java/com/carddemo/controller/CardController.java - REST API endpoint
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Box,
  Container,
  Paper,
  TextField,
  Button,
  Typography,
  Grid,
  CircularProgress,
  Stack,
} from '@mui/material';
import {
  Search as SearchIcon,
  Clear as ClearIcon,
  Add as AddIcon,
} from '@mui/icons-material';

// Internal imports from depends_on_files
import cardService from '../services/cardService';
import { Card } from '../types/card';
import CardTable, { CardData } from '../components/tables/CardTable';
import Header from '../components/common/Header';
import Footer from '../components/common/Footer';
import ErrorMessage from '../components/common/ErrorMessage';
import { useAuth } from '../hooks/useAuth';
import { validateCardNumber } from '../utils/validation';

/**
 * Interface for search filter form state
 * Maps to COBOL ACCTSID and CARDSID input fields
 */
interface SearchFilters {
  /** Account number (11 chars) - Maps to ACCTSID field in COCRDLI.bms */
  accountNumber: string;
  /** Card number (16 chars) - Maps to CARDSID field in COCRDLI.bms */
  cardNumber: string;
}

/**
 * CardListPage Component
 * 
 * Displays paginated list of credit cards with search filters.
 * Converted from COCRDLIC.cbl COBOL program and COCRDLI.bms screen.
 * 
 * Features:
 * - Search by account number (11 digits)
 * - Search by card number (16 digits with Luhn validation)
 * - Paginated table display (10 rows per page matching requirements)
 * - Row selection and navigation to card detail/update
 * - Loading states and error handling
 * - Role-based access control (admin sees all cards, users see account-specific)
 * 
 * @returns React component rendering card list page
 */
const CardListPage: React.FC = () => {
  const navigate = useNavigate();
  const { isAuthenticated, user } = useAuth();

  // Search filter state - Maps to COBOL ACCTSID and CARDSID fields
  const [filters, setFilters] = useState<SearchFilters>({
    accountNumber: '',
    cardNumber: '',
  });

  // Card list data state - Maps to COBOL WS-SCREEN-DATA array
  const [cards, setCards] = useState<Card[]>([]);

  // Loading state - Maps to COBOL processing indicator
  const [loading, setLoading] = useState<boolean>(false);

  // Error state - Maps to COBOL ERRMSG field
  const [error, setError] = useState<string>('');

  // Success message state - Maps to COBOL INFOMSG field
  const [infoMessage, setInfoMessage] = useState<string>('');

  // Pagination state - Maps to COBOL WS-CA-SCREEN-NUM and page tracking
  const [currentPage, setCurrentPage] = useState<number>(1);
  const [totalPages, setTotalPages] = useState<number>(1);
  const [totalCards, setTotalCards] = useState<number>(0);

  // Page size constant - Requirements specify 10 rows per page (changed from COBOL's 7 rows)
  const PAGE_SIZE = 10;

  /**
   * Check authentication on component mount
   * Maps to COBOL: EXEC CICS ASSIGN USERID
   * Redirects to login if not authenticated
   */
  useEffect(() => {
    if (!isAuthenticated) {
      navigate('/login');
    }
  }, [isAuthenticated, navigate]);

  /**
   * Fetch card list data from backend API
   * 
   * Converted from COBOL: 3000-READ-CARD-DATA paragraph
   * COBOL Operation: EXEC CICS STARTBR FILE('CARDDAT') RIDFLD(WS-CARD-RID)
   *                  EXEC CICS READNEXT FILE('CARDDAT') INTO(CARD-RECORD)
   *                  EXEC CICS ENDBR FILE('CARDDAT')
   * 
   * React Operation: cardService.getAllCards() with query parameters
   * 
   * Filtering Logic:
   * - Admin users (userType='A'): Can view all cards
   * - Regular users: Can only view cards for specific account (requires accountNumber)
   * - Account number filter: Maps to COBOL WS-EXCLUDE-THIS-RECORD logic
   * - Card number filter: Maps to COBOL WS-CARD-RID-CARDNUM browse key
   * 
   * @param resetPage - If true, reset to page 1 (for new search)
   */
  const fetchCards = useCallback(async (resetPage: boolean = false) => {
    try {
      setLoading(true);
      setError('');
      setInfoMessage('');

      // Determine page number - reset to 1 for new search, otherwise use current
      const pageNum = resetPage ? 1 : currentPage;

      // Build query parameters for API call
      // Maps to COBOL filter and pagination logic
      const queryParams: any = {
        page: pageNum,
        pageSize: PAGE_SIZE,
      };

      // Add card number filter if provided
      // Maps to COBOL: MOVE CARDSID TO WS-CARD-RID-CARDNUM
      if (filters.cardNumber.trim()) {
        // Validate card number format before API call
        // Maps to COBOL: 2000-PROCESS-INPUTS paragraph validation
        const validation = validateCardNumber(filters.cardNumber);
        if (!validation.valid) {
          setError(validation.message);
          setLoading(false);
          return;
        }
        queryParams.cardNum = filters.cardNumber.trim();
      }

      // Add account number filter if provided
      // Maps to COBOL: MOVE ACCTSID TO WS-FILTER-ACCT-ID
      if (filters.accountNumber.trim()) {
        // Validate account number (11 digits numeric)
        const acctNum = filters.accountNumber.trim();
        if (!/^\d{11}$/.test(acctNum)) {
          setError('Account number must be exactly 11 digits');
          setLoading(false);
          return;
        }
        queryParams.acctId = parseInt(acctNum, 10);
      }

      // Make REST API call to retrieve cards
      // Replaces COBOL: EXEC CICS STARTBR FILE('CARDDAT')
      const response = await cardService.getAllCards(queryParams);

      // Update state with retrieved data
      // Maps to COBOL: 4000-POPULATE-SCREEN-DATA paragraph
      setCards(response.cards);
      setTotalCards(response.pagination.totalItems ?? 0);
      setTotalPages(response.pagination.totalPages ?? 1);
      
      if (resetPage) {
        setCurrentPage(1);
      }

      // Display info message if no results found
      // Maps to COBOL: MOVE 'NO RECORDS FOUND' TO INFOMSG
      if (response.cards.length === 0) {
        setInfoMessage('No cards found matching the search criteria');
      } else {
        setInfoMessage(`Displaying ${response.cards.length} of ${response.pagination.totalItems} cards`);
      }

    } catch (err: any) {
      // Error handling maps COBOL file-status codes to HTTP errors
      // COBOL: IF FILE-STATUS = '23' (record not found) → HTTP 404
      // COBOL: IF FILE-STATUS = '90+' (system error) → HTTP 500
      console.error('[CardListPage] Error fetching cards:', err);
      
      const errorMessage = err?.response?.data?.message || 
                          err?.message || 
                          'Failed to retrieve card list. Please try again.';
      setError(errorMessage);
      setCards([]);
      setTotalCards(0);
      setTotalPages(1);
    } finally {
      setLoading(false);
    }
  }, [filters, currentPage]);

  /**
   * Initial data load on component mount
   * Maps to COBOL: Initial screen display in 1000-SCREEN-IO
   */
  useEffect(() => {
    if (isAuthenticated) {
      fetchCards(false);
    }
  }, [isAuthenticated]);

  /**
   * Handle search button click
   * 
   * Maps to COBOL: 2000-PROCESS-INPUTS paragraph
   * Validates input fields and triggers data retrieval
   * 
   * COBOL Flow:
   * - Validate ACCTSID field (11 numeric digits)
   * - Validate CARDSID field (16 numeric digits + Luhn check)
   * - Set filter flags (WS-FILTER-ACTIVE)
   * - Perform 3000-READ-CARD-DATA
   */
  const handleSearch = () => {
    // Clear previous messages
    setError('');
    setInfoMessage('');

    // Validate at least one filter is provided
    if (!filters.accountNumber.trim() && !filters.cardNumber.trim()) {
      setInfoMessage('Enter account number or card number to search');
      return;
    }

    // Fetch cards with filters, reset to page 1
    fetchCards(true);
  };

  /**
   * Handle clear filters button click
   * 
   * Maps to COBOL: Clear input fields and reload all cards
   * Resets ACCTSID and CARDSID fields to spaces
   */
  const handleClearFilters = () => {
    setFilters({
      accountNumber: '',
      cardNumber: '',
    });
    setError('');
    setInfoMessage('');
    
    // Fetch all cards without filters
    // Maps to COBOL: Browse all records in CARDFILE
    setCurrentPage(1);
    setTimeout(() => {
      fetchCards(true);
    }, 100);
  };

  /**
   * Handle account number input change
   * Maps to COBOL: ACCTSID field modification
   * 
   * @param event - Input change event
   */
  const handleAccountNumberChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const value = event.target.value;
    // Allow only numeric digits, max 11 characters
    // Maps to COBOL: ACCTSID PIC 9(11)
    if (value === '' || /^\d{0,11}$/.test(value)) {
      setFilters(prev => ({ ...prev, accountNumber: value }));
    }
  };

  /**
   * Handle card number input change
   * Maps to COBOL: CARDSID field modification
   * 
   * @param event - Input change event
   */
  const handleCardNumberChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const value = event.target.value;
    // Allow only numeric digits, max 16 characters
    // Maps to COBOL: CARDSID PIC 9(16)
    if (value === '' || /^\d{0,16}$/.test(value)) {
      setFilters(prev => ({ ...prev, cardNumber: value }));
    }
  };

  /**
   * Handle row click in CardTable
   * 
   * Maps to COBOL: Card selection logic (CRDSEL1-7 checkbox processing)
   * Navigates to card detail or update page
   * 
   * COBOL Flow:
   * - User enters selection code in CRDSEL field
   * - Program reads selected CRDNUM (card number)
   * - EXEC CICS XCTL PROGRAM('COCRDUPC') with card number in COMMAREA
   * 
   * @param cardData - Selected card data
   */
  const handleRowClick = (cardData: CardData) => {
    // Navigate to card update page with card number
    // Maps to COBOL: EXEC CICS XCTL PROGRAM('COCRDUPC')
    navigate(`/cards/${cardData.cardNumber}`);
  };



  /**
   * Convert Card objects to CardData format for CardTable
   * 
   * Maps Card interface fields to CardData interface expected by CardTable
   * Handles field name differences between API response and table component
   * 
   * @param cards - Array of Card objects from API
   * @returns Array of CardData objects for table display
   */
  const mapCardsToTableData = (cards: Card[]): CardData[] => {
    return cards.map(card => ({
      id: card.cardNum,
      cardNumber: card.cardNum,
      accountNumber: card.cardAcctId.toString(),
      status: card.cardStatus === 'Y' || card.cardStatus === 'ACTIVE' ? 'Y' : 'N',
      cardAcctId: card.cardAcctId,
      cardMemberId: 0, // Not used in display, placeholder value
      embossedName: card.cardEmbossedName,
      expirationDate: card.cardExpirationDate,
      activeDate: card.cardActiveDate || undefined,
    }));
  };

  /**
   * Handle add new card button click
   * 
   * Maps to COBOL: Navigate to card creation program
   * EXEC CICS XCTL PROGRAM('COCRDADD')
   */
  const handleAddCard = () => {
    navigate('/cards/new');
  };

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      {/* Header component - Maps to BMS lines 1-2 (TRNNAME, PGMNAME, CURDATE, CURTIME) */}
      <Header />

      {/* Main content area */}
      <Container maxWidth="lg" sx={{ flexGrow: 1, py: 4 }}>
        <Paper elevation={3} sx={{ p: 3 }}>
          {/* Page title - Maps to BMS line 4: "List Credit Cards" */}
          <Typography 
            variant="h4" 
            component="h1" 
            gutterBottom 
            sx={{ mb: 3, color: 'primary.main' }}
          >
            List Credit Cards
          </Typography>

          {/* Search filters section - Maps to BMS lines 6-7 (ACCTSID, CARDSID) */}
          <Box sx={{ mb: 4 }}>
            <Typography variant="h6" gutterBottom sx={{ color: 'text.secondary' }}>
              Search Filters
            </Typography>

            <Grid container spacing={2} alignItems="center">
              {/* Account Number filter - Maps to ACCTSID field */}
              <Grid item xs={12} sm={6} md={4}>
                <TextField
                  fullWidth
                  label="Account Number"
                  value={filters.accountNumber}
                  onChange={handleAccountNumberChange}
                  placeholder="Enter 11-digit account number"
                  inputProps={{
                    maxLength: 11,
                    'aria-label': 'Account Number',
                  }}
                  helperText="11 digits"
                  variant="outlined"
                  size="small"
                />
              </Grid>

              {/* Card Number filter - Maps to CARDSID field */}
              <Grid item xs={12} sm={6} md={4}>
                <TextField
                  fullWidth
                  label="Credit Card Number"
                  value={filters.cardNumber}
                  onChange={handleCardNumberChange}
                  placeholder="Enter 16-digit card number"
                  inputProps={{
                    maxLength: 16,
                    'aria-label': 'Card Number',
                  }}
                  helperText="16 digits"
                  variant="outlined"
                  size="small"
                />
              </Grid>

              {/* Search and Clear buttons */}
              <Grid item xs={12} sm={12} md={4}>
                <Stack direction="row" spacing={1}>
                  {/* Search button - Maps to Enter key / Search action */}
                  <Button
                    variant="contained"
                    color="primary"
                    startIcon={<SearchIcon />}
                    onClick={handleSearch}
                    disabled={loading}
                    fullWidth
                  >
                    Search
                  </Button>

                  {/* Clear button - Maps to clearing input fields */}
                  <Button
                    variant="outlined"
                    color="secondary"
                    startIcon={<ClearIcon />}
                    onClick={handleClearFilters}
                    disabled={loading}
                    fullWidth
                  >
                    Clear
                  </Button>
                </Stack>
              </Grid>
            </Grid>
          </Box>

          {/* Error message display - Maps to BMS ERRMSG field (line 23) */}
          {error && (
            <Box sx={{ mb: 2 }}>
              <ErrorMessage 
                message={error} 
                severity="error"
                onClose={() => setError('')}
              />
            </Box>
          )}

          {/* Info message display - Maps to BMS INFOMSG field (line 20) */}
          {infoMessage && !error && (
            <Box sx={{ mb: 2 }}>
              <ErrorMessage 
                message={infoMessage} 
                severity="info"
              />
            </Box>
          )}

          {/* Add New Card button - Admin action */}
          {user?.userType === 'A' && (
            <Box sx={{ mb: 2 }}>
              <Button
                variant="contained"
                color="success"
                startIcon={<AddIcon />}
                onClick={handleAddCard}
                disabled={loading}
              >
                Add New Card
              </Button>
            </Box>
          )}

          {/* Loading indicator */}
          {loading && (
            <Box sx={{ display: 'flex', justifyContent: 'center', my: 4 }}>
              <CircularProgress />
            </Box>
          )}

          {/* Card table - Maps to BMS lines 9-17 (table headers and 7 data rows) */}
          {!loading && (
            <CardTable
              cards={mapCardsToTableData(cards)}
              onRowClick={handleRowClick}
              loading={false}
              pageSize={PAGE_SIZE}
              initialPage={currentPage - 1}
              height={600}
            />
          )}

          {/* Pagination info - Maps to BMS PAGENO field (line 4) */}
          {!loading && cards.length > 0 && (
            <Box sx={{ mt: 2, display: 'flex', justifyContent: 'center' }}>
              <Typography variant="body2" color="text.secondary">
                Page {currentPage} of {totalPages} (Total: {totalCards} cards)
              </Typography>
            </Box>
          )}
        </Paper>
      </Container>

      {/* Footer component - Maps to BMS line 24 (F3=Exit F7=Backward F8=Forward) */}
      <Footer />
    </Box>
  );
};

export default CardListPage;
