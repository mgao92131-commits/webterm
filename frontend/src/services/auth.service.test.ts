import { describe, it, expect, vi, beforeEach } from 'vitest';
import { login, register, fetchMe, verifyEmail, verifyOtp, resendOtp, refreshSession } from './auth.service';

vi.mock('../api/client', () => ({
  request: vi.fn(),
  httpClient: vi.fn(),
}));

import { request } from '../api/client';

const mockedRequest = vi.mocked(request);

describe('auth.service', () => {
  beforeEach(() => {
    mockedRequest.mockReset();
  });

  it('calls login endpoint with credentials', async () => {
    mockedRequest.mockResolvedValueOnce({ id: 'u1', email: 'a@b.com', role: 'user' });

    const result = await login({ email: 'a@b.com', password: 'secret' });

    expect(mockedRequest).toHaveBeenCalledWith('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email: 'a@b.com', password: 'secret' }),
    });
    expect(result.email).toBe('a@b.com');
  });

  it('calls register endpoint with credentials', async () => {
    mockedRequest.mockResolvedValueOnce({ emailVerificationRequired: true });

    const result = await register({ email: 'a@b.com', password: 'secret' });

    expect(mockedRequest).toHaveBeenCalledWith('/api/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email: 'a@b.com', password: 'secret' }),
    });
    expect(result.emailVerificationRequired).toBe(true);
  });

  it('fetches current user', async () => {
    mockedRequest.mockResolvedValueOnce({ id: 'u1', username: 'alice', role: 'admin', mode: 'relay' });

    const user = await fetchMe();

    expect(mockedRequest).toHaveBeenCalledWith('/api/auth/me', { method: 'GET' });
    expect(user.role).toBe('admin');
  });

  it('verifies email code', async () => {
    mockedRequest.mockResolvedValueOnce({ email: 'a@b.com', role: 'user' });

    const result = await verifyEmail('a@b.com', '123456');

    expect(mockedRequest).toHaveBeenCalledWith('/api/auth/verify-email', {
      method: 'POST',
      body: JSON.stringify({ email: 'a@b.com', code: '123456' }),
    });
    expect(result.email).toBe('a@b.com');
  });

  it('verifies otp code', async () => {
    mockedRequest.mockResolvedValueOnce({ email: 'a@b.com', role: 'user' });

    const result = await verifyOtp({ email: 'a@b.com', code: '123456', targetDeviceId: 'd1' });

    expect(mockedRequest).toHaveBeenCalledWith('/api/auth/verify-otp', {
      method: 'POST',
      body: JSON.stringify({ email: 'a@b.com', code: '123456', target_device_id: 'd1' }),
    });
    expect(result.role).toBe('user');
  });

  it('resends otp', async () => {
    mockedRequest.mockResolvedValueOnce(undefined);

    await resendOtp('a@b.com');

    expect(mockedRequest).toHaveBeenCalledWith('/api/auth/resend-otp', {
      method: 'POST',
      body: JSON.stringify({ email: 'a@b.com' }),
    });
  });

  it('refreshSession returns true when endpoint returns a body', async () => {
    mockedRequest.mockResolvedValueOnce({ id: 'u1' });

    const result = await refreshSession();

    expect(mockedRequest).toHaveBeenCalledWith('/api/auth/refresh', { method: 'POST' });
    expect(result).toBe(true);
  });

  it('refreshSession returns false when endpoint returns null', async () => {
    mockedRequest.mockResolvedValueOnce(null);

    const result = await refreshSession();

    expect(result).toBe(false);
  });

  it('refreshSession returns false on error', async () => {
    mockedRequest.mockRejectedValueOnce(new Error('network'));

    const result = await refreshSession();

    expect(result).toBe(false);
  });
});
