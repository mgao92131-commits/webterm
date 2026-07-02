<template>
  <div class="h-screen flex flex-col bg-app-bg text-fg overflow-hidden">
    <!-- Header -->
    <header class="flex items-center justify-between h-11 px-4 border-b border-border bg-app-bg flex-shrink-0 gap-3">
      <div class="flex items-center gap-3 min-w-0">
        <span class="text-[15px] font-semibold tracking-tight text-fg flex-shrink-0">WebTerm</span>
        <!-- Connection health dot -->
        <span class="flex items-center gap-1.5 text-[11px] text-fg-subtle flex-shrink-0">
          <span class="w-1.5 h-1.5 rounded-full" :class="{
            'bg-status-success': connectionHealth === 'connected',
            'bg-status-warning animate-pulse': connectionHealth === 'connecting',
            'bg-status-warning': connectionHealth === 'polling',
            'bg-status-danger': connectionHealth === 'disconnected',
          }"></span>
          <span class="hidden sm:inline">{{ connectionLabel }}</span>
        </span>
        <!-- P2P badge -->
        <span v-if="store.mode === 'relay' && store.selectedDeviceId" class="hidden sm:inline-flex items-center gap-1 text-[10px] px-1.5 py-0.5 rounded-sm font-medium font-mono"
          :class="store.p2pActive ? 'bg-accent-muted text-accent border border-accent/30' : 'bg-bg-tertiary text-fg-subtle border border-border'">
          {{ store.p2pActive ? 'P2P' : 'RELAY' }}
        </span>
        <!-- Mobile device selector -->
        <button
          v-if="store.mode === 'relay'"
          @click="isPanelOpen = !isPanelOpen"
          class="sm:hidden flex items-center gap-1 px-2 py-1 text-[12px] rounded-sm border border-border bg-app-bg text-fg-muted hover:text-fg hover:border-border-hover transition-colors ml-1 min-w-0"
        >
          <span class="truncate">{{ getSelectedDeviceName() }}</span>
          <ChevronDown class="w-3 h-3 flex-shrink-0 transition-transform duration-200" :class="{ 'rotate-180': isPanelOpen }" />
        </button>
      </div>

      <div class="flex items-center gap-2 flex-shrink-0">
        <!-- Theme toggle -->
        <button
          @click="toggleTheme"
          class="hidden sm:flex items-center gap-1.5 px-2.5 py-1.5 text-[12px] rounded-sm text-fg-muted hover:text-fg hover:bg-bg-tertiary transition-colors"
          :title="store.theme === 'dracula' ? '切换到 Solarized' : '切换到 Dracula'"
        >
          <Sun v-if="store.theme === 'dracula'" class="w-3.5 h-3.5 text-status-warning" />
          <Moon v-else class="w-3.5 h-3.5 text-fg-subtle" />
          <span>{{ store.theme === 'dracula' ? 'Solarized' : 'Dracula' }}</span>
        </button>
        <!-- Devices -->
        <button
          @click="goDevices"
          class="flex items-center gap-1.5 px-2.5 py-1.5 text-[12px] rounded-sm text-fg-muted hover:text-fg hover:bg-bg-tertiary transition-colors"
        >
          <Monitor class="w-3.5 h-3.5" />
          <span class="hidden sm:inline">设备</span>
        </button>
        <!-- Logout -->
        <button
          @click="handleLogout"
          :disabled="loggingOut"
          class="hidden sm:flex items-center gap-1.5 px-2.5 py-1.5 text-[12px] rounded-sm text-fg-subtle hover:text-status-danger hover:bg-status-danger/10 transition-colors disabled:opacity-40"
        >
          <LogOut class="w-3.5 h-3.5" />
          <span>{{ loggingOut ? '退出中...' : '退出' }}</span>
        </button>
        <!-- Mobile menu -->
        <button
          @click="isMenuOpen = !isMenuOpen"
          class="sm:hidden flex items-center justify-center w-8 h-8 rounded-sm text-fg-muted hover:text-fg hover:bg-bg-tertiary transition-colors"
        >
          <MoreVertical class="w-4 h-4" />
        </button>
      </div>
    </header>

    <!-- Mobile menu overlay -->
    <Teleport to="body">
      <div v-if="isMenuOpen" class="fixed inset-0 z-50 bg-transparent" @click="isMenuOpen = false"></div>
      <Transition name="fade">
        <div
          v-if="isMenuOpen"
          class="fixed right-3 top-12 w-44 rounded-md border border-border bg-app-bg shadow-lg z-[60] py-1 flex flex-col"
        >
          <button
            @click="toggleTheme(); isMenuOpen = false"
            class="flex items-center gap-3 px-4 py-2.5 text-[13px] text-fg-muted hover:text-fg hover:bg-bg-tertiary transition-colors w-full text-left"
          >
            <Sun v-if="store.theme === 'dracula'" class="w-4 h-4 text-status-warning" />
            <Moon v-else class="w-4 h-4 text-fg-subtle" />
            <span>{{ store.theme === 'dracula' ? 'Solarized' : 'Dracula' }}</span>
          </button>
          <div class="h-px bg-border mx-2 my-1"></div>
          <button
            @click="handleLogout(); isMenuOpen = false"
            :disabled="loggingOut"
            class="flex items-center gap-3 px-4 py-2.5 text-[13px] text-status-danger hover:bg-status-danger/10 transition-colors w-full text-left disabled:opacity-40"
          >
            <LogOut class="w-4 h-4" />
            <span>退出账户</span>
          </button>
        </div>
      </Transition>
    </Teleport>

    <!-- Mobile device panel -->
    <Transition name="slide-down">
      <div
        v-if="isPanelOpen && store.mode === 'relay'"
        class="sm:hidden w-full border-b border-border bg-app-bg z-20 py-3 px-4 flex flex-col gap-2 max-h-[260px] overflow-y-auto flex-shrink-0"
      >
        <div class="flex items-center justify-between">
          <span class="text-[11px] font-medium text-fg-subtle font-mono tracking-wider">选择设备</span>
          <button @click="isPanelOpen = false" class="text-[12px] text-accent hover:text-accent-hover transition-colors">收起</button>
        </div>
        <div class="flex flex-col gap-1">
          <button
            v-for="d in store.devices"
            :key="d.deviceId"
            @click="selectDevice(d.deviceId)"
            :class="['flex items-center gap-2.5 px-3 py-2 rounded-sm text-left transition-colors',
              store.selectedDeviceId === d.deviceId
                ? 'bg-accent-muted border-l-2 border-accent'
                : 'hover:bg-bg-tertiary border-l-2 border-transparent']"
          >
            <Monitor class="w-4 h-4 flex-shrink-0" :class="store.selectedDeviceId === d.deviceId ? 'text-accent' : 'text-fg-subtle'" />
            <span class="text-[13px] text-fg truncate flex-1">{{ d.deviceName }}</span>
            <span class="w-1.5 h-1.5 rounded-full flex-shrink-0" :class="d.status === 'online' ? 'bg-status-success' : 'bg-fg-disabled'"></span>
          </button>
          <div v-if="!store.devices.length" class="text-center py-4 text-[12px] text-fg-subtle font-mono">
            暂无在线设备
          </div>
        </div>
      </div>
    </Transition>

    <!-- Error banner -->
    <div
      v-if="store.managerError"
      class="mx-4 mt-3 p-2.5 text-[13px] text-status-danger bg-status-danger/10 border border-status-danger/20 rounded-sm flex items-center gap-2 font-mono flex-shrink-0"
    >
      <AlertTriangle class="w-3.5 h-3.5 flex-shrink-0" />
      <span class="truncate">{{ store.managerError }}</span>
    </div>

    <!-- Main content -->
    <main class="flex-1 flex flex-col sm:flex-row p-3 sm:p-4 gap-3 sm:gap-4 min-h-0">
      <!-- Device sidebar (desktop) -->
      <aside
        v-if="store.mode === 'relay'"
        class="hidden sm:flex w-[220px] flex-shrink-0 flex-col gap-2"
      >
        <div class="flex items-center justify-between px-1">
          <span class="text-[11px] font-medium text-fg-subtle font-mono tracking-wider">设备</span>
          <span class="text-[10px] font-mono text-fg-disabled">{{ store.devices.length }} 在线</span>
        </div>
        <div class="flex flex-col gap-0.5">
          <button
            v-for="d in store.devices"
            :key="d.deviceId"
            @click="selectDevice(d.deviceId)"
            :class="['flex items-center gap-2.5 px-3 py-2 rounded-sm text-left transition-colors w-full',
              store.selectedDeviceId === d.deviceId
                ? 'bg-accent-muted border-l-2 border-accent'
                : 'hover:bg-bg-tertiary border-l-2 border-transparent']"
          >
            <Monitor class="w-4 h-4 flex-shrink-0" :class="store.selectedDeviceId === d.deviceId ? 'text-accent' : 'text-fg-subtle'" />
            <span class="text-[13px] text-fg truncate flex-1">{{ d.deviceName }}</span>
            <span class="flex items-center gap-1 flex-shrink-0">
              <span v-if="store.selectedDeviceId === d.deviceId && store.p2pActive" class="text-[9px] text-accent font-mono">P2P</span>
              <span class="w-1.5 h-1.5 rounded-full" :class="d.status === 'online' ? 'bg-status-success animate-pulse-dot' : 'bg-fg-disabled'"></span>
            </span>
          </button>
          <div v-if="!store.devices.length" class="text-center py-8 text-[12px] text-fg-subtle font-mono">
            暂无在线设备
          </div>
        </div>
      </aside>

      <!-- Session list -->
      <section class="flex-1 flex flex-col min-h-0 min-w-0">
        <!-- Section header -->
        <div class="flex items-center justify-between mb-3 px-1 flex-shrink-0">
          <div class="flex items-center gap-3">
            <span class="text-[11px] font-medium text-fg-subtle font-mono tracking-wider">终端</span>
            <span v-if="store.selectedDeviceId" class="text-[10px] text-fg-disabled font-mono">{{ store.sessions.length }} 个</span>
          </div>
          <button
            @click="createSession"
            :disabled="!store.selectedDeviceId"
            class="flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-medium rounded-sm bg-accent hover:bg-accent-hover text-black transition-colors disabled:opacity-40 disabled:pointer-events-none active:scale-[0.99]"
          >
            <Plus class="w-3.5 h-3.5" />
            <span>新建终端</span>
          </button>
        </div>

        <!-- Empty: no device selected -->
        <div v-if="!store.selectedDeviceId" class="flex-1 flex flex-col items-center justify-center gap-3 text-fg-subtle">
          <Monitor class="w-8 h-8 opacity-30" />
          <span class="text-[13px]">选择一台设备以查看终端</span>
          <button
            v-if="store.mode === 'relay'"
            @click="isPanelOpen = !isPanelOpen"
            class="sm:hidden px-3 py-1.5 text-[12px] font-medium rounded-sm bg-accent hover:bg-accent-hover text-black transition-colors"
          >
            选择设备
          </button>
        </div>

        <!-- Empty: no sessions -->
        <div v-else-if="!store.sessions.length" class="flex-1 flex flex-col items-center justify-center gap-2 text-fg-subtle">
          <Terminal class="w-8 h-8 opacity-30" />
          <span class="text-[13px]">暂无终端，点击「新建终端」开始</span>
        </div>

        <!-- Session cards -->
        <div v-else class="flex-1 overflow-y-auto grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 content-start">
          <div
            v-for="(s, idx) in store.sessions"
            :key="s.id"
            role="link"
            tabindex="0"
            class="stagger-item group flex flex-col gap-3 p-4 rounded-md bg-app-panel border border-border hover:border-border-hover transition-colors cursor-pointer"
            :style="{ animationDelay: idx * 40 + 'ms' }"
            @click="openSession(s.id)"
            @keydown.enter.prevent="openSession(s.id)"
            @keydown.space.prevent="openSession(s.id)"
          >
            <!-- Header row -->
            <div class="flex items-start justify-between gap-2">
              <div class="flex-1 min-w-0">
                <span class="text-[10px] font-mono text-fg-disabled tracking-wider">SESSION</span>
                <h3 class="text-[14px] font-medium text-fg mt-0.5 truncate">{{ s.name || 'Terminal' }}</h3>
              </div>
              <button
                type="button"
                class="flex-shrink-0 w-5 h-5 flex items-center justify-center rounded-sm text-fg-disabled hover:text-status-danger hover:bg-status-danger/10 transition-colors opacity-0 group-hover:opacity-100"
                @click.stop="closeSession(s.id)"
                @keydown.enter.stop
                @keydown.space.stop
                title="关闭会话"
              >
                <X class="w-3.5 h-3.5" />
              </button>
            </div>

            <!-- Recent input preview -->
            <div class="flex-1 rounded-sm bg-app-bg/80 border border-border/50 p-2.5 font-mono text-[12px] text-fg-muted min-h-[44px] flex items-center">
              <span v-if="(s as any).recentInputHidden" class="text-fg-disabled italic">敏感输入已隐藏</span>
              <pre v-else-if="recentInputText(s)" class="w-full truncate leading-relaxed">{{ recentInputText(s) }}</pre>
              <span v-else class="text-fg-disabled italic">等待输入...</span>
            </div>

            <!-- CWD footer -->
            <div class="flex items-center gap-1.5 text-[11px] text-fg-subtle font-mono truncate">
              <Folder class="w-3 h-3 flex-shrink-0" />
              <span class="truncate">{{ s.cwd || '/' }}</span>
            </div>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import {
  Terminal, Sun, Moon, Plus, AlertTriangle, Folder, Monitor, LogOut, ChevronDown, MoreVertical, X
} from '@lucide/vue';
import { store, api, resetStore } from '../store';
import { p2pManager } from '../lib/p2p';
import { getDevices } from '../api/devices';
import { relayMuxSessionManager } from '../lib/relay-mux-session-manager';
import type { RelayMuxChannel } from '../lib/relay-mux-session';

