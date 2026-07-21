// Package direct 实现 PC Agent 的 Direct 接入层：Android 经 HTTP/WebSocket
// 直连 Agent。它只负责监听、登录认证、Cookie 校验、WebSocket Accept、HTTP
// 请求转发与优雅退出；终端帧、Mux、Session CRUD 与文件传输全部复用现有的
// SessionRouter（经 agentrouter 统一装配），不重新实现任何终端网络协议。
package direct

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"net/http"
	"sync"
	"time"
)

// AuthCookieName 是 Direct 登录会话使用的 Cookie 名。Android 会解析任意
// Set-Cookie 名称并回传，这里固定为 webterm_token。
const AuthCookieName = "webterm_token"

// defaultTokenTTL 是登录 Token 的默认有效期；Refresh 会旋转出新 Token 并
// 重新计算有效期。
const defaultTokenTTL = 7 * 24 * time.Hour

// Authenticator 负责 Direct 模式的登录认证与会话 Token 管理。
//
//   - 密码使用常量时间比较，避免计时侧信道；
//   - Token 使用 crypto/rand 安全随机数生成；
//   - 所有 Token 带过期时间；
//   - Refresh 时旋转 Token（旧 Token 立即失效）。
type Authenticator struct {
	username string
	password string
	ttl      time.Duration

	mu     sync.Mutex
	tokens map[string]time.Time // token -> 绝对过期时间
	now    func() time.Time     // 可注入，便于测试过期逻辑
}

func NewAuthenticator(username, password string) *Authenticator {
	return &Authenticator{
		username: username,
		password: password,
		ttl:      defaultTokenTTL,
		tokens:   make(map[string]time.Time),
		now:      time.Now,
	}
}

// Authenticate 校验账户密码，成功则签发并返回一个新 Token。用户名与密码都
// 做常量时间比较；任一不匹配返回 ok=false。
func (auth *Authenticator) Authenticate(username, password string) (string, bool) {
	userOK := subtle.ConstantTimeCompare([]byte(username), []byte(auth.username)) == 1
	passOK := subtle.ConstantTimeCompare([]byte(password), []byte(auth.password)) == 1
	if !userOK || !passOK {
		return "", false
	}
	return auth.Issue(), true
}

// Issue 签发一个新 Token 并记录其过期时间。
func (auth *Authenticator) Issue() string {
	token := randomToken()
	auth.mu.Lock()
	auth.tokens[token] = auth.now().Add(auth.ttl)
	auth.mu.Unlock()
	return token
}

// Validate 判断 Token 是否存在且未过期；过期 Token 会被顺手清理。
func (auth *Authenticator) Validate(token string) bool {
	if token == "" {
		return false
	}
	auth.mu.Lock()
	defer auth.mu.Unlock()
	expiry, ok := auth.tokens[token]
	if !ok {
		return false
	}
	if !auth.now().Before(expiry) {
		delete(auth.tokens, token)
		return false
	}
	return true
}

// Rotate 在旧 Token 有效时将其失效并签发新 Token（Token 旋转）。旧 Token
// 无效时返回 ok=false，且不签发新 Token。
func (auth *Authenticator) Rotate(token string) (string, bool) {
	if !auth.Validate(token) {
		return "", false
	}
	auth.mu.Lock()
	delete(auth.tokens, token)
	auth.mu.Unlock()
	return auth.Issue(), true
}

// randomToken 生成 32 字节（64 个十六进制字符）的安全随机 Token。
func randomToken() string {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		// crypto/rand 不应失败；失败时 panic 优于发出可预测的弱 Token。
		panic("direct: crypto/rand failed: " + err.Error())
	}
	return hex.EncodeToString(buf)
}

// setAuthCookie 写入 HttpOnly 会话 Cookie。Direct 面向可信局域网、本次不内置
// 证书管理，因此不设置 Secure（否则 http:// 无法携带 Cookie）；公网访问必须
// 经 HTTPS 反向代理或 VPN。
func setAuthCookie(w http.ResponseWriter, token string, ttl time.Duration) {
	http.SetCookie(w, &http.Cookie{
		Name:     AuthCookieName,
		Value:    token,
		Path:     "/",
		HttpOnly: true,
		MaxAge:   int(ttl.Seconds()),
		SameSite: http.SameSiteLaxMode,
	})
}

// clearAuthCookie 使会话 Cookie 失效。
func clearAuthCookie(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{
		Name:     AuthCookieName,
		Value:    "",
		Path:     "/",
		HttpOnly: true,
		MaxAge:   -1,
	})
}

// tokenFromRequest 从请求 Cookie 中读取会话 Token；缺失时返回空串。
func tokenFromRequest(r *http.Request) string {
	if cookie, err := r.Cookie(AuthCookieName); err == nil {
		return cookie.Value
	}
	return ""
}
