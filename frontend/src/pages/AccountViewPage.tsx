/**
 * Account View Page Component
 * 
 * Converted from: COACTVW.bms (BMS 3270 screen definition) and COACTVWC.cbl (COBOL program)
 * Original function: Display read-only account and customer information
 * 
 * Conversion notes:
 * - BMS COACTVW map (24x80 3270 terminal screen) converted to React responsive layout
 * - All 40+ display fields (ASKIP attribute) converted to read-only Material-UI components
 * - COBOL EXEC CICS READ FILE('ACCTFILE') replaced with REST GET /api/accounts/:id
 * - COBOL copybook structures (CVACT01Y.cpy account record, CVCUS01Y.cpy customer record)
 *   converted to TypeScript interface for type-safe data handling
 * - Date fields formatted as MM/DD/YYYY (formatDate utility from CSUTLDTC.cbl logic)
 * - Currency fields formatted with $ and 2 decimals (formatCurrency utility)
 * - SSN formatted as 999-99-9999 per COBOL display patterns
 * - Phone numbers formatted appropriately per BMS field specifications
 * - F3=Exit function key replaced with Back button navigation
 * - Added Edit button to navigate to AccountUpdatePage (COACTUPC.cbl equivalent)
 * 
 * Field mappings from COACTVW.bms:
 * - ACCTSID (line 84-90): Account Number → acctId
 * - ACSTTUS (line 97-100): Active Y/N → acctActiveStatus
 * - ADTOPEN (line 107-109): Opened date → acctOpenDate
 * - ACRDLIM (line 117-121): Credit Limit → acctCreditLimit
 * - AEXPDT (line 128-130): Expiry date → acctExpirationDate
 * - ACSHLIM (line 138-142): Cash credit Limit → acctCashCreditLimit
 * - AREISDT (line 149-151): Reissue date → acctReissueDate
 * - ACURBAL (line 159-163): Current Balance → acctCurrBal
 * - ACRCYCR (line 171-175): Current Cycle Credit → acctCurrCycCredit
 * - AADDGRP (line 182-184): Account Group → acctGroupId
 * - ACRCYDB (line 192-196): Current Cycle Debit → acctCurrCycDebit
 * - ACSTNUM (line 207-209): Customer id → customer.custId
 * - ACSTSSN (line 216-218): SSN → customer.custSsn
 * - ACSTDOB (line 225-227): Date of birth → customer.custDobYyyyMmDd
 * - ACSTFCO (line 234-236): FICO Score → customer.custFicoCreditScore
 * - ACSFNAM (line 251-253): First Name → customer.custFirstName
 * - ACSMNAM (line 256-258): Middle Name → customer.custMiddleName
 * - ACSLNAM (line 261-263): Last Name → customer.custLastName
 * - ACSADL1 (line 268-270): Address Line 1 → customer.custAddrLine1
 * - ACSSTTE (line 277-279): State → customer.custAddrStateCd
 * - ACSADL2 (line 282-284): Address Line 2 → customer.custAddrLine2
 * - ACSZIPC (line 291-294): Zip → customer.custAddrZip
 * - ACSCITY (line 301-303): City → customer.custAddrLine3 (city stored in line 3)
 * - ACSCTRY (line 310-312): Country → customer.custAddrCountryCd
 * - ACSPHN1 (line 319-321): Phone 1 → customer.custPhoneNum1
 * - ACSGOVT (line 326-328): Government Issued Id → customer.custGovtIssuedId
 * - ACSPHN2 (line 335-337): Phone 2 → customer.custPhoneNum2
 * - ACSEFTC (line 342-344): EFT Account Id → customer.custEftAccountId
 * - ACSPFLG (line 351-353): Primary Card Holder Y/N → customer.custPriCardHolderInd
 * - ERRMSG (line 365-368): Error message display → ErrorMessage component
 * 
 * Per Agent Action Plan Section 0.7.2: Maintains identical field layout to COACTVWC.cbl
 * COBOL program logic with all fields as read-only display (ASKIP attribute preservation).
 * Per Section 0.7.5: Preserves COBOL data type semantics for display formatting.
 */

import React, { useState, useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Box,
  Button,
  Card,
  CardContent,
  Container,
  Divider,
  Grid,
  Stack,
  Typography,
} from '@mui/material';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import EditIcon from '@mui/icons-material/Edit';

