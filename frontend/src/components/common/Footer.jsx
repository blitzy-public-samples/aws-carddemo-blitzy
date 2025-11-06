/**
 * CardDemo - Footer Component
 * 
 * React Footer component replicating BMS 3270 screen footer functionality.
 * Displays contextual PF key instructions and help text at the bottom of the screen,
 * matching the BMS screen line 24 (POS=(24,1)) behavior with YELLOW color attribute.
 * 
 * Transformation from BMS patterns:
 * - COSGN00.bms: 'ENTER=Sign-on  F3=Exit'
 * - COMEN01.bms: 'ENTER=Continue  F3=Exit'
 * - COTRN00.bms: 'ENTER=Continue  F3=Back  F7=Backward  F8=Forward'
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 */

import React, { useEffect } from 'react';
import { Box, Typography } from '@mui/material';
import PropTypes from 'prop-types';

/**
 * Footer Component
 * 
 * Displays function key instructions and contextual help text at the bottom of the page.
 * Handles keyboard events for F3 (Back/Exit), F7 (Previous), F8 (Next), and Enter (Submit).
 * 
 * @param {Object} props - Component properties
 * @param {string} props.helpText - Contextual help message to display
 * @param {boolean} props.showF3 - Show F3=Back/Exit instruction
 * @param {boolean} props.showF7 - Show F7=Previous/Backward instruction
 * @param {boolean} props.showF8 - Show F8=Next/Forward instruction
 * @param {boolean} props.showEnter - Show Enter=Submit/Continue instruction
 * @param {Function} props.onBack - Callback function triggered by F3 key
 * @param {Function} props.onPrevious - Callback function triggered by F7 key
 * @param {Function} props.onNext - Callback function triggered by F8 key
 * @param {Function} props.onSubmit - Callback function triggered by Enter key
 * @returns {JSX.Element} Footer component
 */
const Footer = ({
  helpText = '',
  showF3 = false,
  showF7 = false,
  showF8 = false,
  showEnter = false,
  onBack = null,
  onPrevious = null,
  onNext = null,
  onSubmit = null,
}) => {
  /**
   * Keyboard event handler for function keys
   * Matches BMS PF key behavior for F3, F7, F8, and Enter keys
   */
  useEffect(() => {
    const handleKeyDown = (event) => {
      // Check if the target is an input or textarea to avoid interfering with form input
      const isInputField = ['INPUT', 'TEXTAREA', 'SELECT'].includes(
        event.target.tagName
      );

      switch (event.key) {
        case 'F3':
          // F3 key: Back/Exit functionality
          if (showF3 && onBack && typeof onBack === 'function') {
            event.preventDefault();
            onBack();
          }
          break;

        case 'F7':
          // F7 key: Previous/Backward functionality
          if (showF7 && onPrevious && typeof onPrevious === 'function') {
            event.preventDefault();
            onPrevious();
          }
          break;

        case 'F8':
          // F8 key: Next/Forward functionality
          if (showF8 && onNext && typeof onNext === 'function') {
            event.preventDefault();
            onNext();
          }
          break;

        case 'Enter':
          // Enter key: Submit/Continue functionality
          // Only handle if not in an input field (to allow normal form submission)
          if (
            !isInputField &&
            showEnter &&
            onSubmit &&
            typeof onSubmit === 'function'
          ) {
            event.preventDefault();
            onSubmit();
          }
          break;

        default:
          // No action for other keys
          break;
      }
    };

    // Add event listener for keydown events
    document.addEventListener('keydown', handleKeyDown);

    // Cleanup: Remove event listener on component unmount
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [showF3, showF7, showF8, showEnter, onBack, onPrevious, onNext, onSubmit]);

  /**
   * Build function key instruction text based on visible props
   * Matches BMS footer format: "ENTER=Continue  F3=Back  F7=Backward  F8=Forward"
   */
  const buildFunctionKeyText = () => {
    const instructions = [];

    if (showEnter) {
      instructions.push('ENTER=Continue');
    }

    if (showF3) {
      instructions.push('F3=Back');
    }

    if (showF7) {
      instructions.push('F7=Previous');
    }

    if (showF8) {
      instructions.push('F8=Next');
    }

    return instructions.join('  ');
  };

  const functionKeyText = buildFunctionKeyText();

  // Don't render footer if no instructions or help text
  if (!functionKeyText && !helpText) {
    return null;
  }

  return (
    <Box
      component="footer"
      sx={{
        position: 'fixed',
        bottom: 0,
        left: 0,
        right: 0,
        backgroundColor: '#1e1e1e', // Dark background matching mainframe theme
        borderTop: '2px solid #ffd700', // Yellow border matching BMS YELLOW attribute
        padding: { xs: '8px 12px', sm: '10px 16px', md: '12px 20px' }, // Responsive padding
        display: 'flex',
        flexDirection: { xs: 'column', sm: 'row' }, // Stack on mobile, row on larger screens
        justifyContent: 'space-between',
        alignItems: { xs: 'flex-start', sm: 'center' },
        gap: { xs: 1, sm: 2 },
        zIndex: 1000, // Ensure footer stays on top
        boxShadow: '0 -2px 10px rgba(0, 0, 0, 0.3)', // Subtle shadow for depth
      }}
    >
      {/* Function Key Instructions */}
      {functionKeyText && (
        <Typography
          variant="body2"
          sx={{
            color: '#ffd700', // Yellow color matching BMS YELLOW attribute
            fontFamily: 'monospace', // Monospace font for mainframe look
            fontSize: { xs: '0.75rem', sm: '0.875rem', md: '0.9rem' }, // Responsive font size
            fontWeight: 500,
            letterSpacing: '0.5px',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            flexShrink: 0,
          }}
        >
          {functionKeyText}
        </Typography>
      )}

      {/* Contextual Help Text */}
      {helpText && (
        <Typography
          variant="body2"
          sx={{
            color: '#e0e0e0', // Light gray for help text
            fontFamily: 'monospace',
            fontSize: { xs: '0.7rem', sm: '0.8rem', md: '0.85rem' }, // Responsive font size
            fontStyle: 'italic',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: { xs: 'normal', sm: 'nowrap' }, // Wrap on mobile, nowrap on larger screens
            flexGrow: 1,
            textAlign: { xs: 'left', sm: 'right' }, // Left align on mobile, right on larger
          }}
        >
          {helpText}
        </Typography>
      )}
    </Box>
  );
};

/**
 * PropTypes validation for Footer component
 * Ensures type safety and provides developer warnings for incorrect usage
 */
Footer.propTypes = {
  helpText: PropTypes.string,
  showF3: PropTypes.bool,
  showF7: PropTypes.bool,
  showF8: PropTypes.bool,
  showEnter: PropTypes.bool,
  onBack: PropTypes.func,
  onPrevious: PropTypes.func,
  onNext: PropTypes.func,
  onSubmit: PropTypes.func,
};

/**
 * Default props for Footer component
 * Provides sensible defaults when props are not specified
 */
Footer.defaultProps = {
  helpText: '',
  showF3: false,
  showF7: false,
  showF8: false,
  showEnter: false,
  onBack: null,
  onPrevious: null,
  onNext: null,
  onSubmit: null,
};

export default Footer;
