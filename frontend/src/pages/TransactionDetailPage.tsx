/**
 * Transaction Detail Page Component
 * 
 * Converted from COBOL program: COTRN01C.cbl
 * Original BMS map: COTRN01.bms (COTRN1A 24x80 3270 screen)
 * Original function: Transaction detail view (read-only display)
 * 
 * COBOL flow:
 * 1. RECEIVE-TRNID-SCREEN: Receive transaction ID from user input
 * 2. VALIDATE-TRNID-INPUT: Validate transaction ID is not empty (16 chars)
 * 3. READ-TRANSACT-FILE: EXEC CICS READ DATASET('TRANSACT') RIDFLD(TRAN-ID)
 * 4. POPULATE-TRAN-DATA: Move all TRAN-RECORD fields to screen output
 * 5. SEND-TRAN-SCREEN: EXEC CICS SEND MAP('COTRN1A') MAPSET('COTRN01')
 * 
 * React implementation:
 * - Fetch transaction data via REST API GET /api/transactions/:id
 * - Display all fields from CVTRA05Y.cpy (TRAN-RECORD) in read-only format
 * - Material-UI Card layout matching BMS screen positions
 * - Format amounts with formatCurrency() (COBOL COMP-3 precision)
 * - Format timestamps with formatDateTime() (COBOL WS-TIMESTAMP format)
 * - Navigate back to transaction list or previous page
 * 
 * Per Agent Action Plan Section 0.4.18: Convert COTRN01.bms BMS map definition
 * to React component with read-only display fields matching CVTRA05Y.cpy structure.
 * Maintain identical screen layout from COTRN01C.cbl COBOL program logic.
 * 
 * Per Section 0.7.2: Preserve all existing functionality and data precision.
 * TRAN-AMT (PIC S9(09)V99) displayed with 2 decimal places using formatCurrency().
 */

import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Box,
  Card,
  CardContent,
  Container,
  Typography,
  Button,
  Grid,
  Divider,
  Paper,
} from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';

// Internal imports from depends_on_files
import transactionService from '../services/transactionService';
import { Transaction } from '../types/transaction';
import Header from '../components/common/Header';
import Footer from '../components/common/Footer';
import LoadingSpinner from '../components/common/LoadingSpinner';
import ErrorMessage from '../components/common/ErrorMessage';
import { formatCurrency } from '../utils/currencyFormatter';
import { formatDateTime } from '../utils/dateFormatter';

/**
 * TransactionDetailPage Component
 * 
 * Displays read-only detailed view of a single transaction matching
 * COTRN01.bms BMS screen layout (24 lines x 80 columns).
 * 
 * BMS Screen Fields (COTRN1A):
 * - Line 6: TRNIDIN (input field for transaction ID - 16 chars)
 * - Line 10: TRNID (Transaction ID display), CARDNUM (Card Number display)
 * - Line 12: TTYPCD (Type CD - 2 chars), TCATCD (Category CD - 4 digits), TRNSRC (Source - 10 chars)
 * - Line 14: TDESC (Description - 60 chars display)
 * - Line 16: TRNAMT (Amount - 12 chars with 2 decimals), TORIGDT (Original Date), TPROCDT (Processing Date)
 * - Line 18: MID (Merchant ID - 9 chars), MNAME (Merchant Name - 30 chars)
 * - Line 20: MCITY (Merchant City - 25 chars), MZIP (Merchant ZIP - 10 chars)
 * - Line 23: ERRMSG (Error message display - 78 chars)
 * 
 * CVTRA05Y.cpy TRAN-RECORD fields (all ASKIP attributes - read-only):
 * - TRAN-ID: PIC X(16) - Transaction unique identifier
 * - TRAN-CARD-NUM: PIC X(16) - Card number used
 * - TRAN-TYPE-CD: PIC X(02) - Transaction type code
 * - TRAN-CAT-CD: PIC 9(04) - Transaction category code
 * - TRAN-SOURCE: PIC X(10) - Transaction source system
 * - TRAN-DESC: PIC X(100) - Transaction description
 * - TRAN-AMT: PIC S9(09)V99 - Transaction amount (COMP-3 precision)
 * - TRAN-MERCHANT-ID: PIC 9(09) - Merchant identifier
 * - TRAN-MERCHANT-NAME: PIC X(50) - Merchant name
 * - TRAN-MERCHANT-CITY: PIC X(50) - Merchant city
 * - TRAN-MERCHANT-ZIP: PIC X(10) - Merchant ZIP code
 * - TRAN-ORIG-TS: PIC X(26) - Original transaction timestamp
 * - TRAN-PROC-TS: PIC X(26) - Processing timestamp
 * 
 * Navigation:
 * - Back button returns to transaction list page (/transactions)
 * - F3=Back key function emulated with button click
 * 
 * Error Handling:
 * - Invalid transaction ID: Display error message (COBOL: WS-MESSAGE)
 * - Transaction not found (DFHRESP(NOTFND)): 404 error from backend
 * - System errors: Display generic error message
 * 
 * @component
 * @example
 * ```tsx
 * // Route: /transactions/:id
 * // URL: /transactions/0000000000000123
 * <TransactionDetailPage />
 * ```
 */
