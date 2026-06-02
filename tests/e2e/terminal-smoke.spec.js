import { test } from "@playwright/test";
import { expectTerminalSized, expectTerminalText, openTerminal, sendDebugInput } from "./helpers.js";

test("login, create terminal, render xterm, and run a command", async ({ page }) => {
  await openTerminal(page);
  await sendDebugInput(page, "printf 'WEBTERM_OK\\n'\r");
  await expectTerminalText(page, "WEBTERM_OK");
  await expectTerminalSized(page);
});
