/**
 * Vite build and dev-server configuration for the CardDemo React 19 SPA.
 *
 * :purpose: Configure the Vite toolchain for the CardDemo single-page
 *   application (the modern presentation layer replacing the 17 legacy BMS 3270
 *   mapsets). It registers the React plugin, binds the dev and preview servers
 *   for container reachability, pins the production build output to ``dist/``,
 *   and restricts client-exposed environment variables to the ``VITE_`` prefix.
 * :output: A resolved Vite ``UserConfig`` consumed by ``vite`` (dev),
 *   ``vite build`` (production bundle in ``dist/``), and ``vite preview``.
 *
 * The REST base URL is supplied at build time through ``VITE_API_BASE_URL``
 * (default ``/api``, a same-origin path reverse-proxied to the api-gateway by
 * ``frontend/nginx.conf``) and consumed by the API client via
 * ``import.meta.env.VITE_API_BASE_URL``.
 */
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  // React 19 support: Fast Refresh in dev and the automatic JSX runtime in build.
  plugins: [react()],

  // Development server. ``host: true`` binds 0.0.0.0 for container reachability.
  server: {
    host: true,
    port: 5173,
    // Bind exactly port 5173 and fail fast if it is unavailable so the dev URL
    // stays stable and predictable.
    strictPort: true,
  },

  // ``vite preview`` server for the production build; also bound to 0.0.0.0 and
  // pinned to a stable port.
  preview: {
    host: true,
    port: 4173,
    strictPort: true,
  },

  // Production build output copied into the nginx image by frontend/Dockerfile.
  build: {
    outDir: 'dist',
    sourcemap: false,
    emptyOutDir: true,
  },

  // Only ``VITE_``-prefixed variables are exposed to client code via
  // ``import.meta.env`` (notably ``VITE_API_BASE_URL``).
  envPrefix: 'VITE_',
});
