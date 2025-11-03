/**
 * Account View Component
 * 
 * React functional component for displaying read-only credit card account details.
 * Transforms COACTVW BMS mapset (mapset COACTVW / DFHMDI CACTVWA) from COBOL 
 * COACTVWC.cbl program to modern web interface.
 * 
 * Source Transformation:
 * - COBOL Program: app/cbl/COACTVWC.cbl (Account View transaction)
 * - BMS Mapset: app/bms/COACTVW.bms (3270 terminal screen)
 * - BMS Copybook: app/cpy-bms/COACTVW.CPY (field definitions)
 * 
 * Functional Equivalence:
 * This component preserves exact business logic from COACTVWC.cbl including:
 * - VSAM ACCTDAT file read operation → REST API GET /api/accounts/{id}
 * - VSAM CUSTDAT file read operation → Customer data embedded in account response
 * - BMS DFHMDF protected fields (ATTRB=ASKIP/PROT) → disabled Material-UI TextFields
 * - COBOL COMP-3 decimal precision → formatCurrency with 2 decimal places
 * - PF3 key function → Back button navigation
 * - Screen field positioning (POS) → Material-UI Grid responsive layout
 * 
 * Key Features:
 * - Read-only account information display with comprehensive field mapping
 * - Customer details section with address, phone, and identification information
 * - Currency fields formatted with COBOL COMP-3 precision (S9(10)V99 → 2 decimals)
 * - Date fields displayed in MM/DD/YYYY format matching COBOL date patterns
 * - Edit button navigation to AccountUpdateComponent for account modifications
 * - Back button for return navigation to account list or main menu
 * - Loading spinner during data fetch operation matching CICS processing delays
 * - Error handling with toast notifications for account not found scenarios
 * 
 * BMS Field Mappings:
 * - ACCTSID (Account Number) → accountId text field
 * - ACSTTUS (Active Y/N) → accountStatus text field
 * - ADTOPEN (Opened Date) → openDate with formatDateDisplay
 * - AEXPDT (Expiry Date) → expiryDate with formatDateDisplay
 * - AREISDT (Reissue Date) → reissueDate with formatDateDisplay
 * - ACRDLIM (Credit Limit) → creditLimit with formatCurrency
 * - ACSHLIM (Cash Credit Limit) → cashCreditLimit with formatCurrency
 * - ACURBAL (Current Balance) → currentBalance with formatCurrency
 * - ACRCYCR (Current Cycle Credit) → currentCycleCredit with formatCurrency
 * - ACRCYDB (Current Cycle Debit) → currentCycleDebit with formatCurrency
 * - AADDGRP (Account Group) → accountGroup text field
 * - ACSTNUM (Customer ID) → customer.customerId text field
 * - ACSTSSN (SSN) → customer.ssn with formatSSN (masked)
 * - ACSTDOB (Date of Birth) → customer.dateOfBirth with formatDateDisplay
 * - ACSTFCO (FICO Score) → customer.ficoScore text field
 * - ACSFNAM (First Name) → customer.firstName text field
 * - ACSMNAM (Middle Name) → customer.middleName text field
 * - ACSLNAM (Last Name) → customer.lastName text field
 * - ACSADL1 (Address Line 1) → customer.addressLine1 text field
 * - ACSADL2 (Address Line 2) → customer.addressLine2 text field
 * - ACSCITY (City) → customer.city text field
 * - ACSSTTE (State) → customer.state text field
 * - ACSZIPC (Zip Code) → customer.zipCode text field
 * - ACSCTRY (Country) → customer.countryCode text field
 * - ACSPHN1 (Phone 1) → customer.phone1 with formatPhoneNumber
 * - ACSPHN2 (Phone 2) → customer.phone2 with formatPhoneNumber
 * - ACSGOVT (Government ID) → customer.governmentId text field
 * - ACSEFTC (EFT Account ID) → customer.eftAccountId text field
 * - ACSPFLG (Primary Card Holder) → customer.primaryCardholder text field
 * 
 * Performance Requirements:
 * - Account data fetch must complete within sub-200ms transaction response time target
 * - Loading spinner displays after 300ms delay to prevent flash for fast operations
 * - Component renders immediately with loading state, then updates with fetched data
 * 
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */

import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Box,
  Button,
  TextField,
  Typography,
  Paper,
  Grid,
  Divider
} from '@mui/material';
import { toast } from 'react-toastify';
import accountService from '../../services/accountService';
import { formatCurrency, formatDateDisplay, formatSSN, formatPhoneNumber } from '../../utils/formatters';
import Loading from '../common/Loading';

