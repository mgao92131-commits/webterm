<template>
  <div class="manager-layout min-h-screen bg-gradient-to-br from-slate-950 via-slate-900 to-indigo-950 text-slate-100 flex flex-col font-sans relative overflow-y-auto md:overflow-hidden">
    <!-- 背景光效装饰 -->
    <div class="absolute w-[600px] h-[600px] rounded-full bg-indigo-500/5 blur-[120px] top-[-10%] right-[-10%] pointer-events-none"></div>
    <div class="absolute w-[500px] h-[500px] rounded-full bg-cyan-500/5 blur-[100px] bottom-[-10%] left-[-10%] pointer-events-none"></div>

    <!-- 顶部状态栏 -->
    <header class="topbar w-full px-4 md:px-6 py-4 border-b border-slate-800/80 bg-slate-950/40 backdrop-blur-md flex items-center justify-between z-30 gap-4">
      <div class="flex items-center gap-3 overflow-hidden">
        <div class="p-2 rounded-lg bg-indigo-500/10 border border-indigo-500/20 text-indigo-400 flex-shrink-0">
          <Terminal class="w-6 h-6" />
        </div>
        <div class="overflow-hidden">
          <h1 class="text-xl font-bold tracking-wider bg-gradient-to-r from-indigo-400 to-white bg-clip-text text-transparent flex items-center gap-2">
            WebTerm
            <span v-if="store.mode === 'relay' && store.selectedDeviceId" class="hidden md:inline-block text-[10px] px-2 py-0.5 rounded-full font-semibold border font-mono tracking-normal leading-none bg-emerald-500/10 text-emerald-400 border-emerald-500/20">
              {{ store.p2pActive ? '🟢 直连' : '🟡 中继' }}
            </span>
          </h1>
          <p class="text-xs text-slate-500 font-mono truncate hidden md:block">{{ store.user?.username || 'Guest' }}</p>
        </div>

        <!-- 移动端设备切换单按钮 (只在 Relay 模式渲染) -->
        <button
          v-if="store.mode === 'relay'"
          @click="isPanelOpen = !isPanelOpen"
          class="md:hidden flex items-center gap-1.5 px-3 py-1.5 text-xs font-mono rounded-lg border border-slate-800 bg-slate-900/60 text-slate-300 hover:text-white hover:border-slate-700 active:scale-95 transition-all ml-1"
        >
          <span class="truncate max-w-[100px]">{{ getSelectedDeviceName() }}</span>
          <ChevronDown class="w-3.5 h-3.5 transition-transform duration-300 flex-shrink-0" :class="{ 'rotate-180': isPanelOpen }" />
        </button>
      </div>

      <div class="actions flex items-center gap-2.5">
        <!-- 设备管理 (两端常驻，小屏仅显示图标) -->
        <button
          id="devices"
          @click="goDevices"
          class="flex items-center justify-center gap-2 px-3 py-2 text-xs md:text-sm font-medium rounded-lg bg-slate-900 border border-slate-800 hover:border-slate-700 hover:text-white transition-all"
          title="设备管理"
        >
          <Monitor class="w-4 h-4 text-cyan-400" />
          <span class="hidden md:inline">设备管理</span>
        </button>

        <!-- 桌面端可见普通操作按钮 -->
        <button
          @click="toggleTheme"
          class="hidden md:flex items-center justify-center gap-2 px-3 py-2 text-sm font-medium rounded-lg bg-slate-900 border border-slate-800 hover:border-slate-700 hover:text-white transition-all"
        >
          <Sun v-if="store.theme === 'dracula'" class="w-4 h-4 text-amber-400" />
          <Moon v-else class="w-4 h-4 text-indigo-400" />
          <span>{{ store.theme === 'dracula' ? 'Solarized' : 'Dracula' }}</span>
        </button>
        <button
          @click="handleLogout"
          :disabled="loggingOut"
          class="hidden md:flex items-center justify-center gap-2 px-3 py-2 text-sm font-medium rounded-lg bg-slate-900 border border-slate-800 hover:border-rose-700/60 hover:text-rose-400 transition-all disabled:opacity-50"
        >
          <LogOut class="w-4 h-4" />
          <span>{{ loggingOut ? '退出中...' : '退出' }}</span>
        </button>

        <!-- 移动端更多操作按钮 -->
        <button
          @click="isMenuOpen = !isMenuOpen"
          class="md:hidden flex items-center justify-center p-2 rounded-lg bg-slate-900 border border-slate-800 hover:border-slate-700 text-slate-400 hover:text-white transition-all"
          title="更多选项"
        >
          <MoreVertical class="w-4 h-4" />
        </button>
      </div>
    </header>

    <!-- Popover 气泡菜单 Teleport 至 Body -->
    <Teleport to="body">
      <div v-if="isMenuOpen" class="fixed inset-0 z-50 bg-transparent" @click="isMenuOpen = false"></div>
      <Transition name="fade">
        <div
          v-if="isMenuOpen"
          class="fixed right-4 top-16 w-44 rounded-xl border border-slate-850 bg-slate-950/95 backdrop-blur-xl shadow-2xl z-[60] py-1.5 flex flex-col font-sans"
        >
          <button
            @click="toggleTheme(); isMenuOpen = false"
            class="flex items-center gap-3 px-4 py-3 text-sm text-slate-300 hover:text-white hover:bg-slate-900/60 transition-colors w-full text-left"
          >
            <Sun v-if="store.theme === 'dracula'" class="w-4 h-4 text-amber-400" />
            <Moon v-else class="w-4 h-4 text-indigo-400" />
            <span>{{ store.theme === 'dracula' ? 'Solarized 主题' : 'Dracula 主题' }}</span>
          </button>
          <div class="h-px bg-slate-900 my-1"></div>
          <button
            @click="handleLogout(); isMenuOpen = false"
            :disabled="loggingOut"
            class="flex items-center gap-3 px-4 py-3 text-sm text-rose-400 hover:text-rose-300 hover:bg-slate-900/60 transition-colors w-full text-left disabled:opacity-50"
          >
            <LogOut class="w-4 h-4" />
            <span>退出账户</span>
          </button>
        </div>
      </Transition>
    </Teleport>

    <!-- 移动端顶部下拉折叠设备面板 -->
    <Transition name="slide-down">
      <section
        v-if="isPanelOpen && store.mode === 'relay'"
        class="md:hidden w-full border-b border-slate-850 bg-slate-950/90 backdrop-blur-md z-20 py-3 px-4 flex flex-col gap-3 max-h-[300px] overflow-y-auto flex-shrink-0"
      >
        <div class="flex items-center justify-between border-b border-slate-900 pb-2">
          <h4 class="text-xs font-bold text-slate-500 tracking-wider font-mono">选择在线设备</h4>
          <button
            @click="isPanelOpen = false"
            class="text-xs text-indigo-400 hover:text-indigo-300 font-semibold"
          >
            收起 ✕
          </button>
        </div>
        <div class="flex flex-col gap-2">
          <div
            v-for="d in store.devices"
            :key="d.deviceId"
            :class="['flex items-center justify-between p-3 rounded-lg border transition-all cursor-pointer',
                     store.selectedDeviceId === d.deviceId
                       ? 'bg-indigo-600/10 border-indigo-500/40'
                       : 'bg-slate-900/40 border-slate-900 hover:border-slate-800']"
            @click="selectDevice(d.deviceId)"
          >
            <div class="flex items-center gap-2.5 overflow-hidden">
              <span class="text-lg flex-shrink-0">💻</span>
              <span class="font-medium text-sm text-slate-200 truncate">{{ d.deviceName }}</span>
            </div>
            <div class="flex items-center gap-2 flex-shrink-0">
              <span v-if="store.selectedDeviceId === d.deviceId && store.p2pActive" class="text-[9px] text-cyan-400 border border-cyan-500/20 bg-cyan-500/5 px-1.5 py-0.5 rounded leading-none font-mono">⚡P2P</span>
              <span class="w-1.5 h-1.5 rounded-full bg-emerald-500"></span>
            </div>
          </div>
          <div v-if="!store.devices.length" class="text-center py-6 text-xs text-slate-500 font-mono">
            暂无在线设备，请先启动 PC Agent。
          </div>
        </div>
      </section>
    </Transition>

    <!-- 全局报错提示 -->
    <div 
      v-if="store.managerError" 
      id="managerError"
      class="mx-4 md:mx-6 mt-4 p-3 text-sm text-rose-500 bg-rose-500/10 border border-rose-500/20 rounded-lg flex items-center gap-2 font-mono"
    >
      <AlertTriangle class="w-4 h-4 flex-shrink-0" />
      <span>{{ store.managerError }}</span>
    </div>

    <!-- 主面板 -->
    <main class="flex-1 flex flex-col md:flex-row p-4 md:p-6 gap-4 md:gap-6 z-10 overflow-y-auto md:overflow-hidden">
      <!-- 左侧设备中心（仅中转模式且在桌面端显示） -->
      <section 
        v-if="store.mode === 'relay'"
        class="device-section hidden md:flex w-full md:w-[280px] flex-shrink-0 bg-slate-900/40 border border-slate-800/80 backdrop-blur-md rounded-xl p-4 flex-col gap-4 overflow-y-auto"
      >
        <div class="flex items-center justify-between border-b border-slate-850 pb-2">
          <h3 class="text-sm font-bold text-slate-400 tracking-wider font-mono">设备中心</h3>
          <span class="text-xs font-mono px-2 py-0.5 rounded bg-slate-800 text-slate-400">
            {{ store.devices.length }} 在线
          </span>
        </div>
        
        <div class="device-list flex flex-col gap-2">
          <div 
            v-for="d in store.devices" 
            :key="d.deviceId"
            :class="['device-card flex items-center gap-3 p-3 rounded-lg border cursor-pointer transition-all hover:scale-[1.01]', 
                     store.selectedDeviceId === d.deviceId 
                       ? 'bg-indigo-600/15 border-indigo-500/50 hover:border-indigo-500' 
                       : 'bg-slate-950/20 border-slate-850/50 hover:bg-slate-900/30 hover:border-slate-800']"
            @click="selectDevice(d.deviceId)"
          >
            <div class="text-2xl">💻</div>
            <div class="flex-1 overflow-hidden">
              <div class="device-name font-medium text-sm text-slate-200 truncate">{{ d.deviceName }}</div>
              <div class="device-status device-status-online flex items-center gap-1.5 text-xs text-emerald-400 mt-1 font-mono">
                <span class="status-dot w-2 h-2 rounded-full bg-emerald-500 animate-pulse"></span>
                <span>在线</span>
                <span v-if="store.selectedDeviceId === d.deviceId && store.p2pActive" class="text-[10px] text-cyan-400 font-mono ml-2 border border-cyan-500/25 bg-cyan-500/5 px-1.5 py-0.5 rounded leading-none">⚡P2P</span>
              </div>
            </div>
          </div>
          
          <div v-if="!store.devices.length" class="empty-devices text-xs text-slate-500 text-center py-8 font-mono">
            暂无在线设备，请先在电脑上启动 PC Agent。
          </div>
        </div>
      </section>

      <!-- 右侧会话列表 -->
      <section class="session-section w-full md:flex-1 bg-slate-900/40 border border-slate-800/80 backdrop-blur-md rounded-xl p-4 md:p-6 flex flex-col gap-4 overflow-y-auto">
        <div class="flex items-center justify-between border-b border-slate-850 pb-2">
          <h3 class="text-sm font-bold text-slate-400 tracking-wider font-mono">会话列表</h3>
          <div class="flex items-center gap-3">
            <span v-if="store.selectedDeviceId" class="text-xs text-slate-500 font-mono hidden sm:inline">
              {{ store.sessions.length }} 个活动终端
            </span>
            <button
              id="new"
              @click="createSession"
              :disabled="!store.selectedDeviceId"
              class="flex items-center justify-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 disabled:pointer-events-none text-white hover:shadow-lg hover:shadow-indigo-500/20 transition-all active:scale-[0.98]"
            >
              <Plus class="w-3.5 h-3.5" />
              <span>新建终端</span>
            </button>
          </div>
        </div>

        <div v-if="!store.selectedDeviceId" class="empty text-slate-500 flex flex-col items-center justify-center py-20 font-mono text-sm gap-4">
          <ArrowLeft class="w-6 h-6 animate-bounce hidden md:block" />
          <span>请先选择一台设备以查看终端</span>
          <button
            v-if="store.mode === 'relay'"
            @click="isPanelOpen = !isPanelOpen"
            class="md:hidden px-4 py-2.5 text-xs font-semibold rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white transition-all flex items-center gap-1.5 active:scale-95"
          >
            <Monitor class="w-3.5 h-3.5" />
            <span>选择在线设备</span>
          </button>
        </div>

        <div v-else-if="!store.sessions.length" class="empty text-slate-500 flex flex-col items-center justify-center py-20 font-mono text-sm gap-2">
          <Terminal class="w-8 h-8 opacity-40" />
          <span>该设备上还没有终端，点击右上角“新建终端”开启</span>
        </div>

        <div v-else class="session-list grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          <article 
            v-for="s in store.sessions" 
            :key="s.id" 
            class="session-card group relative bg-slate-950/40 border border-slate-850/60 rounded-xl overflow-hidden hover:border-indigo-500/50 hover:bg-slate-950/60 hover:shadow-xl hover:shadow-indigo-950/10 transition-all duration-300 flex flex-col"
          >
            <!-- 关闭按钮 -->
            <button 
              class="session-close absolute top-3 right-3 text-slate-600 hover:text-rose-400 hover:scale-115 transition-all p-1 rounded-md bg-slate-900/20 group-hover:opacity-100 z-10 text-xs font-bold leading-none"
              @click.prevent="closeSession(s.id)"
              title="关闭会话"
            >
              ✕
            </button>
            
            <router-link 
              :to="'/terminal/' + encodeURIComponent(s.id)" 
              class="session-link p-5 flex flex-col gap-3 flex-1"
            >
              <div>
                <span class="text-slate-500 text-xs font-mono block">SESSION</span>
                <div class="session-title text-slate-200 font-semibold group-hover:text-white transition-colors truncate mt-1">
                  {{ s.name || 'Terminal' }}
                </div>
              </div>

              <!-- 敏感输入或最近输入展示 -->
              <div class="bg-slate-950/80 border border-slate-900 rounded-lg p-3 font-mono text-xs text-indigo-300 flex-1 min-h-[50px] flex items-center">
                <span v-if="(s as any).recentInputHidden" class="text-slate-600 italic">敏感输入已隐藏</span>
                <pre v-else-if="recentInputText(s)" class="w-full text-slate-300 truncate leading-relaxed">{{ recentInputText(s) }}</pre>
                <span v-else class="text-slate-700 italic">等待命令输入...</span>
              </div>

              <div class="session-cwd text-slate-500 text-xs font-mono truncate flex items-center gap-1.5 mt-1 border-t border-slate-900/40 pt-2">
                <Folder class="w-3.5 h-3.5 flex-shrink-0" />
                <span class="truncate">{{ s.cwd || '/' }}</span>
              </div>
            </router-link>
          </article>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import {
  Terminal, Sun, Moon, Plus, AlertTriangle, ArrowLeft, Folder, Monitor, LogOut, ChevronDown, MoreVertical
} from '@lucide/vue';
import { store, api, resetStore } from '../store';
import { p2pManager } from '../lib/p2p';

