/**
 * Account View Component
 * 
 * Purpose: React functional component for displaying account details in read-only mode.
 * This component transforms the BMS 3270 terminal screen COACTVWM.bms to a modern
 * React-based web interface while preserving the original field layout, data display
 * patterns, and navigation behavior.
 * 
 * Source BMS Screen: app/bms/COACTVW.bms
 * - Screen size: 24 lines x 80 columns
 * - Fields: All ASKIP (protected/read-only) attributes
 * - Navigation: PF3=Exit (Back button)
 * - Colors: TURQUOISE for labels, various colors for data fields
 * - Sections: Account Details (lines 4-10), Customer Details (lines 11-20)
 * 
 * Source COBOL Program: COACTVWC.cbl
 * - Transaction: CAVW (Account View)
 * - Reads ACCTDAT VSAM file for account data
 * - Reads CARDXREF for customer association
 * - Reads CUSTDAT for customer information
 * - Returns formatted data to BMS screen
 * 
 * Source Data Structure: app/cpy/CVACT01Y.cpy
 * - ACCT-ID: PIC 9(11) - 11 digit account identifier
 * - ACCT-ACTIVE-STATUS: PIC X(01) - Active status (Y/N)
 * - ACCT-CURR-BAL: PIC S9(10)V99 - Current balance (BigDecimal)
 * - ACCT-CREDIT-LIMIT: PIC S9(10)V99 - Credit limit (BigDecimal)
 * - ACCT-CASH-CREDIT-LIMIT: PIC S9(10)V99 - Cash credit limit (BigDecimal)
 * - ACCT-OPEN-DATE: PIC X(10) - Account open date
 * - ACCT-EXPIRAION-DATE: PIC X(10) - Account expiration date
 * - ACCT-REISSUE-DATE: PIC X(10) - Account reissue date
 * - ACCT-CURR-CYC-CREDIT: PIC S9(10)V99 - Current cycle credit
 * - ACCT-CURR-CYC-DEBIT: PIC S9(10)V99 - Current cycle debit
 * - ACCT-ADDR-ZIP: PIC X(10) - Address ZIP code
 * - ACCT-GROUP-ID: PIC X(10) - Account group identifier
 * 
 * REST API Integration:
 * - Endpoint: GET /api/accounts/{id}
 * - Service: AccountViewService.java
 * - Response: AccountResponse DTO with all fields including customer information
 * 
 * Transformation Rules Applied:
 * 1. BMS DFHMDF fields → React read-only form fields
 * 2. ASKIP attribute → disabled inputs or static text displays
 * 3. PF3 key → Back button with React Router navigation
 * 4. BMS field positioning → Responsive CSS Grid layout
 * 5. Monetary fields PICOUT='+ZZZ,ZZZ,ZZZ.99' → JavaScript currency formatting
 * 6. Date fields → ISO 8601 to MM/DD/YYYY display format
 * 7. BMS COLOR attributes → CSS classes for visual styling
 * 8. ERRMSG field → Error message display area
 * 
 * Agent Action Plan References:
 * - Section 0.1: User interface conversion (BMS to React)
 * - Section 0.6: File transformation (COACTVWM.bms → AccountViewComponent.jsx)
 * - Section 0.10: Functional preservation and data precision requirements
 * 
 * @module AccountViewComponent
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Divider } from '@mui/material';
import PropTypes from 'prop-types';
import { getAccountById } from '../../services/accountService.js';
import { MSG_SERVER_ERROR, DATE_FORMAT_DISPLAY } from '../../utils/constants.js';

/**
 * Formats a date string from ISO 8601 (YYYY-MM-DD) to display format (MM/DD/YYYY)
 * 
 * Transformation Context: Converts backend date format to user-friendly display format
 * matching the BMS screen date display pattern.
 * 
 * @param {string} dateString - Date in YYYY-MM-DD format from backend
 * @returns {string} Formatted date in MM/DD/YYYY format or empty string if invalid
 * @private
 */
