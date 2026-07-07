import { reactive } from 'vue';
import type { Theme } from './config';
import { getClientId, getSavedDeviceId, getSavedTheme } from './utils/storage';

export interface User {
  id: string;
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
  displayTitle?: string;
  termTitle?: string;
  cwd: string;
  state?: 'running' | 'exited';
  cmd?: string;
  recentInput?: string;
  /** 敏感输入是否应被隐藏（由后端推送） */
  recentInputHidden?: boolean;
  /** 最近输入的多行文本（由后端推送） */
  recentInputLines?: string[];
  /** Agent / shell 上报的细粒度状态 */
  shellState?: string;
  agentState?: string;
  /** 最后执行的完整命令（可作为 recentInputLines 的 fallback） */
  lastCommand?: string;
  /** 最新通知（由 webterm notify 上报） */
  notification?: {
    title: string;
    body?: string;
    level?: 'info' | 'success' | 'warning' | 'error';
    timestamp: number;
  };
}

export interface AppStore {
  user: User | null;
  mode: 'direct' | 'relay';
  devices: Device[];
  selectedDeviceId: string | null;
  selectedSessionId: string | null;
  sessions: Session[];
  theme: Theme;
  clientId: string;
  managerError: string;
  p2pActive: boolean;
  connectionStates: Record<string, 'connected' | 'connecting' | 'disconnected' | 'polling'>;
}

const savedTheme = getSavedTheme();
const savedDevice = getSavedDeviceId();

export const store = reactive<AppStore>({
  user: null,
  mode: 'direct',
  devices: [],
  selectedDeviceId: savedDevice || null,
  selectedSessionId: null,
  sessions: [],
  theme: savedTheme || 'solarized',
  clientId: getClientId(),
  managerError: '',
  p2pActive: false,
  connectionStates: {},
});

// 重置 Store（用于退出登录或权限失效时，防止单例污染）
export function resetStore() {
  store.user = null;
  store.sessions = [];
  store.devices = [];
  store.selectedDeviceId = null;
  store.selectedSessionId = null;
  store.managerError = '';
  store.p2pActive = false;
  store.connectionStates = {};
}
