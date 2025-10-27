/**
 * Navigation Component - React Navigation Menu
 * 
 * Converted from BMS menu structures:
 * - COMEN01.bms: Main menu with 10 numbered options
 * - COADM01.bms: Admin menu with 4 numbered options for user management
 * 
 * Original COBOL menu structure from COMEN02Y.cpy and COADM02Y.cpy:
 * - Main Menu: 10 options (Account View, Account Update, Card List, etc.)
 * - Admin Menu: 4 options (User List, User Add, User Update, User Delete)
 * - User selection via numeric input (1-12)
 * - Program transfer via EXEC CICS XCTL based on selection
 * 
 * React Implementation Features:
 * - Hierarchical menu structure with collapsible sections
 * - Responsive drawer navigation (persistent desktop, temporary mobile)
 * - Active route highlighting for current page
 * - Role-based menu filtering (admin vs regular user)
 * - Material-UI components for modern UX
 * - React Router integration for client-side navigation
 * 
 * BMS Menu Pattern Transformation:
 * COBOL:
 * ```cobol
 * * User enters option number (e.g., "2" for Account Update)
 * ACCEPT OPTION-INPUT
 * IF OPTION-INPUT = '2'
 *   EXEC CICS XCTL PROGRAM('COACTUPC') 
 *        COMMAREA(CARDDEMO-COMMAREA)
 *   END-EXEC
 * END-IF
 * ```
 * 
 * React:
 * ```typescript
 * // User clicks menu item, React Router navigates
 * <ListItemButton onClick={() => navigate('/accounts/:id/edit')}>
 *   <ListItemText primary="Account Update" />
 * </ListItemButton>
 * ```
 * 
 * Menu Structure Mapping:
 * - BMS OPTN001-OPTN012 fields → React menuItems array
 * - COBOL CDEMO-MENU-OPT-NUM → Menu item ordering
 * - COBOL CDEMO-MENU-OPT-NAME → label property
 * - COBOL CDEMO-MENU-OPT-PGMNAME → path property (mapped to React routes)
 * - COBOL CDEMO-MENU-OPT-USRTYPE → adminOnly flag
 * 
 * Per Agent Action Plan Section 0.4.19 and Section 0.7.1 MINIMAL CHANGE CLAUSE:
 * This component preserves BMS menu functionality while modernizing with
 * responsive drawer navigation. Menu structure directly maps to COBOL menu
 * options from COMEN02Y.cpy and COADM02Y.cpy copybooks.
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * Licensed under the Apache License, Version 2.0
 * 
 * @see app/bms/COMEN01.bms - Original main menu BMS map
 * @see app/bms/COADM01.bms - Original admin menu BMS map
 * @see app/cpy/COMEN02Y.cpy - Main menu options data structure
 * @see app/cpy/COADM02Y.cpy - Admin menu options data structure
 */

