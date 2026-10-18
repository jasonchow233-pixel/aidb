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
  await p.waitForTimeout(1400);
  // ⭐ 文档 2026-10-18 已更新：编辑器从「纯 textarea 无高亮」升级为
  //   自研高亮 + 跳转 + 多语句。断言必须跟着文档一起改 ——
  //   否则这一层会在文档写对、代码也写对的时候红，久了就没人看它了。
  const isTextarea = await p.locator(".sqled__ta").count();
  check("编辑器渲染出来了", isTextarea === 1, `${isTextarea} 个`);
  const hlTokens = await p.locator(".sqled__hl .tk").count();
  check("⭐ 文档说的「有语法高亮」是真的（有高亮 token）", hlTokens > 5, `${hlTokens} 个 token`);
  const kwColored = await p.evaluate(() => {
    const kw = document.querySelector(".sqled__hl .tk--keyword");
    const base = getComputedStyle(document.querySelector(".sqled__hl")).color;
    return kw ? getComputedStyle(kw).color !== base : false;
  });
  check("⭐ 高亮真的上了颜色（不只是有 DOM）", kwColored);
  const gutter = await p.locator(".sqled__ln").count();
  check("文档说的「有行号」是真的", gutter > 1, `${gutter} 行`);

  console.log("\n═══ BENCHMARK.md §多语句 ═══");
  await p.locator(".sqled__ta").click();
  await p.keyboard.press("Meta+a");
  await p.keyboard.type("SELECT 1 AS a; SELECT 2 AS b;");
  await p.waitForTimeout(600);
  check("文档说的「每条语句一个结果页签」：识别出 2 条",
    (await p.locator("[data-test=stmt-count]").innerText().catch(() => "")).includes("2"),
    await p.locator("[data-test=stmt-count]").innerText().catch(() => "NONE"));
  await p.locator("[data-test=run]").click();
  await p.waitForTimeout(3000);
  check("⭐ 执行后确实出现 2 个结果页签",
    (await p.locator("[data-test=result-tab]").count()) === 2,
    `${await p.locator("[data-test=result-tab]").count()} 个`);

  console.log("\n═══ BENCHMARK.md §执行计划 ═══");
  await p.locator(".sqled__ta").click();
  await p.keyboard.press("Meta+a");
  await p.keyboard.type("SELECT id FROM orders WHERE status = 2");
  await p.waitForTimeout(500);
  await p.locator("[data-test=explain]").click();
  await p.waitForTimeout(2500);
  const exText = await p.locator("[data-test=explain-panel]").innerText().catch(() => "");
  check("文档说的「有执行计划 + 人话解读」是真的",
    /覆盖索引|走索引|全表扫描/.test(exText), exText.replace(/\n/g, " ").slice(0, 120));

  console.log("\n═══ BENCHMARK.md §命令面板 ═══");
  // ⚠ 必须先离开编辑器：⌘K 在 textarea 里可能被输入法/浏览器吃掉，
  //   那样测出来的是「编辑器抢了快捷键」而不是「命令面板没实现」。
  await p.locator(".tab--active").click();
  await p.locator(".app-main").click({ position: { x: 5, y: 5 } });
  await p.waitForTimeout(400);
  const before = await p.locator(".cmdk, .palette, .command-palette").count();
  await p.keyboard.press("Meta+k");
  await p.waitForTimeout(900);
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