const TransactionDetailPage: React.FC = () => {
  // URL parameter extraction (transaction ID from route)
  // COBOL equivalent: TRNIDINI OF COTRN1AI (input field)
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  // Component state management
  // COBOL equivalent: TRAN-RECORD from CVTRA05Y.cpy
  const [transaction, setTransaction] = useState<Transaction | null>(null);
  
  // Loading state during API call
  // COBOL equivalent: WS-RESP-CD check after EXEC CICS READ
  const [loading, setLoading] = useState<boolean>(true);
  
  // Error state for display
  // COBOL equivalent: WS-MESSAGE and ERR-FLG from COBOL
  const [error, setError] = useState<string>('');

  /**
   * Fetch transaction data on component mount or ID change
   * 
   * COBOL equivalent:
   * - READ-TRANSACT-FILE paragraph in COTRN01C.cbl
   * - EXEC CICS READ DATASET('TRANSACT') RIDFLD(TRAN-ID) INTO(TRAN-RECORD)
   * - Check DFHRESP(NORMAL), DFHRESP(NOTFND), or errors
   * 
   * Per Section 0.4.23: Replace COBOL EXEC CICS READ with REST API call
   */
  useEffect(() => {
    const fetchTransaction = async () => {
      // Reset state
      setLoading(true);
      setError('');
      setTransaction(null);

      // Validation: Transaction ID must be provided
      // COBOL equivalent: "WHEN TRNIDINI OF COTRN1AI = SPACES OR LOW-VALUES"
      if (!id || id.trim() === '') {
        setError('Tran ID can NOT be empty...');
        setLoading(false);
        return;
      }

      // Validation: Transaction ID should be 16 characters
      // COBOL: TRAN-ID PIC X(16) validation
      if (id.length !== 16) {
        setError('Tran ID must be 16 characters...');
        setLoading(false);
        return;
      }

      try {
        // API call to fetch transaction details
        // Backend: TransactionController.getTransactionById()
        // COBOL: EXEC CICS READ DATASET('TRANSACT')
        const data = await transactionService.getTransactionById(id);
        
        // COBOL equivalent: DFHRESP(NORMAL) - successful read
        setTransaction(data);
      } catch (err: any) {
        // COBOL error handling:
        // - DFHRESP(NOTFND): "Transaction ID NOT found..."
        // - Other errors: "Unable to lookup Transaction..."
        
        if (err.status === 404) {
          setError(`Transaction ID NOT found: ${id}`);
        } else if (err.status === 400) {
          setError(err.message || 'Invalid transaction ID format...');
        } else {
          setError(err.message || 'Unable to lookup Transaction...');
        }
      } finally {
        setLoading(false);
      }
    };

    fetchTransaction();
  }, [id]);

  /**
   * Handle back button navigation
   * 
   * COBOL equivalent: F3=Back key handling in COTRN01C.cbl
   * - WHEN EIBAID = DFHPF3
   * - EXEC CICS RETURN TRANSID('CT00') (returns to transaction list)
   * 
   * Per Section 0.4.23: Replace COBOL EXEC CICS RETURN with React Router navigation
   */
  const handleBackClick = () => {
    // Navigate to transaction list page
    // COBOL: EXEC CICS RETURN TRANSID('CT00')
    navigate('/transactions');
  };

  // Display loading spinner while fetching data
  // COBOL equivalent: Wait state during EXEC CICS READ operation
  if (loading) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
        <Header />
        <Container sx={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <LoadingSpinner message="Loading transaction details..." />
        </Container>
        <Footer />
      </Box>
    );
  }

  // Display error message if error occurred
  // COBOL equivalent: ERR-FLG-ON, display WS-MESSAGE in ERRMSGO field
  if (error) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
        <Header />
        <Container sx={{ flex: 1, py: 4 }}>
          <Box sx={{ mb: 3 }}>
            <Button
              variant="outlined"
              startIcon={<ArrowBackIcon />}
              onClick={handleBackClick}
              sx={{ mb: 2 }}
            >
              Back to Transaction List
            </Button>
          </Box>
          <ErrorMessage message={error} severity="error" />
        </Container>
        <Footer />
      </Box>
    );
  }

  // Display transaction not found if no data
  // COBOL equivalent: DFHRESP(NOTFND) condition
  if (!transaction) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
        <Header />
        <Container sx={{ flex: 1, py: 4 }}>
          <Box sx={{ mb: 3 }}>
            <Button
              variant="outlined"
              startIcon={<ArrowBackIcon />}
              onClick={handleBackClick}
              sx={{ mb: 2 }}
            >
              Back to Transaction List
            </Button>
          </Box>
          <ErrorMessage 
            message={`Transaction not found with ID: ${id}`} 
            severity="warning" 
          />
        </Container>
        <Footer />
      </Box>
    );
  }

  /**
   * Main transaction detail display
   * 
   * COBOL equivalent: SEND-TRAN-SCREEN paragraph
   * - POPULATE-TRAN-DATA: Move all fields to BMS output map
   * - EXEC CICS SEND MAP('COTRN1A') MAPSET('COTRN01')
   * 
   * Layout matches COTRN01.bms screen positions:
   * - Section 1: Transaction header (ID, Card Number)
   * - Section 2: Transaction details (Type, Category, Source, Description, Amount)
   * - Section 3: Merchant information (ID, Name, City, ZIP)
   * - Section 4: Timestamps (Original, Processing)
   */
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <Header />
      <Container sx={{ flex: 1, py: 4 }}>
        {/* Page Header with Title and Navigation */}
        {/* COBOL: BMS TITLE01 field "View Transaction" at POS=(4,30) */}
        <Box sx={{ mb: 3, display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <Typography variant="h4" component="h1" gutterBottom>
            View Transaction
          </Typography>
          <Button
            variant="outlined"
            startIcon={<ArrowBackIcon />}
            onClick={handleBackClick}
          >
            Back
          </Button>
        </Box>

        {/* Main Transaction Detail Card */}
        {/* COBOL: BMS map COTRN1A with all ASKIP (read-only) fields */}
        <Card elevation={3}>
          <CardContent>
            {/* Section 1: Transaction Identification */}
            {/* COBOL: Line 10 fields (TRNID, CARDNUM) */}
            <Paper elevation={1} sx={{ p: 2, mb: 3, backgroundColor: '#f5f5f5' }}>
              <Typography variant="h6" gutterBottom color="primary">
                Transaction Identification
              </Typography>
              <Grid container spacing={3}>
                <Grid item xs={12} sm={6}>
                  {/* COBOL: TRNID field (TRAN-ID PIC X(16)) at POS=(10,22) */}
                  <Typography variant="body2" color="text.secondary" gutterBottom>
                    Transaction ID:
                  </Typography>
                  <Typography variant="body1" fontWeight="medium">
                    {transaction.transId}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={6}>
                  {/* COBOL: CARDNUM field (TRAN-CARD-NUM PIC X(16)) at POS=(10,58) */}
                  <Typography variant="body2" color="text.secondary" gutterBottom>
                    Card Number:
                  </Typography>
                  <Typography variant="body1" fontWeight="medium">
                    {transaction.transCardNum}
                  </Typography>
                </Grid>
              </Grid>
            </Paper>

            <Divider sx={{ my: 3 }} />

            {/* Section 2: Transaction Details */}
            {/* COBOL: Lines 12-16 fields (TTYPCD, TCATCD, TRNSRC, TDESC, TRNAMT, TORIGDT, TPROCDT) */}
            <Paper elevation={1} sx={{ p: 2, mb: 3, backgroundColor: '#f5f5f5' }}>
              <Typography variant="h6" gutterBottom color="primary">
                Transaction Details
              </Typography>
              <Grid container spacing={3}>
                <Grid item xs={12} sm={4}>
                  {/* COBOL: TTYPCD field (TRAN-TYPE-CD PIC X(02)) at POS=(12,15) */}
                  <Typography variant="body2" color="text.secondary" gutterBottom>
                    Type CD:
                  </Typography>
                  <Typography variant="body1" fontWeight="medium">
                    {transaction.transTypeCd}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={4}>
                  {/* COBOL: TCATCD field (TRAN-CAT-CD PIC 9(04)) at POS=(12,36) */}
                  <Typography variant="body2" color="text.secondary" gutterBottom>
                    Category CD:
                  </Typography>
                  <Typography variant="body1" fontWeight="medium">
                    {transaction.transCatCd}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={4}>
                  {/* COBOL: TRNSRC field (TRAN-SOURCE PIC X(10)) at POS=(12,54) */}
                  <Typography variant="body2" color="text.secondary" gutterBottom>
                    Source:
                  </Typography>
                  <Typography variant="body1" fontWeight="medium">
                    {transaction.transSource}
                  </Typography>
                </Grid>
                <Grid item xs={12}>
                  {/* COBOL: TDESC field (TRAN-DESC PIC X(100)) at POS=(14,19) */}
                  <Typography variant="body2" color="text.secondary" gutterBottom>
                    Description:
                  </Typography>
                  <Typography variant="body1" fontWeight="medium">
                    {transaction.transDesc}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={4}>
                  {/* COBOL: TRNAMT field (TRAN-AMT PIC S9(09)V99 COMP-3) at POS=(16,14) */}
                  {/* Per Section 0.7.2: Format with 2 decimal places using formatCurrency() */}
                  <Typography variant="body2" color="text.secondary" gutterBottom>
                    Amount:
                  </Typography>
                  <Typography 
                    variant="h6" 
                    fontWeight="bold"
                    color={transaction.transAmt < 0 ? 'success.main' : 'error.main'}
                  >
                    {formatCurrency(transaction.transAmt)}
                  </Typography>
                </Grid>
              </Grid>
            </Paper>

            <Divider sx={{ my: 3 }} />

            {/* Section 3: Merchant Information */}
            {/* COBOL: Lines 18-20 fields (MID, MNAME, MCITY, MZIP) */}
            <Paper elevation={1} sx={{ p: 2, mb: 3, backgroundColor: '#f5f5f5' }}>
              <Typography variant="h6" gutterBottom color="primary">
                Merchant Information
              </Typography>
              <Grid container spacing={3}>
                <Grid item xs={12} sm={6}>
                  {/* COBOL: MID field (TRAN-MERCHANT-ID PIC 9(09)) at POS=(18,19) */}
                  <Typography variant="body2" color="text.secondary" gutterBottom>
                    Merchant ID:
                  </Typography>
                  <Typography variant="body1" fontWeight="medium">
                    {transaction.transMerchantId}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={6}>
                  {/* COBOL: MNAME field (TRAN-MERCHANT-NAME PIC X(50)) at POS=(18,48) */}
                  <Typography variant="body2" color="text.secondary" gutterBottom>
                    Merchant Name:
                  </Typography>
                  <Typography variant="body1" fontWeight="medium">
                    {transaction.transMerchantName}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={6}>
                  {/* COBOL: MCITY field (TRAN-MERCHANT-CITY PIC X(50)) at POS=(20,21) */}
                  <Typography variant="body2" color="text.secondary" gutterBottom>
                    Merchant City:
                  </Typography>
                  <Typography variant="body1" fontWeight="medium">
                    {transaction.transMerchantCity}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={6}>
                  {/* COBOL: MZIP field (TRAN-MERCHANT-ZIP PIC X(10)) at POS=(20,67) */}
                  <Typography variant="body2" color="text.secondary" gutterBottom>
                    Merchant Zip:
                  </Typography>
                  <Typography variant="body1" fontWeight="medium">
                    {transaction.transMerchantZip}
                  </Typography>
                </Grid>
              </Grid>
            </Paper>

            <Divider sx={{ my: 3 }} />

            {/* Section 4: Timestamps */}
            {/* COBOL: TORIGDT and TPROCDT fields at Line 16 */}
            <Paper elevation={1} sx={{ p: 2, backgroundColor: '#f5f5f5' }}>
              <Typography variant="h6" gutterBottom color="primary">
                Transaction Timestamps
              </Typography>
              <Grid container spacing={3}>
                <Grid item xs={12} sm={6}>
                  {/* COBOL: TORIGDT field (TRAN-ORIG-TS PIC X(26)) at POS=(16,42) */}
                  {/* Per Section 0.7.5: Format using formatDateTime() matching COBOL WS-TIMESTAMP */}
                  <Typography variant="body2" color="text.secondary" gutterBottom>
                    Original Date:
                  </Typography>
                  <Typography variant="body1" fontWeight="medium">
                    {formatDateTime(new Date(transaction.transOrigTs))}
                  </Typography>
                </Grid>
                <Grid item xs={12} sm={6}>
                  {/* COBOL: TPROCDT field (TRAN-PROC-TS PIC X(26)) at POS=(16,68) */}
                  <Typography variant="body2" color="text.secondary" gutterBottom>
                    Processing Date:
                  </Typography>
                  <Typography variant="body1" fontWeight="medium">
                    {formatDateTime(new Date(transaction.transProcTs))}
                  </Typography>
                </Grid>
              </Grid>
            </Paper>
          </CardContent>
        </Card>

        {/* Bottom Action Buttons */}
        {/* COBOL: BMS function key legend at Line 24 */}
        {/* "ENTER=Fetch  F3=Back  F4=Clear  F5=Browse Tran." */}
        <Box sx={{ mt: 3, display: 'flex', gap: 2 }}>
          <Button
            variant="contained"
            onClick={handleBackClick}
            startIcon={<ArrowBackIcon />}
          >
            Back to List
          </Button>
        </Box>
      </Container>
      <Footer />
    </Box>
  );
};

/**
 * Default export: TransactionDetailPage component
 * 
 * Per Agent Action Plan Section 0.4.18 exports schema:
 * - name: 'TransactionDetailPage'
 * - kind: 'component'
 * - is_default: true
 */
export default TransactionDetailPage