const router = useRouter();

let pollTimer: any = null;
let ws: WebSocket | RelayMuxChannel | null = null;
let reconnectTimer: any = null;
let reconnectAttempts = 0;
let manualClose = false;
const loggingOut = ref(false);

const connectionHealth = computed(() => {
  return store.connectionStates['manager'] || 'disconnected';
});

const connectionLabel = computed(() => {
  switch (connectionHealth.value) {
    case 'connected': return '已连接';
    case 'connecting': return '连接中';
    case 'polling': return '轮询中';
    default: return '离线';
  }
});

let onlineHandler = () => {
  reconnectAttempts = 0;
  connectManagerWS();
};
let offlineHandler = () => {
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
};

const isPanelOpen = ref(false);
const isMenuOpen = ref(false);

let mediaQuery: MediaQueryList | null = null;

function handleMediaChange(e: MediaQueryListEvent | MediaQueryList) {
  if (e.matches) {
    isPanelOpen.value = false;
    isMenuOpen.value = false;
  }
}

function getSelectedDeviceName(): string {
  if (!store.selectedDeviceId) return '选择设备';
  const current = store.devices.find(d => d.deviceId === store.selectedDeviceId);
  return current ? current.deviceName : '选择设备';
}

async function bootstrapManager() {
  if (store.mode === 'relay') {
    await refreshDeviceList();
  }
  if (store.selectedDeviceId) {
    p2pManager.connectToDevice(store.selectedDeviceId);
    refreshSessionList();
  }
  connectManagerWS();
  startPolling();
}

