import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const failures = [];

function read(relativePath) {
  const filePath = path.join(repoRoot, relativePath);
  try {
    return fs.readFileSync(filePath, 'utf8');
  } catch (error) {
    failures.push(`${relativePath}: ${error.message}`);
    return '';
  }
}

function requireIncludes(label, text, needle) {
  if (!text.includes(needle)) {
    failures.push(`${label}: missing ${needle}`);
  }
}

function requireMatch(label, text, pattern, message = String(pattern)) {
  if (!pattern.test(text)) {
    failures.push(`${label}: missing ${message}`);
  }
}

const nginx = read('nginx.conf');
requireIncludes('nginx.conf', nginx, 'location /api/');
requireIncludes('nginx.conf', nginx, 'location /ws/');
requireIncludes('nginx.conf', nginx, 'proxy_pass http://go-relay:19090');
requireIncludes('nginx.conf', nginx, 'proxy_set_header Upgrade $http_upgrade;');
requireIncludes('nginx.conf', nginx, 'proxy_set_header Connection "upgrade";');
requireIncludes('nginx.conf', nginx, 'proxy_set_header Sec-WebSocket-Protocol $http_sec_websocket_protocol;');
requireIncludes('nginx.conf', nginx, 'try_files $uri $uri/ /index.html;');

const compose = read('docker-compose.yml');
for (const required of [
  'dockerfile: Dockerfile.go-relay',
  'WEBTERM_RELAY_ADDR=0.0.0.0:19090',
  'WEBTERM_RELAY_STORE_PATH=/app/data/relay-store.json',
  'WEBTERM_RELAY_PUBLIC_URL=${WEBTERM_RELAY_PUBLIC_URL:-}',
  'WEBTERM_RELAY_ALLOW_REGISTRATION=${WEBTERM_RELAY_ALLOW_REGISTRATION:-1}',
  'WEBTERM_RELAY_REQUIRE_EMAIL_OTP=${WEBTERM_RELAY_REQUIRE_EMAIL_OTP:-0}',
  'WEBTERM_RELAY_DEV_PRINT_OTP=${WEBTERM_RELAY_DEV_PRINT_OTP:-0}',
  'WEBTERM_RELAY_SMTP_HOST=${WEBTERM_RELAY_SMTP_HOST:-}',
  'WEBTERM_RELAY_SMTP_PORT=${WEBTERM_RELAY_SMTP_PORT:-}',
  'WEBTERM_RELAY_SMTP_USERNAME=${WEBTERM_RELAY_SMTP_USERNAME:-}',
  'WEBTERM_RELAY_SMTP_PASSWORD=${WEBTERM_RELAY_SMTP_PASSWORD:-}',
  'WEBTERM_RELAY_SMTP_FROM=${WEBTERM_RELAY_SMTP_FROM:-}',

  'WEBTERM_RELAY_BOOTSTRAP_USER=${RELAY_BOOTSTRAP_USER:-admin}',
  'WEBTERM_RELAY_BOOTSTRAP_PASSWORD=${RELAY_BOOTSTRAP_PASSWORD:?required}',
  './web:/app/web:ro',
  './nginx.conf:/etc/nginx/conf.d/default.conf:ro',
]) {
  requireIncludes('docker-compose.yml', compose, required);
}

const dockerfile = read('Dockerfile.go-relay');
requireIncludes('Dockerfile.go-relay', dockerfile, 'FROM golang:');
requireIncludes('Dockerfile.go-relay', dockerfile, 'go mod download');
requireIncludes('Dockerfile.go-relay', dockerfile, 'go build');
requireIncludes('Dockerfile.go-relay', dockerfile, './cmd/webterm-relay');
requireIncludes('Dockerfile.go-relay', dockerfile, 'EXPOSE 19090');
requireIncludes('Dockerfile.go-relay', dockerfile, 'CMD ["./webterm-relay"]');

const deploy = read('deploy.sh');
requireIncludes('deploy.sh', deploy, 'RELAY_BOOTSTRAP_PASSWORD');
requireIncludes('deploy.sh', deploy, '不能使用默认管理员密码部署');
requireIncludes('deploy.sh', deploy, '--dry-run');
requireIncludes('deploy.sh', deploy, 'dry-run: 不打包、不上传、不执行 SSH。');
requireIncludes('deploy.sh', deploy, 'docker compose up -d --build');
requireIncludes('deploy.sh', deploy, 'WEBTERM_RELAY_PUBLIC_URL');

requireMatch('deploy.sh', deploy, /if \[ -z "\$\{RELAY_BOOTSTRAP_PASSWORD:-\}" \]/, 'bootstrap password guard');
if (/RELAY_BOOTSTRAP_PASSWORD=changeme/.test(deploy)) {
  failures.push('deploy.sh: must not deploy with RELAY_BOOTSTRAP_PASSWORD=changeme');
}

const webIndex = path.join(repoRoot, 'web/index.html');
if (!fs.existsSync(webIndex)) {
  failures.push('web/index.html: missing built frontend; run npm run build before deploy');
}

if (failures.length > 0) {
  console.error('go relay deploy config validation failed:');
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exit(1);
}

console.log('go relay deploy config validation ok');
