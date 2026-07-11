<template>
  <div class="h-screen flex flex-col bg-app-bg text-fg overflow-hidden">
    <!-- Header -->
    <header class="flex items-center justify-between h-11 px-4 border-b border-border bg-app-bg flex-shrink-0 gap-3">
      <div class="flex items-center gap-3 min-w-0">
        <span class="text-[15px] font-semibold tracking-tight text-fg flex-shrink-0">WebTerm</span>
        <!-- Connection health badge -->
        <ConnectionBadge :health="connectionHealth" />

        <!-- Device selector trigger -->
        <button
          v-if="store.mode === 'relay'"
          data-testid="device-selector-trigger"
          @click="isDrawerOpen = !isDrawerOpen"
          class="lg:hidden flex items-center gap-1 px-2 py-1 text-[12px] rounded-sm border border-border bg-app-bg text-fg-muted hover:text-fg hover:border-border-hover transition-colors ml-1 min-w-0"
        >
          <span class="truncate">{{ getSelectedDeviceName() }}</span>
          <ChevronDown class="w-3 h-3 flex-shrink-0 transition-transform duration-200" :class="{ 'rotate-180': isDrawerOpen }" />
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

    <!-- Device drawer -->
    <DeviceDrawer
      v-model="isDrawerOpen"
      :devices="store.devices"
      :selected-device-id="store.selectedDeviceId"
      @select="selectDevice"
    />

    <!-- Error banner -->
    <div
      v-if="store.managerError"
      class="mx-4 mt-3 p-2.5 text-[13px] text-status-danger bg-status-danger/10 border border-status-danger/20 rounded-sm flex items-center gap-2 font-mono flex-shrink-0"
    >
      <AlertTriangle class="w-3.5 h-3.5 flex-shrink-0" />
      <span class="truncate">{{ store.managerError }}</span>
    </div>

    <!-- Main content -->
    <main class="flex-1 flex min-h-0 overflow-hidden">
      <!-- Device sidebar (large desktop) -->
      <aside
        v-if="store.mode === 'relay'"
        data-testid="device-sidebar"
        class="hidden lg:flex w-[220px] flex-shrink-0 flex-col gap-2 p-3 border-r border-border"
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
              <span class="w-1.5 h-1.5 rounded-full" :class="d.status === 'online' ? 'bg-status-success animate-pulse-dot' : 'bg-fg-disabled'"></span>
            </span>
          </button>
          <div v-if="!store.devices.length" class="text-center py-8 text-[12px] text-fg-subtle font-mono">
            暂无在线设备
          </div>
        </div>
      </aside>

      <!-- Session list -->
      <section
        data-testid="session-list-panel"
        :class="['flex flex-col min-h-0 p-3 sm:p-4 border-r border-border transition-all',
          isMobileTerminalOpen ? 'hidden' : 'flex',
          'w-full sm:w-[280px] lg:w-[320px] sm:flex-shrink-0'
        ]"
      >
        <SessionGrid
          :sessions="store.sessions"
          :selected-device-id="store.selectedDeviceId"
          :selected-session-id="store.selectedSessionId"
          @create="handleCreateSession"
          @open="openSession"
          @close="handleCloseSession"
        >
          <template #select-device-trigger>
            <button
              v-if="store.mode === 'relay'"
              @click="isDrawerOpen = !isDrawerOpen"
              class="sm:hidden px-3 py-1.5 text-[12px] font-medium rounded-sm bg-accent hover:bg-accent-hover text-black transition-colors"
            >
              选择设备
            </button>
          </template>
        </SessionGrid>
      </section>

      <!-- Terminal view -->
      <section
        data-testid="terminal-panel"
        :class="['flex-1 flex flex-col min-h-0 bg-app-bg relative',
          isMobileTerminalOpen ? 'fixed inset-0 z-30 flex' : 'hidden sm:flex'
        ]"
      >
        <template v-if="store.selectedSessionId">
          <TerminalPane
            :session-id="store.selectedSessionId"
            :inline="true"
          >
            <template #leading>
              <button
                v-if="isMobileTerminalOpen"
                @click="closeMobileTerminal"
                class="flex items-center gap-1.5 px-2 py-1 text-[12px] rounded-sm text-fg-muted hover:text-fg hover:bg-bg-tertiary transition-colors flex-shrink-0"
              >
                <ArrowLeft class="w-3.5 h-3.5" />
                <span>返回</span>
              </button>
            </template>
          </TerminalPane>
        </template>
        <div v-else class="flex-1 flex flex-col items-center justify-center gap-3 text-fg-subtle">
          <Terminal class="w-8 h-8 opacity-30" />
          <span class="text-[13px]">选择一个终端会话以开始</span>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, toRef, watch } from 'vue';
