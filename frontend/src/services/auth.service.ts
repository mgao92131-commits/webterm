/**
 * 认证业务服务。
 * 封装登录、注册、登出、用户信息获取等用例。
 * 不直接操作全局 Store，返回结果由调用方决定如何消费。
 */

import { request } from '../api/client';
import {
  login as apiLogin,
  register as apiRegister,
  logout as apiLogout,
  me as apiMe,
  verifyEmail as apiVerifyEmail,
  verifyOtp as apiVerifyOtp,
  resendOtp as apiResendOtp,
  type AuthUser,
  type LoginResult,
  type RegisterResult,
} from '../api/auth';

export type { AuthUser, LoginResult, RegisterResult };

export interface Credentials {
  email: string;
  password: string;
}

export interface OtpVerifyPayload {
  email: string;
  code: string;
  targetDeviceId?: string;
}

export async function login(credentials: Credentials): Promise<LoginResult> {
  return apiLogin(credentials.email, credentials.password);
}

export async function register(credentials: Credentials): Promise<RegisterResult> {
  return apiRegister(credentials.email, credentials.password);
}

export async function logout(): Promise<void> {
  return apiLogout();
}

export async function fetchMe(): Promise<AuthUser> {
  return apiMe();
}

export async function verifyEmail(email: string, code: string): Promise<{ email?: string; role?: 'admin' | 'user' }> {
  return apiVerifyEmail(email, code);
}

export async function verifyOtp(payload: OtpVerifyPayload): Promise<{ email?: string; role?: 'admin' | 'user' }> {
  return apiVerifyOtp(payload.email, payload.code, payload.targetDeviceId || '');
}

export async function resendOtp(email: string): Promise<void> {
  return apiResendOtp(email);
}

/**
 * 静默刷新当前会话。
 * 成功返回 true，失败返回 false。
 */
export async function refreshSession(): Promise<boolean> {
  try {
    const res = await request('/api/auth/refresh', { method: 'POST' });
    return res !== null && res !== undefined;
  } catch {
    return false;
  }
}
