/**
 * Header Component - BMS 3270 Screen Header Modernization
 * 
 * Purpose: React Header component replicating BMS 3270 screen header layout 
 * (lines 1-3) displaying application metadata and context. Provides consistent 
 * header UX across all screens matching the BMS screen layout while using 
 * Material-UI components for modern web styling.
 * 
 * Original COBOL Sources:
 * - app/bms/COSGN00M.bms: Lines 29-93 (Header field definitions)
 * - app/bms/COMEN01M.bms: Lines 29-74 (Header field definitions)
 * - app/cpy/COTTL01Y.cpy: Lines 17-24 (Screen title constants)
 * - app/cpy/COCOM01Y.cpy: Lines 19-31 (COMMAREA user context structure)
 * 
 * BMS Layout Mapping (80-column terminal screen):
 * Line 1:
 * - POS=(1,1): "Tran :" label (BLUE)
 * - POS=(1,8): TRNNAME field (4 chars, BLUE) - Transaction identifier
 * - POS=(1,21): TITLE01 field (40 chars, YELLOW) - Main application title
 * - POS=(1,64): "Date :" label (BLUE)
 * - POS=(1,71): CURDATE field (8 chars, mm/dd/yy format, BLUE)
 * 
 * Line 2:
 * - POS=(2,1): "Prog :" label (BLUE)
 * - POS=(2,8): PGMNAME field (8 chars, BLUE) - Program name
 * - POS=(2,21): TITLE02 field (40 chars, YELLOW) - Application name
 * - POS=(2,64): "Time :" label (BLUE)
 * - POS=(2,71): CURTIME field (8 chars, hh:mm:ss format, BLUE)
 * 
 * Line 3:
 * - User identification information (user ID, user type)
 * 
 * React Transformation Strategy:
 * - Material-UI AppBar for modern header styling
 * - Grid layout for responsive 80-column equivalent positioning
 * - Real-time clock updating every second via useEffect
 * - Date formatting matching BMS MM/DD/YY pattern
 * - User context integration via useAuth hook from AuthContext
 * - Color theming matching BMS BLUE/YELLOW attribute colors
 * 
 * Migration Context:
 * This component maintains functional equivalence with BMS header fields
 * while modernizing to React + Material-UI architecture as specified in
 * Agent Action Plan sections 0.1, 0.3, 0.6, and 0.10.
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import React, { useState, useEffect } from 'react';
import PropTypes from 'prop-types';
import { Grid } from '@mui/material';
import { useAuth } from '../../context/AuthContext.jsx';
import { 
  TIME_FORMAT, 
  SCREEN_TITLE_MAIN, 
  SCREEN_TITLE_APP,
  DATE_FORMAT_SHORT,
  USER_TYPE
} from '../../utils/constants.js';

/**
 * Format date to BMS format (MM/DD/YY)
 * 
 * Matches COBOL date formatting from CSDAT01Y.cpy WS-CURDATE-MM-DD-YY pattern.
 * BMS screen displays date as "mm/dd/yy" at position (1,71).
 * 
 * COBOL Equivalent:
 * - CSDAT01Y.cpy lines 30-35: WS-CURDATE-MM-DD-YY structure
 * - MOVE WS-CURDATE-MM TO CURDATE-MM
 * - MOVE WS-CURDATE-DD TO CURDATE-DD
 * - MOVE WS-CURDATE-YY TO CURDATE-YY
 * 
 * @param {Date} date - Date object to format
 * @returns {string} Formatted date string in MM/DD/YY format
 */
const formatDateBMS = (date) => {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  const year = String(date.getFullYear()).slice(-2); // Last 2 digits of year
  
  return `${month}/${day}/${year}`;
};

