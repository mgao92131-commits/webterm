import { chromium } from '@playwright/test';

const baseURL = 'http://120.46.85.237:9001';
const user = { email: '3237324890@qq.com', password: '880713' };

async function run() {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext();
  await context.addInitScript(() => {
    window.localStorage.setItem('webtermDebug', '1');
  });
  const page = await context.newPage();

  const consoleLogs = [];
  page.on('console', (msg) => consoleLogs.push(`${msg.type()}: ${msg.text()}`));

  try {
    await page.goto(`${baseURL}/login`, { waitUntil: 'networkidle' });
    await page.fill('input[name="email"]', user.email);
    await page.fill('input[name="password"]', user.password);
    await page.click('button[type="submit"]');
    await page.waitForURL(/\/$/, { timeout: 10000 });

    // Wait for online device and create terminal
    await page.getByRole('button', { name: /mac mini/ }).waitFor({ timeout: 10000 });
    await page.getByRole('button', { name: /新建终端/ }).first().click();
    await page.waitForURL(/\/terminal\//, { timeout: 10000 });

    // Wait for websocket to open
    await page.waitForFunction(() => {
      return window.__webtermDebug?.wsState?.()?.readyState === WebSocket.OPEN;
    }, null, { timeout: 30000 });

    // Send a marker command
    const marker = `REMOTE_TEST_${Date.now()}`;
    await page.evaluate((value) => window.__webtermDebug.input(`printf '${value}\\n'`), marker);
    await page.waitForFunction((value) => {
      return window.__webtermDebug?.termState?.()?.text?.includes(value);
    }, marker, { timeout: 15000 });

    await page.screenshot({ path: '/tmp/remote-terminal-ok.png' });
    const text = await page.evaluate(() => window.__webtermDebug?.termState?.()?.text || '');
    console.log('SUCCESS: terminal rendered and received output');
    console.log('terminal text preview:', text.slice(0, 200));
  } catch (error) {
    await page.screenshot({ path: '/tmp/remote-terminal-fail.png' });
    const debug = await page.evaluate(() => ({
      wsState: window.__webtermDebug?.wsState?.() || null,
      termState: window.__webtermDebug?.termState?.() || null,
    })).catch(() => ({}));
    console.error('FAILED:', error.message);
    console.error('page url:', page.url());
    console.error('debug:', JSON.stringify(debug, null, 2));
    console.error('console logs:', consoleLogs.slice(-30).join('\n'));
    process.exit(1);
  } finally {
    await browser.close();
  }
}

run();
