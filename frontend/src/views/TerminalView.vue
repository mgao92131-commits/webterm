<template>
  <section :class="['terminal-page min-h-screen flex flex-col bg-slate-950 select-none overflow-hidden relative', { 'selection-mode': isSelectionMode }]">
    <!-- 顶部状态栏 -->
    <header class="terminal-bar w-full px-4 py-2 border-b border-slate-900 bg-slate-950/60 backdrop-blur-md flex items-center justify-between z-10 gap-3">
      <router-link 
        to="/" 
        class="button font-mono text-xs px-3 py-1.5 rounded-lg border border-slate-800 text-slate-400 hover:text-white hover:border-slate-700 bg-slate-900/30 transition-all flex items-center gap-1"
      >
        <ArrowLeft class="w-3.5 h-3.5" />
        <span>返回</span>
      </router-link>

      <div class="terminal-title flex-1 flex justify-center max-w-[400px]">
        <input 
          id="sessionName" 
          autocomplete="off" 
          maxlength="80" 
          v-model="sessionNameVal"
          @focus="onTitleFocus"
          @blur="onTitleBlur"
          @keydown.enter="onTitleEnter"
          @keydown.escape="onTitleEsc"
          class="w-full text-center px-3 py-1.5 bg-slate-900/30 border border-transparent rounded-lg text-slate-200 hover:border-slate-850/60 focus:border-indigo-500 focus:bg-slate-900/80 focus:outline-none transition-all font-sans text-sm font-semibold truncate"
          :placeholder="sessionPlaceholder" 
          aria-label="会话名称" 
          title="会话名称，留空时显示终端标题" 
        />
      </div>

      <div class="flex items-center gap-2">
        <span v-if="store.mode === 'relay'" :class="['text-[10px] px-2.5 py-1 rounded-full font-semibold border font-mono tracking-normal leading-none mr-1', store.p2pActive ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20' : 'bg-amber-500/10 text-amber-400 border-amber-500/20']">
          {{ store.p2pActive ? '🟢 直连' : '🟡 中继' }}
        </span>
        <button 
          id="copySelection" 
          class="selection-copy font-mono text-xs px-3 py-1.5 rounded-lg border border-indigo-500/50 text-indigo-400 hover:text-indigo-300 hover:bg-indigo-500/10 transition-all"
          type="button" 
          v-show="isSelectionMode"
          @click="copySelection"
        >
          拷贝
        </button>
        <button 
          id="selectMode" 
          class="font-mono text-xs px-3 py-1.5 rounded-lg border border-slate-800 text-slate-400 hover:text-white hover:border-slate-700 bg-slate-900/30 transition-all"
          type="button"
        >
          选择
        </button>
      </div>
    </header>

    <!-- 终端容器 -->
    <div id="terminal-container" class="flex-1 w-full relative overflow-hidden bg-slate-950">
      <div id="terminal" ref="terminalRef" class="w-full h-full"></div>
    </div>

    <!-- 移动端快捷输入栏 -->
    <nav class="quickbar z-20 w-full border-t border-slate-900 bg-slate-950/90 py-2 px-3 select-none">
      <div class="flex items-center gap-2 overflow-x-auto whitespace-nowrap scrollbar-none py-1 w-full max-w-lg mx-auto">
        <button 
          v-for="k in ['Ctrl', 'Esc', 'Tab', 'Enter', 'Ctrl C', 'Ctrl D', 'Ctrl Z', 'Ctrl X', '/', '-', '|', '>', '\\', '$', '&', '←', '↓', '↑', '→']" 
          :key="k"
          type="button" 
          :data-key="k"
          :class="['font-mono text-xs font-semibold py-2.5 px-3 rounded-lg border transition-all active:scale-95 leading-none text-center flex-shrink-0 min-w-[42px]',
                   k === 'Ctrl' && isCtrlActive 
                     ? 'bg-indigo-600 border-indigo-500 text-white active shadow-md shadow-indigo-600/20' 
                     : 'bg-slate-900/60 border-slate-850/60 text-slate-400 hover:text-slate-200 hover:border-slate-800']"
        >
          {{ k }}
        </button>
      </div>
    </nav>
  </section>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue';
import { useRoute } from 'vue-router';
import { ArrowLeft } from '@lucide/vue';
import { store } from '../store';
import { TerminalSessionContext } from '../lib/terminal-session-context';

const route = useRoute();

// 页面绑定 refs
const terminalRef = ref<HTMLElement | null>(null);
const sessionNameVal = ref('');
const sessionPlaceholder = ref('Terminal');
const isSelectionMode = ref(false);
const isCtrlActive = ref(false);

const sessionId = decodeURIComponent(route.params.id as string);
let context: TerminalSessionContext | null = null;

// 标题修改状态变量
let titleEditing = false;
let skipTitleCommit = false;

onMounted(() => {
  document.body.classList.add("terminal-mode");
  
  if (!terminalRef.value) {
    console.error("Terminal container not found");
    return;
  }

  // 1. 初始化协调管理器 TerminalSessionContext 并订阅其状态
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

  // 2. 挂载 Playwright Debug 测试调试钩子
  if (debugEnabled()) {
    (window as any).__webtermDebug = context.getDebugHooks();
  }
});

onUnmounted(() => {
  document.body.classList.remove("terminal-mode");
  if (context) {
    context.dispose();
    context = null;
  }
});

// --- 标题就地编辑逻辑 ---

function onTitleFocus() {
  titleEditing = true;
  if (context) {
    context.titleBeforeEdit = sessionNameVal.value;
  }
  const titleInput = document.getElementById("sessionName") as HTMLInputElement;
  titleInput?.select();
}

function onTitleBlur() {
  commitTitle();
}

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

// 拷贝选区
function copySelection(event?: Event) {
  context?.selectionController?.copySelection(event);
}

function debugEnabled() {
  return new URLSearchParams(window.location.search).has("debug")
    || localStorage.getItem("webtermDebug") === "1";
}
</script>

<style scoped>
/* 可以在此针对单屏渲染做微调 */
</style>
