import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server for the end-to-end tests (story 2.14): the web app from ../ with /api proxied to the
// e2e backend. Kept here so web/vite.config.ts stays unchanged.
const backendPort = process.env.E2E_BACKEND_PORT ?? '18080';

export default defineConfig({
  root: new URL('..', import.meta.url).pathname,
  plugins: [react()],
  server: {
    strictPort: true,
    proxy: { '/api': `http://127.0.0.1:${backendPort}` },
  },
});
