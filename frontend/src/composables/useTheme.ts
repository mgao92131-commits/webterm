import { watch } from 'vue';
import { saveTheme } from '../utils/storage';
import type { AppStore } from '../store';

/**
 * 监听 Store 主题变化，同步到 localStorage 与 document.documentElement。
 */
export function useTheme(store: Pick<AppStore, 'theme'>) {
  watch(
    () => store.theme,
    (newTheme) => {
      document.documentElement.dataset.theme = newTheme;
      saveTheme(newTheme);
    },
    { immediate: true },
  );
}