import { useRouter } from 'vue-router';
import {
  Sun, Moon, AlertTriangle, Monitor, LogOut, ChevronDown, MoreVertical, ArrowLeft, Terminal
} from '@lucide/vue';
import { store, resetStore } from '../store';
import { UnauthorizedError } from '../api/client';
import { logout } from '../services/auth.service';
import { fetchStoreDevices } from '../services/device.service';
import ConnectionBadge from '../components/ConnectionBadge.vue';
import SessionGrid from '../components/SessionGrid.vue';
import DeviceDrawer from '../components/DeviceDrawer.vue';
import TerminalPane from '../components/TerminalPane.vue';
import { fetchSessions, createSession, closeSession as closeSessionService } from '../services/session.service';
import { connectionService } from '../services/connection.service';
import { useManagerConnection, type ManagerServerMessage } from '../composables/useManagerConnection';
import { sortSessionsByAttention } from '../utils/session';
import type { Session, Device } from '../store';

const router = useRouter();
const loggingOut = ref(false);

function upsertSession(session: Session) {
  if (!session.id) return;
  const index = store.sessions.findIndex((item) => item.id === session.id);
  if (index >= 0) {
    store.sessions.splice(index, 1, session);
  } else {
    store.sessions.push(session);
  }
  store.sessions = sortSessionsByAttention(store.sessions);
}

function removeSession(id: string) {
  store.sessions = store.sessions.filter((s) => s.id !== id);
}

function handleManagerMessage(msg: ManagerServerMessage) {
  if (msg.type === 'devices') {
    if (store.mode === 'direct') return;
    store.devices = (msg.devices || []).map<Device>((d) => ({
      deviceId: d.deviceId,
      deviceName: d.deviceName,
      status: d.status ?? (d.online ? 'online' : 'offline'),
    }));
    const isSelectedOnline = store.devices.some(d => d.deviceId === store.selectedDeviceId);
    if (store.selectedDeviceId && !isSelectedOnline) {
      store.selectedDeviceId = null;
      store.selectedSessionId = null;
      store.sessions = [];
    }
  } else if (msg.type === 'sessions') {
    store.sessions = sortSessionsByAttention(Array.isArray(msg.data) ? (msg.data as Session[]) : []);
  } else if (msg.type === 'session') {
    upsertSession(msg.data as Session);
  } else if (msg.type === 'session-closed') {
    removeSession(msg.id);
    if (store.selectedSessionId === msg.id) {
      store.selectedSessionId = null;
    }
  } else if (msg.type === 'error') {
    store.managerError = msg.message;
  }
}

const managerConnection = useManagerConnection({
  deviceId: toRef(store, 'selectedDeviceId'),
  onMessage: handleManagerMessage,
  poll: refreshSessionList,
  onConnect: () => {
    store.managerError = '';
  },
});

const { connectionHealth } = managerConnection;

const isDrawerOpen = ref(false);
const isMenuOpen = ref(false);
const isDesktop = ref(false);

const isMobileTerminalOpen = computed(() => !isDesktop.value && store.selectedSessionId !== null);

let mobileQuery: MediaQueryList | null = null;
let largeQuery: MediaQueryList | null = null;

function handleMobileChange(e: MediaQueryListEvent | MediaQueryList) {
  isDesktop.value = e.matches;
  if (e.matches) {
    isDrawerOpen.value = false;
    isMenuOpen.value = false;
  }
}

function handleLargeChange(e: MediaQueryListEvent | MediaQueryList) {
  if (e.matches) {
    isDrawerOpen.value = false;
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
    await refreshSessionList();
  }
}