const router = useRouter();

// 状态和定时器定义
let pollTimer: any = null;
let ws: WebSocket | null = null;
let reconnectTimer: any = null;
let reconnectAttempts = 0;
let manualClose = false;
const loggingOut = ref(false);

const isPanelOpen = ref(false); // 移动端顶部设备折叠面板状态
const isMenuOpen = ref(false);  // 移动端更多操作 Popover 状态

let mediaQuery: MediaQueryList | null = null;

function handleMediaChange(e: MediaQueryListEvent | MediaQueryList) {
  if (e.matches) {
    // 屏幕大于等于 768px (PC端) 时，重置移动端面板状态
    isPanelOpen.value = false;
    isMenuOpen.value = false;
  }
}

function getSelectedDeviceName(): string {
  if (!store.selectedDeviceId) return '选择设备';
  const current = store.devices.find(d => d.deviceId === store.selectedDeviceId);
  return current ? current.deviceName : '选择设备';
}

onMounted(() => {
  document.title = "WebTerm";
  document.body.classList.remove("terminal-mode");
  
  // 如果当前选中的设备存在，拉取一次会话列表并尝试直连
  if (store.selectedDeviceId) {
    p2pManager.connectToDevice(store.selectedDeviceId);
    refreshSessionList();
  }
  
  // 启动后台WS实时推送及轮询刷新机制
  connectManagerWS();
  startPolling(); // 无论WS是否在线，大厅会话列表始终走轮询同步

  // 绑定响应式媒体查询
  mediaQuery = window.matchMedia('(min-width: 768px)');
  mediaQuery.addEventListener('change', handleMediaChange);
});