const formatDate = (dateString) => {
  if (!dateString || dateString.trim() === '') {
    return '';
  }

  // Handle ISO 8601 format (YYYY-MM-DD)
  const parts = dateString.split('-');
  if (parts.length === 3) {
    const [year, month, day] = parts;
    return `${month}/${day}/${year}`;
  }

  // Return as-is if already in expected format or invalid
  return dateString;
};

/**
 * Formats a monetary value for display with proper currency formatting
 * 
 * Transformation Context: Matches BMS PICOUT='+ZZZ,ZZZ,ZZZ.99' format for monetary fields.
 * Ensures BigDecimal precision from backend is displayed correctly.
 * 
 * Agent Action Plan Section 0.10: "COBOL COMP-3 to Java BigDecimal Precision Mapping"
 * - All monetary fields formatted to 2 decimal places
 * - Negative values shown with minus sign
 * - Thousands separator for readability
 * 
 * @param {string|number} value - Monetary value from backend (formatted to 2 decimals)
 * @returns {string} Formatted currency string (e.g., "$1,234.56" or "-$1,234.56")
 * @private
 */
const formatCurrency = (value) => {
  if (value === null || value === undefined || value === '') {
    return '$0.00';
  }

  // Convert to number if string
  const numericValue = typeof value === 'string' ? parseFloat(value) : value;

  // Check for invalid number
  if (isNaN(numericValue)) {
    return '$0.00';
  }

  // Determine if negative
  const isNegative = numericValue < 0;
  const absoluteValue = Math.abs(numericValue);

  // Format with thousands separator and 2 decimal places
  const formatted = absoluteValue.toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });

  // Return with currency symbol and proper sign
  return isNegative ? `-$${formatted}` : `$${formatted}`;
};

/**
 * AccountViewComponent
 * 
 * Main functional component for displaying account details in read-only mode.
 * Replicates the BMS screen COACTVWM layout and functionality using modern React patterns.
 * 
 * Component State:
 * - account: Object containing all account and customer data
 * - loading: Boolean indicating data fetch in progress
 * - error: String containing error message if fetch fails
 * 
 * Component Lifecycle:
 * 1. Extract accountId from URL parameters (useParams)
 * 2. Fetch account data on component mount (useEffect)
 * 3. Display loading state during fetch
 * 4. Display account data or error message based on fetch result
 * 5. Handle back navigation on button click (useNavigate)
 * 
 * URL Route: /account/view/:accountId
 * 
 * Navigation:
 * - Back button: Returns to previous page (replaces PF3 key)
 * 
 * @component
 * @returns {JSX.Element} Rendered account view component
 */
