/**
 * main
 * ====
 *
 * :purpose: Browser entry point of the CardDemo single-page application. It creates the
 *     React root on the ``#root`` element declared by ``frontend/index.html``, mounts the
 *     routed :func:`App` inside the one browser-history router, and loads the single
 *     global stylesheet that re-expresses the BMS 3270 presentation.
 * :output: No exported symbol; evaluating the module renders the application.
 */
import React from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import App from './App';
import './index.css';

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>
);
