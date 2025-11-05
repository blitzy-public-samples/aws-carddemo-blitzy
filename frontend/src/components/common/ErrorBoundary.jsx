/**
 * ErrorBoundary Component
 * 
 * React error boundary component providing graceful error handling and user-friendly
 * error display throughout the application. Transforms BMS ERRMSG field behavior
 * (DFHMDF at POS=(23,1) with ATTRB=(ASKIP,BRT,FSET) and COLOR=RED) by catching
 * JavaScript errors in child components and displaying formatted error messages
 * without crashing the entire application.
 * 
 * Implements React 16+ error boundary pattern with componentDidCatch lifecycle method.
 * Replicates mainframe error display patterns with Material-UI components matching
 * BMS COLOR=RED and ATTRB=BRT (bright) attributes.
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import { Component } from 'react';
import { useNavigate } from 'react-router-dom';
import PropTypes from 'prop-types';
import {
  Box,
  Alert,
  AlertTitle,
  Paper,
  Typography,
  Button,
  Collapse,
  Stack,
  Divider
} from '@mui/material';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline';
import HomeIcon from '@mui/icons-material/Home';
import RefreshIcon from '@mui/icons-material/Refresh';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import ExpandLessIcon from '@mui/icons-material/ExpandLess';

/**
 * ErrorBoundary Class Component
 * 
 * Catches JavaScript errors anywhere in child component tree, logs errors,
 * and displays a fallback UI instead of crashing the component tree.
 * 
 * Transforms COBOL error handling patterns and CICS RESP/RESP2 codes to
 * modern exception hierarchy per Agent Action Plan section 0.1.
 */
class ErrorBoundary extends Component {
  constructor(props) {
    super(props);
    
    // Initialize component state
    // Matches BMS screen state management for error display
    this.state = {
      hasError: false,
      error: null,
      errorInfo: null,
      showDetails: false,
      errorCode: null
    };
    
    // Bind methods to ensure correct 'this' context
    this.resetErrorBoundary = this.resetErrorBoundary.bind(this);
    this.toggleDetails = this.toggleDetails.bind(this);
  }

  /**
   * Static method called during render phase when an error is thrown
   * Updates state to trigger fallback UI rendering
   * 
   * Equivalent to CICS HANDLE CONDITION ERROR pattern in COBOL
   * 
   * @param {Error} error - The error that was thrown
   * @returns {Object} New state object
   */
  static getDerivedStateFromError(error) {
    // Generate error code matching COBOL file-status and CICS RESP code patterns
    const errorCode = ErrorBoundary.generateErrorCode(error);
    
    return {
      hasError: true,
      error: error,
      errorCode: errorCode
    };
  }

  /**
   * Generate error code matching COBOL file-status and CICS RESP/RESP2 patterns
   * Maps JavaScript error types to mainframe-style error codes
   * 
   * @param {Error} error - The error object
   * @returns {string} Formatted error code (e.g., "ERR-0001", "RESP-0013")
   */
  static generateErrorCode(error) {
    // Map common error types to CICS-style response codes
    const errorTypeMap = {
      'TypeError': 'ERR-0001',        // CICS INVREQ equivalent
      'ReferenceError': 'ERR-0002',   // CICS NOTFND equivalent
      'RangeError': 'ERR-0003',       // CICS LENGERR equivalent
      'SyntaxError': 'ERR-0004',      // CICS SYSIDERR equivalent
      'NetworkError': 'ERR-0005',     // CICS IOERR equivalent
      'TimeoutError': 'ERR-0006',     // CICS NOTALLOC equivalent
      'SecurityError': 'ERR-0007',    // CICS NOTAUTH equivalent
      'QuotaExceededError': 'ERR-0008' // CICS NOSTG equivalent
    };

    const errorName = error?.name || 'Error';
    return errorTypeMap[errorName] || 'ERR-9999'; // Default unknown error code
  }

  /**
   * Lifecycle method called after an error is thrown by a descendant component
   * Logs error information and optionally sends to error tracking service
   * 
   * Equivalent to COBOL error logging and CICS ABEND handling
   * 
   * @param {Error} error - The error that was thrown
   * @param {Object} errorInfo - Object with componentStack key containing info about component stack
   */
  componentDidCatch(error, errorInfo) {
    // Log error to console for development
    console.error('ErrorBoundary caught an error:', error);
    console.error('Component stack:', errorInfo.componentStack);

    // Store error info in state for display
    this.setState({
      errorInfo: errorInfo
    });

    // Call custom error callback if provided
    // Allows parent components to handle errors (e.g., send to Sentry)
    if (this.props.onError && typeof this.props.onError === 'function') {
      try {
        this.props.onError(error, errorInfo);
      } catch (callbackError) {
        console.error('Error in onError callback:', callbackError);
      }
    }

    // Sentry-ready error tracking
    // Uncomment when Sentry is configured:
    // if (window.Sentry) {
    //   window.Sentry.captureException(error, {
    //     contexts: {
    //       react: {
    //         componentStack: errorInfo.componentStack
    //       }
    //     }
    //   });
    // }
  }

