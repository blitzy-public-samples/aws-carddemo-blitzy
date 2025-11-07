/**
 * Frontend Entry Point
 * 
 * Main entry file for the CardDemo React application.
 * Sets up React root and renders the main App component.
 */

import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App.jsx';

// Create root element and render app
const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
