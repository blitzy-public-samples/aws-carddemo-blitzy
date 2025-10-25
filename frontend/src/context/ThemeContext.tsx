/**
 * Theme Context Provider
 * 
 * Converted from: N/A (New feature for modern web UI)
 * Original function: No COBOL equivalent - 3270 terminals have fixed green-on-black appearance
 * 
 * Purpose:
 * - Manage application-wide theme state (light/dark mode)
 * - Provide Material-UI theme configuration to all components
 * - Persist user theme preference across sessions using localStorage
 * - Enable seamless theme switching without page reload
 * 
 * Features:
 * - Light and dark mode support
 * - Persistent user preference via localStorage
 * - Material-UI integration with custom color palette
 * - Typography configuration for consistent text styling
 * - Component-level theme customization
 * - useTheme hook for convenient context consumption
 * 
 * Usage:
 * 1. Wrap application with ThemeContextProvider in App.tsx
 * 2. Use useTheme hook in any component to access theme state and toggle function
 * 3. Access Material-UI theme object for theme-aware styling
 * 
 * No COBOL Equivalent:
 * - BMS screens have fixed appearance with no dynamic theming
 * - 3270 terminals display green-on-black with no customization
 * - This is a modern web UI feature enhancing user experience
 */

import React, { createContext, useContext, useState, useEffect, useMemo, ReactNode, FC } from 'react';
import { ThemeProvider as MuiThemeProvider, createTheme, Theme } from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';

/**
 * Theme mode type definition
 * Supports light and dark modes for user preference
 */
export type ThemeMode = 'light' | 'dark';

/**
 * Theme context type definition
 * Manages theme mode and provides toggle function
 */
interface ThemeContextType {
  /** Current theme mode: 'light' or 'dark' */
  theme: ThemeMode;
  
  /** Function to toggle between light and dark modes */
  toggleTheme: () => void;
  
  /** Material-UI theme object with color palette and typography */
  muiTheme: Theme;
}

/**
 * Theme context
 * Created with default values for type safety
 * Throws error if accessed outside ThemeContextProvider
 */
const ThemeContext = createContext<ThemeContextType>({
  theme: 'light',
  toggleTheme: () => {},
  muiTheme: createTheme()
});

/**
 * Theme Context Provider Component
 * Manages theme state and provides Material-UI theme to entire application
 * 
 * Features:
 * - Initializes theme from localStorage or defaults to light mode
 * - Persists theme changes to localStorage automatically
 * - Creates Material-UI theme with custom color palette
 * - Provides theme toggle functionality
 * - Wraps children with MUI ThemeProvider and CssBaseline
 * 
 * @param children - React child components to wrap with theme provider
 * 
 * @example
 * // In App.tsx
 * <ThemeContextProvider>
 *   <BrowserRouter>
 *     <Routes>
 *       {/* Application routes *\/}
 *     </Routes>
 *   </BrowserRouter>
 * </ThemeContextProvider>
 */
