/**
 * Header Component - Application Header with Navigation and User Info
 * 
 * Converted from BMS screen header pattern (COTTL01Y.cpy and COMEN01.bms):
 * - COTTL01Y.cpy: CCDA-TITLE01 = "AWS Mainframe Modernization"
 * - COTTL01Y.cpy: CCDA-TITLE02 = "CardDemo"
 * - COMEN01.bms: Header fields include Tran (TRNNAME), Program (PGMNAME), Date (CURDATE), Time (CURTIME)
 * 
 * Original BMS Header Structure (Lines 1-2 of every screen):
 * Line 1: "Tran: xxxx  [TITLE01]  Date: mm/dd/yy"
 * Line 2: "Prog: xxxxxxxx  [TITLE02]  Time: hh:mm:ss"
 * 
 * React Implementation Features:
 * - Material-UI AppBar with sticky positioning at top of viewport
 * - Application title with primary/subtitle layout from COTTL01Y.cpy
 * - Hamburger menu button to toggle navigation drawer (authenticated users only)
 * - User information display with name and role badge from CSUSR01Y.cpy
 * - Logout button with icon for session termination
 * - Theme toggle button for light/dark mode switching (optional)
 * - Responsive layout: collapse user info on mobile, show icons only
 * - Integration with AuthContext for authentication state management
 * - Integration with Navigation component for menu drawer
 * 
 * BMS Header Pattern Transformation:
 * COBOL (Fixed 80-character header):
 * Line 1: Tran: MENU      AWS Mainframe Modernization       Date: 07/25/22
 * Line 2: Prog: COMEN01C              CardDemo              Time: 14:30:45
 * 
 * React (Responsive AppBar):
 * - AppBar with sticky position
 * - MenuIcon button for navigation toggle
 * - Typography for title "AWS Mainframe Modernization"
 * - Typography caption for subtitle "CardDemo"
 * - Box displaying user.firstName and user.lastName
 * - Chip label showing user role (Admin/User)
 * - Button for Logout action
 * 
 * Per Agent Action Plan Section 0.4.19 and Section 0.7.1 MINIMAL CHANGE CLAUSE:
 * This component transforms BMS header fields from COTTL01Y.cpy and COMEN01.bms
 * into modern React AppBar while preserving exact title text and functional equivalence.
 * No unnecessary features beyond standard web application header patterns.
 * 
 * Responsive Behavior:
 * - Desktop (≥600px): Full title, subtitle, user name, role badge, logout text
 * - Mobile (<600px): Smaller title, hide subtitle, hide user name, icon-only logout
 * 
 * Authentication Awareness:
 * - When not authenticated (login page): Show title only, no menu/logout buttons
 * - When authenticated: Show full header with navigation menu and logout functionality
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 * 
 * @see app/cpy/COTTL01Y.cpy - Original COBOL screen title definitions
 * @see app/bms/COMEN01.bms - Original BMS menu header structure
 * @see frontend/src/hooks/useAuth.ts - Authentication context hook
 * @see frontend/src/components/common/Navigation.tsx - Navigation drawer component
 */

import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  AppBar,
  Toolbar,
  Typography,
  IconButton,
  Box,
  Button,
  Chip,
  useTheme,
  useMediaQuery
} from '@mui/material';
import {
  Menu as MenuIcon,
  Logout,
  AccountCircle,
  Brightness4,
  Brightness7
} from '@mui/icons-material';
import { useAuth } from '../../hooks/useAuth';
import Navigation from './Navigation';

/**
 * Header component props interface
 */
interface HeaderProps {
  /**
   * Optional callback function to toggle theme mode (light/dark)
   * When provided, displays theme toggle button in header
   * 
   * @example
   * const [themeMode, setThemeMode] = useState<'light' | 'dark'>('light');
   * const handleThemeToggle = () => setThemeMode(prev => prev === 'light' ? 'dark' : 'light');
   * <Header onThemeToggle={handleThemeToggle} />
   */
  onThemeToggle?: () => void;
}

/**
 * Header Component
 * 
 * Application header component with navigation menu access, user information,
 * and logout functionality. Replaces BMS screen header fields with modern
 * Material-UI AppBar providing consistent header across all application pages.
 * 
 * Features:
 * - Application branding with title from COTTL01Y.cpy
 * - Hamburger menu button for navigation drawer access
 * - User greeting with role badge (Admin/User)
 * - Logout button for session termination
 * - Optional theme toggle for light/dark mode
 * - Responsive design adapting to mobile/desktop
 * - Sticky positioning at top of viewport
 * 
 * Usage Examples:
 * 
 * Basic usage (no theme toggle):
 * @example
 * <Header />
 * 
 * With theme toggle:
 * @example
 * const [themeMode, setThemeMode] = useState('light');
 * <Header onThemeToggle={() => setThemeMode(prev => prev === 'light' ? 'dark' : 'light')} />
 * 
 * In page layout:
 * @example
 * <Box sx=\{\{ display: 'flex', flexDirection: 'column', minHeight: '100vh' \}\}>
 *   <Header />
 *   <Box component="main" sx=\{\{ flexGrow: 1, p: 3 \}\}>
 *     Page content goes here
 *   </Box>
 *   <Footer />
 * </Box>
 * 
 * @param props - Component props
 * @returns React component rendering application header
 */
