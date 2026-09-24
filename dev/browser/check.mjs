// bb browser-check: walk the web UI in the local Chrome and fail on any
// console error or uncaught page error (SPEC.md §17 Phase 4: no JS errors).
// Expects dev/browser_server.clj running on BASE (default port 8765).
import { chromium } from "playwright-core";

const BASE = process.env.BASE ?? "http://127.0.0.1:8765";
const errors = [];

const browser = await chromium.launch({ channel: "chrome", headless: true });
const page = await browser.newPage();
page.on("console", (m) => {
  if (m.type() === "error") errors.push(`console: ${m.text()} @ ${page.url()}`);
});
page.on("pageerror", (e) => errors.push(`pageerror: ${e.message} @ ${page.url()}`));
page.on("response", (r) => {
  if (r.status() >= 400 && !r.url().includes("/docs/")) errors.push(`HTTP ${r.status()}: ${r.url()}`);
});

async function login(user) {
  await page.goto(`${BASE}/`);
  await page.waitForURL(/\/login/);
  await page.fill("input[name=username]", user);
  await page.fill("input[name=password]", `${user}-pw`);
  await Promise.all([page.waitForURL(`${BASE}/`), page.click("button[type=submit]")]);
}

async function step(name, fn) {
  try {
    await fn();
    console.log(`ok   ${name}`);
  } catch (e) {
    errors.push(`step "${name}" failed: ${e.message.split("\n")[0]}`);
    console.log(`FAIL ${name}`);
  }
}

try {
  await step("login alice", () => login("alice"));
  await step("ask with Debug on", async () => {
    await page.fill("textarea[name=query]", "特休天數怎麼計算？");
    await page.check("input[name=debug]");
    await page.click(`form[hx-post="/ask"] button[type=submit]`);
    await page.waitForSelector("[data-trace-id]", { timeout: 15000 });
  });
  await step("click citation [1]", async () => {
    await page.click('a[href="#src-1"]');
    await page.waitForSelector("#src-1");
  });
  await step("open cited document", async () => {
    await Promise.all([page.waitForURL(/\/docs\//), page.click("#src-1 a")]);
    await page.waitForSelector('[data-highlight="true"]');
  });
  await step("logout", async () => {
    await Promise.all([page.waitForURL(/\/login/), page.click("nav button[type=submit]")]);
  });
  await step("login admin", () => login("admin"));
  await step("admin: run ingest", async () => {
    await page.goto(`${BASE}/admin`);
    await page.click('button[hx-post="/admin/ingest"]');
    await page.waitForSelector("#ingest-status:has-text('完成')", { timeout: 30000 });
  });
  await step("admin: trace detail", async () => {
    await page.goto(`${BASE}/admin`);
    await Promise.all([page.waitForURL(/\/admin\/traces\//), page.click('a[href^="/admin/traces/"]')]);
  });
} finally {
  await browser.close();
}

if (errors.length) {
  console.error(`browser check FAILED (${errors.length}):`);
  for (const e of errors) console.error(`  ${e}`);
  process.exit(1);
}
console.log("browser check OK");