export const ThemeContextProvider: FC<{ children: ReactNode }> = ({ children }) => {
  /**
   * Initialize theme from localStorage or default to light mode
   * Validates stored value to ensure it's a valid ThemeMode
   */
  const [theme, setTheme] = useState<ThemeMode>(() => {
    try {
      const storedTheme = localStorage.getItem('theme');
      if (storedTheme === 'light' || storedTheme === 'dark') {
        return storedTheme;
      }
    } catch (error) {
      // localStorage may not be available in some environments (e.g., SSR)
      console.warn('Failed to read theme from localStorage:', error);
    }
    return 'light';
  });

  /**
   * Persist theme changes to localStorage
   * Effect runs whenever theme changes
   */
  useEffect(() => {
    try {
      localStorage.setItem('theme', theme);
    } catch (error) {
      console.warn('Failed to save theme to localStorage:', error);
    }
  }, [theme]);

  /**
   * Toggle theme between light and dark modes
   * Updates state which triggers localStorage persistence via useEffect
   */
  const toggleTheme = (): void => {
    setTheme((prevTheme) => (prevTheme === 'light' ? 'dark' : 'light'));
  };

  /**
   * Create Material-UI theme object based on current mode
   * Uses useMemo to avoid recreating theme on every render
   * Only recreates when theme mode changes
   * 
   * Theme Configuration:
   * - Custom color palette for light and dark modes
   * - Typography settings with system font stack
   * - Component-level customizations (buttons, text fields, cards)
   */
  const muiTheme = useMemo(() => {
    return createTheme({
      palette: {
        mode: theme,
        primary: {
          main: theme === 'light' ? '#1976d2' : '#90caf9',
          light: theme === 'light' ? '#42a5f5' : '#e3f2fd',
          dark: theme === 'light' ? '#1565c0' : '#42a5f5',
          contrastText: '#fff',
        },
        secondary: {
          main: theme === 'light' ? '#dc004e' : '#f48fb1',
          light: theme === 'light' ? '#ff4081' : '#ffc1e3',
          dark: theme === 'light' ? '#c51162' : '#bf5f82',
          contrastText: '#fff',
        },
        background: {
          default: theme === 'light' ? '#f5f5f5' : '#121212',
          paper: theme === 'light' ? '#ffffff' : '#1e1e1e',
        },
        text: {
          primary: theme === 'light' ? 'rgba(0, 0, 0, 0.87)' : 'rgba(255, 255, 255, 0.87)',
          secondary: theme === 'light' ? 'rgba(0, 0, 0, 0.6)' : 'rgba(255, 255, 255, 0.6)',
        },
        error: {
          main: theme === 'light' ? '#d32f2f' : '#f44336',
        },
        warning: {
          main: theme === 'light' ? '#f57c00' : '#ff9800',
        },
        info: {
          main: theme === 'light' ? '#0288d1' : '#03a9f4',
        },
        success: {
          main: theme === 'light' ? '#388e3c' : '#4caf50',
        },
      },
      typography: {
        fontFamily: [
          '-apple-system',
          'BlinkMacSystemFont',
          '"Segoe UI"',
          'Roboto',
          '"Helvetica Neue"',
          'Arial',
          'sans-serif',
          '"Apple Color Emoji"',
          '"Segoe UI Emoji"',
          '"Segoe UI Symbol"',
        ].join(','),
        h1: {
          fontSize: '2.5rem',
          fontWeight: 500,
        },
        h2: {
          fontSize: '2rem',
          fontWeight: 500,
        },
        h3: {
          fontSize: '1.75rem',
          fontWeight: 500,
        },
        h4: {
          fontSize: '1.5rem',
          fontWeight: 500,
        },
        h5: {
          fontSize: '1.25rem',
          fontWeight: 500,
        },
        h6: {
          fontSize: '1rem',
          fontWeight: 500,
        },
        body1: {
          fontSize: '1rem',
        },
        body2: {
          fontSize: '0.875rem',
        },
      },
      components: {
        MuiButton: {
          styleOverrides: {
            root: {
              textTransform: 'none', // Disable uppercase transformation for better readability
              borderRadius: 4,
            },
          },
        },
        MuiTextField: {
          defaultProps: {
            variant: 'outlined',
          },
        },
        MuiCard: {
          styleOverrides: {
            root: {
              borderRadius: 8,
            },
          },
        },
      },
    });
  }, [theme]);

  /**
   * Context value object
   * Contains current theme, toggle function, and Material-UI theme
   */
  const value: ThemeContextType = {
    theme,
    toggleTheme,
    muiTheme,
  };

  /**
   * Render provider with Material-UI ThemeProvider and CssBaseline
   * CssBaseline provides consistent CSS reset across browsers
   * ThemeProvider makes theme available to all MUI components
   */
  return (
    <ThemeContext.Provider value={value}>
      <MuiThemeProvider theme={muiTheme}>
        <CssBaseline />
        {children}
      </MuiThemeProvider>
    </ThemeContext.Provider>
  );
};

/**
 * Custom hook for consuming theme context
 * Provides convenient access to theme state and toggle function
 * 
 * @returns ThemeContextType object containing theme, toggleTheme, and muiTheme
 * @throws Error if used outside ThemeContextProvider
 * 
 * @example
 * // Basic usage in a component
 * const { theme, toggleTheme, muiTheme } = useTheme();
 * 
 * // Toggle theme on button click
 * <Button onClick={toggleTheme}>
 *   {theme === 'light' ? 'Dark Mode' : 'Light Mode'}
 * </Button>
 * 
 * @example
 * // Access theme colors for custom styling
 * const { muiTheme } = useTheme();
 * const backgroundColor = muiTheme.palette.background.default;
 * const textColor = muiTheme.palette.text.primary;
 * 
 * @example
 * // Theme-aware component styling
 * const { muiTheme } = useTheme();
 * <Box
 *   sx={{
 *     color: muiTheme.palette.text.primary,
 *     backgroundColor: muiTheme.palette.background.paper,
 *     padding: 2,
 *   }}
 * >
 *   Content adapts to theme
 * </Box>
 * 
 * @example
 * // Conditional rendering based on theme
 * const { theme } = useTheme();
 * return (
 *   <img 
 *     src={theme === 'light' ? '/logo-light.png' : '/logo-dark.png'}
 *     alt="Logo"
 *   />
 * );
 */
export const useTheme = (): ThemeContextType => {
  const context = useContext(ThemeContext);
  
  if (!context) {
    throw new Error('useTheme must be used within a ThemeContextProvider');
  }
  
  return context;
};

