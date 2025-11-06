import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Vite configuration for CardDemo frontend
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    host: true,
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
});
