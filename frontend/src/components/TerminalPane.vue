<template>
  <section :class="['terminal-page flex flex-col bg-app-bg select-none overflow-x-hidden relative', { 'selection-mode': isSelectionMode, 'terminal-page-inline': inline }]">
    <!-- Header bar -->
    <header class="terminal-bar w-full h-10 px-3 border-b border-border bg-app-bg flex items-center justify-between z-10 gap-2 flex-shrink-0">
      <slot name="leading" />

      <!-- 终端通过 OSC 上报的只读标题 -->
      <div class="flex-1 flex justify-center min-w-0 px-2">
        <span class="w-full max-w-[360px] text-center text-fg font-mono text-[13px] truncate" :title="displayTitle">
          {{ displayTitle }}
        </span>
      </div>

      <div class="flex items-center gap-1 flex-shrink-0">
        <!-- Copy selection — bound by terminal-selection.ts via #copySelection -->
        <button
          id="copySelection"
          v-show="isSelectionMode"
          @click="copySelection"
          class="text-[11px] px-2 py-1 rounded-sm bg-accent-muted text-accent border border-accent/30 hover:bg-accent/20 transition-colors font-mono"
        >拷贝</button>

        <!-- Font size -->
        <div class="flex items-center rounded-sm border border-border overflow-hidden flex-shrink-0">
          <button
            @click="adjustFontSize(-1)"
            class="px-2 h-7 text-[11px] text-fg-muted hover:text-fg hover:bg-bg-tertiary transition-colors font-mono"
            title="减小字号"
          >A-</button>
          <div class="w-px h-3 bg-border"></div>
          <button
            @click="adjustFontSize(1)"
            class="px-2 h-7 text-[11px] text-fg-muted hover:text-fg hover:bg-bg-tertiary transition-colors font-mono"
            title="增大字号"
          >A+</button>
        </div>

        <!-- Selection mode — bound by terminal-selection.ts via #selectMode -->
        <button
          id="selectMode"
          class="text-[11px] px-2 py-1 rounded-sm text-fg-muted hover:text-fg hover:bg-bg-tertiary transition-colors font-mono"
        >选择</button>

        <slot name="actions" />
      </div>
    </header>

    <!-- Terminal container -->
    <div id="terminal-container" class="flex-1 w-full relative overflow-hidden">
      <div id="terminal" ref="terminalRef" class="w-full h-full"></div>
    </div>

    <!-- Hook notification toast -->
    <Transition
      enter-active-class="transition ease-out duration-200"
      enter-from-class="opacity-0 -translate-y-2"
      enter-to-class="opacity-100 translate-y-0"
      leave-active-class="transition ease-in duration-150"
      leave-from-class="opacity-100 translate-y-0"
      leave-to-class="opacity-0 -translate-y-2"
    >
      <div
        v-if="hookToast"
        class="absolute top-10 left-0 right-0 z-30 px-3 py-2 border-b flex items-center justify-between gap-3"
        :class="toastClass"
      >
        <div class="flex items-center gap-2 min-w-0">
          <span class="font-medium text-[13px] truncate">{{ hookToast.text }}</span>
        </div>
        <div class="flex items-center gap-2 flex-shrink-0">
          <button
            v-if="hookToast.sticky"
            @click="focusTerminal"
            class="text-[12px] px-2 py-1 rounded-sm bg-black/20 hover:bg-black/30 transition-colors"
          >
            查看
          </button>
          <button
            @click="dismissToast"
            class="w-6 h-6 flex items-center justify-center rounded-sm hover:bg-black/20 transition-colors"
          >
            <X class="w-3.5 h-3.5" />
          </button>
        </div>
      </div>
    </Transition>

    <!-- Quickbar (mobile) -->
    <nav class="quickbar z-20 w-full border-t border-border bg-app-bg py-2 px-2 select-none flex-shrink-0">
      <div class="quickbar-scroll-hint flex items-center gap-1.5 overflow-x-auto whitespace-nowrap scrollbar-none py-1 w-full max-w-lg mx-auto">
        <button
          v-for="k in quickKeys"
          :key="k"
          type="button"
          :data-key="k"
          :class="['font-mono text-[11px] font-medium py-2 px-2.5 rounded-sm border transition-all active:scale-95 leading-none text-center flex-shrink-0',
            k === 'Ctrl' && isCtrlActive
              ? 'bg-accent border-accent text-black'
              : 'bg-app-bg border-border text-fg-muted hover:text-fg hover:border-border-hover']"
        >
          {{ k }}
        </button>
      </div>
    </nav>
  </section>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
