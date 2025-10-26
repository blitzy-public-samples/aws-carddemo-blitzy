/**
 * LoadingSpinner Component
 * 
 * Reusable loading indicator component using Material-UI CircularProgress.
 * Provides consistent loading feedback across the application during async operations.
 * 
 * Converted from COBOL CICS wait states to modern React async UI pattern.
 * Replaces blocking mainframe screen waits with non-blocking animated feedback.
 * 
 * Purpose:
 * - Display loading indicator during API calls, data fetching, and form submissions
 * - Provide visual feedback for async operations (replaces COBOL EXEC CICS WAIT)
 * - Support both full-screen overlay and inline display modes
 * - Offer configurable size and color variants
 * 
 * Usage Examples:
 * 
 * 1. Lazy Loading Fallback (App.tsx):
 *    <Suspense fallback={<LoadingSpinner fullScreen message="Loading page..." />}>
 * 
 * 2. Inline Loading State:
 *    {isLoading && <LoadingSpinner size={30} message="Fetching accounts..." />}
 * 
 * 3. Full Screen Loading:
 *    {isSubmitting && <LoadingSpinner fullScreen message="Processing transaction..." />}
 * 
 * 4. Minimal Spinner:
 *    <LoadingSpinner size={20} />
 * 
 * @module components/common/LoadingSpinner
 */

import React from 'react';
import { Box, CircularProgress, Typography } from '@mui/material';

/**
 * LoadingSpinner component props interface
 */
interface LoadingSpinnerProps {
  /**
   * Size of the spinner in pixels or as a CSS value
   * @default 40
   * @example size={40} // 40px diameter
   * @example size="3rem" // 3rem diameter
   */
  size?: number | string;

  /**
   * Optional loading message to display below the spinner
   * @example message="Loading accounts..."
   * @example message="Processing transaction..."
   */
  message?: string;

  /**
   * If true, displays as full-screen overlay covering entire viewport
   * If false, displays as inline element within parent container
   * @default false
   */
  fullScreen?: boolean;

  /**
   * Color variant of the spinner matching Material-UI theme
   * - 'primary': Theme primary color (typically blue)
   * - 'secondary': Theme secondary color (typically purple)
   * - 'inherit': Inherits color from parent element
   * @default 'primary'
   */
  color?: 'primary' | 'secondary' | 'inherit';
}

/**
 * LoadingSpinner Component
 * 
 * Displays a centered circular progress indicator with optional message text.
 * Supports both full-screen and inline display modes.
 * 
 * Features:
 * - Centered flexbox layout (horizontal and vertical centering)
 * - Animated circular spinner (CSS-based GPU-accelerated animation)
 * - Optional message text below spinner
 * - Configurable size (20px small, 40px medium, 60px large)
 * - Theme-aware color variants
 * - Responsive height (100vh full-screen, 200px inline)
 * - Accessibility: implicit progressbar role, screen reader friendly
 * 
 * COBOL to React Migration Context:
 * - COBOL: EXEC CICS WAIT EXTERNAL (blocking wait, frozen screen)
 * - React: LoadingSpinner (async UI feedback, non-blocking)
 * - BMS: No loading indicator (3270 terminal limitations)
 * - React: Animated spinner with progress feedback
 * 
 * @param props - Component properties
 * @returns React component displaying loading spinner
 */
const LoadingSpinner: React.FC<LoadingSpinnerProps> = ({ 
  size = 40, 
  message, 
  fullScreen = false,
  color = 'primary'
}) => {
  return (
    <Box
      display="flex"
      flexDirection="column"
      justifyContent="center"
      alignItems="center"
      minHeight={fullScreen ? '100vh' : '200px'}
      gap={2}
      role="status"
      aria-live="polite"
      aria-busy="true"
    >
      <CircularProgress 
        size={size} 
        color={color}
        aria-label={message || 'Loading'}
      />
      {message && (
        <Typography 
          variant="body1" 
          color="text.secondary"
          align="center"
        >
          {message}
        </Typography>
      )}
    </Box>
  );
};

export default LoadingSpinner;
