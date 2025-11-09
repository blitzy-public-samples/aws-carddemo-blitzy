/**
 * ErrorBoundary Component
 * 
 * React Error Boundary component for global error handling across the CardDemo application.
 * Catches JavaScript errors anywhere in the child component tree, logs error details,
 * displays a fallback UI when errors occur, and provides a recovery mechanism.
 * 
 * This component implements the Error Boundary pattern introduced in React 16+ to prevent
 * the entire application from crashing when individual components fail, ensuring graceful
 * degradation and improved user experience.
 * 
 * Key Features:
 * - Catches errors during rendering, lifecycle methods, and constructors of child components
 * - Logs error information to console (extensible for monitoring services)
 * - Displays user-friendly error message with recovery options
 * - Provides reset mechanism to attempt recovery from error state
 * - Consistent styling with Material-UI theme
 * 
 * Usage:
 * <ErrorBoundary>
 *   <YourComponent />
 * </ErrorBoundary>
 * 
 * @component
 * @example
 * // Wrap entire application or specific sections
 * <ErrorBoundary>
 *   <App />
 * </ErrorBoundary>
 */

import { Component } from 'react';
import PropTypes from 'prop-types';
import { Alert, Button, Box, Typography, Container } from '@mui/material';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline';

/**
 * ErrorBoundary Class Component
 * 
 * Error boundaries must be class components as they rely on lifecycle methods
 * (getDerivedStateFromError and componentDidCatch) that are only available in class components.
 */
class ErrorBoundary extends Component {
  /**
   * Constructor
   * Initializes the error boundary state
   * 
   * @param {Object} props - Component props
   */
  constructor(props) {
    super(props);
    
    /**
     * Component State
     * @property {boolean} hasError - Flag indicating if an error has been caught
     * @property {Error|null} error - The error object that was thrown
     * @property {Object|null} errorInfo - React error info containing component stack trace
     */
    this.state = {
      hasError: false,
      error: null,
      errorInfo: null
    };
  }

  /**
   * getDerivedStateFromError
   * 
   * Static lifecycle method called when an error is thrown in a descendant component.
   * Updates state to trigger fallback UI rendering on the next render.
   * This method is called during the render phase, so side effects are not permitted.
   * 
   * @static
   * @param {Error} error - The error that was thrown
   * @returns {Object} Updated state object
   */
  static getDerivedStateFromError(error) {
    // Update state to indicate an error has occurred
    // This triggers the fallback UI in the next render
    return {
      hasError: true,
      error: error
    };
  }

  /**
   * componentDidCatch
   * 
   * Lifecycle method called after an error has been thrown by a descendant component.
   * Used for logging error information and performing side effects.
   * This method is called during the commit phase, so side effects are permitted.
   * 
   * @param {Error} error - The error that was thrown
   * @param {Object} errorInfo - Object containing component stack trace information
   * @param {string} errorInfo.componentStack - Stack trace showing which components threw the error
   */
  componentDidCatch(error, errorInfo) {
    // Log error details to console for debugging
    // In production, this could be extended to send errors to a monitoring service
    // (e.g., Sentry, LogRocket, Application Insights)
    console.error('ErrorBoundary caught an error:', error);
    console.error('Component stack trace:', errorInfo.componentStack);

    // Update state with error information for display
    this.setState({
      errorInfo: errorInfo
    });

    // TODO: In production, integrate with error monitoring service
    // Example: logErrorToMonitoringService(error, errorInfo);
    // This could include:
    // - Sending error to backend logging endpoint
    // - Tracking user context and session information
    // - Capturing browser information and user actions leading to error
  }

  /**
   * handleReset
   * 
   * Resets the error boundary state, allowing users to attempt recovery from the error.
   * Clears the error state and attempts to re-render the child components.
   * This gives users a chance to recover without reloading the entire page.
   */
  handleReset = () => {
    // Clear error state to attempt recovery
    this.setState({
      hasError: false,
      error: null,
      errorInfo: null
    });

    // Log the reset attempt
    console.log('ErrorBoundary: User attempted to reset error state');
  };

