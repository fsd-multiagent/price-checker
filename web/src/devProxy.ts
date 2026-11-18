// Dev and preview only: read by vite.config.ts, never imported by app code (AC-5).

export const DEFAULT_API_PROXY_TARGET = 'http://localhost:8080';

export interface ApiProxyEnv {
  VITE_API_PROXY_TARGET?: string;
}

/** The `/api` proxy for `vite dev` and `vite preview`; a blank override falls back. */
export function apiProxy(env: ApiProxyEnv): Record<string, { target: string }> {
  const override = env.VITE_API_PROXY_TARGET?.trim();
  return { '/api': { target: override ? override : DEFAULT_API_PROXY_TARGET } };
}
