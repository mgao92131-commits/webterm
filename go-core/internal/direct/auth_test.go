package direct

import (
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

func TestAuthenticateSuccess(t *testing.T) {
	auth := NewAuthenticator("admin", "s3cret")
	token, ok := auth.Authenticate("admin", "s3cret")
	if !ok || token == "" {
		t.Fatalf("Authenticate ok=%v token=%q, want success with token", ok, token)
	}
	if !auth.Validate(token) {
		t.Fatal("issued token should validate")
	}
}

func TestAuthenticateWrongPassword(t *testing.T) {
	auth := NewAuthenticator("admin", "s3cret")
	if token, ok := auth.Authenticate("admin", "wrong"); ok || token != "" {
		t.Fatalf("wrong password accepted: token=%q ok=%v", token, ok)
	}
}

func TestAuthenticateWrongUsername(t *testing.T) {
	auth := NewAuthenticator("admin", "s3cret")
	if _, ok := auth.Authenticate("someone", "s3cret"); ok {
		t.Fatal("wrong username accepted")
	}
}

func TestValidateUnknownToken(t *testing.T) {
	auth := NewAuthenticator("admin", "s3cret")
	if auth.Validate("not-a-real-token") {
		t.Fatal("unknown token validated")
	}
	if auth.Validate("") {
		t.Fatal("empty token validated")
	}
}

// TestExpiredTokenRejected 过期 Token 必须失效；通过注入 now 推进时间验证。
func TestExpiredTokenRejected(t *testing.T) {
	auth := NewAuthenticator("admin", "s3cret")
	auth.ttl = time.Hour
	base := time.Now()
	auth.now = func() time.Time { return base }

	token := auth.Issue()
	if !auth.Validate(token) {
		t.Fatal("fresh token should validate")
	}
	// 推进到过期之后。
	auth.now = func() time.Time { return base.Add(2 * time.Hour) }
	if auth.Validate(token) {
		t.Fatal("expired token should not validate")
	}
}

// TestRotateInvalidatesOldAndIssuesNew Refresh 旋转：旧 Token 失效、新 Token
// 有效，且二者不同。
func TestRotateInvalidatesOldAndIssuesNew(t *testing.T) {
	auth := NewAuthenticator("admin", "s3cret")
	old := auth.Issue()
	newToken, ok := auth.Rotate(old)
	if !ok || newToken == "" {
		t.Fatalf("Rotate ok=%v new=%q, want success", ok, newToken)
	}
	if newToken == old {
		t.Fatal("rotated token should differ from old token")
	}
	if auth.Validate(old) {
		t.Fatal("old token should be invalidated after rotation")
	}
	if !auth.Validate(newToken) {
		t.Fatal("new token should validate")
	}
}

func TestRotateRejectsInvalidToken(t *testing.T) {
	auth := NewAuthenticator("admin", "s3cret")
	if _, ok := auth.Rotate("bogus"); ok {
		t.Fatal("rotating an invalid token should fail")
	}
}

func TestIssuedTokensAreUnique(t *testing.T) {
	auth := NewAuthenticator("admin", "s3cret")
	seen := make(map[string]struct{})
	for i := 0; i < 100; i++ {
		token := auth.Issue()
		if _, dup := seen[token]; dup {
			t.Fatalf("duplicate token issued: %s", token)
		}
		seen[token] = struct{}{}
	}
}

// TestAuthCookieRoundTrip 验证 setAuthCookie/tokenFromRequest 的 Cookie 名与
// HttpOnly 属性，以及从请求中读回 Token。
func TestAuthCookieRoundTrip(t *testing.T) {
	recorder := httptest.NewRecorder()
	setAuthCookie(recorder, "token-value", time.Hour)
	resp := recorder.Result()
	defer resp.Body.Close()

	cookies := resp.Cookies()
	if len(cookies) != 1 {
		t.Fatalf("expected 1 Set-Cookie, got %d", len(cookies))
	}
	cookie := cookies[0]
	if cookie.Name != AuthCookieName {
		t.Fatalf("cookie name = %q, want %q", cookie.Name, AuthCookieName)
	}
	if cookie.Value != "token-value" {
		t.Fatalf("cookie value = %q", cookie.Value)
	}
	if !cookie.HttpOnly {
		t.Fatal("auth cookie must be HttpOnly")
	}

	req, _ := http.NewRequest(http.MethodGet, "/", nil)
	req.AddCookie(&http.Cookie{Name: AuthCookieName, Value: "token-value"})
	if got := tokenFromRequest(req); got != "token-value" {
		t.Fatalf("tokenFromRequest = %q", got)
	}
	if got := tokenFromRequest(mustRequestNoCookie(t)); got != "" {
		t.Fatalf("tokenFromRequest without cookie = %q, want empty", got)
	}
}

func mustRequestNoCookie(t *testing.T) *http.Request {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, "/", nil)
	if err != nil {
		t.Fatalf("NewRequest: %v", err)
	}
	return req
}

// TestConcurrentRotateOnlyOneSucceeds 并发刷新同一 Token 时，原子旋转保证只有
// 一个请求成功，其余拿到 ok=false。
func TestConcurrentRotateOnlyOneSucceeds(t *testing.T) {
	auth := NewAuthenticator("admin", "s3cret")
	token := auth.Issue()

	const n = 16
	var wg sync.WaitGroup
	successes := make(chan bool, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, ok := auth.Rotate(token)
			successes <- ok
		}()
	}
	wg.Wait()
	close(successes)

	count := 0
	for ok := range successes {
		if ok {
			count++
		}
	}
	if count != 1 {
		t.Fatalf("concurrent rotate successes = %d, want exactly 1", count)
	}
	// 旧 Token 已失效。
	if auth.Validate(token) {
		t.Fatal("old token should be invalidated after rotation")
	}
}

func TestLoginLimiterBlocksAfterThreshold(t *testing.T) {
	limiter := NewLoginLimiter()
	ip := "192.168.1.50"
	for i := 0; i < 5; i++ {
		if !limiter.Allow(ip) {
			t.Fatalf("attempt %d should be allowed before threshold", i)
		}
		limiter.RecordFailure(ip)
	}
	if limiter.Allow(ip) {
		t.Fatal("should be blocked after 5 failures")
	}
	// 其它 IP 不受影响。
	if !limiter.Allow("10.0.0.9") {
		t.Fatal("unrelated IP should not be blocked")
	}
}

func TestLoginLimiterSuccessClears(t *testing.T) {
	limiter := NewLoginLimiter()
	ip := "192.168.1.51"
	for i := 0; i < 4; i++ {
		limiter.RecordFailure(ip)
	}
	limiter.RecordSuccess(ip)
	// 成功后计数清零：再失败 4 次仍未达阈值。
	for i := 0; i < 4; i++ {
		limiter.RecordFailure(ip)
	}
	if !limiter.Allow(ip) {
		t.Fatal("success should reset the failure counter")
	}
}

func TestLoginLimiterBanExpires(t *testing.T) {
	limiter := NewLoginLimiter()
	base := time.Now()
	limiter.now = func() time.Time { return base }
	ip := "192.168.1.52"
	for i := 0; i < 5; i++ {
		limiter.RecordFailure(ip)
	}
	if limiter.Allow(ip) {
		t.Fatal("should be blocked immediately after threshold")
	}
	// 推进到封禁期之后。
	limiter.now = func() time.Time { return base.Add(limiter.banDuration + time.Second) }
	if !limiter.Allow(ip) {
		t.Fatal("ban should expire after banDuration")
	}
}