onMounted(() => {
  document.title = "WebTerm";
  document.body.classList.remove("terminal-mode");

  store.connectionStates['manager'] = 'connecting';
  window.addEventListener('online', onlineHandler);
  window.addEventListener('offline', offlineHandler);

  bootstrapManager();

  mediaQuery = window.matchMedia('(min-width: 640px)');
  mediaQuery.addEventListener('change', handleMediaChange);
});

onUnmounted(() => {
  window.removeEventListener('online', onlineHandler);
  window.removeEventListener('offline', offlineHandler);
  stopPolling();
  closeManagerWS();
  if (mediaQuery) {
    mediaQuery.removeEventListener('change', handleMediaChange);
  }
  document.body.style.overflow = '';
});

watch(isPanelOpen, (newVal) => {
  if (newVal) {
    isMenuOpen.value = false;
    document.body.style.overflow = 'hidden';
  } else if (!isMenuOpen.value) {
    document.body.style.overflow = '';
  }
});

watch(isMenuOpen, (newVal) => {
  if (newVal) {
    isPanelOpen.value = false;
    document.body.style.overflow = 'hidden';
  } else if (!isPanelOpen.value) {
    document.body.style.overflow = '';
  }
});

function recentInputText(session: any): string {
  const lines = Array.isArray(session.recentInputLines)
    ? session.recentInputLines.filter(Boolean).slice(-2)
    : [];
  return lines.join("\n");
}

