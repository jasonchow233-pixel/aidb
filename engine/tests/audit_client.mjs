/**
 * ⭐ 客户端体验专项审计 —— 分页 / 排序 / 筛选 / 类型提示 / 字段元数据
 * 只测「现在到底有没有」，不测「应该怎样」。
 */
import { chromium } from "playwright";

const URL = "http://localhost:5173";
const out = {};
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
const errs = [];
page.on("pageerror", (e) => errs.push(String(e).slice(0, 200)));

try {
  await page.goto(URL, { waitUntil: "domcontentloaded" });
  await page.waitForSelector(".tree button.node", { timeout: 30000 });
  await page.waitForTimeout(1500);
  await page.locator(".tree button.node", { hasText: "orders" }).first().click();
  await page.waitForSelector(".grid tbody tr", { timeout: 15000 });
  await page.waitForTimeout(1000);

  const colIdx = (name) =>
    page.evaluate((n) =>
      [...document.querySelectorAll(".grid thead th")].findIndex((t) =>
        t.innerText.trim().startsWith(n),
      ), name);

  // ── 1. 分页 ──
  out["分页"] = {};
  out["分页"]["行数选择器选项"] = await page.locator(".toolbar select").first().locator("option").allInnerTexts();
  const footText = await page.locator(".grid-foot").innerText();
  out["分页"]["底栏信息"] = footText.replace(/\n/g, " | ");
  out["分页"]["有下一页按钮"] = (await page.locator(".toolbar button", { hasText: "下一页" }).count()) > 0;
  out["分页"]["有页码/页大小控件"] = (await page.locator(".toolbar button, .toolbar select").count()) > 0;
  out["分页"]["跳页输入框"] = (await page.locator('.toolbar input[type="number"], .grid-foot input').count()) > 0;

  // 改行数看是否重新加载
  const before = await page.locator(".grid tbody tr").count();
  await page.locator(".toolbar select").first().selectOption("100");
  await page.waitForTimeout(1200);
  const after = await page.locator(".grid tbody tr").count();
  out["分页"]["改行数后行数"] = `${before} -> ${after}`;
  out["分页"]["改行数生效"] = after !== before;

  // ── 2. 排序 ──
  out["排序"] = {};
  const totalIdx = await colIdx("total_cents");
  await page.locator(".grid thead th").nth(totalIdx).click();
  await page.waitForTimeout(600);
  const ascVals = await page.evaluate((i) => {
    const rows = [...document.querySelectorAll(".grid tbody tr")].slice(0, 8);
    return rows.map((r) => Number(r.querySelectorAll("td")[i].innerText.replace(/,/g, "")));
  }, totalIdx);
  out["排序"]["首次点击升序"] = ascVals.slice(0, 5);
  out["排序"]["升序有效"] = ascVals.every((v, i) => i === 0 || ascVals[i - 1] <= v);

  await page.locator(".grid thead th").nth(totalIdx).click();
  await page.waitForTimeout(600);
  const descVals = await page.evaluate((i) => {
    const rows = [...document.querySelectorAll(".grid tbody tr")].slice(0, 8);
    return rows.map((r) => Number(r.querySelectorAll("td")[i].innerText.replace(/,/g, "")));
  }, totalIdx);
  out["排序"]["二次点击降序"] = descVals.slice(0, 5);
  out["排序"]["降序有效"] = descVals.every((v, i) => i === 0 || descVals[i - 1] >= v);

  out["排序"]["有排序下推到数据库"] = await page.evaluate(async () => {
    // 看后端有没有收到带 ORDER BY 的 SQL：直接问 API 对比性能
    const t0 = performance.now();
    const r = await fetch('/api/query', { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ sql: 'SELECT * FROM orders', maxRows: 200 }) });
    await r.json();
    return Math.round(performance.now() - t0) + "ms（仅后端往返，无法判定是否下推）";
  });

  // ── 3. 筛选 ──
  out["筛选"] = {};
  out["筛选"]["工具栏筛选器存在"] = (await page.locator(".toolbar button", { hasText: "筛选" }).count()) > 0;
  // 打开筛选面板
  await page.locator(".toolbar button", { hasText: "筛选" }).first().click();
  await page.waitForTimeout(400);
  out["筛选"]["筛选面板元素"] = await page.locator(".toolbar select, .toolbar input").count();
  const hasFilterCol = await page.locator('.toolbar select').count();
  out["筛选"]["可筛选列数"] = hasFilterCol > 0 ? await page.locator('.toolbar select').first().locator('option').count() : 0;
  out["筛选"]["列头有筛选图标"] = (await page.locator(".grid thead th [class*=filter], .grid thead th .ico-filter").count()) > 0;
  out["筛选"]["筛选下推到数据库"] = false; // 见下，用 API 对照

  // 实测筛选是否真下推：比对 WHERE 查询的耗时与 total
  const pushed = await page.evaluate(async () => {
    const r = await fetch('/api/query', { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ sql: 'SELECT * FROM orders WHERE status = 2', maxRows: 200 }) });
    const j = await r.json();
    return { total: j.data.page.total, ms: j.data.page.durationMs, rows: j.data.page.rows.length };
  });
  out["筛选"]["后端 WHERE 查询 total"] = pushed.total; // null = 后端拒绝估算（说明真查了）
  out["筛选"]["后端 WHERE 查询耗时"] = pushed.ms + "ms";
  await page.locator(".toolbar button", { hasText: "清除筛选" }).first().click().catch(() => {});

  // ── 4. 类型提示 / 字段元数据 ──
  out["类型与字段"] = {};
  const stIdx = await colIdx("status");
  const headTexts = await page.evaluate(() =>
    [...document.querySelectorAll(".grid thead th")].map((t) => t.innerText.replace(/\n/g, " | ")));
  out["类型与字段"]["表头样例"] = headTexts.slice(1, 5);
  out["类型与字段"]["表头显示类型"] = headTexts.some((h) => /TEXT|INTEGER|REAL/.test(h));
  out["类型与字段"]["表头显示注释"] = headTexts.some((h) => /订单|金额|状态/.test(h));
  out["类型与字段"]["表头显示默认值"] = headTexts.some((h) => /=/.test(h));

  // 枚举是否渲染成中文
  const stTexts = await page.evaluate((i) => {
    const rows = [...document.querySelectorAll(".grid tbody tr")].slice(0, 6);
    return rows.map((r) => r.querySelectorAll("td")[i]?.innerText.trim());
  }, stIdx);
  out["类型与字段"]["status 列渲染"] = stTexts;
  out["类型与字段"]["⭐ 枚举显示中文"] = stTexts.some((t) => /待支付|已支付|已完成|已发货/.test(t || ""));

  // 编辑时是否有类型化控件（下拉/日期选择）
  await page.locator(".grid tbody tr").nth(0).locator("td").nth(stIdx).click();
  await page.waitForTimeout(400);
  out["类型与字段"]["编辑枚举列出现下拉"] = (await page.locator(".grid select").count()) > 0;
  out["类型与字段"]["编辑出现类型输入框"] = (await page.locator(".grid input").count()) > 0;
  await page.keyboard.press("Escape");

  // 创建时间列
  const ctIdx = await colIdx("created_at");
  await page.locator(".grid tbody tr").nth(0).locator("td").nth(ctIdx).click();
  await page.waitForTimeout(400);
  out["类型与字段"]["编辑日期列出现日期选择器"] =
    (await page.locator('.grid input[type=date], .grid input[type=datetime-local]').count()) > 0;
  await page.keyboard.press("Escape");

  // NULL 渲染
  const nullCount = await page.locator(".grid td.null").count();
  out["类型与字段"]["NULL 单元格数"] = nullCount;
  out["类型与字段"]["NULL 有专属样式"] = nullCount === 0 ? "本表无 NULL" : "有";

  // ── 5. 字段完整度 ──
  const fullHead = await page.evaluate(() =>
    [...document.querySelectorAll(".grid thead th")].map((t) => t.innerText.replace(/\n/g, " | ")));
  out["字段"] = fullHead;

  // ── 6. 缺失的交互 ──
  out["缺失交互"] = {
    列头筛选面板: (await page.locator(".grid thead th .filter-panel").count()) > 0 ? "有" : "❌ 无",
    行复选框: (await page.locator('.grid tbody input[type=checkbox]').count()) > 0 ? "有" : "❌ 无",
    列冻结: (await page.locator('.grid th[style*="sticky"]').count()) > 0 ? "有" : "❌ 无",
    列宽拖拽: (await page.locator(".grid th .resizer").count()) > 0 ? "有" : "❌ 无",
    列显示控制: (await page.locator("[class*=column-menu]").count()) > 0 ? "有" : "❌ 无",
    右键菜单: (await page.locator(".context-menu").count()) > 0 ? "有" : "❌ 无",
    下一页翻页: (await page.locator(".toolbar button", { hasText: "下一" }).count()) > 0 ? "有" : "❌ 无",
    全选: (await page.locator('.grid thead input[type=checkbox]').count()) > 0 ? "有" : "❌ 无",
    单元格右键设NULL: (await page.locator('[class*=cell-menu]').count()) > 0 ? "有" : "❌ 无",
  };
  out["控制台错误"] = errs.length ? errs.slice(0, 3) : "无";
} finally {
  await browser.close();
}
console.log(JSON.stringify(out, null, 2));