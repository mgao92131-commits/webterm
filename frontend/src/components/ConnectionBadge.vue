<template>
  <span class="flex items-center gap-1.5 text-[11px] text-fg-subtle flex-shrink-0">
    <span class="w-1.5 h-1.5 rounded-full" :class="{
      'bg-status-success': health === 'connected',
      'bg-status-warning animate-pulse': health === 'connecting',
      'bg-status-warning': health === 'polling',
      'bg-status-danger': health === 'disconnected',
    }"></span>
    <span class="hidden sm:inline">{{ label }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue';

export type ConnectionHealth = 'connected' | 'connecting' | 'polling' | 'disconnected';

const props = defineProps<{
  health: ConnectionHealth;
}>();

const LABELS: Record<ConnectionHealth, string> = {
  connected: '已连接',
  connecting: '连接中',
  polling: '轮询中',
  disconnected: '离线',
};

const label = computed(() => LABELS[props.health]);
</script>
