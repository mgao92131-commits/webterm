import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { defineComponent, h, ref, watch, nextTick } from 'vue';
import { mount } from '@vue/test-utils';
import { resetStore, store } from '../store';
import { connectionService } from '../services/connection.service';
import { useManagerConnection, type ManagerServerMessage } from './useManagerConnection';
import type { RelayMuxChannel } from '../lib/relay-mux-session';

vi.mock('../services/connection.service', () => ({
  connectionService: {
    openManagerChannel: vi.fn(),
  },
}));

function asChannel(ws: MockWebSocket): RelayMuxChannel {
  return ws as unknown as RelayMuxChannel;
}

class MockWebSocket extends EventTarget {
  readyState: number = WebSocket.CONNECTING;
  close = vi.fn(() => {
    this.readyState = WebSocket.CLOSED;
  });

  triggerOpen() {
    this.readyState = WebSocket.OPEN;
    this.dispatchEvent(new Event('open'));
  }

  triggerMessage(data: unknown) {
    this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(data) }));
  }

  triggerClose(code = 1006) {
    this.readyState = WebSocket.CLOSED;
    this.dispatchEvent(new CloseEvent('close', { code }));
  }

  triggerError() {
    this.dispatchEvent(new Event('error'));
  }
}

const TestComponent = defineComponent({
  props: {
    deviceId: {
      type: String,
      default: null,
    },
  },
  emits: ['message', 'connect', 'poll'],
  setup(props, { emit }) {
    const deviceIdRef = ref<string | null>(props.deviceId ?? null);

    watch(() => props.deviceId, (value) => {
      deviceIdRef.value = value ?? null;
    });

    const { connectionHealth } = useManagerConnection({
      deviceId: deviceIdRef,
      onMessage: (msg: ManagerServerMessage) => emit('message', msg),
      poll: async () => emit('poll'),
      onConnect: () => emit('connect'),
    });

    return { connectionHealth };
  },
  render() {
    return h('div');
  },
});

