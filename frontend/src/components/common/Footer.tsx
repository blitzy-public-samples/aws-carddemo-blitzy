/**
 * Footer Component
 * 
 * Reusable page footer component displaying copyright information, application version,
 * and optional navigation links. Provides consistent footer across all pages with
 * Material-UI styling, responsive layout, and sticky positioning option.
 * 
 * Features:
 * - Copyright notice with dynamic year based on Apache 2.0 license
 * - Application version display from environment variable or default
 * - Optional navigation links (Help, Privacy Policy, Terms of Service)
 * - Responsive layout (vertical on mobile, horizontal on desktop)
 * - Theme-aware styling (adapts to light/dark mode)
 * - Optional sticky positioning at bottom of viewport
 * 
 * Converted from COBOL context:
 * - COBOL: No footer concept in 3270 terminal screens
 * - BMS: Screen ends after last field, no persistent footer
 * - React: Modern web footer with copyright, links, and version information
 * 
 * This is an enhancement necessary for modern web applications and licensing compliance,
 * per Agent Action Plan Section 0.7.1 MINIMAL CHANGE CLAUSE.
 * 
 * @module components/common/Footer
 */

import React from 'react';
import { Box, Container, Typography, Link, Divider } from '@mui/material';

/**
 * Props interface for Footer component
 */
interface FooterProps {
  /**
   * If true, footer will stick to bottom of viewport.
   * Useful for pages with minimal content to keep footer at bottom.
   * Default: false
   */
  sticky?: boolean;
  
  /**
   * If true, display navigation links (Help, Privacy Policy, Terms of Service).
   * Default: true
   */
  showLinks?: boolean;
  
  /**
   * Application version string to display.
   * Default: Value from VITE_APP_VERSION environment variable or '1.0.0'
   * Example format from COBOL: 'CardDemo_v1.0-15-g27d6c6f-68'
   */
  version?: string;
}

/**
 * Footer Component
 * 
 * Displays copyright information, application version, and optional navigation links
 * at the bottom of every page. Implements responsive design with Material-UI components.
 * 
 * @param props - Component props
 * @returns React footer component
 * 
 * @example
 * // Standard footer
 * <Footer />
 * 
 * @example
 * // Sticky footer for pages with minimal content
 * <Footer sticky />
 * 
 * @example
 * // Footer without navigation links
 * <Footer showLinks={false} />
 * 
 * @example
 * // Footer with custom version
 * <Footer version="CardDemo v1.0.0" />
 */
const Footer: React.FC<FooterProps> = ({ 
  sticky = false, 
  showLinks = true,
  version = import.meta.env['VITE_APP_VERSION'] || '1.0.0'
}) => {
  // Get current year for copyright notice - updates automatically
  const currentYear = new Date().getFullYear();

  return (
    <Box
      component="footer"
      sx={{
        py: 3, // Vertical padding: 24px
        px: 2, // Horizontal padding: 16px
        mt: 'auto', // Push footer to bottom in flex container
        backgroundColor: (theme) =>
          theme.palette.mode === 'light'
            ? theme.palette.grey[200]  // Light gray for light mode
            : theme.palette.grey[800], // Dark gray for dark mode
        ...(sticky && {
          position: 'sticky',
          bottom: 0,
          width: '100%',
          zIndex: 1000
        })
      }}
    >
      <Container maxWidth="lg">
        {/* Main footer content - responsive flex layout */}
        <Box
          sx={{
            display: 'flex',
            flexDirection: { xs: 'column', sm: 'row' }, // Vertical on mobile, horizontal on desktop
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 2 // 16px gap between items
          }}
        >
          {/* Copyright and Version Information */}
          <Box
            sx={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: { xs: 'center', sm: 'flex-start' }, // Centered on mobile, left-aligned on desktop
              gap: 0.5 // 4px gap between lines
            }}
          >
            <Typography variant="body2" color="text.secondary">
              © {currentYear} CardDemo. All rights reserved.
            </Typography>
            <Typography variant="caption" color="text.secondary">
              Version {version}
            </Typography>
          </Box>

          {/* Navigation Links */}
          {showLinks && (
            <Box
              sx={{
                display: 'flex',
                gap: 2, // 16px gap between links
                flexWrap: 'wrap',
                justifyContent: 'center',
                alignItems: 'center'
              }}
            >
              <Link
                href="/help"
                underline="hover"
                color="text.secondary"
                sx={{ 
                  cursor: 'pointer',
                  fontSize: '0.875rem', // Match body2 size
                  '&:hover': {
                    color: 'primary.main' // Highlight on hover
                  }
                }}
              >
                Help
              </Link>
              <Divider orientation="vertical" flexItem />
              <Link
                href="/privacy"
                underline="hover"
                color="text.secondary"
                sx={{ 
                  cursor: 'pointer',
                  fontSize: '0.875rem',
                  '&:hover': {
                    color: 'primary.main'
                  }
                }}
              >
                Privacy Policy
              </Link>
              <Divider orientation="vertical" flexItem />
              <Link
                href="/terms"
                underline="hover"
                color="text.secondary"
                sx={{ 
                  cursor: 'pointer',
                  fontSize: '0.875rem',
                  '&:hover': {
                    color: 'primary.main'
                  }
                }}
              >
                Terms of Service
              </Link>
            </Box>
          )}
        </Box>

        {/* License Information - Apache 2.0 */}
        <Box sx={{ mt: 2 }}>
          <Typography 
            variant="caption" 
            color="text.secondary" 
            align="center" 
            display="block"
            sx={{
              fontSize: '0.75rem' // Smaller text for license notice
            }}
          >
            Licensed under the Apache License, Version 2.0
          </Typography>
        </Box>
      </Container>
    </Box>
  );
};

export default Footer;
