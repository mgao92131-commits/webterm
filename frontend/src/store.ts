import { reactive, watch } from 'vue';
import { p2pManager } from './lib/p2p';

export interface User {
  id: number;
  username: string;
  role: 'admin' | 'user';
  mode: 'direct' | 'relay';
}

export interface Device {
  deviceId: string;
  deviceName: string;
  status: 'online' | 'offline';
}

export interface Session {
  id: string;
  name?: string;
  cwd: string;
  state?: 'running' | 'exited';
  cmd?: string;
  recentInput?: string;
}

export interface AppStore {
  user: User | null;
  mode: 'direct' | 'relay';
  devices: Device[];
  selectedDeviceId: string | null;
  sessions: Session[];
  theme: 'solarized' | 'dracula';
  clientId: string;
  managerError: string;
  p2pActive: boolean;
  connectionStates: Record<string, 'connected' | 'connecting' | 'disconnected' | 'polling'>;
}

// 初始化 Client ID
const getClientId = (): string => {
  let id = sessionStorage.getItem("webterm-client-id");
  if (!id) {
    id = "c_" + Math.random().toString(36).substring(2, 15);
    sessionStorage.setItem("webterm-client-id", id);
  }
  return id;
};

const savedTheme = localStorage.getItem("webterm-theme") as 'solarized' | 'dracula';
const savedDevice = localStorage.getItem("webterm-selected-device");

export const store = reactive<AppStore>({
  user: null,
  mode: 'direct',
  devices: [],
  selectedDeviceId: savedDevice || null,
  sessions: [],
  theme: savedTheme === 'dracula' ? 'dracula' : 'solarized',
  clientId: getClientId(),
  managerError: '',
  p2pActive: false,
  connectionStates: {},
});

// 监听主题变化，并应用到 html 元素
watch(() => store.theme, (newTheme) => {
  document.documentElement.dataset.theme = newTheme;
  localStorage.setItem("webterm-theme", newTheme);
}, { immediate: true });

// 监听选中设备变化，同步到 localStorage
watch(() => store.selectedDeviceId, (newDevice) => {
  if (newDevice) {
    localStorage.setItem("webterm-selected-device", newDevice);
  } else {
    localStorage.removeItem("webterm-selected-device");
  }
});

// 重置 Store（用于退出登录或权限失效时，防止单例污染）
export function resetStore() {
  store.user = null;
  store.sessions = [];
  store.devices = [];
  store.selectedDeviceId = null;
  store.managerError = '';
  store.p2pActive = false;
  store.connectionStates = {};
}

let isRefreshing = false;
let refreshPromise: Promise<boolean> | null = null;

// 统一 API HTTP 请求方法
export async function api(path: string, options: RequestInit = {}): Promise<any> {
  const isSignalingOrAuth = path.startsWith('/api/auth/') || path.startsWith('/api/p2p/');
  if (store.mode === 'relay' && p2pManager.isP2PActive() && !isSignalingOrAuth) {
    try {
      const response = await p2pManager.sendRequest(path, options);
      if (response.statusCode === 204) return null;
      if (response.statusCode >= 400) {
        throw new Error(response.body || `HTTP ${response.statusCode}`);
      }
      try {
        return JSON.parse(response.body);
      } catch {
        return response.body;
      }
    } catch (err) {
      console.warn('[P2P] HTTP 请求代理失败，降级回中转路由:', err);
    }
  }

  const headers: Record<string, string> = { 
    "Content-Type": "application/json", 
    ...(options.headers as Record<string, string> || {}) 
  };
  
  if (store.mode === 'relay' && store.selectedDeviceId) {
    headers["X-Device-Id"] = store.selectedDeviceId;
  }
  if (store.clientId) {
    headers["X-Client-Id"] = store.clientId;
  }
  
  const res = await fetch(path, {
    credentials: "same-origin",
    headers,
    ...options,
  });
  
  if (!res.ok) {
    const isAuthEndpoint = path.startsWith('/api/auth/');

    if (res.status === 401 && !isAuthEndpoint) {
      if (!isRefreshing) {
        isRefreshing = true;
        refreshPromise = (async () => {
          try {
            const refreshRes = await fetch('/api/auth/refresh', {
              method: 'POST',
              credentials: 'same-origin'
            });
            return refreshRes.ok;
          } catch (err) {
            console.error('[Auth] Silent refresh failed:', err);
            return false;
          }
        })();
      }

      const success = await refreshPromise;
      isRefreshing = false;
      refreshPromise = null;

      if (success) {
        // Retry the original request
        return api(path, options);
      } else {
        resetStore();
        if (window.location.pathname !== '/login' && window.location.pathname !== '/register') {
          window.location.href = '/login';
        }
        throw Object.assign(new Error('会话已过期，请重新登录'), { status: 401 });
      }
    }

    const text = await res.text();
    const err = new Error(text || res.statusText) as any;
    err.status = res.status;

    if (res.status === 401 && !isAuthEndpoint) {
      resetStore();
    }
    throw err;
  }
  
  if (res.status === 204) return null;
  return res.json();
}