  /**
   * Reset error boundary state to allow retry
   * Clears error state and re-renders children
   * 
   * Equivalent to CICS screen refresh after error handling
   */
  resetErrorBoundary() {
    this.setState({
      hasError: false,
      error: null,
      errorInfo: null,
      showDetails: false,
      errorCode: null
    });
  }

  /**
   * Toggle display of technical error details
   * Implements collapsible section for component stack trace
   */
  toggleDetails() {
    this.setState(prevState => ({
      showDetails: !prevState.showDetails
    }));
  }

  /**
   * Format error message for user display
   * Transforms technical error into user-friendly message
   * 
   * @param {Error} error - The error object
   * @returns {string} Formatted user-friendly error message
   */
  formatErrorMessage(error) {
    if (!error) {
      return 'An unexpected error occurred. Please try again.';
    }

    // Map technical errors to user-friendly messages
    // Maintains BMS screen error message patterns
    const errorMessage = error.message || 'Unknown error';
    
    // Common error patterns with user-friendly translations
    if (errorMessage.includes('Cannot read properties of undefined')) {
      return 'Unable to access required data. Please refresh the page and try again.';
    }
    if (errorMessage.includes('Cannot read properties of null')) {
      return 'Required data is not available. Please check your connection and try again.';
    }
    if (errorMessage.includes('Network')) {
      return 'Network connection error. Please check your internet connection and try again.';
    }
    if (errorMessage.includes('timeout')) {
      return 'The operation timed out. Please try again.';
    }
    if (errorMessage.includes('Failed to fetch')) {
      return 'Unable to connect to the server. Please try again later.';
    }

    // Return original message if no pattern match
    return `An error occurred: ${errorMessage}`;
  }

