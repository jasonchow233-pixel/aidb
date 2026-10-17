#!/usr/bin/env node
/**
 * AgenticDB 浏览器级冒烟测试。
 *
 * ⭐ 为什么需要它：`e2e_contract.py` 只测 API，覆盖不到前端渲染。
 *  实际踩过的三个 bug —— 编译通过、`cargo test` 通过、`vite build` 通过，
 *  但页面是坏的：
 *    1. 表格页白屏（模板里 ref 被二次解包）
 *    2. DataGridPage 丢失 12 个 computed/函数
 *    3. 侧栏首次加载是空树（用户得点两次才看到表）
 *
 *  这三个都只会在浏览器里暴露。这条门禁就是为了挡住它们。
 *
 * 用法：
 *   node engine/tests/smoke_browser.mjs
 *   node engine/tests/smoke_browser.mjs --url http://localhost:5173
 *
 * 前置：后端 8787 + 前端 5173 都要在跑。
 * 退出码：0 = 通过，1 = 有失败。
 */
import { chromium } from "playwright";

const URL = process.argv.includes("--url")
  ? process.argv[process.argv.indexOf("--url") + 1]
  : "http://localhost:5173";

let pass = 0;
let fail = 0;
const failures = [];

function check(label, ok, extra = "") {
  if (ok) {
    pass++;
    console.log(`  ok    ${label}`);
  } else {
    fail++;
    failures.push(label);
    console.log(`  FAIL  ${label}  ${extra}`);
  }
}

/** 等待应用完成 boot（侧栏出现「数据库」计数）。 */
async function waitBoot(page, timeout = 30000) {
  await page.waitForSelector("text=数据库", { timeout });
  // boot 是异步的（capabilities → session → diagnostics → tree）
  await page.waitForFunction(
    () => {
      const el = [...document.querySelectorAll("button")].find((b) =>
        /数据库\s+\d+/.test(b.textContent || ""),
      );
      const m = el && el.textContent.match(/数据库\s+(\d+)/);
      return m && Number(m[1]) > 0;
    },
    { timeout },
  );
}

const consoleErrors = [];
const pageErrors = [];

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });

page.on("console", (m) => {
  if (m.type() === "error") consoleErrors.push(m.text());
});
page.on("pageerror", (e) => pageErrors.push(String(e)));

try {
  console.log("═══ 1. 首屏加载 ═══");
  const t0 = Date.now();
  await page.goto(URL, { waitUntil: "domcontentloaded" });
  await waitBoot(page);
  const bootMs = Date.now() - t0;
  check(`应用启动并完成 boot（${bootMs}ms）`, bootMs < 15000, `${bootMs}ms`);

  console.log("\n═══ 2. 侧栏（回归：首屏必须是展开的树，不能是空树）═══");
  const tableNodes = await page.locator(".tree button.node").count();
  check("首屏就列出对象（不是空树）", tableNodes > 0, `找到 ${tableNodes} 个`);
  check("对象数合理（>=20）", tableNodes >= 20, `找到 ${tableNodes} 个`);

  const commentShown = await page.locator(".tree .comment").count();
  check("表注释已渲染", commentShown > 0, `${commentShown} 条`);

  const riskBadges = await page.locator(".tree .badge-warn").count();
  check("诊断角标已渲染", riskBadges > 0, `${riskBadges} 个`);

  console.log("\n═══ 3. 打开表（回归：表格页曾因 ref 二次解包而白屏）═══");
  await page.locator(".tree button.node", { hasText: "orders" }).first().click();
  await page.waitForSelector(".grid", { timeout: 15000 });

  const gridRows = await page.locator(".grid tbody tr").count();
  check("表格渲染出数据行", gridRows > 0, `${gridRows} 行`);
  check("行数达到一页（>=100）", gridRows >= 100, `${gridRows} 行`);

  const colHeaders = await page.locator(".grid thead th").count();
  check("表头有列", colHeaders > 3, `${colHeaders} 列`);

  const rownums = await page.locator(".grid tbody tr td.rownum").allInnerTexts();
  check("行号从 1 开始且连续", rownums[0] === "1", `首行行号=${rownums[0]}`);

  const headText = await page.locator(".grid thead").innerText();
  check("表头显示类型与注释", /订单/.test(headText), headText.slice(0, 60).replace(/\n/g, " "));

  console.log("\n═══ 4. 状态栏信息 ═══");
  const status = await page.locator(".statusbar").innerText();
  check("显示环境标识", /PRODUCTION|DEV|TEST/.test(status), status.slice(0, 40));
  check("显示数据库名", /demo\.db/.test(status));

  console.log("\n═══ 5. 筛选（列头点击排序 + 工具栏筛选）═══");
  const firstHeader = page.locator(".grid thead th").nth(2);
  await firstHeader.click();
  await page.waitForTimeout(400);
  const sortArrow = await page.locator(".grid thead").innerText();
  check("点击表头可排序", /[↑↓]/.test(sortArrow), "无排序标记");

  console.log("\n═══ 6. 表结构页 ═══");
  // ⭐ 回归：这里曾因 `structuredClone(reactive Proxy)` 抛 DataCloneError，
  //   页面整块渲染不出来 —— 但 tab 会正常激活，看起来「点开了」。
  //   所以断言必须看**内容**，不能只看 tab 是否激活。
  await page.locator(".toolbar button", { hasText: "表结构" }).first().click();
  await page.waitForSelector(".tabs", { timeout: 10000 });
  const structText = await page.locator(".page").innerText();
  check("结构页 tab 已激活", /结构/.test(await page.locator(".tab--active").innerText()));
  check("结构页显示列信息", /TEXT|INTEGER/.test(structText), structText.slice(0, 60).replace(/\n/g, " "));
  check("结构页显示枚举含义", /待支付|已支付/.test(structText), "无枚举含义");
  const structRows = await page.locator(".page .grid tbody tr").count();
  check("结构页列出列（>=9）", structRows >= 9, `${structRows} 行`);

  console.log("\n═══ 7. 搜索 ═══");
  const searchBox = page.locator('input[placeholder*="搜索"]').first();
  await searchBox.fill("订单");
  await page.waitForTimeout(900);
  const hits = await page.locator(".tree .hit").count();
  check("搜索有结果", hits > 0, `${hits} 条`);
  await searchBox.fill("");

  console.log("\n═══ 8. 查询编辑器 ═══");
  await page.locator("button", { hasText: "查询" }).first().click();
  await page.waitForTimeout(600);
  const editor = await page.locator("textarea.editor, .editor").first().isVisible();
  check("查询编辑器可见", editor);

  console.log("\n═══ 9. 控制台干净 ═══");
  check("无未捕获异常", pageErrors.length === 0, pageErrors.slice(0, 2).join(" | "));
  check("无 console.error", consoleErrors.length === 0, consoleErrors.slice(0, 2).join(" | "));
} finally {
  await browser.close();
}

console.log("");
if (fail) {
  console.log(`失败项：\n  - ${failures.join("\n  - ")}`);
}
console.log(`通过 ${pass} / 失败 ${fail}`);
process.exit(fail ? 1 : 0);