const AccountViewComponent = () => {
  // Extract accountId from URL route parameters
  // Matches BMS field ACCTSID (Account Number input)
  const { accountId } = useParams();

  // React Router navigation hook for back button (PF3 replacement)
  const navigate = useNavigate();

  // Component state management
  const [account, setAccount] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  /**
   * Fetches account data from backend API
   * 
   * Transformation Context: Replaces COBOL COACTVWC.cbl program execution:
   * - EXEC CICS READ DATASET(ACCTDAT) → getAccountById() REST call
   * - EXEC CICS READ DATASET(CARDXREF) → Backend JPA join
   * - EXEC CICS READ DATASET(CUSTDAT) → Backend JPA join
   * 
   * Error Handling:
   * - Network errors: Display generic server error message
   * - Validation errors: Display specific validation message
   * - Not found errors: Display resource not found message
   * - All errors logged in development mode
   * 
   * @async
   * @function
   * @returns {Promise<void>}
   */
  const fetchAccountData = useCallback(async () => {
    try {
      setLoading(true);
      setError('');

      // Call account service to fetch data from backend
      // Service method uses getAccountById from accountService.js
      const accountData = await getAccountById(accountId);

      // Update state with fetched account data
      setAccount(accountData);
    } catch (err) {
      // Handle error with user-friendly message
      // Display specific error message or generic server error
      const errorMessage = err.message || MSG_SERVER_ERROR;
      setError(errorMessage);

      // Log error details in development mode
      if (import.meta.env.MODE === 'development') {
        console.error('[AccountViewComponent] Error fetching account:', accountId, err);
      }
    } finally {
      setLoading(false);
    }
  }, [accountId]);

  /**
   * Effect hook to fetch account data on component mount
   * 
   * Runs once when component mounts and whenever accountId changes.
   * Matches COBOL program initialization and data retrieval on transaction start.
   */
  useEffect(() => {
    if (accountId) {
      fetchAccountData();
    } else {
      setError('Account ID not provided');
      setLoading(false);
    }
  }, [accountId, fetchAccountData]);

  /**
   * Handles back button click
   * 
   * Transformation Context: Replaces BMS PF3=Exit key functionality
   * Navigates back to previous page using React Router history
   * 
   * @function
   */
  const handleBack = useCallback(() => {
    navigate(-1);
  }, [navigate]);

  /**
   * Renders loading state
   * 
   * Displays loading message while data is being fetched from backend.
   * Matches CICS "Processing..." wait state during VSAM file reads.
   */
  if (loading) {
    return (
      <div style={styles.container}>
        <div style={styles.header}>
          <h2 style={styles.title}>View Account</h2>
        </div>
        <div style={styles.loadingContainer}>
          <p style={styles.loadingText}>Loading account details...</p>
        </div>
      </div>
    );
  }

  /**
   * Renders error state
   * 
   * Displays error message matching BMS ERRMSG field (line 23, red color).
   * Shows specific error message from backend or generic server error.
   */
  if (error) {
    return (
      <div style={styles.container}>
        <div style={styles.header}>
          <h2 style={styles.title}>View Account</h2>
        </div>
        <div style={styles.errorContainer}>
          <p style={styles.errorMessage}>{error}</p>
        </div>
        <div style={styles.footer}>
          <button onClick={handleBack} style={styles.backButton}>
            ← Back (F3)
          </button>
        </div>
      </div>
    );
  }

  /**
   * Renders account data display
   * 
   * Main render logic displaying all account and customer fields in read-only format.
   * Layout matches BMS screen structure with Account Details and Customer Details sections.
   */
  return (
    <div style={styles.container}>
      {/* Header matching BMS lines 1-3 */}
      <div style={styles.header}>
        <div style={styles.headerRow}>
          <span style={styles.headerLabel}>Tran: CAVW</span>
          <h2 style={styles.title}>View Account</h2>
          <span style={styles.headerLabel}>
            Date: {new Date().toLocaleDateString('en-US')}
          </span>
        </div>
        <div style={styles.headerRow}>
          <span style={styles.headerLabel}>Prog: COACTVWC</span>
          <span style={styles.headerLabel}>
            Time: {new Date().toLocaleTimeString('en-US')}
          </span>
        </div>
      </div>

      {/* Account Details Section - BMS lines 4-10 */}
      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Account Details</h3>

        <div style={styles.formGrid}>
          {/* Account Number - BMS ACCTSID field, line 5 */}
          <div style={styles.fieldRow}>
            <label style={styles.fieldLabel}>Account Number:</label>
            <span style={styles.fieldValue}>{account?.accountId || ''}</span>

            <label style={styles.fieldLabel}>Active Y/N:</label>
            <span style={styles.fieldValue}>{account?.accountStatus || 'N'}</span>
          </div>

          {/* Open Date and Credit Limit - BMS ADTOPEN and ACRDLIM fields, line 6 */}
          <div style={styles.fieldRow}>
            <label style={styles.fieldLabel}>Opened:</label>
            <span style={styles.fieldValue}>{formatDate(account?.openDate)}</span>

            <label style={styles.fieldLabel}>Credit Limit:</label>
            <span style={styles.fieldValueCurrency}>
              {formatCurrency(account?.creditLimit)}
            </span>
          </div>

          {/* Expiry Date and Cash Credit Limit - BMS AEXPDT and ACSHLIM fields, line 7 */}
          <div style={styles.fieldRow}>
            <label style={styles.fieldLabel}>Expiry:</label>
            <span style={styles.fieldValue}>{formatDate(account?.expirationDate)}</span>

            <label style={styles.fieldLabel}>Cash Credit Limit:</label>
            <span style={styles.fieldValueCurrency}>
              {formatCurrency(account?.cashCreditLimit)}
            </span>
          </div>

          {/* Reissue Date and Current Balance - BMS AREISDT and ACURBAL fields, line 8 */}
          <div style={styles.fieldRow}>
            <label style={styles.fieldLabel}>Reissue:</label>
            <span style={styles.fieldValue}>{formatDate(account?.reissueDate)}</span>

            <label style={styles.fieldLabel}>Current Balance:</label>
            <span style={styles.fieldValueCurrency}>
              {formatCurrency(account?.currentBalance)}
            </span>
          </div>

          {/* Current Cycle Credit - BMS ACRCYCR field, line 9 */}
          <div style={styles.fieldRow}>
            <label style={styles.fieldLabel}></label>
            <span style={styles.fieldValue}></span>

            <label style={styles.fieldLabel}>Current Cycle Credit:</label>
            <span style={styles.fieldValueCurrency}>
              {formatCurrency(account?.currentCycleCredit)}
            </span>
          </div>

          {/* Account Group and Current Cycle Debit - BMS AADDGRP and ACRCYDB fields, line 10 */}
          <div style={styles.fieldRow}>
            <label style={styles.fieldLabel}>Account Group:</label>
            <span style={styles.fieldValue}>{account?.groupId || ''}</span>

            <label style={styles.fieldLabel}>Current Cycle Debit:</label>
            <span style={styles.fieldValueCurrency}>
              {formatCurrency(account?.currentCycleDebit)}
            </span>
          </div>
        </div>
      </div>

      {/* Divider between sections matching BMS visual separation at line 11 */}
      <Divider style={styles.divider} />

      {/* Customer Details Section - BMS lines 11-20 */}
      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Customer Details</h3>

        <div style={styles.formGrid}>
          {/* Customer ID and SSN - BMS ACSTNUM and ACSTSSN fields, line 12 */}
          <div style={styles.fieldRow}>
            <label style={styles.fieldLabel}>Customer ID:</label>
            <span style={styles.fieldValue}>{account?.customerId || ''}</span>

            <label style={styles.fieldLabel}>SSN:</label>
            <span style={styles.fieldValue}>{account?.customerSsn || ''}</span>
          </div>

          {/* Date of Birth and FICO Score - BMS ACSTDOB and ACSTFCO fields, line 13 */}
          <div style={styles.fieldRow}>
            <label style={styles.fieldLabel}>Date of Birth:</label>
            <span style={styles.fieldValue}>{formatDate(account?.customerDob)}</span>

            <label style={styles.fieldLabel}>FICO Score:</label>
            <span style={styles.fieldValue}>{account?.customerFicoScore || ''}</span>
          </div>

          {/* Name Fields - BMS ACSFNAM, ACSMNAM, ACSLNAM fields, lines 14-15 */}
          <div style={styles.fieldRow}>
            <label style={styles.fieldLabel}>First Name:</label>
            <span style={styles.fieldValue}>{account?.customerFirstName || ''}</span>

            <label style={styles.fieldLabel}>Middle Name:</label>
            <span style={styles.fieldValue}>{account?.customerMiddleName || ''}</span>
          </div>

          <div style={styles.fieldRow}>
            <label style={styles.fieldLabel}>Last Name:</label>
            <span style={styles.fieldValueWide}>{account?.customerLastName || ''}</span>
          </div>

          {/* Address Line 1 and State - BMS ACSADL1 and ACSSTTE fields, line 16 */}
          <div style={styles.fieldRow}>
            <label style={styles.fieldLabel}>Address:</label>
            <span style={styles.fieldValueWide}>{account?.customerAddressLine1 || ''}</span>

            <label style={styles.fieldLabel}>State:</label>
            <span style={styles.fieldValue}>{account?.customerState || ''}</span>
          </div>

          {/* Address Line 2 and ZIP - BMS ACSADL2 and ACSZIPC fields, line 17 */}
          <div style={styles.fieldRow}>
            <label style={styles.fieldLabel}></label>
            <span style={styles.fieldValueWide}>{account?.customerAddressLine2 || ''}</span>

            <label style={styles.fieldLabel}>Zip:</label>
            <span style={styles.fieldValue}>{account?.customerZip || ''}</span>
          </div>

          {/* City and Country - BMS ACSCITY and ACSCTRY fields, line 18 */}
          <div style={styles.fieldRow}>
            <label style={styles.fieldLabel}>City:</label>
            <span style={styles.fieldValueWide}>{account?.customerCity || ''}</span>

            <label style={styles.fieldLabel}>Country:</label>
            <span style={styles.fieldValue}>{account?.customerCountry || ''}</span>
          </div>

          {/* Phone 1 and Government ID - BMS ACSPHN1 and ACSGOVT fields, line 19 */}
          <div style={styles.fieldRow}>
            <label style={styles.fieldLabel}>Phone 1:</label>
            <span style={styles.fieldValue}>{account?.customerPhone1 || ''}</span>

            <label style={styles.fieldLabel}>Government Issued Id Ref:</label>
            <span style={styles.fieldValue}>{account?.customerGovtId || ''}</span>
          </div>

          {/* Phone 2, EFT Account, and Primary Flag - BMS ACSPHN2, ACSEFTC, ACSPFLG fields, line 20 */}
          <div style={styles.fieldRow}>
            <label style={styles.fieldLabel}>Phone 2:</label>
            <span style={styles.fieldValue}>{account?.customerPhone2 || ''}</span>

            <label style={styles.fieldLabel}>EFT Account Id:</label>
            <span style={styles.fieldValue}>{account?.customerEftAccountId || ''}</span>
          </div>

          <div style={styles.fieldRow}>
            <label style={styles.fieldLabel}>Primary Card Holder Y/N:</label>
            <span style={styles.fieldValue}>{account?.customerPrimaryCardholderFlag || 'N'}</span>
          </div>
        </div>
      </div>

      {/* Footer with navigation - BMS line 24 */}
      <div style={styles.footer}>
        <button onClick={handleBack} style={styles.backButton}>
          ← Back (F3)
        </button>
      </div>
    </div>
  );
};

