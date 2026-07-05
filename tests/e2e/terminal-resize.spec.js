import { expect, test } from "@playwright/test";
import { expectTerminalSized, openTerminal, sendDebugInput, waitForLayoutSettle } from "./helpers.js";

test("terminal keeps valid dimensions across desktop and mobile resizes", async ({ page }) => {
  await openTerminal(page);
  await waitForLayoutSettle(page);

  await page.setViewportSize({ width: 1280, height: 800 });
  await expectTerminalSized(page);
  await waitForLayoutSettle(page);
  const tallState = await page.evaluate(() => ({
    rows: window.__webtermDebug.termState().rows,
    resizeMessageCount: window.__webtermDebug.layoutState().resizeMessageCount,
  }));

  await page.setViewportSize({ width: 1280, height: 520 });
  await expect.poll(async () => page.evaluate(() => {
    const raw = getComputedStyle(document.documentElement).getPropertyValue("--terminal-keyboard-shift");
    return parseInt(raw, 10) || 0;
  })).toBe(0);
  expect(await page.evaluate(() => window.__webtermDebug.termState().rows)).toBe(tallState.rows);
  expect(await page.evaluate(() => window.__webtermDebug.layoutState().resizeMessageCount)).toBe(tallState.resizeMessageCount);

  await page.setViewportSize({ width: 1280, height: 800 });
  await expect.poll(async () => page.evaluate(() => {
    const raw = getComputedStyle(document.documentElement).getPropertyValue("--terminal-keyboard-shift");
    return parseInt(raw, 10) || 0;
  })).toBe(0);
  await sendDebugInput(page, "for i in $(seq 1 80); do echo DESKTOP_SHIFT_$i; done\n");
  await expect.poll(async () => page.evaluate(() => window.__webtermDebug.termState().text)).toContain("DESKTOP_SHIFT_80");
  await page.waitForTimeout(250);
  await waitForLayoutSettle(page);

  const bottomState = await page.evaluate(() => ({
    rows: window.__webtermDebug.termState().rows,
    resizeMessageCount: window.__webtermDebug.layoutState().resizeMessageCount,
  }));

  await page.setViewportSize({ width: 1280, height: 520 });
  await waitForLayoutSettle(page);
  expect(await page.evaluate(() => window.__webtermDebug.termState().rows)).toBe(bottomState.rows);
  expect(await page.evaluate(() => window.__webtermDebug.layoutState().resizeMessageCount)).toBe(bottomState.resizeMessageCount);
  await page.evaluate(() => {
    window.__webtermDebug.scrollToLine(999999);
    window.__webtermDebug.keyboardAvoidance();
  });

  await expect.poll(async () => page.evaluate(() => {
    const raw = getComputedStyle(document.documentElement).getPropertyValue("--terminal-keyboard-shift");
    return parseInt(raw, 10) || 0;
  })).toBeGreaterThan(0);
  await expect.poll(async () => page.evaluate(() => {
    const terminalBottom = document.querySelector("#terminal-container")?.getBoundingClientRect().bottom || 0;
    const quickbarTop = document.querySelector(".quickbar")?.getBoundingClientRect().top || 0;
    const quickbarBottom = document.querySelector(".quickbar")?.getBoundingClientRect().bottom || 0;
    const visibleBottom = window.visualViewport?.height || window.innerHeight;
    return {
      terminalAboveViewport: terminalBottom <= visibleBottom + 1,
      quickbarVisible: quickbarTop >= 0 && quickbarBottom <= visibleBottom + 1,
    };
  })).toEqual({ terminalAboveViewport: true, quickbarVisible: true });

  await page.setViewportSize({ width: 390, height: 844 });
  await waitForLayoutSettle(page);
  await expectTerminalSized(page);

  await expect.poll(async () => page.evaluate(() => {
    const terminalBottom = document.querySelector("#terminal-container")?.getBoundingClientRect().bottom || 0;
    const quickbarTop = document.querySelector(".quickbar")?.getBoundingClientRect().top || 0;
    return Math.abs(quickbarTop - terminalBottom);
  })).toBeLessThanOrEqual(1);

  const metrics = await page.evaluate(() => ({
    pageHeight: document.querySelector(".terminal-page")?.getBoundingClientRect().height || 0,
    pageBottom: document.querySelector(".terminal-page")?.getBoundingClientRect().bottom || 0,
    terminalHeight: document.querySelector("#terminal-container")?.getBoundingClientRect().height || 0,
    terminalBottom: document.querySelector("#terminal-container")?.getBoundingClientRect().bottom || 0,
    quickbarTop: document.querySelector(".quickbar")?.getBoundingClientRect().top || 0,
    quickbarHeight: document.querySelector(".quickbar")?.getBoundingClientRect().height || 0,
    quickbarHeightVar: getComputedStyle(document.documentElement).getPropertyValue("--quickbar-height"),
    viewportHeight: getComputedStyle(document.documentElement).getPropertyValue("--viewport-height"),
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
    innerHeight: window.innerHeight,
  }));
  expect(metrics.terminalHeight).toBeGreaterThan(0);
  expect(Math.abs(metrics.quickbarTop - metrics.terminalBottom)).toBeLessThanOrEqual(1);
  expect(metrics.quickbarHeightVar.trim()).toBe(`${Math.ceil(metrics.quickbarHeight)}px`);
  expect(metrics.viewportHeight.trim()).not.toBe("");
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.innerWidth);
});
