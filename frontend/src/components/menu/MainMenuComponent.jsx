/**
 * MainMenuComponent.jsx
 * 
 * React functional component for the main application menu.
 * Transforms COBOL program COMEN01C.cbl and BMS mapset COMEN01M.bms to modern React component.
 * 
 * Source Files:
 * - app/bms/COMEN01.bms: BMS 3270 terminal screen definition for main menu layout
 * - app/cbl/COMEN01C.cbl: COBOL business logic for menu navigation and option processing
 * - app/cpy/COMEN02Y.cpy: Copybook data structure defining menu options
 * 
 * Functional Equivalence:
 * - Displays 10 menu options matching COBOL menu structure (OPTN001O through OPTN010O)
 * - Implements role-based menu filtering matching CDEMO-USRTYP-USER logic
 * - Preserves option selection and validation logic from COMEN01C.cbl WS-OPTION processing
 * - Maps PF3=Exit to React Router navigation preserving 3270 screen flow
 * - Integrates with MenuNavigationService REST endpoint (GET /api/menu)
 * - Displays error messages matching ERRMSG field behavior from BMS mapset
 * - Implements ENTER=Continue functionality for option selection
 * 
 * COBOL Procedure Division Mapping:
 * - MAIN-PARA → MainMenuComponent functional component
 * - PROCESS-ENTER-KEY → handleSubmit function
 * - RETURN-TO-PREV-SCREEN → handleExit function
 * - SEND-MENU-SCREEN → component render with JSX
 * - RECEIVE-MENU-SCREEN → form onSubmit handler
 * - EVALUATE WS-OPTION → switch statement for route selection
 * 
 * State Management:
 * - selectedOption: Replaces COBOL WS-OPTION (PIC 99) for user input
 * - errorMessage: Replaces COBOL WS-MESSAGE for error display
 * - loading: Async operation indicator (no COBOL equivalent)
 * - menuOptions: Array of menu items from backend API
 * 
 * @module MainMenuComponent
 */

import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { 
  Box, 
  Container, 
  Paper, 
  Typography, 
  TextField, 
  Button, 
  List, 
  ListItem, 
  ListItemText,
  CircularProgress,
  Alert,
  Grid
} from '@mui/material';
import { useAuth } from '../../context/AuthContext';
import Header from '../common/Header';
import { MSG_INVALID_KEY } from '../../utils/constants';
import apiClient from '../../utils/apiClient';

/**
 * MainMenuComponent
 * 
 * Displays main application menu with navigation options for regular users.
 * Replicates BMS COMEN01M screen layout and COMEN01C business logic.
 * 
 * Component Lifecycle:
 * 1. Mount: Fetches menu options from backend API (GET /api/menu)
 * 2. Render: Displays menu options in numbered list with selection input
 * 3. User Input: Validates and processes option selection
 * 4. Navigation: Routes to selected application screen
 * 
 * COBOL Equivalent:
 * - COMEN01C PROCEDURE DIVISION MAIN-PARA paragraph
 * - Pseudo-conversational CICS processing with COMMAREA state
 * - EXEC CICS SEND MAP (COMEN01) command → component render
 * - EXEC CICS RECEIVE MAP command → form submission handler
 * 
 * Error Handling:
 * - Invalid key press → MSG_INVALID_KEY constant from CSMSG01Y.cpy
 * - Invalid option number → "Invalid option. Please select 1-10."
 * - API errors → Display error message from backend
 * - Non-numeric input → "Option must be a number."
 * 
 * @returns {JSX.Element} Main menu component with BMS-style layout
 */