const Header: React.FC<HeaderProps> = ({ onThemeToggle }) => {
  // State for navigation drawer open/closed
  const [navOpen, setNavOpen] = useState<boolean>(false);
  
  // Authentication context from COBOL COCOM01Y.cpy COMMAREA equivalent
  const { user, logout, isAuthenticated } = useAuth();
  
  // React Router navigation for post-logout redirect
  const navigate = useNavigate();
  
  // Material-UI theme and responsive breakpoints
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down('sm'));

  /**
   * Handle logout button click
   * Replaces COBOL: EXEC CICS SIGNOFF END-EXEC
   * Clears authentication state and navigates to login page
   */
  const handleLogout = (): void => {
    logout();
    navigate('/login');
  };

  /**
   * Toggle navigation drawer open/closed
   * Controls visibility of Navigation component
   */
  const toggleNavigation = (): void => {
    setNavOpen(!navOpen);
  };

  return (
    <>
      {/* Application Header AppBar */}
      <AppBar position="sticky" elevation={2}>
        <Toolbar>
          {/* Navigation Menu Button (only when authenticated) */}
          {isAuthenticated && (
            <IconButton
              edge="start"
              color="inherit"
              aria-label="menu"
              onClick={toggleNavigation}
              sx={{ mr: 2 }}
            >
              <MenuIcon />
            </IconButton>
          )}

          {/* Application Title - from COTTL01Y.cpy */}
          <Box sx={{ flexGrow: 1 }}>
            {/* Primary Title: CCDA-TITLE01 = "AWS Mainframe Modernization" */}
            <Typography 
              variant={isMobile ? 'h6' : 'h5'} 
              component="h1"
              noWrap
            >
              AWS Mainframe Modernization
            </Typography>
            {/* Subtitle: CCDA-TITLE02 = "CardDemo" (hide on mobile) */}
            <Typography 
              variant="caption" 
              component="div"
              sx={{ display: { xs: 'none', sm: 'block' } }}
            >
              CardDemo
            </Typography>
          </Box>

          {/* User Info and Actions (only when authenticated) */}
          {isAuthenticated && user && (
            <Box 
              sx={{ 
                display: 'flex', 
                alignItems: 'center', 
                gap: 2 
              }}
            >
              {/* User Information Display (hide on mobile) */}
              {!isMobile && (
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                  <AccountCircle />
                  <Box>
                    {/* User name from CSUSR01Y.cpy: SEC-USR-FNAME, SEC-USR-LNAME */}
                    <Typography variant="body2">
                      {user.userFirstName} {user.userLastName}
                    </Typography>
                    {/* Role badge from CSUSR01Y.cpy: SEC-USR-TYPE ('A' = Admin, 'U' = User) */}
                    <Chip
                      label={user.userType === 'A' ? 'Admin' : 'User'}
                      size="small"
                      color={user.userType === 'A' ? 'secondary' : 'default'}
                      sx={{ height: 20 }}
                    />
                  </Box>
                </Box>
              )}

              {/* Theme Toggle Button (optional) */}
              {onThemeToggle && (
                <IconButton 
                  color="inherit" 
                  onClick={onThemeToggle}
                  aria-label="toggle theme"
                >
                  {theme.palette.mode === 'dark' ? <Brightness7 /> : <Brightness4 />}
                </IconButton>
              )}

              {/* Logout Button - replaces COBOL EXEC CICS SIGNOFF */}
              <Button
                color="inherit"
                startIcon={<Logout />}
                onClick={handleLogout}
                aria-label="logout"
              >
                {!isMobile && 'Logout'}
              </Button>
            </Box>
          )}
        </Toolbar>
      </AppBar>

      {/* Navigation Drawer - replaces BMS menu screens COMEN01/COADM01 */}
      {isAuthenticated && (
        <Navigation 
          open={navOpen} 
          onClose={() => setNavOpen(false)} 
        />
      )}
    </>
  );
};

/**
 * Default export for Header component
 * Per Agent Action Plan Section 0.4.19 export requirements
 */
export default Header;