// Internal imports from depends_on_files
import accountService from '../services/accountService';
import Header from '../components/common/Header';
import Footer from '../components/common/Footer';
import ErrorMessage from '../components/common/ErrorMessage';
import LoadingSpinner from '../components/common/LoadingSpinner';
import { Account } from '../types/account';
import { useAuth } from '../hooks/useAuth';
import { formatDate } from '../utils/dateFormatter';
import { formatCurrency } from '../utils/currencyFormatter';

/**
 * Customer data structure embedded in account view response
 * 
 * Converted from: CVCUS01Y.cpy (CUSTOMER-RECORD copybook)
 * These fields are returned by backend GET /api/accounts/:id when account includes
 * customer data via card-account-customer cross-reference (XREFFILE in COBOL).
 * 
 * The backend AccountController likely performs the join to retrieve customer
 * details associated with the account, replicating COACTVWC.cbl logic:
 * - READ ACCTFILE by account ID
 * - READ XREFFILE by account ID to get customer ID
 * - READ CUSTFILE by customer ID
 * - Combine data into single response
 */
interface CustomerData {
  /** Customer identifier - CUST-ID PIC 9(09) from CVCUS01Y.cpy */
  custId: number;
  
  /** Customer first name - CUST-FIRST-NAME PIC X(25) */
  custFirstName: string;
  
  /** Customer middle name - CUST-MIDDLE-NAME PIC X(25) */
  custMiddleName: string | null;
  
  /** Customer last name - CUST-LAST-NAME PIC X(25) */
  custLastName: string;
  
  /** Address line 1 - CUST-ADDR-LINE-1 PIC X(50) */
  custAddrLine1: string | null;
  
  /** Address line 2 - CUST-ADDR-LINE-2 PIC X(50) */
  custAddrLine2: string | null;
  
  /** Address line 3 (city) - CUST-ADDR-LINE-3 PIC X(50) */
  custAddrLine3: string | null;
  
  /** State code - CUST-ADDR-STATE-CD PIC X(02) */
  custAddrStateCd: string | null;
  
  /** Country code - CUST-ADDR-COUNTRY-CD PIC X(03) */
  custAddrCountryCd: string | null;
  
  /** ZIP code - CUST-ADDR-ZIP PIC X(10) */
  custAddrZip: string | null;
  
  /** Phone number 1 - CUST-PHONE-NUM-1 PIC X(15) */
  custPhoneNum1: string | null;
  
  /** Phone number 2 - CUST-PHONE-NUM-2 PIC X(15) */
  custPhoneNum2: string | null;
  
  /** Social Security Number - CUST-SSN PIC 9(09) */
  custSsn: string | null;
  
  /** Government issued ID - CUST-GOVT-ISSUED-ID PIC X(20) */
  custGovtIssuedId: string | null;
  
  /** Date of birth - CUST-DOB-YYYY-MM-DD PIC X(10) */
  custDobYyyyMmDd: string | null;
  
  /** EFT account ID - CUST-EFT-ACCOUNT-ID PIC X(10) */
  custEftAccountId: string | null;
  
  /** Primary card holder indicator - CUST-PRI-CARD-HOLDER-IND PIC X(01) */
  custPriCardHolderInd: string | null;
  
  /** FICO credit score - CUST-FICO-CREDIT-SCORE PIC 9(03) */
  custFicoCreditScore: number | null;
}

/**
 * Extended account interface including embedded customer data
 * 
 * Backend GET /api/accounts/:id returns this enriched structure for account view page,
 * combining account record (CVACT01Y.cpy) with customer record (CVCUS01Y.cpy).
 */
interface AccountWithCustomer extends Account {
  /** Embedded customer data from CUSTFILE via XREFFILE join */
  customer?: CustomerData;
}

/**
 * Format SSN for display as 999-99-9999
 * 
 * COBOL equivalent: SSN display editing from COACTVW.bms ACSTSSN field (line 216-218)
 * Original COBOL stores SSN as PIC 9(09), displays with dashes inserted.
 * 
 * @param ssn - 9-digit SSN string or null
 * @returns Formatted SSN string or empty string if null
 * 
 * @example
 * formatSSN('123456789') → '123-45-6789'
 * formatSSN('12345') → '12345' (invalid length, return as-is)
 * formatSSN(null) → ''
 */
