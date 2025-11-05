/**
 * Loading Component
 * 
 * React loading component providing visual feedback during asynchronous operations
 * with spinner animation and skeleton screens. Modern equivalent to 3270 terminal
 * wait states and CICS transaction processing delays.
 * 
 * This component ensures users receive immediate feedback that data fetching or
 * processing is in progress rather than experiencing unresponsive blank screens,
 * maintaining the sub-200ms transaction response time requirement by providing
 * visual indicators for operations approaching this threshold.
 * 
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0
 */

import { useState, useEffect } from 'react';
import PropTypes from 'prop-types';
import {
  Box,
  CircularProgress,
  LinearProgress,
  Skeleton,
  Typography,
  Backdrop,
  Fade,
} from '@mui/material';

/**
 * Loading component with multiple display variants for different use cases
 * 
 * Variants:
 * - spinner: Circular spinner for quick operations (card authorization, account lookup under 200ms)
 * - skeleton: Skeleton screens for list views matching BMS pagination patterns
 * - linear: Linear progress bar for batch operations or longer-running processes
 * 
 * Overlay modes:
 * - fullscreen: Backdrop overlay covering entire viewport
 * - inline: Inline loading indicator within container
 * - modal: Loading indicator in modal dialog context
 * 
 * @param {Object} props - Component props
 * @param {string} props.type - Loading indicator type ('spinner'|'skeleton'|'linear')
 * @param {string} props.size - Size of loading indicator ('small'|'medium'|'large')
 * @param {string} props.message - Optional loading message text
 * @param {string} props.overlay - Overlay mode ('fullscreen'|'inline'|'modal')
 * @param {number} props.progress - Optional progress percentage (0-100) for determinate operations
 * @param {string} props.variant - Skeleton variant ('account'|'card'|'transaction'|'form')
 * @param {boolean} props.show - Whether to show the loading indicator
 * @returns {JSX.Element} Loading component
 */
