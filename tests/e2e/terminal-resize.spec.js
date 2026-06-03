import { expect, test } from "@playwright/test";
import { expectTerminalSized, openTerminal } from "./helpers.js";

test("terminal keeps valid dimensions across desktop and mobile resizes", async ({ page }) => {
  await openTerminal(page);

  await page.setViewportSize({ width: 1280, height: 800 });
  await expectTerminalSized(page);

  await page.setViewportSize({ width: 390, height: 844 });
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
