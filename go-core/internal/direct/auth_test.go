package direct

import (
	"net/http"
	"net/http/httptest"
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
