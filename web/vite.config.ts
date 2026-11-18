/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { apiProxy } from './src/devProxy.ts';

// The /api proxy exists only for `vite dev` and `vite preview` (command `serve`); a build gets
// none, so production stays same-origin behind Caddy.
export default defineConfig(({ command, mode }) => {
  const proxy =
    command === 'serve'
      ? apiProxy(loadEnv(mode, import.meta.dirname, 'VITE_API_PROXY'))
      : undefined;
  return {
    plugins: [react()],
    ...(proxy ? { server: { proxy }, preview: { proxy } } : {}),
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      include: ['src/**/*.test.{ts,tsx}'],
    },
  };
});
