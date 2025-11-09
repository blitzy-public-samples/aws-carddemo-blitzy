/**
 * Admin Menu Component - BMS COADM01 Screen Modernization
 * 
 * Purpose: React functional component displaying administrative menu for admin users
 * with 4 security-related navigation options (User List, User Add, User Update, User Delete).
 * Implements role-based access control enforcing admin-only access with automatic redirect
 * if non-admin attempts access.
 * 
 * Original COBOL Sources:
 * - app/bms/COADM01.bms: Lines 1-168 (Admin menu BMS mapset definition)
 * - app/cbl/COADM01C.cbl: Lines 1-260 (Admin menu COBOL program logic)
 * - app/cpy/COADM02Y.cpy: Lines 19-52 (Admin menu options data structure)
 * 
 * BMS Layout Mapping (80-column terminal screen):
 * Line 1-3: Header with transaction (CA00), program (COADM01C), date, time
 * Line 4: "Admin Menu" title centered at POS=(4,35)
 * Lines 6-9: Four admin menu options (OPTN001O through OPTN004O)
 *   - Option 1: User List (Security) at POS=(6,20)
 *   - Option 2: User Add (Security) at POS=(7,20)
 *   - Option 3: User Update (Security) at POS=(8,20)
 *   - Option 4: User Delete (Security) at POS=(9,20)
 * Line 20: "Please select an option :" prompt at POS=(20,15)
 * Line 20: OPTION input field (2 chars, numeric, right-justified) at POS=(20,41)
 * Line 23: ERRMSG error message field (red, 78 chars) at POS=(23,1)
 * Line 24: Footer "ENTER=Continue  F3=Exit" at POS=(24,1)
 * 
 * COBOL Program Flow (COADM01C.cbl):
 * - MAIN-PARA: Entry point checking COMMAREA, handles reentry logic
 * - PROCESS-ENTER-KEY: Validates option selection, transfers control to selected program
 * - POPULATE-HEADER-INFO: Populates header fields with transaction, program, date, time
 * - BUILD-MENU-OPTIONS: Constructs menu option text from COADM02Y.cpy data
 * - SEND-MENU-SCREEN: Sends BMS map to terminal
 * - RECEIVE-MENU-SCREEN: Receives user input from terminal
 * - RETURN-TO-SIGNON-SCREEN: Handles PF3 exit to sign-on screen
 * 
 * Admin Menu Options (COADM02Y.cpy):
 * - CDEMO-ADMIN-OPT-COUNT: 4 options total
 * - Each option contains: NUM (option number 1-4), NAME (35 chars), PGMNAME (8 chars)
 * - Option 1: User List (Security) → COUSR00C program
 * - Option 2: User Add (Security) → COUSR01C program
 * - Option 3: User Update (Security) → COUSR02C program
 * - Option 4: User Delete (Security) → COUSR03C program
 * 
 * React Transformation Strategy:
 * - Replace EXEC CICS SEND MAP with component render
 * - Replace EXEC CICS RECEIVE MAP with form onSubmit handler
 * - Replace COMMAREA state with AuthContext (useAuth hook)
 * - Replace CICS XCTL with React Router useNavigate
 * - Replace COBOL EVALUATE with JavaScript switch statement
 * - Replace BMS field validation with form validation and error state
 * - Map DFHPF3 (PF3 key) to Exit button with onClick handler
 * - Preserve CDEMO-USRTYP-ADMIN role check for admin-only access
 * - Display error messages in red matching ERRMSG field COLOR=RED attribute
 * 
 * Security Implementation:
 * - Enforce admin-only access using isAdmin check from useAuth
 * - Redirect non-admin users to main menu or login
 * - Match COBOL CDEMO-USRTYP-ADMIN ('A') role checking logic
 * - Preserve original security model from RACF implementation
 * 
 * Migration Context:
 * This component maintains functional equivalence with COADM01C.cbl COBOL program
 * and COADM01.bms BMS mapset while modernizing to React architecture as specified
 * in Agent Action Plan sections 0.1, 0.3, 0.6, and 0.10.
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { CircularProgress } from '@mui/material';
import { useAuth } from '../../context/AuthContext.jsx';
import Header from '../common/Header.jsx';

/**
 * Admin Menu Options Configuration
 * 
 * Source: app/cpy/COADM02Y.cpy lines 19-48
 * 
 * COBOL Structure:
 * - CDEMO-ADMIN-OPT-COUNT: PIC 9(02) VALUE 4
 * - CDEMO-ADMIN-OPT: OCCURS 9 TIMES
 *   - CDEMO-ADMIN-OPT-NUM: PIC 9(02) (option number)
 *   - CDEMO-ADMIN-OPT-NAME: PIC X(35) (option display name)
 *   - CDEMO-ADMIN-OPT-PGMNAME: PIC X(08) (target COBOL program name)
 * 
 * React Mapping:
 * - Array of objects with num, name, route, pgm properties
 * - route: React Router path replacing COBOL XCTL program transfer
 * - pgm: Original COBOL program name for reference/documentation
 */