async function selectDevice(deviceId: string) {
  if (store.selectedDeviceId === deviceId) {
    isPanelOpen.value = false;
    return;
  }
  store.selectedDeviceId = deviceId;
  store.managerError = '';
  store.sessions = [];
  isPanelOpen.value = false;
  closeManagerWS();

  p2pManager.connectToDevice(deviceId);
  connectManagerWS();

  try {
    await refreshSessionList();
  } catch (err: any) {
    store.managerError = err.message || '获取会话列表失败';
  }
}

async function refreshDeviceList() {
  try {
    const devices = await getDevices();
    store.devices = devices.map((device) => ({
      deviceId: device.deviceId,
      deviceName: device.deviceName,
      status: device.online ? 'online' : 'offline',
    }));
    const selected = store.selectedDeviceId
      ? store.devices.find((device) => device.deviceId === store.selectedDeviceId)
      : null;
    if (store.selectedDeviceId && !selected) {
      store.selectedDeviceId = null;
      store.sessions = [];
    }
    if (!store.selectedDeviceId) {
      const firstOnline = store.devices.find((device) => device.status === 'online');
      if (firstOnline) {
        store.selectedDeviceId = firstOnline.deviceId;
      }
    }
  } catch (err: any) {
    if (err.status !== 404) {
      store.managerError = err.message || '加载设备列表失败';
    }
  }
}

