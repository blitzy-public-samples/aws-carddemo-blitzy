/**
 * Main App Component
 * 
 * Root component for the CardDemo application.
 * Wraps the application with ErrorBoundary for error handling.
 */

import React from 'react';
import { ThemeProvider, createTheme, CssBaseline, Box, Container } from '@mui/material';
import ErrorBoundary from './components/common/ErrorBoundary';
import Header from './components/common/Header';
import Footer from './components/common/Footer';
import Navigation from './components/common/Navigation';

// Create Material-UI theme
const theme = createTheme({
  palette: {
    primary: {
      main: '#1976d2',
    },
    secondary: {
      main: '#dc004e',
    },
  },
});

/**
 * Main App Component
 * 
 * @returns {JSX.Element} The main application component
 */
function App() {
  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <ErrorBoundary>
        <Box
          sx={{
            display: 'flex',
            flexDirection: 'column',
            minHeight: '100vh',
          }}
        >
          <Header />
          <Navigation />
          <Container
            component="main"
            sx={{
              flex: 1,
              py: 4,
            }}
          >
            <Box>
              <h1>CardDemo - Credit Card Management System</h1>
              <p>Welcome to the CardDemo application.</p>
            </Box>
          </Container>
          <Footer />
        </Box>
      </ErrorBoundary>
    </ThemeProvider>
  );
}

export default App;
