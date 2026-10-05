import axios, { AxiosError, AxiosInstance, InternalAxiosRequestConfig } from 'axios';

/**
 * Centralized Axios client.
 *
 * - Base URL: same-origin `/api` in dev (Vite proxies to the backend, which
 *   ships no CORS config); `VITE_API_BASE_URL` override for production.
 * - Access token lives in memory only (module scope), never localStorage.
 * - Refresh uses the httpOnly `gc_refresh` cookie, so every request sends
 *   credentials; the refresh token itself is never visible to JS.
 * - On 401, exactly one refresh+retry is attempted; concurrent 401s share a
 *   single refresh promise. Refresh failure clears auth and surfaces 401 so
 *   the app can redirect to /login. No infinite loops by construction.
 */

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '';

let accessToken: string | null = null;
let refreshPromise: Promise<string> | null = null;
let onAuthFailure: (() => void) | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function onAuthFailureSubscribe(listener: () => void): () => void {
  onAuthFailure = listener;
  return () => {
    if (onAuthFailure === listener) onAuthFailure = null;
  };
}

async function refreshAccessToken(client: AxiosInstance): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = client
      .post('/api/v1/auth/refresh')
      .then((res) => {
        const token = res.data?.accessToken as string | undefined;
        if (!token) throw new Error('refresh produced no token');
        accessToken = token;
        return token;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

function isAuthEndpoint(url: string | undefined): boolean {
  return !!url && /\/api\/v1\/auth\/(login|register|refresh|verify|password|verification)/.test(url);
}

export function createApiClient(): AxiosInstance {
  const client = axios.create({
    baseURL: API_BASE_URL,
    timeout: 30000,
    withCredentials: true,
    headers: { 'Content-Type': 'application/json' },
  });

  client.interceptors.request.use((config: InternalAxiosRequestConfig) => {
    if (accessToken) {
      config.headers.set('Authorization', `Bearer ${accessToken}`);
    }
    return config;
  });

  client.interceptors.response.use(
    (response) => response,
    async (error: AxiosError) => {
      const original = error.config as (InternalAxiosRequestConfig & { _retried?: boolean }) | undefined;
      const status = error.response?.status;
      if (
        status === 401 &&
        original &&
        !original._retried &&
        !isAuthEndpoint(original.url)
      ) {
        original._retried = true;
        try {
          const token = await refreshAccessToken(client);
          original.headers.set('Authorization', `Bearer ${token}`);
          return await client.request(original);
        } catch {
          accessToken = null;
          onAuthFailure?.();
        }
      }
      return Promise.reject(error);
    },
  );

  return client;
}

export const api = createApiClient();
