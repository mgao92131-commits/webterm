// P2P WebSocket Mock — bridges browser WebSocket API over a WebRTC DataChannel tunnel.
// Works with P2PConnectionManager to create WS-like connections over P2P.

export function decodeBase64Utf8(base64: string): string {
  const binaryStr = atob(base64);
  const bytes = new Uint8Array(binaryStr.length);
  for (let i = 0; i < binaryStr.length; i++) {
    bytes[i] = binaryStr.charCodeAt(i);
  }
  return new TextDecoder().decode(bytes);
}
