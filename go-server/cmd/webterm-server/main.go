package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/creack/pty"
	"github.com/gorilla/websocket"
)

const (
	cookieName = "webterm_token"

	msgInput  = byte(0x01)
	msgOutput = byte(0x02)
	msgResize = byte(0x03)
	msgHello  = byte(0x04)
	msgInfo   = byte(0x05)
	msgExit   = byte(0x06)
	msgPing   = byte(0x07)
	msgPong   = byte(0x08)
	msgTitle  = byte(0x09)

	defaultCols           = 100
	defaultRows           = 30
	clientSendQueueSize   = 2048
	maxRingFrames         = 4000
	maxReplayPayloadBytes = 64 * 1024
	maxRecentInputChars   = 2000
	maxTermTitleChars     = 256
)

type Config struct {
	Addr     string
	Username string
	Password string
	Shell    string
	CWD      string
	ZDotDir  string
}

type Server struct {
	config   Config
	auth     *Auth
	sessions *SessionManager
	upgrader websocket.Upgrader
}

type Auth struct {
	username string
	password string
	mu       sync.Mutex
	failures map[string]int
}

type SessionManager struct {
	mu       sync.Mutex
	nextID   int
	sessions map[string]*TerminalSession
	config   Config
}

type TerminalSession struct {
	mu                sync.Mutex
	id                string
	instanceID        string
	name              string
	termTitle         string
	cwd               string
	command           string
	status            string
	cols              int
	rows              int
	createdAt         time.Time
	lastActiveAt      time.Time
	clients           map[*Client]struct{}
	ptyFile           *os.File
	process           *os.Process
	ring              []Frame
	latestSeq         uint64
	inputBuffer       string
	recentInputLines  []string
	recentInputHidden bool
	onExit            func(string)
}

type Frame struct {
	Seq  uint64 `json:"seq"`
	Data []byte `json:"-"`
}

type Client struct {
	ws      *websocket.Conn
	session *TerminalSession
	send    chan Outbound
	done    chan struct{}
	outMu   sync.Mutex
	ready   bool
}

type Outbound struct {
	kind byte
	data []byte
}

type SessionInfo struct {
	ID                string    `json:"id"`
	InstanceID        string    `json:"instanceId"`
	Name              string    `json:"name"`
	TermTitle         string    `json:"termTitle"`
	DisplayTitle      string    `json:"displayTitle"`
	CWD               string    `json:"cwd"`
	Command           string    `json:"command"`
	Status            string    `json:"status"`
	Clients           int       `json:"clients"`
	Cols              int       `json:"cols"`
	Rows              int       `json:"rows"`
	RecentInputLines  []string  `json:"recentInputLines"`
	RecentInputHidden bool      `json:"recentInputHidden"`
	CreatedAt         time.Time `json:"createdAt"`
	LastActiveAt      time.Time `json:"lastActiveAt"`
}

func main() {
	config := loadConfig()
	if config.Password == "" {
		log.Fatal("WEBTERM_PASSWORD must be set")
	}

	server := &Server{
		config:   config,
		auth:     newAuth(config.Username, config.Password),
		sessions: newSessionManager(config),
		upgrader: websocket.Upgrader{
			CheckOrigin: sameHostOrigin,
		},
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/api/login", server.handleLogin)
	mux.HandleFunc("/api/me", server.requireAuth(server.handleMe))
	mux.HandleFunc("/api/sessions", server.requireAuth(server.handleSessions))
	mux.HandleFunc("/api/sessions/", server.requireAuth(server.handleSessionByID))
	mux.HandleFunc("/ws/sessions/", server.requireAuth(server.handleSessionWebSocket))
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "webterm go server", http.StatusOK)
	})

	log.Printf("webterm go listening on http://%s", config.Addr)
	log.Fatal(http.ListenAndServe(config.Addr, mux))
}

