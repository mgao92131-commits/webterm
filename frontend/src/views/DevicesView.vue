<template>
  <div class="min-h-screen bg-app-bg text-fg flex flex-col">
    <!-- Header -->
    <header class="flex items-center h-11 px-4 border-b border-border bg-app-bg flex-shrink-0 gap-3">
      <button
        @click="router.push('/')"
        class="flex items-center gap-1.5 text-[13px] text-fg-muted hover:text-fg transition-colors"
      >
        <ArrowLeft class="w-4 h-4" />
        <span>返回</span>
      </button>
      <span class="text-[15px] font-semibold tracking-tight text-fg">设备管理</span>
    </header>

    <main class="flex-1 p-4 sm:p-6 max-w-3xl w-full mx-auto flex flex-col gap-8 overflow-y-auto">
      <!-- Section A: PC Agent Devices -->
      <section class="flex flex-col gap-4">
        <div class="flex items-start justify-between gap-4">
          <div>
            <h3 class="text-[13px] font-semibold text-fg">PC Agent 设备</h3>
            <p class="text-[12px] text-fg-subtle mt-1">用于远程登录你电脑的终端 Agent，每个设备需要一个独立的 secret</p>
          </div>
          <button
            @click="showAddForm = !showAddForm"
            class="flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-medium rounded-sm bg-accent hover:bg-accent-hover text-black transition-colors flex-shrink-0 active:scale-[0.99]"
          >
            <Plus class="w-3.5 h-3.5" />
            <span>添加设备</span>
          </button>
        </div>

        <!-- Add form -->
        <div v-if="showAddForm" class="flex flex-col gap-3 p-4 rounded-md bg-app-panel border border-border">
          <label class="flex flex-col gap-1.5">
            <span class="text-[13px] text-fg-muted">设备名称</span>
            <input
              v-model="newDeviceName"
              placeholder="如：MacBook Pro"
              class="h-10 px-3 rounded-sm bg-app-bg border border-border text-fg placeholder:text-fg-disabled focus:outline-none focus:border-accent focus:ring-1 focus:ring-accent transition-colors font-mono text-[13px]"
            />
          </label>
          <div class="flex gap-2">
            <button
              :disabled="creating || !newDeviceName.trim()"
              @click="handleCreateDevice"
              class="px-3 py-1.5 text-[12px] font-medium rounded-sm bg-accent hover:bg-accent-hover disabled:opacity-40 text-black transition-colors"
            >
              {{ creating ? '生成中...' : '生成 secret' }}
            </button>
            <button
              @click="cancelAdd"
              class="px-3 py-1.5 text-[12px] rounded-sm bg-bg-tertiary text-fg-muted hover:text-fg hover:bg-border transition-colors"
            >取消</button>
          </div>
        </div>

        <!-- Secret reveal -->
        <div v-if="newSecret" class="flex flex-col gap-3 p-4 rounded-md border border-status-danger/30 bg-status-danger/5">
          <div class="flex items-center gap-2 text-status-danger text-[13px] font-medium">
            <AlertTriangle class="w-4 h-4" />
            <span>secret 仅显示一次，请立即复制保存</span>
          </div>
          <div class="p-3 rounded-sm bg-app-bg border border-border font-mono text-[12px] text-fg break-all">
            {{ newSecret }}
          </div>
          <div class="flex gap-2">
            <button
              @click="copySecret"
              class="flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-medium rounded-sm bg-accent hover:bg-accent-hover text-black transition-colors"
            >
              <Copy class="w-3.5 h-3.5" />
              <span>{{ copied ? '已复制' : '复制' }}</span>
            </button>
            <button
              @click="dismissSecret"
              class="px-3 py-1.5 text-[12px] rounded-sm bg-bg-tertiary text-fg-muted hover:text-fg hover:bg-border transition-colors"
            >我已保存</button>
          </div>
          <p class="text-[12px] text-fg-subtle">
            将此 secret 配置到 PC Agent 的 <code class="text-accent font-mono">RELAY_SECRET</code> 环境变量后启动 Agent。
          </p>
        </div>

        <!-- Device list -->
        <div class="flex flex-col gap-0.5">
          <div v-if="agentDevices.length === 0" class="text-center py-8 text-[13px] text-fg-subtle font-mono">
            暂无 PC Agent 设备
          </div>
          <div
            v-for="d in agentDevices"
            :key="d.deviceId"
            class="flex items-center gap-3 px-3 py-2.5 rounded-sm hover:bg-bg-tertiary transition-colors group"
          >
            <Monitor class="w-4 h-4 flex-shrink-0" :class="d.online ? 'text-status-success' : 'text-fg-disabled'" />
            <div class="flex-1 min-w-0">
              <div class="text-[13px] text-fg truncate">{{ d.deviceName }}</div>
              <div class="text-[11px] text-fg-subtle font-mono mt-0.5 flex items-center gap-2">
                <span class="flex items-center gap-1">
                  <span class="w-1.5 h-1.5 rounded-full" :class="d.online ? 'bg-status-success' : 'bg-fg-disabled'"></span>
                  {{ d.online ? '在线' : '离线' }}
                </span>
                <span v-if="d.lastSeenAt">最后在线: {{ formatTime(d.lastSeenAt) }}</span>
                <span v-else>从未上线</span>
              </div>
            </div>
            <button
              @click="deleteAgentDevice(d)"
              class="flex-shrink-0 w-7 h-7 flex items-center justify-center rounded-sm text-fg-disabled hover:text-status-danger hover:bg-status-danger/10 transition-colors opacity-0 group-hover:opacity-100"
              title="删除设备"
            >
              <Trash2 class="w-3.5 h-3.5" />
            </button>
          </div>
        </div>
      </section>

      <!-- Divider -->
      <div class="h-px bg-border"></div>

      <!-- Section B: Trusted devices -->
      <section class="flex flex-col gap-4">
        <div>
          <h3 class="text-[13px] font-semibold text-fg">信任的浏览器/移动设备</h3>
          <p class="text-[12px] text-fg-subtle mt-1">已通过邮箱验证的设备。撤销信任后，该设备下次登录需重新输入验证码</p>
        </div>

        <div class="flex flex-col gap-0.5">
          <div v-if="trustedDevices.length === 0" class="text-center py-8 text-[13px] text-fg-subtle font-mono">
            暂无信任设备
          </div>
          <div
            v-for="d in trustedDevices"
            :key="d.id"
            class="flex items-center gap-3 px-3 py-2.5 rounded-sm hover:bg-bg-tertiary transition-colors group"
          >
            <Globe class="w-4 h-4 flex-shrink-0 text-fg-subtle" />
            <div class="flex-1 min-w-0">
              <div class="text-[13px] text-fg truncate">{{ d.deviceName || '未知设备' }}</div>
              <div class="text-[11px] text-fg-subtle font-mono mt-0.5">
                <span v-if="d.lastSeenAt">最后活跃: {{ formatTime(d.lastSeenAt) }}</span>
                <span v-else>从未活跃</span>
                <span class="ml-2">添加于: {{ formatTime(d.createdAt) }}</span>
              </div>
            </div>
            <button
              @click="deleteTrusted(d)"
              class="flex-shrink-0 px-2 py-1 text-[11px] rounded-sm text-status-danger hover:bg-status-danger/10 transition-colors opacity-0 group-hover:opacity-100"
            >撤销信任</button>
          </div>
        </div>
      </section>

      <!-- Error -->
      <p v-if="loadError" class="text-[13px] text-status-danger bg-status-danger/10 border border-status-danger/20 px-3 py-2 rounded-sm font-mono text-center">
        {{ loadError }}
      </p>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ArrowLeft, Plus, AlertTriangle, Copy, Trash2, Monitor, Globe } from '@lucide/vue';