const ADMIN_MENU_OPTIONS = [
  {
    num: 1,
    name: 'User List (Security)',
    route: '/admin/users',
    pgm: 'COUSR00C',
  },
  {
    num: 2,
    name: 'User Add (Security)',
    route: '/admin/users/add',
    pgm: 'COUSR01C',
  },
  {
    num: 3,
    name: 'User Update (Security)',
    route: '/admin/users/update',
    pgm: 'COUSR02C',
  },
  {
    num: 4,
    name: 'User Delete (Security)',
    route: '/admin/users/delete',
    pgm: 'COUSR03C',
  },
];

/**
 * Admin Menu Component
 * 
 * Functional component implementing the administrative menu screen for admin users.
 * Replaces COADM01C.cbl COBOL program with React state management and event handling.
 * 
 * State Management:
 * - selectedOption: String storing the user's selected option number (1-4)
 * - errorMessage: String storing validation or access error messages
 * - loading: Boolean indicating role verification or API data fetch in progress
 * 
 * COBOL Working Storage Equivalents:
 * - WS-OPTION → selectedOption state variable
 * - WS-MESSAGE → errorMessage state variable
 * - WS-ERR-FLG → implicit in errorMessage presence
 * 
 * Effects:
 * - Admin Role Verification: useEffect checks isAdmin on mount, redirects if false
 * - Initial Load: useEffect initializes component state and prepares menu display
 * 
 * Event Handlers:
 * - handleOptionChange: Updates selectedOption state on input field change
 * - handleSubmit: Validates selection, navigates to selected admin route
 * - handleExit: Navigates back to main menu (replaces DFHPF3 key handling)
 * 
 * @returns {React.Element} Admin menu screen component with role-based access control
 */
