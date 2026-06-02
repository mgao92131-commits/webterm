import { test } from "@playwright/test";
import { expectTerminalText, openTerminal, sendDebugInput, waitForTerminalReady } from "./helpers.js";

test("terminal renders large output without going blank", async ({ page }) => {
  await openTerminal(page);
  await sendDebugInput(page, "seq 1 5000\r");
  await expectTerminalText(page, "5000");
});

test("terminal restores output after page reload and continues accepting input", async ({ page }) => {
  await openTerminal(page);
  await sendDebugInput(page, "printf 'BEFORE_RELOAD\\n'\r");
  await expectTerminalText(page, "BEFORE_RELOAD");

  await page.reload();
  await waitForTerminalReady(page);
  await expectTerminalText(page, "BEFORE_RELOAD");

  await sendDebugInput(page, "printf 'AFTER_RELOAD\\n'\r");
  await expectTerminalText(page, "AFTER_RELOAD");
});
