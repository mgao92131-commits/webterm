<template>
  <section class="flex-1 flex flex-col min-h-0 min-w-0">
    <!-- Section header -->
    <div class="flex items-center justify-between mb-3 px-1 flex-shrink-0">
      <div class="flex items-center gap-3">
        <span class="text-[11px] font-medium text-fg-subtle font-mono tracking-wider">终端</span>
        <span v-if="selectedDeviceId" class="text-[10px] text-fg-disabled font-mono">{{ sessions.length }} 个</span>
      </div>
      <button
        @click="emit('create')"
        :disabled="!selectedDeviceId"
        class="flex items-center gap-1.5 px-3 py-1.5 text-[12px] font-medium rounded-sm bg-accent hover:bg-accent-hover text-black transition-colors disabled:opacity-40 disabled:pointer-events-none active:scale-[0.99]"
      >
        <Plus class="w-3.5 h-3.5" />
        <span>新建终端</span>
      </button>
    </div>

    <!-- Empty: no device selected -->
    <div v-if="!selectedDeviceId" class="flex-1 flex flex-col items-center justify-center gap-3 text-fg-subtle">
      <Monitor class="w-8 h-8 opacity-30" />
      <span class="text-[13px]">选择一台设备以查看终端</span>
      <slot name="select-device-trigger" />
    </div>

    <!-- Empty: no sessions -->
    <div v-else-if="!sessions.length" class="flex-1 flex flex-col items-center justify-center gap-2 text-fg-subtle">
      <Terminal class="w-8 h-8 opacity-30" />
      <span class="text-[13px]">暂无终端，点击「新建终端」开始</span>
    </div>

    <!-- Session cards -->
    <div v-else class="flex-1 overflow-y-auto flex flex-col gap-2 content-start">
      <div
        v-for="(s, idx) in sessions"
        :key="s.id"
        role="link"
        tabindex="0"
        :class="['session-link stagger-item group flex flex-col gap-1.5 p-3 rounded-md border transition-colors cursor-pointer',
          s.id === selectedSessionId
            ? 'bg-accent-muted border-accent ring-1 ring-accent'
            : cardBorderClass(s)]"
        :style="{ animationDelay: idx * 40 + 'ms' }"
        @click="emit('open', s.id)"
        @keydown.enter.prevent="emit('open', s.id)"
        @keydown.space.prevent="emit('open', s.id)"
      >
        <!-- Header row: title + agent status/notification -->
        <div class="flex items-center justify-between gap-2 min-w-0">
          <h3 class="flex-1 text-[14px] font-medium text-fg truncate">
            {{ s.displayTitle || s.name || s.termTitle || 'Terminal' }}
          </h3>
          <div class="flex items-center gap-1.5 flex-shrink-0">
            <span
              v-if="s.agentState && !s.notification"
              :class="['px-1.5 py-0.5 rounded-sm text-[10px] font-medium whitespace-nowrap', agentStateClass(s.agentState)]"
            >
              {{ s.agentState }}
            </span>
            <span
              v-if="s.notification"
              :class="['flex items-center gap-1 px-1.5 py-0.5 rounded-sm text-[10px] font-medium whitespace-nowrap', notificationClass(s.notification.level)]"
            >
              <AlertCircle class="w-3 h-3" />
              <span class="truncate max-w-[80px]">{{ s.notification.title }}</span>
            </span>
            <button
              type="button"
              class="w-5 h-5 flex items-center justify-center rounded-sm text-fg-disabled hover:text-status-danger hover:bg-status-danger/10 transition-colors opacity-0 group-hover:opacity-100"
              @click.stop="emit('close', s.id)"
              @keydown.enter.stop
              @keydown.space.stop
              title="关闭会话"
            >
              <X class="w-3.5 h-3.5" />
            </button>
          </div>
        </div>

        <!-- Recent command line -->
        <div class="rounded-sm bg-app-bg/80 border border-border/50 p-2 font-mono text-[11px] text-fg-muted min-h-[28px] flex items-center">
          <span v-if="s.recentInputHidden" class="text-fg-disabled italic">敏感输入已隐藏</span>
          <pre v-else-if="recentCommandText(s)" class="w-full truncate leading-relaxed">{{ recentCommandText(s) }}</pre>
          <span v-else-if="s.lastCommand" class="w-full truncate leading-relaxed">{{ s.lastCommand }}</span>
          <span v-else-if="s.notification?.body" class="truncate text-fg-subtle">{{ s.notification.body }}</span>
          <span v-else class="text-fg-disabled italic">等待输入...</span>
        </div>

      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { Plus, Monitor, Terminal, Folder, X, AlertCircle } from '@lucide/vue';
import type { Session } from '../store';

defineProps<{
  sessions: Session[];
  selectedDeviceId: string | null;
  selectedSessionId?: string | null;
}>();

const emit = defineEmits<{
  create: [];
  open: [sessionId: string];
  close: [sessionId: string];
}>();

function recentCommandText(session: Session): string {
  const lines = Array.isArray(session.recentInputLines)
    ? session.recentInputLines.filter(Boolean)
    : [];
  return lines.length > 0 ? lines[lines.length - 1] : '';
}

function cardBorderClass(session: Session): string {
  const level = session.notification?.level;
  if (session.agentState === 'approval_required' || level === 'error') {
    return 'bg-app-panel border-status-warning/60 hover:border-status-warning';
  }
  if (level === 'warning') {
    return 'bg-app-panel border-status-warning/40 hover:border-status-warning/60';
  }
  return 'bg-app-panel border-border hover:border-border-hover';
}

function agentStateClass(state: string): string {
  switch (state) {
    case 'approval_required':
    case 'failed':
      return 'bg-status-danger/10 text-status-danger border border-status-danger/20';
    case 'done':
      return 'bg-status-success/10 text-status-success border border-status-success/20';
    case 'running':
      return 'bg-accent/10 text-accent border border-accent/20';
    default:
      return 'bg-bg-tertiary text-fg-subtle border border-border';
  }
}

function notificationClass(level?: string): string {
  switch (level) {
    case 'error':
      return 'bg-status-danger/10 text-status-danger border border-status-danger/20';
    case 'warning':
      return 'bg-status-warning/10 text-status-warning border border-status-warning/20';
    case 'success':
      return 'bg-status-success/10 text-status-success border border-status-success/20';
    default:
      return 'bg-accent/10 text-accent border border-accent/20';
  }
}
</script>
