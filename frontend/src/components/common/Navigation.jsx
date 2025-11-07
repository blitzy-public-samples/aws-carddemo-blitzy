/**
 * Navigation Component
 * 
 * Navigation component for the CardDemo application.
 * Provides menu options for authenticated users.
 */

import PropTypes from 'prop-types';
import {
  Box,
  Drawer,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Divider,
} from '@mui/material';
import HomeIcon from '@mui/icons-material/Home';
import CreditCardIcon from '@mui/icons-material/CreditCard';
import AccountBalanceIcon from '@mui/icons-material/AccountBalance';
import ReceiptIcon from '@mui/icons-material/Receipt';
import AssessmentIcon from '@mui/icons-material/Assessment';
import PeopleIcon from '@mui/icons-material/People';

/**
 * Navigation component displaying menu options
 * 
 * @param {Object} props - Component props
 * @param {boolean} props.open - Whether the navigation drawer is open
 * @param {Function} props.onClose - Callback when drawer is closed
 * @returns {JSX.Element} The navigation component
 */
const Navigation = ({ open, onClose }) => {
  const menuItems = [
    { text: 'Home', icon: <HomeIcon />, path: '/' },
    { text: 'Accounts', icon: <AccountBalanceIcon />, path: '/accounts' },
    { text: 'Cards', icon: <CreditCardIcon />, path: '/cards' },
    { text: 'Transactions', icon: <ReceiptIcon />, path: '/transactions' },
    { text: 'Reports', icon: <AssessmentIcon />, path: '/reports' },
  ];

  const adminItems = [
    { text: 'User Management', icon: <PeopleIcon />, path: '/admin/users' },
  ];

  return (
    <Drawer
      anchor="left"
      open={open}
      onClose={onClose}
    >
      <Box
        sx={{ width: 250 }}
        role="presentation"
        onClick={onClose}
        onKeyDown={onClose}
      >
        <List>
          {menuItems.map((item) => (
            <ListItem key={item.text} disablePadding>
              <ListItemButton>
                <ListItemIcon>
                  {item.icon}
                </ListItemIcon>
                <ListItemText primary={item.text} />
              </ListItemButton>
            </ListItem>
          ))}
        </List>
        
        <Divider />
        
        <List>
          {adminItems.map((item) => (
            <ListItem key={item.text} disablePadding>
              <ListItemButton>
                <ListItemIcon>
                  {item.icon}
                </ListItemIcon>
                <ListItemText primary={item.text} />
              </ListItemButton>
            </ListItem>
          ))}
        </List>
      </Box>
    </Drawer>
  );
};

Navigation.propTypes = {
  open: PropTypes.bool,
  onClose: PropTypes.func,
};

Navigation.defaultProps = {
  open: false,
  onClose: () => {},
};

export default Navigation;
