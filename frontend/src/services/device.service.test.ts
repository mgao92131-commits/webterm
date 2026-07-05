import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fetchDevices, fetchStoreDevices, createDevice, removeDevice } from './device.service';

vi.mock('../api/devices', () => ({
  getDevices: vi.fn(),
  registerDevice: vi.fn(),
  deleteDevice: vi.fn(),
}));

import { getDevices, registerDevice, deleteDevice } from '../api/devices';

const mockedGetDevices = vi.mocked(getDevices);
const mockedRegisterDevice = vi.mocked(registerDevice);
const mockedDeleteDevice = vi.mocked(deleteDevice);

describe('device.service', () => {
  beforeEach(() => {
    mockedGetDevices.mockReset();
    mockedRegisterDevice.mockReset();
    mockedDeleteDevice.mockReset();
  });

  it('fetches raw devices', async () => {
    const devices = [
      { deviceId: 'd1', deviceName: 'Mac', online: true, lastSeenAt: null, createdAt: '2024-01-01' },
    ];
    mockedGetDevices.mockResolvedValueOnce(devices);

    const result = await fetchDevices();

    expect(mockedGetDevices).toHaveBeenCalledTimes(1);
    expect(result).toEqual(devices);
  });

  it('maps raw devices to store model', async () => {
    mockedGetDevices.mockResolvedValueOnce([
      { deviceId: 'd1', deviceName: 'Mac', online: true, lastSeenAt: null, createdAt: '2024-01-01' },
      { deviceId: 'd2', deviceName: 'PC', online: false, lastSeenAt: null, createdAt: '2024-01-01' },
    ]);

    const result = await fetchStoreDevices();

    expect(result).toEqual([
      { deviceId: 'd1', deviceName: 'Mac', status: 'online' },
      { deviceId: 'd2', deviceName: 'PC', status: 'offline' },
    ]);
  });

  it('creates a device', async () => {
    mockedRegisterDevice.mockResolvedValueOnce({ deviceId: 'd2', deviceName: 'PC', agentSecret: 'secret' });

    const result = await createDevice('PC');

    expect(mockedRegisterDevice).toHaveBeenCalledWith('PC');
    expect(result.agentSecret).toBe('secret');
  });

  it('removes a device', async () => {
    mockedDeleteDevice.mockResolvedValueOnce(undefined);

    await removeDevice('d1');

    expect(mockedDeleteDevice).toHaveBeenCalledWith('d1');
  });
});
