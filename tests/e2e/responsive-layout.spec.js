import { test, expect } from "@playwright/test";
import { openTerminal } from "./helpers.js";

test("responsive layout adapts session list and terminal across breakpoints", async ({ page }) => {
  await openTerminal(page);

  // Desktop: session list + terminal visible
  await expect(page.getByTestId("session-list-panel")).toBeVisible();
  await expect(page.getByTestId("terminal-panel")).toBeVisible();

  // Tablet: session list + terminal still visible
  await page.setViewportSize({ width: 800, height: 600 });
  await expect(page.getByTestId("session-list-panel")).toBeVisible();
  await expect(page.getByTestId("terminal-panel")).toBeVisible();

  // Mobile: terminal goes full-screen over session list
  await page.setViewportSize({ width: 375, height: 667 });
  await expect(page.getByTestId("terminal-panel")).toBeVisible();

  // Back button returns to session list
  await page.getByRole("button", { name: "返回" }).click();
  await expect(page.getByTestId("session-list-panel")).toBeVisible();
  await expect(page.getByTestId("terminal-panel")).toBeHidden();
});