onMounted(() => {
  document.title = "WebTerm";
  document.body.classList.remove("terminal-mode");

  bootstrapManager();

  mobileQuery = window.matchMedia('(min-width: 640px)');
  isDesktop.value = mobileQuery.matches;
  mobileQuery.addEventListener('change', handleMobileChange);

  largeQuery = window.matchMedia('(min-width: 1024px)');
  largeQuery.addEventListener('change', handleLargeChange);
});

onUnmounted(() => {
  if (mobileQuery) {
    mobileQuery.removeEventListener('change', handleMobileChange);
  }
  if (largeQuery) {
    largeQuery.removeEventListener('change', handleLargeChange);
  }
  document.body.style.overflow = '';
});

watch(isDrawerOpen, (newVal) => {
  if (newVal) {
    isMenuOpen.value = false;
    document.body.style.overflow = 'hidden';
  } else if (!isMenuOpen.value) {
    document.body.style.overflow = '';
  }
});

watch(isMenuOpen, (newVal) => {
  if (newVal) {
    isDrawerOpen.value = false;
    document.body.style.overflow = 'hidden';
  } else if (!isDrawerOpen.value) {
    document.body.style.overflow = '';
  }
});

async function selectDevice(deviceId: string) {
  if (store.selectedDeviceId === deviceId) {
    isDrawerOpen.value = false;
    return;
  }
  store.selectedDeviceId = deviceId;
  store.selectedSessionId = null;
  store.managerError = '';
  store.sessions = [];
  isDrawerOpen.value = false;



  try {
    await refreshSessionList();
  } catch (err: any) {
    store.managerError = err.message || '获取会话列表失败';
  }
}

async function refreshDeviceList() {
  try {
    store.devices = await fetchStoreDevices();
    const selected = store.selectedDeviceId
      ? store.devices.find((device) => device.deviceId === store.selectedDeviceId)
      : null;
    if (store.selectedDeviceId && !selected) {
      store.selectedDeviceId = null;
      store.selectedSessionId = null;
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
    store.sessions = sortSessionsByAttention(await fetchSessions());
  } catch (err: any) {
    if (err instanceof UnauthorizedError) {
      router.push("/login");
    } else {
      store.managerError = err.message || '更新会话列表失败';
    }
  }
}

async function handleCreateSession() {
  try {
    store.managerError = '';
    const session = await createSession();

    if (!isDesktop.value) {
      router.push(`/terminal/${encodeURIComponent(session.id)}`);
      return;
    }

    store.selectedSessionId = session.id;
  } catch (err: any) {
    if (err instanceof UnauthorizedError) {
      router.push('/login');
      return;
    }
    store.managerError = err.message || '新建终端会话失败';
  }
}

function openSession(sessionId: string) {
  if (!isDesktop.value) {
    router.push(`/terminal/${encodeURIComponent(sessionId)}`);
    return;
  }

  store.selectedSessionId = sessionId;
}

function closeMobileTerminal() {
  store.selectedSessionId = null;
}

async function handleCloseSession(sessionId: string) {
  if (!confirm("确定要关闭这个终端会话吗？")) return;
  try {
    await closeSessionService(sessionId);
    if (store.selectedSessionId === sessionId) {
      store.selectedSessionId = null;
    }
    await refreshSessionList();
  } catch (err: any) {
    if (err instanceof UnauthorizedError) {
      router.push('/login');
      return;
    }
    store.managerError = err.message || "关闭会话失败";
  }
}

function toggleTheme() {
  store.theme = store.theme === 'solarized' ? 'dracula' : 'solarized';
}

function goDevices() {
  managerConnection.disconnect();
  router.push('/devices');
}

async function handleLogout() {
  if (loggingOut.value) return;
  if (!confirm('确定要退出登录吗？')) return;
  loggingOut.value = true;
  try {
    managerConnection.disconnect();
    connectionService.closeAll();
    await logout();
  } catch {
    // ignore
  } finally {
    resetStore();
    loggingOut.value = false;
    router.push('/login');
  }
}

</script>
