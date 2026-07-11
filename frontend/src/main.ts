import { createApp } from 'vue';
import App from './App.vue';
import { router } from './router';
import { resetStore, store } from './store';
import { useTheme } from './composables/useTheme';
import { useSelectedDevice } from './composables/useSelectedDevice';
import { configureApiClient } from './api/client';
import { connectionService } from './services/connection.service';
import './index.css';

useTheme(store);
useSelectedDevice(store);

// 注入 HTTP Client 上下文，避免 api/client 直接依赖全局 Store
configureApiClient({
  getMode: () => store.mode,
  getSelectedDeviceId: () => store.selectedDeviceId,
  getClientId: () => store.clientId,
  onSessionInvalidated: () => {
    resetStore();
    window.dispatchEvent(new CustomEvent('webterm:session-invalidated'));
  },
});

// 初始化连接服务（启动 Relay mux 事件监听）
connectionService.initialize();

const app = createApp(App);
app.use(router);
app.mount('#app');
