/**
 * Header Component
 * 
 * Application header component displaying the CardDemo branding and navigation.
 */

import React from 'react';
import PropTypes from 'prop-types';
import {
  AppBar,
  Toolbar,
  Typography,
  IconButton,
  Box,
} from '@mui/material';
import MenuIcon from '@mui/icons-material/Menu';
import AccountCircleIcon from '@mui/icons-material/AccountCircle';

/**
 * Header component for the CardDemo application
 * 
 * @param {Object} props - Component props
 * @param {Function} props.onMenuClick - Callback for menu button click
 * @returns {JSX.Element} The header component
 */
const Header = ({ onMenuClick }) => {
  return (
    <AppBar position="static">
      <Toolbar>
        <IconButton
          edge="start"
          color="inherit"
          aria-label="menu"
          onClick={onMenuClick}
          sx={{ mr: 2 }}
        >
          <MenuIcon />
        </IconButton>
        
        <Typography variant="h6" component="div" sx={{ flexGrow: 1 }}>
          CardDemo - Credit Card Management
        </Typography>
        
        <Box>
          <IconButton
            color="inherit"
            aria-label="account"
          >
            <AccountCircleIcon />
          </IconButton>
        </Box>
      </Toolbar>
    </AppBar>
  );
};

Header.propTypes = {
  onMenuClick: PropTypes.func,
};

Header.defaultProps = {
  onMenuClick: () => {},
};

export default Header;
