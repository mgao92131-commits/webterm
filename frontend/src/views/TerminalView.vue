<template>
  <TerminalPane :session-id="sessionId" :inline="false">
    <template #leading>
      <router-link
        to="/"
        class="flex items-center gap-1.5 px-2 py-1 text-[12px] rounded-sm text-fg-muted hover:text-fg hover:bg-bg-tertiary transition-colors flex-shrink-0"
      >
        <ArrowLeft class="w-3.5 h-3.5" />
        <span>返回</span>
      </router-link>
    </template>
  </TerminalPane>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, computed } from 'vue';
import { useRoute } from 'vue-router';
import { ArrowLeft } from '@lucide/vue';
import TerminalPane from '../components/TerminalPane.vue';

const route = useRoute();

const sessionId = computed(() => decodeURIComponent(route.params.id as string));

onMounted(() => {
  document.title = "Terminal - WebTerm";
  document.body.classList.add("terminal-mode");
});

onUnmounted(() => {
  document.body.classList.remove("terminal-mode");
  document.body.style.overflow = '';
});
</script>
