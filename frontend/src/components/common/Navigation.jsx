/**
 * Navigation Component
 * 
 * React Navigation component providing main menu navigation with role-based access control
 * and breadcrumb trails for the CardDemo application.
 * 
 * This component transforms mainframe BMS screen navigation patterns to modern React routing:
 * - COMEN01.bms (Main Menu) → Main navigation menu
 * - COADM01.bms (Admin Menu) → Admin-only navigation items
 * - COCOM01Y.cpy user type mapping → Role-based menu filtering
 * 
 * Functional Equivalence:
 * - Replaces CICS XCTL program-to-program navigation with React Router
 * - Preserves BMS menu structure (OPTN001-OPTN012 menu options)
 * - Implements role-based access control matching RACF security patterns
 * - Provides breadcrumb navigation for hierarchical screen relationships
 * 
 * Source COBOL References:
 * - app/bms/COMEN01.bms: Main menu screen layout (12 options)
 * - app/bms/COADM01.bms: Admin menu screen layout
 * - app/cpy/COCOM01Y.cpy: User type definitions (CDEMO-USRTYP-ADMIN, CDEMO-USRTYP-USER)
 * 
 * @module components/common/Navigation
 */

import React, { useState, useEffect, useMemo } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import {
  Box,
  Drawer,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Divider,
  Breadcrumbs,
  Typography,
  Link as MuiLink,
  Toolbar,
  useTheme,
  useMediaQuery,
} from '@mui/material';
import {
  Home as HomeIcon,
  AccountBalance as AccountBalanceIcon,
  CreditCard as CreditCardIcon,
  Receipt as ReceiptIcon,
  Assessment as AssessmentIcon,
  Payment as PaymentIcon,
  People as PeopleIcon,
  PersonAdd as PersonAddIcon,
  Edit as EditIcon,
  Delete as DeleteIcon,
  Visibility as VisibilityIcon,
  Update as UpdateIcon,
  NavigateNext as NavigateNextIcon,
} from '@mui/icons-material';

// Import authentication context and constants
import { useAuth } from '../../context/AuthContext';
import { USER_TYPE } from '../../utils/constants';

/**
 * Drawer width constant for responsive layout
 */
const DRAWER_WIDTH = 260;

/**
 * Navigation Component
 * 
 * Provides hierarchical navigation menu with role-based filtering and breadcrumb trails.
 * Implements Material-UI Drawer for side navigation with responsive behavior.
 * 
 * Menu Structure from BMS Screens:
 * - Main Menu (COMEN01): Account Management, Card Management, Transactions, Reports
 * - Admin Menu (COADM01): User Administration (Admin users only)
 * 
 * @returns {JSX.Element} The Navigation component with drawer and breadcrumbs
 */