  /**
   * Render method
   * Returns fallback UI when error caught, otherwise renders children
   * 
   * Transforms BMS ERRMSG field display pattern to Material-UI Alert component
   */
  render() {
    // If custom fallback provided and error occurred, use custom fallback
    if (this.state.hasError && this.props.fallback) {
      return this.props.fallback({
        error: this.state.error,
        errorInfo: this.state.errorInfo,
        errorCode: this.state.errorCode,
        resetErrorBoundary: this.resetErrorBoundary
      });
    }

    // If error occurred, render default fallback UI
    if (this.state.hasError) {
      const { error, errorInfo, showDetails, errorCode } = this.state;
      const userMessage = this.formatErrorMessage(error);

      // Render fallback UI matching BMS ERRMSG field pattern
      // POS=(23,1) LENGTH=78 COLOR=RED ATTRB=(ASKIP,BRT,FSET)
      return (
        <Box
          sx={{
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
            minHeight: '100vh',
            backgroundColor: '#f5f5f5',
            padding: 3
          }}
        >
          <Paper
            elevation={3}
            sx={{
              maxWidth: 800,
              width: '100%',
              padding: 4
            }}
          >
            {/* Main Error Alert - Replicates BMS ERRMSG with COLOR=RED */}
            <Alert
              severity="error"
              icon={<ErrorOutlineIcon fontSize="large" />}
              sx={{
                marginBottom: 3,
                '& .MuiAlert-message': {
                  width: '100%'
                }
              }}
            >
              <AlertTitle sx={{ fontSize: '1.25rem', fontWeight: 'bold' }}>
                Application Error
                {errorCode && (
                  <Typography
                    component="span"
                    sx={{
                      marginLeft: 2,
                      fontSize: '0.875rem',
                      fontWeight: 'normal',
                      color: 'error.dark'
                    }}
                  >
                    [Code: {errorCode}]
                  </Typography>
                )}
              </AlertTitle>
              <Typography variant="body1" sx={{ marginTop: 1 }}>
                {userMessage}
              </Typography>
            </Alert>

            {/* Action Buttons Stack */}
            <Stack
              direction={{ xs: 'column', sm: 'row' }}
              spacing={2}
              sx={{ marginBottom: 3 }}
            >
              {/* Try Again Button - Resets error boundary */}
              <Button
                variant="contained"
                color="primary"
                startIcon={<RefreshIcon />}
                onClick={this.resetErrorBoundary}
                fullWidth
                sx={{
                  textTransform: 'none',
                  fontSize: '1rem',
                  padding: '10px 24px'
                }}
              >
                Try Again
              </Button>

              {/* Return to Home Button - Uses navigate via wrapper */}
              <NavigateButton
                variant="outlined"
                color="primary"
                startIcon={<HomeIcon />}
                to="/"
                fullWidth
                sx={{
                  textTransform: 'none',
                  fontSize: '1rem',
                  padding: '10px 24px'
                }}
              >
                Return to Home
              </NavigateButton>
            </Stack>

            <Divider sx={{ marginY: 2 }} />

            {/* Technical Details Section - Collapsible */}
            <Box>
              <Button
                variant="text"
                onClick={this.toggleDetails}
                endIcon={showDetails ? <ExpandLessIcon /> : <ExpandMoreIcon />}
                sx={{
                  textTransform: 'none',
                  color: 'text.secondary',
                  marginBottom: 1
                }}
              >
                {showDetails ? 'Hide' : 'Show'} Technical Details
              </Button>

              <Collapse in={showDetails}>
                <Paper
                  variant="outlined"
                  sx={{
                    padding: 2,
                    backgroundColor: '#fafafa',
                    border: '1px solid #e0e0e0'
                  }}
                >
                  {/* Error Name and Message */}
                  <Typography
                    variant="subtitle2"
                    sx={{ fontWeight: 'bold', marginBottom: 1 }}
                  >
                    Error Type:
                  </Typography>
                  <Typography
                    variant="body2"
                    sx={{
                      fontFamily: 'monospace',
                      marginBottom: 2,
                      color: 'error.main'
                    }}
                  >
                    {error?.name || 'Error'}
                  </Typography>

                  <Typography
                    variant="subtitle2"
                    sx={{ fontWeight: 'bold', marginBottom: 1 }}
                  >
                    Error Message:
                  </Typography>
                  <Typography
                    variant="body2"
                    sx={{
                      fontFamily: 'monospace',
                      marginBottom: 2,
                      color: 'error.main',
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-word'
                    }}
                  >
                    {error?.message || 'No error message available'}
                  </Typography>

                  {/* Component Stack Trace */}
                  {errorInfo?.componentStack && (
                    <>
                      <Typography
                        variant="subtitle2"
                        sx={{ fontWeight: 'bold', marginBottom: 1 }}
                      >
                        Component Stack:
                      </Typography>
                      <Typography
                        variant="body2"
                        sx={{
                          fontFamily: 'monospace',
                          fontSize: '0.75rem',
                          whiteSpace: 'pre-wrap',
                          backgroundColor: '#f5f5f5',
                          padding: 1,
                          borderRadius: 1,
                          overflow: 'auto',
                          maxHeight: 300,
                          color: 'text.secondary'
                        }}
                      >
                        {errorInfo.componentStack}
                      </Typography>
                    </>
                  )}

                  {/* Full Error Stack (if available) */}
                  {error?.stack && (
                    <>
                      <Typography
                        variant="subtitle2"
                        sx={{ fontWeight: 'bold', marginTop: 2, marginBottom: 1 }}
                      >
                        Error Stack:
                      </Typography>
                      <Typography
                        variant="body2"
                        sx={{
                          fontFamily: 'monospace',
                          fontSize: '0.75rem',
                          whiteSpace: 'pre-wrap',
                          backgroundColor: '#f5f5f5',
                          padding: 1,
                          borderRadius: 1,
                          overflow: 'auto',
                          maxHeight: 300,
                          color: 'text.secondary'
                        }}
                      >
                        {error.stack}
                      </Typography>
                    </>
                  )}
                </Paper>
              </Collapse>
            </Box>

            {/* Help Text - Matches BMS PF key instructions pattern */}
            <Typography
              variant="caption"
              sx={{
                display: 'block',
                marginTop: 3,
                textAlign: 'center',
                color: 'text.secondary'
              }}
            >
              If this problem persists, please contact system support.
            </Typography>
          </Paper>
        </Box>
      );
    }

    // No error - render children normally
    return this.props.children;
  }
}

/**
 * PropTypes validation for ErrorBoundary component
 * Provides runtime type checking and inline documentation
 */
ErrorBoundary.propTypes = {
  children: PropTypes.node.isRequired,
  fallback: PropTypes.func,
  onError: PropTypes.func
};

ErrorBoundary.defaultProps = {
  fallback: null,
  onError: null
};

/**
 * NavigateButton Component
 * 
 * Functional wrapper component that provides navigation capability to ErrorBoundary
 * Uses useNavigate hook from react-router-dom which cannot be used directly in class components
 * 
 * @param {Object} props - Component props including 'to' navigation path
 */
const NavigateButton = ({ to, children, ...props }) => {
  const navigate = useNavigate();

  const handleClick = () => {
    navigate(to);
  };

  return (
    <Button onClick={handleClick} {...props}>
      {children}
    </Button>
  );
};

NavigateButton.propTypes = {
  to: PropTypes.string.isRequired,
  children: PropTypes.node.isRequired
};

// Default export
export default ErrorBoundary;
