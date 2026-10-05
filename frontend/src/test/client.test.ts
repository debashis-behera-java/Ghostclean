import { describe, expect, it, vi } from 'vitest';
import type { AxiosAdapter, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import { createApiClient, getAccessToken, setAccessToken } from '../api/client';

function response(config: InternalAxiosRequestConfig, data: unknown, status = 200): AxiosResponse {
  return { data, status, statusText: 'OK', headers: {}, config };
}

function failure(config: InternalAxiosRequestConfig, status: number): unknown {
  return { response: { status, data: {} }, config, isAxiosError: true };
}

describe('api client 401 handling', () => {
  it('refreshes once and retries the original request', async () => {
    setAccessToken('stale');
    let refreshCalls = 0;
    const adapter: AxiosAdapter = async (config) => {
      if (config.url === '/api/v1/auth/refresh') {
        refreshCalls += 1;
        return response(config, { accessToken: 'fresh' });
      }
      if (config.headers?.get?.('Authorization') === 'Bearer fresh') {
        return response(config, { ok: true });
      }
      throw failure(config, 401);
    };
    const client = createApiClient();
    client.defaults.adapter = adapter;
    const res = await client.get('/api/v1/discovered');
    expect(res.data).toEqual({ ok: true });
    expect(refreshCalls).toBe(1);
    expect(getAccessToken()).toBe('fresh');
    setAccessToken(null);
  });

  it('concurrent 401s share a single refresh', async () => {
    setAccessToken('stale');
    let refreshCalls = 0;
    const adapter: AxiosAdapter = async (config) => {
      if (config.url === '/api/v1/auth/refresh') {
        refreshCalls += 1;
        await new Promise((r) => setTimeout(r, 10));
        return response(config, { accessToken: 'fresh2' });
      }
      if ((config.headers?.get?.('Authorization') as string)?.includes('fresh2')) {
        return response(config, { ok: true });
      }
      throw failure(config, 401);
    };
    const client = createApiClient();
    client.defaults.adapter = adapter;
    const [a, b] = await Promise.all([client.get('/a'), client.get('/b')]);
    expect(a.data).toEqual({ ok: true });
    expect(b.data).toEqual({ ok: true });
    expect(refreshCalls).toBe(1);
    setAccessToken(null);
  });

  it('does not retry auth endpoints and surfaces the 401', async () => {
    setAccessToken('stale');
    const refresh = vi.fn();
    const adapter: AxiosAdapter = async (config) => {
      if (config.url === '/api/v1/auth/refresh') {
        refresh();
        return response(config, { accessToken: 'x' });
      }
      throw failure(config, 401);
    };
    const client = createApiClient();
    client.defaults.adapter = adapter;
    await expect(client.post('/api/v1/auth/login', {})).rejects.toMatchObject({
      response: { status: 401 },
    });
    expect(refresh).not.toHaveBeenCalled();
    setAccessToken(null);
  });
});
