package relaycontrol

import (
	"crypto/tls"
	"errors"
	"fmt"
	"net/smtp"
	"os"
	"strconv"
	"strings"
)

type envOTPSender struct{}

func (envOTPSender) SendOTP(toEmail string, purpose string, code string) error {
	if os.Getenv("WEBTERM_RELAY_DEV_PRINT_OTP") == "1" {
		fmt.Printf("[Relay OTP] email=%s purpose=%s code=%s\n", toEmail, purpose, code)
		return nil
	}
	cfg := smtpConfigFromEnv()
	if !cfg.configured() {
		return errors.New("otp delivery is not configured")
	}
	return sendSMTPOTP(cfg, toEmail, purpose, code)
}

type smtpConfig struct {
	host      string
	port      int
	username  string
	password  string
	from      string
	publicURL string
}

// SMTPConfig is the file/env configuration consumed by the Relay command.
// Keeping it exported lets relayapp pass the selected configuration to the
// control plane without reconstructing process environment variables.
type SMTPConfig struct {
	Host      string `json:"host,omitempty"`
	Port      int    `json:"port,omitempty"`
	Username  string `json:"username,omitempty"`
	Password  string `json:"password,omitempty"`
	From      string `json:"from,omitempty"`
	PublicURL string `json:"publicUrl,omitempty"`
}

func (cfg SMTPConfig) configured() bool {
	return cfg.Host != "" && cfg.Port > 0 && cfg.Username != "" && cfg.Password != "" && cfg.From != ""
}

func (cfg SMTPConfig) Configured() bool { return cfg.configured() }

type configuredOTPSender struct {
	config   SMTPConfig
	devPrint bool
}

func (sender configuredOTPSender) SendOTP(toEmail string, purpose string, code string) error {
	if sender.devPrint {
		fmt.Printf("[Relay OTP] email=%s purpose=%s code=%s\n", toEmail, purpose, code)
		return nil
	}
	if !sender.config.configured() {
		return errors.New("otp delivery is not configured")
	}
	return sendSMTPOTP(smtpConfig{
		host: sender.config.Host, port: sender.config.Port, username: sender.config.Username,
		password: sender.config.Password, from: sender.config.From, publicURL: sender.config.PublicURL,
	}, toEmail, purpose, code)
}

func smtpConfigFromEnv() smtpConfig {
	port, _ := strconv.Atoi(firstEnv("WEBTERM_RELAY_SMTP_PORT", "SMTP_PORT"))
	return smtpConfig{
		host:      firstEnv("WEBTERM_RELAY_SMTP_HOST", "SMTP_HOST"),
		port:      port,
		username:  firstEnv("WEBTERM_RELAY_SMTP_USERNAME", "SMTP_USER"),
		password:  firstEnv("WEBTERM_RELAY_SMTP_PASSWORD", "SMTP_PASS"),
		from:      firstEnv("WEBTERM_RELAY_SMTP_FROM", "SMTP_FROM"),
		publicURL: firstEnv("WEBTERM_RELAY_PUBLIC_URL", "PUBLIC_URL"),
	}
}

func (cfg smtpConfig) configured() bool {
	return cfg.host != "" && cfg.port > 0 && cfg.username != "" && cfg.password != "" && cfg.from != ""
}

func sendSMTPOTP(cfg smtpConfig, toEmail string, purpose string, code string) error {
	addr := fmt.Sprintf("%s:%d", cfg.host, cfg.port)
	auth := smtp.PlainAuth("", cfg.username, cfg.password, cfg.host)
	subject := "WebTerm verification code"
	if purpose == newDevicePurpose {
		subject = "WebTerm new device verification code"
	}
	body := fmt.Sprintf("Your WebTerm verification code is: %s\n\nThis code expires in 10 minutes.", code)
	if cfg.publicURL != "" {
		body += "\n\nWebTerm: " + cfg.publicURL
	}
	message := strings.Join([]string{
		"From: " + cfg.from,
		"To: " + toEmail,
		"Subject: " + subject,
		"MIME-Version: 1.0",
		"Content-Type: text/plain; charset=UTF-8",
		"",
		body,
	}, "\r\n")
	if cfg.port == 465 {
		return sendSMTPImplicitTLS(addr, cfg.host, auth, cfg.from, []string{toEmail}, []byte(message))
	}
	return smtp.SendMail(addr, auth, cfg.from, []string{toEmail}, []byte(message))
}

func sendSMTPImplicitTLS(addr string, host string, auth smtp.Auth, from string, to []string, msg []byte) error {
	conn, err := tls.Dial("tcp", addr, &tls.Config{ServerName: host})
	if err != nil {
		return err
	}
	defer conn.Close()
	client, err := smtp.NewClient(conn, host)
	if err != nil {
		return err
	}
	defer client.Quit()
	if err := client.Auth(auth); err != nil {
		return err
	}
	if err := client.Mail(from); err != nil {
		return err
	}
	for _, recipient := range to {
		if err := client.Rcpt(recipient); err != nil {
			return err
		}
	}
	writer, err := client.Data()
	if err != nil {
		return err
	}
	if _, err := writer.Write(msg); err != nil {
		_ = writer.Close()
		return err
	}
	return writer.Close()
}

func firstEnv(names ...string) string {
	for _, name := range names {
		if value := strings.TrimSpace(os.Getenv(name)); value != "" {
			return value
		}
	}
	return ""
}
