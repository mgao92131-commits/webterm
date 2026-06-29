package main

import (
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"strings"
	"time"

	"nhooyr.io/websocket"

	"webterm/go-core/internal/protocol"
)

func main() {
	baseURL := flag.String("url", "http://127.0.0.1:8080", "direct server base URL")
	username := flag.String("user", "admin", "direct username")
	password := flag.String("password", "", "direct password")
	cwd := flag.String("cwd", "", "session working directory")
	timeout := flag.Duration("timeout", 8*time.Second, "smoke test timeout")
	flag.Parse()

	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
	defer cancel()

	if err := run(ctx, *baseURL, *username, *password, *cwd); err != nil {
		fmt.Fprintf(os.Stderr, "flow smoke failed: %v\n", err)
		os.Exit(1)
	}
	fmt.Println("flow smoke ok")
}

func run(ctx context.Context, baseURL string, username string, password string, cwd string) error {
	base, err := url.Parse(strings.TrimRight(baseURL, "/"))
	if err != nil {
		return err
	}
	jar, err := cookiejar.New(nil)
	if err != nil {
		return err
	}
	client := &http.Client{Jar: jar}

	if err := login(ctx, client, base, username, password); err != nil {
		return err
	}
	sessionID, err := createSession(ctx, client, base, cwd)
	if err != nil {
		return err
	}
	if err := smokeTerminal(ctx, client, base, sessionID); err != nil {
		return err
	}
	if err := runMuxSmoke(ctx, client, base, sessionID); err != nil {
		return err
	}
	return nil
}

func login(ctx context.Context, client *http.Client, base *url.URL, username string, password string) error {
	body := map[string]string{"username": username, "password": password}
	response, err := postJSON(ctx, client, base.JoinPath("/api/login"), body)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("login status %d: %s", response.StatusCode, readBody(response.Body))
	}
	return nil
}

func createSession(ctx context.Context, client *http.Client, base *url.URL, cwd string) (string, error) {
	body := map[string]string{"name": "flow-smoke", "cwd": cwd}
	response, err := postJSON(ctx, client, base.JoinPath("/api/sessions"), body)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusCreated {
		return "", fmt.Errorf("create session status %d: %s", response.StatusCode, readBody(response.Body))
	}
	var payload struct {
		ID string `json:"id"`
	}
	if err := json.NewDecoder(response.Body).Decode(&payload); err != nil {
		return "", err
	}
	if payload.ID == "" {
		return "", fmt.Errorf("create session returned empty id")
	}
	return payload.ID, nil
}

func smokeTerminal(ctx context.Context, client *http.Client, base *url.URL, sessionID string) error {
	wsURL := *base
	if wsURL.Scheme == "https" {
		wsURL.Scheme = "wss"
	} else {
		wsURL.Scheme = "ws"
	}
	wsURL.Path = strings.TrimRight(base.Path, "/") + "/ws/sessions/" + sessionID
	header := http.Header{}
	for _, cookie := range client.Jar.Cookies(base) {
		header.Add("Cookie", cookie.String())
	}
	conn, _, err := websocket.Dial(ctx, wsURL.String(), &websocket.DialOptions{
		HTTPHeader:   header,
		Subprotocols: []string{protocol.BinarySubprotocol},
	})
	if err != nil {
		return err
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	hello, err := protocol.EncodeJSONMessage(protocol.MsgHello, map[string]int{"cols": 100, "rows": 30})
	if err != nil {
		return err
	}
	if err := conn.Write(ctx, websocket.MessageBinary, hello); err != nil {
		return err
	}

	marker := fmt.Sprintf("GO_CORE_FLOW_OK_%d", time.Now().UnixNano())
	input := append([]byte{protocol.MsgInput}, []byte("printf "+marker+"\\n")...)
	if err := conn.Write(ctx, websocket.MessageBinary, input); err != nil {
		return err
	}

	for {
		messageType, data, err := conn.Read(ctx)
		if err != nil {
			return err
		}
		if messageType == websocket.MessageText && strings.Contains(string(data), marker) {
			return nil
		}
		if messageType != websocket.MessageBinary || len(data) == 0 {
			continue
		}
		switch data[0] {
		case protocol.MsgOutput, protocol.MsgState:
			_, _, payload, err := protocol.DecodeSequencedData(data)
			if err == nil && bytes.Contains(payload, []byte(marker)) {
				return nil
			}
		case protocol.MsgInfo:
			if bytes.Contains(data[1:], []byte(marker)) {
				return nil
			}
		}
	}
}

func runMuxSmoke(ctx context.Context, client *http.Client, base *url.URL, sessionID string) error {
	wsURL := *base
	if wsURL.Scheme == "https" {
		wsURL.Scheme = "wss"
	} else {
		wsURL.Scheme = "ws"
	}
	wsURL.Path = strings.TrimRight(base.Path, "/") + "/ws/sessions"

	header := http.Header{}
	for _, cookie := range client.Jar.Cookies(base) {
		header.Add("Cookie", cookie.String())
	}

	conn, _, err := websocket.Dial(ctx, wsURL.String(), &websocket.DialOptions{
		HTTPHeader:   header,
		Subprotocols: []string{protocol.MuxSubprotocol},
	})
	if err != nil {
		return fmt.Errorf("mux dial: %w", err)
	}
	defer conn.Close(websocket.StatusNormalClosure, "")

	connectMsg, _ := json.Marshal(map[string]any{
		"type":               protocol.WSConnect,
		"tunnelConnectionId": "term1",
		"path":               "/ws/sessions/" + sessionID,
		"protocols":          []string{protocol.BinarySubprotocol},
	})
	if err := conn.Write(ctx, websocket.MessageText, connectMsg); err != nil {
		return fmt.Errorf("mux ws-connect write: %w", err)
	}

	// read ws-connected
	if _, _, err := conn.Read(ctx); err != nil {
		return fmt.Errorf("mux ws-connected read: %w", err)
	}

	// send hello
	helloPayload, _ := json.Marshal(map[string]any{"lastSeq": 0})
	helloFrame := append([]byte{protocol.MsgHello}, helloPayload...)
	tunnel, err := protocol.EncodeTunnelFrame(protocol.MsgTypeWSData, "term1", protocol.WSDataBinary, helloFrame)
	if err != nil {
		return fmt.Errorf("mux encode tunnel: %w", err)
	}
	if err := conn.Write(ctx, websocket.MessageBinary, tunnel); err != nil {
		return fmt.Errorf("mux hello write: %w", err)
	}

	// read one response (tunnel frame) to prove the channel is working
	if _, _, err := conn.Read(ctx); err != nil {
		return fmt.Errorf("mux response read: %w", err)
	}

	fmt.Println("mux smoke OK")
	return nil
}

func postJSON(ctx context.Context, client *http.Client, endpoint *url.URL, body any) (*http.Response, error) {
	data, err := json.Marshal(body)
	if err != nil {
		return nil, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint.String(), bytes.NewReader(data))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Content-Type", "application/json")
	return client.Do(request)
}

func readBody(body io.Reader) string {
	data, _ := io.ReadAll(io.LimitReader(body, 4096))
	return string(data)
}