func loadConfig() Config {
	loadLocalEnv(filepath.Join("..", ".env.local"))
	loadLocalEnv(".env.local")
	loadExecutableEnv()
	cwd, _ := os.Getwd()
	return Config{
		Addr:     env("WEBTERM_GO_ADDR", defaultGoAddr(env("WEBTERM_ADDR", "127.0.0.1:8080"))),
		Username: env("WEBTERM_USER", "admin"),
		Password: os.Getenv("WEBTERM_PASSWORD"),
		Shell:    os.Getenv("WEBTERM_SHELL"),
		CWD:      env("WEBTERM_CWD", cwd),
		ZDotDir:  env("WEBTERM_ZDOTDIR", filepath.Join(cwd, "go-server", "runtime", "zsh")),
	}
}

func defaultGoAddr(nodeAddr string) string {
	host, _, err := net.SplitHostPort(nodeAddr)
	if err == nil {
		return net.JoinHostPort(host, "8081")
	}
	index := strings.LastIndex(nodeAddr, ":")
	if index > 0 {
		return nodeAddr[:index] + ":8081"
	}
	return "127.0.0.1:8081"
}

func loadExecutableEnv() {
	executable, err := os.Executable()
	if err != nil {
		return
	}
	loadLocalEnv(filepath.Join(filepath.Dir(executable), ".env.local"))
}

func loadLocalEnv(path string) {
	data, err := os.ReadFile(path)
	if err != nil {
		return
	}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		key = strings.TrimSpace(key)
		value = strings.Trim(strings.TrimSpace(value), `"'`)
		if os.Getenv(key) == "" {
			_ = os.Setenv(key, value)
		}
	}
}

func env(key, fallback string) string {
	value := os.Getenv(key)
	if value == "" {
		return fallback
	}
	return value
}

func newAuth(username, password string) *Auth {
	return &Auth{username: username, password: password, failures: make(map[string]int)}
}

func (a *Auth) verify(username, password, remoteAddr string) bool {
	ok := safeEqual(username, a.username) && safeEqual(password, a.password)
	a.mu.Lock()
	defer a.mu.Unlock()
	if ok {
		delete(a.failures, remoteAddr)
		return true
	}
	a.failures[remoteAddr]++
	return false
}

func (a *Auth) token() string {
	mac := hmac.New(sha256.New, []byte(a.password))
	_, _ = mac.Write([]byte(a.username))
	return "v1-" + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

func (a *Auth) authenticated(r *http.Request) bool {
	cookie, err := r.Cookie(cookieName)
	return err == nil && safeEqual(cookie.Value, a.token())
}

func (a *Auth) failureDelay(remoteAddr string) time.Duration {
	a.mu.Lock()
	count := a.failures[remoteAddr]
	a.mu.Unlock()
	delay := 250 + count*150
	if delay > 1000 {
		delay = 1000
	}
	return time.Duration(delay) * time.Millisecond
}

func safeEqual(a, b string) bool {
	left := []byte(a)
	right := []byte(b)
	return len(left) == len(right) && subtle.ConstantTimeCompare(left, right) == 1
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var body struct {
		Username string `json:"username"`
		Password string `json:"password"`
	}
	if err := readJSON(r, &body); err != nil {
		http.Error(w, "invalid json", http.StatusBadRequest)
		return
	}
	remote := remoteHost(r)
	if !s.auth.verify(body.Username, body.Password, remote) {
		time.Sleep(s.auth.failureDelay(remote))
		http.Error(w, "invalid credentials", http.StatusUnauthorized)
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name:     cookieName,
		Value:    url.QueryEscape(s.auth.token()),
		Path:     "/",
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		Secure:   r.TLS != nil || os.Getenv("WEBTERM_COOKIE_SECURE") == "1",
	})
	writeJSON(w, http.StatusOK, map[string]string{"username": s.config.Username})
}

func (s *Server) requireAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !s.auth.authenticated(r) {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		next(w, r)
	}
}

func (s *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"username": s.config.Username})
}

