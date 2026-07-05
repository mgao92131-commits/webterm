import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/e2e",
  timeout: 30_000,
  expect: {
    timeout: 10_000,
  },
  use: {
    baseURL: "http://127.0.0.1:18080",
    trace: "retain-on-failure",
  },
  webServer: {
    command: "npm start",
    env: {
      WEBTERM_USER: "admin",
      WEBTERM_PASSWORD: "test",
      WEBTERM_ADDR: "127.0.0.1:18080",
      WEBTERM_CONTROL_ADDR: "127.0.0.1:0",
    },
    url: "http://127.0.0.1:18080",
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
