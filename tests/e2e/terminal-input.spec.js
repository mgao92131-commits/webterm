import { expect, test } from "@playwright/test";
import { openTerminal, sendDebugInput, terminalText } from "./helpers.js";

test("quickbar Ctrl C interrupts a running command", async ({ page }) => {
  await openTerminal(page);
  await sendDebugInput(page, "sleep 10; printf 'SLEEP_DONE\\n'\r");

  await page.getByRole("button", { name: "Ctrl C" }).click();
  await sendDebugInput(page, "printf 'AFTER_CTRL_C\\n'\r");

  await expect.poll(async () => terminalText(page), { timeout: 3000 }).toContain("AFTER_CTRL_C");
  expect(await terminalText(page)).toContain("^C");
});
