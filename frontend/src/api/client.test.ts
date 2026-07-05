import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { configureApiClient, request, httpClient, UnauthorizedError } from './client';

describe('api/client', () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockReset();
    configureApiClient({});
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('merges default Content-Type with config and caller headers', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({ ok: true }),
    });

    configureApiClient({
      getMode: () => 'relay',
      getSelectedDeviceId: () => 'd1',
      getClientId: () => 'c1',
    });

    await request('/api/sessions', {
      method: 'GET',
      headers: { 'X-Custom': 'foo' },
    });

    expect(fetchMock).toHaveBeenCalledWith('/api/sessions', {
      method: 'GET',
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json',
        'X-Device-Id': 'd1',
        'X-Client-Id': 'c1',
        'X-Custom': 'foo',
      },
    });
  });

  it('does not add X-Device-Id in direct mode', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({ ok: true }),
    });

    configureApiClient({
      getMode: () => 'direct',
      getSelectedDeviceId: () => 'd1',
      getClientId: () => 'c1',
    });

    await request('/api/sessions', { method: 'GET' });

    const call = fetchMock.mock.calls[0][1] as RequestInit;
    expect(call.headers).not.toHaveProperty('X-Device-Id');
    expect(call.headers).toHaveProperty('X-Client-Id', 'c1');
  });

  it('caller headers can override defaults', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: true,
      status: 200,
      json: async () => ({ ok: true }),
    });

    await request('/api/sessions', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
    });

    const call = fetchMock.mock.calls[0][1] as RequestInit;
    expect(call.headers).toHaveProperty('Content-Type', 'text/plain');
  });

  it('returns null for 204 responses', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: true,
      status: 204,
    });

    const result = await request('/api/sessions/s1', { method: 'DELETE' });
    expect(result).toBeNull();
  });

  it('throws ApiError on non-ok response', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
      text: async () => 'boom',
    });

    await expect(request('/api/sessions', { method: 'GET' })).rejects.toThrow('boom');
  });

  it('retries once on 401 after successful refresh', async () => {
    fetchMock
      .mockResolvedValueOnce({ ok: false, status: 401, statusText: 'Unauthorized', text: async () => '' })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ refreshed: true }) })
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ ok: true }) });

    const result = await httpClient('/api/sessions', { method: 'GET' });
    expect(result).toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('calls onSessionInvalidated and throws UnauthorizedError when refresh fails', async () => {
    fetchMock
      .mockResolvedValueOnce({ ok: false, status: 401, statusText: 'Unauthorized', text: async () => '' })
      .mockResolvedValueOnce({ ok: false, status: 401, statusText: 'Unauthorized', text: async () => '' });

    const onSessionInvalidated = vi.fn();
    configureApiClient({ onSessionInvalidated });

    await expect(httpClient('/api/sessions', { method: 'GET' })).rejects.toBeInstanceOf(UnauthorizedError);
    expect(onSessionInvalidated).toHaveBeenCalledTimes(1);
  });
});
