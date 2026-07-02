import { api } from '../store';

export interface AuthUser {
  id: string;
  username: string;
  role: 'admin' | 'user';
  mode?: 'direct' | 'relay';
}

export interface LoginResult {
  id?: string;
  email?: string;
  username?: string;
  role?: 'admin' | 'user';
  otp_required?: boolean;
  target_device_id?: string;
  error?: string;
}

export interface RegisterResult {
  id?: string;
  email?: string;
  username?: string;
  role?: 'admin' | 'user';
  emailVerificationRequired?: boolean;
}

export async function login(email: string, password: string): Promise<LoginResult> {
  return api('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password })
  });
}

export async function register(email: string, password: string): Promise<RegisterResult> {
  return api('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password })
  });
}

export async function verifyEmail(email: string, code: string) {
  return api('/api/auth/verify-email', {
    method: 'POST',
    body: JSON.stringify({ email, code })
  });
}

export async function verifyOtp(email: string, code: string, targetDeviceId: string) {
  return api('/api/auth/verify-otp', {
    method: 'POST',
    body: JSON.stringify({ email, code, target_device_id: targetDeviceId })
  });
}

export async function resendOtp(email: string) {
  return api('/api/auth/resend-otp', {
    method: 'POST',
    body: JSON.stringify({ email })
  });
}

export async function refresh() {
  return api('/api/auth/refresh', {
    method: 'POST'
  });
}

export async function logout() {
  return api('/api/auth/logout', {
    method: 'POST'
  });
}

export async function me(): Promise<AuthUser> {
  return api('/api/auth/me', {
    method: 'GET'
  });
}

export interface TrustedDevice {
  id: string;
  deviceId: string;
  deviceName: string | null;
  lastSeenAt: string | null;
  createdAt: string;
}

export async function getTrustedDevices(): Promise<TrustedDevice[]> {
  return api('/api/auth/devices', {
    method: 'GET'
  });
}

export async function deleteTrustedDevice(id: string): Promise<void> {
  return api(`/api/auth/devices/${id}`, {
    method: 'DELETE'
  });
}
