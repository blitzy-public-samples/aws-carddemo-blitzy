import React, { createContext, useContext, useState, useEffect, useMemo, ReactNode } from 'react';
import { ThemeProvider as MuiThemeProvider, createTheme, Theme } from '@mui/material/styles';
import CssBaseline from '@mui/material/CssBaseline';

/**
 * Theme mode type definition
 * Supports light and dark modes
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
  
  /** Material-UI theme object */
  muiTheme: Theme;
}

/**
 * Theme context
 * Created with undefined to detect usage outside provider
 */
const ThemeContext = createContext<ThemeContextType | undefined>(undefined);

/**
 * Theme Context Provider Component
 * Manages theme state and provides Material-UI theme to application
 * 
 * @param children - React child components
 */
export const ThemeContextProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  /**
   * Initialize theme from localStorage or default to light mode
   */
  const [theme, setTheme] = useState<ThemeMode>(() => {
    const storedTheme = localStorage.getItem('theme');
    return (storedTheme === 'light' || storedTheme === 'dark') ? storedTheme : 'light';
  });

  /**
   * Persist theme changes to localStorage
   */
  useEffect(() => {
    localStorage.setItem('theme', theme);
  }, [theme]);

  /**
   * Toggle theme between light and dark modes
   */
  const toggleTheme = (): void => {
    setTheme((prevTheme) => (prevTheme === 'light' ? 'dark' : 'light'));
  };

  /**
   * Create Material-UI theme object based on current mode
   * Uses useMemo to avoid recreating theme on every render
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
              textTransform: 'none', // Disable uppercase transformation
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

  const value: ThemeContextType = {
    theme,
    toggleTheme,
    muiTheme,
  };

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
 * @returns ThemeContextType object
 * @throws Error if used outside ThemeContextProvider
 * 
 * @example
 * const { theme, toggleTheme, muiTheme } = useTheme();
 * 
 * // Toggle theme on button click
 * <Button onClick={toggleTheme}>
 *   {theme === 'light' ? 'Dark Mode' : 'Light Mode'}
 * </Button>
 * 
 * // Access theme colors
 * const backgroundColor = muiTheme.palette.background.default;
 * 
 * // Theme-aware styling
 * const styles = {
 *   color: muiTheme.palette.text.primary,
 *   backgroundColor: muiTheme.palette.background.paper,
 * };
 */
export const useTheme = (): ThemeContextType => {
  const context = useContext(ThemeContext);
  
  if (context === undefined) {
    throw new Error('useTheme must be used within a ThemeContextProvider');
  }
  
  return context;
};