  /**
   * handleReload
   * 
   * Reloads the entire page as a fallback recovery mechanism.
   * Used when the simple reset doesn't resolve the issue.
   */
  handleReload = () => {
    console.log('ErrorBoundary: User initiated page reload');
    window.location.reload();
  };

  /**
   * render
   * 
   * Renders either the fallback error UI or the child components.
   * When an error has occurred, displays user-friendly error message with recovery options.
   * When no error, renders children normally.
   * 
   * @returns {React.ReactNode} Either error UI or children components
   */
  render() {
    // If an error has been caught, render the fallback UI
    if (this.state.hasError) {
      return (
        <Container maxWidth="md" sx={{ mt: 8 }}>
          <Box
            sx={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              minHeight: '60vh',
              textAlign: 'center'
            }}
          >
            {/* Error Icon */}
            <ErrorOutlineIcon
              sx={{
                fontSize: 80,
                color: 'error.main',
                mb: 3
              }}
            />

            {/* Error Alert */}
            <Alert
              severity="error"
              sx={{
                width: '100%',
                mb: 3,
                textAlign: 'left'
              }}
            >
              <Typography variant="h6" component="div" gutterBottom>
                Oops! Something went wrong
              </Typography>
              <Typography variant="body2" component="div" paragraph>
                We encountered an unexpected error. The application has been notified
                and we&apos;re working to fix the issue.
              </Typography>
              
              {/* Display error message in development mode */}
              {import.meta.env.MODE === 'development' && this.state.error && (
                <Box
                  sx={{
                    mt: 2,
                    p: 2,
                    backgroundColor: 'rgba(0, 0, 0, 0.05)',
                    borderRadius: 1,
                    fontFamily: 'monospace',
                    fontSize: '0.875rem',
                    wordBreak: 'break-word'
                  }}
                >
                  <Typography variant="subtitle2" gutterBottom>
                    <strong>Error Details (Development Mode):</strong>
                  </Typography>
                  <Typography variant="body2" component="div">
                    <strong>Type:</strong> {this.state.error.name || 'Error'}
                  </Typography>
                  <Typography variant="body2" component="div">
                    <strong>Message:</strong> {this.state.error.message}
                  </Typography>
                  {this.state.error.stack && (
                    <Box sx={{ mt: 1 }}>
                      <Typography variant="body2" component="div">
                        <strong>Stack Trace:</strong>
                      </Typography>
                      <pre style={{ 
                        fontSize: '0.75rem', 
                        overflow: 'auto',
                        maxHeight: '200px'
                      }}>
                        {this.state.error.stack}
                      </pre>
                    </Box>
                  )}
                </Box>
              )}
            </Alert>

            {/* Recovery Instructions */}
            <Typography variant="body1" color="text.secondary" paragraph>
              You can try the following recovery options:
            </Typography>

            {/* Recovery Buttons */}
            <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', justifyContent: 'center' }}>
              <Button
                variant="contained"
                color="primary"
                onClick={this.handleReset}
                sx={{ minWidth: 150 }}
              >
                Try Again
              </Button>
              <Button
                variant="outlined"
                color="primary"
                onClick={this.handleReload}
                sx={{ minWidth: 150 }}
              >
                Reload Page
              </Button>
              <Button
                variant="text"
                color="primary"
                onClick={() => window.history.back()}
                sx={{ minWidth: 150 }}
              >
                Go Back
              </Button>
            </Box>

            {/* Additional Help Text */}
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ mt: 4 }}
            >
              If the problem persists, please contact support or try again later.
            </Typography>
          </Box>
        </Container>
      );
    }

    // No error occurred, render children normally
    return this.props.children;
  }
}

/**
 * PropTypes Validation
 * 
 * Validates that children prop is provided and is a valid React node.
 * Provides developer warnings in development mode when incorrect prop types are passed.
 */
ErrorBoundary.propTypes = {
  /**
   * Child components to be rendered and monitored for errors
   * Can be any valid React node (element, string, number, array, fragment, etc.)
   */
  children: PropTypes.node.isRequired
};

/**
 * Default Export
 * ErrorBoundary component for use throughout the application
 */
export default ErrorBoundary;
