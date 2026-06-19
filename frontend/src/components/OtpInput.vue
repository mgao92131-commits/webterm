<template>
  <div class="otp-section flex flex-col gap-4">
    <div class="text-center">
      <p class="text-sm text-slate-400">
        验证码已发送至
      </p>
      <p class="text-sm text-slate-200 font-mono mt-1 break-all">{{ email }}</p>
      <p class="text-xs text-slate-500 mt-2">请输入 6 位数字验证码，10 分钟内有效</p>
    </div>

    <input
      ref="codeInputRef"
      v-model="code"
      name="otp-code"
      autocomplete="one-time-code"
      inputmode="numeric"
      pattern="[0-9]*"
      maxlength="6"
      placeholder="······"
      :disabled="verifying"
      autofocus
      class="px-4 py-3 rounded-lg bg-slate-950/50 border border-slate-800 text-slate-100 placeholder:text-slate-700 focus:outline-none focus:ring-2 focus:ring-indigo-500/40 focus:border-indigo-500 transition-all font-mono text-center text-2xl tracking-[0.5em]"
      @keydown.enter="handleSubmit"
    />

    <button
      type="button"
      :disabled="verifying || code.length !== 6"
      @click="handleSubmit"
      class="w-full py-3 px-4 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white font-semibold transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-indigo-500/40 active:scale-[0.98] disabled:opacity-50 disabled:pointer-events-none hover:shadow-lg hover:shadow-indigo-500/20"
    >
      {{ verifying ? '验证中...' : '验证' }}
    </button>

    <div class="flex items-center justify-between text-xs">
      <button
        type="button"
        :disabled="resendCooldown > 0 || resending"
        @click="handleResend"
        class="text-indigo-400 hover:text-indigo-300 disabled:text-slate-600 disabled:cursor-not-allowed transition-colors"
      >
        {{ resending ? '发送中...' : resendCooldown > 0 ? `重新发送 (${resendCooldown}s)` : '重新发送验证码' }}
      </button>
    </div>

    <p
      v-if="errorMessage"
      class="error text-sm text-rose-500 bg-rose-500/10 border border-rose-500/20 px-3 py-2 rounded-lg font-mono text-center"
    >
      {{ errorMessage }}
    </p>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue';
import { verifyEmail, verifyOtp, resendOtp } from '../api/auth';

const props = defineProps<{
  email: string;
  purpose: 'register' | 'new_device';
  targetDeviceId?: string;
}>();

const emit = defineEmits<{
  (e: 'verified', payload: { email: string; role: 'admin' | 'user' }): void;
  (e: 'resend-required'): void;
}>();

const code = ref('');
const errorMessage = ref('');
const verifying = ref(false);
const resending = ref(false);
const resendCooldown = ref(60);
const codeInputRef = ref<HTMLInputElement | null>(null);
let cooldownTimer: any = null;

function startCooldown() {
  resendCooldown.value = 60;
  if (cooldownTimer) clearInterval(cooldownTimer);
  cooldownTimer = setInterval(() => {
    resendCooldown.value -= 1;
    if (resendCooldown.value <= 0) {
      clearInterval(cooldownTimer);
      cooldownTimer = null;
    }
  }, 1000);
}

async function handleSubmit() {
  if (code.value.length !== 6 || verifying.value) return;
  verifying.value = true;
  errorMessage.value = '';
  try {
    if (props.purpose === 'register') {
      const res = await verifyEmail(props.email, code.value);
      emit('verified', { email: res.email || props.email, role: res.role || 'user' });
    } else {
      const res = await verifyOtp(props.email, code.value, props.targetDeviceId || '');
      emit('verified', { email: res.email || props.email, role: res.role || 'user' });
    }
  } catch (err: any) {
    errorMessage.value = err.message || '验证失败';
    code.value = '';
    nextTickFocus();
  } finally {
    verifying.value = false;
  }
}

async function handleResend() {
  if (resendCooldown.value > 0 || resending.value) return;
  resending.value = true;
  errorMessage.value = '';
  try {
    await resendOtp(props.email);
    startCooldown();
  } catch (err: any) {
    errorMessage.value = err.message || '发送失败，请稍后再试';
  } finally {
    resending.value = false;
  }
}

function nextTickFocus() {
  requestAnimationFrame(() => {
    codeInputRef.value?.focus();
  });
}

watch(() => props.email, () => {
  code.value = '';
  errorMessage.value = '';
  startCooldown();
});

onMounted(() => {
  startCooldown();
  nextTickFocus();
});

onUnmounted(() => {
  if (cooldownTimer) clearInterval(cooldownTimer);
});
</script>
