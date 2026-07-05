import { CONFIG, type Theme } from '../config';

/**
 * 安全地读取 sessionStorage。
 */
export function getSessionItem(key: string): string | null {
  try {
    return sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

/**
 * 安全地写入 sessionStorage。
 */
export function setSessionItem(key: string, value: string): void {
  try {
    sessionStorage.setItem(key, value);
  } catch {
    // ignore
  }
}

/**
 * 安全地读取 localStorage。
 */
export function getLocalItem(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

/**
 * 安全地写入 localStorage。
 */
export function setLocalItem(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    // ignore
  }
}

/**
 * 安全地从 localStorage 删除项。
 */
export function removeLocalItem(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    // ignore
  }
}

/**
 * 获取或创建客户端 ID。
 */
export function getClientId(): string {
  let id = getSessionItem(CONFIG.storageKeys.clientId);
  if (!id) {
    id = 'c_' + Math.random().toString(36).substring(2, 15);
    setSessionItem(CONFIG.storageKeys.clientId, id);
  }
  return id;
}

/**
 * 获取保存的主题。
 */
export function getSavedTheme(): Theme | null {
  const theme = getLocalItem(CONFIG.storageKeys.theme) as Theme;
  return CONFIG.themes.includes(theme) ? theme : null;
}

/**
 * 保存主题。
 */
export function saveTheme(theme: Theme): void {
  setLocalItem(CONFIG.storageKeys.theme, theme);
}

/**
 * 获取保存的选中设备 ID。
 */
export function getSavedDeviceId(): string | null {
  return getLocalItem(CONFIG.storageKeys.selectedDevice);
}

/**
 * 保存选中设备 ID。
 */
export function saveSelectedDeviceId(deviceId: string): void {
  setLocalItem(CONFIG.storageKeys.selectedDevice, deviceId);
}

/**
 * 清除保存的选中设备 ID。
 */
export function clearSelectedDeviceId(): void {
  removeLocalItem(CONFIG.storageKeys.selectedDevice);
}