const Navigation = () => {
  // Access authentication context for user information and role checking
  const { isAuthenticated, userType } = useAuth();
  
  // Get current location for active route highlighting and breadcrumb generation
  const location = useLocation();
  
  // Responsive drawer state management
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down('md'));
  const [mobileOpen, setMobileOpen] = useState(false);

  /**
   * Check if current user is an administrator
   * Maps to COBOL 88-level condition: CDEMO-USRTYP-ADMIN VALUE 'A'
   */
  const isAdmin = useMemo(() => {
    return userType === USER_TYPE.ADMIN;
  }, [userType]);

  /**
   * Main menu items configuration
   * Maps to COMEN01.bms menu options (OPTN001-OPTN010)
   * Available to all authenticated users (both Admin and Regular User)
   */
  const mainMenuItems = useMemo(() => [
    {
      text: 'Main Menu',
      icon: <HomeIcon />,
      path: '/menu',
      transactionId: 'CM00', // CICS Transaction ID from COMEN01C.cbl
      description: 'Return to main menu',
    },
    {
      text: 'View Accounts',
      icon: <VisibilityIcon />,
      path: '/accounts/view',
      transactionId: 'CAVW', // CICS Transaction ID from COACTVWC.cbl
      description: 'View account information',
    },
    {
      text: 'Update Account',
      icon: <UpdateIcon />,
      path: '/accounts/update',
      transactionId: 'CAUP', // CICS Transaction ID from COACTUPC.cbl
      description: 'Update account details',
    },
    {
      text: 'List Cards',
      icon: <CreditCardIcon />,
      path: '/cards',
      transactionId: 'CCLI', // CICS Transaction ID from COCRDLIC.cbl
      description: 'View card list (7 per page)',
    },
    {
      text: 'Card Details',
      icon: <VisibilityIcon />,
      path: '/cards/details',
      transactionId: 'CCDL', // CICS Transaction ID from COCRDSLC.cbl
      description: 'View card detail information',
    },
    {
      text: 'Update Card',
      icon: <EditIcon />,
      path: '/cards/update',
      transactionId: 'CCUP', // CICS Transaction ID from COCRDUPC.cbl
      description: 'Update card information',
    },
    {
      text: 'List Transactions',
      icon: <ReceiptIcon />,
      path: '/transactions',
      transactionId: 'CT00', // CICS Transaction ID from COTRN00C.cbl
      description: 'View transaction list (10 per page)',
    },
    {
      text: 'View Transaction',
      icon: <VisibilityIcon />,
      path: '/transactions/view',
      transactionId: 'CT01', // CICS Transaction ID from COTRN01C.cbl
      description: 'View transaction details',
    },
    {
      text: 'Add Transaction',
      icon: <Receipt as ReceiptIcon />,
      path: '/transactions/add',
      transactionId: 'CT02', // CICS Transaction ID from COTRN02C.cbl
      description: 'Add new transaction',
    },
    {
      text: 'Reports',
      icon: <AssessmentIcon />,
      path: '/reports',
      transactionId: 'CR00', // CICS Transaction ID from CORPT00C.cbl
      description: 'Generate transaction reports',
    },
    {
      text: 'Bill Payment',
      icon: <PaymentIcon />,
      path: '/billing/payment',
      transactionId: 'CB00', // CICS Transaction ID from COBIL00C.cbl
      description: 'Process bill payments',
    },
  ], []);

  /**
   * Admin menu items configuration
   * Maps to COADM01.bms admin menu options
   * Only visible when userType === USER_TYPE.ADMIN ('A')
   * Implements RACF-equivalent role-based access control
   */
  const adminMenuItems = useMemo(() => [
    {
      text: 'Admin Menu',
      icon: <PeopleIcon />,
      path: '/admin',
      transactionId: 'CA00', // CICS Transaction ID from COADM01C.cbl
      description: 'Administrative menu',
    },
    {
      text: 'User List',
      icon: <PeopleIcon />,
      path: '/admin/users',
      transactionId: 'CU00', // CICS Transaction ID from COUSR00C.cbl
      description: 'View user list',
    },
    {
      text: 'Add User',
      icon: <PersonAddIcon />,
      path: '/admin/users/add',
      transactionId: 'CU01', // CICS Transaction ID from COUSR01C.cbl
      description: 'Create new user',
    },
    {
      text: 'Update User',
      icon: <EditIcon />,
      path: '/admin/users/update',
      transactionId: 'CU02', // CICS Transaction ID from COUSR02C.cbl
      description: 'Update user information',
    },
    {
      text: 'Delete User',
      icon: <DeleteIcon />,
      path: '/admin/users/delete',
      transactionId: 'CU03', // CICS Transaction ID from COUSR03C.cbl
      description: 'Delete user account',
    },
  ], []);

  /**
   * Combined menu items with role-based filtering
   * Admin users see all items; Regular users see only main menu items
   * Implements functional equivalence with COBOL 88-level condition checking
   */
  const visibleMenuItems = useMemo(() => {
    if (isAdmin) {
      return [...mainMenuItems, ...adminMenuItems];
    }
    return mainMenuItems;
  }, [isAdmin, mainMenuItems, adminMenuItems]);

  /**
   * Generate breadcrumb trail based on current location
   * Provides hierarchical navigation context matching BMS screen flows
   * 
   * @returns {Array} Array of breadcrumb objects with label and path
   */
  const breadcrumbs = useMemo(() => {
    const pathParts = location.pathname.split('/').filter(Boolean);
    const crumbs = [{ label: 'Home', path: '/' }];

    let currentPath = '';
    pathParts.forEach((part, index) => {
      currentPath += `/${part}`;
      
      // Find matching menu item for breadcrumb label
      const menuItem = visibleMenuItems.find(item => item.path === currentPath);
      
      if (menuItem) {
        crumbs.push({
          label: menuItem.text,
          path: currentPath,
        });
      } else {
        // Fallback: capitalize path segment
        const label = part.charAt(0).toUpperCase() + part.slice(1);
        crumbs.push({
          label,
          path: currentPath,
        });
      }
    });

    return crumbs;
  }, [location.pathname, visibleMenuItems]);

  /**
   * Handle drawer toggle for mobile responsiveness
   */
  const handleDrawerToggle = () => {
    setMobileOpen(!mobileOpen);
  };

  /**
   * Close mobile drawer when route changes
   */
  useEffect(() => {
    if (isMobile && mobileOpen) {
      setMobileOpen(false);
    }
  }, [location.pathname, isMobile, mobileOpen]);

  /**
   * Render navigation drawer content
   * Implements Material-UI Drawer with List components
   */
  const drawerContent = (
    <Box sx={{ overflow: 'auto' }}>
      <Toolbar />
      
      {/* Main Menu Section */}
      <Typography
        variant="overline"
        sx={{ px: 2, py: 1, display: 'block', color: 'text.secondary' }}
      >
        Main Menu
      </Typography>
      <List>
        {mainMenuItems.map((item) => (
          <ListItem key={item.path} disablePadding>
            <ListItemButton
              component={NavLink}
              to={item.path}
              sx={{
                '&.active': {
                  backgroundColor: 'action.selected',
                  borderLeft: 3,
                  borderColor: 'primary.main',
                },
                '&:hover': {
                  backgroundColor: 'action.hover',
                },
              }}
            >
              <ListItemIcon sx={{ color: 'primary.main' }}>
                {item.icon}
              </ListItemIcon>
              <ListItemText 
                primary={item.text} 
                secondary={item.transactionId}
                primaryTypographyProps={{
                  fontSize: '0.9rem',
                  fontWeight: 'medium',
                }}
                secondaryTypographyProps={{
                  fontSize: '0.75rem',
                  color: 'text.secondary',
                }}
              />
            </ListItemButton>
          </ListItem>
        ))}
      </List>

      {/* Admin Menu Section - Only visible to administrators */}
      {isAdmin && (
        <>
          <Divider sx={{ my: 1 }} />
          <Typography
            variant="overline"
            sx={{ px: 2, py: 1, display: 'block', color: 'text.secondary' }}
          >
            Administration
          </Typography>
          <List>
            {adminMenuItems.map((item) => (
              <ListItem key={item.path} disablePadding>
                <ListItemButton
                  component={NavLink}
                  to={item.path}
                  sx={{
                    '&.active': {
                      backgroundColor: 'action.selected',
                      borderLeft: 3,
                      borderColor: 'secondary.main',
                    },
                    '&:hover': {
                      backgroundColor: 'action.hover',
                    },
                  }}
                >
                  <ListItemIcon sx={{ color: 'secondary.main' }}>
                    {item.icon}
                  </ListItemIcon>
                  <ListItemText 
                    primary={item.text} 
                    secondary={item.transactionId}
                    primaryTypographyProps={{
                      fontSize: '0.9rem',
                      fontWeight: 'medium',
                    }}
                    secondaryTypographyProps={{
                      fontSize: '0.75rem',
                      color: 'text.secondary',
                    }}
                  />
                </ListItemButton>
              </ListItem>
            ))}
          </List>
        </>
      )}
    </Box>
  );

  /**
   * Render breadcrumb navigation
   * Provides quick navigation to parent screens in hierarchy
   */
  const breadcrumbNavigation = isAuthenticated && breadcrumbs.length > 1 && (
    <Box sx={{ px: 2, py: 1, backgroundColor: 'background.paper' }}>
      <Breadcrumbs
        separator={<NavigateNextIcon fontSize="small" />}
        aria-label="breadcrumb navigation"
      >
        {breadcrumbs.map((crumb, index) => {
          const isLast = index === breadcrumbs.length - 1;
          
          return isLast ? (
            <Typography
              key={crumb.path}
              color="text.primary"
              sx={{ fontWeight: 'medium', fontSize: '0.875rem' }}
            >
              {crumb.label}
            </Typography>
          ) : (
            <MuiLink
              key={crumb.path}
              component={NavLink}
              to={crumb.path}
              underline="hover"
              color="inherit"
              sx={{ fontSize: '0.875rem' }}
            >
              {crumb.label}
            </MuiLink>
          );
        })}
      </Breadcrumbs>
    </Box>
  );

  // Only render navigation for authenticated users
  if (!isAuthenticated) {
    return null;
  }

  return (
    <>
      {/* Breadcrumb Navigation */}
      {breadcrumbNavigation}

      {/* Permanent Drawer for Desktop */}
      {!isMobile && (
        <Drawer
          variant="permanent"
          sx={{
            width: DRAWER_WIDTH,
            flexShrink: 0,
            '& .MuiDrawer-paper': {
              width: DRAWER_WIDTH,
              boxSizing: 'border-box',
            },
          }}
        >
          {drawerContent}
        </Drawer>
      )}

      {/* Temporary Drawer for Mobile */}
      {isMobile && (
        <Drawer
          variant="temporary"
          open={mobileOpen}
          onClose={handleDrawerToggle}
          ModalProps={{
            keepMounted: true, // Better mobile performance
          }}
          sx={{
            '& .MuiDrawer-paper': {
              width: DRAWER_WIDTH,
              boxSizing: 'border-box',
            },
          }}
        >
          {drawerContent}
        </Drawer>
      )}
    </>
  );
};

export default Navigation;
