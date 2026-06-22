<template>
  <section :class="['terminal-page flex flex-col bg-app-bg select-none overflow-x-hidden relative', { 'selection-mode': isSelectionMode }]">
    <!-- Header bar -->
    <header class="terminal-bar w-full h-10 px-3 border-b border-border bg-app-bg flex items-center justify-between z-10 gap-2 flex-shrink-0">
      <router-link
        to="/"
        class="flex items-center gap-1.5 px-2 py-1 text-[12px] rounded-sm text-fg-muted hover:text-fg hover:bg-bg-tertiary transition-colors flex-shrink-0"
      >
        <ArrowLeft class="w-3.5 h-3.5" />
        <span class="hidden sm:inline">返回</span>
      </router-link>

      <!-- Title input -->
      <div class="flex-1 flex justify-center min-w-0 px-2">
        <input
          id="sessionName"
          autocomplete="off"
          maxlength="80"
          v-model="sessionNameVal"
          @focus="onTitleFocus"
          @blur="onTitleBlur"
          @keydown.enter="onTitleEnter"
          @keydown.escape="onTitleEsc"
          class="w-full max-w-[360px] text-center h-7 px-2 bg-transparent border border-transparent rounded-sm text-fg hover:border-border focus:border-accent focus:bg-app-bg focus:outline-none transition-colors font-mono text-[13px] truncate"
          :placeholder="sessionPlaceholder"
          aria-label="会话名称"
          title="会话名称，留空时显示终端标题"
        />
      </div>

      <div class="flex items-center gap-1 flex-shrink-0">
        <!-- P2P/Relay badge -->
        <span v-if="store.mode === 'relay'" :class="['text-[10px] px-1.5 py-0.5 rounded-sm font-medium font-mono hidden sm:inline-flex',
          store.p2pActive ? 'bg-accent-muted text-accent border border-accent/30' : 'bg-bg-tertiary text-fg-subtle border border-border']">
          {{ store.p2pActive ? 'P2P' : 'RELAY' }}
        </span>

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
      </div>
    </header>

    <!-- Terminal container -->
    <div id="terminal-container" class="flex-1 w-full relative overflow-hidden">
      <div id="terminal" ref="terminalRef" class="w-full h-full"></div>
    </div>

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
import { ref, onMounted, onUnmounted, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ArrowLeft } from '@lucide/vue';
import { store } from '../store';
import { TerminalSessionContext } from '../lib/terminal-session-context';

const route = useRoute();

const quickKeys = ['Ctrl', 'Esc', 'Tab', 'Enter', 'Ctrl C', 'Ctrl D', 'Ctrl Z', 'Ctrl X', '/', '-', '|', '>', '\\', '$', '&', '←', '↓', '↑', '→'];

const terminalRef = ref<HTMLElement | null>(null);
const sessionNameVal = ref('');
const sessionPlaceholder = ref('Terminal');
const isSelectionMode = ref(false);
const isCtrlActive = ref(false);

let context: TerminalSessionContext | null = null;

let titleEditing = false;
let skipTitleCommit = false;

function getSessionId(): string {
  return decodeURIComponent(route.params.id as string);
}

function createContext(): void {
  const sessionId = getSessionId();

  if (!terminalRef.value) {
    console.error("Terminal container not found");
    return;
  }

  context = new TerminalSessionContext({
    element: terminalRef.value,
    sessionId,
    theme: store.theme,
    p2pActive: store.p2pActive,
    onStateChange: (change) => {
      if (change.isSelectionMode !== undefined) isSelectionMode.value = change.isSelectionMode;
      if (change.isCtrlActive !== undefined) isCtrlActive.value = change.isCtrlActive;
      if (change.sessionPlaceholder !== undefined) sessionPlaceholder.value = change.sessionPlaceholder;
      if (change.sessionName !== undefined && !titleEditing) {
        sessionNameVal.value = change.sessionName;
      }
    },
    onExit: () => {
      // 终端连接断开退出
    }
  });

  if (debugEnabled()) {
    (window as any).__webtermDebug = context.getDebugHooks();
  }
}

onMounted(() => {
  document.body.classList.add("terminal-mode");
  createContext();
});

// 监听路由参数变化：同一组件在 /terminal/A 和 /terminal/B 之间导航时，
// Vue Router 复用组件实例，onMounted/onUnmounted 不会触发。
// 必须手动 watch route.params.id 来替换 TerminalSessionContext。
watch(() => route.params.id, (newId, oldId) => {
  if (!oldId || newId === oldId) return;

  if (context) {
    context.dispose();
    context = null;
  }

  // 重置编辑状态，避免旧会话标题残留
  titleEditing = false;
  skipTitleCommit = false;
  sessionNameVal.value = '';
  sessionPlaceholder.value = 'Terminal';

  createContext();
});

onUnmounted(() => {
  document.body.classList.remove("terminal-mode");
  if (context) {
    context.dispose();
    context = null;
  }
});

function onTitleFocus() {
  titleEditing = true;
  if (context) {
    context.titleBeforeEdit = sessionNameVal.value;
  }
  const titleInput = document.getElementById("sessionName") as HTMLInputElement;
  titleInput?.select();
}

function onTitleBlur() { commitTitle(); }

function onTitleEnter() {
  const titleInput = document.getElementById("sessionName") as HTMLInputElement;
  titleInput?.blur();
}

function onTitleEsc() {
  skipTitleCommit = true;
  titleEditing = false;
  if (context) {
    sessionNameVal.value = context.titleBeforeEdit;
  }
  const titleInput = document.getElementById("sessionName") as HTMLInputElement;
  titleInput?.blur();
}

async function commitTitle() {
  if (skipTitleCommit) {
    skipTitleCommit = false;
    titleEditing = false;
    return;
  }
  titleEditing = false;
  if (context) {
    await context.commitTerminalTitle(sessionNameVal.value.trim());
  }
}

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
</script>
