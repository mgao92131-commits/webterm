package com.webterm.mobile.transport;

import com.webterm.mobile.data.api.WebTermApi;
import com.webterm.mobile.data.api.WebTermUrls;
import com.webterm.transport.api.MuxTransport;
import com.webterm.mobile.domain.session.RelayMuxSessionRegistry;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.util.Collections;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import dagger.hilt.android.qualifiers.ApplicationContext;
import okhttp3.OkHttpClient;

@Singleton
public final class P2PConnectionManager {
    private static final String TAG = "P2PConnectionManager";

    public interface Listener {
        void onConnecting(String deviceId);
        void onConnected(String deviceId);
        void onDisconnected(String deviceId, String reason);
        void onError(String deviceId, String message);
    }

    private final Handler mainHandler;
    private final WebTermApi api;
    private final Provider<RelayMuxSessionRegistry> registryProvider;
    private volatile Listener listener;

    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private DataChannel dataChannel;
    private P2PDataChannelEndpoint dataChannelEndpoint;
    private String baseUrl;
    private String cookie;
    private String deviceId;
    private boolean connected;
    private boolean disconnecting;

    @Inject
    public P2PConnectionManager(@ApplicationContext Context context, OkHttpClient http, Handler mainHandler, Provider<RelayMuxSessionRegistry> registryProvider) {
        this.mainHandler = mainHandler;
        this.api = new WebTermApi(http);
        this.registryProvider = registryProvider;
        ensureFactory(context.getApplicationContext());
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized boolean isP2PActive(String deviceId) {
        return connected
            && safeEquals(this.deviceId, deviceId)
            && dataChannelEndpoint != null
            && dataChannelEndpoint.isOpen();
    }

    public synchronized MuxTransport getDataChannelTransport(String deviceId) {
        if (!isP2PActive(deviceId)) return null;
        return new WebRtcDataChannelTransport(dataChannelEndpoint);
    }

    public synchronized void connectToDevice(String baseUrl, String cookie, String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) return;
        if (safeEquals(this.deviceId, deviceId) && peerConnection != null) return;
        disconnect("switching device");
        disconnecting = false;
        this.baseUrl = WebTermUrls.normalizeBaseUrl(baseUrl);
        this.cookie = cookie;
        this.deviceId = deviceId;
        Log.i(TAG, "p2p connecting to " + deviceId);
        listener.onConnecting(deviceId);

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(
            Collections.singletonList(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        );
        peerConnection = factory.createPeerConnection(config, new PeerObserver(deviceId));
        if (peerConnection == null) {
            fail(deviceId, "Failed to create PeerConnection.");
            return;
        }
        DataChannel.Init init = new DataChannel.Init();
        init.ordered = true;
        dataChannel = peerConnection.createDataChannel("tunnel", init);
        if (dataChannel == null) {
            fail(deviceId, "Failed to create DataChannel.");
            return;
        }
        dataChannelEndpoint = new P2PDataChannelEndpoint(dataChannel, new P2PDataChannelEndpoint.StateListener() {
            @Override public void onOpen() {
                if (disconnecting || !safeEquals(P2PConnectionManager.this.deviceId, deviceId)) return;
                Log.i(TAG, "p2p datachannel open for " + deviceId);
                connected = true;
                mainHandler.post(() -> listener.onConnected(deviceId));
            }

            @Override public void onClosed(String reason) {
                if (disconnecting || !safeEquals(P2PConnectionManager.this.deviceId, deviceId)) return;
                Log.i(TAG, "p2p " + reason + " for " + deviceId);
                connected = false;
                mainHandler.post(() -> listener.onDisconnected(deviceId, reason));
            }
        });

        peerConnection.createOffer(new SdpObserverAdapter() {
            @Override
            public void onCreateSuccess(SessionDescription offer) {
                PeerConnection pc = peerConnection;
                if (pc == null || !safeEquals(P2PConnectionManager.this.deviceId, deviceId)) return;
                pc.setLocalDescription(new SdpObserverAdapter() {
                    @Override
                    public void onSetSuccess() {
                        sendOffer(deviceId, offer.description);
                    }

                    @Override
                    public void onSetFailure(String error) {
                        fail(deviceId, "Set local SDP failed: " + error);
                    }
                }, offer);
            }

            @Override
            public void onCreateFailure(String error) {
                fail(deviceId, "Create offer failed: " + error);
            }
        }, new org.webrtc.MediaConstraints());
    }

    public synchronized void disconnect() {
        disconnect("manual disconnect");
    }

    private synchronized void disconnect(String reason) {
        String oldDeviceId = deviceId;
        if (disconnecting) return;
        disconnecting = true;
        connected = false;
        if (dataChannelEndpoint != null) {
            dataChannelEndpoint.close();
            dataChannelEndpoint = null;
        } else if (dataChannel != null) {
            try { dataChannel.close(); } catch (Exception ignored) {}
        }
        dataChannel = null;
        if (peerConnection != null) {
            try { peerConnection.close(); } catch (Exception ignored) {}
            peerConnection = null;
        }
        deviceId = null;
        disconnecting = false;
        if (oldDeviceId != null && listener != null) {
            mainHandler.post(() -> listener.onDisconnected(oldDeviceId, reason));
        }
    }

    private void sendOffer(String targetDeviceId, String sdp) {
        api.postP2POffer(baseUrl, cookie, targetDeviceId, sdp, new WebTermApi.P2POfferCallback() {
            @Override
            public void onAnswer(String answerSdp) {
                PeerConnection pc = peerConnection;
                if (pc == null || !safeEquals(deviceId, targetDeviceId)) return;
                SessionDescription answer = new SessionDescription(SessionDescription.Type.ANSWER, answerSdp);
                pc.setRemoteDescription(new SdpObserverAdapter() {
                    @Override
                    public void onSetSuccess() {
                        Log.i(TAG, "p2p remote answer applied for " + targetDeviceId);
                    }

                    @Override
                    public void onSetFailure(String error) {
                        fail(targetDeviceId, "Set remote SDP failed: " + error);
                    }
                }, answer);
            }

            @Override
            public void onError(String message) {
                fail(targetDeviceId, message);
            }
        });
    }

    private void sendIceCandidate(String targetDeviceId, IceCandidate candidate) {
        JSONObject json = new JSONObject();
        try {
            json.put("candidate", candidate.sdp);
            json.put("sdpMid", candidate.sdpMid);
            json.put("sdpMLineIndex", candidate.sdpMLineIndex);
        } catch (JSONException e) {
            fail(targetDeviceId, e.getMessage());
            return;
        }
        api.postP2PIce(baseUrl, cookie, targetDeviceId, json, new WebTermApi.SimpleCallback() {
            @Override public void onReady() {}

            @Override public void onError(String message) {
                Log.w(TAG, "P2P ICE failed: " + message);
            }
        });
    }

    private void fail(String targetDeviceId, String message) {
        Log.w(TAG, message);
        mainHandler.post(() -> listener.onError(targetDeviceId, message));
        disconnect(message);
    }

    private void ensureFactory(Context context) {
        if (factory != null) return;
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        );
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
    }

    private final class PeerObserver implements PeerConnection.Observer {
        private final String targetDeviceId;

        PeerObserver(String targetDeviceId) {
            this.targetDeviceId = targetDeviceId;
        }

        @Override public void onSignalingChange(PeerConnection.SignalingState signalingState) {}
        @Override public void onIceConnectionReceivingChange(boolean receiving) {}
        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}
        @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        @Override public void onAddStream(MediaStream stream) {}
        @Override public void onRemoveStream(MediaStream stream) {}
        @Override public void onDataChannel(DataChannel dataChannel) {}
        @Override public void onRenegotiationNeeded() {}
        @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] mediaStreams) {}

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
            if (disconnecting) return;
            if (state == PeerConnection.IceConnectionState.FAILED
                || state == PeerConnection.IceConnectionState.DISCONNECTED
                || state == PeerConnection.IceConnectionState.CLOSED) {
                disconnect("ice " + state.name().toLowerCase(java.util.Locale.US));
            }
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            sendIceCandidate(targetDeviceId, candidate);
        }
    }

    private static class SdpObserverAdapter implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String error) {}
        @Override public void onSetFailure(String error) {}
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }
}