async function refreshSessionList() {
  if (!store.selectedDeviceId) return;
  try {
    const rawSessions = await api("/api/sessions");
    store.sessions = Array.isArray(rawSessions) ? rawSessions : [];
  } catch (err: any) {
    if (err.status === 401) {
      router.push("/login");
    } else {
      store.managerError = err.message || '更新会话列表失败';
    }
  }
}

async function createSession() {
  stopPolling();
  try {
    store.managerError = '';
    const session = await api("/api/sessions", { method: "POST" });
    router.push(`/terminal/${encodeURIComponent(session.id)}`);
  } catch (err: any) {
    store.managerError = err.message || '新建终端会话失败';
    startPolling();
  }
}

function openSession(sessionId: string) {
  router.push(`/terminal/${encodeURIComponent(sessionId)}`);
}

async function closeSession(sessionId: string) {
  if (!confirm("确定要关闭这个终端会话吗？")) return;
  try {
    await api(`/api/sessions/${encodeURIComponent(sessionId)}`, { method: "DELETE" });
    await refreshSessionList();
  } catch (err: any) {
    store.managerError = err.message || "关闭会话失败";
  }
}

function toggleTheme() {
  store.theme = store.theme === 'solarized' ? 'dracula' : 'solarized';
}

function goDevices() {
  closeManagerWS();
  stopPolling();
  router.push('/devices');
}

