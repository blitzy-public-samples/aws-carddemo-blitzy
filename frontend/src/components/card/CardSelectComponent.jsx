/**
 * CardDemo Card Select Component
 * 
 * React functional component implementing card detail view and selection functionality.
 * Transforms BMS mapset COCRDSL and COBOL program COCRDSLC.cbl to modern React UI.
 * 
 * COBOL Program Mapping:
 * - Source: app/cbl/COCRDSLC.cbl (Card Selection/Detail View)
 * - BMS Map: app/bms/COCRDSL.bms (Card Selection Screen)
 * - Transaction: CCDL
 * 
 * Business Logic Preservation:
 * - Account number validation: 11 digits, non-zero (COCRDSLC lines 144-147)
 * - Card number validation: 16 digits required (COCRDSLC lines 148-149)
 * - Search by account ID and/or card number
 * - Card detail display with read-only fields (BMS PROT attributes)
 * - Error message display matching COBOL WS-RETURN-MSG patterns
 * - Info message display matching COBOL WS-INFO-MSG patterns
 * - F3=Exit navigation to card list
 * - Optional navigation to card update screen
 * 
 * Screen Layout Transformation:
 * - BMS ACCTSID field → Account Number TextField (11 digits)
 * - BMS CARDSID field → Card Number TextField (16 digits, required)
 * - BMS CRDNAME field → Name on card display (50 chars)
 * - BMS CRDSTCD field → Card Active Y/N display (1 char, 'A' = Y, else N)
 * - BMS EXPMON/EXPYEAR fields → Expiry Date display (MM/YYYY format)
 * - BMS INFOMSG field → Info Alert component
 * - BMS ERRMSG field → Error Alert component
 * - BMS FKEYS field → Navigation buttons (F3=Exit)
 * 
 * @module components/card/CardSelectComponent
 */

import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Box,
  Paper,
  TextField,
  Button,
  Typography,
  Grid,
  Alert,
  CircularProgress,
  Divider
} from '@mui/material';
import { ArrowBack, Search } from '@mui/icons-material';
import cardService from '../../services/cardService';

/**
 * CardSelectComponent - Card Detail View and Selection
 * 
 * Displays detailed card information and provides search capability for both
 * account ID and card number. Implements validation matching COBOL field
 * validation rules from COCRDSLC.cbl.
 * 
 * Component State:
 * - loading: Boolean indicating async data fetch in progress
 * - error: String containing error message to display
 * - infoMessage: String containing informational message
 * - accountSearch: String containing account number search input (11 digits)
 * - cardSearch: String containing card number search input (16 digits)
 * - cardData: Object containing retrieved card details or null
 * 
 * Route Parameters:
 * - cardNumber: Optional card number from URL route (/cards/:cardNumber)
 * 
 * Navigation:
 * - Can be accessed directly with card number in URL
 * - Can be accessed from card list with pre-populated search criteria
 * - F3 Exit button returns to card list
 * - Update Card button navigates to card edit screen
 * 
 * @returns {JSX.Element} Card selection and detail view component
 */