/**
 * Format time to BMS format (HH:MM:SS)
 * 
 * Matches COBOL time formatting from CSDAT01Y.cpy WS-CURTIME-HH-MM-SS pattern.
 * BMS screen displays time as "hh:mm:ss" at position (2,71).
 * 
 * COBOL Equivalent:
 * - CSDAT01Y.cpy lines 36-41: WS-CURTIME-HH-MM-SS structure
 * - MOVE WS-CURTIME-HH TO CURTIME-HH
 * - MOVE WS-CURTIME-MM TO CURTIME-MM
 * - MOVE WS-CURTIME-SS TO CURTIME-SS
 * 
 * @param {Date} date - Date object to format
 * @returns {string} Formatted time string in HH:MM:SS format (24-hour)
 */
const formatTimeBMS = (date) => {
  const hours = String(date.getHours()).padStart(2, '0');
  const minutes = String(date.getMinutes()).padStart(2, '0');
  const seconds = String(date.getSeconds()).padStart(2, '0');
  
  return `${hours}:${minutes}:${seconds}`;
};

/**
 * Header Component
 * 
 * Displays BMS-style header information with real-time clock and user context.
 * 
 * Props:
 * - transactionName: Transaction identifier (TRNNAME from BMS, 4 chars max)
 * - programName: Program name (PGMNAME from BMS, 8 chars max)
 * 
 * State Management:
 * - currentTime: Date object updated every second for real-time clock display
 * 
 * Context Integration:
 * - useAuth(): Accesses user authentication context for userId, userType, firstName, lastName
 * 
 * Lifecycle:
 * - useEffect: Sets up 1-second interval timer for clock updates
 * - Cleanup: Clears interval on component unmount to prevent memory leaks
 * 
 * @param {Object} props - Component props
 * @param {string} props.transactionName - Transaction identifier (TRNNAME)
 * @param {string} props.programName - Program name (PGMNAME)
 * @returns {JSX.Element} Header component with BMS-style layout
 */
