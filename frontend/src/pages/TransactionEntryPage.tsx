/**
 * TransactionEntryPage Component
 * 
 * Converted from COBOL program: COTRN02C.cbl
 * BMS Map: COTRN02.bms (Transaction Add screen)
 * COBOL Copybook: CVTRA05Y.cpy (Transaction record structure)
 * 
 * Purpose: Transaction entry and posting page for adding new credit card transactions
 * 
 * COBOL to React Migration:
 * - COTRN02.bms BMS map (24x80 3270 terminal screen) → React Material-UI components
 * - COTRN02C.cbl PROCEDURE DIVISION logic → React component with hooks
 * - EXEC CICS SEND MAP/RECEIVE MAP → REST API calls with JSON
 * - EXEC CICS WRITE FILE('TRANSACT') → POST /api/transactions
 * - BMS field validation (ATTRB=NUM, LENGTH) → Yup validation schema
 * - COMMAREA session management → React Context (useAuth)
 * - COBOL COMP-3 PIC S9(09)V99 → JavaScript number with 2 decimal precision
 * 
 * Key Features:
 * - Comprehensive transaction entry form with all fields from BMS map
 * - Real-time validation for card number (16 digits), transaction type/category codes
 * - Amount validation with 2 decimal precision matching COBOL COMP-3 format
 * - Merchant information fields (ID, name, city, ZIP)
 * - Date/time fields for original and processing timestamps
 * - Confirmation requirement before posting transaction
 * - Success/error message display with Material-UI alerts
 * - Navigation back to transaction list on success or cancel
 * 
 * BMS Field Mappings:
 * - ACTIDIN (11 digits) → Account ID input field
 * - CARDNIN (16 digits) → Card Number input field (mutually exclusive with ACTIDIN)
 * - TTYPCD (2 chars) → Transaction Type Code dropdown
 * - TCATCD (4 digits) → Transaction Category Code dropdown
 * - TRNSRC (10 chars) → Transaction Source input
 * - TDESC (100 chars) → Description textarea
 * - TRNAMT (12 chars) → Amount input with currency format
 * - TORIGDT (YYYY-MM-DD) → Original Date input
 * - TPROCDT (YYYY-MM-DD) → Process Date input (auto-populated)
 * - MID (9 digits) → Merchant ID input
 * - MNAME (50 chars) → Merchant Name input
 * - MCITY (50 chars) → Merchant City input
 * - MZIP (10 chars) → Merchant ZIP input
 * - CONFIRM (Y/N) → Confirmation checkbox
 * - ERRMSG (78 chars) → Error message display area
 * 
 * COBOL Program Flow:
 * 1. COTRN02C.cbl MAIN-PARA: Initialize screen, check authentication
 * 2. PROCESS-ENTER-KEY: Validate all input fields
 * 3. VALIDATE-INPUT-KEY-FIELDS: Account ID or Card Number validation
 * 4. VALIDATE-INPUT-DATA-FIELDS: Type, category, amount, merchant validation
 * 5. ADD-TRANSACTION: Generate transaction ID, populate record, write to file
 * 6. WRITE-TRANSACT-FILE: EXEC CICS WRITE FILE('TRANSACT')
 * 7. RETURN-TO-PREV-SCREEN: EXEC CICS RETURN TRANSID('CTRN')
 * 
 * React Component Flow:
 * 1. useEffect: Check authentication, initialize form with current date
 * 2. TransactionForm: Render form with Formik validation
 * 3. handleSubmit: Call transactionService.createTransaction()
 * 4. Show success alert and navigate back to transaction list
 * 5. handleCancel: Navigate back without saving
 * 
 * Performance Requirements:
 * - Transaction posting must complete within 200ms (sub-200ms SLA)
 * - Form validation must be real-time with no perceptible lag
 * - API error handling with proper user feedback
 * 
 * Security Requirements:
 * - Authentication check via useAuth hook
 * - JWT token included in API request headers
 * - User context logged for audit trail
 * 
 * @module pages/TransactionEntryPage
 */

import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Box, Container, Paper, Typography } from '@mui/material';
import transactionService from '../services/transactionService';
import { TransactionForm, TransactionFormData } from '../components/forms/TransactionForm';
import Header from '../components/common/Header';
import Footer from '../components/common/Footer';
import ErrorMessage from '../components/common/ErrorMessage';
import { useAuth } from '../hooks/useAuth';
import { formatCurrency } from '../utils/currencyFormatter';

/**
 * TransactionEntryPage Component
 * 
 * Main page component for transaction entry and posting functionality.
 * Converted from COBOL COTRN02C.cbl online transaction program.
 * 
 * @returns JSX.Element - Transaction entry page with form
 */
