/**
 * ErrorMessage Component
 * 
 * Converted from BMS ERRMSG field pattern found in COSGN00.bms and COMEN01.bms
 * Original function: Display error messages at bottom of 3270 terminal screen
 * 
 * BMS Pattern:
 * - ERRMSG field: ATTRB=(ASKIP,BRT,FSET), COLOR=RED, LENGTH=78, POS=(23,1)
 * - Fixed position at line 23 (bottom of 24-line screen)
 * - Red bright text for error visibility
 * - 78 character limit (full screen width minus margins)
 * 
 * Conversion Notes:
 * - BMS fixed-position field → React component with flexible placement
 * - BMS COLOR=RED only → MUI Alert with multiple severity levels
 * - BMS no dismissal → Optional dismissible alert with close button
 * - BMS instant display → Animated Collapse transition for smooth UX
 * - Added auto-hide timer capability for transient messages
 * - Added title support for categorized error messages
 * 
 * Copyright: Apache License 2.0
 */

import React from 'react';
import { Alert, AlertTitle, Collapse } from '@mui/material';

/**
 * Props interface for ErrorMessage component
 * Defines all configuration options for error display
 */
interface ErrorMessageProps {
  /**
   * Error message text to display
   * Null or undefined = no error, component returns null
   * Maps to ERRMSG field content in BMS maps
   */
  message?: string | null;

  /**
   * Alert severity level determining color and icon
   * - error: Red with X icon (default, matches BMS red text)
   * - warning: Orange with ! icon
   * - info: Blue with i icon
   * - success: Green with checkmark icon
   */
  severity?: 'error' | 'warning' | 'info' | 'success';

  /**
   * Optional alert title displayed above message
   * Bold text for error categorization
   * Example: "Validation Error", "API Error"
   */
  title?: string;

  /**
   * Optional close button handler
   * If provided, renders X button in alert
   * Called when user clicks close button
   */
  onClose?: () => void;

  /**
   * Auto-hide duration in milliseconds
   * Alert automatically dismisses after this time
   * Useful for success messages: "Account saved successfully!"
   * If not provided, alert persists until message cleared or manually closed
   */
  autoHideDuration?: number;

  /**
   * Alert visual style variant
   * - filled: Solid colored background (default, highest visibility)
   * - outlined: Border with light background
   * - standard: Light colored background
   */
  variant?: 'filled' | 'outlined' | 'standard';
}

/**
 * ErrorMessage Component
 * 
 * Reusable error message display component for showing validation errors,
 * API errors, and user feedback messages throughout the application.
 * 
 * Replaces BMS ERRMSG field pattern with modern Material-UI Alert component
 * providing consistent error styling, multiple severity levels, animated
 * transitions, and dismissible alerts.
 * 
 * Usage Examples:
 * 
 * 1. Form Validation Error (replaces BMS ERRMSG with COBOL validation message):
 *    <ErrorMessage 
 *      message={formError} 
 *      severity="error"
 *      title="Validation Error"
 *    />
 * 
 * 2. API Error with Dismissal:
 *    <ErrorMessage 
 *      message={apiError?.message} 
 *      severity="error"
 *      onClose={() => setApiError(null)}
 *    />
 * 
 * 3. Success Message with Auto-Hide:
 *    <ErrorMessage 
 *      message="Account updated successfully!" 
 *      severity="success"
 *      autoHideDuration={5000}
 *      onClose={() => setSuccessMsg(null)}
 *    />
 * 
 * 4. Warning (replaces BMS warning messages):
 *    <ErrorMessage 
 *      message="Credit limit approaching maximum" 
 *      severity="warning"
 *      variant="outlined"
 *    />
 * 
 * 5. Info Message:
 *    <ErrorMessage 
 *      message="Search returned no results" 
 *      severity="info"
 *    />
 */
const ErrorMessage: React.FC<ErrorMessageProps> = ({ 
  message, 
  severity = 'error',
  title,
  onClose,
  autoHideDuration,
  variant = 'filled'
}) => {
  // Internal visibility state for animated transitions
  // Separate from message prop to enable exit animation before unmounting
  const [visible, setVisible] = React.useState<boolean>(true);

  // Auto-hide timer effect
  // Automatically dismisses alert after autoHideDuration milliseconds
  React.useEffect(() => {
    // Early return if auto-hide not configured or no message
    if (!autoHideDuration || !message) {
      return undefined;
    }

    const timer = setTimeout(() => {
      setVisible(false);
      // Call onClose callback after hide animation completes
      // Allows parent to clear message state
      onClose?.();
    }, autoHideDuration);

    // Cleanup timer on unmount or when dependencies change
    return () => clearTimeout(timer);
  }, [autoHideDuration, message, onClose]);

  // Reset visibility when message changes
  // Ensures alert shows again when new message arrives
  React.useEffect(() => {
    // Convert message to boolean (null/undefined/empty = false)
    setVisible(!!message);
  }, [message]);

  // Don't render if no message
  // Returns null = no DOM element = no layout impact
  if (!message) {
    return null;
  }

  /**
   * Handle close button click
   * Sets visibility to false (triggers exit animation)
   * Calls onClose callback to notify parent
   */
  const handleClose = () => {
    setVisible(false);
    onClose?.();
  };

  return (
    <Collapse in={visible}>
      <Alert 
        severity={severity} 
        variant={variant}
        // Only show close button if onClose handler provided
        onClose={onClose ? handleClose : undefined}
        sx={{ 
          mb: 2  // Margin bottom: 16px (MUI spacing unit * 2)
        }}
      >
        {/* Optional title - bold text above message */}
        {title && <AlertTitle>{title}</AlertTitle>}
        {/* Error message text */}
        {message}
      </Alert>
    </Collapse>
  );
};

export default ErrorMessage;
