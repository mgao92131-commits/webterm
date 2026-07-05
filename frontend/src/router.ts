import { createRouter, createWebHistory, RouteRecordRaw } from 'vue-router';
import { store, resetStore } from './store';
import { me } from './api/auth';

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'Manager',
    component: () => import('./views/ManagerView.vue'),
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('./views/LoginView.vue'),
    meta: { public: true },
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('./views/RegisterView.vue'),
    meta: { public: true },
  },
  {
    path: '/devices',
    name: 'Devices',
    component: () => import('./views/DevicesView.vue'),
  },
  {
    path: '/terminal/:id',
    name: 'Terminal',
    component: () => import('./views/TerminalView.vue'),
  },
  {
    // 捕获所有未定义路径，重定向到首页
    path: '/:pathMatch(.*)*',
    redirect: '/',
  }
];

export const router = createRouter({
  history: createWebHistory(),
  routes,
});

// 全局路由守卫
router.beforeEach(async (to, _from, next) => {
  const isPublicRoute = !!(to.meta as any)?.public || to.name === 'Login' || to.name === 'Register';

  if (!store.user) {
    try {
      const user = await me();
      store.user = {
        id: user.id,
        username: user.username,
        role: user.role,
        mode: user.mode || 'relay',
      };
      store.mode = user.mode || 'relay';

      if (store.mode === 'direct') {
        store.selectedDeviceId = 'local';
        store.devices = [{ deviceId: 'local', deviceName: '本机', status: 'online' }];
      }

      if (isPublicRoute) {
        next({ name: 'Manager' });
      } else {
        next();
      }
    } catch {
      if (!isPublicRoute) {
        next({ name: 'Login', query: { redirect: to.fullPath } });
      } else {
        next();
      }
    }
  } else if (store.user && isPublicRoute) {
    next({ name: 'Manager' });
  } else {
    next();
  }
});
