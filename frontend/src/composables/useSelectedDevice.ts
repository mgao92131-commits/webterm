import { watch } from 'vue';
import { saveSelectedDeviceId, clearSelectedDeviceId } from '../utils/storage';
import type { AppStore } from '../store';

/**
 * 监听 Store 选中设备变化，同步到 localStorage。
 */
export function useSelectedDevice(store: Pick<AppStore, 'selectedDeviceId'>) {
  watch(() => store.selectedDeviceId, (newDevice) => {
    if (newDevice) {
      saveSelectedDeviceId(newDevice);
    } else {
      clearSelectedDeviceId();
    }
  });
}