func (s *Server) handleSessions(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		writeJSON(w, http.StatusOK, s.sessions.list())
	case http.MethodPost:
		var body struct {
			Name string `json:"name"`
			CWD  string `json:"cwd"`
		}
		if err := readJSON(r, &body); err != nil && !errors.Is(err, io.EOF) {
			http.Error(w, "invalid json", http.StatusBadRequest)
			return
		}
		session, err := s.sessions.create(body.Name, body.CWD)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		writeJSON(w, http.StatusCreated, session.info())
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *Server) handleSessionByID(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/api/sessions/")
	if id == "" {
		http.NotFound(w, r)
		return
	}
	switch r.Method {
	case http.MethodDelete:
		if !s.sessions.close(id) {
			http.Error(w, "session not found", http.StatusNotFound)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	case http.MethodPatch:
		var body struct {
			Name string `json:"name"`
		}
		if err := readJSON(r, &body); err != nil {
			http.Error(w, "invalid json", http.StatusBadRequest)
			return
		}
		session, ok := s.sessions.rename(id, body.Name)
		if !ok {
			http.Error(w, "session not found", http.StatusNotFound)
			return
		}
		writeJSON(w, http.StatusOK, session.info())
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *Server) handleSessionWebSocket(w http.ResponseWriter, r *http.Request) {
	id := strings.TrimPrefix(r.URL.Path, "/ws/sessions/")
	id, _ = url.PathUnescape(id)
	session, ok := s.sessions.get(id)
	if !ok {
		log.Printf("ws reject session=%s remote=%s reason=not_found", id, r.RemoteAddr)
		http.Error(w, "session not found", http.StatusNotFound)
		return
	}
	ws, err := s.upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("ws upgrade failed session=%s remote=%s err=%v", id, r.RemoteAddr, err)
		return
	}
	log.Printf("ws accepted session=%s remote=%s", id, r.RemoteAddr)
	session.attach(ws)
}

func newSessionManager(config Config) *SessionManager {
	return &SessionManager{nextID: 1, sessions: make(map[string]*TerminalSession), config: config}
}

func (m *SessionManager) list() []SessionInfo {
	m.mu.Lock()
	defer m.mu.Unlock()
	result := make([]SessionInfo, 0, len(m.sessions))
	for _, session := range m.sessions {
		result = append(result, session.info())
	}
	return result
}

func (m *SessionManager) get(id string) (*TerminalSession, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	session, ok := m.sessions[id]
	return session, ok
}

func (m *SessionManager) create(name, cwd string) (*TerminalSession, error) {
	m.mu.Lock()
	id := "g" + strconv.Itoa(m.nextID)
	m.nextID++
	m.mu.Unlock()

	session, err := newTerminalSession(id, name, cwd, m.config, func(id string) {
		m.mu.Lock()
		delete(m.sessions, id)
		m.mu.Unlock()
	})
	if err != nil {
		return nil, err
	}

	m.mu.Lock()
	m.sessions[id] = session
	m.mu.Unlock()
	return session, nil
}

func (m *SessionManager) close(id string) bool {
	m.mu.Lock()
	session, ok := m.sessions[id]
	if ok {
		delete(m.sessions, id)
	}
	m.mu.Unlock()
	if ok {
		session.close()
	}
	return ok
}

func (m *SessionManager) rename(id, name string) (*TerminalSession, bool) {
	session, ok := m.get(id)
	if !ok {
		return nil, false
	}
	session.mu.Lock()
	session.name = strings.TrimSpace(name)
	session.touchLocked()
	clients, payload := session.infoBroadcastLocked()
	session.mu.Unlock()
	sendInfoBroadcast(clients, payload)
	return session, true
}

func newTerminalSession(id, name, cwd string, config Config, onExit func(string)) (*TerminalSession, error) {
	cleanCWD := cwd
	if cleanCWD == "" {
		cleanCWD = config.CWD
	}
	if stat, err := os.Stat(cleanCWD); err != nil || !stat.IsDir() {
		return nil, fmt.Errorf("cwd does not exist or is not a directory: %s", cleanCWD)
	}

	shell := config.Shell
	args := []string{}
	if shell == "" {
		shell = os.Getenv("SHELL")
	}
	if shell == "" {
		for _, candidate := range []string{"/bin/zsh", "/bin/bash", "/bin/sh"} {
			if isExecutable(candidate) {
				shell = candidate
				break
			}
		}
	}
	if shell == "" {
		return nil, errors.New("no shell found; set WEBTERM_SHELL")
	}

	cmd := exec.Command(shell, args...)
	cmd.Dir = cleanCWD
	cmd.Env = shellEnv(cleanCWD, config.ZDotDir)
	ptmx, err := pty.StartWithSize(cmd, &pty.Winsize{Cols: defaultCols, Rows: defaultRows})
	if err != nil {
		return nil, err
	}

	session := &TerminalSession{
		id:           id,
		instanceID:   newInstanceID(),
		name:         strings.TrimSpace(name),
		cwd:          cleanCWD,
		command:      shell,
		status:       "running",
		cols:         defaultCols,
		rows:         defaultRows,
		createdAt:    time.Now(),
		lastActiveAt: time.Now(),
		clients:      make(map[*Client]struct{}),
		ptyFile:      ptmx,
		process:      cmd.Process,
		onExit:       onExit,
	}
	go session.readPTY()
	go session.waitProcess(cmd)
	return session, nil
}

func newInstanceID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 36)
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%012x",
		b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

