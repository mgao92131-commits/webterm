<template>
  <div class="devices-layout min-h-screen bg-gradient-to-br from-slate-950 via-slate-900 to-indigo-950 text-slate-100 flex flex-col font-sans">
    <div class="absolute w-[500px] h-[500px] rounded-full bg-indigo-500/5 blur-[120px] top-[-10%] right-[-10%] pointer-events-none"></div>

    <header class="w-full px-4 md:px-6 py-4 border-b border-slate-800/80 bg-slate-950/40 backdrop-blur-md flex items-center justify-between z-10">
      <div class="flex items-center gap-3">
        <button
          @click="router.push('/')"
          class="text-slate-400 hover:text-white transition-colors flex items-center gap-1 text-sm"
        >
          <ArrowLeft class="w-4 h-4" />
          <span>返回</span>
        </button>
        <h1 class="text-xl font-bold tracking-wider bg-gradient-to-r from-indigo-400 to-white bg-clip-text text-transparent">
          设备管理
        </h1>
      </div>
    </header>

    <main class="flex-1 p-4 md:p-6 z-10 overflow-y-auto max-w-4xl w-full mx-auto flex flex-col gap-6">
      <!-- 区块 A：PC Agent 设备 -->
      <section class="bg-slate-900/40 border border-slate-800/80 backdrop-blur-md rounded-xl p-5 flex flex-col gap-4">
        <div class="flex items-center justify-between border-b border-slate-850 pb-3">
          <div>
            <h3 class="text-sm font-bold text-slate-300 tracking-wider font-mono">PC Agent 设备</h3>
            <p class="text-xs text-slate-500 mt-1">用于远程登录你电脑的终端 Agent，每个设备需要一个独立的 secret</p>
          </div>
          <button
            @click="showAddForm = !showAddForm"
            class="flex items-center gap-2 px-3 py-2 text-xs font-semibold rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white transition-all active:scale-[0.98]"
          >
            <Plus class="w-4 h-4" />
            <span>添加设备</span>
          </button>
        </div>

        <!-- 添加表单 -->
        <div v-if="showAddForm" class="bg-slate-950/40 border border-slate-800 rounded-lg p-4 flex flex-col gap-3">
          <label class="flex flex-col gap-2 text-sm text-slate-400">
            <span>设备名称</span>
            <input
              v-model="newDeviceName"
              placeholder="如：MacBook Pro"
              class="px-3 py-2 rounded-lg bg-slate-950/50 border border-slate-800 text-slate-100 placeholder:text-slate-700 focus:outline-none focus:ring-2 focus:ring-indigo-500/40 focus:border-indigo-500 transition-all font-mono text-sm"
            />
          </label>
          <div class="flex gap-2">
            <button
              :disabled="creating || !newDeviceName.trim()"
              @click="createDevice"
              class="px-3 py-2 text-xs font-semibold rounded-lg bg-indigo-600 hover:bg-indigo-500 disabled:opacity-40 text-white transition-all"
            >
              {{ creating ? '生成中...' : '生成 secret' }}
            </button>
            <button
              @click="cancelAdd"
              class="px-3 py-2 text-xs font-medium rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 transition-all"
            >
              取消
            </button>
          </div>
        </div>

        <!-- 新生成的 secret 展示 -->
        <div v-if="newSecret" class="bg-amber-500/10 border border-amber-500/30 rounded-lg p-4 flex flex-col gap-3">
          <div class="flex items-center gap-2 text-amber-400 text-sm font-semibold">
            <AlertTriangle class="w-4 h-4" />
            <span>secret 仅显示一次，请立即复制保存</span>
          </div>
          <div class="bg-slate-950/80 border border-slate-800 rounded-lg p-3 font-mono text-xs text-amber-300 break-all">
            {{ newSecret }}
          </div>
          <div class="flex gap-2">
            <button
              @click="copySecret"
              class="px-3 py-2 text-xs font-semibold rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white transition-all flex items-center gap-2"
            >
              <Copy class="w-3.5 h-3.5" />
              <span>{{ copied ? '已复制' : '复制到剪贴板' }}</span>
            </button>
            <button
              @click="dismissSecret"
              class="px-3 py-2 text-xs font-medium rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 transition-all"
            >
              我已保存
            </button>
          </div>
          <p class="text-xs text-slate-500">
            将此 secret 配置到 PC Agent 的 <code class="text-indigo-400">RELAY_SECRET</code> 环境变量后启动 Agent。
          </p>
        </div>

        <!-- 设备列表 -->
        <div v-if="agentDevices.length === 0" class="text-xs text-slate-500 text-center py-6 font-mono">
          暂无 PC Agent 设备，点击右上角添加。
        </div>
        <div v-else class="flex flex-col gap-2">
          <div
            v-for="d in agentDevices"
            :key="d.deviceId"
            class="flex items-center gap-3 p-3 rounded-lg bg-slate-950/20 border border-slate-850/50"
          >
            <div class="text-2xl">💻</div>
            <div class="flex-1 overflow-hidden">
              <div class="font-medium text-sm text-slate-200 truncate">{{ d.deviceName }}</div>
              <div class="text-xs text-slate-500 font-mono mt-1 flex items-center gap-2">
                <span
                  :class="['inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px]',
                    d.online ? 'bg-emerald-500/10 text-emerald-400' : 'bg-slate-800 text-slate-500']"
                >
                  <span class="w-1.5 h-1.5 rounded-full" :class="d.online ? 'bg-emerald-500' : 'bg-slate-600'"></span>
                  {{ d.online ? '在线' : '离线' }}
                </span>
                <span v-if="d.lastSeenAt">最后在线: {{ formatTime(d.lastSeenAt) }}</span>
                <span v-else>从未上线</span>
              </div>
            </div>
            <button
              @click="deleteAgentDevice(d)"
              class="text-slate-500 hover:text-rose-400 transition-colors p-1"
              title="删除设备"
            >
              <Trash2 class="w-4 h-4" />
            </button>
          </div>
        </div>
      </section>

      <!-- 区块 B：信任的浏览器/移动设备 -->
      <section class="bg-slate-900/40 border border-slate-800/80 backdrop-blur-md rounded-xl p-5 flex flex-col gap-4">
        <div class="border-b border-slate-850 pb-3">
          <h3 class="text-sm font-bold text-slate-300 tracking-wider font-mono">信任的浏览器/移动设备</h3>
          <p class="text-xs text-slate-500 mt-1">已通过邮箱验证的设备。撤销信任后，该设备下次登录需重新输入验证码</p>
        </div>

        <div v-if="trustedDevices.length === 0" class="text-xs text-slate-500 text-center py-6 font-mono">
          暂无信任设备
        </div>
        <div v-else class="flex flex-col gap-2">
          <div
            v-for="d in trustedDevices"
            :key="d.id"
            class="flex items-center gap-3 p-3 rounded-lg bg-slate-950/20 border border-slate-850/50"
          >
            <div class="text-2xl">🌐</div>
            <div class="flex-1 overflow-hidden">
              <div class="font-medium text-sm text-slate-200 truncate">{{ d.deviceName || '未知设备' }}</div>
              <div class="text-xs text-slate-500 font-mono mt-1">
                <span v-if="d.lastSeenAt">最后活跃: {{ formatTime(d.lastSeenAt) }}</span>
                <span v-else>从未活跃</span>
                <span class="ml-2">添加于: {{ formatTime(d.createdAt) }}</span>
              </div>
            </div>
            <button
              @click="deleteTrusted(d)"
              class="text-xs px-2.5 py-1 rounded-md bg-rose-500/10 text-rose-400 hover:bg-rose-500/20 transition-colors"
            >
              撤销信任
            </button>
          </div>
        </div>
      </section>

      <p v-if="loadError" class="text-sm text-rose-500 bg-rose-500/10 border border-rose-500/20 px-3 py-2 rounded-lg font-mono text-center">
        {{ loadError }}
      </p>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ArrowLeft, Plus, AlertTriangle, Copy, Trash2 } from '@lucide/vue';