import { fetchDevices, createDevice, removeDevice } from '../services/device.service';
import { getTrustedDevices, deleteTrustedDevice } from '../api/auth';

const router = useRouter();

interface AgentDevice {
  deviceId: string;
  deviceName: string;
  online: boolean;
  lastSeenAt: string | null;
  createdAt: string;
}

const agentDevices = ref<AgentDevice[]>([]);
const trustedDevices = ref<Awaited<ReturnType<typeof getTrustedDevices>>>([]);
const loadError = ref('');

const showAddForm = ref(false);
const newDeviceName = ref('');
const creating = ref(false);
const newSecret = ref('');
const copied = ref(false);

onMounted(() => {
  document.title = "设备管理 - WebTerm";
  refreshAll();
});

async function refreshAll() {
  loadError.value = '';
  await Promise.all([refreshAgentDevices(), refreshTrustedDevices()]);
}

async function refreshAgentDevices() {
  try {
    const data = await fetchDevices();
    agentDevices.value = data;
  } catch (err: any) {
    // direct 模式没有 /api/devices 端点，静默处理
    if (err.status !== 404) {
      loadError.value = err.message || '加载 PC Agent 设备失败';
    }
    agentDevices.value = [];
  }
}

async function refreshTrustedDevices() {
  try {
    trustedDevices.value = await getTrustedDevices();
  } catch (err: any) {
    if (err.status !== 404) {
      loadError.value = err.message || '加载信任设备失败';
    }
    trustedDevices.value = [];
  }
}