func shellEnv(cwd, zDotDir string) []string {
	env := make([]string, 0, len(os.Environ())+8)
	for _, item := range os.Environ() {
		if strings.HasPrefix(item, "PWD=") ||
			strings.HasPrefix(item, "OLDPWD=") ||
			strings.HasPrefix(item, "TERM_SESSION_ID=") ||
			strings.HasPrefix(item, "TERM_PROGRAM=") ||
			strings.HasPrefix(item, "TERM_PROGRAM_VERSION=") ||
			strings.HasPrefix(item, "SHELL_SESSION_") ||
			strings.HasPrefix(item, "NO_COLOR=") ||
			strings.HasPrefix(item, "FORCE_COLOR=") ||
			strings.HasPrefix(item, "CLICOLOR=") ||
			strings.HasPrefix(item, "CLICOLOR_FORCE=") ||
			strings.HasPrefix(item, "ZDOTDIR=") {
			continue
		}
		env = append(env, item)
	}
	env = append(env,
		"PWD="+cwd,
		"TERM=xterm-256color",
		"COLORTERM=truecolor",
		"TERM_PROGRAM=WebTerm",
		"FORCE_COLOR=1",
		"CLICOLOR=1",
		"CLICOLOR_FORCE=1",
		"WEBTERM=1",
		"WEBTERM_CWD="+cwd,
		"ZDOTDIR="+zDotDir,
		"SHELL_SESSIONS_DISABLE=1",
	)
	return env
}

func (s *TerminalSession) info() SessionInfo {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.infoLocked()
}

func (s *TerminalSession) infoBroadcastLocked() ([]*Client, []byte) {
	info := s.infoLocked()
	payload := mustJSON(info)
	clients := s.clientListLocked()
	return clients, payload
}

func sanitizeTermTitle(title string) string {
	title = strings.TrimSpace(title)
	if title == "" {
		return ""
	}
	var builder strings.Builder
	count := 0
	for _, r := range title {
		if count >= maxTermTitleChars {
			break
		}
		if r < ' ' || r == '\x7f' {
			continue
		}
		builder.WriteRune(r)
		count++
	}
	return strings.TrimSpace(builder.String())
}

func sendInfoBroadcast(clients []*Client, payload []byte) {
	for _, client := range clients {
		client.sendMessage(msgInfo, payload)
	}
}

func (s *TerminalSession) updateTermTitle(title string) {
	nextTitle := sanitizeTermTitle(title)
	s.mu.Lock()
	if nextTitle == s.termTitle {
		s.mu.Unlock()
		return
	}
	s.termTitle = nextTitle
	s.touchLocked()
	clients, payload := s.infoBroadcastLocked()
	s.mu.Unlock()
	sendInfoBroadcast(clients, payload)
}