/**
 * AccountViewComponent - Read-only account details display
 * 
 * Maps COBOL COACTVWC.cbl program flow:
 * 1. Initialize component state (WORKING-STORAGE initialization)
 * 2. Extract accountId from URL parameters (COMMAREA account ID field)
 * 3. Fetch account data via REST API (EXEC CICS READ DATASET('ACCTDAT'))
 * 4. Fetch customer data embedded in response (EXEC CICS READ DATASET('CUSTDAT'))
 * 5. Display all fields as disabled text inputs (BMS DFHMDF ATTRB=ASKIP/PROT)
 * 6. Provide navigation buttons for Edit and Back (PF keys)
 * 
 * @returns {JSX.Element} Account view component with read-only form fields
 */
const AccountViewComponent = () => {
  // ============================================================================
  // STATE MANAGEMENT
  // Maps COBOL WORKING-STORAGE SECTION variables
  // ============================================================================
  
  // Account data state - maps to ACCOUNT-RECORD from CVACT01Y.cpy
  const [account, setAccount] = useState(null);
  
  // Loading state - maps to CICS transaction processing delay indicator
  const [loading, setLoading] = useState(true);
  
  // Error state - maps to WS-RETURN-MSG error message handling
  const [error, setError] = useState(null);
  
  // ============================================================================
  // ROUTING AND NAVIGATION
  // Maps CICS transaction context and COMMAREA handling
  // ============================================================================
  
  // Extract accountId from URL parameters - maps to COMMAREA account ID field
  const { accountId } = useParams();
  
  // Navigation hook for programmatic routing - maps to EXEC CICS XCTL/RETURN
  const navigate = useNavigate();
  
  // ============================================================================
  // DATA FETCHING EFFECT
  // Maps COBOL 9300-GETACCTDATA-BYACCT paragraph
  // ============================================================================
  
  useEffect(() => {
    /**
     * Fetch account data from REST API
     * 
     * COBOL Equivalent:
     * 9300-GETACCTDATA-BYACCT.
     *    EXEC CICS READ DATASET('ACCTDAT')
     *         INTO(ACCOUNT-RECORD)
     *         RIDFLD(ACCTSID)
     *         RESP(WS-RESP-CD)
     *         RESP2(WS-REAS-CD)
     *    END-EXEC
     *    
     *    EVALUATE WS-RESP-CD
     *       WHEN DFHRESP(NORMAL)
     *          PERFORM 9350-GETACCTDATA-CUSTOMER
     *       WHEN DFHRESP(NOTFND)
     *          SET DID-NOT-FIND-ACCT-IN-ACCTDAT TO TRUE
     *    END-EVALUATE
     */
    const fetchAccount = async () => {
      try {
        setLoading(true);
        setError(null);
        
        // Validate accountId parameter exists
        // Maps COBOL: 2210-EDIT-ACCOUNT validation paragraph
        if (!accountId) {
          throw new Error('Account number not provided');
        }
        
        // Call account service to fetch data via REST API
        // Maps: EXEC CICS READ DATASET('ACCTDAT') RIDFLD(accountId)
        const data = await accountService.getAccount(accountId);
        
        // Store account data in state - maps to moving data to symbolic storage
        setAccount(data);
        
      } catch (err) {
        // Error handling - maps COBOL WHEN DFHRESP(NOTFND) condition
        // and WS-RETURN-MSG error message patterns
        const errorMessage = err.message || 'Failed to load account details';
        setError(errorMessage);
        
        // Display error notification to user
        // Maps COBOL: MOVE error message TO ERRMSG field in BMS map
        toast.error(errorMessage, {
          position: 'top-right',
          autoClose: 5000,
          hideProgressBar: false,
          closeOnClick: true,
          pauseOnHover: true,
          draggable: true
        });
        
        console.error('Error fetching account:', err);
      } finally {
        // Always clear loading state
        // Maps COBOL: End of processing paragraph
        setLoading(false);
      }
    };
    
    // Execute fetch operation on component mount and when accountId changes
    fetchAccount();
  }, [accountId]);
  
  // ============================================================================
  // EVENT HANDLERS
  // Maps COBOL PF key processing and navigation logic
  // ============================================================================
  
  /**
   * Handle Edit button click
   * Navigates to AccountUpdateComponent for account modification
   * 
   * Maps COBOL: EXEC CICS XCTL PROGRAM('COACTUPC') COMMAREA(account-data)
   */
  const handleEdit = () => {
    navigate(`/accounts/${accountId}/edit`);
  };
  
  /**
   * Handle Back button click
   * Navigates back to account list or main menu
   * 
   * Maps COBOL: EXEC CICS RETURN TRANSID('CM00') (PF3 key function)
   */
  const handleBack = () => {
    navigate('/accounts');
  };
  
  // ============================================================================
  // LOADING STATE RENDERING
  // Maps CICS transaction processing delay indicator
  // ============================================================================
  
  if (loading) {
    return (
      <Loading
        type="spinner"
        size="large"
        message="Loading account details..."
        overlay="inline"
      />
    );
  }
  
  // ============================================================================
  // ERROR STATE RENDERING
  // Maps COBOL error condition display
  // ============================================================================
  
  if (error || !account) {
    return (
      <Box sx={{ p: 3 }}>
        <Paper elevation={2} sx={{ p: 3 }}>
          <Typography variant="h5" color="error" gutterBottom>
            Error Loading Account
          </Typography>
          <Typography variant="body1" paragraph>
            {error || 'Account not found'}
          </Typography>
          <Button 
            variant="contained" 
            color="primary" 
            onClick={handleBack}
          >
            Back to Accounts
          </Button>
        </Paper>
      </Box>
    );
  }
  
  // ============================================================================
  // MAIN COMPONENT RENDERING
  // Maps BMS COACTVW screen layout with all DFHMDF field definitions
  // ============================================================================
  
  return (
    <Box sx={{ p: 3 }}>
      <Paper elevation={3} sx={{ p: 3 }}>
        {/* ================================================================== */}
        {/* HEADER SECTION */}
        {/* Maps BMS POS=(4,33) INITIAL='View Account' */}
        {/* ================================================================== */}
        
        <Typography variant="h4" component="h1" gutterBottom align="center" sx={{ mb: 3 }}>
          View Account
        </Typography>
        
        <Divider sx={{ mb: 3 }} />
        
        {/* ================================================================== */}
        {/* ACCOUNT INFORMATION SECTION */}
        {/* Maps BMS fields from lines 84-197 (account-specific fields) */}
        {/* ================================================================== */}
        
        <Grid container spacing={3}>
          {/* Account Number and Status Row */}
          {/* ACCTSID: POS=(5,38), LENGTH=11, PICIN='99999999999' */}
          {/* ACSTTUS: POS=(5,70), LENGTH=1, ATTRB=ASKIP */}
          <Grid item xs={12} md={8}>
            <TextField
              label="Account Number"
              value={account.accountId || ''}
              disabled
              fullWidth
              variant="outlined"
              InputProps={{
                readOnly: true,
              }}
              helperText="11-digit account identifier"
            />
          </Grid>
          
          <Grid item xs={12} md={4}>
            <TextField
              label="Active Y/N"
              value={account.accountStatus || ''}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
            />
          </Grid>
          
          {/* Date Fields Row */}
          {/* ADTOPEN: POS=(6,17), LENGTH=10 */}
          {/* AEXPDT: POS=(7,17), LENGTH=10 */}
          {/* AREISDT: POS=(8,17), LENGTH=10 */}
          <Grid item xs={12} md={4}>
            <TextField
              label="Opened"
              value={formatDateDisplay(account.openDate)}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
            />
          </Grid>
          
          <Grid item xs={12} md={4}>
            <TextField
              label="Expiry"
              value={formatDateDisplay(account.expiryDate)}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
            />
          </Grid>
          
          <Grid item xs={12} md={4}>
            <TextField
              label="Reissue"
              value={formatDateDisplay(account.reissueDate)}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
            />
          </Grid>
          
          {/* Credit Limit and Cash Credit Limit Row */}
          {/* ACRDLIM: POS=(6,61), LENGTH=15, PICOUT='+ZZZ,ZZZ,ZZZ.99', JUSTIFY=RIGHT */}
          {/* ACSHLIM: POS=(7,61), LENGTH=15, PICOUT='+ZZZ,ZZZ,ZZZ.99', JUSTIFY=RIGHT */}
          <Grid item xs={12} md={6}>
            <TextField
              label="Credit Limit"
              value={formatCurrency(account.creditLimit)}
              disabled
              fullWidth
              variant="outlined"
              InputProps={{
                readOnly: true,
                style: { textAlign: 'right' }
              }}
              helperText="Maximum credit available (COMP-3 precision S9(10)V99)"
            />
          </Grid>
          
          <Grid item xs={12} md={6}>
            <TextField
              label="Cash Credit Limit"
              value={formatCurrency(account.cashCreditLimit)}
              disabled
              fullWidth
              variant="outlined"
              InputProps={{
                readOnly: true,
                style: { textAlign: 'right' }
              }}
              helperText="Cash advance limit"
            />
          </Grid>
          
          {/* Current Balance Row */}
          {/* ACURBAL: POS=(8,61), LENGTH=15, PICOUT='+ZZZ,ZZZ,ZZZ.99', JUSTIFY=RIGHT */}
          <Grid item xs={12} md={6}>
            <TextField
              label="Current Balance"
              value={formatCurrency(account.currentBalance)}
              disabled
              fullWidth
              variant="outlined"
              InputProps={{
                readOnly: true,
                style: { textAlign: 'right' }
              }}
              helperText="Outstanding balance"
            />
          </Grid>
          
          {/* Account Group */}
          {/* AADDGRP: POS=(10,23), LENGTH=10 */}
          <Grid item xs={12} md={6}>
            <TextField
              label="Account Group"
              value={account.accountGroup || ''}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
            />
          </Grid>
          
          {/* Current Cycle Credit and Debit Row */}
          {/* ACRCYCR: POS=(9,61), LENGTH=15, PICOUT='+ZZZ,ZZZ,ZZZ.99', JUSTIFY=RIGHT */}
          {/* ACRCYDB: POS=(10,61), LENGTH=15, PICOUT='+ZZZ,ZZZ,ZZZ.99', JUSTIFY=RIGHT */}
          <Grid item xs={12} md={6}>
            <TextField
              label="Current Cycle Credit"
              value={formatCurrency(account.currentCycleCredit)}
              disabled
              fullWidth
              variant="outlined"
              InputProps={{
                readOnly: true,
                style: { textAlign: 'right' }
              }}
              helperText="Credits in current billing cycle"
            />
          </Grid>
          
          <Grid item xs={12} md={6}>
            <TextField
              label="Current Cycle Debit"
              value={formatCurrency(account.currentCycleDebit)}
              disabled
              fullWidth
              variant="outlined"
              InputProps={{
                readOnly: true,
                style: { textAlign: 'right' }
              }}
              helperText="Debits in current billing cycle"
            />
          </Grid>
        </Grid>
        
        {/* ================================================================== */}
        {/* CUSTOMER DETAILS SECTION */}
        {/* Maps BMS INITIAL='Customer Details' at POS=(11,32) */}
        {/* Maps BMS fields from lines 203-355 (customer information) */}
        {/* ================================================================== */}
        
        <Divider sx={{ my: 4 }} />
        
        <Typography variant="h5" component="h2" gutterBottom sx={{ mb: 3 }}>
          Customer Details
        </Typography>
        
        <Grid container spacing={3}>
          {/* Customer ID, SSN, Date of Birth, FICO Score Row */}
          {/* ACSTNUM: POS=(12,23), LENGTH=9 - Customer ID */}
          {/* ACSTSSN: POS=(12,54), LENGTH=12 - SSN */}
          {/* ACSTDOB: POS=(13,23), LENGTH=10 - Date of Birth */}
          {/* ACSTFCO: POS=(13,61), LENGTH=3 - FICO Score */}
          <Grid item xs={12} md={3}>
            <TextField
              label="Customer ID"
              value={account.customer?.customerId || ''}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
              helperText="9-digit customer identifier"
            />
          </Grid>
          
          <Grid item xs={12} md={3}>
            <TextField
              label="SSN"
              value={formatSSN(account.customer?.ssn, true)}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
              helperText="Masked for security (XXX-XX-XXXX)"
            />
          </Grid>
          
          <Grid item xs={12} md={3}>
            <TextField
              label="Date of Birth"
              value={formatDateDisplay(account.customer?.dateOfBirth)}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
            />
          </Grid>
          
          <Grid item xs={12} md={3}>
            <TextField
              label="FICO Score"
              value={account.customer?.ficoScore || ''}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
              helperText="Credit score (300-850)"
            />
          </Grid>
          
          {/* Customer Name Fields Row */}
          {/* ACSFNAM: POS=(15,1), LENGTH=25 - First Name */}
          {/* ACSMNAM: POS=(15,28), LENGTH=25 - Middle Name */}
          {/* ACSLNAM: POS=(15,55), LENGTH=25 - Last Name */}
          <Grid item xs={12} md={4}>
            <TextField
              label="First Name"
              value={account.customer?.firstName || ''}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
            />
          </Grid>
          
          <Grid item xs={12} md={4}>
            <TextField
              label="Middle Name"
              value={account.customer?.middleName || ''}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
            />
          </Grid>
          
          <Grid item xs={12} md={4}>
            <TextField
              label="Last Name"
              value={account.customer?.lastName || ''}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
            />
          </Grid>
          
          {/* Address Fields */}
          {/* ACSADL1: POS=(16,10), LENGTH=50 - Address Line 1 */}
          {/* ACSADL2: POS=(17,10), LENGTH=50 - Address Line 2 */}
          <Grid item xs={12}>
            <TextField
              label="Address Line 1"
              value={account.customer?.addressLine1 || ''}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
            />
          </Grid>
          
          <Grid item xs={12}>
            <TextField
              label="Address Line 2"
              value={account.customer?.addressLine2 || ''}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
            />
          </Grid>
          
          {/* City, State, Zip Code, Country Row */}
          {/* ACSCITY: POS=(18,10), LENGTH=50 - City */}
          {/* ACSSTTE: POS=(16,73), LENGTH=2 - State */}
          {/* ACSZIPC: POS=(17,73), LENGTH=5, JUSTIFY=RIGHT - Zip Code */}
          {/* ACSCTRY: POS=(18,73), LENGTH=3 - Country */}
          <Grid item xs={12} md={6}>
            <TextField
              label="City"
              value={account.customer?.city || ''}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
            />
          </Grid>
          
          <Grid item xs={12} md={2}>
            <TextField
              label="State"
              value={account.customer?.state || ''}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
            />
          </Grid>
          
          <Grid item xs={12} md={2}>
            <TextField
              label="Zip Code"
              value={account.customer?.zipCode || ''}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
            />
          </Grid>
          
          <Grid item xs={12} md={2}>
            <TextField
              label="Country"
              value={account.customer?.countryCode || ''}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
              helperText="3-letter code"
            />
          </Grid>
          
          {/* Phone Numbers and Government ID Row */}
          {/* ACSPHN1: POS=(19,10), LENGTH=13 - Phone 1 */}
          {/* ACSPHN2: POS=(20,10), LENGTH=13 - Phone 2 */}
          {/* ACSGOVT: POS=(19,58), LENGTH=20 - Government ID */}
          <Grid item xs={12} md={4}>
            <TextField
              label="Phone 1"
              value={formatPhoneNumber(account.customer?.phone1)}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
              helperText="Primary phone number"
            />
          </Grid>
          
          <Grid item xs={12} md={4}>
            <TextField
              label="Phone 2"
              value={formatPhoneNumber(account.customer?.phone2)}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
              helperText="Secondary phone number"
            />
          </Grid>
          
          <Grid item xs={12} md={4}>
            <TextField
              label="Government Issued ID Ref"
              value={account.customer?.governmentId || ''}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
              helperText="Driver's license or state ID"
            />
          </Grid>
          
          {/* EFT Account ID and Primary Cardholder Row */}
          {/* ACSEFTC: POS=(20,41), LENGTH=10 - EFT Account ID */}
          {/* ACSPFLG: POS=(20,78), LENGTH=1 - Primary Card Holder Y/N */}
          <Grid item xs={12} md={6}>
            <TextField
              label="EFT Account ID"
              value={account.customer?.eftAccountId || ''}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
              helperText="Electronic funds transfer account"
            />
          </Grid>
          
          <Grid item xs={12} md={6}>
            <TextField
              label="Primary Card Holder Y/N"
              value={account.customer?.primaryCardholder || ''}
              disabled
              variant="outlined"
              fullWidth
              InputProps={{
                readOnly: true,
              }}
            />
          </Grid>
        </Grid>
        
        {/* ================================================================== */}
        {/* ACTION BUTTONS SECTION */}
        {/* Maps BMS PF key functions and navigation */}
        {/* F3=Exit → Back button */}
        {/* Edit function → Edit button (navigates to COACTUPC program) */}
        {/* ================================================================== */}
        
        <Divider sx={{ my: 4 }} />
        
        <Box sx={{ display: 'flex', gap: 2, justifyContent: 'flex-end' }}>
          <Button
            variant="outlined"
            color="primary"
            onClick={handleBack}
            size="large"
          >
            Back
          </Button>
          
          <Button
            variant="contained"
            color="primary"
            onClick={handleEdit}
            size="large"
          >
            Edit Account
          </Button>
        </Box>
        
        {/* ================================================================== */}
        {/* INFORMATIONAL MESSAGE AREA */}
        {/* Maps BMS INFOMSG field: POS=(22,23), LENGTH=45, ATTRB=PROT */}
        {/* Used for displaying non-error informational messages */}
        {/* ================================================================== */}
        
        {account && (
          <Box sx={{ mt: 3, textAlign: 'center' }}>
            <Typography variant="body2" color="text.secondary">
              Displaying details of account {account.accountId}
            </Typography>
          </Box>
        )}
      </Paper>
    </Box>
  );
};

// Export component as default export per schema requirements
export default AccountViewComponent;
