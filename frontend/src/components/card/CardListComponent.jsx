/**
 * CardListComponent.jsx
 * 
 * React functional component for displaying a paginated list of credit cards with search 
 * and navigation capabilities. Transforms COCRDLI.bms (Card Listing Screen) BMS mapset to React.
 * 
 * Key Features:
 * - Displays 7 cards per page in table format matching BMS repeating rows (CRDSEL1-7, ACCTNO1-7, CRDNUM1-7, CRDSTS1-7)
 * - Search filters for Account Number (ACCTSID - 11 digits) and Card Number (CARDSID - 16 chars)
 * - Card number masking showing only last 4 digits for PII protection
 * - Pagination controls (F7=Backward/Previous, F8=Forward/Next)
 * - Row selection for navigation to CardDetailComponent
 * - Info and error message display matching BMS INFOMSG and ERRMSG fields
 * 
 * BMS Source: app/bms/COCRDLI.bms
 * COBOL Data Structure: app/cpy/CVACT02Y.cpy (CARD-RECORD)
 * REST Endpoint: GET /api/cards with pagination parameters
 * 
 * @component
 */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
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
  Checkbox,
  Alert,
  Container,
  Grid,
  IconButton,
  Skeleton
} from '@mui/material';
import { Clear } from '@mui/icons-material';
import SearchIcon from '@mui/icons-material/Search';
import NavigateNextIcon from '@mui/icons-material/NavigateNext';
import NavigateBeforeIcon from '@mui/icons-material/NavigateBefore';

// Internal imports - cardService for API calls
import cardService from '../../services/cardService';

// Common components replicating BMS screen layout
import Header from '../common/Header';
import Footer from '../common/Footer';

// Constants including CARDS_PER_PAGE=7 matching BMS layout
import { CARDS_PER_PAGE } from '../../utils/constants';

// Validation utilities for card number format
import { validateCardNumber } from '../../utils/validators';

/**
 * CardListComponent
 * 
 * Main functional component implementing the card listing screen.
 * Matches BMS COCRDLI mapset with 7 repeating rows, search filters, and pagination.
 * 
 * State Management:
 * - cards: Array of card objects from API
 * - currentPage: Zero-based page index for pagination
 * - totalPages: Total number of pages from API response
 * - accountFilter: Account Number search filter (11 digits, maps to ACCTSID)
 * - cardFilter: Card Number search filter (16 chars, maps to CARDSID)
 * - selectedCards: Set of selected card numbers from checkboxes (CRDSEL1-7)
 * - infoMessage: Informational message (maps to INFOMSG POS=(20,19) LENGTH=45)
 * - errorMessage: Error message (maps to ERRMSG POS=(23,1) LENGTH=78 COLOR=RED)
 * - loading: Loading state during API calls
 * 
 * @returns {JSX.Element} Card listing component
 */