const Header = ({ transactionName, programName }) => {
  // ====================================================================
  // State Management
  // ====================================================================
  
  /**
   * Current time state for real-time clock display
   * Updated every second via useEffect timer
   * 
   * COBOL Equivalent:
   * - EXEC CICS ASKTIME ABSTIME(WS-ABSTIME) END-EXEC
   * - EXEC CICS FORMATTIME ABSTIME(WS-ABSTIME) TIME(WS-TIME) END-EXEC
   */
  const [currentTime, setCurrentTime] = useState(new Date());
  
  // ====================================================================
  // Context Integration
  // ====================================================================
  
  /**
   * Authentication context providing user session data
   * 
   * Maps to COBOL COMMAREA structure from COCOM01Y.cpy:
   * - user.userId → CDEMO-USER-ID (PIC X(08))
   * - user.userType → CDEMO-USER-TYPE (PIC X(01))
   *   - 'A' = Admin (88 CDEMO-USRTYP-ADMIN VALUE 'A')
   *   - 'U' = User (88 CDEMO-USRTYP-USER VALUE 'U')
   * - user.firstName → CDEMO-CUST-FNAME (PIC X(25))
   * - user.lastName → CDEMO-CUST-LNAME (PIC X(25))
   */
  const { user, isAuthenticated } = useAuth();
  
  // ====================================================================
  // Real-Time Clock Effect
  // ====================================================================
  
  /**
   * Set up interval timer for real-time clock updates
   * 
   * Updates currentTime state every 1000ms (1 second) to display
   * current time in header matching BMS CURTIME field behavior.
   * 
   * COBOL Equivalent:
   * - Continuous EXEC CICS ASKTIME calls in CICS transaction loop
   * - BMS map field CURTIME automatically updated by CICS on each screen send
   * 
   * Cleanup:
   * - Clears interval on component unmount to prevent memory leaks
   */
  useEffect(() => {
    // Update clock immediately on mount
    setCurrentTime(new Date());
    
    // Set up 1-second interval for clock updates
    const intervalId = setInterval(() => {
      setCurrentTime(new Date());
    }, 1000);
    
    // Cleanup: Clear interval on component unmount
    return () => {
      clearInterval(intervalId);
    };
  }, []); // Empty dependency array - run once on mount
  
  // ====================================================================
  // Computed Display Values
  // ====================================================================
  
  /**
   * Format current date for display (MM/DD/YY)
   * Matches BMS CURDATE field at position (1,71)
   */
  const formattedDate = formatDateBMS(currentTime);
  
  /**
   * Format current time for display (HH:MM:SS)
   * Matches BMS CURTIME field at position (2,71)
   */
  const formattedTime = formatTimeBMS(currentTime);
  
  /**
   * User type display label
   * Maps COBOL 88-level conditions to user-friendly labels
   * 
   * COBOL Equivalent:
   * - IF CDEMO-USRTYP-ADMIN (88-level VALUE 'A')
   * -   DISPLAY "Admin"
   * - ELSE IF CDEMO-USRTYP-USER (88-level VALUE 'U')
   * -   DISPLAY "User"
   */
  const userTypeLabel = user && user.userType === USER_TYPE.ADMIN ? 'Admin' : 'User';
  
  /**
   * Full user name for display
   * Concatenates firstName and lastName from COMMAREA
   * 
   * COBOL Equivalent:
   * - STRING CDEMO-CUST-FNAME DELIMITED BY SPACE
   * -        " " DELIMITED BY SIZE
   * -        CDEMO-CUST-LNAME DELIMITED BY SPACE
   * -        INTO WS-FULL-NAME
   * - END-STRING
   */
  const fullUserName = user 
    ? `${user.firstName || ''} ${user.lastName || ''}`.trim() 
    : '';
  
  // ====================================================================
  // Render Header Component
  // ====================================================================
  
  return (
    <header
      style={{
        backgroundColor: '#1e3a5f', // Dark blue matching BMS BLUE attribute
        color: '#ffffff',
        padding: '12px 24px',
        fontFamily: 'Courier New, monospace', // Monospace font matching 3270 terminal
        fontSize: '14px',
        borderBottom: '2px solid #ffd700', // Gold border matching BMS YELLOW
      }}
    >
      {/* 
        BMS Line 1: Transaction info, Title, Date
        Positions: (1,1) - (1,80)
      */}
      <Grid 
        container 
        spacing={2} 
        alignItems="center"
        style={{ marginBottom: '4px' }}
      >
        {/* Left section: Transaction Name (BMS POS=(1,1) to (1,12)) */}
        <Grid item xs={12} sm={3}>
          <span style={{ color: '#87CEEB' }}> {/* Light blue for labels */}
            Tran : 
          </span>
          <span 
            style={{ 
              color: '#87CEEB',
              fontWeight: 'bold',
              marginLeft: '4px'
            }}
          >
            {transactionName || 'N/A'}
          </span>
        </Grid>
        
        {/* Center section: Main Title (BMS POS=(1,21) - TITLE01, 40 chars) */}
        <Grid item xs={12} sm={6} style={{ textAlign: 'center' }}>
          <span 
            style={{ 
              color: '#ffd700', // Yellow matching BMS YELLOW attribute
              fontWeight: 'bold',
              fontSize: '16px'
            }}
          >
            {SCREEN_TITLE_MAIN}
          </span>
        </Grid>
        
        {/* Right section: Current Date (BMS POS=(1,64) - CURDATE) */}
        <Grid item xs={12} sm={3} style={{ textAlign: 'right' }}>
          <span style={{ color: '#87CEEB' }}>
            Date : 
          </span>
          <span 
            style={{ 
              color: '#87CEEB',
              fontWeight: 'bold',
              marginLeft: '4px'
            }}
          >
            {formattedDate}
          </span>
        </Grid>
      </Grid>
      
      {/* 
        BMS Line 2: Program info, Application Title, Time
        Positions: (2,1) - (2,80)
      */}
      <Grid 
        container 
        spacing={2} 
        alignItems="center"
        style={{ marginBottom: '4px' }}
      >
        {/* Left section: Program Name (BMS POS=(2,1) to (2,16)) */}
        <Grid item xs={12} sm={3}>
          <span style={{ color: '#87CEEB' }}>
            Prog : 
          </span>
          <span 
            style={{ 
              color: '#87CEEB',
              fontWeight: 'bold',
              marginLeft: '4px'
            }}
          >
            {programName || 'N/A'}
          </span>
        </Grid>
        
        {/* Center section: Application Name (BMS POS=(2,21) - TITLE02, 40 chars) */}
        <Grid item xs={12} sm={6} style={{ textAlign: 'center' }}>
          <span 
            style={{ 
              color: '#ffd700', // Yellow matching BMS YELLOW attribute
              fontWeight: 'bold',
              fontSize: '16px'
            }}
          >
            {SCREEN_TITLE_APP}
          </span>
        </Grid>
        
        {/* Right section: Current Time (BMS POS=(2,64) - CURTIME) */}
        <Grid item xs={12} sm={3} style={{ textAlign: 'right' }}>
          <span style={{ color: '#87CEEB' }}>
            Time : 
          </span>
          <span 
            style={{ 
              color: '#87CEEB',
              fontWeight: 'bold',
              marginLeft: '4px'
            }}
          >
            {formattedTime}
          </span>
        </Grid>
      </Grid>
      
      {/* 
        BMS Line 3: User Information
        Positions: (3,1) - (3,80)
        Displays user ID, full name, and user type from COMMAREA
      */}
      {isAuthenticated && user && (
        <Grid 
          container 
          spacing={2} 
          alignItems="center"
        >
          {/* User identification section */}
          <Grid item xs={12}>
            <span style={{ color: '#87CEEB' }}>
              User : 
            </span>
            <span 
              style={{ 
                color: '#ffffff',
                fontWeight: 'bold',
                marginLeft: '4px'
              }}
            >
              {user.userId || 'N/A'}
            </span>
            
            {fullUserName && (
              <>
                <span style={{ color: '#87CEEB', marginLeft: '16px' }}>
                  Name : 
                </span>
                <span 
                  style={{ 
                    color: '#ffffff',
                    fontWeight: 'bold',
                    marginLeft: '4px'
                  }}
                >
                  {fullUserName}
                </span>
              </>
            )}
            
            <span style={{ color: '#87CEEB', marginLeft: '16px' }}>
              Type : 
            </span>
            <span 
              style={{ 
                color: user.userType === USER_TYPE.ADMIN ? '#ffd700' : '#ffffff',
                fontWeight: 'bold',
                marginLeft: '4px'
              }}
            >
              {userTypeLabel}
            </span>
          </Grid>
        </Grid>
      )}
    </header>
  );
};

/**
 * PropTypes Validation
 * 
 * Defines expected prop types and requirements for Header component.
 * Provides runtime validation in development mode.
 */
Header.propTypes = {
  /**
   * Transaction identifier
   * Maps to BMS TRNNAME field (4 characters max)
   * Example: "CC00", "CM00", "CAVW", "CT01"
   */
  transactionName: PropTypes.string,
  
  /**
   * Program name
   * Maps to BMS PGMNAME field (8 characters max)
   * Example: "COSGN00C", "COMEN01C", "COACTVWC"
   */
  programName: PropTypes.string,
};

/**
 * Default Props
 * 
 * Provides default values for optional props when not specified.
 * Ensures component renders without errors when props are omitted.
 */
Header.defaultProps = {
  transactionName: '',
  programName: '',
};

/**
 * Export Header Component
 * 
 * Default export for use in other components:
 * 
 * import Header from './components/common/Header';
 * 
 * <Header 
 *   transactionName="CM00" 
 *   programName="COMEN01C" 
 * />
 */
export default Header;
