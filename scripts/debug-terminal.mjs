// Debug script: open browser, login, check terminal page
import { chromium } from 'playwright';

const BASE = 'http://120.46.85.237:9000';

(async () => {
  const browser = await chromium.launch({ headless: false });
  const context = await browser.newContext();
  const page = await context.newPage();

  // Collect console errors
  const consoleErrors = [];
  page.on('console', msg => {
    if (msg.type() === 'error') consoleErrors.push(msg.text());
  });
  page.on('pageerror', err => consoleErrors.push(err.message));

  // Step 1: Navigate to home
  console.log('=== Step 1: Navigate to home ===');
  await page.goto(BASE, { waitUntil: 'networkidle' });
  await page.screenshot({ path: '/tmp/debug-01-home.png', fullPage: true });
  console.log('URL:', page.url());
  console.log('Title:', await page.title());

  // Check if redirected to login
  const currentUrl = page.url();
  if (currentUrl.includes('/login')) {
    console.log('Redirected to login page');

    // Step 2: Login
    console.log('=== Step 2: Login ===');

    // Try to find the login form
    const emailInput = page.locator('input[type="email"], input[type="text"], input[placeholder*="邮箱"], input[placeholder*="用户"], input[placeholder*="email"], input[placeholder*="user"]').first();
    const passwordInput = page.locator('input[type="password"]').first();
    const submitBtn = page.locator('button[type="submit"], button:has-text("登录"), button:has-text("Login"), button:has-text("Sign in")').first();

    console.log('Email input exists:', await emailInput.count());
    console.log('Password input exists:', await passwordInput.count());
    console.log('Submit button exists:', await submitBtn.count());

    // Try admin / test (from playwright.config.js)
    await emailInput.fill('admin');
    await passwordInput.fill('test');
    await page.screenshot({ path: '/tmp/debug-02-login-filled.png', fullPage: true });

    await submitBtn.click();
    await page.waitForTimeout(3000);
    await page.screenshot({ path: '/tmp/debug-03-after-login.png', fullPage: true });
    console.log('URL after login:', page.url());
  }

  // Step 3: Navigate to terminal if not already there
  console.log('=== Step 3: Terminal page ===');
  // Check if we have sessions/devices
  const pageContent = await page.content();
  console.log('Page has "本机" or device list:', pageContent.includes('本机') || pageContent.includes('device') || pageContent.includes('Device'));
  console.log('Page has "终端" or "Terminal":', pageContent.includes('终端') || pageContent.includes('Terminal'));

  // Try to click on a device or create session
  const deviceBtn = page.locator('button, a, div[role="button"]').filter({ hasText: /本机|MacMini|online|在线/ }).first();
  if (await deviceBtn.count() > 0) {
    console.log('Clicking device button...');
    await deviceBtn.click();
    await page.waitForTimeout(2000);
    await page.screenshot({ path: '/tmp/debug-04-device-selected.png', fullPage: true });
  }

  // Try to create a new session
  const newSessionBtn = page.locator('button').filter({ hasText: /新建|New|创建|plus|＋|\+/ }).first();
  if (await newSessionBtn.count() > 0) {
    console.log('Clicking new session button...');
    await newSessionBtn.click();
    await page.waitForTimeout(2000);
    await page.screenshot({ path: '/tmp/debug-05-new-session.png', fullPage: true });
    console.log('URL after new session:', page.url());
  }

  // Try to navigate to any terminal URL
  const sessionLinks = page.locator('a[href*="terminal"], [data-session-id], .session-item, .cursor-pointer').first();
  if (await sessionLinks.count() > 0) {
    console.log('Clicking session link...');
    await sessionLinks.click();
    await page.waitForTimeout(3000);
    await page.screenshot({ path: '/tmp/debug-06-terminal.png', fullPage: true });
    console.log('URL after clicking session:', page.url());
  }

  // Final state
  await page.screenshot({ path: '/tmp/debug-99-final.png', fullPage: true });
  console.log('\n=== Console Errors ===');
  consoleErrors.forEach(e => console.log('  ', e));

  console.log('\n=== Done. Screenshots saved to /tmp/debug-*.png ===');
  console.log('Current URL:', page.url());

  // Keep browser open for manual inspection
  console.log('Browser will stay open for 30s for inspection...');
  await page.waitForTimeout(30000);
  await browser.close();
})();
