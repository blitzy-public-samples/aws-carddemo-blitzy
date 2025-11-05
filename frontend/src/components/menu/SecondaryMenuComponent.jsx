/**
 * SecondaryMenuComponent.jsx
 * 
 * Secondary navigation menu component for CardDemo application.
 * Transforms 3270 hierarchical menu navigation patterns to modern React breadcrumb
 * navigation with contextual sub-menu options.
 * 
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

import React, { useMemo } from 'react';
import { Link, useLocation } from 'react-router-dom';
import {
  Box,
  Breadcrumbs,
  Typography,
  List,
  ListItem,
  ListItemButton,
  ListItemText,
  Paper,
  Divider
} from '@mui/material';
import NavigateNext from '@mui/icons-material/NavigateNext';

/**
 * SecondaryMenuComponent
 * 
 * Provides hierarchical navigation with breadcrumbs and contextual sub-menu options.
 * Maintains navigation patterns equivalent to mainframe CICS XCTL program transfers
 * while providing modern web UI experience.
 * 
 * Features:
 * - Breadcrumb trail showing navigation path (Home > Section > Page)
 * - Contextual menu items based on current application area
 * - Dynamic menu rendering based on user role and permissions
 * - Material-UI components for consistent design
 * - React Router integration for SPA navigation
 * 
 * @param {Object} props - Component properties
 * @param {Array} props.breadcrumbs - Breadcrumb trail [{label: string, path: string}]
 * @param {Array} props.contextMenuItems - Contextual menu options [{label, path, icon?, requiresAdmin?}]
 * @param {string} props.title - Section title for the current navigation context
 * @param {boolean} props.isAdmin - Whether current user has admin privileges
 * @returns {JSX.Element} Secondary navigation menu component
 */
const SecondaryMenuComponent = ({
  breadcrumbs = [],
  contextMenuItems = [],
  title = '',
  isAdmin = false
}) => {
  const location = useLocation();

  /**
   * Filter menu items based on user role and permissions.
   * Preserves mainframe role-based menu filtering logic from COMEN01C.cbl
   * where admin-only options are hidden from regular users.
   */
  const filteredMenuItems = useMemo(() => {
    return contextMenuItems.filter(item => {
      // If item requires admin access and user is not admin, filter it out
      if (item.requiresAdmin && !isAdmin) {
        return false;
      }
      return true;
    });
  }, [contextMenuItems, isAdmin]);

  /**
   * Generate breadcrumb navigation trail.
   * Transforms mainframe hierarchical screen navigation (CC00 -> CM00 -> CA00)
   * to modern breadcrumb pattern with clickable links.
   */
  const breadcrumbElements = useMemo(() => {
    if (breadcrumbs.length === 0) {
      return null;
    }

    return breadcrumbs.map((crumb, index) => {
      const isLast = index === breadcrumbs.length - 1;
      
      // Last breadcrumb is current page - display as text without link
      if (isLast) {
        return (
          <Typography
            key={crumb.path || index}
            color="text.primary"
            sx={{ fontWeight: 500 }}
          >
            {crumb.label}
          </Typography>
        );
      }

      // Intermediate breadcrumbs are clickable links
      return (
        <Link
          key={crumb.path || index}
          to={crumb.path}
          style={{
            textDecoration: 'none',
            color: 'inherit'
          }}
        >
          <Typography
            color="text.secondary"
            sx={{
              '&:hover': {
                textDecoration: 'underline',
                color: 'primary.main'
              }
            }}
          >
            {crumb.label}
          </Typography>
        </Link>
      );
    });
  }, [breadcrumbs]);

  /**
   * Check if a menu item is currently active based on current route.
   * Maintains visual feedback equivalent to mainframe screen highlighting.
   */
  const isMenuItemActive = (itemPath) => {
    return location.pathname === itemPath || location.pathname.startsWith(itemPath + '/');
  };

  /**
   * Render contextual menu items for current application section.
   * Equivalent to COMEN01C BUILD-MENU-OPTIONS paragraph that dynamically
   * builds menu options based on user type and context.
   */
  const renderContextMenu = () => {
    if (filteredMenuItems.length === 0) {
      return null;
    }

    return (
      <Paper
        elevation={1}
        sx={{
          mt: 2,
          borderRadius: 1,
          overflow: 'hidden'
        }}
      >
        <Box
          sx={{
            px: 2,
            py: 1.5,
            backgroundColor: 'primary.main',
            color: 'primary.contrastText'
          }}
        >
          <Typography variant="subtitle2" fontWeight={600}>
            {title || 'Menu Options'}
          </Typography>
        </Box>
        <Divider />
        <List sx={{ py: 0 }}>
          {filteredMenuItems.map((item, index) => {
            const isActive = isMenuItemActive(item.path);
            
            return (
              <React.Fragment key={item.path || index}>
                <ListItem
                  disablePadding
                  sx={{
                    backgroundColor: isActive ? 'action.selected' : 'transparent'
                  }}
                >
                  <ListItemButton
                    component={Link}
                    to={item.path}
                    selected={isActive}
                    sx={{
                      py: 1.5,
                      '&.Mui-selected': {
                        backgroundColor: 'action.selected',
                        borderLeft: 3,
                        borderColor: 'primary.main',
                        '&:hover': {
                          backgroundColor: 'action.hover'
                        }
                      }
                    }}
                  >
                    {item.icon && (
                      <Box sx={{ mr: 2, display: 'flex', alignItems: 'center' }}>
                        {item.icon}
                      </Box>
                    )}
                    <ListItemText
                      primary={item.label}
                      secondary={item.description}
                      primaryTypographyProps={{
                        fontWeight: isActive ? 600 : 400,
                        color: isActive ? 'primary.main' : 'text.primary'
                      }}
                      secondaryTypographyProps={{
                        variant: 'caption'
                      }}
                    />
                  </ListItemButton>
                </ListItem>
                {index < filteredMenuItems.length - 1 && <Divider />}
              </React.Fragment>
            );
          })}
        </List>
      </Paper>
    );
  };

  return (
    <Box
      sx={{
        width: '100%',
        mb: 3
      }}
    >
      {/* Breadcrumb Navigation */}
      {breadcrumbElements && (
        <Box
          sx={{
            mb: 2,
            p: 2,
            backgroundColor: 'background.paper',
            borderRadius: 1,
            boxShadow: 1
          }}
        >
          <Breadcrumbs
            separator={<NavigateNext fontSize="small" />}
            aria-label="breadcrumb navigation"
            sx={{
              '& .MuiBreadcrumbs-separator': {
                mx: 1
              }
            }}
          >
            {breadcrumbElements}
          </Breadcrumbs>
        </Box>
      )}

      {/* Contextual Menu Options */}
      {renderContextMenu()}
    </Box>
  );
};

export default SecondaryMenuComponent;
