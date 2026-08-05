/**
 * main
 * ====
 *
 * :purpose: Browser entry point of the CardDemo single-page application. It mounts the
 *     routed :func:`App` into the ``#root`` element of ``index.html`` and loads the
 *     one global stylesheet that re-expresses the BMS 3270 presentation, so the
 *     seventeen migrated screens are reachable as one running application.
 * :output: No exported symbol; the module's evaluation renders the application.
 * :raises Error: when ``index.html`` carries no ``#root`` element, so a broken shell
 *     fails loudly at start-up instead of rendering nothing.
 */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import App from './App';
import './index.css';

/** Id of the mount element declared by ``frontend/index.html``. */
const ROOT_ELEMENT_ID = 'root';

const container = document.getElementById(ROOT_ELEMENT_ID);
if (container === null) {
  throw new Error(`Missing #${ROOT_ELEMENT_ID} mount element`);
}

createRoot(container).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
