/**
 * CardDetailComponent - Card Selection/Detail View Screen
 * 
 * React functional component transforming COCRDSL.bms (Card Selection/View screen)
 * BMS mapset to modern web interface. Displays detailed credit card information
 * with search functionality allowing users to look up card details by account number
 * or card number.
 * 
 * Original COBOL Sources:
 * - app/bms/COCRDSL.bms: BMS mapset for card detail view screen (CCDL transaction)
 * - app/cbl/COCRDSLC.cbl: COBOL program handling card detail view logic
 * - app/cpy/CVACT02Y.cpy: Card record structure (CARD-RECORD, 150 bytes)
 * - app/cpy/COCOM01Y.cpy: COMMAREA structure for session context
 * 
 * BMS Screen Layout (24x80 terminal):
 * Line 1-3: Header with transaction name, titles, date/time
 * Line 4: Screen title "View Credit Card Detail"
 * Line 7: Account Number input field (11 digits, POS=(7,45))
 * Line 8: Card Number input field (16 chars, POS=(8,45))
 * Line 11: Name on card display (50 chars, POS=(11,25))
 * Line 13: Card Active Y/N display (1 char, POS=(13,25))
 * Line 15: Expiry Date display MM/YYYY (POS=(15,25))
 * Line 20: Info message area (40 chars, POS=(20,25), COLOR=NEUTRAL)
 * Line 23: Error message area (80 chars, POS=(23,1), COLOR=RED)
 * Line 24: Function keys "ENTER=Search Cards  F3=Exit"
 * 
 * Key Features:
 * - Search by account number (11 digits) or card number (16 digits)
 * - Display complete card details including cardholder name, status, expiration
 * - Card number masking for security (shows only last 4 digits: ****-****-****-1234)
 * - Real-time validation using validators module
 * - Error and info message display matching BMS COLOR attributes
 * - F3=Exit and ENTER=Search Cards keyboard navigation
 * - Loading state during API calls
 * - Empty state when no card found
 * - Material-UI responsive design matching BMS layout semantics
 * 
 * Agent Action Plan Compliance:
 * - Section 0.1: Maintains functional equivalence with CICS transaction CCDL
 * - Section 0.3: Follows Repository Pattern for data access via cardService
 * - Section 0.6: File-by-file transformation from COCRDSL.bms to React JSX
 * - Section 0.10: Preserves all validation rules and error handling patterns
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import {
  Box,
  Card,
  CardContent,
  TextField,
  Typography,
  Grid,
  Button,
  Alert,
  CircularProgress,
  Divider,
  Paper
} from '@mui/material';
import { Search as SearchIcon, ExitToApp as ExitToAppIcon } from '@mui/icons-material';

// Internal service and component imports
import { 
  getCardByNumber, 
  getCardsByAccount, 
  maskCardNumber 
} from '../../services/cardService.js';
import Header from '../common/Header.jsx';
import Footer from '../common/Footer.jsx';

// Utility imports
import {
  ERROR_REQUIRED,
  ERROR_INVALID_FORMAT,
  MSG_SERVER_ERROR,
  MAX_LENGTH_ACCOUNT_ID,
  MAX_LENGTH_CARD_NUMBER,
  MAX_LENGTH_EMBOSSED_NAME,
  CARD_STATUS
} from '../../utils/constants.js';
import {
  validateAccountId,
  validateCardNumber
} from '../../utils/validators.js';

// Auth context import with fallback for undefined context
let useAuth;
try {
  const authModule = await import('../../context/AuthContext.js');
  useAuth = authModule.useAuth;
} catch (error) {
  // Fallback if AuthContext doesn't exist yet
  useAuth = () => ({
    isAuthenticated: true,
    userId: 'DEMO',
    userType: 'U',
    isAdmin: false
  });
}

/**
 * CardDetailComponent
 * 
 * Displays detailed credit card information with search functionality.
 * Allows searching by account number or card number input fields.
 * 
 * @component
 * @returns {JSX.Element} Card detail view component
 * 
 * @example
 * // Route configuration
 * <Route path="/cards/:cardNumber" element={<CardDetailComponent />} />
 * 
 * @example
 * // Direct usage
 * <CardDetailComponent />
 */