async function handleLogout() {
  if (loggingOut.value) return;
  if (!confirm('确定要退出登录吗？')) return;
  loggingOut.value = true;
  try {
    closeManagerWS();
    stopPolling();
    await api('/api/auth/logout', { method: 'POST' });
  } catch {
    // ignore
  } finally {
    resetStore();
    loggingOut.value = false;
    router.push('/login');
  }
}

// ── WebSocket ──

function connectManagerWS() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
    return;
  }

  manualClose = false;

  if (store.mode === 'relay') {
    if (!store.selectedDeviceId) {
      store.connectionStates['manager'] = 'disconnected';
      return;
    }
    ws = relayMuxSessionManager.openManagerChannel(store.selectedDeviceId);
  } else {
    const proto = window.location.protocol === "https:" ? "wss" : "ws";
    const deviceParam = store.selectedDeviceId ? `&deviceId=${encodeURIComponent(store.selectedDeviceId)}` : '';
    ws = new WebSocket(`${proto}://${window.location.host}/ws/sessions?clientId=${store.clientId}${deviceParam}`);
  }
  const currentWs = ws;

  ws.addEventListener("open", () => {
    if (ws !== currentWs) return;
    store.connectionStates['manager'] = 'connected';
    reconnectAttempts = 0;
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    store.managerError = "";
    stopPolling();
  });

  ws.addEventListener("message", (event) => {
    if (ws !== currentWs) return;
    try {
      const msg = JSON.parse((event as MessageEvent).data);
      if (msg.type === "devices") {
        if (store.mode === 'direct') return;
        store.devices = Array.isArray(msg.devices) ? msg.devices : [];
        const isSelectedOnline = store.devices.some(d => d.deviceId === store.selectedDeviceId);
        if (store.selectedDeviceId && !isSelectedOnline) {
          store.selectedDeviceId = null;
          store.sessions = [];
        }
      } else if (msg.type === "sessions") {
        store.sessions = Array.isArray(msg.data) ? msg.data : [];
        stopPolling();
      } else if (msg.type === "session") {
        upsertSession(msg.data);
        stopPolling();
      } else if (msg.type === "session-closed") {
        removeSession(msg.id);
        stopPolling();
      } else if (msg.type === "p2p-ice") {
        p2pManager.handleRemoteCandidate(msg.candidate);
      } else if (msg.type === "error") {
        store.managerError = msg.message;
      }
    } catch (e) {
      console.error("解析WebSocket推送失败", e);
    }
  });

  ws.addEventListener("close", (event: any) => {
    if (ws !== currentWs) return;
    ws = null;
    if (manualClose) return;

    const blockList = [1000, 1008, 1011];
    if (!blockList.includes(event.code)) {
      store.connectionStates['manager'] = 'polling';
      startPolling();
      scheduleReconnect();
    } else {
      store.connectionStates['manager'] = 'disconnected';
      startPolling();
    }
  });

  ws.addEventListener("error", () => {
    if (ws !== currentWs) return;
    startPolling();
  });
}

function closeManagerWS() {
  manualClose = true;
  store.connectionStates['manager'] = 'disconnected';
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  if (ws) {
    ws.close();
    ws = null;
  }
}

function scheduleReconnect() {
  if (reconnectTimer) clearTimeout(reconnectTimer);
  const cap = Math.min(1000 * Math.pow(1.6, reconnectAttempts++), 8000);
  const delay = Math.max(200, Math.random() * cap);
  reconnectTimer = setTimeout(() => connectManagerWS(), delay);
}

function startPolling() {
  if (pollTimer) return;
  pollTimer = setInterval(async () => {
    if (store.selectedDeviceId) {
      try {
        await refreshSessionList();
      } catch (err: any) {
        store.managerError = err.message || '更新会话列表失败';
      }
    }
  }, 3000);
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

function upsertSession(session: any) {
  if (!session?.id) return;
  const index = store.sessions.findIndex((item) => item.id === session.id);
  if (index >= 0) {
    store.sessions.splice(index, 1, session);
  } else {
    store.sessions.push(session);
  }
}

function removeSession(id: string) {
  store.sessions = store.sessions.filter((s) => s.id !== id);
}
</script>
