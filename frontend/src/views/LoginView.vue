<template>
  <section class="login-shell min-h-screen w-full flex items-center justify-center p-6 bg-gradient-to-tr from-slate-950 via-slate-900 to-indigo-950 relative overflow-hidden">
    <div class="absolute w-[500px] h-[500px] rounded-full bg-indigo-500/10 blur-[120px] top-[-10%] left-[-10%] pointer-events-none"></div>
    <div class="absolute w-[400px] h-[400px] rounded-full bg-cyan-500/5 blur-[100px] bottom-[-5%] right-[-5%] pointer-events-none"></div>

    <div
      class="login-card w-full max-w-[380px] p-8 rounded-2xl bg-slate-900/60 border border-slate-800/80 backdrop-blur-xl shadow-2xl relative z-10 transition-all duration-300 hover:border-slate-700/80 flex flex-col gap-6"
    >
      <div class="text-center">
        <h1 class="text-3xl font-bold tracking-wider bg-gradient-to-r from-indigo-400 via-cyan-400 to-white bg-clip-text text-transparent">
          WebTerm
        </h1>
        <p class="text-xs text-slate-500 mt-2 font-mono">SECURE CONSOLE CONNECTION</p>
      </div>

      <!-- 凭据态：邮箱 + 密码 -->
      <form v-if="mode === 'credentials'" @submit.prevent="handleLogin" class="flex flex-col gap-6" novalidate>
        <div class="flex flex-col gap-4">
          <label class="flex flex-col gap-2 text-sm text-slate-400 font-medium cursor-pointer">
            <span>邮箱</span>
            <input
              v-model="form.email"
              name="email"
              type="email"
              autocomplete="email"
              placeholder="you@example.com"
              class="px-4 py-3 rounded-lg bg-slate-950/50 border border-slate-800 text-slate-100 placeholder:text-slate-700 focus:outline-none focus:ring-2 focus:ring-indigo-500/40 focus:border-indigo-500 transition-all font-mono"
              required
            />
          </label>

          <label class="flex flex-col gap-2 text-sm text-slate-400 font-medium cursor-pointer">
            <span>密码</span>
            <input
              v-model="form.password"
              name="password"
              type="password"
              autocomplete="current-password"
              placeholder="••••••••"
              class="px-4 py-3 rounded-lg bg-slate-950/50 border border-slate-800 text-slate-100 placeholder:text-slate-700 focus:outline-none focus:ring-2 focus:ring-indigo-500/40 focus:border-indigo-500 transition-all font-mono"
              autofocus
              required
            />
          </label>
        </div>

        <button
          type="submit"
          :disabled="loading"
          class="w-full py-3 px-4 mt-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white font-semibold transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-indigo-500/40 active:scale-[0.98] disabled:opacity-50 disabled:pointer-events-none hover:shadow-lg hover:shadow-indigo-500/20"
        >
          {{ loading ? '验证中...' : '登录' }}
        </button>

        <div class="text-center text-xs text-slate-500">
          还没有账号？
          <router-link to="/register" class="text-indigo-400 hover:text-indigo-300 transition-colors">立即注册</router-link>
        </div>

        <p
          v-if="errorMessage"
          class="error text-sm text-rose-500 bg-rose-500/10 border border-rose-500/20 px-3 py-2 rounded-lg font-mono text-center"
        >
          {{ errorMessage }}
        </p>

        <div v-if="inactiveEmail" class="flex flex-col gap-2">
          <p class="text-xs text-amber-400 bg-amber-500/10 border border-amber-500/20 px-3 py-2 rounded-lg text-center">
            账户尚未激活，请查收邮箱验证码
          </p>
          <button
            type="button"
            :disabled="resending"
            @click="resendActivation"
            class="text-xs text-indigo-400 hover:text-indigo-300 disabled:text-slate-600 transition-colors"
          >
            {{ resending ? '发送中...' : '重新发送激活邮件' }}
          </button>
        </div>
      </form>

      <!-- OTP 态 -->
      <OtpInput
        v-else-if="mode === 'otp'"
        :email="form.email"
        purpose="new_device"
        :target-device-id="targetDeviceId || ''"
        @verified="handleOtpVerified"
      />

      <button
        v-if="mode === 'otp'"
        type="button"
        @click="backToCredentials"
        class="text-xs text-slate-500 hover:text-slate-300 transition-colors"
      >
        ← 返回重新登录
      </button>
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
  inactiveEmail.value = false;
  try {
    const res = await apiLogin(form.email, form.password);
    if (res.otp_required) {
      targetDeviceId.value = res.target_device_id || '';
      if (res.error) {
        errorMessage.value = res.error;
      }
      mode.value = 'otp';
      return;
    }
    // 正常登录成功
    store.user = {
      id: 0,
      username: res.email || res.username || form.email,
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
      errorMessage.value = err.message || '登录失败，请检查邮箱和密码';
    }
  } finally {
    loading.value = false;
  }
}

async function handleOtpVerified(payload: { email: string; role: 'admin' | 'user' }) {
  store.user = {
    id: 0,
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
    // 拉取 /me 失败不阻塞跳转，后续请求若 401 会被拦截器处理
  }
}

function redirectToNext() {
  const next = router.currentRoute.value.redirectedFrom?.fullPath || "/";
  router.push(next);
}

function backToCredentials() {
  mode.value = 'credentials';
  targetDeviceId.value = '';
  errorMessage.value = '';
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

<style scoped>
</style>