/**
 * PropTypes validation
 * 
 * Currently no props expected as component reads accountId from URL parameters.
 * PropTypes included for future extensibility if props are needed.
 */
AccountViewComponent.propTypes = {
  // No props expected - component uses URL parameters
};

/**
 * Inline styles matching BMS screen layout and color attributes
 * 
 * Transformation Context: Converts BMS COLOR, HILIGHT, and positioning attributes
 * to CSS styles for modern web display while maintaining visual structure.
 * 
 * BMS Color Mapping:
 * - BLUE → #1976d2 (header labels)
 * - YELLOW → #ffc107 (titles)
 * - TURQUOISE → #00bcd4 (field labels)
 * - GREEN → #4caf50 (input field data)
 * - RED → #f44336 (error messages)
 * - NEUTRAL → #000000 (standard text)
 */
const styles = {
  container: {
    fontFamily: 'monospace',
    maxWidth: '1200px',
    margin: '0 auto',
    padding: '20px',
    backgroundColor: '#f5f5f5',
    minHeight: '100vh'
  },
  header: {
    marginBottom: '20px',
    padding: '10px',
    backgroundColor: '#ffffff',
    borderRadius: '4px',
    boxShadow: '0 2px 4px rgba(0,0,0,0.1)'
  },
  headerRow: {
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: '5px'
  },
  headerLabel: {
    color: '#1976d2',
    fontSize: '12px',
    fontWeight: 'bold'
  },
  title: {
    color: '#ffc107',
    fontSize: '20px',
    fontWeight: 'bold',
    margin: '0',
    textAlign: 'center',
    flexGrow: 1
  },
  section: {
    backgroundColor: '#ffffff',
    padding: '20px',
    marginBottom: '20px',
    borderRadius: '4px',
    boxShadow: '0 2px 4px rgba(0,0,0,0.1)'
  },
  sectionTitle: {
    color: '#00bcd4',
    fontSize: '16px',
    fontWeight: 'bold',
    marginBottom: '15px',
    borderBottom: '2px solid #00bcd4',
    paddingBottom: '5px'
  },
  formGrid: {
    display: 'flex',
    flexDirection: 'column',
    gap: '10px'
  },
  fieldRow: {
    display: 'grid',
    gridTemplateColumns: 'auto 1fr auto 1fr',
    gap: '10px',
    alignItems: 'center',
    padding: '5px 0'
  },
  fieldLabel: {
    color: '#00bcd4',
    fontSize: '14px',
    fontWeight: 'bold',
    textAlign: 'right',
    paddingRight: '10px',
    whiteSpace: 'nowrap'
  },
  fieldValue: {
    color: '#4caf50',
    fontSize: '14px',
    padding: '5px 10px',
    backgroundColor: '#f9f9f9',
    border: '1px solid #e0e0e0',
    borderRadius: '2px',
    minHeight: '24px'
  },
  fieldValueCurrency: {
    color: '#4caf50',
    fontSize: '14px',
    padding: '5px 10px',
    backgroundColor: '#f9f9f9',
    border: '1px solid #e0e0e0',
    borderRadius: '2px',
    minHeight: '24px',
    textAlign: 'right',
    fontWeight: 'bold'
  },
  fieldValueWide: {
    color: '#4caf50',
    fontSize: '14px',
    padding: '5px 10px',
    backgroundColor: '#f9f9f9',
    border: '1px solid #e0e0e0',
    borderRadius: '2px',
    minHeight: '24px',
    gridColumn: 'span 3'
  },
  divider: {
    margin: '20px 0',
    borderColor: '#00bcd4'
  },
  footer: {
    backgroundColor: '#ffffff',
    padding: '15px',
    borderRadius: '4px',
    boxShadow: '0 2px 4px rgba(0,0,0,0.1)',
    display: 'flex',
    justifyContent: 'flex-start'
  },
  backButton: {
    backgroundColor: '#1976d2',
    color: '#ffffff',
    border: 'none',
    padding: '10px 20px',
    fontSize: '14px',
    fontWeight: 'bold',
    borderRadius: '4px',
    cursor: 'pointer',
    transition: 'background-color 0.3s ease',
    boxShadow: '0 2px 4px rgba(0,0,0,0.2)'
  },
  loadingContainer: {
    backgroundColor: '#ffffff',
    padding: '40px',
    borderRadius: '4px',
    boxShadow: '0 2px 4px rgba(0,0,0,0.1)',
    textAlign: 'center'
  },
  loadingText: {
    color: '#1976d2',
    fontSize: '16px',
    fontWeight: 'bold'
  },
  errorContainer: {
    backgroundColor: '#ffffff',
    padding: '20px',
    marginBottom: '20px',
    borderRadius: '4px',
    boxShadow: '0 2px 4px rgba(0,0,0,0.1)'
  },
  errorMessage: {
    color: '#f44336',
    fontSize: '14px',
    fontWeight: 'bold',
    margin: '0',
    padding: '10px',
    backgroundColor: '#ffebee',
    border: '1px solid #f44336',
    borderRadius: '4px'
  }
};

// Default export as specified in exports schema
export default AccountViewComponent;
