import { expect, test } from "@playwright/test";
import { expectTerminalSized, openTerminal } from "./helpers.js";

test("terminal keeps valid dimensions across desktop and mobile resizes", async ({ page }) => {
  await openTerminal(page);

  await page.setViewportSize({ width: 1280, height: 800 });
  await expectTerminalSized(page);

  await page.setViewportSize({ width: 390, height: 844 });
  await expectTerminalSized(page);

  const metrics = await page.evaluate(() => ({
    terminalHeight: document.querySelector("#terminal-container")?.getBoundingClientRect().height || 0,
    viewportHeight: getComputedStyle(document.documentElement).getPropertyValue("--viewport-height"),
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
  }));
  expect(metrics.terminalHeight).toBeGreaterThan(0);
  expect(metrics.viewportHeight.trim()).not.toBe("");
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.innerWidth);
});