import { X } from '@lucide/vue';
import { store } from '../store';
import { TerminalSessionContext } from '../lib/terminal-session-context';

interface HookToast {
  text: string;
  level?: 'idle' | 'running' | 'error';
  sticky: boolean;
}

const props = defineProps<{
  sessionId: string;
  inline?: boolean;
}>();

const quickKeys = ['Ctrl', 'Esc', 'Tab', 'Enter', 'Ctrl C', 'Ctrl D', 'Ctrl Z', 'Ctrl X', '/', '-', '|', '>', '\\', '$', '&', '←', '↓', '↑', '→'];

const terminalRef = ref<HTMLElement | null>(null);
const displayTitle = ref('Terminal');
const isSelectionMode = ref(false);
const isCtrlActive = ref(false);
const hookToast = ref<HookToast | null>(null);
let hookToastTimer: ReturnType<typeof setTimeout> | null = null;

let context: TerminalSessionContext | null = null;

function createContext(sessionId: string): void {
  if (!terminalRef.value) {
    console.error("Terminal container not found");
    return;
  }

  context = new TerminalSessionContext({
    element: terminalRef.value,
    sessionId,
    theme: store.theme,
    mode: store.mode,
    deviceId: store.selectedDeviceId,
    setDocumentTitle: !props.inline,
    onStateChange: (change) => {
      if (change.isSelectionMode !== undefined) isSelectionMode.value = change.isSelectionMode;
      if (change.isCtrlActive !== undefined) isCtrlActive.value = change.isCtrlActive;
      if (change.displayTitle !== undefined) displayTitle.value = change.displayTitle;
    },
    onExit: () => {
      // 终端连接断开退出
    }
  });

  if (debugEnabled()) {
    (window as any).__webtermDebug = context.getDebugHooks();
  }
}

function disposeContext(): void {
  if (context) {
    context.dispose();
    context = null;
  }
}

onMounted(() => {
  createContext(props.sessionId);
});

watch(() => props.sessionId, (newId, oldId) => {
  if (!oldId || newId === oldId) return;

  disposeContext();

  displayTitle.value = 'Terminal';

  createContext(newId);
});

onUnmounted(() => {
  disposeContext();
});

function copySelection(event?: Event) {
  context?.selectionController?.copySelection(event);
}

function adjustFontSize(delta: number) {
  context?.changeFontSize(delta);
}

function debugEnabled() {
  return new URLSearchParams(window.location.search).has("debug")
    || localStorage.getItem("webtermDebug") === "1";
}

function onHookEvent(event: Event) {
  const detail = (event as CustomEvent).detail;
  if (!detail || detail.sessionId !== props.sessionId) return;
  const text = detail.source
    ? `${detail.source}: ${detail.message}`
    : String(detail.message || '');
  showHookToast({
    text,
    level: detail.level,
    sticky: detail.level === 'error',
  });
}

function showHookToast(toast: HookToast) {
  hookToast.value = toast;
  if (hookToastTimer) {
    clearTimeout(hookToastTimer);
    hookToastTimer = null;
  }
  if (!toast.sticky) {
    hookToastTimer = setTimeout(() => {
      hookToast.value = null;
    }, 3000);
  }
}

function dismissToast() {
  hookToast.value = null;
  if (hookToastTimer) {
    clearTimeout(hookToastTimer);
    hookToastTimer = null;
  }
}

function focusTerminal() {
  context?.terminalView?.focus();
  dismissToast();
}

const toastClass = computed(() => {
  const level = hookToast.value?.level;
  const base = 'bg-app-bg/95 backdrop-blur-sm ';
  switch (level) {
    case 'error':
      return base + 'border-status-danger/50 text-status-danger';
    case 'running':
      return base + 'border-status-success/50 text-status-success';
    case 'idle':
    default:
      return base + 'border-border/50 text-fg-subtle';
  }
});

onMounted(() => {
  window.addEventListener('webterm:hook', onHookEvent);
});

onUnmounted(() => {
  window.removeEventListener('webterm:hook', onHookEvent);
  if (hookToastTimer) {
    clearTimeout(hookToastTimer);
  }
});
</script>
