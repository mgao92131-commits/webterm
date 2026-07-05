/**
 * 全局应用配置。
 * 所有硬编码的魔法值、超时、阈值、存储键都应集中在这里。
 */

export const CONFIG = {
  /** WebRTC STUN 服务器 */
  stunServers: [{ urls: 'stun:stun.l.google.com:19302' }] as RTCIceServer[],

  /** P2P 连接超时（毫秒） */
  p2pConnectTimeoutMs: 3000,

  /** P2P 物理断连宽限期（毫秒） */
  p2pDisconnectedGraceMs: 8000,

  /** P2P HTTP 请求超时（毫秒） */
  p2pRequestTimeoutMs: 30000,

  /** P2P WebSocket Mock 连接超时（毫秒） */
  p2pWsMockConnectTimeoutMs: 5000,

  /** Manager 会话列表轮询间隔（毫秒） */
  managerPollIntervalMs: 3000,

  /** WebSocket 重连退避策略 */
  reconnectBackoff: {
    baseMs: 1000,
    multiplier: 1.6,
    capMs: 8000,
    jitter: true,
    minDelayMs: 200,
    relayMinDelayMs: 250,
  },

  /** 不需要自动重连的 WebSocket close code */
  reconnectBlockedCloseCodes: [1000, 1008, 1011],

  /** 终端字体大小限制 */
  fontSize: {
    min: 8,
    max: 30,
    defaultMobile: 12,
    defaultDesktop: 14,
  },

  /** localStorage / sessionStorage 键名 */
  storageKeys: {
    clientId: 'webterm-client-id',
    theme: 'webterm-theme',
    selectedDevice: 'webterm-selected-device',
    fontSize: 'webterm:fontSize',
    lastSeqPrefix: 'webterm:',
  },

  /** 支持的主题列表 */
  themes: ['solarized', 'dracula'] as const,
} as const;

export type Theme = (typeof CONFIG.themes)[number];
