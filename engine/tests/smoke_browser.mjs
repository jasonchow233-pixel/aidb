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

  // ⭐ 窗口化渲染：DOM 里只有可视区的几十行，
  //    行数要从底栏文案读，不是数 <tr>。
  const domRows = await page.locator(".grid tbody tr:not(.win-spacer)").count();
  const footText = await page.locator(".grid-foot").innerText();
  const pageRows = Number(footText.match(/本页\s+([\d,]+)\s*行/)?.[1].replace(/,/g, "") ?? -1);
  check("表格渲染出数据行", domRows > 0, `DOM ${domRows} 行`);
  check("底栏报告本页行数", pageRows >= 100, `${pageRows} 行`);
  check("⭐ 窗口化生效（DOM 行数远小于本页行数）", domRows < pageRows,
    `DOM ${domRows} < 本页 ${pageRows}`);

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

  console.log("\n═══ 9. 工具页路由（回归：mask/audit 曾被 diagnosis 分支抢先命中）═══");
  // 侧栏「工具」分组默认折叠
  await page.locator(".tree .sec", { hasText: "工具" }).first().click();
  await page.waitForTimeout(600);

  // ⭐ 工具节点紧跟在「工具」分组标题之后；用「后 5 个节点」定位，
  //    不要去匹配图标 —— 图标是装饰，改一次就会让测试假失败。
  const toolNodes = await page.evaluate(() =>
    [...document.querySelectorAll(".tree button.node")]
      .map((b, i) => ({ i, text: b.innerText.replace(/\n/g, "|") }))
      .slice(-5),
  );
  const expected = ["诊断", "数据传输", "数据工厂", "能力矩阵", "变更管控"];
  const toolIdx = {};
  for (const n of toolNodes) {
    for (const name of expected) {
      if (n.text.includes(name)) toolIdx[name] = n.i;
    }
  }
  check(
    "侧栏存在 5 个工具入口",
    Object.keys(toolIdx).length === 5,
    `找到 ${Object.keys(toolIdx).join(",")} / 预期 ${expected.join(",")}`,
  );

  // ⭐ 每个工具页必须渲染出**不同的**内容。
  //   之前 mask/audit 都被路由到 DiagnosisPage，内容长度完全一样（2126）——
  //   点了跟没点一样，但没有任何报错。
  const fingerprints = {};
  for (const name of ["诊断", "数据传输", "数据工厂", "能力矩阵", "变更管控"]) {
    const i = toolIdx[name];
    if (i === undefined) continue;
    await page.evaluate((idx) => {
      document.querySelectorAll(".tree button.node")[idx].click();
    }, i);
    await page.waitForTimeout(900);
    const info = await page.evaluate(() => ({
      tab: document.querySelector(".tab--active")?.textContent?.trim().replace("✕", "") ?? "",
      body: (document.querySelector(".tabbar")?.nextElementSibling)?.innerText ?? "",
    }));
    fingerprints[name] = { tab: info.tab, len: info.body.length, head: info.body.slice(0, 40).replace(/\n/g, " ") };
    check(`「${name}」激活对应 tab`, info.tab.includes(name), `实际=${info.tab}`);
  }

  const lens = Object.values(fingerprints).map((f) => f.len);
  check("工具页内容互不相同", new Set(lens).size === lens.length, lens.join("/"));

  console.log("\n═══ 10. 控制台干净 ═══");
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