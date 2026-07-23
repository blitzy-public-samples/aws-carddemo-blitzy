/**
 * Vite build and dev-server configuration for the CardDemo React 19 SPA.
 *
 * :purpose: Configure the Vite toolchain that powers the CardDemo single-page
 *   application (the modern presentation layer replacing the 17 legacy BMS 3270
 *   mapsets). It registers the React plugin, binds the dev and preview servers
 *   so they are reachable from inside containers, pins the production build
 *   output to ``dist/``, and restricts client-exposed environment variables to
 *   the ``VITE_`` prefix.
 * :output: A resolved Vite ``UserConfig`` consumed by ``vite`` (dev),
 *   ``vite build`` (production bundle emitted to ``dist/``), and
 *   ``vite preview`` (local static preview).
 *
 * The REST base URL used to reach the api-gateway is intentionally NOT declared
 * here. It is supplied at build time through the ``VITE_API_BASE_URL``
 * environment variable (see root ``docker-compose.yml``) and consumed by the
 * API client via ``import.meta.env.VITE_API_BASE_URL``; this config only
 * guarantees that ``VITE_``-prefixed variables are exposed to client code.
 */
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// A static configuration object is sufficient: no environment-dependent proxy
// is defined, so the mode-aware function form of ``defineConfig`` is not needed.
export default defineConfig({
  // React 19 support: Fast Refresh in dev and the automatic JSX runtime in build.
  plugins: [react()],

  // Development server. ``host: true`` binds 0.0.0.0 so the server is reachable
  // from outside the container (e.g. Docker/Kubernetes port mapping), not just
  // on the loopback interface.
  server: {
    host: true,
    port: 5173,
    // Allow Vite to fall back to the next free port instead of failing hard when
    // 5173 is already taken.
    strictPort: false,
  },

  // ``vite preview`` server for locally serving the production build on its own
  // port; also bound to 0.0.0.0 for container reachability.
  preview: {
    host: true,
    port: 4173,
  },

  // Production build output. ``dist/`` is copied into the nginx image by
  // frontend/Dockerfile and is ignored by the repository-root .gitignore.
  build: {
    outDir: 'dist',
    // Source maps add weight and expose source; disabled for the production bundle.
    sourcemap: false,
    // Clear stale artifacts from a previous build before writing a new one.
    emptyOutDir: true,
  },

  // Only variables prefixed with ``VITE_`` are exposed to client code via
  // ``import.meta.env`` (notably ``VITE_API_BASE_URL``). Set explicitly to make
  // the client-exposure boundary self-documenting; this is also the Vite default.
  envPrefix: 'VITE_',
});
