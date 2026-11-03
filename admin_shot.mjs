import { chromium } from "playwright-core";
const browser = await chromium.launch({ args: ["--no-sandbox"] });
const ctx = await browser.newContext({ viewport: { width: 420, height: 780 }, deviceScaleFactor: 2 });
const page = await ctx.newPage();
await page.goto("https://tekbhatta663-source.github.io/tekbhatta-portfolio/admin/login/", { waitUntil: "networkidle" });
await page.waitForTimeout(1200);
await page.screenshot({ path: "/app/conversations/6add78e79883f31f19598ab4/admin_login.png" });
await browser.close();
console.log("ok");
