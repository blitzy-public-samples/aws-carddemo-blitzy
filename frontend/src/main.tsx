/**
 * main ====
 * :purpose: Browser entry point of the CardDemo single-page application. It registers the
 *     SPA's uncaught-failure telemetry, creates the React root on the ``#root`` element
 *     declared by ``frontend/index.html``, mounts the routed :func:`App` inside the one
 *     browser-history router and the root :class:`ErrorBoundary`, and loads the two global
 *     stylesheets that re-express the BMS 3270 presentation: the base terminal (``index.css``)
 *     and the absolute map placement derived from ``app/bms/*.bms`` (``bmsGrid.css``), in that
 *     order.
 * :output: No exported symbol; evaluating the module renders the application.
 * :note: Telemetry is registered before the mount, so a failure of the mount itself — an
 *     ``index.html`` whose ``#root`` node is absent, which makes ``createRoot`` throw — is
 *     reported on the same channel as every later fault rather than being lost. The boundary
 *     wraps the router so a render-time exception in any screen, and in the router itself, is
 *     contained.
 */
import React from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import App from './App';
import ErrorBoundary, { registerGlobalErrorTelemetry } from './components/ErrorBoundary';
import './index.css';
import './bmsGrid.css';

registerGlobalErrorTelemetry();

createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ErrorBoundary>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </ErrorBoundary>
  </React.StrictMode>
);