onUnmounted(() => {
  stopPolling();
  closeManagerWS();
  if (mediaQuery) {
    mediaQuery.removeEventListener('change', handleMediaChange);
  }
  document.body.style.overflow = ''; // 卸载时恢复背景滚动
});

// 互斥与页面滚动锁定
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

// 计算最近输入展示文本
function recentInputText(session: any): string {
  const lines = Array.isArray(session.recentInputLines) 
    ? session.recentInputLines.filter(Boolean).slice(-2) 
    : [];
  return lines.join("\n");
}

// 切换设备
async function selectDevice(deviceId: string) {
  if (store.selectedDeviceId === deviceId) {
    isPanelOpen.value = false;
    return;
  }
  store.selectedDeviceId = deviceId;
  store.managerError = '';
  store.sessions = [];
  isPanelOpen.value = false; // 选中后自动收起
  
  // 启动 P2P 直连握手
  p2pManager.connectToDevice(deviceId);

  try {
    await refreshSessionList();
  } catch (err: any) {
    store.managerError = err.message || '获取会话列表失败';
  }
}

// 刷新会话列表 HTTP 接口
async function refreshSessionList() {
  if (!store.selectedDeviceId) return;
  try {
    const rawSessions = await api("/api/sessions");
    store.sessions = rawSessions;
  } catch (err: any) {
    if (err.status === 401) {
      router.push("/login");
    } else {
      store.managerError = err.message || '更新会话列表失败';
    }
  }
}

