import { expect, test } from "@playwright/test";
import { expectTerminalText, openTerminal, sendDebugInput } from "./helpers.js";

test("selection mode copies selected terminal text and restores input", async ({ context, page }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await openTerminal(page);
  await sendDebugInput(page, "printf 'COPY_ME\\n'\r");
  await expectTerminalText(page, "COPY_ME");

  await page.getByRole("button", { name: "选择" }).click();
  await expect(page.getByRole("button", { name: "拷贝" })).toBeVisible();
  await expect.poll(async () => page.evaluate(() => window.__webtermDebug.selectText("COPY_ME"))).toContain("COPY_ME");
  await page.getByRole("button", { name: "拷贝" }).click();

  await expect.poll(async () => page.evaluate(() => navigator.clipboard.readText())).toContain("COPY_ME");
  await expect(page.getByRole("button", { name: "选择" })).toBeVisible();

  await sendDebugInput(page, "printf 'AFTER_COPY\\n'\r");
  await expectTerminalText(page, "AFTER_COPY");
});
