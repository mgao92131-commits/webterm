import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fetchSessions, createSession, closeSession, renameSession } from './session.service';

vi.mock('../api/client', () => ({
  httpClient: vi.fn(),
  request: vi.fn(),
  ApiError: class ApiError extends Error {
    status: number;
    constructor(message: string, status: number) {
      super(message);
      this.status = status;
    }
  },
  UnauthorizedError: class UnauthorizedError extends Error {
    status = 401;
  },
}));

import { httpClient } from '../api/client';

const mockedHttpClient = vi.mocked(httpClient);

describe('session.service', () => {
  beforeEach(() => {
    mockedHttpClient.mockReset();
  });

  it('fetches sessions', async () => {
    const sessions = [{ id: 's1', cwd: '/home', name: 'Term' }];
    mockedHttpClient.mockResolvedValueOnce(sessions);

    const result = await fetchSessions();

    expect(mockedHttpClient).toHaveBeenCalledWith('/api/sessions', { method: 'GET' });
    expect(result).toEqual(sessions);
  });

  it('normalizes non-array fetch result to empty array', async () => {
    mockedHttpClient.mockResolvedValueOnce(null);

    const result = await fetchSessions();

    expect(result).toEqual([]);
  });

  it('creates a session', async () => {
    const session = { id: 's2', cwd: '/', name: '' };
    mockedHttpClient.mockResolvedValueOnce(session);

    const result = await createSession();

    expect(mockedHttpClient).toHaveBeenCalledWith('/api/sessions', {
      method: 'POST',
      body: JSON.stringify({}),
    });
    expect(result).toEqual(session);
  });

  it('closes a session', async () => {
    mockedHttpClient.mockResolvedValueOnce(undefined);

    await closeSession('s1');

    expect(mockedHttpClient).toHaveBeenCalledWith('/api/sessions/s1', { method: 'DELETE' });
  });

  it('closes a composite relay session by local id', async () => {
    mockedHttpClient.mockResolvedValueOnce(undefined);

    await closeSession('d1:s1');

    expect(mockedHttpClient).toHaveBeenCalledWith('/api/sessions/s1', { method: 'DELETE' });
  });

  it('renames a session', async () => {
    const updated = { id: 's1', cwd: '/', name: 'New Name' };
    mockedHttpClient.mockResolvedValueOnce(updated);

    const result = await renameSession('d1:s1', 'New Name');

    expect(mockedHttpClient).toHaveBeenCalledWith('/api/sessions/s1', {
      method: 'PATCH',
      body: JSON.stringify({ name: 'New Name' }),
    });
    expect(result).toEqual(updated);
  });
});
