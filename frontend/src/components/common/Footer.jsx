/**
 * Footer Component
 * 
 * React footer component providing consistent application footer with copyright
 * information, version details, and modern equivalents to BMS function key legends.
 * 
 * Replicates BMS mapset footer patterns from COBOL/CICS CardDemo application:
 * - Source files: COSGN00.bms, COACTUP.bms, COCRDSL.bms, COCRDUP.bms
 * - BMS Pattern: DFHMDF fields at POS=(24,x) with function key legends
 *   (FKEYS='ENTER=Process F3=Exit', FKEY05='F5=Save', FKEY12='F12=Cancel')
 * - Color: YELLOW (COLOR=YELLOW in BMS definitions)
 * 
 * Transformation Details:
 * - BMS footer at row 24 (bottom of 24x80 terminal screen) → Sticky footer at viewport bottom
 * - Function key text legends → Informational footer text
 * - DFHMDF INITIAL values → Typography component content
 * - BMS COPYRIGHT headers → Copyright notice display
 * - Ver: CardDemo_v1.0-70-g193b394-123 → Application version from constants
 * 
 * Design Features:
 * - Material-UI Box and Typography components for layout
 * - Responsive design: centered text on mobile, multi-column on desktop
 * - Flexbox layout with sticky positioning
 * - Theme colors matching BMS YELLOW attribute
 * - Accessibility attributes (ARIA labels) for screen readers
 * - Links to documentation, privacy policy, and terms of service
 * 
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */

import React from 'react';
import PropTypes from 'prop-types';
import { Box, Container, Typography, Link, Divider, useTheme, useMediaQuery } from '@mui/material';
import { APP_CONFIG } from '../../utils/constants';

/**
 * Footer Component
 * 
 * Displays application footer with copyright, version, license information,
 * and function key legends transformed from BMS mapset footers.
 * 
 * @param {Object} props - Component props
 * @param {boolean} props.showFunctionKeys - Whether to display function key legends (default: true)
 * @param {string} props.variant - Footer variant ('default' or 'minimal')
 * @returns {JSX.Element} Footer component
 */