import React, { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import {
  Drawer,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Collapse,
  Divider,
  IconButton,
  Box,
  useTheme,
  useMediaQuery
} from '@mui/material';
import {
  ExpandLess,
  ExpandMore,
  AccountBalance,
  CreditCard,
  Receipt,
  People,
  Assessment,
  ChevronLeft,
  Home
} from '@mui/icons-material';
import { useAuth } from '../../hooks/useAuth';

/**
 * Navigation component props interface
 */
interface NavigationProps {
  /**
   * Controls drawer visibility
   */
  open: boolean;
  
  /**
   * Callback function to close the drawer
   */
  onClose: () => void;
  
  /**
   * Drawer width in pixels
   * @default 240
   */
  drawerWidth?: number;
}

/**
 * Menu item interface representing navigation options
 * Maps to COBOL CDEMO-MENU-OPT structure from COMEN02Y.cpy
 */
interface MenuItem {
  /**
   * Display label for menu item
   * Maps to COBOL CDEMO-MENU-OPT-NAME (PIC X(35))
   */
  label: string;
  
  /**
   * Route path for navigation
   * Maps to COBOL CDEMO-MENU-OPT-PGMNAME (PIC X(08)) converted to React route
   */
  path?: string;
  
  /**
   * Material-UI icon component
   */
  icon: React.ReactNode;
  
  /**
   * Child menu items for hierarchical structure
   */
  children?: MenuItem[];
  
  /**
   * Flag indicating admin-only access
   * Maps to COBOL CDEMO-MENU-OPT-USRTYPE (PIC X(01))
   * true = Admin only ('A'), false = All users ('U')
   */
  adminOnly?: boolean;
}

/**
 * Menu structure definition
 * 
 * Transformed from COBOL menu options in COMEN02Y.cpy and COADM02Y.cpy:
 * 
 * Main Menu (COMEN02Y.cpy - 10 options):
 * 1. Account View (COACTVWC) - path: /accounts/:id
 * 2. Account Update (COACTUPC) - path: /accounts/:id/edit
 * 3. Credit Card List (COCRDLIC) - path: /cards
 * 4. Credit Card View (COCRDSLC) - path: /cards/:id (handled by card detail)
 * 5. Credit Card Update (COCRDUPC) - path: /cards/:id/edit
 * 6. Transaction List (COTRN00C) - path: /transactions
 * 7. Transaction View (COTRN01C) - path: /transactions/:id
 * 8. Transaction Add (COTRN02C) - path: /transactions/new
 * 9. Transaction Reports (CORPT00C) - path: /reports
 * 10. Bill Payment (COBIL00C) - path: /billing (incorporated into main menu)
 * 
 * Admin Menu (COADM02Y.cpy - 4 options):
 * 1. User List (COUSR00C) - path: /users
 * 2. User Add (COUSR01C) - path: /users/new
 * 3. User Update (COUSR02C) - path: /users/:id/edit
 * 4. User Delete (COUSR03C) - path: /users/:id/delete (confirmation handled in update page)
 */
const menuItems: MenuItem[] = [
  {
    label: 'Home',
    path: '/',
    icon: <Home />
  },
  {
    label: 'Accounts',
    icon: <AccountBalance />,
    children: [
      {
        label: 'View Account',
        path: '/accounts/:id',
        icon: <AccountBalance />
      },
      {
        label: 'Update Account',
        path: '/accounts/:id/edit',
        icon: <AccountBalance />
      }
    ]
  },
  {
    label: 'Cards',
    icon: <CreditCard />,
    children: [
      {
        label: 'Card List',
        path: '/cards',
        icon: <CreditCard />
      },
      {
        label: 'Update Card',
        path: '/cards/:id/edit',
        icon: <CreditCard />
      }
    ]
  },
  {
    label: 'Transactions',
    icon: <Receipt />,
    children: [
      {
        label: 'Transaction List',
        path: '/transactions',
        icon: <Receipt />
      },
      {
        label: 'Transaction Detail',
        path: '/transactions/:id',
        icon: <Receipt />
      },
      {
        label: 'New Transaction',
        path: '/transactions/new',
        icon: <Receipt />
      }
    ]
  },
  {
    label: 'Reports',
    path: '/reports',
    icon: <Assessment />
  },
  {
    label: 'Users',
    icon: <People />,
    adminOnly: true,
    children: [
      {
        label: 'User List',
        path: '/users',
        icon: <People />,
        adminOnly: true
      },
      {
        label: 'Add User',
        path: '/users/new',
        icon: <People />,
        adminOnly: true
      }
    ]
  }
];

/**
 * Navigation Component
 * 
 * Renders a responsive drawer navigation menu with hierarchical structure.
 * Replaces BMS 3270 terminal menu screens with modern web navigation.
 * 
 * Features:
 * - Responsive behavior: Persistent drawer on desktop, temporary on mobile
 * - Collapsible menu sections for better organization
 * - Active route highlighting for user feedback
 * - Role-based filtering (admin vs regular user)
 * - Auto-close on mobile after navigation
 * - Smooth collapse animations
 * 
 * COBOL Comparison:
 * - BMS screens COMEN01/COADM01: Fixed 24x80 terminal screens
 * - React Navigation: Responsive drawer adapting to screen size
 * 
 * - COBOL: Sequential screen navigation (menu → detail → back to menu)
 * - React: Client-side routing (menu stays visible, content changes)
 * 
 * - COBOL: Numeric option selection (enter "2" for Account Update)
 * - React: Direct click navigation with visual feedback
 * 
 * - COBOL: Fixed menu structure for all users
 * - React: Dynamic menu filtered by user role
 * 
 * @param props - Navigation component props
 * @returns Navigation drawer component
 */
const Navigation: React.FC<NavigationProps> = ({ 
  open, 
  onClose,
  drawerWidth = 240
}) => {
  // Hooks for navigation and location tracking
  const navigate = useNavigate();
  const location = useLocation();
  const theme = useTheme();
  
  // Detect mobile screen size for responsive behavior
  // Mobile: width < 600px (theme.breakpoints.down('sm'))
  const isMobile = useMediaQuery(theme.breakpoints.down('sm'));
  
  // Authentication context for role-based access control
  // Replaces COBOL CDEMO-USER-TYPE field from COMMAREA
  const { user } = useAuth();
  
  // Track which menu sections are expanded
  // Key: section label, Value: expanded state (true/false)
  const [openSections, setOpenSections] = useState<{ [key: string]: boolean }>({});

  /**
   * Toggle collapsible menu section
   * 
   * Replaces COBOL menu navigation where user enters option number.
   * React allows hierarchical navigation with expand/collapse.
   * 
   * @param label - Menu section label to toggle
   */
  const handleToggleSection = (label: string): void => {
    setOpenSections(prev => ({
      ...prev,
      [label]: !prev[label]
    }));
  };

  /**
   * Navigate to selected route
   * 
   * Replaces COBOL EXEC CICS XCTL program transfer:
   * - COBOL: EXEC CICS XCTL PROGRAM('COACTUPC') COMMAREA(...)
   * - React: navigate('/accounts/:id/edit')
   * 
   * Auto-closes drawer on mobile devices after navigation for better UX.
   * 
   * @param path - Route path to navigate to
   */
  const handleNavigate = (path: string): void => {
    navigate(path);
    
    // Close drawer on mobile after navigation
    if (isMobile) {
      onClose();
    }
  };

  /**
   * Check if route is currently active
   * 
   * Provides visual feedback similar to BMS screen title indicators.
   * Active route is highlighted in the navigation menu.
   * 
   * @param path - Route path to check
   * @returns true if route is active, false otherwise
   */
  const isActive = (path: string): boolean => {
    // Home route requires exact match
    if (path === '/') {
      return location.pathname === '/';
    }
    
    // Other routes match if current path starts with route path
    // Remove :id parameter patterns for matching
    const pathPattern = path.replace(':id', '');
    return location.pathname.startsWith(pathPattern);
  };

  /**
   * Filter menu items by user role
   * 
   * Implements COBOL user type checking from CDEMO-MENU-OPT-USRTYPE:
   * - COBOL: IF CDEMO-USER-TYPE = 'A' PERFORM ADMIN-PROCESSING
   * - React: Filter adminOnly items if user.userType !== 'A'
   * 
   * Recursively filters child items as well.
   * 
   * @param items - Menu items to filter
   * @returns Filtered menu items based on user role
   */
  const filterByRole = (items: MenuItem[]): MenuItem[] => {
    return items.filter(item => {
      // Remove admin-only items for non-admin users
      if (item.adminOnly && user?.userType !== 'A') {
        return false;
      }
      
      // Recursively filter children
      if (item.children) {
        item.children = filterByRole(item.children);
      }
      
      return true;
    });
  };

  /**
   * Render individual menu item
   * 
   * Handles both parent items (with children) and leaf items (with paths).
   * Applies indentation based on hierarchy level.
   * 
   * @param item - Menu item to render
   * @param level - Hierarchy level for indentation (0 = root)
   * @returns Rendered menu item JSX
   */
  const renderMenuItem = (item: MenuItem, level: number = 0): JSX.Element => {
    const hasChildren = item.children && item.children.length > 0;
    const isOpen = openSections[item.label];

    return (
      <React.Fragment key={item.label}>
        <ListItem 
          disablePadding 
          sx={{ pl: level * 2 }}
        >
          <ListItemButton
            onClick={() => {
              if (hasChildren) {
                // Toggle expansion for parent items
                handleToggleSection(item.label);
              } else if (item.path) {
                // Navigate for leaf items
                handleNavigate(item.path);
              }
            }}
            selected={item.path ? isActive(item.path) : false}
          >
            <ListItemIcon>{item.icon}</ListItemIcon>
            <ListItemText primary={item.label} />
            {hasChildren && (isOpen ? <ExpandLess /> : <ExpandMore />)}
          </ListItemButton>
        </ListItem>
        
        {/* Collapsible child items */}
        {hasChildren && (
          <Collapse in={isOpen} timeout="auto" unmountOnExit>
            <List component="div" disablePadding>
              {item.children!.map(child => renderMenuItem(child, level + 1))}
            </List>
          </Collapse>
        )}
      </React.Fragment>
    );
  };

  // Filter menu items based on user role
  const filteredMenuItems = filterByRole(menuItems);

  return (
    <Drawer
      variant={isMobile ? 'temporary' : 'persistent'}
      anchor="left"
      open={open}
      onClose={onClose}
      sx={{
        width: drawerWidth,
        flexShrink: 0,
        '& .MuiDrawer-paper': {
          width: drawerWidth,
          boxSizing: 'border-box'
        }
      }}
    >
      {/* Drawer header with close button */}
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'flex-end',
          p: 1
        }}
      >
        <IconButton onClick={onClose} aria-label="Close navigation menu">
          <ChevronLeft />
        </IconButton>
      </Box>
      
      <Divider />
      
      {/* Navigation menu items */}
      <List>
        {filteredMenuItems.map(item => renderMenuItem(item))}
      </List>
    </Drawer>
  );
};

export default Navigation;