const Loading = ({
  type = 'spinner',
  size = 'medium',
  message = '',
  overlay = 'inline',
  progress = null,
  variant = 'default',
  show = true,
}) => {
  // State to control fade-in animation delay (300ms to prevent flash for fast operations)
  const [shouldShow, setShouldShow] = useState(false);

  // Implement 300ms delay to prevent flash of loading state for operations completing under 200ms
  useEffect(() => {
    if (show) {
      const timer = setTimeout(() => {
        setShouldShow(true);
      }, 300);

      return () => clearTimeout(timer);
    } else {
      setShouldShow(false);
    }
  }, [show]);

  // Size mapping for CircularProgress and other components
  const sizeMap = {
    small: 24,
    medium: 40,
    large: 60,
  };

  // Theme colors matching BMS color scheme (BLUE primary, TURQUOISE secondary)
  const primaryColor = '#1976d2'; // BLUE primary
  // eslint-disable-next-line no-unused-vars
  const secondaryColor = '#00acc1'; // TURQUOISE secondary - Reserved for future UI enhancements

  /**
   * Renders spinner loading indicator using Material-UI CircularProgress
   * Used for quick operations (card authorization, account lookup under 200ms)
   */
  const renderSpinner = () => {
    const isDeterminate = progress !== null && progress >= 0 && progress <= 100;

    return (
      <Box
        sx={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 2,
        }}
        role="status"
        aria-live="polite"
        aria-label="Loading"
      >
        <CircularProgress
          variant={isDeterminate ? 'determinate' : 'indeterminate'}
          value={isDeterminate ? progress : undefined}
          size={sizeMap[size]}
          sx={{ color: primaryColor }}
        />
        {isDeterminate && (
          <Typography
            variant="body2"
            sx={{ color: primaryColor, fontWeight: 'medium' }}
          >
            {Math.round(progress)}%
          </Typography>
        )}
        {message && (
          <Typography
            variant="body2"
            sx={{ color: 'text.secondary', mt: 1 }}
          >
            {message}
          </Typography>
        )}
        {/* ARIA live region for screen reader accessibility */}
        <span className="sr-only" aria-live="assertive" aria-atomic="true">
          {message || 'Loading...'}
        </span>
      </Box>
    );
  };

  /**
   * Renders linear progress bar using Material-UI LinearProgress
   * Used for batch operations or longer-running processes
   */
  const renderLinear = () => {
    const isDeterminate = progress !== null && progress >= 0 && progress <= 100;

    return (
      <Box
        sx={{
          width: '100%',
          display: 'flex',
          flexDirection: 'column',
          gap: 1,
        }}
        role="status"
        aria-live="polite"
        aria-label="Loading"
      >
        {message && (
          <Typography
            variant="body2"
            sx={{ color: 'text.secondary', mb: 1 }}
          >
            {message}
          </Typography>
        )}
        <LinearProgress
          variant={isDeterminate ? 'determinate' : 'indeterminate'}
          value={isDeterminate ? progress : undefined}
          sx={{
            height: size === 'large' ? 8 : size === 'small' ? 4 : 6,
            borderRadius: 1,
            backgroundColor: 'rgba(25, 118, 210, 0.1)',
            '& .MuiLinearProgress-bar': {
              backgroundColor: primaryColor,
            },
          }}
        />
        {isDeterminate && (
          <Typography
            variant="caption"
            sx={{ color: primaryColor, fontWeight: 'medium', textAlign: 'right' }}
          >
            {Math.round(progress)}%
          </Typography>
        )}
        {/* ARIA live region for screen reader accessibility */}
        <span className="sr-only" aria-live="assertive" aria-atomic="true">
          {message || 'Loading...'}
        </span>
      </Box>
    );
  };

  /**
   * Renders skeleton screen variant using Material-UI Skeleton components
   * Used for list views (7 cards per page, 10 transactions per page) matching BMS pagination
   */
  const renderSkeleton = () => {
    // Skeleton variants matching major screen layouts
    const skeletonVariants = {
      // Account list skeleton (default 7 items)
      account: (
        <Box sx={{ width: '100%', p: 2 }}>
          {[...Array(7)].map((_, index) => (
            <Box key={index} sx={{ mb: 2, p: 2, border: '1px solid #e0e0e0', borderRadius: 1 }}>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 1 }}>
                <Skeleton variant="text" width="40%" height={24} />
                <Skeleton variant="text" width="20%" height={24} />
              </Box>
              <Skeleton variant="text" width="60%" height={20} />
              <Box sx={{ display: 'flex', gap: 2, mt: 1 }}>
                <Skeleton variant="text" width="30%" height={20} />
                <Skeleton variant="text" width="30%" height={20} />
              </Box>
            </Box>
          ))}
        </Box>
      ),

      // Card list skeleton (7 cards per page per BMS pagination)
      card: (
        <Box sx={{ width: '100%', p: 2 }}>
          {[...Array(7)].map((_, index) => (
            <Box key={index} sx={{ mb: 2, p: 2, border: '1px solid #e0e0e0', borderRadius: 1 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
                <Skeleton variant="rectangular" width={60} height={40} />
                <Box sx={{ flex: 1 }}>
                  <Skeleton variant="text" width="60%" height={24} />
                  <Skeleton variant="text" width="40%" height={20} />
                </Box>
                <Skeleton variant="circular" width={24} height={24} />
              </Box>
            </Box>
          ))}
        </Box>
      ),

      // Transaction list skeleton (10 transactions per page per BMS pagination)
      transaction: (
        <Box sx={{ width: '100%', p: 2 }}>
          {[...Array(10)].map((_, index) => (
            <Box key={index} sx={{ mb: 1.5, p: 1.5, border: '1px solid #e0e0e0', borderRadius: 1 }}>
              <Box sx={{ display: 'flex', justifyContent: 'space-between', mb: 0.5 }}>
                <Skeleton variant="text" width="30%" height={20} />
                <Skeleton variant="text" width="20%" height={20} />
              </Box>
              <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                <Skeleton variant="text" width="50%" height={18} />
                <Skeleton variant="text" width="15%" height={18} />
              </Box>
            </Box>
          ))}
        </Box>
      ),

      // Form skeleton
      form: (
        <Box sx={{ width: '100%', p: 2 }}>
          <Skeleton variant="text" width="40%" height={32} sx={{ mb: 3 }} />
          {[...Array(5)].map((_, index) => (
            <Box key={index} sx={{ mb: 2 }}>
              <Skeleton variant="text" width="30%" height={20} sx={{ mb: 0.5 }} />
              <Skeleton variant="rectangular" width="100%" height={40} />
            </Box>
          ))}
          <Box sx={{ display: 'flex', gap: 2, mt: 3 }}>
            <Skeleton variant="rectangular" width={100} height={36} />
            <Skeleton variant="rectangular" width={100} height={36} />
          </Box>
        </Box>
      ),

      // Default skeleton
      default: (
        <Box sx={{ width: '100%', p: 2 }}>
          <Skeleton variant="text" width="60%" height={32} sx={{ mb: 2 }} />
          <Skeleton variant="rectangular" width="100%" height={200} sx={{ mb: 2 }} />
          <Skeleton variant="text" width="80%" height={24} sx={{ mb: 1 }} />
          <Skeleton variant="text" width="90%" height={24} />
        </Box>
      ),
    };

    return (
      <Box
        role="status"
        aria-live="polite"
        aria-label="Loading content"
      >
        {skeletonVariants[variant] || skeletonVariants.default}
        {message && (
          <Typography
            variant="body2"
            sx={{ color: 'text.secondary', textAlign: 'center', mt: 2 }}
          >
            {message}
          </Typography>
        )}
        {/* ARIA live region for screen reader accessibility */}
        <span className="sr-only" aria-live="assertive" aria-atomic="true">
          {message || 'Loading content...'}
        </span>
      </Box>
    );
  };

  /**
   * Renders the appropriate loading indicator based on type prop
   */
  const renderLoadingContent = () => {
    switch (type) {
      case 'linear':
        return renderLinear();
      case 'skeleton':
        return renderSkeleton();
      case 'spinner':
      default:
        return renderSpinner();
    }
  };

  // Don't render anything if show is false and delay hasn't started
  if (!show && !shouldShow) {
    return null;
  }

  // Render with fade-in animation (300ms delay)
  const loadingContent = (
    <Fade in={shouldShow} timeout={300}>
      <Box>
        {renderLoadingContent()}
      </Box>
    </Fade>
  );

  // Render based on overlay mode
  if (overlay === 'fullscreen') {
    // Fullscreen backdrop overlay
    return (
      <Backdrop
        open={shouldShow}
        sx={{
          color: '#fff',
          zIndex: (theme) => theme.zIndex.drawer + 1,
          backgroundColor: 'rgba(0, 0, 0, 0.7)',
        }}
      >
        {loadingContent}
      </Backdrop>
    );
  } else if (overlay === 'modal') {
    // Modal dialog loading
    return (
      <Box
        sx={{
          position: 'absolute',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          backgroundColor: 'rgba(255, 255, 255, 0.9)',
          zIndex: 1,
        }}
      >
        {loadingContent}
      </Box>
    );
  } else {
    // Inline loading (default)
    return (
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          minHeight: type === 'linear' ? 'auto' : size === 'large' ? 200 : size === 'small' ? 80 : 120,
          width: '100%',
        }}
      >
        {loadingContent}
      </Box>
    );
  }
};

// PropTypes for runtime type checking and inline documentation
Loading.propTypes = {
  // Loading indicator type
  type: PropTypes.oneOf(['spinner', 'skeleton', 'linear']),
  
  // Size of loading indicator
  size: PropTypes.oneOf(['small', 'medium', 'large']),
  
  // Optional loading message text
  message: PropTypes.string,
  
  // Overlay mode for loading indicator placement
  overlay: PropTypes.oneOf(['fullscreen', 'inline', 'modal']),
  
  // Optional progress percentage (0-100) for determinate operations
  progress: PropTypes.number,
  
  // Skeleton variant for different screen layouts
  variant: PropTypes.oneOf(['default', 'account', 'card', 'transaction', 'form']),
  
  // Whether to show the loading indicator
  show: PropTypes.bool,
};

export default Loading;