func (s *TerminalSession) displayTitleLocked() string {
	termTitle := s.termTitleLocked()
	if s.name != "" {
		return s.name + " - " + termTitle
	}
	return termTitle
}

func (s *TerminalSession) termTitleLocked() string {
	if s.termTitle != "" {
		return s.termTitle
	}
	return "Terminal"
}

func (s *TerminalSession) readPTY() {
	buffer := make([]byte, 8192)
	for {
		n, err := s.ptyFile.Read(buffer)
		if n > 0 {
			data := append([]byte(nil), buffer[:n]...)
			s.handleOutput(data)
		}
		if err != nil {
			return
		}
	}
}

func (s *TerminalSession) waitProcess(cmd *exec.Cmd) {
	err := cmd.Wait()
	code := 0
	if err != nil {
		if exit, ok := err.(*exec.ExitError); ok {
			code = exit.ExitCode()
		} else {
			code = -1
		}
	}
	s.mu.Lock()
	if s.status == "closed" {
		s.mu.Unlock()
		return
	}
	s.status = "closed"
	s.touchLocked()
	clients := s.readyClientListLocked()
	s.mu.Unlock()
	for _, client := range clients {
		client.sendMessage(msgExit, mustJSON(map[string]int{"code": code}))
		client.close()
	}
	if s.onExit != nil {
		s.onExit(s.id)
	}
}

func (s *TerminalSession) handleOutput(data []byte) {
	s.mu.Lock()
	s.latestSeq++
	frame := Frame{Seq: s.latestSeq, Data: append([]byte(nil), data...)}
	s.ring = append(s.ring, frame)
	if len(s.ring) > maxRingFrames {
		s.ring = s.ring[len(s.ring)-maxRingFrames:]
	}
	s.touchLocked()
	clients := s.readyClientListLocked()
	s.mu.Unlock()

	payload := make([]byte, 8+len(data))
	putUint64(payload[:8], frame.Seq)
	copy(payload[8:], data)
	for _, client := range clients {
		client.sendMessage(msgOutput, payload)
	}
}

func (s *TerminalSession) attach(ws *websocket.Conn) {
	client := &Client{
		ws:      ws,
		session: s,
		send:    make(chan Outbound, clientSendQueueSize),
		done:    make(chan struct{}),
	}
	s.mu.Lock()
	s.clients[client] = struct{}{}
	s.touchLocked()
	info := s.infoLocked()
	s.mu.Unlock()

	log.Printf("ws attached session=%s clients=%d status=%s", s.id, info.Clients, info.Status)
	go client.writer()
	client.sendMessage(msgInfo, mustJSON(info))
	client.reader()
}

func (c *Client) reader() {
	defer c.close()
	for {
		messageType, payload, err := c.ws.ReadMessage()
		if err != nil {
			log.Printf("ws read closed session=%s err=%v", c.session.id, err)
			return
		}
		if messageType != websocket.BinaryMessage || len(payload) == 0 {
			continue
		}
		kind := payload[0]
		data := payload[1:]
		switch kind {
		case msgInput:
			c.session.writeInput(data)
		case msgResize:
			var resize struct {
				Cols int `json:"cols"`
				Rows int `json:"rows"`
			}
			if json.Unmarshal(data, &resize) == nil {
				c.session.resize(resize.Cols, resize.Rows)
			}
		case msgHello:
			var hello struct {
				LastSeq uint64 `json:"lastSeq"`
			}
			_ = json.Unmarshal(data, &hello)
			c.session.sendReplay(c, hello.LastSeq)
		case msgPing:
			c.sendMessage(msgPong, nil)
		case msgTitle:
			c.session.updateTermTitle(string(data))
		}
	}
}

func (c *Client) writer() {
	for message := range c.send {
		payload := append([]byte{message.kind}, message.data...)
		if err := c.ws.WriteMessage(websocket.BinaryMessage, payload); err != nil {
			log.Printf("ws write closed session=%s err=%v", c.session.id, err)
			c.close()
			return
		}
	}
}

