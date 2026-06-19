// WebRTC P2P connection manager for the PC Agent.
// Handles PeerConnection lifecycle, DataChannel messaging, and ICE candidate relay.

let nodeDataChannel;
try {
  const mod = await import('node-datachannel');
  nodeDataChannel = mod.default || mod;
  nodeDataChannel.initLogger('Warning');
} catch (err) {
  console.warn('[Agent WebRTC] node-datachannel is not installed or failed to load. P2P direct mode will be disabled:', err.message);
}

export class P2PManager {
  constructor(sendJSON) {
    this.sendJSON = sendJSON;
    this.peerConnections = new Map(); // clientId -> { pc, username }
    this.available = !!nodeDataChannel;
  }

  handleOffer(msg) {
    if (!nodeDataChannel) {
      console.warn('[Agent WebRTC] node-datachannel is not available. Ignoring offer.');
      return;
    }
    const clientId = msg.from;
    const sdp = msg.sdp;
    const username = msg.username;

    // Clean up existing connection
    const oldVal = this.peerConnections.get(clientId);
    if (oldVal) {
      try { oldVal.pc.close(); } catch {}
      this.peerConnections.delete(clientId);
    }

    console.log(`[Agent WebRTC] Creating PeerConnection for client: ${clientId} (user: ${username})`);

    let pc;
    try {
      pc = new nodeDataChannel.PeerConnection(clientId, {
        iceServers: ['stun:stun.l.google.com:19302']
      });
    } catch (err) {
      console.error('[Agent WebRTC] Failed to create PeerConnection:', err.message);
      return;
    }

    const clientState = { pc, username };
    this.peerConnections.set(clientId, clientState);

    pc.onLocalDescription((sdpText, type) => {
      if (type === 'answer') {
        console.log(`[Agent WebRTC] Generated local SDP answer for ${clientId}, sending immediately.`);
        this.sendJSON({ type: 'p2p-answer', sdp: sdpText, to: clientId });
      }
    });

    pc.onLocalCandidate((candidate, mid) => {
      this.sendJSON({
        type: 'p2p-ice',
        candidate: { candidate, sdpMid: mid || '0' },
        to: clientId
      });
    });

    pc.onStateChange((state) => {
      console.log(`[Agent WebRTC] PeerConnection state for ${clientId} changed to: ${state}`);
      if (state === 'disconnected' || state === 'failed' || state === 'closed') {
        console.log(`[Agent WebRTC] Cleaning up client connection for ${clientId}`);
        try { pc.close(); } catch {}
        this.peerConnections.delete(clientId);
      }
    });

    pc.onDataChannel((dc) => {
      console.log(`[Agent WebRTC] DataChannel opened for client: ${clientId}`);

      dc.onMessage((data) => {
        if (typeof data === 'string') {
          this._onDataChannelText(clientId, dc, data);
        } else {
          this._onDataChannelBinary(clientId, data);
        }
      });

      dc.onClosed(() => {
        console.log(`[Agent WebRTC] DataChannel closed for client: ${clientId}`);
      });
    });

    try {
      pc.setRemoteDescription(sdp, 'offer');
    } catch (err) {
      console.error('[Agent WebRTC] Failed to setRemoteDescription:', err.message);
      this.peerConnections.delete(clientId);
    }
  }

  handleIce(msg) {
    const clientId = msg.from;
    const clientState = this.peerConnections.get(clientId);
    if (clientState && msg.candidate) {
      try {
        clientState.pc.addRemoteCandidate(msg.candidate.candidate, msg.candidate.sdpMid || '0');
      } catch (err) {
        console.error(`[Agent WebRTC] Failed to add remote candidate for ${clientId}:`, err.message);
      }
    }
  }

  cleanup() {
    for (const { pc } of this.peerConnections.values()) {
      try { pc.close(); } catch {}
    }
    this.peerConnections.clear();
  }

  // Callbacks set by agent.js to delegate DataChannel message handling
  setMessageHandlers({ onTextMessage, onBinaryMessage }) {
    this._onTextMessage = onTextMessage;
    this._onBinaryMessage = onBinaryMessage;
  }

  _onDataChannelText(clientId, dc, data) {
    try {
      const parsed = JSON.parse(data);

      const transport = {
        sendJSON: (m) => {
          try { dc.sendMessage(JSON.stringify(m)); } catch (err) {
            console.error('[Agent WebRTC] Failed to send JSON over DC:', err.message);
          }
        },
        sendBinary: (f) => {
          try { dc.sendMessage(f); } catch (err) {
            console.error('[Agent WebRTC] Failed to send Binary over DC:', err.message);
          }
        }
      };

      if (this._onTextMessage) {
        this._onTextMessage(parsed, transport);
      }
    } catch (err) {
      console.error('[Agent WebRTC] Error parsing DC text message:', err.message);
    }
  }

  _onDataChannelBinary(clientId, data) {
    if (this._onBinaryMessage) {
      try {
        this._onBinaryMessage(data);
      } catch (err) {
        console.error('[Agent WebRTC] Error processing DC binary message:', err.message);
      }
    }
  }
}
