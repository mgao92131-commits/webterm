package relayapp

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/relaycontrol"
	"webterm/go-core/internal/relaycore"
	"webterm/go-core/internal/relaygateway"
	"webterm/go-core/internal/testutil"
)

func TestAppRoutesP2PUnavailableFallback(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	app := NewInMemory(Config{})
	user, err := app.Store().CreateUser("owner@example.com", "secret", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	device, credential, err := app.Store().CreateDevice(user.ID, "Agent Mac")
	if err != nil {
		t.Fatalf("CreateDevice returned error: %v", err)
	}
	token, err := app.Store().IssueToken(user.ID, time.Hour)
	if err != nil {
		t.Fatalf("IssueToken returned error: %v", err)
	}

	server := httptest.NewServer(app.Handler())
	defer server.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	agentDone := make(chan error, 1)
	go func() {
		agentDone <- runP2PAgent(ctx, server.URL, credential)
	}()
	waitForPresence(t, app, user.ID, device.ID)

	body, _ := json.Marshal(relaycore.P2POffer{DeviceID: device.ID, SDP: "v=0"})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, server.URL+"/api/p2p/offer", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	req.AddCookie(&http.Cookie{Name: relaycontrol.AuthCookieName, Value: token.Value})
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("Do returned error: %v", err)
	}
	defer res.Body.Close()
	payload, _ := io.ReadAll(res.Body)
	if res.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("status = %d body=%s", res.StatusCode, payload)
	}
	if !bytes.Contains(payload, []byte("p2p disabled")) {
		t.Fatalf("body = %s, want p2p disabled message", payload)
	}
	if err := <-agentDone; err != nil {
		t.Fatalf("agent failed: %v", err)
	}
}

func TestAppAcceptsP2PICEForOnlineAgent(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	app := NewInMemory(Config{})
	user, err := app.Store().CreateUser("owner@example.com", "secret", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	device, credential, err := app.Store().CreateDevice(user.ID, "Agent Mac")
	if err != nil {
		t.Fatalf("CreateDevice returned error: %v", err)
	}
	token, err := app.Store().IssueToken(user.ID, time.Hour)
	if err != nil {
		t.Fatalf("IssueToken returned error: %v", err)
	}

	server := httptest.NewServer(app.Handler())
	defer server.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	agentDone := make(chan error, 1)
	go func() {
		agentDone <- runIdleP2PAgent(ctx, server.URL, credential)
	}()
	waitForPresence(t, app, user.ID, device.ID)

	body := []byte(`{"deviceId":"` + device.ID + `","candidate":{"candidate":"candidate:1"}}`)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, server.URL+"/api/p2p/ice", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("new request: %v", err)
	}
	req.AddCookie(&http.Cookie{Name: relaycontrol.AuthCookieName, Value: token.Value})
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("Do returned error: %v", err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusNoContent {
		payload, _ := io.ReadAll(res.Body)
		t.Fatalf("status = %d body=%s", res.StatusCode, payload)
	}
	cancel()
	if err := <-agentDone; err != nil && !errors.Is(err, context.Canceled) {
		t.Fatalf("agent failed: %v", err)
	}
}

func TestAppForwardsP2PICEToActiveOfferStream(t *testing.T) {
	testutil.SkipIfLoopbackListenUnavailable(t)

	app := NewInMemory(Config{})
	user, err := app.Store().CreateUser("owner@example.com", "secret", "admin")
	if err != nil {
		t.Fatalf("CreateUser returned error: %v", err)
	}
	device, credential, err := app.Store().CreateDevice(user.ID, "Agent Mac")
	if err != nil {
		t.Fatalf("CreateDevice returned error: %v", err)
	}
	token, err := app.Store().IssueToken(user.ID, time.Hour)
	if err != nil {
		t.Fatalf("IssueToken returned error: %v", err)
	}

	server := httptest.NewServer(app.Handler())
	defer server.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	agentDone := make(chan error, 1)
	go func() {
		agentDone <- runP2PAgentExpectingICE(ctx, server.URL, credential)
	}()
	waitForPresence(t, app, user.ID, device.ID)

	offerDone := make(chan *http.Response, 1)
	offerErr := make(chan error, 1)
	go func() {
		body, _ := json.Marshal(relaycore.P2POffer{DeviceID: device.ID, SDP: "v=0"})
		req, err := http.NewRequestWithContext(ctx, http.MethodPost, server.URL+"/api/p2p/offer", bytes.NewReader(body))
		if err != nil {
			offerErr <- err
			return
		}
		req.AddCookie(&http.Cookie{Name: relaycontrol.AuthCookieName, Value: token.Value})
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			offerErr <- err
			return
		}
		offerDone <- res
	}()

	waitForP2PStream(t, app, user.ID, device.ID)

	body := []byte(`{"deviceId":"` + device.ID + `","candidate":{"candidate":"candidate:1","sdpMid":"0","sdpMLineIndex":0}}`)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, server.URL+"/api/p2p/ice", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("new ice request: %v", err)
	}
	req.AddCookie(&http.Cookie{Name: relaycontrol.AuthCookieName, Value: token.Value})
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("ice Do returned error: %v", err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusNoContent {
		payload, _ := io.ReadAll(res.Body)
		t.Fatalf("ice status = %d body=%s", res.StatusCode, payload)
	}

	select {
	case err := <-offerErr:
		t.Fatalf("offer returned error: %v", err)
	case res := <-offerDone:
		defer res.Body.Close()
		payload, _ := io.ReadAll(res.Body)
		if res.StatusCode != http.StatusServiceUnavailable {
			t.Fatalf("offer status = %d body=%s", res.StatusCode, payload)
		}
	case <-ctx.Done():
		t.Fatalf("offer response timeout: %v", ctx.Err())
	}
	if err := <-agentDone; err != nil {
		t.Fatalf("agent failed: %v", err)
	}
}