// 新建终端会话
async function createSession() {
  stopPolling();
  try {
    store.managerError = '';
    const session = await api("/api/sessions", {
      method: "POST",
    });
    router.push(`/terminal/${encodeURIComponent(session.id)}`);
  } catch (err: any) {
    store.managerError = err.message || '新建终端会话失败';
    startPolling();
  }
}

// 关闭会话
async function closeSession(sessionId: string) {
  if (!confirm("确定要关闭这个终端会话吗？")) return;
  try {
    // 提取本地会话 ID 发送给 API 端，中转服务器会自动使用 X-Device-Id 进行寻址转发
    await api(`/api/sessions/${encodeURIComponent(sessionId)}`, { 
      method: "DELETE" 
    });
    await refreshSessionList();
  } catch (err: any) {
    store.managerError = err.message || "关闭会话失败";
  }
}

// 切换主题
function toggleTheme() {
  store.theme = store.theme === 'solarized' ? 'dracula' : 'solarized';
}

// 跳转设备管理
function goDevices() {
  closeManagerWS();
  stopPolling();
  router.push('/devices');
}

// 退出登录
async function handleLogout() {
  if (loggingOut.value) return;
  if (!confirm('确定要退出登录吗？')) return;
  loggingOut.value = true;
  try {
    closeManagerWS();
    stopPolling();
    await api('/api/auth/logout', { method: 'POST' });
  } catch {
    // 即使后端 logout 失败也要清前端状态
  } finally {
    resetStore();
    loggingOut.value = false;
    router.push('/login');
  }
}

