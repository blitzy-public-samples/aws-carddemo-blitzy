/**
 * Vite Configuration
 * 
 * Build tool and development server configuration for CardDemo React frontend application.
 * Converted from mainframe BMS 3270 terminal screens to modern React SPA.
 * 
 * Configuration sections:
 * - Plugins: React Fast Refresh and JSX transformation
 * - Resolve: Path aliases for clean imports (matching tsconfig.json)
 * - Server: Development server with proxy to Spring Boot backend
 * - Build: Production build optimization with code splitting
 * - Preview: Preview server for production builds
 * 
 * Per Agent Action Plan Section 0.4.16: Vite 6.0.3 configured for fast development
 * and optimized production builds.
 */

import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import svgr from 'vite-plugin-svgr';
import path from 'path';

// https://vitejs.dev/config/
export default defineConfig({
  /**
   * Plugins Configuration
   * 
   * React Plugin: Enables Fast Refresh for instant hot-reload during development
   * and JSX transformation using React 18 automatic runtime.
   * 
   * SVGR Plugin: Enables importing SVG files as React components using the ?react suffix.
   * Example: import Icon from './icon.svg?react' - imports SVG as React component
   * 
   * Note: Fast Refresh is enabled by default in @vitejs/plugin-react v4.3.4+
   */
  plugins: [
    react({
      // Babel configuration for additional transformations if needed
      babel: {
        plugins: [],
        // Future: Add emotion/babel-plugin for MUI SSR if needed
      },
    }),
    svgr({
      // SVGR options for SVG to React component transformation
      svgrOptions: {
        // Export as React component
        exportType: 'default',
        // Add ref support for SVG components
        ref: true,
        // Add SVGO optimization
        svgo: true,
        // Preserve title elements for accessibility
        titleProp: true,
      },
    }),
  ],

  /**
   * Path Resolution Configuration
   * 
   * Alias configuration must match tsconfig.json paths for consistency.
   * Enables clean imports: import { Button } from '@components/common/Button'
   * Instead of relative paths: import { Button } from '../../../components/common/Button'
   */
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      '@components': path.resolve(__dirname, './src/components'),
      '@pages': path.resolve(__dirname, './src/pages'),
      '@services': path.resolve(__dirname, './src/services'),
      '@hooks': path.resolve(__dirname, './src/hooks'),
      '@context': path.resolve(__dirname, './src/context'),
      '@types': path.resolve(__dirname, './src/types'),
      '@utils': path.resolve(__dirname, './src/utils'),
      '@styles': path.resolve(__dirname, './src/styles'),
      '@assets': path.resolve(__dirname, './src/assets'),
    },
  },

  /**
   * Development Server Configuration
   * 
   * Runs on http://localhost:5173 with hot module replacement.
   * Proxies API requests to Spring Boot backend (http://localhost:8080).
   * Avoids CORS issues during development.
   */
  server: {
    // Port for development server
    port: 5173,
    
    // Listen on all network interfaces (0.0.0.0) for Docker/remote access
    host: true,
    
    // Try next available port if 5173 is in use
    strictPort: false,

    // Hot Module Replacement (HMR) configuration
    hmr: {
      // Show errors as overlay in browser
      overlay: true,
    },

    // Proxy API requests to Spring Boot backend
    // Frontend: http://localhost:5173
    // Backend: http://localhost:8080
    // API call: axios.get('/api/accounts') → proxied to http://localhost:8080/api/accounts
    proxy: {
      '/api': {
        // Target backend URL
        target: 'http://localhost:8080',
        
        // Change origin header to target
        changeOrigin: true,
        
        // Accept self-signed certificates in development
        secure: false,
        
        // Proxy WebSocket connections for real-time features (if needed)
        ws: true,
        
        // Configure proxy behavior
        configure: (proxy, _options) => {
          // Log proxy requests for debugging
          proxy.on('proxyReq', (proxyReq, req, _res) => {
            console.log('[Proxy]', req.method, req.url, '→', 'http://localhost:8080' + req.url);
          });
          
          // Log proxy responses
          proxy.on('proxyRes', (proxyRes, req, _res) => {
            console.log('[Proxy]', proxyRes.statusCode, req.method, req.url);
          });
          
          // Log proxy errors
          proxy.on('error', (err, req, _res) => {
            console.error('[Proxy Error]', req.method, req.url, err.message);
          });
        },
      },
    },

    // Open browser automatically on server start
    open: true,

    // CORS configuration (allow all origins in development)
    cors: true,
  },

  /**
   * Production Build Configuration
   * 
   * Optimizes bundle size through code splitting, tree shaking, and minification.
   * Generates source maps for debugging production issues.
   * Implements manual chunk splitting for better caching strategy.
   */
  build: {
    // Build output directory
    outDir: 'dist',
    
    // Static assets subdirectory within outDir
    assetsDir: 'assets',
    
    // Generate source maps for debugging production issues
    // Source maps are separate files, not included in main bundle
    sourcemap: true,

    // Rollup options for advanced bundling configuration
    rollupOptions: {
      output: {
        /**
         * Manual Chunk Splitting Strategy
         * 
         * Separates vendor libraries into dedicated chunks for better caching.
         * When application code changes, vendor chunks remain cached in browser.
         * Benefits:
         * - Faster page loads (cached vendor chunks)
         * - Smaller delta for updates (only app code changes)
         * - Parallel downloads (multiple chunks)
         */
        manualChunks: {
          // React core libraries
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          
          // Material-UI library (large, separating for better caching)
          'mui-vendor': [
            '@mui/material',
            '@mui/icons-material',
            '@emotion/react',
            '@emotion/styled',
          ],
          
          // Utility libraries
          'utils-vendor': ['axios', 'date-fns', 'lodash', 'formik', 'yup'],
          
          // React Query for server state management
          'query-vendor': ['@tanstack/react-query'],
        },

        /**
         * Asset Naming Patterns
         * 
         * [name]: Original chunk/entry name
         * [hash]: Content hash for cache busting
         * 
         * Example output:
         * - assets/js/react-vendor-abc123.js
         * - assets/js/main-def456.js
         * - assets/css/style-ghi789.css
         */
        chunkFileNames: 'assets/js/[name]-[hash].js',
        entryFileNames: 'assets/js/[name]-[hash].js',
        assetFileNames: 'assets/[ext]/[name]-[hash].[ext]',
      },
    },

    /**
     * Minification Configuration
     * 
     * Use esbuild for fast minification (faster than terser).
     * Compresses JavaScript and CSS for smaller bundle sizes.
     */
    minify: 'esbuild',

    /**
     * Target Browser Support
     * 
     * ES2020 features supported by modern browsers (Chrome 80+, Firefox 72+, Safari 13.1+).
     * No IE11 support needed for internal enterprise application.
     */
    target: 'es2020',

    /**
     * CSS Code Splitting
     * 
     * Split CSS into separate files for lazy-loaded routes.
     * Reduces initial bundle size and enables progressive loading.
     */
    cssCodeSplit: true,

    /**
     * Report Compressed Size
     * 
     * Display compressed bundle sizes in build output.
     * Helps monitor bundle size growth.
     */
    reportCompressedSize: true,

    /**
     * Chunk Size Warning Limit
     * 
     * Warn if any chunk exceeds 1000kb (1MB).
     * Default is 500kb, but MUI vendor chunk may exceed this.
     */
    chunkSizeWarningLimit: 1000,
  },

  /**
   * Preview Server Configuration
   * 
   * Serves production build locally for testing before deployment.
   * Usage: npm run build && npm run preview
   * Access: http://localhost:4173
   */
  preview: {
    // Port for preview server (different from dev server)
    port: 4173,
    
    // Listen on all network interfaces
    host: true,
    
    // Try next available port if 4173 is in use
    strictPort: false,
  },

  /**
   * Environment Variables Configuration
   * 
   * Define constants available in client code.
   * 
   * Usage in code:
   * - __APP_VERSION__ → application version from package.json
   * 
   * Note: Environment variables with VITE_ prefix are automatically available.
   * Example: .env file with VITE_API_URL=http://api.example.com
   * Access in code: import.meta.env.VITE_API_URL
   */
  define: {
    __APP_VERSION__: JSON.stringify(process.env.npm_package_version || '1.0.0'),
  },
});