const CardListComponent = () => {
  const navigate = useNavigate();

  // State management matching BMS screen fields and behavior
  const [cards, setCards] = useState([]);
  const [currentPage, setCurrentPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [accountFilter, setAccountFilter] = useState('');
  const [cardFilter, setCardFilter] = useState('');
  const [selectedCards, setSelectedCards] = useState(new Set());
  const [infoMessage, setInfoMessage] = useState('');
  const [errorMessage, setErrorMessage] = useState('');
  const [loading, setLoading] = useState(false);
  const [initialLoad, setInitialLoad] = useState(true);

  // Validation state for search inputs
  const [accountFilterError, setAccountFilterError] = useState('');
  const [cardFilterError, setCardFilterError] = useState('');

  /**
   * Validate account number filter input
   * Must be numeric and 11 digits (PIC 9(11) from CVACT02Y.cpy)
   * 
   * @param {string} value - Account number input value
   * @returns {boolean} True if valid or empty, false otherwise
   */
  const validateAccountFilter = useCallback((value) => {
    if (!value || value.trim() === '') {
      setAccountFilterError('');
      return true;
    }

    // Must be numeric
    if (!/^\d+$/.test(value)) {
      setAccountFilterError('Account number must be numeric');
      return false;
    }

    // Must be exactly 11 digits
    if (value.length !== 11) {
      setAccountFilterError('Account number must be exactly 11 digits');
      return false;
    }

    setAccountFilterError('');
    return true;
  }, []);

  /**
   * Validate card number filter input
   * Uses validateCardNumber utility from validators.js
   * Must be 16 alphanumeric characters (PIC X(16) from CVACT02Y.cpy)
   * 
   * @param {string} value - Card number input value
   * @returns {boolean} True if valid or empty, false otherwise
   */
  const validateCardFilter = useCallback((value) => {
    if (!value || value.trim() === '') {
      setCardFilterError('');
      return true;
    }

    const validationResult = validateCardNumber(value);
    if (!validationResult.isValid) {
      setCardFilterError(validationResult.errorMessage);
      return false;
    }

    setCardFilterError('');
    return true;
  }, []);

  /**
   * Fetch cards from API with pagination and filters
   * Calls cardService.searchCards with criteria object containing page, size, accountId, cardNumber
   * Matches GET /api/cards REST endpoint from CardListService.java
   * 
   * @param {number} page - Zero-based page number
   * @param {string} acctFilter - Optional account number filter
   * @param {string} crdFilter - Optional card number filter
   */
  const fetchCards = useCallback(async (page, acctFilter = '', crdFilter = '') => {
    try {
      setLoading(true);
      setErrorMessage('');
      setInfoMessage('');

      // Build criteria object for API call
      const criteria = {
        page: page + 1, // searchCards expects 1-indexed pages
        size: CARDS_PER_PAGE
      };
      
      if (acctFilter && acctFilter.trim() !== '') {
        criteria.accountId = acctFilter.trim();
      }
      if (crdFilter && crdFilter.trim() !== '') {
        criteria.cardNumber = crdFilter.trim();
      }

      // Call cardService.searchCards with criteria
      // Returns { cards: [], pagination: { totalPages, currentPage, totalCards, ... }, criteria }
      const response = await cardService.searchCards(criteria);

      setCards(response.cards || []);
      setTotalPages(response.pagination?.totalPages || 0);
      setTotalElements(response.pagination?.totalCards || 0);
      setCurrentPage(response.pagination?.currentPage ? response.pagination.currentPage - 1 : 0); // Convert back to 0-indexed

      // Set info message showing results count (maps to INFOMSG field)
      if (response.cards && response.cards.length > 0) {
        const filterText = (acctFilter || crdFilter) ? ' matching filters' : '';
        setInfoMessage(`${response.pagination?.totalCards || 0} card${response.pagination?.totalCards !== 1 ? 's' : ''} found${filterText}`);
      } else {
        setInfoMessage('No cards found. Try adjusting your search criteria.');
      }

      // Clear selection when fetching new data
      setSelectedCards(new Set());

    } catch (error) {
      console.error('Error fetching cards:', error);
      
      // Map error to user-friendly message (matches ERRMSG field COLOR=RED)
      if (error.response) {
        if (error.response.status === 404) {
          setErrorMessage('No cards found for the specified criteria.');
          setCards([]);
          setTotalPages(0);
          setTotalElements(0);
        } else if (error.response.status === 401) {
          setErrorMessage('Session expired. Please login again.');
        } else if (error.response.status === 403) {
          setErrorMessage('Access denied. You do not have permission to view cards.');
        } else {
          setErrorMessage(error.response.data?.message || 'Error retrieving card list. Please try again.');
        }
      } else if (error.request) {
        setErrorMessage('Network error. Please check your connection and try again.');
      } else {
        setErrorMessage('Unexpected error occurred. Please try again.');
      }
      
      setCards([]);
      setInfoMessage('');
    } finally {
      setLoading(false);
      setInitialLoad(false);
    }
  }, []);

  /**
   * useEffect hook to fetch cards on component mount and when page changes
   * Implements data fetching on mount matching COBOL program initialization
   */
  useEffect(() => {
    fetchCards(currentPage, accountFilter, cardFilter);
  }, [fetchCards, currentPage, accountFilter, cardFilter]);

  /**
   * Handle search button click
   * Validates filters and triggers new search from page 0
   * Maps to ENTER key action in BMS screen
   */
  const handleSearch = useCallback(() => {
    // Validate both filters before searching
    const acctValid = validateAccountFilter(accountFilter);
    const cardValid = validateCardFilter(cardFilter);

    if (!acctValid || !cardValid) {
      setErrorMessage('Please correct the validation errors before searching.');
      return;
    }

    // Reset to page 0 when applying new filters
    setCurrentPage(0);
    fetchCards(0, accountFilter, cardFilter);
  }, [accountFilter, cardFilter, fetchCards, validateAccountFilter, validateCardFilter]);

  /**
   * Handle clear filters button click
   * Resets both search filters to empty strings and fetches all cards
   */
  const handleClearFilters = useCallback(() => {
    setAccountFilter('');
    setCardFilter('');
    setAccountFilterError('');
    setCardFilterError('');
    setErrorMessage('');
    setCurrentPage(0);
    fetchCards(0, '', '');
  }, [fetchCards]);

  /**
   * Handle previous page button click
   * Maps to F7=Backward function key from BMS
   */
  const handlePreviousPage = useCallback(() => {
    if (currentPage > 0) {
      const newPage = currentPage - 1;
      setCurrentPage(newPage);
      fetchCards(newPage, accountFilter, cardFilter);
    }
  }, [currentPage, accountFilter, cardFilter, fetchCards]);

  /**
   * Handle next page button click
   * Maps to F8=Forward function key from BMS
   */
  const handleNextPage = useCallback(() => {
    if (currentPage < totalPages - 1) {
      const newPage = currentPage + 1;
      setCurrentPage(newPage);
      fetchCards(newPage, accountFilter, cardFilter);
    }
  }, [currentPage, totalPages, accountFilter, cardFilter, fetchCards]);

  /**
   * Handle account filter input change
   * Implements debounced input for ACCTSID field (POS=(6,44) LENGTH=11)
   * 
   * @param {Event} event - Input change event
   */
  const handleAccountFilterChange = useCallback((event) => {
    const value = event.target.value;
    setAccountFilter(value);
    validateAccountFilter(value);
  }, [validateAccountFilter]);

  /**
   * Handle card filter input change
   * Implements debounced input for CARDSID field (POS=(7,44) LENGTH=16)
   * 
   * @param {Event} event - Input change event
   */
  const handleCardFilterChange = useCallback((event) => {
    const value = event.target.value;
    setCardFilter(value);
    validateCardFilter(value);
  }, [validateCardFilter]);

  /**
   * Handle checkbox selection for a card
   * Manages CRDSEL1-7 field state (though not used in current flow)
   * 
   * @param {string} cardNumber - Card number to toggle selection
   */
  const handleCardSelection = useCallback((cardNumber) => {
    setSelectedCards(prevSelected => {
      const newSelected = new Set(prevSelected);
      if (newSelected.has(cardNumber)) {
        newSelected.delete(cardNumber);
      } else {
        newSelected.add(cardNumber);
      }
      return newSelected;
    });
  }, []);

  /**
   * Handle card row click for navigation
   * Navigates to CardDetailComponent with selected card number
   * Maps to CICS XCTL from COCRDLIC.cbl to COCRDSLC.cbl
   * 
   * @param {string} cardNumber - Card number to view details
   */
  const handleCardRowClick = useCallback((cardNumber) => {
    navigate(`/cards/${cardNumber}`);
  }, [navigate]);

  /**
   * Handle Enter key press in search fields
   * Triggers search when Enter is pressed
   * 
   * @param {KeyboardEvent} event - Keyboard event
   */
  const handleKeyPress = useCallback((event) => {
    if (event.key === 'Enter') {
      handleSearch();
    }
  }, [handleSearch]);

  /**
   * Render loading skeleton for table rows
   * Displays 7 skeleton rows matching CARDS_PER_PAGE constant
   * 
   * @returns {JSX.Element[]} Array of skeleton table rows
   */
  const renderLoadingSkeleton = () => {
    return Array.from({ length: CARDS_PER_PAGE }).map((_, index) => (
      <TableRow key={`skeleton-${index}`}>
        <TableCell padding="checkbox">
          <Skeleton variant="rectangular" width={24} height={24} />
        </TableCell>
        <TableCell>
          <Skeleton variant="text" width="80%" />
        </TableCell>
        <TableCell>
          <Skeleton variant="text" width="90%" />
        </TableCell>
        <TableCell>
          <Skeleton variant="text" width="40%" />
        </TableCell>
      </TableRow>
    ));
  };

  /**
   * Render empty state message
   * Displays when no cards are found after successful API call
   * 
   * @returns {JSX.Element} Empty state message
   */
  const renderEmptyState = () => {
    return (
      <TableRow>
        <TableCell colSpan={4} align="center" sx={{ py: 8 }}>
          <Typography variant="body1" color="text.secondary" gutterBottom>
            No cards found
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {(accountFilter || cardFilter) 
              ? 'Try adjusting your search criteria' 
              : 'There are no cards to display'}
          </Typography>
        </TableCell>
      </TableRow>
    );
  };

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      {/* Header component - replicates BMS 3270 screen header with TRNNAME='CCLI', TITLE='List Credit Cards' */}
      <Header 
        transactionName="CCLI"
        title1="CardDemo"
        title2="List Credit Cards"
      />

      <Container maxWidth="lg" sx={{ flex: 1, py: 3 }}>
        {/* Search Filters Section - Maps to ACCTSID and CARDSID BMS fields */}
        <Paper elevation={2} sx={{ p: 3, mb: 3 }}>
          <Typography variant="h6" gutterBottom>
            Search Filters
          </Typography>
          
          <Grid container spacing={2} alignItems="flex-start">
            {/* Account Number Filter - ACCTSID field POS=(6,44) LENGTH=11 with IC (initial cursor) */}
            <Grid item xs={12} sm={6} md={4}>
              <TextField
                fullWidth
                label="Account Number"
                placeholder="Enter 11-digit account number"
                value={accountFilter}
                onChange={handleAccountFilterChange}
                onKeyPress={handleKeyPress}
                error={Boolean(accountFilterError)}
                helperText={accountFilterError || 'Enter 11-digit account number'}
                inputProps={{
                  maxLength: 11,
                  'aria-label': 'Account number filter',
                  autoFocus: true  // IC attribute from BMS - initial cursor focus
                }}
                disabled={loading}
              />
            </Grid>

            {/* Card Number Filter - CARDSID field POS=(7,44) LENGTH=16 */}
            <Grid item xs={12} sm={6} md={4}>
              <TextField
                fullWidth
                label="Card Number"
                placeholder="Enter 16-digit card number"
                value={cardFilter}
                onChange={handleCardFilterChange}
                onKeyPress={handleKeyPress}
                error={Boolean(cardFilterError)}
                helperText={cardFilterError || 'Enter 16-digit card number'}
                inputProps={{
                  maxLength: 16,
                  'aria-label': 'Card number filter'
                }}
                disabled={loading}
              />
            </Grid>

            {/* Search and Clear Buttons */}
            <Grid item xs={12} sm={12} md={4}>
              <Box sx={{ display: 'flex', gap: 1, height: '100%', alignItems: 'center' }}>
                <Button
                  variant="contained"
                  color="primary"
                  onClick={handleSearch}
                  disabled={loading || Boolean(accountFilterError) || Boolean(cardFilterError)}
                  startIcon={<SearchIcon />}
                  fullWidth
                  sx={{ height: '56px' }}
                >
                  Search
                </Button>
                <IconButton
                  color="secondary"
                  onClick={handleClearFilters}
                  disabled={loading || (!accountFilter && !cardFilter)}
                  aria-label="Clear filters"
                  sx={{ 
                    height: '56px', 
                    width: '56px',
                    border: '1px solid',
                    borderColor: 'divider',
                    borderRadius: 1
                  }}
                >
                  <Clear />
                </IconButton>
              </Box>
            </Grid>
          </Grid>
        </Paper>

        {/* Info Message - Maps to INFOMSG field POS=(20,19) LENGTH=45 COLOR=NEUTRAL */}
        {infoMessage && !errorMessage && (
          <Alert severity="info" sx={{ mb: 2 }}>
            {infoMessage}
          </Alert>
        )}

        {/* Error Message - Maps to ERRMSG field POS=(23,1) LENGTH=78 COLOR=RED ATTRB=ASKIP,BRT,FSET */}
        {errorMessage && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setErrorMessage('')}>
            {errorMessage}
          </Alert>
        )}

        {/* Card List Table - Maps to repeating rows CRDSEL1-7, ACCTNO1-7, CRDNUM1-7, CRDSTS1-7 */}
        <Paper elevation={2}>
          {/* Page Number Display - Maps to PAGENO field POS=(4,76) LENGTH=3 */}
          <Box sx={{ px: 3, pt: 2, pb: 1, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <Typography variant="h6">
              Card List
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Page {currentPage + 1} of {totalPages || 1}
            </Typography>
          </Box>

          <TableContainer>
            <Table aria-label="card list table">
              {/* Table Header - Maps to BMS column headers at POS=(9,*) */}
              <TableHead>
                <TableRow>
                  {/* Select column - CRDSEL header POS=(9,10) */}
                  <TableCell padding="checkbox">
                    <Typography variant="subtitle2" fontWeight="bold">
                      Select
                    </Typography>
                  </TableCell>
                  {/* Account Number column - Header at POS=(9,21) */}
                  <TableCell>
                    <Typography variant="subtitle2" fontWeight="bold">
                      Account Number
                    </Typography>
                  </TableCell>
                  {/* Card Number column - Header at POS=(9,45) */}
                  <TableCell>
                    <Typography variant="subtitle2" fontWeight="bold">
                      Card Number
                    </Typography>
                  </TableCell>
                  {/* Active Status column - Header at POS=(9,66) */}
                  <TableCell>
                    <Typography variant="subtitle2" fontWeight="bold">
                      Active
                    </Typography>
                  </TableCell>
                </TableRow>
              </TableHead>

              {/* Table Body - 7 rows mapping to CRDSEL1-7, ACCTNO1-7, CRDNUM1-7, CRDSTS1-7 at POS=(11-17,*) */}
              <TableBody>
                {loading && initialLoad ? (
                  // Show loading skeleton on initial load
                  renderLoadingSkeleton()
                ) : cards.length === 0 ? (
                  // Show empty state when no cards found
                  renderEmptyState()
                ) : (
                  // Render card rows (up to 7 per page matching CARDS_PER_PAGE constant)
                  cards.map((card, index) => (
                    <TableRow
                      key={card.cardNumber || `card-${index}`}
                      hover
                      onClick={() => handleCardRowClick(card.cardNumber)}
                      sx={{ 
                        cursor: 'pointer',
                        '&:hover': {
                          backgroundColor: 'action.hover'
                        }
                      }}
                      aria-label={`Card ${card.cardNumber}`}
                    >
                      {/* Select Checkbox - Maps to CRDSEL1-7 fields */}
                      <TableCell padding="checkbox" onClick={(e) => e.stopPropagation()}>
                        <Checkbox
                          checked={selectedCards.has(card.cardNumber)}
                          onChange={() => handleCardSelection(card.cardNumber)}
                          inputProps={{ 'aria-label': `Select card ${card.cardNumber}` }}
                        />
                      </TableCell>

                      {/* Account Number - Maps to ACCTNO1-7 fields (11 digits) */}
                      <TableCell>
                        <Typography variant="body2">
                          {card.accountId || 'N/A'}
                        </Typography>
                      </TableCell>

                      {/* Card Number (Masked) - Maps to CRDNUM1-7 fields (16 chars)
                          Displays format '**** **** **** XXXX' where XXXX are last 4 digits
                          Protects CARD-NUM PII per section 0.4 security requirements */}
                      <TableCell>
                        <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>
                          {card.cardNumber}
                        </Typography>
                      </TableCell>

                      {/* Active Status - Maps to CRDSTS1-7 fields (Y/N single character) */}
                      <TableCell>
                        <Typography 
                          variant="body2"
                          sx={{
                            color: card.activeStatus === 'Y' ? 'success.main' : 'error.main',
                            fontWeight: 'medium'
                          }}
                        >
                          {card.activeStatus === 'Y' ? 'Yes' : 'No'}
                        </Typography>
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>

          {/* Pagination Controls - Maps to F7=Backward and F8=Forward function keys */}
          <Box sx={{ px: 3, py: 2, display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderTop: '1px solid', borderColor: 'divider' }}>
            <Box>
              <Typography variant="body2" color="text.secondary">
                Showing {cards.length > 0 ? ((currentPage * CARDS_PER_PAGE) + 1) : 0} to {Math.min((currentPage + 1) * CARDS_PER_PAGE, totalElements)} of {totalElements} cards
              </Typography>
            </Box>
            <Box sx={{ display: 'flex', gap: 1 }}>
              {/* Previous Button - Maps to F7=Backward function key */}
              <Button
                variant="outlined"
                onClick={handlePreviousPage}
                disabled={loading || currentPage === 0}
                startIcon={<NavigateBeforeIcon />}
                aria-label="Previous page"
              >
                Previous (F7)
              </Button>
              {/* Next Button - Maps to F8=Forward function key */}
              <Button
                variant="outlined"
                onClick={handleNextPage}
                disabled={loading || currentPage >= totalPages - 1}
                endIcon={<NavigateNextIcon />}
                aria-label="Next page"
              >
                Next (F8)
              </Button>
            </Box>
          </Box>
        </Paper>
      </Container>

      {/* Footer component - replicates BMS footer with F3=Exit, F7=Backward, F8=Forward */}
      <Footer 
        functionKeys={[
          { key: 'F3', label: 'Exit', action: () => navigate('/menu') },
          { key: 'F7', label: 'Backward', action: handlePreviousPage, disabled: currentPage === 0 },
          { key: 'F8', label: 'Forward', action: handleNextPage, disabled: currentPage >= totalPages - 1 }
        ]}
      />
    </Box>
  );
};

export default CardListComponent;
