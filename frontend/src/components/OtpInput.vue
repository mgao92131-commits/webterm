<template>
  <div class="flex flex-col gap-5">
    <div class="text-center flex flex-col gap-1">
      <p class="text-[13px] text-fg-muted">验证码已发送至</p>
      <p class="text-[13px] text-fg font-mono break-all">{{ email }}</p>
      <p class="text-[12px] text-fg-subtle mt-1">请输入 6 位数字，10 分钟内有效</p>
    </div>

    <!-- 6 individual digit inputs -->
    <div class="flex gap-2 justify-center">
      <input
        v-for="(_, i) in 6"
        :key="i"
        :ref="(el) => { if (el) digitRefs[i] = el as HTMLInputElement }"
        v-model="digits[i]"
        type="text"
        inputmode="numeric"
        pattern="[0-9]"
        maxlength="1"
        autocomplete="one-time-code"
        :disabled="verifying"
        class="w-11 h-12 rounded-sm bg-app-bg border text-fg placeholder:text-fg-disabled focus:outline-none focus:border-accent focus:ring-1 focus:ring-accent transition-colors font-mono text-xl text-center"
        :class="verifying && digits[i] ? 'border-accent/50' : 'border-border'"
        @input="onDigitInput(i, $event)"
        @keydown="onDigitKeydown(i, $event)"
        @paste="onPaste"
        @focus="onDigitFocus"
      />
    </div>

    <button
      type="button"
      :disabled="verifying || !allDigitsFilled"
      @click="handleSubmit"
      class="h-10 w-full rounded-sm bg-accent hover:bg-accent-hover text-black font-medium text-[14px] transition-colors focus:outline-none focus:ring-2 focus:ring-accent/40 active:scale-[0.99] disabled:opacity-40 disabled:pointer-events-none"
    >
      {{ verifying ? '验证中...' : '验证' }}
    </button>

    <div class="flex items-center justify-center">
      <button
        type="button"
        :disabled="resendCooldown > 0 || resending"
        @click="handleResend"
        class="text-[13px] text-accent hover:text-accent-hover disabled:text-fg-disabled disabled:cursor-not-allowed transition-colors"
      >
        {{ resending ? '发送中...' : resendCooldown > 0 ? `重新发送 (${resendCooldown}s)` : '重新发送验证码' }}
      </button>
    </div>

    <p
      v-if="errorMessage"
      class="text-[13px] text-status-danger bg-status-danger/10 border border-status-danger/20 px-3 py-2 rounded-sm font-mono text-center"
    >
      {{ errorMessage }}
    </p>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue';
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

const digits = ref<string[]>(['', '', '', '', '', '']);
const digitRefs = ref<(HTMLInputElement | null)[]>(Array(6).fill(null));
const errorMessage = ref('');
const verifying = ref(false);
const resending = ref(false);
const resendCooldown = ref(60);

function onDigitFocus(event: FocusEvent) {
  (event.target as HTMLInputElement | null)?.select();
}
let cooldownTimer: any = null;

const allDigitsFilled = computed(() => digits.value.every(d => d.length === 1));

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

function focusNext(fromIndex: number) {
  const next = digitRefs.value[fromIndex + 1];
  next?.focus();
}

function focusPrev(fromIndex: number) {
  const prev = digitRefs.value[fromIndex - 1];
  prev?.focus();
}

function onDigitInput(index: number, event: Event) {
  const input = event.target as HTMLInputElement;
  const val = input.value.replace(/\D/g, '');
  if (val.length > 0) {
    digits.value[index] = val[val.length - 1];
    if (index < 5) focusNext(index);
  } else {
    digits.value[index] = '';
  }
}

function onDigitKeydown(index: number, event: KeyboardEvent) {
  if (event.key === 'Enter') {
    if (allDigitsFilled.value) handleSubmit();
  } else if (event.key === 'Backspace' && !digits.value[index] && index > 0) {
    focusPrev(index);
  } else if (event.key === 'ArrowLeft' && index > 0) {
    focusPrev(index);
  } else if (event.key === 'ArrowRight' && index < 5) {
    focusNext(index);
  }
}

function onPaste(event: ClipboardEvent) {
  event.preventDefault();
  const pasted = event.clipboardData?.getData('text')?.replace(/\D/g, '').slice(0, 6) || '';
  for (let i = 0; i < 6; i++) {
    digits.value[i] = pasted[i] || '';
  }
  // Focus the next empty or last
  const nextEmpty = digits.value.findIndex(d => d === '');
  const target = nextEmpty >= 0 ? nextEmpty : 5;
  digitRefs.value[target]?.focus();
}

async function handleSubmit() {
  if (!allDigitsFilled.value || verifying.value) return;
  verifying.value = true;
  errorMessage.value = '';
  const code = digits.value.join('');
  try {
    if (props.purpose === 'register') {
      const res = await verifyEmail(props.email, code);
      emit('verified', { email: res.email || props.email, role: res.role || 'user' });
    } else {
      const res = await verifyOtp(props.email, code, props.targetDeviceId || '');
      emit('verified', { email: res.email || props.email, role: res.role || 'user' });
    }
  } catch (err: any) {
    errorMessage.value = err.message || '验证失败';
    digits.value = ['', '', '', '', '', ''];
    digitRefs.value[0]?.focus();
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

watch(() => props.email, () => {
  digits.value = ['', '', '', '', '', ''];
  errorMessage.value = '';
  startCooldown();
});

onMounted(() => {
  startCooldown();
  digitRefs.value[0]?.focus();
});

onUnmounted(() => {
  if (cooldownTimer) clearInterval(cooldownTimer);
});
</script>
