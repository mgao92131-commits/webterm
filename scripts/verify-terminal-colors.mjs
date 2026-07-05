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

  try {
    await page.goto(`${baseURL}/login`, { waitUntil: 'networkidle' });
    await page.fill('input[name="email"]', user.email);
    await page.fill('input[name="password"]', user.password);
    await page.click('button[type="submit"]');
    await page.waitForURL(/\/$/, { timeout: 10000 });

    await page.getByRole('button', { name: /mac mini/ }).waitFor({ timeout: 10000 });
    await page.getByRole('button', { name: /新建终端/ }).first().click();
    await page.waitForURL(/\/terminal\//, { timeout: 10000 });

    await page.waitForFunction(() => {
      return window.__webtermDebug?.wsState?.()?.readyState === WebSocket.OPEN;
    }, null, { timeout: 30000 });

    const commands = [
      "echo -e '\\e[31mRED\\e[0m \\e[32mGREEN\\e[0m \\e[34mBLUE\\e[0m'",
      "ls",
      "alias ls",
      "type ls",
    ];
    for (const cmd of commands) {
      await page.evaluate((c) => window.__webtermDebug.input(`${c}\n`), cmd);
      await page.waitForTimeout(1500);
    }

    await page.screenshot({ path: '/tmp/terminal-colors.png' });
    const text = await page.evaluate(() => window.__webtermDebug?.termState?.()?.text || '');
    console.log('terminal text:', text);
  } catch (error) {
    await page.screenshot({ path: '/tmp/terminal-colors-fail.png' });
    console.error('FAILED:', error.message);
    process.exit(1);
  } finally {
    await browser.close();
  }
}

run();
