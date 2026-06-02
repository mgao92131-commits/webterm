import { expect } from "@playwright/test";

export async function openTerminal(page) {
  await page.goto("/");
  await page.evaluate(() => localStorage.setItem("webtermDebug", "1"));
  await page.reload();
  await page.getByLabel("密码").fill("test");
  await page.getByRole("button", { name: "登录" }).click();
  await page.getByRole("button", { name: "新建终端" }).click();
  await expect(page).toHaveURL(/\/terminal\/s\d+/);
  await expect(page.locator("#terminal .xterm")).toBeVisible();
  await waitForTerminalReady(page);
}

export async function waitForTerminalReady(page) {
  await page.evaluate(() => document.fonts?.ready);
  await expect.poll(async () => page.evaluate(() => window.__webtermDebug?.termState?.().cols || 0)).toBeGreaterThan(0);
  await expect.poll(async () => page.evaluate(() => window.__webtermDebug?.termState?.().rows || 0)).toBeGreaterThan(0);
  await expect.poll(async () => page.evaluate(() => window.__webtermDebug?.wsState?.().readyState)).toBe(1);
  await expect.poll(async () => page.evaluate(() => window.__webtermDebug?.wsState?.().restored)).toBe(true);
}

export async function sendDebugInput(page, input) {
  await page.evaluate((value) => window.__webtermDebug.input(value), input);
}

export async function terminalText(page) {
  return page.evaluate(() => window.__webtermDebug.termState().text);
}

export async function expectTerminalText(page, text) {
  await expect.poll(async () => terminalText(page)).toContain(text);
}

export async function expectTerminalSized(page) {
  await expect.poll(async () => page.evaluate(() => window.__webtermDebug?.termState?.().cols || 0)).toBeGreaterThan(0);
  await expect.poll(async () => page.evaluate(() => window.__webtermDebug?.termState?.().rows || 0)).toBeGreaterThan(0);
  const box = await page.locator("#terminal .xterm-screen").boundingBox();
  expect(box?.width || 0).toBeGreaterThan(0);
  expect(box?.height || 0).toBeGreaterThan(0);
}
