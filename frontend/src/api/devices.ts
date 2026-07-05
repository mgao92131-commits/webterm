import { httpClient } from './client';

export interface DeviceInfo {
  deviceId: string;
  deviceName: string;
  online: boolean;
  lastSeenAt: string | null;
  createdAt: string;
}

export interface CreateDeviceResult {
  deviceId: string;
  deviceName: string;
  agentSecret: string;
}

export async function getDevices(): Promise<DeviceInfo[]> {
  return httpClient('/api/devices', {
    method: 'GET'
  });
}

export async function registerDevice(deviceName: string): Promise<CreateDeviceResult> {
  return httpClient('/api/devices', {
    method: 'POST',
    body: JSON.stringify({ deviceName })
  });
}

export async function deleteDevice(deviceId: string): Promise<void> {
  const id = deviceId.startsWith('d') ? deviceId.slice(1) : deviceId;
  return httpClient(`/api/devices/d${id}`, {
    method: 'DELETE'
  });
}
