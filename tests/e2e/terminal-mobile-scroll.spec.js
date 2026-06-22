import { expect, test } from "@playwright/test";
import { openTerminal, sendDebugInput } from "./helpers.js";

test("mobile terminal touch scrolls history and keyboard state creates page scroll room", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openTerminal(page);

  const sparseBeforeKeyboard = await page.evaluate(() => ({
    rows: window.__webtermDebug.termState().rows,
    resizeMessageCount: window.__webtermDebug.layoutState().resizeMessageCount,
  }));

  await page.evaluate(() => {
    Object.defineProperty(navigator, "maxTouchPoints", {
      configurable: true,
      value: 1,
    });
    Object.defineProperty(window, "visualViewport", {
      configurable: true,
      value: {
        height: 420,
        offsetTop: 0,
        scale: 1,
        addEventListener() {},
        removeEventListener() {},
      },
    });
    window.dispatchEvent(new Event("resize"));
    window.__webtermDebug.keyboardAvoidance();
  });

  await expect.poll(async () => page.evaluate(() => {
    const raw = getComputedStyle(document.documentElement).getPropertyValue("--terminal-keyboard-shift");
    return parseInt(raw, 10) || 0;
  })).toBe(0);
  expect(await page.evaluate(() => window.__webtermDebug.termState().rows)).toBe(sparseBeforeKeyboard.rows);
  expect(await page.evaluate(() => window.__webtermDebug.layoutState().resizeMessageCount)).toBe(sparseBeforeKeyboard.resizeMessageCount);

  await page.evaluate(() => {
    Object.defineProperty(window, "visualViewport", {
      configurable: true,
      value: {
        height: 844,
        offsetTop: 0,
        scale: 1,
        addEventListener() {},
        removeEventListener() {},
      },
    });
    window.dispatchEvent(new Event("resize"));
    window.__webtermDebug.keyboardAvoidance();
  });
  await expect.poll(async () => page.evaluate(() => {
    const raw = getComputedStyle(document.documentElement).getPropertyValue("--terminal-keyboard-shift");
    return parseInt(raw, 10) || 0;
  })).toBe(0);

  await sendDebugInput(page, "for i in $(seq 1 80); do echo MOBILE_SCROLL_$i; done\n");
  await expect.poll(async () => page.evaluate(() => window.__webtermDebug.termState().text)).toContain("MOBILE_SCROLL_80");

  await page.evaluate(() => window.__webtermDebug.scrollToLine(999999));
  const before = await page.evaluate(() => window.__webtermDebug.scroll().viewportY);

  await page.dispatchEvent("#terminal-container", "touchstart", {
    touches: [{ identifier: 1, clientX: 180, clientY: 250 }],
    changedTouches: [{ identifier: 1, clientX: 180, clientY: 250 }],
  });
  await page.dispatchEvent("#terminal-container", "touchmove", {
    touches: [{ identifier: 1, clientX: 180, clientY: 650 }],
    changedTouches: [{ identifier: 1, clientX: 180, clientY: 650 }],
  });
  await page.dispatchEvent("#terminal-container", "touchend", {
    touches: [],
    changedTouches: [{ identifier: 1, clientX: 180, clientY: 650 }],
  });

  await expect.poll(async () => page.evaluate(() => window.__webtermDebug.scroll().viewportY)).toBeLessThan(before);

  const beforeKeyboard = await page.evaluate(() => ({
    rows: window.__webtermDebug.termState().rows,
    resizeMessageCount: window.__webtermDebug.layoutState().resizeMessageCount,
  }));

  await page.evaluate(() => {
    Object.defineProperty(navigator, "maxTouchPoints", {
      configurable: true,
      value: 1,
    });
    Object.defineProperty(window, "visualViewport", {
      configurable: true,
      value: {
        height: 420,
        offsetTop: 0,
        scale: 1,
        addEventListener() {},
        removeEventListener() {},
      },
    });
    window.__webtermDebug.scrollToLine(999999);
    window.dispatchEvent(new Event("resize"));
    window.__webtermDebug.keyboardAvoidance();
  });

  await expect.poll(async () => page.evaluate(() => {
    const raw = getComputedStyle(document.documentElement).getPropertyValue("--viewport-height");
    return parseInt(raw, 10) || 0;
  })).toBe(844);

  await expect.poll(async () => page.evaluate(() => {
    const raw = getComputedStyle(document.documentElement).getPropertyValue("--terminal-keyboard-shift");
    return parseInt(raw, 10) || 0;
  })).toBeGreaterThan(0);
  expect(await page.evaluate(() => window.__webtermDebug.termState().rows)).toBe(beforeKeyboard.rows);
  expect(await page.evaluate(() => window.__webtermDebug.layoutState().resizeMessageCount)).toBe(beforeKeyboard.resizeMessageCount);

  const keyboardLayout = await page.evaluate(() => {
    const pageEl = document.querySelector(".terminal-page");
    const quickbar = document.querySelector(".quickbar");
    const quickbarRect = quickbar.getBoundingClientRect();
    const terminalRect = document.querySelector("#terminal-container").getBoundingClientRect();
    const shift = parseInt(getComputedStyle(document.documentElement).getPropertyValue("--terminal-keyboard-shift"), 10) || 0;
    return {
      pageClientHeight: pageEl.clientHeight,
      rows: window.__webtermDebug.termState().rows,
      lastResizeMessage: window.__webtermDebug.layoutState().lastResizeMessage,
      quickbarTop: quickbarRect.top,
      quickbarBottom: quickbarRect.bottom,
      terminalBottom: terminalRect.bottom,
      terminalTop: terminalRect.top,
      shift,
    };
  });

  expect(keyboardLayout.pageClientHeight).toBe(844);
  expect(keyboardLayout.lastResizeMessage.rows).toBe(beforeKeyboard.rows);
  expect(keyboardLayout.quickbarBottom).toBeLessThanOrEqual(421);
  expect(keyboardLayout.quickbarTop).toBeGreaterThan(300);
  expect(Math.abs(keyboardLayout.quickbarTop - keyboardLayout.terminalBottom)).toBeLessThanOrEqual(1);
  expect(keyboardLayout.terminalTop).toBeLessThan(0);
  expect(keyboardLayout.shift).toBeGreaterThan(0);
});
