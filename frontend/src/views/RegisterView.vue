<template>
  <section class="min-h-screen w-full flex items-center justify-center p-6 bg-app-bg">
    <div class="w-full max-w-[360px] flex flex-col gap-8">
      <!-- Brand -->
      <div class="text-center flex flex-col gap-2">
        <h1 class="text-2xl font-semibold tracking-tight text-fg">WebTerm</h1>
        <p class="text-[13px] text-fg-subtle font-mono">创建新账户</p>
      </div>

      <!-- Credentials form -->
      <form v-if="mode === 'credentials'" @submit.prevent="handleRegister" class="flex flex-col gap-6">
        <div class="flex flex-col gap-5">
          <label class="flex flex-col gap-1.5 cursor-pointer">
            <span class="text-[13px] text-fg-muted font-medium">邮箱</span>
            <input
              v-model="form.email"
              name="email"
              type="email"
              autocomplete="email"
              placeholder="you@example.com"
              class="h-10 px-3 rounded-sm bg-app-bg border border-border text-fg placeholder:text-fg-disabled focus:outline-none focus:border-accent focus:ring-1 focus:ring-accent transition-colors font-mono text-[13px]"
              required
            />
          </label>

          <label class="flex flex-col gap-1.5 cursor-pointer">
            <span class="text-[13px] text-fg-muted font-medium">密码</span>
            <input
              v-model="form.password"
              name="new-password"
              type="password"
              autocomplete="new-password"
              placeholder="至少 6 位"
              class="h-10 px-3 rounded-sm bg-app-bg border border-border text-fg placeholder:text-fg-disabled focus:outline-none focus:border-accent focus:ring-1 focus:ring-accent transition-colors font-mono text-[13px]"
              autofocus
              required
            />
          </label>

          <label class="flex flex-col gap-1.5 cursor-pointer">
            <span class="text-[13px] text-fg-muted font-medium">确认密码</span>
            <input
              v-model="form.confirmPassword"
              name="confirm-password"
              type="password"
              autocomplete="new-password"
              placeholder="再次输入密码"
              class="h-10 px-3 rounded-sm bg-app-bg border border-border text-fg placeholder:text-fg-disabled focus:outline-none focus:border-accent focus:ring-1 focus:ring-accent transition-colors font-mono text-[13px]"
              required
            />
          </label>
        </div>

        <button
          type="submit"
          :disabled="loading"
          class="h-10 w-full rounded-sm bg-accent hover:bg-accent-hover text-black font-medium text-[14px] transition-colors focus:outline-none focus:ring-2 focus:ring-accent/40 active:scale-[0.99] disabled:opacity-40 disabled:pointer-events-none"
        >
          {{ loading ? '发送验证码中...' : '注册' }}
        </button>

        <div class="text-center text-[13px] text-fg-subtle">
          已有账号？
          <router-link to="/login" class="text-accent hover:text-accent-hover transition-colors">登录</router-link>
        </div>

        <p
          v-if="errorMessage"
          class="text-[13px] px-3 py-2 rounded-sm font-mono text-center"
          :class="successMessage ? 'text-status-success bg-status-success/10 border border-status-success/20' : 'text-status-danger bg-status-danger/10 border border-status-danger/20'"
        >
          {{ errorMessage }}
        </p>
      </form>

      <!-- OTP mode -->
      <template v-else>
        <OtpInput
          :email="form.email"
          purpose="register"
          @verified="handleVerified"
        />
        <button
          type="button"
          @click="backToCredentials"
          class="text-[13px] text-fg-subtle hover:text-fg-muted transition-colors text-center"
        >
          ← 返回修改注册信息
        </button>
      </template>
    </div>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { api, store } from '../store';
import { register as apiRegister } from '../api/auth';
import OtpInput from '../components/OtpInput.vue';

const router = useRouter();
const loading = ref(false);
const errorMessage = ref('');
const successMessage = ref(false);

type Mode = 'credentials' | 'otp';
const mode = ref<Mode>('credentials');

const form = reactive({
  email: '',
  password: '',
  confirmPassword: '',
});

onMounted(() => {
  document.title = "WebTerm - 注册";
  document.body.classList.remove("terminal-mode");
});

async function handleRegister() {
  errorMessage.value = '';
  successMessage.value = false;
  if (form.password !== form.confirmPassword) {
    errorMessage.value = '两次输入的密码不一致';
    return;
  }
  if (form.password.length < 6) {
    errorMessage.value = '密码长度至少 6 位';
    return;
  }

  loading.value = true;
  try {
    const res = await apiRegister(form.email, form.password);
    if (res.emailVerificationRequired) {
      mode.value = 'otp';
      return;
    }
    successMessage.value = true;
    errorMessage.value = '注册成功，请登录';
    setTimeout(() => {
      router.push('/login');
    }, 800);
  } catch (err: any) {
    errorMessage.value = err.message || '注册失败，请稍后再试';
  } finally {
    loading.value = false;
  }
}

async function handleVerified(payload: { email: string; role: 'admin' | 'user' }) {
  store.user = {
    id: '',
    username: payload.email,
    role: payload.role,
    mode: 'relay',
  };
  store.mode = 'relay';
  try {
    const me = await api('/api/auth/me');
    store.user = {
      id: me.id,
      username: me.username,
      role: me.role,
      mode: me.mode || 'relay',
    };
    store.mode = me.mode || 'relay';
  } catch {
    // ignore
  }
  router.push('/');
}

function backToCredentials() {
  mode.value = 'credentials';
  errorMessage.value = '';
  successMessage.value = false;
}
</script>