const formatSSN = (ssn: string | null): string => {
  if (!ssn) {
    return '';
  }
  
  // SSN must be exactly 9 digits
  if (ssn.length === 9 && /^\d{9}$/.test(ssn)) {
    return `${ssn.slice(0, 3)}-${ssn.slice(3, 5)}-${ssn.slice(5)}`;
  }
  
  // Return as-is if not 9 digits (invalid SSN)
  return ssn;
};

/**
 * Format phone number for display
 * 
 * COBOL equivalent: Phone number display from COACTVW.bms ACSPHN1/ACSPHN2 fields
 * (lines 319-321, 335-337). Original COBOL stores as PIC X(15), displays with formatting.
 * 
 * Supports multiple formats:
 * - 10 digits: (999) 999-9999
 * - 11 digits (with 1 prefix): 1 (999) 999-9999
 * - Other lengths: display as-is
 * 
 * @param phone - Phone number string or null
 * @returns Formatted phone string or empty string if null
 * 
 * @example
 * formatPhoneNumber('5551234567') → '(555) 123-4567'
 * formatPhoneNumber('15551234567') → '1 (555) 123-4567'
 * formatPhoneNumber('123') → '123' (too short, return as-is)
 * formatPhoneNumber(null) → ''
 */
const formatPhoneNumber = (phone: string | null): string => {
  if (!phone) {
    return '';
  }
  
  // Remove all non-digit characters
  const digits = phone.replace(/\D/g, '');
  
  // Format 10-digit phone: (999) 999-9999
  if (digits.length === 10) {
    return `(${digits.slice(0, 3)}) ${digits.slice(3, 6)}-${digits.slice(6)}`;
  }
  
  // Format 11-digit phone with country code: 1 (999) 999-9999
  if (digits.length === 11 && digits.startsWith('1')) {
    return `${digits[0]} (${digits.slice(1, 4)}) ${digits.slice(4, 7)}-${digits.slice(7)}`;
  }
  
  // Return as-is if format doesn't match expected patterns
  return phone;
};

/**
 * Account View Page Component
 * 
 * Displays read-only account and customer information in a card layout.
 * Replaces COACTVW.bms 3270 terminal screen with modern responsive web interface.
 * 
 * Component workflow (mirrors COACTVWC.cbl program flow):
 * 1. Extract account ID from URL parameters (/:id route parameter)
 * 2. Verify user authentication via useAuth hook (replaces COBOL COMMAREA security check)
 * 3. Call accountService.getAccountById() to fetch account data (replaces EXEC CICS READ)
 * 4. Display all account fields in read-only format (ASKIP attribute preservation)
 * 5. Display all customer fields in read-only format
 * 6. Provide Back button (F3=Exit replacement)
 * 7. Provide Edit button to navigate to AccountUpdatePage (COACTUPC.cbl)
 * 
 * Error handling:
 * - Account not found (404): Display error message (COBOL file-status 23)
 * - API failure: Display error message with details
 * - Invalid account ID format: Display validation error
 * - Authentication failure: Redirect to login page
 * 
 * Per Agent Action Plan Section 0.4.18: Converts COACTVW.bms map to React component
 * with Material-UI styling, maintaining identical field layout and read-only display.
 */