async function handleCreateDevice() {
  if (!newDeviceName.value.trim() || creating.value) return;
  creating.value = true;
  try {
    const res = await createDevice(newDeviceName.value.trim());
    newSecret.value = res.agentSecret;
    copied.value = false;
    showAddForm.value = false;
    newDeviceName.value = '';
    await refreshAgentDevices();
  } catch (err: any) {
    loadError.value = err.message || '创建设备失败';
  } finally {
    creating.value = false;
  }
}

function cancelAdd() {
  showAddForm.value = false;
  newDeviceName.value = '';
}

async function copySecret() {
  try {
    await writeClipboardText(newSecret.value);
    copied.value = true;
    setTimeout(() => { copied.value = false; }, 2000);
  } catch {
    loadError.value = '复制失败，请手动选择文本复制';
  }
}

async function writeClipboardText(value: string) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value);
    return;
  }
  const textArea = document.createElement('textarea');
  textArea.value = value;
  textArea.setAttribute('readonly', '');
  textArea.style.position = 'fixed';
  textArea.style.left = '-9999px';
  document.body.appendChild(textArea);
  textArea.select();
  try {
    if (!document.execCommand('copy')) {
      throw new Error('copy command failed');
    }
  } finally {
    document.body.removeChild(textArea);
  }
}

function dismissSecret() {
  newSecret.value = '';
}

async function deleteAgentDevice(d: AgentDevice) {
  if (!confirm(`确定要删除设备 "${d.deviceName}" 吗？\n该设备的 PC Agent 将无法再连接中转服务器。`)) return;
  try {
    await removeDevice(d.deviceId);
    await refreshAgentDevices();
  } catch (err: any) {
    loadError.value = err.message || '删除设备失败';
  }
}

async function deleteTrusted(d: { id: string; deviceName: string | null }) {
  if (!confirm(`确定要撤销对 "${d.deviceName || '未知设备'}" 的信任吗？\n该设备下次登录将需要重新输入验证码。`)) return;
  try {
    await deleteTrustedDevice(d.id);
    await refreshTrustedDevices();
  } catch (err: any) {
    loadError.value = err.message || '撤销信任失败';
  }
}

function formatTime(iso: string | null): string {
  if (!iso) return '—';
  try {
    return new Date(iso.endsWith('Z') ? iso : iso + 'Z').toLocaleString('zh-CN', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit',
    });
  } catch {
    return iso;
  }
}
</script>