import { getDevices, registerDevice, deleteDevice } from '../api/devices';
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
    agentDevices.value = await getDevices();
  } catch (err: any) {
    loadError.value = err.message || '加载 PC Agent 设备失败';
  }
}

async function refreshTrustedDevices() {
  try {
    trustedDevices.value = await getTrustedDevices();
  } catch (err: any) {
    loadError.value = err.message || '加载信任设备失败';
  }
}

async function createDevice() {
  if (!newDeviceName.value.trim() || creating.value) return;
  creating.value = true;
  try {
    const res = await registerDevice(newDeviceName.value.trim());
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
    await navigator.clipboard.writeText(newSecret.value);
    copied.value = true;
    setTimeout(() => { copied.value = false; }, 2000);
  } catch {
    loadError.value = '复制失败，请手动选择文本复制';
  }
}

function dismissSecret() {
  newSecret.value = '';
}

async function deleteAgentDevice(d: AgentDevice) {
  if (!confirm(`确定要删除设备 "${d.deviceName}" 吗？\n该设备的 PC Agent 将无法再连接中转服务器。`)) return;
  try {
    await deleteDevice(d.deviceId);
    await refreshAgentDevices();
  } catch (err: any) {
    loadError.value = err.message || '删除设备失败';
  }
}

async function deleteTrusted(d: { id: number; deviceName: string | null }) {
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

<style scoped>
</style>
