/**
 * Application Entry Point
 * 
 * Main entry file for the CardDemo React TypeScript frontend application.
 * Renders the root App component into the DOM.
 * 
 * Per Agent Action Plan Section 0.4.17: React 18 createRoot API for concurrent features.
 */

import React from 'react';
import ReactDOM from 'react-dom/client';
import './styles/global.css';

// Placeholder App component until App.tsx is created
const App = () => {
  return (
    <div style={{ padding: '20px', fontFamily: 'Arial, sans-serif' }}>
      <h1>CardDemo - Credit Card Management System</h1>
      <p>React application is loading...</p>
      <p>This is a placeholder. The full application will be implemented by other agents.</p>
    </div>
  );
};

// Get root element
const rootElement = document.getElementById('root');

if (!rootElement) {
  throw new Error('Failed to find the root element. Ensure index.html contains <div id="root"></div>');
}

// Create React root (React 18 concurrent mode)
const root = ReactDOM.createRoot(rootElement);

// Render App component
root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
