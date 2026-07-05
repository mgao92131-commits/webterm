<template>
  <Teleport to="body">
    <Transition name="fade">
      <div
        v-if="modelValue"
        class="fixed inset-0 z-50 bg-black/60 backdrop-blur-[2px]"
        @click="close"
      ></div>
    </Transition>

    <Transition name="slide-from-left">
      <aside
        v-if="modelValue"
        class="fixed left-0 top-0 bottom-0 z-[60] w-[260px] bg-app-bg border-r border-border flex flex-col shadow-xl"
        role="dialog"
        aria-label="选择设备"
      >
        <div class="flex items-center justify-between h-11 px-4 border-b border-border flex-shrink-0">
          <span class="text-[13px] font-semibold text-fg">选择设备</span>
          <button
            @click="close"
            class="flex items-center justify-center w-7 h-7 rounded-sm text-fg-muted hover:text-fg hover:bg-bg-tertiary transition-colors"
            aria-label="关闭"
          >
            <X class="w-4 h-4" />
          </button>
        </div>

        <div class="flex-1 overflow-y-auto p-3">
          <div class="flex items-center justify-between px-1 mb-2">
            <span class="text-[11px] font-medium text-fg-subtle font-mono tracking-wider">设备</span>
            <span class="text-[10px] font-mono text-fg-disabled">{{ devices.length }} 在线</span>
          </div>

          <div class="flex flex-col gap-0.5">
            <button
              v-for="d in devices"
              :key="d.deviceId"
              @click="select(d.deviceId)"
              :class="['flex items-center gap-2.5 px-3 py-2.5 rounded-sm text-left transition-colors w-full',
                selectedDeviceId === d.deviceId
                  ? 'bg-accent-muted border-l-2 border-accent'
                  : 'hover:bg-bg-tertiary border-l-2 border-transparent']"
            >
              <Monitor class="w-4 h-4 flex-shrink-0" :class="selectedDeviceId === d.deviceId ? 'text-accent' : 'text-fg-subtle'" />
              <span class="text-[13px] text-fg truncate flex-1">{{ d.deviceName }}</span>
              <span class="flex items-center gap-1 flex-shrink-0">
                <span v-if="selectedDeviceId === d.deviceId && p2pActive" class="text-[9px] text-accent font-mono">P2P</span>
                <span class="w-1.5 h-1.5 rounded-full" :class="d.status === 'online' ? 'bg-status-success animate-pulse-dot' : 'bg-fg-disabled'"></span>
              </span>
            </button>

            <div v-if="!devices.length" class="text-center py-8 text-[12px] text-fg-subtle font-mono">
              暂无在线设备
            </div>
          </div>
        </div>
      </aside>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { Monitor, X } from '@lucide/vue';
import type { Device } from '../store';

const props = defineProps<{
  modelValue: boolean;
  devices: Device[];
  selectedDeviceId: string | null;
  p2pActive?: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  select: [deviceId: string];
}>();

function close() {
  emit('update:modelValue', false);
}

function select(deviceId: string) {
  emit('select', deviceId);
  close();
}
</script>

<style scoped>
.slide-from-left-enter-active,
.slide-from-left-leave-active {
  transition: transform 0.25s ease, opacity 0.25s ease;
}

.slide-from-left-enter-from,
.slide-from-left-leave-to {
  transform: translateX(-100%);
  opacity: 0;
}
</style>