// --- WebSocket 业务监听逻辑 ---

function connectManagerWS() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
    return;
  }
  
  const proto = window.location.protocol === "https:" ? "wss" : "ws";
  manualClose = false;
  
  const deviceParam = store.selectedDeviceId ? `&deviceId=${encodeURIComponent(store.selectedDeviceId)}` : '';
  ws = new WebSocket(`${proto}://${window.location.host}/ws/sessions?clientId=${store.clientId}${deviceParam}`);
  
  ws.addEventListener("open", () => {
    reconnectAttempts = 0;
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    store.managerError = "";
  });
  
  ws.addEventListener("message", (event) => {
    try {
      const msg = JSON.parse(event.data);
      if (msg.type === "devices") {
        if (store.mode === 'direct') return;
        store.devices = Array.isArray(msg.devices) ? msg.devices : [];
        const isSelectedOnline = store.devices.some(d => d.deviceId === store.selectedDeviceId);
        if (store.selectedDeviceId && !isSelectedOnline) {
          store.selectedDeviceId = null;
          store.sessions = [];
        }
      } else if (msg.type === "p2p-ice") {
        p2pManager.handleRemoteCandidate(msg.candidate);
      } else if (msg.type === "error") {
        store.managerError = msg.message;
      }
    } catch (e) {
      console.error("解析WebSocket推送失败", e);
    }
  });
  
  ws.addEventListener("close", () => {
    ws = null;
    if (manualClose) return;
    
    // 降级为 HTTP 轮询刷新，并进行网络指数退避重连
    startPolling();
    scheduleReconnect();
  });
  
  ws.addEventListener("error", () => {
    startPolling();
  });
}

function closeManagerWS() {
  manualClose = true;
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
  const delay = Math.min(1000 * Math.pow(1.6, reconnectAttempts++), 8000);
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

<style scoped>
/* 可在这里编写过渡或微调样式 */
</style>
