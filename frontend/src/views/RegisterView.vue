<template>
  <section class="register-shell min-h-screen w-full flex items-center justify-center p-6 bg-gradient-to-tr from-slate-950 via-slate-900 to-indigo-950 relative overflow-hidden">
    <div class="absolute w-[500px] h-[500px] rounded-full bg-indigo-500/10 blur-[120px] top-[-10%] left-[-10%] pointer-events-none"></div>
    <div class="absolute w-[400px] h-[400px] rounded-full bg-cyan-500/5 blur-[100px] bottom-[-5%] right-[-5%] pointer-events-none"></div>

    <div
      class="register-card w-full max-w-[380px] p-8 rounded-2xl bg-slate-900/60 border border-slate-800/80 backdrop-blur-xl shadow-2xl relative z-10 transition-all duration-300 hover:border-slate-700/80 flex flex-col gap-6"
    >
      <div class="text-center">
        <h1 class="text-3xl font-bold tracking-wider bg-gradient-to-r from-indigo-400 via-cyan-400 to-white bg-clip-text text-transparent">
          WebTerm
        </h1>
        <p class="text-xs text-slate-500 mt-2 font-mono">CREATE NEW ACCOUNT</p>
      </div>

      <!-- 凭据注册态 -->
      <form v-if="mode === 'credentials'" @submit.prevent="handleRegister" class="flex flex-col gap-6">
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
              name="new-password"
              type="password"
              autocomplete="new-password"
              placeholder="至少 6 位"
              class="px-4 py-3 rounded-lg bg-slate-950/50 border border-slate-800 text-slate-100 placeholder:text-slate-700 focus:outline-none focus:ring-2 focus:ring-indigo-500/40 focus:border-indigo-500 transition-all font-mono"
              autofocus
              required
            />
          </label>

          <label class="flex flex-col gap-2 text-sm text-slate-400 font-medium cursor-pointer">
            <span>确认密码</span>
            <input
              v-model="form.confirmPassword"
              name="confirm-password"
              type="password"
              autocomplete="new-password"
              placeholder="再次输入密码"
              class="px-4 py-3 rounded-lg bg-slate-950/50 border border-slate-800 text-slate-100 placeholder:text-slate-700 focus:outline-none focus:ring-2 focus:ring-indigo-500/40 focus:border-indigo-500 transition-all font-mono"
              required
            />
          </label>
        </div>

        <button
          type="submit"
          :disabled="loading"
          class="w-full py-3 px-4 mt-2 rounded-lg bg-indigo-600 hover:bg-indigo-500 text-white font-semibold transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-indigo-500/40 active:scale-[0.98] disabled:opacity-50 disabled:pointer-events-none hover:shadow-lg hover:shadow-indigo-500/20"
        >
          {{ loading ? '发送验证码中...' : '注册' }}
        </button>

        <div class="text-center text-xs text-slate-500">
          已有账号？
          <router-link to="/login" class="text-indigo-400 hover:text-indigo-300 transition-colors">去登录</router-link>
        </div>

        <p
          v-if="errorMessage"
          class="error text-sm text-rose-500 bg-rose-500/10 border border-rose-500/20 px-3 py-2 rounded-lg font-mono text-center"
        >
          {{ errorMessage }}
        </p>
      </form>

      <!-- OTP 态 -->
      <template v-else>
        <OtpInput
          :email="form.email"
          purpose="register"
          @verified="handleVerified"
        />
        <button
          type="button"
          @click="backToCredentials"
          class="text-xs text-slate-500 hover:text-slate-300 transition-colors"
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
    await apiRegister(form.email, form.password);
    mode.value = 'otp';
  } catch (err: any) {
    errorMessage.value = err.message || '注册失败，请稍后再试';
  } finally {
    loading.value = false;
  }
}

async function handleVerified(payload: { email: string; role: 'admin' | 'user' }) {
  store.user = {
    id: 0,
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
}
</script>

<style scoped>
</style>
