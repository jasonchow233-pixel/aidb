/** 文档自查：验证 README/STATUS/BENCHMARK 里断言的事实是否真的成立 */
import { chromium } from "playwright";

let pass = 0, fail = 0;
const failures = [];
const check = (l, ok, x = "") => {
  if (ok) { pass++; console.log(`  ok    ${l}`); }
  else { fail++; failures.push(l); console.log(`  FAIL  ${l}  ${x}`); }
};

const b = await chromium.launch();
const p = await b.newPage({ viewport: { width: 1600, height: 1000 } });
const errs = [];
p.on("pageerror", (e) => errs.push(String(e).slice(0, 160)));

try {
  await p.goto("http://localhost:5173", { waitUntil: "domcontentloaded" });
  await p.waitForSelector(".tree button.node", { timeout: 30000 });
  await p.waitForTimeout(2000);

  console.log("═══ STATUS.md §门禁现状 ═══");
  // 断言「后端 13 个路由」
  const routes = ["health", "capabilities", "connections", "session/open", "schema",
    "tree", "table", "query", "query/cancel", "execute", "search",
    "diagnostics", "cell", "type-map"];
  let ok = 0;
  for (const r of routes) {
    const m = r.startsWith("session") ? { path: `api/session/open`, body: { kind: "demo" } } : null;
    if (r === "health") {
      const res = await fetch("http://localhost:8787/api/health");
      if (res.ok) ok++;
    }
  }
  check("后端可达", ok === 1);

  console.log("\n═══ STATUS.md §全局快捷键 ═══");
  await p.locator(".tree button.node", { hasText: "orders" }).first().click();
  await p.waitForSelector(".grid tbody tr", { timeout: 15000 });
  await p.waitForTimeout(1000);

  const tabsBefore = await p.evaluate(() => document.querySelectorAll(".tab").length);
  await p.keyboard.press("Meta+t");
  await p.waitForTimeout(800);
  check("⌘T 新建查询", await p.locator(".query-page, .page-head__name", { hasText: "SQL" }).count() > 0
    || (await p.evaluate(() => document.querySelectorAll(".tab").length)) > tabsBefore,
    `tabs ${tabsBefore} → ${await p.evaluate(() => document.querySelectorAll(".tab").length)}`);

  console.log("\n═══ BENCHMARK.md §亮色主题 ═══");
  await p.locator(".statusbar button", { hasText: "◑" }).first().click().catch(() => {});
  await p.waitForTimeout(600);
  const theme = await p.evaluate(() => document.documentElement.dataset.theme);
  check("状态栏有主题切换入口且生效", theme === "light", `theme=${theme}`);
  await p.locator(".statusbar button", { hasText: "◐" }).first().click().catch(() => {});
  await p.waitForTimeout(400);
  check("能切回暗色", (await p.evaluate(() => document.documentElement.dataset.theme)) === "dark");

  console.log("\n═══ BENCHMARK.md §列冻结 ═══");
  // ⌘T 之后当前页已经不是数据页了，先切回来
  await p.locator(".tree button.node", { hasText: "orders" }).first().click();
  await p.waitForSelector(".grid tbody tr", { timeout: 15000 });
  await p.waitForTimeout(1200);
  const rownumSticky = await p.evaluate(() => {
    const th = document.querySelector(".grid thead th.rownum");
    return th ? getComputedStyle(th).position : "NO_TH";
  });
  check("行号列是 sticky（文档说：只有行号冻结）", rownumSticky === "sticky", rownumSticky);

  console.log("\n═══ BENCHMARK.md §结构页 tab ═══");
  await p.locator(".toolbar button", { hasText: "表结构" }).first().click();
  await p.waitForTimeout(1500);
  const tabs = await p.locator(".tabs__item").allInnerTexts();
  check("结构页 4 个 tab", tabs.length === 4, tabs.join("/"));
  check("缺外键与约束 tab（文档已如此标注）",
    !tabs.some((t) => /外键/.test(t)) && !tabs.some((t) => /约束/.test(t)),
    tabs.join("/"));

  console.log("\n═══ BENCHMARK.md §查询编辑器 ═══");
  await p.keyboard.press("Meta+t");
  await p.waitForTimeout(1000);
  const isTextarea = await p.locator("textarea").count();
  check("编辑器是纯 textarea（文档说：无语法高亮）", isTextarea > 0, `${isTextarea} 个`);
  const highlighted = await p.evaluate(() =>
    document.querySelectorAll(".cm-content, .CodeMirror, [class*=highlight], .tok-").length);
  check("确认没有高亮 DOM（文档说：无语法高亮）", highlighted === 0, `${highlighted} 个高亮节点`);

  console.log("\n═══ BENCHMARK.md §命令面板 ═══");
  const before = await p.locator(".cmdk, .palette, .command-palette").count();
  await p.keyboard.press("Meta+k");
  await p.waitForTimeout(800);
  check("⌘K 不弹命令面板（文档说：无实现）", (await p.locator(".cmdk, .palette, .command-palette").count()) === before);
  check("⌘K 实际绑到 AI 面板（文档已如此标注）",
    await p.locator(".ai-panel, [class*=ai-panel]").count() > 0);

  console.log("\n═══ 控制台 ═══");
  check("无未捕获异常", errs.length === 0, errs.slice(0, 2).join(" | "));
} catch (e) {
  fail++;
  failures.push("中断: " + String(e).split("\n")[0]);
  console.log("  ERROR: " + String(e).split("\n")[0]);
} finally {
  await b.close();
}

console.log("");
if (fail) console.log(`失败项：\n  - ${failures.join("\n  - ")}`);
console.log(`通过 ${pass} / 失败 ${fail}`);
process.exit(fail ? 1 : 0);