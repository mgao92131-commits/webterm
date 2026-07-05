import { computed, onMounted, onUnmounted, ref, watch, type Ref } from 'vue';
import { CONFIG } from '../config';
import { connectionService } from '../services/connection.service';
import { store } from '../store';
import type { ConnectionHealth } from '../components/ConnectionBadge.vue';
import type { RelayMuxChannel } from '../lib/relay-mux-session';

export type ManagerServerMessage =
  | { type: 'devices'; devices: Array<{ deviceId: string; deviceName: string; status?: 'online' | 'offline'; online?: boolean }> }
  | { type: 'sessions'; data: unknown[] }
  | { type: 'session'; data: unknown }
  | { type: 'session-closed'; id: string }
  | { type: 'p2p-ice'; candidate: RTCIceCandidateInit }
  | { type: 'error'; message: string };

export interface UseManagerConnectionOptions {
  /** 当前选中的设备 ID；direct 模式下可为 null */
  deviceId: Ref<string | null>;
  /** 收到 WebSocket 推送消息时的回调 */
  onMessage: (msg: ManagerServerMessage) => void;
  /** 轮询回调，在 WS 断开后周期性调用 */
  poll: () => Promise<void>;
  /** WS 连接成功时回调 */
  onConnect?: () => void;
}

/**
 * Manager 页连接状态管理。
 * 统一维护 manager WebSocket、断线重连、轮询以及网络状态监听，
 * 让视图只关心 UI 与业务回调。
 */
export function useManagerConnection(options: UseManagerConnectionOptions) {
  let ws: WebSocket | RelayMuxChannel | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let pollTimer: ReturnType<typeof setInterval> | null = null;
  let reconnectAttempts = 0;
  let manualClose = false;

  const isActive = ref(false);

  const connectionHealth = computed<ConnectionHealth>(() => {
    const state = store.connectionStates['manager'];
    if (state === 'connected' || state === 'connecting' || state === 'polling') {
      return state;
    }
    return 'disconnected';
  });

  function setState(state: 'connected' | 'connecting' | 'disconnected' | 'polling'): void {
    store.connectionStates['manager'] = state;
  }

  function connect(): void {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      return;
    }

    manualClose = false;

    if (store.mode === 'relay' && !options.deviceId.value) {
      setState('disconnected');
      return;
    }

    setState('connecting');

    ws = connectionService.openManagerChannel(options.deviceId.value || '');
    const currentWs = ws;

    ws.addEventListener('open', () => {
      if (ws !== currentWs) return;
      setState('connected');
      reconnectAttempts = 0;
      if (reconnectTimer) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
      stopPolling();
      options.onConnect?.();
    });

    ws.addEventListener('message', (event) => {
      if (ws !== currentWs) return;
      try {
        const msg = JSON.parse((event as MessageEvent).data);
        options.onMessage(msg as ManagerServerMessage);
        stopPolling();
      } catch (e) {
        console.error('[ManagerConnection] 解析 WebSocket 推送失败', e);
      }
    });

    ws.addEventListener('close', (event) => {
      if (ws !== currentWs) return;
      ws = null;
      if (manualClose) return;

      const code = (event as CloseEvent).code ?? 1000;
      if (!(CONFIG.reconnectBlockedCloseCodes as readonly number[]).includes(code)) {
        setState('polling');
        startPolling();
        scheduleReconnect();
      } else {
        setState('disconnected');
        startPolling();
      }
    });

    ws.addEventListener('error', () => {
      if (ws !== currentWs) return;
      setState('polling');
      startPolling();
    });
  }

  function disconnect(): void {
    manualClose = true;
    setState('disconnected');
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    if (ws) {
      ws.close();
      ws = null;
    }
  }

  function scheduleReconnect(): void {
    if (reconnectTimer) clearTimeout(reconnectTimer);
    const backoff = CONFIG.reconnectBackoff;
    const cap = Math.min(backoff.baseMs * Math.pow(backoff.multiplier, reconnectAttempts++), backoff.capMs);
    const delay = Math.max(backoff.minDelayMs, Math.random() * cap);
    reconnectTimer = setTimeout(() => connect(), delay);
  }

  function startPolling(): void {
    if (pollTimer) return;
    pollTimer = setInterval(async () => {
      if (options.deviceId.value) {
        try {
          await options.poll();
        } catch (err) {
          console.error('[ManagerConnection] 轮询失败', err);
        }
      }
    }, CONFIG.managerPollIntervalMs);
  }

  function stopPolling(): void {
    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  function onOnline(): void {
    reconnectAttempts = 0;
    connect();
  }

  function onOffline(): void {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
  }

  watch(options.deviceId, () => {
    disconnect();
    if (options.deviceId.value || store.mode === 'direct') {
      connect();
    }
  });

  onMounted(() => {
    isActive.value = true;
    window.addEventListener('online', onOnline);
    window.addEventListener('offline', onOffline);
    connect();
  });

  onUnmounted(() => {
    isActive.value = false;
    window.removeEventListener('online', onOnline);
    window.removeEventListener('offline', onOffline);
    disconnect();
    stopPolling();
  });

  return {
    connectionHealth,
    connect,
    disconnect,
  };
}