function AdminMenuComponent() {
  // =========================================================================
  // HOOKS AND STATE MANAGEMENT
  // =========================================================================
  
  /**
   * Authentication Context
   * 
   * Source: useAuth hook from AuthContext.jsx
   * Replaces COBOL COMMAREA fields:
   * - CDEMO-USER-ID → user.userId
   * - CDEMO-USER-TYPE → user.userType
   * - CDEMO-USRTYP-ADMIN ('A') → isAdmin boolean
   * 
   * Members accessed:
   * - isAuthenticated: Boolean indicating if user is logged in
   * - isAdmin: Boolean indicating if user has admin privileges (userType === 'A')
   * - user: Object containing userId, userType, firstName, lastName
   */
  const { isAuthenticated, isAdmin, user } = useAuth();
  
  /**
   * React Router Navigation Hook
   * 
   * Replaces COBOL CICS XCTL program control transfer.
   * Used for:
   * - Navigating to selected admin option route
   * - Redirecting non-admin users to main menu or login
   * - Handling PF3=Exit navigation back to main menu
   */
  const navigate = useNavigate();
  
  /**
   * Component State: Selected Option
   * 
   * COBOL Equivalent: WS-OPTION PIC 9(02) VALUE 0
   * 
   * Stores the user's selected menu option number (1-4).
   * Updated by handleOptionChange when user types in option field.
   * Validated in handleSubmit before navigation.
   */
  const [selectedOption, setSelectedOption] = useState('');
  
  /**
   * Component State: Error Message
   * 
   * COBOL Equivalent: WS-MESSAGE PIC X(80) VALUE SPACES
   * 
   * Stores error messages for display in red error message area.
   * Maps to ERRMSG field (COLOR=RED) in BMS mapset at POS=(23,1).
   * 
   * Common error messages:
   * - 'Please enter a valid option number...' (invalid option selection)
   * - 'You must be an admin to access this page.' (role verification failure)
   */
  const [errorMessage, setErrorMessage] = useState('');
  
  /**
   * Component State: Loading
   * 
   * Indicates asynchronous operation in progress:
   * - Role verification check
   * - API data fetch (if admin menu options loaded from backend)
   * - Navigation in progress
   * 
   * When true, displays CircularProgress loading indicator.
   * Prevents user interaction until loading complete.
   */
  const [loading, setLoading] = useState(true);

  // =========================================================================
  // EFFECTS: ROLE VERIFICATION AND INITIALIZATION
  // =========================================================================
  
  /**
   * Effect: Admin Role Verification and Redirect
   * 
   * COBOL Equivalent:
   * - Checking CDEMO-USER-TYPE = 'A' (CDEMO-USRTYP-ADMIN)
   * - Performing RETURN-TO-SIGNON-SCREEN if not admin
   * 
   * Flow:
   * 1. Check if user is authenticated
   * 2. If not authenticated, redirect to login (/login)
   * 3. If authenticated but not admin, set error and redirect to main menu (/)
   * 4. If admin, set loading to false and allow access
   * 
   * This effect enforces admin-only access control matching original
   * COBOL security model where CDEMO-USRTYP-ADMIN condition must be true.
   * 
   * Dependencies: [isAuthenticated, isAdmin, navigate]
   * - Runs on mount and when authentication state changes
   */
  useEffect(() => {
    // Check authentication status first
    if (!isAuthenticated) {
      // User not authenticated - redirect to login
      setErrorMessage('Please login to access this page.');
      setLoading(false);
      navigate('/login', { replace: true });
      return;
    }

    // User is authenticated - verify admin role
    if (!isAdmin) {
      // User is not admin - deny access and redirect to main menu
      // Matches COBOL CDEMO-USRTYP-ADMIN condition check
      setErrorMessage('You must be an admin to access this page.');
      setLoading(false);
      
      // Small delay to allow error message display before redirect
      setTimeout(() => {
        navigate('/', { replace: true });
      }, 1500);
      return;
    }

    // User is authenticated and is admin - grant access
    setLoading(false);
    
    // Clear any previous error messages
    setErrorMessage('');

    if (import.meta.env.MODE === 'development') {
      console.log('[AdminMenuComponent] Admin access granted:', {
        userId: user?.userId,
        userType: user?.userType,
        isAdmin: isAdmin,
      });
    }
  }, [isAuthenticated, isAdmin, navigate, user]);

  // =========================================================================
  // EVENT HANDLERS
  // =========================================================================
  
  /**
   * Handle Option Input Field Change
   * 
   * COBOL Equivalent:
   * - RECEIVE-MENU-SCREEN paragraph receiving OPTIONI field
   * - MOVE OPTIONI OF COADM1AI TO WS-OPTION-X
   * 
   * Updates selectedOption state as user types in the option field.
   * Allows only numeric input (1-4) to match COBOL PIC 9(02) field definition.
   * 
   * @param {React.ChangeEvent<HTMLInputElement>} event - Input change event
   */
  const handleOptionChange = (event) => {
    const value = event.target.value;
    
    // Allow only numeric input (empty string or digits)
    // Matches COBOL OPTION field DFHMDF ATTRB=(NUM)
    if (value === '' || /^\d{1,2}$/.test(value)) {
      setSelectedOption(value);
      // Clear error message when user starts typing
      if (errorMessage) {
        setErrorMessage('');
      }
    }
  };

  /**
   * Handle Form Submission (ENTER Key)
   * 
   * COBOL Equivalent: PROCESS-ENTER-KEY paragraph
   * 
   * Flow:
   * 1. Validate option is numeric and in range (1-4)
   * 2. If invalid, set error message and return
   * 3. If valid, navigate to selected admin option route
   * 
   * COBOL Logic (lines 115-155):
   * - WS-OPTION IS NOT NUMERIC → error
   * - WS-OPTION > CDEMO-ADMIN-OPT-COUNT → error
   * - WS-OPTION = ZEROS → error
   * - Valid option → XCTL to CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION)
   * 
   * React Implementation:
   * - Validates option using parseInt and range check
   * - Sets error message for invalid input
   * - Navigates to route using React Router instead of CICS XCTL
   * 
   * @param {React.FormEvent<HTMLFormElement>} event - Form submit event
   */
  const handleSubmit = (event) => {
    event.preventDefault(); // Prevent default form submission
    
    // Clear any existing error messages
    setErrorMessage('');
    
    // Parse option as integer
    const optionNum = parseInt(selectedOption, 10);
    
    // Validate option input
    // COBOL: IF WS-OPTION IS NOT NUMERIC OR
    //        WS-OPTION > CDEMO-ADMIN-OPT-COUNT OR
    //        WS-OPTION = ZEROS
    if (
      isNaN(optionNum) ||
      optionNum < 1 ||
      optionNum > ADMIN_MENU_OPTIONS.length
    ) {
      // Invalid option - set error message
      // COBOL: MOVE 'Please enter a valid option number...' TO WS-MESSAGE
      setErrorMessage('Please enter a valid option number...');
      return;
    }
    
    // Find selected menu option
    const selectedMenuOption = ADMIN_MENU_OPTIONS.find(
      (option) => option.num === optionNum
    );
    
    if (selectedMenuOption) {
      // Valid option - navigate to selected route
      // Replaces COBOL: EXEC CICS XCTL PROGRAM(CDEMO-ADMIN-OPT-PGMNAME(WS-OPTION))
      
      if (import.meta.env.MODE === 'development') {
        console.log('[AdminMenuComponent] Navigating to option:', {
          num: selectedMenuOption.num,
          name: selectedMenuOption.name,
          route: selectedMenuOption.route,
          originalProgram: selectedMenuOption.pgm,
        });
      }
      
      navigate(selectedMenuOption.route);
    } else {
      // Should not reach here due to validation above, but handle gracefully
      setErrorMessage('Selected option is not available.');
    }
  };

  /**
   * Handle Exit Button Click (PF3 Key)
   * 
   * COBOL Equivalent:
   * - EVALUATE EIBAID
   * -   WHEN DFHPF3
   * -     MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
   * -     PERFORM RETURN-TO-SIGNON-SCREEN
   * 
   * Navigates back to main menu when user clicks Exit button.
   * Replaces DFHPF3 (PF3 function key) handling from CICS.
   * 
   * BMS: Line 162 "ENTER=Continue  F3=Exit" footer text
   */
  const handleExit = () => {
    if (import.meta.env.MODE === 'development') {
      console.log('[AdminMenuComponent] Exit button clicked - navigating to main menu');
    }
    
    // Navigate to main menu (replaces RETURN-TO-SIGNON-SCREEN)
    // In React app, main menu is at root path '/'
    navigate('/');
  };

  // =========================================================================
  // RENDER: LOADING STATE
  // =========================================================================
  
  /**
   * Loading State Display
   * 
   * Shows CircularProgress spinner while:
   * - Verifying admin role
   * - Fetching menu data from API
   * - Preparing component for display
   * 
   * Prevents premature display of admin menu before access verification.
   */
  if (loading) {
    return (
      <div
        style={{
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          minHeight: '100vh',
          backgroundColor: '#1e1e1e',
        }}
      >
        <CircularProgress
          size={60}
          style={{ color: '#4a9eff' }}
          aria-label="Loading admin menu"
        />
      </div>
    );
  }

  // =========================================================================
  // RENDER: ADMIN MENU SCREEN
  // =========================================================================
  
  /**
   * Main Admin Menu Screen Render
   * 
   * Layout Structure:
   * - Header component (transaction: CA00, program: COADM01C)
   * - Admin Menu title (centered)
   * - Four menu options in vertical list
   * - Option selection input field
   * - Error message display area (red, conditional)
   * - Footer with ENTER=Continue and F3=Exit instructions
   * 
   * Styling:
   * - Dark background (#1e1e1e) matching BMS terminal appearance
   * - Blue text for labels and options
   * - Yellow/gold highlights for important text
   * - Red error messages matching BMS ERRMSG COLOR=RED
   * - Monospace font for terminal-like appearance
   */
  return (
    <div
      style={{
        backgroundColor: '#1e1e1e',
        minHeight: '100vh',
        color: '#ffffff',
        fontFamily: 'Courier New, monospace',
        padding: '0',
        margin: '0',
      }}
    >
      {/* 
        Header Component
        
        Displays:
        - Transaction: CA00 (COBOL: WS-TRANID PIC X(04) VALUE 'CA00')
        - Program: COADM01C (COBOL: WS-PGMNAME PIC X(08) VALUE 'COADM01C')
        - Date: Current date in MM/DD/YY format
        - Time: Current time in HH:MM:SS format
        - Application titles from COTTL01Y.cpy
        
        Replaces BMS header fields at lines 1-3 (POS 1-80)
      */}
      <Header transaction="CA00" program="COADM01C" />

      {/*
        Main Content Container
        
        Replicates BMS 24x80 screen layout from line 4 onwards
      */}
      <div style={{ padding: '20px 40px' }}>
        
        {/*
          Admin Menu Title
          
          BMS: Line 4, POS=(4,35)
          DFHMDF ATTRB=(ASKIP,BRT), COLOR=NEUTRAL, LENGTH=10,
                 INITIAL='Admin Menu'
          
          Centered title displayed in bright/bold style
        */}
        <div
          style={{
            textAlign: 'center',
            fontSize: '20px',
            fontWeight: 'bold',
            marginBottom: '30px',
            marginTop: '20px',
            color: '#ffffff',
            letterSpacing: '2px',
          }}
        >
          Admin Menu
        </div>

        {/*
          Admin Menu Options List
          
          BMS: Lines 6-9 (OPTN001O through OPTN004O)
          Each option at POS=(6-9, 20), COLOR=BLUE, LENGTH=40
          
          Displays 4 admin menu options from COADM02Y.cpy:
          - User List (Security)
          - User Add (Security)
          - User Update (Security)
          - User Delete (Security)
          
          COBOL BUILD-MENU-OPTIONS paragraph (lines 226-260):
          - STRING CDEMO-ADMIN-OPT-NUM(WS-IDX) DELIMITED BY SIZE
          -        '. ' DELIMITED BY SIZE
          -        CDEMO-ADMIN-OPT-NAME(WS-IDX) DELIMITED BY SIZE
          -   INTO WS-ADMIN-OPT-TXT
        */}
        <div
          style={{
            marginBottom: '40px',
            marginLeft: '80px',
          }}
        >
          {ADMIN_MENU_OPTIONS.map((option) => (
            <div
              key={option.num}
              style={{
                color: '#4a9eff',
                fontSize: '16px',
                marginBottom: '8px',
                fontFamily: 'Courier New, monospace',
              }}
            >
              {option.num}. {option.name}
            </div>
          ))}
        </div>

        {/*
          Option Selection Form
          
          BMS: Line 20
          - POS=(20,15): "Please select an option :" label
          - POS=(20,41): OPTION input field (2 chars, numeric)
          
          COBOL PROCESS-ENTER-KEY validation (lines 115-155)
        */}
        <form onSubmit={handleSubmit} style={{ marginTop: '40px' }}>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              marginBottom: '20px',
              marginLeft: '60px',
            }}
          >
            {/*
              Option Selection Label
              
              BMS: DFHMDF ATTRB=(ASKIP,BRT), COLOR=TURQUOISE,
                         LENGTH=25, POS=(20,15),
                         INITIAL='Please select an option :'
            */}
            <label
              htmlFor="option-input"
              style={{
                color: '#00d4d4',
                fontSize: '16px',
                marginRight: '10px',
                fontWeight: 'bold',
              }}
            >
              Please select an option :
            </label>
            
            {/*
              Option Input Field
              
              BMS: OPTION DFHMDF ATTRB=(FSET,IC,NORM,NUM,UNPROT),
                                HILIGHT=UNDERLINE,
                                JUSTIFY=(RIGHT,ZERO),
                                LENGTH=2,
                                POS=(20,41)
              
              COBOL: WS-OPTION PIC 9(02) VALUE 0
              
              Attributes:
              - IC: Initial cursor position (autoFocus)
              - NUM: Numeric only input
              - UNPROT: Unprotected (user can type)
              - HILIGHT=UNDERLINE: Underlined style
              - LENGTH=2: Max 2 characters
              - JUSTIFY=RIGHT: Right-aligned text
            */}
            <input
              id="option-input"
              type="text"
              value={selectedOption}
              onChange={handleOptionChange}
              maxLength={2}
              autoFocus
              style={{
                width: '40px',
                padding: '5px',
                fontSize: '16px',
                fontFamily: 'Courier New, monospace',
                backgroundColor: '#2a2a2a',
                color: '#ffffff',
                border: 'none',
                borderBottom: '2px solid #4a9eff',
                textAlign: 'right',
                outline: 'none',
              }}
              aria-label="Menu option number"
              aria-describedby="option-error"
            />
          </div>

          {/*
            Error Message Display Area
            
            BMS: Line 23, POS=(23,1)
            ERRMSG DFHMDF ATTRB=(ASKIP,BRT,FSET),
                          COLOR=RED,
                          LENGTH=78,
                          POS=(23,1)
            
            COBOL: WS-MESSAGE PIC X(80) VALUE SPACES
            
            Conditionally rendered when errorMessage has value.
            Displays validation errors and access denial messages in red.
          */}
          {errorMessage && (
            <div
              id="option-error"
              role="alert"
              aria-live="assertive"
              style={{
                color: '#ff4444',
                fontSize: '14px',
                marginTop: '20px',
                marginBottom: '20px',
                marginLeft: '20px',
                fontWeight: 'bold',
              }}
            >
              {errorMessage}
            </div>
          )}

          {/*
            Footer Navigation Instructions
            
            BMS: Line 24, POS=(24,1)
            DFHMDF ATTRB=(ASKIP,NORM),
                   COLOR=YELLOW,
                   LENGTH=23,
                   POS=(24,1),
                   INITIAL='ENTER=Continue  F3=Exit'
            
            Displays available navigation options:
            - ENTER: Submit form and navigate to selected option
            - F3: Exit back to main menu
          */}
          <div
            style={{
              marginTop: '40px',
              marginLeft: '20px',
              display: 'flex',
              gap: '20px',
            }}
          >
            {/*
              Continue Button (ENTER Key)
              
              COBOL: WHEN DFHENTER
              -        PERFORM PROCESS-ENTER-KEY
              
              Submits form, validates option, navigates to selected route
            */}
            <button
              type="submit"
              style={{
                padding: '10px 20px',
                fontSize: '14px',
                fontFamily: 'Courier New, monospace',
                backgroundColor: '#4a9eff',
                color: '#ffffff',
                border: 'none',
                cursor: 'pointer',
                fontWeight: 'bold',
                borderRadius: '4px',
              }}
              onMouseOver={(e) => {
                e.target.style.backgroundColor = '#3a8eef';
              }}
              onMouseOut={(e) => {
                e.target.style.backgroundColor = '#4a9eff';
              }}
            >
              ENTER = Continue
            </button>
            
            {/*
              Exit Button (F3 Key)
              
              COBOL: WHEN DFHPF3
              -        MOVE 'COSGN00C' TO CDEMO-TO-PROGRAM
              -        PERFORM RETURN-TO-SIGNON-SCREEN
              
              Navigates back to main menu
            */}
            <button
              type="button"
              onClick={handleExit}
              style={{
                padding: '10px 20px',
                fontSize: '14px',
                fontFamily: 'Courier New, monospace',
                backgroundColor: '#666666',
                color: '#ffffff',
                border: 'none',
                cursor: 'pointer',
                fontWeight: 'bold',
                borderRadius: '4px',
              }}
              onMouseOver={(e) => {
                e.target.style.backgroundColor = '#555555';
              }}
              onMouseOut={(e) => {
                e.target.style.backgroundColor = '#666666';
              }}
            >
              F3 = Exit
            </button>
          </div>
        </form>

        {/*
          Development Mode Debugging Information
          
          Displays current state values for debugging during development.
          Hidden in production builds.
        */}
        {import.meta.env.MODE === 'development' && (
          <div
            style={{
              marginTop: '40px',
              padding: '10px',
              backgroundColor: '#2a2a2a',
              borderLeft: '4px solid #4a9eff',
              fontSize: '12px',
              color: '#cccccc',
            }}
          >
            <div style={{ fontWeight: 'bold', marginBottom: '5px' }}>
              Debug Info (Development Only):
            </div>
            <div>User ID: {user?.userId || 'N/A'}</div>
            <div>User Type: {user?.userType || 'N/A'}</div>
            <div>Is Admin: {isAdmin ? 'Yes' : 'No'}</div>
            <div>Selected Option: {selectedOption || 'None'}</div>
            <div>Error Message: {errorMessage || 'None'}</div>
            <div>Total Options: {ADMIN_MENU_OPTIONS.length}</div>
          </div>
        )}
      </div>
    </div>
  );
}

// PropTypes definition for component prop validation (currently no props)
AdminMenuComponent.propTypes = {};

// Default export as specified in exports schema
export default AdminMenuComponent;