func runP2PAgent(ctx context.Context, serverURL, credential string) error {
	conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(serverURL, "http")+"/ws/agent", nil)
	if err != nil {
		return err
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	if err := writeWSJSON(ctx, conn, map[string]any{
		"type":       relaygateway.AgentRegisterMessage,
		"credential": credential,
		"deviceName": "Agent Mac",
	}); err != nil {
		return err
	}
	if _, _, err := conn.Read(ctx); err != nil {
		return err
	}

	frame, err := readRelayFrame(ctx, conn)
	if err != nil {
		return err
	}
	if frame.Type != relaycore.FrameTypeP2POffer {
		return errUnexpected("p2p frame type", frame.Type, relaycore.FrameTypeP2POffer)
	}
	payload, _ := json.Marshal(relaycore.P2PUnavailable{Message: "p2p disabled"})
	return writeRelayFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeP2PUnavailable, frame.StreamID, 0, payload))
}

func runP2PAgentExpectingICE(ctx context.Context, serverURL, credential string) error {
	conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(serverURL, "http")+"/ws/agent", nil)
	if err != nil {
		return err
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	if err := writeWSJSON(ctx, conn, map[string]any{
		"type":       relaygateway.AgentRegisterMessage,
		"credential": credential,
		"deviceName": "Agent Mac",
	}); err != nil {
		return err
	}
	if _, _, err := conn.Read(ctx); err != nil {
		return err
	}

	offer, err := readRelayFrame(ctx, conn)
	if err != nil {
		return err
	}
	if offer.Type != relaycore.FrameTypeP2POffer {
		return errUnexpected("p2p frame type", offer.Type, relaycore.FrameTypeP2POffer)
	}
	ice, err := readRelayFrame(ctx, conn)
	if err != nil {
		return err
	}
	if ice.Type != relaycore.FrameTypeP2PIce {
		return errUnexpected("ice frame type", ice.Type, relaycore.FrameTypeP2PIce)
	}
	if ice.StreamID != offer.StreamID {
		return errUnexpected("ice stream id", ice.StreamID, offer.StreamID)
	}
	if !bytes.Contains(ice.Payload, []byte("candidate:1")) {
		return errUnexpected("ice payload", string(ice.Payload), "candidate:1")
	}
	payload, _ := json.Marshal(relaycore.P2PUnavailable{Message: "p2p disabled"})
	return writeRelayFrame(ctx, conn, relaycore.NewFrame(relaycore.FrameTypeP2PUnavailable, offer.StreamID, 0, payload))
}

func runIdleP2PAgent(ctx context.Context, serverURL, credential string) error {
	conn, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(serverURL, "http")+"/ws/agent", nil)
	if err != nil {
		return err
	}
	defer conn.Close(websocket.StatusNormalClosure, "")
	if err := writeWSJSON(ctx, conn, map[string]any{
		"type":       relaygateway.AgentRegisterMessage,
		"credential": credential,
		"deviceName": "Agent Mac",
	}); err != nil {
		return err
	}
	if _, _, err := conn.Read(ctx); err != nil {
		return err
	}
	<-ctx.Done()
	return ctx.Err()
}

func waitForP2PStream(t *testing.T, app *App, userID, deviceID string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		for _, stream := range app.Streams().Snapshot() {
			if stream.Kind == relaycore.StreamKindP2P && stream.UserID == userID && stream.DeviceID == deviceID && !stream.State.Terminal() {
				return
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("p2p stream did not become active")
}