describe('useManagerConnection', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(connectionService.openManagerChannel).mockReset();
    resetStore();
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('connects on mount and sets state to connecting', async () => {
    const ws = new MockWebSocket();
    vi.mocked(connectionService.openManagerChannel).mockReturnValueOnce(asChannel(ws));

    store.mode = 'relay';
    const wrapper = mount(TestComponent, { props: { deviceId: 'd1' } });
    await nextTick();

    expect(vi.mocked(connectionService.openManagerChannel)).toHaveBeenCalledWith('d1');
    expect(store.connectionStates['manager']).toBe('connecting');
    expect(wrapper.vm.connectionHealth).toBe('connecting');
    wrapper.unmount();
  });

  it('transitions to connected on open and clears polling', async () => {
    const ws = new MockWebSocket();
    vi.mocked(connectionService.openManagerChannel).mockReturnValueOnce(asChannel(ws));

    store.mode = 'relay';
    const wrapper = mount(TestComponent, { props: { deviceId: 'd1' } });
    await nextTick();

    ws.triggerOpen();
    await nextTick();

    expect(store.connectionStates['manager']).toBe('connected');
    expect(wrapper.emitted('connect')).toHaveLength(1);
    expect(wrapper.vm.connectionHealth).toBe('connected');
    wrapper.unmount();
  });

  it('forwards messages and stops polling', async () => {
    const ws = new MockWebSocket();
    vi.mocked(connectionService.openManagerChannel).mockReturnValueOnce(asChannel(ws));

    store.mode = 'relay';
    const wrapper = mount(TestComponent, { props: { deviceId: 'd1' } });
    await nextTick();
    ws.triggerOpen();

    ws.triggerMessage({ type: 'sessions', data: [{ id: 's1' }] });
    await nextTick();

    const messages = wrapper.emitted('message') as ManagerServerMessage[][];
    expect(messages).toHaveLength(1);
    expect(messages[0][0]).toMatchObject({ type: 'sessions', data: [{ id: 's1' }] });
    wrapper.unmount();
  });


  it('enters polling and schedules reconnect after abnormal close', async () => {
    const ws = new MockWebSocket();
    vi.mocked(connectionService.openManagerChannel)
      .mockReturnValueOnce(asChannel(ws))
      .mockReturnValue(asChannel(new MockWebSocket()));

    store.mode = 'relay';
    const wrapper = mount(TestComponent, { props: { deviceId: 'd1' } });
    await nextTick();
    ws.triggerOpen();

    ws.triggerClose(1006);
    await nextTick();

    expect(store.connectionStates['manager']).toBe('polling');
    expect(wrapper.vm.connectionHealth).toBe('polling');

    // reconnect scheduled with backoff
    await vi.advanceTimersByTimeAsync(5000);
    expect(vi.mocked(connectionService.openManagerChannel)).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('does not reconnect on blocked close codes', async () => {
    const ws = new MockWebSocket();
    vi.mocked(connectionService.openManagerChannel).mockReturnValueOnce(asChannel(ws));

    store.mode = 'relay';
    const wrapper = mount(TestComponent, { props: { deviceId: 'd1' } });
    await nextTick();
    ws.triggerOpen();

    ws.triggerClose(1000);
    await nextTick();

    expect(store.connectionStates['manager']).toBe('disconnected');

    await vi.advanceTimersByTimeAsync(10000);
    expect(vi.mocked(connectionService.openManagerChannel)).toHaveBeenCalledTimes(1);
    wrapper.unmount();
  });

  it('reconnects immediately when network comes back online', async () => {
    const ws = new MockWebSocket();
    vi.mocked(connectionService.openManagerChannel)
      .mockReturnValueOnce(asChannel(ws))
      .mockReturnValue(asChannel(new MockWebSocket()));

    store.mode = 'relay';
    const wrapper = mount(TestComponent, { props: { deviceId: 'd1' } });
    await nextTick();

    ws.triggerClose(1006);
    await nextTick();

    window.dispatchEvent(new Event('online'));
    await nextTick();

    expect(vi.mocked(connectionService.openManagerChannel)).toHaveBeenCalledTimes(2);
    wrapper.unmount();
  });

  it('disconnects and reconnects when deviceId changes', async () => {
    const ws1 = new MockWebSocket();
    const ws2 = new MockWebSocket();
    vi.mocked(connectionService.openManagerChannel)
      .mockReturnValueOnce(asChannel(ws1))
      .mockReturnValueOnce(asChannel(ws2));

    store.mode = 'relay';
    const wrapper = mount(TestComponent, { props: { deviceId: 'd1' } });
    await nextTick();

    expect(ws1.close).not.toHaveBeenCalled();
    await wrapper.setProps({ deviceId: 'd2' });
    await nextTick();

    expect(ws1.close).toHaveBeenCalledTimes(1);
    expect(vi.mocked(connectionService.openManagerChannel)).toHaveBeenLastCalledWith('d2');
    wrapper.unmount();
  });

  it('polls periodically while disconnected', async () => {
    const ws = new MockWebSocket();
    vi.mocked(connectionService.openManagerChannel).mockReturnValueOnce(asChannel(ws));

    store.mode = 'relay';
    const wrapper = mount(TestComponent, { props: { deviceId: 'd1' } });
    await nextTick();
    ws.triggerOpen();

    ws.triggerClose(1000);
    await nextTick();

    await vi.advanceTimersByTimeAsync(3500);
    expect((wrapper.emitted('poll') ?? []).length).toBeGreaterThanOrEqual(1);
    wrapper.unmount();
  });

  it('disconnects on unmount', async () => {
    const ws = new MockWebSocket();
    vi.mocked(connectionService.openManagerChannel).mockReturnValueOnce(asChannel(ws));

    store.mode = 'relay';
    const wrapper = mount(TestComponent, { props: { deviceId: 'd1' } });
    await nextTick();

    wrapper.unmount();

    expect(ws.close).toHaveBeenCalledTimes(1);
    expect(store.connectionStates['manager']).toBe('disconnected');
  });
});