func (c *Client) sendMessage(kind byte, data []byte) {
	c.outMu.Lock()
	defer c.outMu.Unlock()
	select {
	case <-c.done:
		return
	default:
	}
	select {
	case c.send <- Outbound{kind: kind, data: data}:
	default:
		log.Printf("ws send queue full session=%s kind=%d bytes=%d; dropping message", c.session.id, kind, len(data))
	}
}

func (c *Client) close() {
	c.outMu.Lock()
	defer c.outMu.Unlock()
	c.closeLocked()
}

func (c *Client) closeLocked() {
	select {
	case <-c.done:
		return
	default:
		close(c.done)
	}
	c.session.mu.Lock()
	delete(c.session.clients, c)
	c.session.touchLocked()
	clients := len(c.session.clients)
	c.session.mu.Unlock()
	log.Printf("ws detached session=%s clients=%d", c.session.id, clients)
	_ = c.ws.Close()
	close(c.send)
}

func (s *TerminalSession) writeInput(data []byte) {
	s.mu.Lock()
	if s.status != "running" {
		s.mu.Unlock()
		return
	}
	s.recordInputLocked(data)
	s.touchLocked()
	s.mu.Unlock()
	_, _ = s.ptyFile.Write(data)
}

func (s *TerminalSession) resize(cols, rows int) {
	if cols < 10 || rows < 5 || cols > 500 || rows > 200 {
		return
	}
	s.mu.Lock()
	s.cols = cols
	s.rows = rows
	s.touchLocked()
	s.mu.Unlock()
	_ = pty.Setsize(s.ptyFile, &pty.Winsize{Cols: uint16(cols), Rows: uint16(rows)})
}

func (s *TerminalSession) sendReplay(client *Client, lastSeq uint64) {
	s.mu.Lock()
	info := s.infoLocked()
	frames := s.framesAfterLocked(lastSeq)
	s.mu.Unlock()
	client.sendMessage(msgInfo, mustJSON(info))
	sentSeq, chunks := client.sendOutputFrames(frames)
	if len(frames) == 0 {
		sentSeq = lastSeq
	}
	log.Printf("ws replay session=%s lastSeq=%d frames=%d chunks=%d latestSeq=%d", s.id, lastSeq, len(frames), chunks, sentSeq)
	for {
		s.mu.Lock()
		missed := s.framesAfterLocked(sentSeq)
		if len(missed) == 0 {
			client.ready = true
			s.mu.Unlock()
			return
		}
		s.mu.Unlock()
		nextSeq, _ := client.sendOutputFrames(missed)
		sentSeq = nextSeq
	}
}

func (c *Client) sendOutputFrames(frames []Frame) (uint64, int) {
	if len(frames) == 0 {
		return 0, 0
	}
	chunks := 0
	chunk := make([]byte, 0, maxReplayPayloadBytes)
	var chunkSeq uint64
	flush := func() {
		if len(chunk) == 0 {
			return
		}
		payload := make([]byte, 8+len(chunk))
		putUint64(payload[:8], chunkSeq)
		copy(payload[8:], chunk)
		c.sendMessage(msgOutput, payload)
		chunks++
		chunk = chunk[:0]
	}

	for _, frame := range frames {
		if len(chunk) > 0 && len(chunk)+len(frame.Data) > maxReplayPayloadBytes {
			flush()
		}
		chunk = append(chunk, frame.Data...)
		chunkSeq = frame.Seq
	}
	flush()
	return chunkSeq, chunks
}

func (s *TerminalSession) close() {
	s.mu.Lock()
	if s.status == "closed" {
		s.mu.Unlock()
		return
	}
	s.status = "closed"
	clients := s.clientListLocked()
	s.mu.Unlock()
	for _, client := range clients {
		client.sendMessage(msgExit, mustJSON(map[string]int{"code": 0}))
		client.close()
	}
	if s.process != nil {
		_ = s.process.Kill()
	}
	_ = s.ptyFile.Close()
}

