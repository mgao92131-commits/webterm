import { expect, test } from "@playwright/test";
import { expectTerminalText, openTerminal, sendDebugInput, waitForTerminalReady } from "./helpers.js";

test("re-entering a terminal does not duplicate terminal page lifecycle resources", async ({ page }) => {
  await openTerminal(page);
  await page.waitForTimeout(250);
  const baseline = await lifecycle(page);
  expect(baseline.hasTerminalView).toBe(true);
  expect(baseline.hasInputController).toBe(true);
  expect(baseline.hasLayoutController).toBe(true);
  expect(baseline.hasSelectionController).toBe(true);
  expect(baseline.disposables).toBeGreaterThan(0);

  for (let index = 0; index < 3; index += 1) {
    await page.getByRole("link", { name: "返回" }).click();
    await page.locator(".session-link").first().click();
    await waitForTerminalReady(page);
    await page.waitForTimeout(250);
    const current = await lifecycle(page);
    expect(current.disposables).toBe(baseline.disposables);
    expect(current.wsReadyState).toBe(1);
  }

  await sendDebugInput(page, "printf 'ONE_INPUT\\n'\r");
  await expectTerminalText(page, "ONE_INPUT");
});

async function lifecycle(page) {
  return page.evaluate(() => window.__webtermDebug.lifecycleState());
}