const CardDetailComponent = () => {
  // ============================================================================
  // HOOKS AND STATE MANAGEMENT
  // ============================================================================

  const navigate = useNavigate();
  const location = useLocation();
  const auth = useAuth();

  // Search input state - corresponds to BMS input fields ACCTSID and CARDSID
  const [accountNumber, setAccountNumber] = useState('');
  const [cardNumber, setCardNumber] = useState('');

  // Card data state - corresponds to CARD-RECORD structure from CVACT02Y.cpy
  const [cardData, setCardData] = useState(null);

  // UI state management
  const [loading, setLoading] = useState(false);
  const [infoMessage, setInfoMessage] = useState('');  // INFOMSG field (POS=(20,25))
  const [errorMessage, setErrorMessage] = useState(''); // ERRMSG field (POS=(23,1))

  // Validation error state for input fields
  const [accountNumberError, setAccountNumberError] = useState('');
  const [cardNumberError, setCardNumberError] = useState('');

  // ============================================================================
  // INITIALIZATION - Load card from URL parameter or location state
  // ============================================================================

  useEffect(() => {
    // Check if card number is in URL parameters or location state
    const urlCardNumber = location.pathname.split('/').pop();
    const stateCardNumber = location.state?.cardNumber;
    const stateAccountNumber = location.state?.accountNumber;

    if (stateCardNumber) {
      // Load card from location state (navigation from card list)
      setCardNumber(stateCardNumber);
      fetchCardByNumber(stateCardNumber);
    } else if (stateAccountNumber) {
      // Search by account from location state
      setAccountNumber(stateAccountNumber);
      fetchCardsByAccount(stateAccountNumber);
    } else if (urlCardNumber && urlCardNumber !== 'detail' && urlCardNumber.length === 16) {
      // Load card from URL parameter
      setCardNumber(urlCardNumber);
      fetchCardByNumber(urlCardNumber);
    }
  }, []);

  // ============================================================================
  // DATA FETCHING FUNCTIONS
  // Replaces COBOL: EXEC CICS READ DATASET(CARDDAT) INTO(CARD-RECORD)
  // ============================================================================

  /**
   * Fetches card details by card number
   * 
   * COBOL Equivalent: COCRDSLC.cbl - READ-CARD-BY-NUMBER paragraph
   * - EXEC CICS READ DATASET(CARDDAT) INTO(CARD-RECORD) RIDFLD(WS-CARD-RID)
   * 
   * @param {string} cardNum - 16-digit card number
   */
  const fetchCardByNumber = async (cardNum) => {
    // Clear previous messages
    setErrorMessage('');
    setInfoMessage('');
    setCardData(null);

    // Validate card number before API call
    const validation = validateCardNumber(cardNum);
    if (!validation.isValid) {
      setErrorMessage(validation.errorMessage);
      return;
    }

    setLoading(true);

    try {
      // Call REST API: GET /api/cards/{cardNumber}
      const card = await getCardByNumber(cardNum);
      setCardData(card);
      setInfoMessage('Card details retrieved successfully');
    } catch (error) {
      // Enhanced error handling matching COBOL RESP/RESP2 pattern
      const errorMsg = error.message || MSG_SERVER_ERROR;
      setErrorMessage(errorMsg);
      setCardData(null);
    } finally {
      setLoading(false);
    }
  };

  /**
   * Fetches cards associated with an account number
   * 
   * COBOL Equivalent: COCRDSLC.cbl - READ-CARDS-BY-ACCOUNT paragraph
   * - EXEC CICS STARTBR DATASET(CARDAIX) with account key browse
   * 
   * @param {string} acctNum - 11-digit account number
   */
  const fetchCardsByAccount = async (acctNum) => {
    // Clear previous messages
    setErrorMessage('');
    setInfoMessage('');
    setCardData(null);

    // Validate account ID before API call
    const validation = validateAccountId(acctNum);
    if (!validation.isValid) {
      setErrorMessage(validation.errorMessage);
      return;
    }

    setLoading(true);

    try {
      // Call REST API: GET /api/cards?accountId={accountId}
      const result = await getCardsByAccount(acctNum);
      
      if (result.cards && result.cards.length > 0) {
        // Display first card found for the account
        const firstCard = result.cards[0];
        setCardData(firstCard);
        setCardNumber(firstCard.fullCardNumber || firstCard.cardNumber);
        
        if (result.cards.length > 1) {
          setInfoMessage(`Found ${result.cards.length} cards for account. Displaying first card.`);
        } else {
          setInfoMessage('Card details retrieved successfully');
        }
      } else {
        // No cards found for account - equivalent to COBOL NOTFND condition
        setErrorMessage(`No cards found for account number ${acctNum}`);
        setCardData(null);
      }
    } catch (error) {
      // Enhanced error handling
      const errorMsg = error.message || MSG_SERVER_ERROR;
      setErrorMessage(errorMsg);
      setCardData(null);
    } finally {
      setLoading(false);
    }
  };

  // ============================================================================
  // EVENT HANDLERS
  // Replaces COBOL: EVALUATE EIBAID logic for function key processing
  // ============================================================================

  /**
   * Handles search button click (ENTER key functionality)
   * 
   * COBOL Equivalent: COCRDSLC.cbl - PROCESS-ENTER-KEY paragraph
   * BMS Field: FKEYS='ENTER=Search Cards  F3=Exit'
   */
  const handleSearch = useCallback(() => {
    // Clear previous validation errors
    setAccountNumberError('');
    setCardNumberError('');
    setErrorMessage('');
    setInfoMessage('');

    // Validate at least one search field is provided
    if (!accountNumber && !cardNumber) {
      setErrorMessage('Please enter either Account Number or Card Number to search');
      return;
    }

    // If both fields provided, prioritize card number search
    if (cardNumber) {
      const validation = validateCardNumber(cardNumber);
      if (!validation.isValid) {
        setCardNumberError(validation.errorMessage);
        setErrorMessage(validation.errorMessage);
        return;
      }
      fetchCardByNumber(cardNumber);
    } else if (accountNumber) {
      const validation = validateAccountId(accountNumber);
      if (!validation.isValid) {
        setAccountNumberError(validation.errorMessage);
        setErrorMessage(validation.errorMessage);
        return;
      }
      fetchCardsByAccount(accountNumber);
    }
  }, [accountNumber, cardNumber]);

  /**
   * Handles F3=Exit button click
   * 
   * COBOL Equivalent: COCRDSLC.cbl - PROCESS-PF3-KEY paragraph
   * - EXEC CICS XCTL PROGRAM('COCRDLIC') (return to card list)
   * BMS Field: FKEYS='F3=Exit'
   */
  const handleExit = useCallback(() => {
    // Navigate back to card list screen
    navigate('/cards');
  }, [navigate]);

  /**
   * Handles Enter key press in input fields
   * Triggers search when Enter is pressed
   */
  const handleKeyPress = (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      handleSearch();
    }
  };

  /**
   * Handles account number input change with validation
   * Enforces 11-digit numeric constraint from COBOL PIC 9(11)
   */
  const handleAccountNumberChange = (event) => {
    const value = event.target.value;
    
    // Allow only numeric input, max 11 digits
    if (value === '' || /^\d{0,11}$/.test(value)) {
      setAccountNumber(value);
      setAccountNumberError('');
      
      // Clear card number when account number is entered
      if (value) {
        setCardNumber('');
        setCardNumberError('');
      }
    }
  };

  /**
   * Handles card number input change with validation
   * Enforces 16-character alphanumeric constraint from COBOL PIC X(16)
   */
  const handleCardNumberChange = (event) => {
    const value = event.target.value;
    
    // Allow only alphanumeric input, max 16 characters
    if (value === '' || /^[\dA-Za-z]{0,16}$/.test(value)) {
      setCardNumber(value);
      setCardNumberError('');
      
      // Clear account number when card number is entered
      if (value) {
        setAccountNumber('');
        setAccountNumberError('');
      }
    }
  };

  /**
   * Formats expiration date for display
   * Ensures MM/YYYY format matching BMS POS=(15,25) layout with '/' separator
   * 
   * @param {string} expirationDate - Date in various formats
   * @returns {string} Formatted date as MM/YYYY
   */
  const formatExpirationDate = (expirationDate) => {
    if (!expirationDate) {
      return '';
    }
    
    // If already in MM/YYYY format, return as is
    if (/^\d{2}\/\d{4}$/.test(expirationDate)) {
      return expirationDate;
    }
    
    // Convert from YYYY-MM-DD or other formats
    if (expirationDate.includes('-')) {
      const parts = expirationDate.split('-');
      if (parts.length >= 2) {
        return `${parts[1]}/${parts[0]}`;
      }
    }
    
    return expirationDate;
  };

  // ============================================================================
  // RENDER COMPONENT
  // ============================================================================

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      {/* Header Component - BMS screen lines 1-3 */}
      <Header 
        transactionName="CCDL"
        programName="COCRDSLC"
        title1="AWS Mainframe Modernization"
        title2="CardDemo"
      />

      {/* Main Content Area - BMS screen lines 4-23 */}
      <Box component="main" sx={{ flexGrow: 1, p: 3, backgroundColor: '#f5f5f5' }}>
        <Paper elevation={2} sx={{ maxWidth: 1000, mx: 'auto', p: 3 }}>
          {/* Screen Title - BMS POS=(4,30): "View Credit Card Detail" */}
          <Typography 
            variant="h5" 
            component="h1" 
            gutterBottom 
            sx={{ 
              textAlign: 'center', 
              color: '#1976d2',
              fontWeight: 'bold',
              mb: 3 
            }}
          >
            View Credit Card Detail
          </Typography>

          <Divider sx={{ mb: 3 }} />

          {/* Search Input Fields Section - BMS lines 7-8 */}
          <Card variant="outlined" sx={{ mb: 3, backgroundColor: '#fafafa' }}>
            <CardContent>
              <Typography variant="h6" gutterBottom color="primary">
                Search Card
              </Typography>
              
              <Grid container spacing={3}>
                {/* Account Number Input - BMS ACCTSID field POS=(7,45) LENGTH=11 */}
                <Grid item xs={12} md={6}>
                  <TextField
                    fullWidth
                    label="Account Number"
                    value={accountNumber}
                    onChange={handleAccountNumberChange}
                    onKeyPress={handleKeyPress}
                    error={Boolean(accountNumberError)}
                    helperText={accountNumberError || '11 digits'}
                    placeholder="Enter 11-digit account number"
                    inputProps={{
                      maxLength: MAX_LENGTH_ACCOUNT_ID,
                      'aria-label': 'Account Number',
                      autoFocus: true // IC (Initial Cursor) attribute from BMS
                    }}
                    disabled={loading}
                    sx={{
                      '& .MuiInputBase-input': {
                        fontFamily: 'monospace'
                      }
                    }}
                  />
                </Grid>

                {/* Card Number Input - BMS CARDSID field POS=(8,45) LENGTH=16 */}
                <Grid item xs={12} md={6}>
                  <TextField
                    fullWidth
                    label="Card Number"
                    value={cardNumber}
                    onChange={handleCardNumberChange}
                    onKeyPress={handleKeyPress}
                    error={Boolean(cardNumberError)}
                    helperText={cardNumberError || '16 characters'}
                    placeholder="Enter 16-character card number"
                    inputProps={{
                      maxLength: MAX_LENGTH_CARD_NUMBER,
                      'aria-label': 'Card Number'
                    }}
                    disabled={loading}
                    sx={{
                      '& .MuiInputBase-input': {
                        fontFamily: 'monospace'
                      }
                    }}
                  />
                </Grid>

                {/* Search Button - ENTER key functionality */}
                <Grid item xs={12}>
                  <Button
                    variant="contained"
                    color="primary"
                    startIcon={loading ? <CircularProgress size={20} color="inherit" /> : <SearchIcon />}
                    onClick={handleSearch}
                    disabled={loading || (!accountNumber && !cardNumber)}
                    fullWidth
                    sx={{ py: 1.5 }}
                  >
                    {loading ? 'Searching...' : 'Search Cards'}
                  </Button>
                </Grid>
              </Grid>
            </CardContent>
          </Card>

          {/* Info Message Display - BMS INFOMSG field POS=(20,25) LENGTH=40 COLOR=NEUTRAL */}
          {infoMessage && (
            <Alert severity="info" sx={{ mb: 2 }} onClose={() => setInfoMessage('')}>
              {infoMessage}
            </Alert>
          )}

          {/* Error Message Display - BMS ERRMSG field POS=(23,1) LENGTH=80 COLOR=RED */}
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

          {/* Card Details Display Section - BMS lines 11-15 */}
          {!loading && cardData && (
            <Card variant="outlined">
              <CardContent>
                <Typography variant="h6" gutterBottom color="primary">
                  Card Details
                </Typography>
                
                <Grid container spacing={2}>
                  {/* Cardholder Name - BMS CRDNAME field POS=(11,25) LENGTH=50 */}
                  <Grid item xs={12}>
                    <Box sx={{ mb: 2 }}>
                      <Typography variant="body2" color="text.secondary" gutterBottom>
                        Name on card:
                      </Typography>
                      <Typography 
                        variant="body1" 
                        sx={{ 
                          fontWeight: 'medium',
                          fontFamily: 'monospace',
                          pl: 2,
                          borderLeft: '3px solid #1976d2'
                        }}
                      >
                        {cardData.cardholderName || 'N/A'}
                      </Typography>
                    </Box>
                  </Grid>

                  {/* Card Number (Masked) - Derived from CARD-NUM with security masking */}
                  <Grid item xs={12} md={6}>
                    <Box sx={{ mb: 2 }}>
                      <Typography variant="body2" color="text.secondary" gutterBottom>
                        Card Number:
                      </Typography>
                      <Typography 
                        variant="body1" 
                        sx={{ 
                          fontWeight: 'medium',
                          fontFamily: 'monospace',
                          pl: 2,
                          borderLeft: '3px solid #1976d2'
                        }}
                      >
                        {cardData.cardNumber ? maskCardNumber(cardData.cardNumber) : 'N/A'}
                      </Typography>
                    </Box>
                  </Grid>

                  {/* Account ID - Derived from CARD-ACCT-ID */}
                  <Grid item xs={12} md={6}>
                    <Box sx={{ mb: 2 }}>
                      <Typography variant="body2" color="text.secondary" gutterBottom>
                        Account Number:
                      </Typography>
                      <Typography 
                        variant="body1" 
                        sx={{ 
                          fontWeight: 'medium',
                          fontFamily: 'monospace',
                          pl: 2,
                          borderLeft: '3px solid #1976d2'
                        }}
                      >
                        {cardData.accountId || 'N/A'}
                      </Typography>
                    </Box>
                  </Grid>

                  {/* Card Active Status - BMS CRDSTCD field POS=(13,25) LENGTH=1 */}
                  <Grid item xs={12} md={6}>
                    <Box sx={{ mb: 2 }}>
                      <Typography variant="body2" color="text.secondary" gutterBottom>
                        Card Active Y/N:
                      </Typography>
                      <Typography 
                        variant="body1" 
                        sx={{ 
                          fontWeight: 'medium',
                          fontFamily: 'monospace',
                          pl: 2,
                          borderLeft: '3px solid #1976d2',
                          color: cardData.activeStatus === CARD_STATUS.ACTIVE ? 'success.main' : 'error.main'
                        }}
                      >
                        {cardData.activeStatus === CARD_STATUS.ACTIVE ? 'Y (Active)' : 'N (Inactive)'}
                      </Typography>
                    </Box>
                  </Grid>

                  {/* Expiration Date - BMS EXPMON/EXPYEAR fields POS=(15,25) MM/YYYY */}
                  <Grid item xs={12} md={6}>
                    <Box sx={{ mb: 2 }}>
                      <Typography variant="body2" color="text.secondary" gutterBottom>
                        Expiry Date:
                      </Typography>
                      <Typography 
                        variant="body1" 
                        sx={{ 
                          fontWeight: 'medium',
                          fontFamily: 'monospace',
                          pl: 2,
                          borderLeft: '3px solid #1976d2'
                        }}
                      >
                        {formatExpirationDate(cardData.expirationDate) || 'N/A'}
                      </Typography>
                    </Box>
                  </Grid>

                  {/* Card Type - Additional information */}
                  {cardData.cardType && (
                    <Grid item xs={12} md={6}>
                      <Box sx={{ mb: 2 }}>
                        <Typography variant="body2" color="text.secondary" gutterBottom>
                          Card Type:
                        </Typography>
                        <Typography 
                          variant="body1" 
                          sx={{ 
                            fontWeight: 'medium',
                            fontFamily: 'monospace',
                            pl: 2,
                            borderLeft: '3px solid #1976d2'
                          }}
                        >
                          {cardData.cardType}
                        </Typography>
                      </Box>
                    </Grid>
                  )}

                  {/* Card Status - Additional information */}
                  {cardData.status && (
                    <Grid item xs={12} md={6}>
                      <Box sx={{ mb: 2 }}>
                        <Typography variant="body2" color="text.secondary" gutterBottom>
                          Status:
                        </Typography>
                        <Typography 
                          variant="body1" 
                          sx={{ 
                            fontWeight: 'medium',
                            fontFamily: 'monospace',
                            pl: 2,
                            borderLeft: '3px solid #1976d2'
                          }}
                        >
                          {cardData.status}
                        </Typography>
                      </Box>
                    </Grid>
                  )}
                </Grid>
              </CardContent>
            </Card>
          )}

          {/* Empty State - No card data */}
          {!loading && !cardData && !errorMessage && (
            <Box sx={{ textAlign: 'center', py: 4 }}>
              <Typography variant="body1" color="text.secondary">
                Enter an Account Number or Card Number to search for card details
              </Typography>
            </Box>
          )}

          {/* Exit Button - F3 key functionality */}
          <Box sx={{ mt: 3, display: 'flex', justifyContent: 'flex-end' }}>
            <Button
              variant="outlined"
              color="secondary"
              startIcon={<ExitToAppIcon />}
              onClick={handleExit}
              sx={{ minWidth: 150 }}
            >
              Exit (F3)
            </Button>
          </Box>
        </Paper>
      </Box>

      {/* Footer Component - BMS screen line 24 */}
      <Footer
        helpText="ENTER=Search Cards  F3=Exit"
        showEnter={true}
        showF3={true}
        onSubmit={handleSearch}
        onBack={handleExit}
      />
    </Box>
  );
};

// Export component as default
export default CardDetailComponent;
