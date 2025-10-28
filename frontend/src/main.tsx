/**
 * Application Entry Point - main.tsx
 * 
 * React 18 application entry point for CardDemo credit card management system.
 * This file is the first JavaScript/TypeScript file executed when the application loads.
 * 
 * Conversion from COBOL mainframe architecture:
 * - No direct COBOL equivalent - replaces BMS 3270 terminal initialization
 * - Original system: CICS region initialization → BMS screen display
 * - Modern system: React root creation → Component tree rendering
 * 
 * Per Agent Action Plan Section 0.4.17:
 * - Uses React 18.3 createRoot API for concurrent rendering features
 * - Wraps App component in React.StrictMode for development checks
 * - Mounts application to #root div element in public/index.html
 * 
 * React 18 Concurrent Features Enabled:
 * - Automatic batching of state updates (performance optimization)
 * - Transitions API for non-urgent updates
 * - Suspense for data fetching and lazy loading
 * - Server-side streaming rendering support
 * 
 * Execution Flow:
 * 1. Browser loads public/index.html
 * 2. HTML includes <script type="module" src="/src/main.tsx">
 * 3. Vite processes main.tsx and all imports
 * 4. This file executes:
 *    - Imports React, ReactDOM, App, and global styles
 *    - Gets #root DOM element from HTML
 *    - Creates React 18 root container
 *    - Renders App component wrapped in StrictMode
 * 5. React component tree initialized (App → Router → Pages)
 * 6. Browser displays rendered CardDemo UI
 * 
 * @module main
 * @since 1.0.0
 */

import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './styles/global.css';

/**
 * Get root DOM element from index.html
 * 
 * The root element is the container where the entire React application
 * will be mounted. It must exist in public/index.html as:
 * <div id="root"></div>
 * 
 * @throws {Error} If root element is not found in DOM
 */
const rootElement = document.getElementById('root');

if (!rootElement) {
  throw new Error(
    'Failed to find the root element. ' +
    'Ensure public/index.html contains <div id="root"></div>'
  );
}

/**
 * Create React 18 root container
 * 
 * ReactDOM.createRoot() is the new React 18 API that replaces
 * ReactDOM.render() from React 17. This enables concurrent features:
 * 
 * - Automatic Batching: Multiple setState calls batched into single render
 * - Transitions: Mark updates as non-urgent for better responsiveness
 * - Suspense: Declarative loading states for async operations
 * - Streaming SSR: Server-side rendering with progressive enhancement
 * 
 * React 17 (deprecated):
 *   ReactDOM.render(<App />, document.getElementById('root'))
 * 
 * React 18 (current):
 *   ReactDOM.createRoot(element).render(<App />)
 * 
 * @see https://react.dev/blog/2022/03/29/react-v18#new-root-api
 */
const root = ReactDOM.createRoot(rootElement);

/**
 * Render the root App component
 * 
 * React.StrictMode wrapper enables additional development-mode checks:
 * - Double-invokes component lifecycle methods to detect side effects
 * - Warns about deprecated lifecycle methods (componentWillMount, etc.)
 * - Warns about legacy string ref API usage
 * - Warns about unexpected side effects in useEffect
 * - Detects unsafe practices and potential bugs
 * 
 * StrictMode behavior:
 * - Development: Performs extra checks and warnings (double rendering)
 * - Production: No overhead, wrapper is effectively removed
 * 
 * Component Tree Hierarchy:
 * StrictMode
 *   └─ App (BrowserRouter, Providers, Routes)
 *       ├─ AuthProvider (authentication state)
 *       ├─ ThemeProvider (theme configuration)
 *       └─ Routes (17 pages from BMS maps)
 *           ├─ SignonPage (COSGN00.bms)
 *           ├─ MainMenuPage (COMEN01.bms)
 *           ├─ AccountUpdatePage (COACTUP.bms)
 *           └─ ... (14 more pages)
 * 
 * Per Agent Action Plan Section 0.7.1 MINIMAL CHANGE CLAUSE:
 * This implementation includes ONLY the essential React 18 initialization
 * required for the COBOL-to-React migration. No unnecessary features added.
 * 
 * @see App.tsx for routing and provider configuration
 */
root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
