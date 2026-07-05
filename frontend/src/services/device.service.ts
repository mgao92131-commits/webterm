/**
 * 设备业务服务。
 * 封装 PC Agent 设备的查询、创建、删除等用例。
 */

import {
  getDevices as apiGetDevices,
  registerDevice as apiRegisterDevice,
  deleteDevice as apiDeleteDevice,
  type DeviceInfo,
  type CreateDeviceResult,
} from '../api/devices';
import type { Device } from '../store';

export type { DeviceInfo, CreateDeviceResult };

function toStoreDevice(device: DeviceInfo): Device {
  return {
    deviceId: device.deviceId,
    deviceName: device.deviceName,
    status: device.online ? 'online' : 'offline',
  };
}

/** 获取 PC Agent 设备原始数据（设备管理页使用）。 */
export async function fetchDevices(): Promise<DeviceInfo[]> {
  return apiGetDevices();
}

/** 获取已映射为 Store 设备模型的列表（Manager 页使用）。 */
export async function fetchStoreDevices(): Promise<Device[]> {
  const devices = await apiGetDevices();
  return devices.map(toStoreDevice);
}

export async function createDevice(deviceName: string): Promise<CreateDeviceResult> {
  return apiRegisterDevice(deviceName);
}

export async function removeDevice(deviceId: string): Promise<void> {
  return apiDeleteDevice(deviceId);
}