const Footer = ({ showFunctionKeys = true, variant = 'default' }) => {
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down('sm'));
  
  // Copyright year - dynamic to stay current
  const currentYear = new Date().getFullYear();
  
  // Footer background and text colors matching BMS terminal aesthetics
  // BMS YELLOW color for function key legends
  const footerBgColor = theme.palette.mode === 'dark' ? '#1a1a1a' : '#f5f5f5';
  const textColor = theme.palette.mode === 'dark' ? '#e0e0e0' : '#333333';
  const accentColor = theme.palette.warning.main; // Yellow accent matching BMS COLOR=YELLOW
  
  /**
   * Function key legend text from BMS mapsets
   * Represents common function key patterns from source BMS files:
   * - COSGN00.bms: 'ENTER=Sign-on  F3=Exit'
   * - COACTUP.bms: 'ENTER=Process F3=Exit', 'F5=Save', 'F12=Cancel'
   * - Other mapsets follow similar patterns
   */
  const functionKeyLegends = [
    'ENTER=Process',
    'F3=Exit',
    'F5=Save',
    'F12=Cancel'
  ];

  return (
    <Box
      component="footer"
      role="contentinfo"
      aria-label="Application footer with copyright and navigation information"
      sx={{
        position: 'sticky',
        bottom: 0,
        width: '100%',
        backgroundColor: footerBgColor,
        borderTop: `1px solid ${theme.palette.divider}`,
        py: { xs: 2, sm: 3 },
        mt: 'auto', // Push footer to bottom when content is short
        zIndex: theme.zIndex.appBar - 1
      }}
    >
      <Container maxWidth="lg">
        {/* Function Key Legends Section - Replicates BMS FKEYS at POS=(24,x) */}
        {showFunctionKeys && variant === 'default' && (
          <>
            <Box
              sx={{
                display: 'flex',
                flexDirection: { xs: 'column', sm: 'row' },
                justifyContent: 'center',
                alignItems: 'center',
                gap: { xs: 1, sm: 3 },
                mb: 2
              }}
              aria-label="Function key shortcuts"
            >
              {functionKeyLegends.map((legend, index) => (
                <Typography
                  key={index}
                  variant="body2"
                  sx={{
                    color: accentColor,
                    fontFamily: 'monospace',
                    fontSize: { xs: '0.75rem', sm: '0.875rem' },
                    fontWeight: 500,
                    letterSpacing: '0.5px'
                  }}
                  aria-label={`Function key: ${legend}`}
                >
                  {legend}
                </Typography>
              ))}
            </Box>
            <Divider sx={{ mb: 2 }} />
          </>
        )}

        {/* Main Footer Content */}
        <Box
          sx={{
            display: 'flex',
            flexDirection: { xs: 'column', md: 'row' },
            justifyContent: 'space-between',
            alignItems: { xs: 'center', md: 'flex-start' },
            gap: { xs: 2, md: 4 },
            textAlign: { xs: 'center', md: 'left' }
          }}
        >
          {/* Copyright and License Section */}
          <Box sx={{ flex: { xs: '1 1 100%', md: '1 1 40%' } }}>
            <Typography
              variant="body2"
              color={textColor}
              sx={{ mb: 0.5, fontSize: { xs: '0.75rem', sm: '0.875rem' } }}
              aria-label="Copyright notice"
            >
              Copyright © {currentYear} Amazon.com, Inc. or its affiliates.
            </Typography>
            <Typography
              variant="body2"
              color={textColor}
              sx={{ mb: 0.5, fontSize: { xs: '0.75rem', sm: '0.875rem' } }}
            >
              All Rights Reserved.
            </Typography>
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ display: 'block', mt: 1, fontSize: { xs: '0.65rem', sm: '0.75rem' } }}
              aria-label="License information"
            >
              Licensed under the{' '}
              <Link
                href="http://www.apache.org/licenses/LICENSE-2.0"
                target="_blank"
                rel="noopener noreferrer"
                sx={{ color: accentColor, textDecoration: 'none', '&:hover': { textDecoration: 'underline' } }}
                aria-label="Apache License 2.0 - opens in new window"
              >
                Apache License, Version 2.0
              </Link>
            </Typography>
          </Box>

          {/* Application Information Section */}
          <Box sx={{ flex: { xs: '1 1 100%', md: '1 1 30%' } }}>
            <Typography
              variant="body2"
              color={textColor}
              sx={{ mb: 0.5, fontWeight: 500, fontSize: { xs: '0.75rem', sm: '0.875rem' } }}
              aria-label="Application name and version"
            >
              {APP_CONFIG.APP_NAME} v{APP_CONFIG.VERSION}
            </Typography>
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ display: 'block', fontSize: { xs: '0.65rem', sm: '0.75rem' } }}
              aria-label="Application description"
            >
              Credit Card Management System
            </Typography>
            <Typography
              variant="caption"
              color="text.secondary"
              sx={{ display: 'block', mt: 0.5, fontSize: { xs: '0.65rem', sm: '0.75rem' } }}
              aria-label="Modernization note"
            >
              Mainframe Modernization Project
            </Typography>
          </Box>

          {/* Links Section */}
          {variant === 'default' && (
            <Box
              sx={{
                flex: { xs: '1 1 100%', md: '1 1 30%' },
                display: 'flex',
                flexDirection: { xs: 'row', md: 'column' },
                flexWrap: 'wrap',
                justifyContent: 'center',
                gap: { xs: 2, md: 0.5 }
              }}
              aria-label="Footer navigation links"
            >
              <Link
                href="/docs"
                sx={{
                  color: theme.palette.primary.main,
                  textDecoration: 'none',
                  fontSize: { xs: '0.75rem', sm: '0.875rem' },
                  '&:hover': { textDecoration: 'underline' }
                }}
                aria-label="Documentation"
              >
                Documentation
              </Link>
              <Link
                href="/privacy"
                sx={{
                  color: theme.palette.primary.main,
                  textDecoration: 'none',
                  fontSize: { xs: '0.75rem', sm: '0.875rem' },
                  '&:hover': { textDecoration: 'underline' }
                }}
                aria-label="Privacy Policy"
              >
                Privacy Policy
              </Link>
              <Link
                href="/terms"
                sx={{
                  color: theme.palette.primary.main,
                  textDecoration: 'none',
                  fontSize: { xs: '0.75rem', sm: '0.875rem' },
                  '&:hover': { textDecoration: 'underline' }
                }}
                aria-label="Terms of Service"
              >
                Terms of Service
              </Link>
              <Link
                href="/support"
                sx={{
                  color: theme.palette.primary.main,
                  textDecoration: 'none',
                  fontSize: { xs: '0.75rem', sm: '0.875rem' },
                  '&:hover': { textDecoration: 'underline' }
                }}
                aria-label="Support"
              >
                Support
              </Link>
            </Box>
          )}
        </Box>

        {/* Build Information - Replicates BMS version footer comment */}
        {variant === 'default' && (
          <Box sx={{ mt: 2, textAlign: 'center' }}>
            <Typography
              variant="caption"
              color="text.disabled"
              sx={{
                fontFamily: 'monospace',
                fontSize: { xs: '0.625rem', sm: '0.7rem' },
                display: 'block'
              }}
              aria-label="Build information"
            >
              Build: {APP_CONFIG.APP_NAME}_v{APP_CONFIG.VERSION} | {new Date().toISOString().split('T')[0]}
            </Typography>
          </Box>
        )}
      </Container>
    </Box>
  );
};

// PropTypes for type checking and documentation
Footer.propTypes = {
  /**
   * Whether to display function key legends from BMS mapset footers
   * Set to false for minimal footer variant
   */
  showFunctionKeys: PropTypes.bool,
  
  /**
   * Footer variant:
   * - 'default': Full footer with all sections and links
   * - 'minimal': Compact footer with only copyright and version
   */
  variant: PropTypes.oneOf(['default', 'minimal'])
};

// Default export as specified in schema
export default Footer;