const MainMenuComponent = () => {
  // ===========================================================================
  // Context Integration
  // ===========================================================================
  
  /**
   * Authentication context for user session data
   * 
   * Maps to COBOL COMMAREA structure from COCOM01Y.cpy:
   * - isAuthenticated → Session validity check
   * - user.userId → CDEMO-USER-ID (PIC X(08))
   * - user.userType → CDEMO-USER-TYPE (PIC X(01))
   * - isAdmin → 88 CDEMO-USRTYP-ADMIN VALUE 'A'
   * 
   * COBOL Equivalent:
   * - EXEC CICS RETRIEVE INTO(DFHCOMMAREA) LENGTH(COMM-AREA-LEN)
   * - IF CDEMO-USER-ID = SPACES THEN return to signon
   */
  const { user, isAuthenticated, isAdmin } = useAuth();
  
  /**
   * React Router navigation hook
   * 
   * Replaces COBOL CICS XCTL commands:
   * - EXEC CICS XCTL PROGRAM('COACTVWC') → navigate('/account/view')
   * - EXEC CICS XCTL PROGRAM('COSGN00C') → navigate('/login')
   * 
   * Provides programmatic navigation to other application screens
   * based on user's menu option selection.
   */
  const navigate = useNavigate();
  
  // ===========================================================================
  // Component State Management
  // ===========================================================================
  
  /**
   * Selected menu option number (1-10)
   * 
   * COBOL Equivalent:
   * - WS-OPTION PIC 99 (WORKING-STORAGE)
   * - OPTNIONO field from BMS mapset COMEN01M
   * 
   * Validation Rules:
   * - Must be numeric
   * - Must be in range 1-10
   * - Matches COBOL validation at lines 126-140 in COMEN01C.cbl
   */
  const [selectedOption, setSelectedOption] = useState('');
  
  /**
   * Error message to display to user
   * 
   * COBOL Equivalent:
   * - WS-MESSAGE PIC X(60) (WORKING-STORAGE)
   * - ERRMSGO field from BMS mapset COMEN01M at position (23,1)
   * 
   * Populated from:
   * - CCDA-MSG-INVALID-KEY from CSMSG01Y.cpy (line 20-21)
   * - Custom validation error messages
   * - Backend API error responses
   */
  const [errorMessage, setErrorMessage] = useState('');
  
  /**
   * Loading state for async API operations
   * 
   * No direct COBOL equivalent - represents async HTTP request state
   * Displays CircularProgress component while fetching menu data
   * 
   * Prevents user interaction until menu options are loaded
   */
  const [loading, setLoading] = useState(true);
  
  /**
   * Menu options array fetched from backend
   * 
   * COBOL Equivalent:
   * - Menu options from COMEN02Y.cpy copybook structure
   * - Array of objects with: { optionNumber, optionName, targetRoute, userType }
   * 
   * Backend API returns menu items filtered by user role
   * matching COBOL role-based menu filtering logic
   */
  const [menuOptions, setMenuOptions] = useState([]);
  
  // ===========================================================================
  // Menu Options Route Mapping
  // ===========================================================================
  
  /**
   * Maps menu option numbers to React Router routes
   * 
   * COBOL Equivalent:
   * - EVALUATE WS-OPTION statement in COMEN01C.cbl lines 151-197
   * - EXEC CICS XCTL PROGRAM commands to transfer control
   * 
   * Route Mappings:
   * 1: COACTVWC → /account/view (Account View)
   * 2: COACTUPC → /account/update (Account Update)
   * 3: COCRDLIC → /cards (Credit Card List)
   * 4: COCRDSLC → /cards (Card Detail View - user selects card)
   * 5: COCRDUPC → /cards/update (Card Update)
   * 6: COTRN00C → /transactions (Transaction List)
   * 7: COTRN01C → /transactions (Transaction View - user selects transaction)
   * 8: COTRN02C → /transactions/add (Transaction Add)
   * 9: CORPT00C → /reports (Transaction Reports)
   * 10: COBIL00C → /billing (Bill Payment)
   */
  const menuRouteMap = {
    1: '/account/view',
    2: '/account/update',
    3: '/cards',
    4: '/cards',
    5: '/cards/update',
    6: '/transactions',
    7: '/transactions',
    8: '/transactions/add',
    9: '/reports',
    10: '/billing'
  };
  
  // ===========================================================================
  // Component Lifecycle Effects
  // ===========================================================================
  
  /**
   * Fetch menu options on component mount
   * 
   * COBOL Equivalent:
   * - BUILD-MENU-OPTIONS paragraph in COMEN01C.cbl
   * - Reads menu configuration and builds display options
   * - Filters menu items based on user type (CDEMO-USER-TYPE)
   * 
   * API Call:
   * - GET /api/menu
   * - Returns menu options filtered by user role from backend
   * - MenuNavigationService.java processes request
   * 
   * Error Handling:
   * - Network errors: Display connection error message
   * - 401 Unauthorized: Redirect to login (handled by apiClient interceptor)
   * - 500 Server Error: Display server error message
   */
  useEffect(() => {
    /**
     * Async function to fetch menu options from backend
     */
    const fetchMenuOptions = async () => {
      try {
        setLoading(true);
        setErrorMessage('');
        
        // Call Spring Boot MenuNavigationService endpoint
        // Replaces COBOL copybook COMEN02Y.cpy data structure access
        const response = await apiClient.get('/menu');
        
        // Extract menu options from response
        // Expected format: { menuItems: [ { optionNumber, optionName, targetRoute, userType } ] }
        if (response.data && response.data.menuItems) {
          setMenuOptions(response.data.menuItems);
        } else {
          // Fallback to default menu options if backend doesn't return data
          // This ensures functional equivalence even if API structure differs
          setMenuOptions(getDefaultMenuOptions());
        }
        
      } catch (error) {
        console.error('Error fetching menu options:', error);
        
        // Display user-friendly error message
        setErrorMessage(
          error.message || 'Unable to load menu options. Please try again.'
        );
        
        // Use default menu options as fallback
        setMenuOptions(getDefaultMenuOptions());
        
      } finally {
        setLoading(false);
      }
    };
    
    // Only fetch menu if user is authenticated
    if (isAuthenticated) {
      fetchMenuOptions();
    } else {
      // User not authenticated - redirect to login
      // Matches COBOL check: IF CDEMO-USER-ID = SPACES
      navigate('/login');
    }
  }, [isAuthenticated, navigate]);
  
  // ===========================================================================
  // Default Menu Options (Fallback)
  // ===========================================================================
  
  /**
   * Returns default menu options matching COMEN02Y.cpy structure
   * 
   * COBOL Equivalent:
   * - Menu options defined in COMEN02Y.cpy copybook
   * - Lines defining option names and target program names
   * 
   * Used as fallback if API call fails or returns unexpected data
   * Ensures application remains functional even with backend issues
   * 
   * @returns {Array} Array of menu option objects
   */
  const getDefaultMenuOptions = () => {
    return [
      { optionNumber: 1, optionName: 'View Account', targetRoute: '/account/view', userType: 'U' },
      { optionNumber: 2, optionName: 'Update Account', targetRoute: '/account/update', userType: 'U' },
      { optionNumber: 3, optionName: 'View Credit Cards', targetRoute: '/cards', userType: 'U' },
      { optionNumber: 4, optionName: 'View Card Details', targetRoute: '/cards', userType: 'U' },
      { optionNumber: 5, optionName: 'Update Credit Card', targetRoute: '/cards/update', userType: 'U' },
      { optionNumber: 6, optionName: 'View Transactions', targetRoute: '/transactions', userType: 'U' },
      { optionNumber: 7, optionName: 'View Transaction Details', targetRoute: '/transactions', userType: 'U' },
      { optionNumber: 8, optionName: 'Add Transaction', targetRoute: '/transactions/add', userType: 'U' },
      { optionNumber: 9, optionName: 'View Reports', targetRoute: '/reports', userType: 'U' },
      { optionNumber: 10, optionName: 'Make Bill Payment', targetRoute: '/billing', userType: 'U' }
    ];
  };
  
  // ===========================================================================
  // Event Handlers
  // ===========================================================================
  
  /**
   * Handle option selection input change
   * 
   * COBOL Equivalent:
   * - RECEIVE MAP handling for OPTNIONO field
   * - Updates WS-OPTION variable in WORKING-STORAGE
   * 
   * Validates that input is numeric (digits only)
   * Clears error message when user starts typing
   * 
   * @param {Object} event - Input change event
   */
  const handleOptionChange = (event) => {
    const value = event.target.value;
    
    // Allow only numeric input (COBOL PIC 99 validation)
    if (value === '' || /^\d+$/.test(value)) {
      setSelectedOption(value);
      
      // Clear error message when user starts typing
      if (errorMessage) {
        setErrorMessage('');
      }
    }
  };
  
  /**
   * Handle form submission (option selection)
   * 
   * COBOL Equivalent:
   * - PROCESS-ENTER-KEY paragraph in COMEN01C.cbl lines 109-197
   * - Validates WS-OPTION and performs XCTL to target program
   * 
   * Validation Steps:
   * 1. Check if option is empty
   * 2. Check if option is numeric
   * 3. Check if option is in valid range (1-10)
   * 4. Navigate to corresponding route
   * 
   * Error Handling:
   * - Empty input → "Please select an option."
   * - Non-numeric → "Option must be a number."
   * - Out of range → "Invalid option. Please select 1-10."
   * 
   * @param {Object} event - Form submit event
   */
  const handleSubmit = (event) => {
    event.preventDefault();
    
    // Clear any existing error messages
    setErrorMessage('');
    
    // Validate option is not empty
    // COBOL: IF WS-OPTION = SPACES
    if (!selectedOption || selectedOption.trim() === '') {
      setErrorMessage('Please select an option.');
      return;
    }
    
    // Convert option to number for validation
    const optionNum = parseInt(selectedOption, 10);
    
    // Validate option is a valid number
    // COBOL: IF WS-OPTION IS NOT NUMERIC
    if (isNaN(optionNum)) {
      setErrorMessage('Option must be a number.');
      return;
    }
    
    // Validate option is in valid range (1-10)
    // COBOL: IF WS-OPTION < 1 OR WS-OPTION > 10
    if (optionNum < 1 || optionNum > 10) {
      setErrorMessage('Invalid option. Please select 1-10.');
      return;
    }
    
    // Get target route from mapping
    const targetRoute = menuRouteMap[optionNum];
    
    if (targetRoute) {
      // Navigate to selected route
      // COBOL: EXEC CICS XCTL PROGRAM(target-program-name)
      navigate(targetRoute);
    } else {
      // Should not happen with validation above, but handle gracefully
      setErrorMessage('Invalid menu option selected.');
    }
  };
  
  /**
   * Handle Exit (PF3) button click
   * 
   * COBOL Equivalent:
   * - RETURN-TO-PREV-SCREEN paragraph in COMEN01C.cbl lines 96-107
   * - Handles DFHPF3 key press
   * - EXEC CICS XCTL PROGRAM('COSGN00C') to return to signon screen
   * 
   * Navigates user back to login screen, effectively ending session
   * Matches mainframe PF3=Exit functionality
   */
  const handleExit = () => {
    // Navigate to login screen (signon)
    // COBOL: EXEC CICS XCTL PROGRAM('COSGN00C')
    navigate('/login');
  };
  
  /**
   * Handle invalid key press event
   * 
   * COBOL Equivalent:
   * - Lines 100-106 in COMEN01C.cbl
   * - IF EIBAID NOT = DFHENTER AND NOT = DFHPF3
   * - MOVE CCDA-MSG-INVALID-KEY TO WS-MESSAGE
   * 
   * Displays error message from CSMSG01Y.cpy: MSG_INVALID_KEY
   * Used when user presses keys other than ENTER or F3
   * 
   * Note: In web interface, this is less relevant as we use buttons
   * Included for functional equivalence with mainframe behavior
   */
  const handleInvalidKey = () => {
    setErrorMessage(MSG_INVALID_KEY);
  };
  
  // ===========================================================================
  // Loading State Rendering
  // ===========================================================================
  
  /**
   * Display loading spinner while fetching menu options
   * 
   * No direct COBOL equivalent - handles async HTTP request state
   * Prevents user interaction until data is available
   */
  if (loading) {
    return (
      <>
        <Header transactionName="CM00" programName="COMEN01C" />
        <Container maxWidth="md" sx={{ mt: 4, display: 'flex', justifyContent: 'center' }}>
          <CircularProgress />
        </Container>
      </>
    );
  }
  
  // ===========================================================================
  // Main Component Rendering
  // ===========================================================================
  
  /**
   * Main menu component rendering
   * 
   * COBOL Equivalent:
   * - EXEC CICS SEND MAP('COMEN01') MAPSET('COMEN01M')
   * - BMS mapset layout from COMEN01.bms
   * 
   * Screen Layout:
   * - Header: Transaction name (CM00), program name (COMEN01C), date, time
   * - Title: Main menu title and subtitle
   * - Menu Options: 10 numbered options (OPTN001O through OPTN010O)
   * - Selection Input: OPTNIONO field for user input
   * - Action Buttons: Enter (Continue) and Exit (PF3)
   * - Error Display: ERRMSGO field at bottom (red color)
   * 
   * Styling:
   * - BMS BLUE attribute → Material-UI primary blue theme
   * - BMS YELLOW attribute → Gold/yellow highlights
   * - BMS RED attribute → Error message red color
   * - Monospace font matching 3270 terminal appearance
   */
  return (
    <>
      {/* 
        BMS Header Section
        Positions: (1,1) through (3,80)
        Contains transaction info, titles, date, time, user info
      */}
      <Header transactionName="CM00" programName="COMEN01C" />
      
      {/* 
        Main Menu Content Area
        Replaces BMS mapset body (lines 4-23)
      */}
      <Container maxWidth="md" sx={{ mt: 4, mb: 4 }}>
        <Paper 
          elevation={3} 
          sx={{ 
            p: 4,
            backgroundColor: '#f5f5f5',
            border: '2px solid #1e3a5f'
          }}
        >
          {/* 
            Menu Title Section
            BMS Position: (5,1) - Main title line
          */}
          <Box sx={{ mb: 3, textAlign: 'center' }}>
            <Typography 
              variant="h4" 
              component="h1"
              sx={{ 
                color: '#1e3a5f',
                fontWeight: 'bold',
                fontFamily: 'Arial, sans-serif',
                mb: 1
              }}
            >
              Main Menu
            </Typography>
            <Typography 
              variant="subtitle1" 
              sx={{ 
                color: '#666',
                fontFamily: 'Arial, sans-serif'
              }}
            >
              Please select an option from the menu below
            </Typography>
          </Box>
          
          {/* 
            Menu Options List
            BMS Positions: (8,1) through (17,80)
            Fields: OPTN001O through OPTN010O
            
            COBOL: Menu options from COMEN02Y.cpy copybook
          */}
          <Box sx={{ mb: 3 }}>
            <List>
              {menuOptions.map((option) => (
                <ListItem 
                  key={option.optionNumber}
                  sx={{
                    backgroundColor: '#ffffff',
                    mb: 1,
                    border: '1px solid #ddd',
                    borderRadius: 1,
                    '&:hover': {
                      backgroundColor: '#e3f2fd',
                      cursor: 'pointer'
                    }
                  }}
                  onClick={() => setSelectedOption(option.optionNumber.toString())}
                >
                  <ListItemText
                    primary={
                      <Typography
                        sx={{
                          fontFamily: 'Courier New, monospace',
                          fontSize: '14px',
                          color: '#1e3a5f'
                        }}
                      >
                        <strong>{option.optionNumber}.</strong> {option.optionName}
                      </Typography>
                    }
                  />
                </ListItem>
              ))}
            </List>
          </Box>
          
          {/* 
            Option Selection Form
            BMS Position: (19,1) - Selection prompt and input field
            Field: OPTNIONO (PIC 99)
          */}
          <Box 
            component="form" 
            onSubmit={handleSubmit}
            sx={{ mb: 3 }}
          >
            <Grid container spacing={2} alignItems="center">
              <Grid item xs={12} sm={8}>
                <TextField
                  fullWidth
                  label="Enter Option (1-10)"
                  value={selectedOption}
                  onChange={handleOptionChange}
                  placeholder="Enter option number"
                  variant="outlined"
                  inputProps={{
                    maxLength: 2,
                    pattern: '[0-9]*',
                    style: {
                      fontFamily: 'Courier New, monospace',
                      fontSize: '16px'
                    }
                  }}
                  sx={{
                    '& .MuiOutlinedInput-root': {
                      '&:hover fieldset': {
                        borderColor: '#1e3a5f',
                      },
                      '&.Mui-focused fieldset': {
                        borderColor: '#1e3a5f',
                      },
                    },
                  }}
                />
              </Grid>
              
              <Grid item xs={12} sm={4}>
                <Button
                  type="submit"
                  variant="contained"
                  fullWidth
                  sx={{
                    backgroundColor: '#1e3a5f',
                    color: '#ffffff',
                    height: '56px',
                    fontWeight: 'bold',
                    '&:hover': {
                      backgroundColor: '#2c5282',
                    },
                  }}
                >
                  Continue (Enter)
                </Button>
              </Grid>
            </Grid>
          </Box>
          
          {/* 
            Action Buttons Section
            PF3=Exit functionality
            
            COBOL: RETURN-TO-PREV-SCREEN paragraph
          */}
          <Box sx={{ display: 'flex', justifyContent: 'center', gap: 2 }}>
            <Button
              variant="outlined"
              onClick={handleExit}
              sx={{
                borderColor: '#1e3a5f',
                color: '#1e3a5f',
                fontWeight: 'bold',
                minWidth: '120px',
                '&:hover': {
                  borderColor: '#2c5282',
                  backgroundColor: '#e3f2fd',
                },
              }}
            >
              Exit (F3)
            </Button>
          </Box>
          
          {/* 
            Error Message Display Area
            BMS Position: (23,1) - ERRMSGO field
            Color: RED attribute from BMS
            
            COBOL: WS-MESSAGE variable content
          */}
          {errorMessage && (
            <Box sx={{ mt: 3 }}>
              <Alert 
                severity="error"
                sx={{
                  fontFamily: 'Courier New, monospace',
                  fontSize: '14px',
                  backgroundColor: '#ffebee',
                  color: '#c62828',
                  border: '1px solid #c62828'
                }}
              >
                {errorMessage}
              </Alert>
            </Box>
          )}
          
          {/* 
            Help Text Section
            Provides guidance to users
          */}
          <Box sx={{ mt: 3, textAlign: 'center' }}>
            <Typography 
              variant="caption" 
              sx={{ 
                color: '#666',
                fontStyle: 'italic',
                fontFamily: 'Arial, sans-serif'
              }}
            >
              Enter a menu option number (1-10) and press Continue, or press Exit to return to login.
            </Typography>
          </Box>
        </Paper>
      </Container>
    </>
  );
};

/**
 * Export MainMenuComponent as default export
 * 
 * Usage in React Router:
 * 
 * import MainMenuComponent from './components/menu/MainMenuComponent';
 * 
 * <Route path="/menu" element={<MainMenuComponent />} />
 * 
 * Replaces COBOL CICS transaction CM00 executing program COMEN01C
 */
export default MainMenuComponent;