func (s *TerminalSession) infoLocked() SessionInfo {
	return SessionInfo{
		ID:                s.id,
		InstanceID:        s.instanceID,
		Name:              s.name,
		TermTitle:         s.termTitleLocked(),
		DisplayTitle:      s.displayTitleLocked(),
		CWD:               s.cwd,
		Command:           s.command,
		Status:            s.status,
		Clients:           len(s.clients),
		Cols:              s.cols,
		Rows:              s.rows,
		RecentInputLines:  append([]string(nil), s.recentInputLines...),
		RecentInputHidden: s.recentInputHidden,
		CreatedAt:         s.createdAt,
		LastActiveAt:      s.lastActiveAt,
	}
}

func (s *TerminalSession) clientListLocked() []*Client {
	clients := make([]*Client, 0, len(s.clients))
	for client := range s.clients {
		clients = append(clients, client)
	}
	return clients
}

func (s *TerminalSession) readyClientListLocked() []*Client {
	clients := make([]*Client, 0, len(s.clients))
	for client := range s.clients {
		if client.ready {
			clients = append(clients, client)
		}
	}
	return clients
}

func (s *TerminalSession) framesAfterLocked(seq uint64) []Frame {
	frames := make([]Frame, 0)
	for _, frame := range s.ring {
		if seq == 0 || frame.Seq > seq {
			frames = append(frames, Frame{Seq: frame.Seq, Data: append([]byte(nil), frame.Data...)})
		}
	}
	return frames
}

func (s *TerminalSession) touchLocked() {
	s.lastActiveAt = time.Now()
}

func (s *TerminalSession) recordInputLocked(data []byte) {
	for _, r := range string(data) {
		switch r {
		case '\r':
			s.commitInputLocked()
		case '\n':
			s.inputBuffer = trimInputBuffer(s.inputBuffer + string(r))
		case '\x03', '\x1b':
			s.inputBuffer = ""
		case '\x7f', '\b':
			if len(s.inputBuffer) > 0 {
				s.inputBuffer = s.inputBuffer[:len(s.inputBuffer)-1]
			}
		default:
			if r >= ' ' && r != '\x7f' {
				s.inputBuffer = trimInputBuffer(s.inputBuffer + string(r))
			}
		}
	}
}

func (s *TerminalSession) commitInputLocked() {
	text := strings.TrimSpace(s.inputBuffer)
	s.inputBuffer = ""
	if text == "" {
		return
	}
	s.recentInputHidden = isSensitiveInput(text)
	if s.recentInputHidden {
		s.recentInputLines = nil
		return
	}
	lines := strings.Split(text, "\n")
	clean := make([]string, 0, len(lines))
	for _, line := range lines {
		line = strings.TrimRight(line, "\r\n")
		if strings.TrimSpace(line) != "" {
			clean = append(clean, line)
		}
	}
	if len(clean) > 2 {
		clean = clean[len(clean)-2:]
	}
	s.recentInputLines = clean
}

func trimInputBuffer(value string) string {
	if len(value) <= maxRecentInputChars {
		return value
	}
	return value[len(value)-maxRecentInputChars:]
}

func isSensitiveInput(value string) bool {
	lower := strings.ToLower(value)
	for _, word := range []string{"password", "passwd", "token", "secret", "api_key", "apikey", "authorization", "bearer", "credential", "private_key"} {
		if strings.Contains(lower, word) {
			return true
		}
	}
	return false
}

func readJSON(r *http.Request, target any) error {
	defer r.Body.Close()
	return json.NewDecoder(r.Body).Decode(target)
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	body, _ := json.Marshal(value)
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write(body)
}

func mustJSON(value any) []byte {
	body, _ := json.Marshal(value)
	return body
}

func sameHostOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return true
	}
	parsed, err := url.Parse(origin)
	return err == nil && strings.EqualFold(parsed.Host, r.Host)
}

func remoteHost(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

func isExecutable(path string) bool {
	stat, err := os.Stat(path)
	return err == nil && !stat.IsDir() && stat.Mode()&0111 != 0
}

func putUint64(dst []byte, value uint64) {
	for i := 7; i >= 0; i-- {
		dst[i] = byte(value)
		value >>= 8
	}
}
