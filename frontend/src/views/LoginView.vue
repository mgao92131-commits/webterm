<template>
  <section class="min-h-screen w-full flex items-center justify-center p-6 bg-app-bg">
    <div class="w-full max-w-[360px] flex flex-col gap-8">
      <!-- Brand -->
      <div class="text-center flex flex-col gap-2">
        <h1 class="text-2xl font-semibold tracking-tight text-fg">WebTerm</h1>
        <p class="text-[13px] text-fg-subtle font-mono">登录到远程终端</p>
      </div>

      <!-- Credentials form -->
      <form v-if="mode === 'credentials'" @submit.prevent="handleLogin" class="flex flex-col gap-6" novalidate>
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
            <p v-if="errorField === 'email'" class="text-[12px] text-status-danger">{{ errorMessage }}</p>
          </label>

          <label class="flex flex-col gap-1.5 cursor-pointer">
            <span class="text-[13px] text-fg-muted font-medium">密码</span>
            <input
              v-model="form.password"
              name="password"
              type="password"
              autocomplete="current-password"
              placeholder="••••••••"
              class="h-10 px-3 rounded-sm bg-app-bg border border-border text-fg placeholder:text-fg-disabled focus:outline-none focus:border-accent focus:ring-1 focus:ring-accent transition-colors font-mono text-[13px]"
              autofocus
              required
            />
            <p v-if="errorField === 'password'" class="text-[12px] text-status-danger">{{ errorMessage }}</p>
          </label>
        </div>

        <button
          type="submit"
          :disabled="loading"
          class="h-10 w-full rounded-sm bg-accent hover:bg-accent-hover text-black font-medium text-[14px] transition-colors focus:outline-none focus:ring-2 focus:ring-accent/40 active:scale-[0.99] disabled:opacity-40 disabled:pointer-events-none"
        >
          {{ loading ? '验证中...' : '登录' }}
        </button>

        <div class="text-center text-[13px] text-fg-subtle">
          还没有账号？
          <router-link to="/register" class="text-accent hover:text-accent-hover transition-colors">注册</router-link>
        </div>

        <!-- General error -->
        <p
          v-if="errorMessage && !errorField"
          class="text-[13px] text-status-danger bg-status-danger/10 border border-status-danger/20 px-3 py-2 rounded-sm font-mono text-center"
        >
          {{ errorMessage }}
        </p>

        <!-- Inactive email hint -->
        <div v-if="inactiveEmail" class="flex flex-col gap-2 items-center">
          <p class="text-[12px] text-status-warning bg-status-warning/10 border border-status-warning/20 px-3 py-2 rounded-sm text-center">
            账户尚未激活，请查收邮箱验证码
          </p>
          <button
            type="button"
            :disabled="resending"
            @click="resendActivation"
            class="text-[13px] text-accent hover:text-accent-hover disabled:text-fg-disabled transition-colors"
          >
            {{ resending ? '发送中...' : '重新发送激活邮件' }}
          </button>
        </div>
      </form>

      <!-- OTP mode -->
      <template v-else>
        <OtpInput
          :email="form.email"
          purpose="new_device"
          :target-device-id="targetDeviceId || ''"
          @verified="handleOtpVerified"
        />
        <button
          type="button"
          @click="backToCredentials"
          class="text-[13px] text-fg-subtle hover:text-fg-muted transition-colors text-center"
        >
          ← 返回重新登录
        </button>
      </template>
    </div>
  </section>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { api, store } from '../store';
import { login as apiLogin, resendOtp } from '../api/auth';
import OtpInput from '../components/OtpInput.vue';

const router = useRouter();
const loading = ref(false);
const resending = ref(false);
const errorMessage = ref('');
const errorField = ref('');
const inactiveEmail = ref(false);

type Mode = 'credentials' | 'otp';
const mode = ref<Mode>('credentials');
const targetDeviceId = ref<string>('');

const form = reactive({
  email: '',
  password: '',
});

onMounted(() => {
  document.title = "WebTerm";
  document.body.classList.remove("terminal-mode");
});

async function handleLogin() {
  loading.value = true;
  errorMessage.value = '';
  errorField.value = '';
  inactiveEmail.value = false;
  const email = form.email.trim();
  const password = form.password;
  if (!email) {
    errorField.value = 'email';
    errorMessage.value = '请输入邮箱';
    loading.value = false;
    return;
  }
  if (!password) {
    errorField.value = 'password';
    errorMessage.value = '请输入密码';
    loading.value = false;
    return;
  }
  try {
    const res = await apiLogin(email, password);
    if (res.otp_required) {
      targetDeviceId.value = res.target_device_id || '';
      if (res.error) {
        errorMessage.value = res.error;
      }
      mode.value = 'otp';
      return;
    }
    store.user = {
      id: res.id || '',
      username: res.email || res.username || email,
      role: res.role || 'user',
      mode: 'relay',
    };
    store.mode = 'relay';
    await bootstrapUser();
    redirectToNext();
  } catch (err: any) {
    if (err.status === 403) {
      inactiveEmail.value = true;
      errorMessage.value = '账户尚未激活，请先完成邮箱验证';
    } else {
      if (err.status === 401) {
        errorField.value = 'password';
      }
      errorMessage.value = err.message || '登录失败，请检查邮箱和密码';
    }
  } finally {
    loading.value = false;
  }
}

async function handleOtpVerified(payload: { email: string; role: 'admin' | 'user' }) {
  store.user = {
    id: '',
    username: payload.email,
    role: payload.role,
    mode: 'relay',
  };
  store.mode = 'relay';
  await bootstrapUser();
  redirectToNext();
}

async function bootstrapUser() {
  try {
    const me = await api('/api/auth/me');
    store.user = {
      id: me.id,
      username: me.username,
      role: me.role,
      mode: me.mode || 'relay',
    };
    store.mode = me.mode || 'relay';
    if (store.mode === 'direct') {
      store.selectedDeviceId = 'local';
      store.devices = [{ deviceId: 'local', deviceName: '本机', status: 'online' }];
    }
  } catch {
    // 拉取 /me 失败不阻塞跳转
  }
}

function redirectToNext() {
  const route = router.currentRoute.value;
  const queryRedirect = Array.isArray(route.query.redirect)
    ? route.query.redirect[0]
    : route.query.redirect;
  const next = queryRedirect || route.redirectedFrom?.fullPath || "/";
  router.push(next);
}

function backToCredentials() {
  mode.value = 'credentials';
  targetDeviceId.value = '';
  errorMessage.value = '';
  errorField.value = '';
}

async function resendActivation() {
  if (resending.value) return;
  resending.value = true;
  try {
    await resendOtp(form.email);
    errorMessage.value = '激活邮件已发送，请查收';
    inactiveEmail.value = false;
  } catch (err: any) {
    errorMessage.value = err.message || '发送失败，请稍后再试';
  } finally {
    resending.value = false;
  }
}
</script>