const CardSelectComponent = () => {
  // Extract card number from route parameters if provided
  const { cardNumber: routeCardNumber } = useParams();
  const navigate = useNavigate();

  // Component state management
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [infoMessage, setInfoMessage] = useState('');
  const [accountSearch, setAccountSearch] = useState('');
  const [cardSearch, setCardSearch] = useState(routeCardNumber || '');
  const [cardData, setCardData] = useState(null);

  /**
   * Validate Account Number
   * 
   * Maps COBOL validation logic from COCRDSLC.cbl lines 647-683
   * (2210-EDIT-ACCOUNT paragraph)
   * 
   * Validation Rules:
   * - Must not be empty/spaces/zeros
   * - Must be exactly 11 digits
   * - Must be numeric
   * - Must be non-zero value
   * 
   * COBOL Equivalence:
   * - Lines 651-653: Check if account ID is LOW-VALUES, SPACES, or ZEROS
   * - Lines 654-660: Set FLG-ACCTFILTER-BLANK and WS-PROMPT-FOR-ACCT error
   * - Lines 665-674: Check if account ID is NOT NUMERIC, set error message
   * - Line 670: Error message "ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER"
   * 
   * @param {string} value - Account number to validate
   * @returns {string|null} Error message if invalid, null if valid
   */
  const validateAccountNumber = (value) => {
    // Map COBOL lines 651-653: Not supplied or empty
    if (!value || value.trim() === '') {
      // Map COBOL line 657: WS-PROMPT-FOR-ACCT message
      return 'Account number not provided';
    }

    // Map COBOL line 665: NOT NUMERIC check
    if (!/^\d{11}$/.test(value)) {
      // Map COBOL lines 669-671: Error message for non-numeric or wrong length
      return 'Account number must be a non zero 11 digit number';
    }

    // Map COBOL line 653: Check for all zeros
    if (value === '00000000000') {
      // Map COBOL lines 144-145: SEARCHED-ACCT-ZEROES message
      return 'Account number must be a non zero 11 digit number';
    }

    // Account number is valid
    return null;
  };

  /**
   * Validate Card Number
   * 
   * Maps COBOL validation logic from COCRDSLC.cbl lines 685-724
   * (2220-EDIT-CARD paragraph)
   * 
   * Validation Rules:
   * - Must not be empty/spaces/zeros
   * - Must be exactly 16 digits
   * - Must be numeric
   * 
   * COBOL Equivalence:
   * - Lines 691-693: Check if card number is LOW-VALUES, SPACES, or ZEROS
   * - Lines 694-701: Set FLG-CARDFILTER-BLANK and WS-PROMPT-FOR-CARD error
   * - Lines 706-715: Check if card number is NOT NUMERIC, set error message
   * - Line 711: Error message "CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER"
   * 
   * @param {string} value - Card number to validate
   * @returns {string|null} Error message if invalid, null if valid
   */
  const validateCardNumber = (value) => {
    // Map COBOL lines 691-693: Not supplied or empty
    if (!value || value.trim() === '') {
      // Map COBOL line 697: WS-PROMPT-FOR-CARD message
      return 'Card number not provided';
    }

    // Map COBOL line 706: NOT NUMERIC check
    if (!/^\d{16}$/.test(value)) {
      // Map COBOL lines 710-712: Error message for non-numeric or wrong length
      return 'Card number if supplied must be a 16 digit number';
    }

    // Card number is valid
    return null;
  };

  /**
   * Fetch Card Details
   * 
   * Maps COBOL paragraph 9000-READ-DATA and 9100-GETCARD-BYACCTCARD
   * (COCRDSLC.cbl lines 726-777)
   * 
   * COBOL Equivalence:
   * - Line 742: EXEC CICS READ FILE('CARDDAT') RIDFLD(WS-CARD-RID-CARDNUM)
   * - Lines 753-754: DFHRESP(NORMAL) → Set FOUND-CARDS-FOR-ACCOUNT
   * - Lines 755-761: DFHRESP(NOTFND) → Set DID-NOT-FIND-ACCTCARD-COMBO error
   * - Lines 762-772: OTHER → Set XREF-READ-ERROR
   * 
   * REST API Mapping:
   * - CICS READ CARDDAT → GET /api/cards/{cardNumber}
   * - RESP(NORMAL) → HTTP 200 OK
   * - RESP(NOTFND) → HTTP 404 Not Found
   * - RESP(OTHER) → HTTP 500 Server Error
   * 
   * @param {string} cardNum - Card number to retrieve (16 digits)
   */
  const fetchCardDetails = async (cardNum) => {
    setLoading(true);
    setError('');
    setInfoMessage('');

    try {
      // Call cardService.getCard() which maps COBOL CICS READ operation
      const card = await cardService.getCard(cardNum);
      
      // Map COBOL line 754: Set FOUND-CARDS-FOR-ACCOUNT
      setCardData(card);
      
      // Map COBOL lines 129-130: FOUND-CARDS-FOR-ACCOUNT info message
      setInfoMessage('   Displaying requested details');
    } catch (err) {
      // Map COBOL lines 760: DID-NOT-FIND-ACCTCARD-COMBO or other errors
      setError(err.message || 'Card not found');
      setCardData(null);
    } finally {
      setLoading(false);
    }
  };

  /**
   * Effect hook to load card details on component mount or route parameter change
   * 
   * Maps COBOL logic for when program is entered from card list with pre-selected card
   * (COCRDSLC.cbl lines 339-348: CDEMO-PGM-ENTER and CDEMO-FROM-PROGRAM checks)
   */
  useEffect(() => {
    if (routeCardNumber) {
      // Map COBOL lines 341-348: Coming from card list, selection already validated
      fetchCardDetails(routeCardNumber);
    } else {
      // Map COBOL lines 131-132: WS-PROMPT-FOR-INPUT message
      setInfoMessage('Please enter Account and Card Number');
    }
  }, [routeCardNumber]);

  /**
   * Handle Search Button Click
   * 
   * Maps COBOL paragraph 2000-PROCESS-INPUTS and 2200-EDIT-MAP-INPUTS
   * (COCRDSLC.cbl lines 582-644)
   * 
   * COBOL Equivalence:
   * - Lines 610-612: Set INPUT-OK, FLG-CARDFILTER-ISVALID, FLG-ACCTFILTER-ISVALID
   * - Lines 630-634: PERFORM 2210-EDIT-ACCOUNT (validate account)
   * - Lines 633-634: PERFORM 2220-EDIT-CARD (validate card)
   * - Lines 637-640: Cross field validation - both fields blank error
   * - Line 639: NO-SEARCH-CRITERIA-RECEIVED error message
   * 
   * Validation Flow:
   * 1. Validate account number (if provided)
   * 2. Validate card number (required)
   * 3. Check cross-field validation (at least card number required)
   * 4. If valid, execute search
   */
  const handleSearch = () => {
    // Clear previous messages
    setError('');
    setInfoMessage('');

    // Trim inputs before validation for better user experience
    const trimmedAccountSearch = accountSearch.trim();
    const trimmedCardSearch = cardSearch.trim();

    // Map COBOL lines 630-631: Validate account number (optional)
    const accountError = trimmedAccountSearch ? validateAccountNumber(trimmedAccountSearch) : null;
    
    // Map COBOL lines 633-634: Validate card number (required)
    const cardError = validateCardNumber(trimmedCardSearch);

    // Display account validation error if present
    if (accountError) {
      setError(accountError);
      return;
    }

    // Display card validation error if present
    if (cardError) {
      setError(cardError);
      return;
    }

    // Map COBOL lines 365-369: Perform data read after successful validation
    fetchCardDetails(trimmedCardSearch);
  };

  /**
   * Handle Enter Key Press in Input Fields
   * 
   * Maps COBOL ENTER key processing (AID key handling)
   * (COCRDSLC.cbl lines 292-299: CCARD-AID-ENTER check)
   * 
   * Allows user to press Enter key in any input field to trigger search,
   * matching mainframe user experience where ENTER key submits the screen.
   * 
   * @param {KeyboardEvent} e - Keyboard event from input field
   */
  const handleKeyPress = (e) => {
    if (e.key === 'Enter') {
      // Map COBOL line 292: CCARD-AID-ENTER triggers processing
      handleSearch();
    }
  };

  /**
   * Format Expiration Date
   * 
   * Maps COBOL date field formatting from COCRDSLC.cbl
   * (Lines 477-482: CARD-EXPIRAION-DATE field handling)
   * 
   * COBOL Date Structure:
   * - CARD-EXPIRAION-DATE-X PIC X(10) (line 84)
   * - CARD-EXPIRY-YEAR PIC X(4) (line 86)
   * - CARD-EXPIRY-MONTH PIC X(2) (line 88)
   * - CARD-EXPIRY-DAY PIC X(2) (line 90)
   * 
   * BMS Display Format:
   * - EXPMON field: 2 digits (lines 480)
   * - EXPYEAR field: 4 digits (lines 482)
   * - Display format: MM/YYYY
   * 
   * @param {string} expiryDate - Expiry date in YYYY-MM-DD format from API
   * @returns {string} Formatted date in MM/YYYY format
   */
  const formatExpirationDate = (expiryDate) => {
    if (!expiryDate) return '';

    try {
      // Parse API date format (YYYY-MM-DD)
      const date = new Date(expiryDate);
      
      // Extract month and year components
      const month = String(date.getMonth() + 1).padStart(2, '0');
      const year = date.getFullYear();

      // Map COBOL display format: EXPMON/EXPYEAR → MM/YYYY
      return `${month}/${year}`;
    } catch (err) {
      // Handle invalid date format gracefully
      console.error('Error formatting expiration date:', err);
      return expiryDate; // Return original if formatting fails
    }
  };

  /**
   * Format Card Status Display
   * 
   * Maps COBOL card status field to Y/N display
   * (COCRDSLC.cbl line 484: CARD-ACTIVE-STATUS to CRDSTCDO)
   * 
   * COBOL Status Values:
   * - 'A' = Active (display as 'Y')
   * - 'E' = Expired (display as 'N')
   * - 'B' = Blocked (display as 'N')
   * 
   * BMS Field: CRDSTCD LENGTH=1 (Y/N display)
   * 
   * @param {string} status - Card status code from API ('A', 'E', 'B')
   * @returns {string} Display value ('Y' or 'N')
   */
  const formatCardStatus = (status) => {
    // Map COBOL logic: 'A' means active (Y), anything else means not active (N)
    return status === 'A' ? 'Y' : 'N';
  };

  /**
   * Handle Exit Button Click
   * 
   * Maps COBOL F3 key handling (PFK03 processing)
   * (COCRDSLC.cbl lines 305-334: CCARD-AID-PFK03 evaluation)
   * 
   * COBOL Equivalence:
   * - Line 305: WHEN CCARD-AID-PFK03
   * - Lines 309-321: Determine return program (CDEMO-FROM-PROGRAM or LIT-MENUPGM)
   * - Lines 331-334: EXEC CICS XCTL to calling program
   * 
   * React Navigation:
   * - Returns to card list (/cards route)
   * - Maintains navigation history for browser back button
   */
  const handleExit = () => {
    // Map COBOL XCTL to card list program (COCRDLIC)
    navigate('/cards');
  };

  /**
   * Handle Update Card Button Click
   * 
   * Navigates to card update screen with current card number.
   * Maps navigation from card detail view to card update program (COCRDUPC).
   * 
   * @param {string} cardNum - Card number to update
   */
  const handleUpdateCard = (cardNum) => {
    // Navigate to card update component (maps COCRDUPC.cbl)
    navigate(`/cards/${cardNum}/edit`);
  };

  /**
   * Component Render
   * 
   * Maps BMS screen layout from COCRDSL.bms to Material-UI components.
   * Preserves screen structure, field positioning, and user interaction patterns.
   * 
   * BMS to React Component Mapping:
   * - TITLE01/TITLE02 → Typography variant="h4" (screen title)
   * - ACCTSID field → TextField for account number input
   * - CARDSID field → TextField for card number input (required)
   * - CRDNAME field → Typography display (read-only, PROT attribute)
   * - CRDSTCD field → Typography display (read-only, Y/N format)
   * - EXPMON/EXPYEAR → Typography display (read-only, MM/YYYY format)
   * - INFOMSG field → Alert severity="info"
   * - ERRMSG field → Alert severity="error"
   * - FKEYS field → Button components with icons
   * 
   * Screen Flow:
   * 1. Display search inputs (account and card number)
   * 2. User enters search criteria and clicks Search or presses Enter
   * 3. Validate inputs per COBOL rules
   * 4. Fetch and display card details if validation passes
   * 5. Show error message if validation fails or card not found
   * 6. Provide Exit button to return to card list
   * 7. Provide Update button to navigate to card edit screen
   */
  return (
    <Box sx={{ p: 3 }}>
      {/* Screen Title - Maps BMS TITLE02 field */}
      <Typography variant="h4" gutterBottom>
        View Credit Card Detail
      </Typography>

      {/* Search Section - Maps BMS ACCTSID and CARDSID input fields */}
      <Paper sx={{ p: 2, mb: 3 }}>
        <Grid container spacing={2}>
          {/* Account Number Input - Maps BMS ACCTSID field (LENGTH=11) */}
          <Grid item xs={12} md={6}>
            <TextField
              label="Account Number"
              value={accountSearch}
              onChange={(e) => setAccountSearch(e.target.value)}
              onKeyPress={handleKeyPress}
              placeholder="Enter 11-digit account number"
              inputProps={{ 
                maxLength: 11,
                'aria-label': 'Account Number'
              }}
              fullWidth
              autoFocus={!routeCardNumber}
              helperText="Optional: 11 digits, non-zero"
            />
          </Grid>

          {/* Card Number Input - Maps BMS CARDSID field (LENGTH=16, required) */}
          <Grid item xs={12} md={6}>
            <TextField
              label="Card Number"
              value={cardSearch}
              onChange={(e) => setCardSearch(e.target.value)}
              onKeyPress={handleKeyPress}
              placeholder="Enter 16-digit card number"
              inputProps={{ 
                maxLength: 16,
                'aria-label': 'Card Number'
              }}
              fullWidth
              required
              helperText="Required: 16 digits"
            />
          </Grid>

          {/* Search Button - Maps ENTER key functionality */}
          <Grid item xs={12}>
            <Button
              variant="contained"
              startIcon={<Search />}
              onClick={handleSearch}
              disabled={loading}
            >
              Search Cards
            </Button>
          </Grid>
        </Grid>
      </Paper>

      {/* Info Message - Maps BMS INFOMSG field (LENGTH=40) */}
      {infoMessage && !error && (
        <Alert severity="info" sx={{ mb: 2 }}>
          {infoMessage}
        </Alert>
      )}

      {/* Error Message - Maps BMS ERRMSG field (LENGTH=80) */}
      {error && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {/* Loading Indicator */}
      {loading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', p: 3 }}>
          <CircularProgress />
        </Box>
      )}

      {/* Card Details Display - Maps BMS CRDNAME, CRDSTCD, EXPMON, EXPYEAR fields */}
      {cardData && !loading && (
        <Paper sx={{ p: 3 }}>
          <Grid container spacing={3}>
            {/* Name on Card - Maps BMS CRDNAME field (LENGTH=50, PROT) */}
            <Grid item xs={12}>
              <Typography variant="subtitle2" color="textSecondary">
                Name on card:
              </Typography>
              <Typography variant="h6">
                {cardData.embossedName || cardData.cardholderName || 'N/A'}
              </Typography>
            </Grid>

            <Grid item xs={12}>
              <Divider />
            </Grid>

            {/* Card Active Status - Maps BMS CRDSTCD field (LENGTH=1, Y/N) */}
            <Grid item xs={12} md={6}>
              <Typography variant="subtitle2" color="textSecondary">
                Card Active Y/N:
              </Typography>
              <Typography variant="body1">
                {formatCardStatus(cardData.cardStatus)}
              </Typography>
            </Grid>

            {/* Expiry Date - Maps BMS EXPMON and EXPYEAR fields (MM/YYYY format) */}
            <Grid item xs={12} md={6}>
              <Typography variant="subtitle2" color="textSecondary">
                Expiry Date:
              </Typography>
              <Typography variant="body1">
                {formatExpirationDate(cardData.expiryDate)}
              </Typography>
            </Grid>

            <Grid item xs={12}>
              <Divider />
            </Grid>

            {/* Account Number Display - Reference information */}
            <Grid item xs={12} md={6}>
              <Typography variant="subtitle2" color="textSecondary">
                Account Number:
              </Typography>
              <Typography variant="body1">
                {cardData.accountId}
              </Typography>
            </Grid>

            {/* Card Number Display - Reference information */}
            <Grid item xs={12} md={6}>
              <Typography variant="subtitle2" color="textSecondary">
                Card Number:
              </Typography>
              <Typography variant="body1">
                {cardData.cardNumber}
              </Typography>
            </Grid>
          </Grid>
        </Paper>
      )}

      {/* Navigation Buttons - Maps BMS FKEYS field (F3=Exit) */}
      <Box sx={{ mt: 3, display: 'flex', gap: 2 }}>
        {/* Exit Button - Maps PF3 key */}
        <Button
          startIcon={<ArrowBack />}
          onClick={handleExit}
        >
          F3: Exit
        </Button>

        {/* Update Card Button - Navigation to COCRDUPC program */}
        {cardData && (
          <Button
            variant="outlined"
            onClick={() => handleUpdateCard(cardData.cardNumber)}
          >
            Update Card
          </Button>
        )}
      </Box>
    </Box>
  );
};

// Default export as specified in schema
export default CardSelectComponent;