const TransactionEntryPage: React.FC = () => {
  // Navigation hook for programmatic routing
  // Replaces COBOL EXEC CICS RETURN TRANSID('CTRN') and EXEC CICS XCTL
  const navigate = useNavigate();

  // Authentication context
  // Replaces COBOL COMMAREA session management and RACF authentication checks
  // COBOL equivalent: EXEC CICS RETRIEVE INTO(CARDDEMO-COMMAREA)
  const { isAuthenticated, user, isLoading: authLoading } = useAuth();

  // State for success message display
  // COBOL equivalent: WS-MESSAGE field in WORKING-STORAGE
  const [successMessage, setSuccessMessage] = useState<string>('');

  // State for error message display
  // COBOL equivalent: WS-ERR-MSG field with ERR-FLG indicator
  const [errorMessage, setErrorMessage] = useState<string>('');

  /**
   * Authentication Check Effect
   * 
   * Verifies user is authenticated before allowing access to transaction entry.
   * COBOL equivalent: COTRN02C.cbl CHECK-USER-SESSION paragraph
   * 
   * If not authenticated, redirects to sign-on screen (COSGN00).
   */
  useEffect(() => {
    // Wait for authentication check to complete
    if (!authLoading && !isAuthenticated) {
      // User not authenticated, redirect to sign-on screen
      // COBOL equivalent: EXEC CICS XCTL PROGRAM('COSGN00C')
      navigate('/login', { replace: true });
    }
  }, [isAuthenticated, authLoading, navigate]);

  /**
   * Form Submission Handler
   * 
   * Handles transaction form submission and posting to backend API.
   * 
   * COBOL equivalent: COTRN02C.cbl ADD-TRANSACTION paragraph
   * Flow:
   * 1. Validate all input fields (VALIDATE-INPUT-DATA-FIELDS)
   * 2. Generate transaction ID (READPREV-TRANSACT-FILE, increment by 1)
   * 3. Initialize TRAN-RECORD structure from copybook CVTRA05Y.cpy
   * 4. Populate all fields from screen input (COTRN2AI)
   * 5. Write to VSAM TRANSACT file (EXEC CICS WRITE FILE('TRANSACT'))
   * 6. Display success message
   * 7. Return to transaction list screen (EXEC CICS RETURN TRANSID('CTRN'))
   * 
   * @param values - Validated form data from TransactionForm component
   */
  const handleSubmit = async (values: TransactionFormData): Promise<void> => {
    try {
      // Clear any previous messages
      setSuccessMessage('');
      setErrorMessage('');

      // Prepare transaction data for API request
      // Map form fields to Transaction DTO matching backend expectations
      // COBOL equivalent: MOVE statements populating TRAN-RECORD
      const transactionData = {
        // Card number from form (CARDNIN field)
        // COBOL: MOVE CARDNINI OF COTRN2AI TO TRAN-CARD-NUM
        transCardNum: values.cardNum || '',

        // Transaction type code (TTYPCD field, 2 chars)
        // COBOL: MOVE TTYPCDI OF COTRN2AI TO TRAN-TYPE-CD
        transTypeCd: values.transTypeCd,

        // Transaction category code (TCATCD field, 4 digits)
        // COBOL: MOVE TCATCDI OF COTRN2AI TO TRAN-CAT-CD
        transCatCd: values.transCatCd,

        // Transaction source (TRNSRC field, max 10 chars)
        // COBOL: MOVE TRNSRCI OF COTRN2AI TO TRAN-SOURCE
        transSource: values.transSource,

        // Transaction description (TDESC field, max 100 chars)
        // COBOL: MOVE TDESCI OF COTRN2AI TO TRAN-DESC
        transDesc: values.transDesc,

        // Transaction amount with 2 decimal precision
        // COBOL: MOVE TRNAMTI OF COTRN2AI TO TRAN-AMT (PIC S9(09)V99 COMP-3)
        // Maintains BigDecimal precision from COBOL packed decimal format
        transAmt: values.transAmt,

        // Merchant ID (MID field, 9 digits)
        // COBOL: MOVE MIDI OF COTRN2AI TO TRAN-MERCHANT-ID
        transMerchantId: values.transMerchantId || '',

        // Merchant name (MNAME field, max 50 chars)
        // COBOL: MOVE MNAMEI OF COTRN2AI TO TRAN-MERCHANT-NAME
        transMerchantName: values.transMerchantName || '',

        // Merchant city (MCITY field, max 50 chars)
        // COBOL: MOVE MCITYI OF COTRN2AI TO TRAN-MERCHANT-CITY
        transMerchantCity: values.transMerchantCity || '',

        // Merchant ZIP (MZIP field, max 10 chars)
        // COBOL: MOVE MZIPI OF COTRN2AI TO TRAN-MERCHANT-ZIP
        transMerchantZip: values.transMerchantZip || '',

        // Original transaction timestamp (TORIGDT field, YYYY-MM-DD)
        // COBOL: MOVE TORIGDTI OF COTRN2AI TO TRAN-ORIG-TS
        // Convert to ISO 8601 timestamp format for backend
        transOrigTs: values.transOrigTs.includes('T')
          ? values.transOrigTs
          : `${values.transOrigTs}T00:00:00Z`,
      };

      // Call backend API to create transaction
      // COBOL equivalent: EXEC CICS WRITE FILE('TRANSACT') FROM(TRAN-RECORD)
      // Spring Boot backend: TransactionController.createTransaction()
      // - Generates TRAN-ID (sequential ID generation)
      // - Sets TRAN-PROC-TS (processing timestamp)
      // - Validates card number against CARDFILE
      // - Validates transaction type/category against reference tables
      // - Posts transaction to TRANSACT VSAM file (now PostgreSQL table)
      const createdTransaction = await transactionService.createTransaction(transactionData);

      // Transaction posted successfully
      // COBOL equivalent: MOVE 'Transaction added successfully' TO WS-MESSAGE
      setSuccessMessage(
        `Transaction posted successfully! Transaction ID: ${createdTransaction.transId}. ` +
        `Amount: ${formatCurrency(createdTransaction.transAmt)}.`
      );

      // Log success for audit trail
      // COBOL equivalent: WRITE to audit log file
      console.log(
        'Transaction created:',
        createdTransaction.transId,
        'by user:',
        user?.userId || 'unknown'
      );

      // Navigate back to transaction list after 2 seconds
      // COBOL equivalent: EXEC CICS RETURN TRANSID('CTRN') COMMAREA(...)
      setTimeout(() => {
        navigate('/transactions');
      }, 2000);
    } catch (error: any) {
      // Transaction posting failed
      // COBOL equivalent: ERR-FLG-ON, MOVE error message to WS-ERR-MSG
      
      // Extract error message from API response
      // Backend returns structured error with status, message, and errors array
      let errorMsg = 'Unable to create transaction. Please try again.';
      
      if (error.status && error.message) {
        // Structured error from transactionService validation or API
        errorMsg = error.message;
        
        // If there are detailed error messages, append them
        if (error.errors && Array.isArray(error.errors) && error.errors.length > 0) {
          errorMsg += ' Details: ' + error.errors.join(', ');
        }
      } else if (error.message) {
        // Generic error object
        errorMsg = error.message;
      }

      // Display error message to user
      // COBOL equivalent: MOVE WS-ERR-MSG TO ERRMSGO OF COTRN2AO
      setErrorMessage(errorMsg);

      // Log error for troubleshooting
      console.error('Transaction creation error:', error);
    }
  };

  /**
   * Cancel Button Handler
   * 
   * Handles cancel button click, navigating back to transaction list without saving.
   * 
   * COBOL equivalent: Process F3 key (PF3=Back)
   * EXEC CICS RETURN TRANSID('CTRN') COMMAREA(...)
   * 
   * No data is saved, user is returned to previous screen.
   */
  const handleCancel = (): void => {
    // Navigate back to transaction list
    // COBOL equivalent: EXEC CICS RETURN TRANSID('CTRN')
    navigate('/transactions');
  };

  /**
   * Get Initial Form Values
   * 
   * Provides default/initial values for transaction entry form.
   * 
   * COBOL equivalent: COTRN02C.cbl INITIALIZE-SCREEN-FIELDS paragraph
   * - MOVE CURRENT-DATE to TORIGDTO, TPROCDTO
   * - MOVE SPACES to input fields
   * - MOVE LOW-VALUES to numeric fields
   * 
   * @returns Partial<TransactionFormData> - Initial form values
   */
  const getInitialValues = (): Partial<TransactionFormData> => {
    // Get current date in YYYY-MM-DD format
    // COBOL equivalent: ACCEPT CURRENT-DATE FROM DATE
    const today = new Date().toISOString().split('T')[0];

    return {
      // Account ID: empty (mutually exclusive with card number)
      acctId: '',
      
      // Card number: empty (user must enter)
      cardNum: '',
      
      // Transaction type: default to purchase (01)
      // COBOL equivalent: MOVE '01' TO TTYPCD
      transTypeCd: '01',
      
      // Transaction category: default to general merchandise (5311)
      // COBOL equivalent: MOVE 5311 TO TCATCD
      transCatCd: 5311,
      
      // Transaction source: default to POS
      // COBOL equivalent: MOVE 'POS' TO TRNSRC
      transSource: 'POS',
      
      // Description: empty (user must enter)
      transDesc: '',
      
      // Amount: 0.00 (user must enter)
      transAmt: 0,
      
      // Merchant fields: empty (optional)
      transMerchantId: '',
      transMerchantName: '',
      transMerchantCity: '',
      transMerchantZip: '',
      
      // Original date: today
      // COBOL equivalent: MOVE CURRENT-DATE TO TORIGDT
      transOrigTs: today,
      
      // Process date: today (auto-set by backend)
      // COBOL equivalent: MOVE CURRENT-DATE TO TPROCDT
      transProcTs: today,
      
      // Confirmation: unchecked (user must confirm)
      confirm: 'N',
    };
  };

  // Show loading spinner while checking authentication
  // COBOL equivalent: Processing indicator during CICS session check
  if (authLoading) {
    return (
      <Box
        display="flex"
        justifyContent="center"
        alignItems="center"
        minHeight="100vh"
      >
        <Typography variant="h6">Loading...</Typography>
      </Box>
    );
  }

  // If not authenticated, return null (useEffect will redirect)
  if (!isAuthenticated) {
    return null;
  }

  // Render transaction entry page
  // BMS screen layout: Header (rows 1-2), Form (rows 4-21), Footer (rows 23-24)
  return (
    <Box
      sx={{
        display: 'flex',
        flexDirection: 'column',
        minHeight: '100vh',
        backgroundColor: '#f5f5f5',
      }}
    >
      {/* Page Header Component
          BMS equivalent: Rows 1-2 with TRNNAME, TITLE01, CURDATE, PGMNAME, TITLE02, CURTIME
          COBOL: MOVE 'CT02' TO TRNNAMEO, MOVE 'CardDemo' TO TITLE01O, etc.
          Note: Header component displays standard CardDemo branding without custom props */}
      <Header />

      {/* Main Content Area
          BMS equivalent: Rows 4-21 with form fields and labels */}
      <Container
        component="main"
        maxWidth="lg"
        sx={{ flexGrow: 1, py: 4 }}
      >
        <Paper elevation={3} sx={{ p: 4 }}>
          {/* Page Title
              BMS equivalent: Row 4, POS=(4,30), 'Add Transaction' */}
          <Typography
            variant="h4"
            component="h1"
            gutterBottom
            sx={{ mb: 3, textAlign: 'center', fontWeight: 'bold' }}
          >
            Add Transaction
          </Typography>

          {/* Success Message Alert
              BMS equivalent: Status message area (non-standard, added for UX)
              Displayed after successful transaction posting */}
          {successMessage && (
            <Alert severity="success" sx={{ mb: 2 }} onClose={() => setSuccessMessage('')}>
              {successMessage}
            </Alert>
          )}

          {/* Error Message Component
              BMS equivalent: ERRMSG field, POS=(23,1), LENGTH=78, COLOR=RED
              COBOL: MOVE WS-ERR-MSG TO ERRMSGO OF COTRN2AO */}
          {errorMessage && (
            <Box sx={{ mb: 2 }}>
              <ErrorMessage message={errorMessage} />
            </Box>
          )}

          {/* Transaction Entry Form Component
              BMS equivalent: Rows 6-21 with all input fields
              COBOL: COTRN2AI input map structure
              
              Fields included:
              - ACTIDIN: Account ID (row 6, col 21, length 11)
              - CARDNIN: Card Number (row 6, col 55, length 16)
              - TTYPCD: Type Code (row 10, col 15, length 2)
              - TCATCD: Category Code (row 10, col 36, length 4)
              - TRNSRC: Source (row 10, col 54, length 10)
              - TDESC: Description (row 12, col 19, length 60)
              - TRNAMT: Amount (row 14, col 14, length 12)
              - TORIGDT: Original Date (row 14, col 42, length 10)
              - TPROCDT: Process Date (row 14, col 68, length 10)
              - MID: Merchant ID (row 16, col 19, length 9)
              - MNAME: Merchant Name (row 16, col 48, length 30)
              - MCITY: Merchant City (row 18, col 21, length 25)
              - MZIP: Merchant Zip (row 18, col 67, length 10)
              - CONFIRM: Confirmation (row 21, col 63, length 1)
          */}
          <TransactionForm
            initialValues={getInitialValues()}
            onSubmit={handleSubmit}
            onCancel={handleCancel}
          />
        </Paper>
      </Container>

      {/* Page Footer Component
          BMS equivalent: Row 24 with function key legend
          COBOL: 'ENTER=Continue  F3=Back  F4=Clear  F5=Copy Last Tran.' */}
      <Footer />
    </Box>
  );
};

/**
 * Default Export
 * 
 * Exports TransactionEntryPage component as default export.
 * Used by React Router for /transactions/new route.
 * 
 * COBOL equivalent: PROGRAM-ID. COTRN02C.
 */
export default TransactionEntryPage;
