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
    <div v-else class="flex-1 overflow-y-auto grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 content-start">
      <div
        v-for="(s, idx) in sessions"
        :key="s.id"
        role="link"
        tabindex="0"
        class="session-link stagger-item group flex flex-col gap-2 p-3 rounded-md bg-app-panel border border-border hover:border-border-hover transition-colors cursor-pointer"
        :style="{ animationDelay: idx * 40 + 'ms' }"
        @click="emit('open', s.id)"
        @keydown.enter.prevent="emit('open', s.id)"
        @keydown.space.prevent="emit('open', s.id)"
      >
        <!-- Header row -->
        <div class="flex items-start justify-between gap-2">
          <div class="flex-1 min-w-0">
            <h3 class="text-[14px] font-medium text-fg truncate">{{ s.name || 'Terminal' }}</h3>
          </div>
          <button
            type="button"
            class="flex-shrink-0 w-5 h-5 flex items-center justify-center rounded-sm text-fg-disabled hover:text-status-danger hover:bg-status-danger/10 transition-colors opacity-0 group-hover:opacity-100"
            @click.stop="emit('close', s.id)"
            @keydown.enter.stop
            @keydown.space.stop
            title="关闭会话"
          >
            <X class="w-3.5 h-3.5" />
          </button>
        </div>

        <!-- Recent input preview -->
        <div class="flex-1 rounded-sm bg-app-bg/80 border border-border/50 p-2 font-mono text-[11px] text-fg-muted min-h-[28px] flex items-center">
          <span v-if="s.recentInputHidden" class="text-fg-disabled italic">敏感输入已隐藏</span>
          <pre v-else-if="recentInputText(s)" class="w-full truncate leading-relaxed">{{ recentInputText(s) }}</pre>
          <span v-else class="text-fg-disabled italic">等待输入...</span>
        </div>

        <!-- CWD footer -->
        <div class="flex items-center gap-1.5 text-[10px] text-fg-subtle font-mono truncate">
          <Folder class="w-3 h-3 flex-shrink-0" />
          <span class="truncate">{{ s.cwd || '/' }}</span>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { Plus, Monitor, Terminal, Folder, X } from '@lucide/vue';
import type { Session } from '../store';

defineProps<{
  sessions: Session[];
  selectedDeviceId: string | null;
}>();

const emit = defineEmits<{
  create: [];
  open: [sessionId: string];
  close: [sessionId: string];
}>();

function recentInputText(session: Session): string {
  const lines = Array.isArray(session.recentInputLines)
    ? session.recentInputLines.filter(Boolean).slice(-2)
    : [];
  return lines.join('\n');
}
</script>