const AccountViewPage: React.FC = () => {
  // React Router hooks for navigation and URL parameters
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  
  // Authentication state from useAuth hook (replaces COBOL COMMAREA)
  const { isAuthenticated } = useAuth();
  
  // Component state management
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string>('');
  const [accountData, setAccountData] = useState<AccountWithCustomer | null>(null);
  
  /**
   * Fetch account data on component mount
   * 
   * Replaces COBOL COACTVWC.cbl PROCEDURE DIVISION logic:
   * - PERFORM 1000-READ-ACCOUNT-FILE
   * - EXEC CICS READ FILE('ACCTFILE') RIDFLD(ACCT-ID) INTO(ACCOUNT-RECORD)
   * - PERFORM 2000-READ-CUSTOMER-FILE via XREFFILE
   * - EXEC CICS SEND MAP('CACTVWA') MAPSET('COACTVW')
   * 
   * Error handling matches COBOL file-status checks:
   * - Success (00): Display account data
   * - Not found (23): Display "Account not found" error
   * - Other errors: Display generic error message
   */
  useEffect(() => {
    // Redirect to login if not authenticated (replaces COBOL security check)
    if (!isAuthenticated) {
      navigate('/login');
      return;
    }
    
    // Validate account ID parameter
    if (!id) {
      setError('Account ID is required');
      setLoading(false);
      return;
    }
    
    // Parse account ID to number
    const accountId = parseInt(id, 10);
    
    // Validate account ID is a positive integer
    if (isNaN(accountId) || accountId <= 0) {
      setError('Invalid account ID format. Account ID must be a positive number.');
      setLoading(false);
      return;
    }
    
    // Validate account ID length (11 digits max per COBOL PIC 9(11))
    if (accountId > 99999999999) {
      setError('Invalid account ID. Account ID must not exceed 11 digits.');
      setLoading(false);
      return;
    }
    
    /**
     * Async function to fetch account data from backend API
     * Replaces COBOL EXEC CICS READ FILE('ACCTFILE')
     */
    const fetchAccountData = async () => {
      try {
        setLoading(true);
        setError('');
        
        // Call REST API: GET /api/accounts/:id
        // Replaces COBOL EXEC CICS READ FILE('ACCTFILE') RIDFLD(ACCT-ID)
        const account = await accountService.getAccountById(accountId);
        
        // Cast to AccountWithCustomer to access customer data
        // Backend should return enriched account object with customer data
        setAccountData(account as AccountWithCustomer);
        
      } catch (err: any) {
        // Map error to user-friendly message (matches COBOL error handling)
        const errorMessage = err.message || 'Failed to load account information. Please try again.';
        setError(errorMessage);
        
      } finally {
        setLoading(false);
      }
    };
    
    // Execute fetch on component mount and when account ID changes
    fetchAccountData();
    
  }, [id, isAuthenticated, navigate]);
  
  /**
   * Handle Back button click
   * Replaces COBOL F3=Exit function key (COACTVW.bms line 373)
   * Navigates back to previous page or main menu
   */
  const handleBack = () => {
    navigate(-1); // Browser back navigation
  };
  
  /**
   * Handle Edit button click
   * Navigates to AccountUpdatePage for account modification
   * Replaces COBOL EXEC CICS XCTL PROGRAM('COACTUPC') from COACTVWC.cbl
   */
  const handleEdit = () => {
    if (accountData) {
      navigate(`/accounts/${accountData.acctId}/edit`);
    }
  };
  
  /**
   * Render loading spinner during data fetch
   * Replaces COBOL CICS wait state during file I/O
   */
  if (loading) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
        <Header />
        <Container maxWidth="lg" sx={{ flex: 1, py: 4 }}>
          <LoadingSpinner />
        </Container>
        <Footer />
      </Box>
    );
  }
  
  /**
   * Render error message if account fetch failed
   * Replaces COBOL error message display (COACTVW.bms ERRMSG field line 365-368)
   */
  if (error) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
        <Header />
        <Container maxWidth="lg" sx={{ flex: 1, py: 4 }}>
          <ErrorMessage message={error} />
          <Box sx={{ mt: 3 }}>
            <Button
              variant="outlined"
              startIcon={<ArrowBackIcon />}
              onClick={handleBack}
            >
              Back
            </Button>
          </Box>
        </Container>
        <Footer />
      </Box>
    );
  }
  
  /**
   * Render message if account data not found
   * Should not normally occur if error handling works correctly
   */
  if (!accountData) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
        <Header />
        <Container maxWidth="lg" sx={{ flex: 1, py: 4 }}>
          <Typography variant="h6" color="error">
            Account data not available
          </Typography>
          <Box sx={{ mt: 3 }}>
            <Button
              variant="outlined"
              startIcon={<ArrowBackIcon />}
              onClick={handleBack}
            >
              Back
            </Button>
          </Box>
        </Container>
        <Footer />
      </Box>
    );
  }
  
  // Extract customer data from account response
  const customer = accountData.customer;
  
  /**
   * Main page layout rendering account and customer information
   * 
   * Layout structure mirrors COACTVW.bms screen layout:
   * - Header (TRNNAME, TITLE01/02, CURDATE, CURTIME - lines 34-74)
   * - Page title "View Account" (line 78)
   * - Account section with all account fields (lines 84-196)
   * - Customer Details section with all customer fields (lines 203-353)
   * - Action buttons replacing function keys (line 373 F3=Exit)
   * - Footer with help text
   * 
   * All fields are read-only (Typography components) matching BMS ASKIP attribute.
   * Field positions and groupings match COACTVW.bms POS= coordinates.
   */
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      {/* Header component - replaces BMS header fields (lines 34-74) */}
      <Header />
      
      {/* Main content area */}
      <Container maxWidth="lg" sx={{ flex: 1, py: 4 }}>
        {/* Page title - replaces "View Account" at POS=(4,33) line 78 */}
        <Typography variant="h4" component="h1" gutterBottom align="center" sx={{ mb: 3 }}>
          View Account
        </Typography>
        
        {/* Action buttons - replaces BMS function keys (line 373 F3=Exit) */}
        <Stack direction="row" spacing={2} sx={{ mb: 3 }}>
          <Button
            variant="outlined"
            startIcon={<ArrowBackIcon />}
            onClick={handleBack}
          >
            Back
          </Button>
          <Button
            variant="contained"
            startIcon={<EditIcon />}
            onClick={handleEdit}
            color="primary"
          >
            Edit Account
          </Button>
        </Stack>
        
        {/* Account information card */}
        <Card sx={{ mb: 3 }}>
          <CardContent>
            {/* Account header section - lines 84-100 */}
            <Grid container spacing={3}>
              {/* Account Number field - ACCTSID POS=(5,38) lines 84-90 */}
              <Grid item xs={12} md={6}>
                <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                  Account Number
                </Typography>
                <Typography variant="body1" fontWeight="medium">
                  {accountData.acctId}
                </Typography>
              </Grid>
              
              {/* Active Status field - ACSTTUS POS=(5,70) lines 97-100 */}
              <Grid item xs={12} md={6}>
                <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                  Active Y/N
                </Typography>
                <Typography variant="body1" fontWeight="medium">
                  {accountData.acctActiveStatus}
                </Typography>
              </Grid>
            </Grid>
            
            <Divider sx={{ my: 2 }} />
            
            {/* Account dates and limits section - lines 107-163 */}
            <Grid container spacing={3}>
              {/* Opened Date field - ADTOPEN POS=(6,17) lines 107-109 */}
              <Grid item xs={12} sm={6} md={4}>
                <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                  Opened
                </Typography>
                <Typography variant="body1">
                  {accountData.acctOpenDate ? formatDate(new Date(accountData.acctOpenDate)) : ''}
                </Typography>
              </Grid>
              
              {/* Expiry Date field - AEXPDT POS=(7,17) lines 128-130 */}
              <Grid item xs={12} sm={6} md={4}>
                <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                  Expiry
                </Typography>
                <Typography variant="body1">
                  {accountData.acctExpirationDate ? formatDate(new Date(accountData.acctExpirationDate)) : ''}
                </Typography>
              </Grid>
              
              {/* Reissue Date field - AREISDT POS=(8,17) lines 149-151 */}
              <Grid item xs={12} sm={6} md={4}>
                <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                  Reissue
                </Typography>
                <Typography variant="body1">
                  {accountData.acctReissueDate ? formatDate(new Date(accountData.acctReissueDate)) : ''}
                </Typography>
              </Grid>
              
              {/* Credit Limit field - ACRDLIM POS=(6,61) lines 117-121 */}
              <Grid item xs={12} sm={6} md={4}>
                <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                  Credit Limit
                </Typography>
                <Typography variant="body1">
                  {formatCurrency(accountData.acctCreditLimit)}
                </Typography>
              </Grid>
              
              {/* Cash Credit Limit field - ACSHLIM POS=(7,61) lines 138-142 */}
              <Grid item xs={12} sm={6} md={4}>
                <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                  Cash Credit Limit
                </Typography>
                <Typography variant="body1">
                  {formatCurrency(accountData.acctCashCreditLimit)}
                </Typography>
              </Grid>
              
              {/* Current Balance field - ACURBAL POS=(8,61) lines 159-163 */}
              <Grid item xs={12} sm={6} md={4}>
                <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                  Current Balance
                </Typography>
                <Typography variant="body1" fontWeight="medium">
                  {formatCurrency(accountData.acctCurrBal)}
                </Typography>
              </Grid>
            </Grid>
            
            <Divider sx={{ my: 2 }} />
            
            {/* Current cycle and account group section - lines 171-196 */}
            <Grid container spacing={3}>
              {/* Account Group field - AADDGRP POS=(10,23) lines 182-184 */}
              <Grid item xs={12} sm={6} md={4}>
                <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                  Account Group
                </Typography>
                <Typography variant="body1">
                  {accountData.acctGroupId || 'N/A'}
                </Typography>
              </Grid>
              
              {/* Current Cycle Credit field - ACRCYCR POS=(9,61) lines 171-175 */}
              <Grid item xs={12} sm={6} md={4}>
                <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                  Current Cycle Credit
                </Typography>
                <Typography variant="body1">
                  {formatCurrency(accountData.acctCurrCycCredit)}
                </Typography>
              </Grid>
              
              {/* Current Cycle Debit field - ACRCYDB POS=(10,61) lines 192-196 */}
              <Grid item xs={12} sm={6} md={4}>
                <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                  Current Cycle Debit
                </Typography>
                <Typography variant="body1">
                  {formatCurrency(accountData.acctCurrCycDebit)}
                </Typography>
              </Grid>
            </Grid>
          </CardContent>
        </Card>
        
        {/* Customer Details card - lines 203-353 */}
        {customer && (
          <Card>
            <CardContent>
              {/* Customer Details title - POS=(11,32) line 201 */}
              <Typography variant="h6" component="h2" gutterBottom align="center" sx={{ mb: 2 }}>
                Customer Details
              </Typography>
              
              {/* Customer ID and SSN section - lines 207-218 */}
              <Grid container spacing={3}>
                {/* Customer ID field - ACSTNUM POS=(12,23) lines 207-209 */}
                <Grid item xs={12} sm={6} md={4}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Customer ID
                  </Typography>
                  <Typography variant="body1">
                    {customer.custId}
                  </Typography>
                </Grid>
                
                {/* SSN field - ACSTSSN POS=(12,54) lines 216-218 */}
                <Grid item xs={12} sm={6} md={4}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    SSN
                  </Typography>
                  <Typography variant="body1">
                    {formatSSN(customer.custSsn)}
                  </Typography>
                </Grid>
                
                {/* Date of Birth field - ACSTDOB POS=(13,23) lines 225-227 */}
                <Grid item xs={12} sm={6} md={4}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Date of Birth
                  </Typography>
                  <Typography variant="body1">
                    {customer.custDobYyyyMmDd ? formatDate(new Date(customer.custDobYyyyMmDd)) : ''}
                  </Typography>
                </Grid>
                
                {/* FICO Score field - ACSTFCO POS=(13,61) lines 234-236 */}
                <Grid item xs={12} sm={6} md={4}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    FICO Score
                  </Typography>
                  <Typography variant="body1">
                    {customer.custFicoCreditScore || 'N/A'}
                  </Typography>
                </Grid>
              </Grid>
              
              <Divider sx={{ my: 2 }} />
              
              {/* Customer name section - lines 251-263 */}
              <Grid container spacing={3}>
                {/* First Name field - ACSFNAM POS=(15,1) lines 251-253 */}
                <Grid item xs={12} sm={4}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    First Name
                  </Typography>
                  <Typography variant="body1">
                    {customer.custFirstName}
                  </Typography>
                </Grid>
                
                {/* Middle Name field - ACSMNAM POS=(15,28) lines 256-258 */}
                <Grid item xs={12} sm={4}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Middle Name
                  </Typography>
                  <Typography variant="body1">
                    {customer.custMiddleName || ''}
                  </Typography>
                </Grid>
                
                {/* Last Name field - ACSLNAM POS=(15,55) lines 261-263 */}
                <Grid item xs={12} sm={4}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Last Name
                  </Typography>
                  <Typography variant="body1">
                    {customer.custLastName}
                  </Typography>
                </Grid>
              </Grid>
              
              <Divider sx={{ my: 2 }} />
              
              {/* Address section - lines 268-312 */}
              <Grid container spacing={3}>
                {/* Address Line 1 field - ACSADL1 POS=(16,10) lines 268-270 */}
                <Grid item xs={12} sm={8}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Address
                  </Typography>
                  <Typography variant="body1">
                    {customer.custAddrLine1 || ''}
                  </Typography>
                </Grid>
                
                {/* State field - ACSSTTE POS=(16,73) lines 277-279 */}
                <Grid item xs={12} sm={4}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    State
                  </Typography>
                  <Typography variant="body1">
                    {customer.custAddrStateCd || ''}
                  </Typography>
                </Grid>
                
                {/* Address Line 2 field - ACSADL2 POS=(17,10) lines 282-284 */}
                <Grid item xs={12} sm={8}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Address Line 2
                  </Typography>
                  <Typography variant="body1">
                    {customer.custAddrLine2 || ''}
                  </Typography>
                </Grid>
                
                {/* Zip Code field - ACSZIPC POS=(17,73) lines 291-294 */}
                <Grid item xs={12} sm={4}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Zip
                  </Typography>
                  <Typography variant="body1">
                    {customer.custAddrZip || ''}
                  </Typography>
                </Grid>
                
                {/* City field - ACSCITY POS=(18,10) lines 301-303 */}
                <Grid item xs={12} sm={8}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    City
                  </Typography>
                  <Typography variant="body1">
                    {customer.custAddrLine3 || ''}
                  </Typography>
                </Grid>
                
                {/* Country field - ACSCTRY POS=(18,73) lines 310-312 */}
                <Grid item xs={12} sm={4}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Country
                  </Typography>
                  <Typography variant="body1">
                    {customer.custAddrCountryCd || ''}
                  </Typography>
                </Grid>
              </Grid>
              
              <Divider sx={{ my: 2 }} />
              
              {/* Contact and additional information section - lines 319-353 */}
              <Grid container spacing={3}>
                {/* Phone 1 field - ACSPHN1 POS=(19,10) lines 319-321 */}
                <Grid item xs={12} sm={6} md={4}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Phone 1
                  </Typography>
                  <Typography variant="body1">
                    {formatPhoneNumber(customer.custPhoneNum1)}
                  </Typography>
                </Grid>
                
                {/* Phone 2 field - ACSPHN2 POS=(20,10) lines 335-337 */}
                <Grid item xs={12} sm={6} md={4}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Phone 2
                  </Typography>
                  <Typography variant="body1">
                    {formatPhoneNumber(customer.custPhoneNum2)}
                  </Typography>
                </Grid>
                
                {/* Government Issued ID field - ACSGOVT POS=(19,58) lines 326-328 */}
                <Grid item xs={12} sm={6} md={4}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Government Issued ID
                  </Typography>
                  <Typography variant="body1">
                    {customer.custGovtIssuedId || 'N/A'}
                  </Typography>
                </Grid>
                
                {/* EFT Account ID field - ACSEFTC POS=(20,41) lines 342-344 */}
                <Grid item xs={12} sm={6} md={4}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    EFT Account ID
                  </Typography>
                  <Typography variant="body1">
                    {customer.custEftAccountId || 'N/A'}
                  </Typography>
                </Grid>
                
                {/* Primary Card Holder field - ACSPFLG POS=(20,78) lines 351-353 */}
                <Grid item xs={12} sm={6} md={4}>
                  <Typography variant="subtitle2" color="text.secondary" gutterBottom>
                    Primary Card Holder Y/N
                  </Typography>
                  <Typography variant="body1">
                    {customer.custPriCardHolderInd || 'N/A'}
                  </Typography>
                </Grid>
              </Grid>
            </CardContent>
          </Card>
        )}
        
        {/* Display message if customer data not available */}
        {!customer && (
          <Card>
            <CardContent>
              <Typography variant="body1" color="text.secondary" align="center">
                Customer information not available for this account
              </Typography>
            </CardContent>
          </Card>
        )}
      </Container>
      
      {/* Footer component - replaces BMS footer (line 373) */}
      <Footer />
    </Box>
  );
};

// Default export as required by schema
export default AccountViewPage;
