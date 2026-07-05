export class ApiError extends Error {
  status: number;

  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

export class UnauthorizedError extends ApiError {
  constructor(message = 'Unauthorized') {
    super(message, 401);
  }
}

interface PendingRefresh {
  promise: Promise<boolean>;
}

let pendingRefresh: PendingRefresh | null = null;

export interface ApiClientConfig {
  /** 获取当前连接模式，用于决定是否携带 X-Device-Id */
  getMode?: () => 'direct' | 'relay';
  /** 获取当前选中的设备 ID */
  getSelectedDeviceId?: () => string | null;
  /** 获取当前客户端 ID */
  getClientId?: () => string;
  /** 会话失效时的回调（由调用方负责清理 Store 等） */
  onSessionInvalidated?: () => void;
}

let apiConfig: ApiClientConfig = {};

/**
 * 配置 HTTP Client 的上下文来源。
 * 调用方（如 main.ts）通过此方法注入获取当前 mode/device/client 的方式，
 * 避免 api/client 直接依赖全局 Store。
 */
export function configureApiClient(config: ApiClientConfig): void {
  apiConfig = config;
}

/**
 * 基础 HTTP 请求封装。
 * 不处理 401 刷新，调用方自行处理。
 */
export async function request(path: string, options: RequestInit = {}): Promise<any> {
  const callerHeaders: Record<string, string> = (options.headers as Record<string, string>) || {};
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };

  if (apiConfig.getMode?.() === 'relay') {
    const deviceId = apiConfig.getSelectedDeviceId?.();
    if (deviceId) {
      headers['X-Device-Id'] = deviceId;
    }
  }

  const clientId = apiConfig.getClientId?.();
  if (clientId) {
    headers['X-Client-Id'] = clientId;
  }

  // 允许调用方覆盖默认 header
  Object.assign(headers, callerHeaders);

  const { headers: _, ...restOptions } = options;

  const res = await fetch(path, {
    credentials: 'same-origin',
    ...restOptions,
    headers,
  });

  if (!res.ok) {
    const text = await res.text();
    const err = new ApiError(text || res.statusText, res.status);
    throw err;
  }

  if (res.status === 204) return null;
  return res.json();
}

/**
 * 带 401 静默刷新的 HTTP 客户端。
 * 刷新失败时抛出 UnauthorizedError，调用方负责跳转登录页。
 */
export async function httpClient(path: string, options: RequestInit = {}): Promise<any> {
  try {
    return await request(path, options);
  } catch (err) {
    const apiErr = err as ApiError;
    const isAuthEndpoint = path.startsWith('/api/auth/');

    if (apiErr.status !== 401 || isAuthEndpoint) {
      throw err;
    }

    const success = await tryRefreshSession();
    if (success) {
      return request(path, options);
    }

    apiConfig.onSessionInvalidated?.();
    throw new UnauthorizedError('会话已过期，请重新登录');
  }
}

async function tryRefreshSession(): Promise<boolean> {
  if (!pendingRefresh) {
    pendingRefresh = {
      promise: (async () => {
        try {
          const refreshRes = await fetch('/api/auth/refresh', {
            method: 'POST',
            credentials: 'same-origin',
          });
          return refreshRes.ok;
        } catch (err) {
          console.error('[Auth] Silent refresh failed:', err);
          return false;
        }
      })(),
    };
  }

  try {
    return await pendingRefresh.promise;
  } finally {
    pendingRefresh = null;
  }
}
