/**
 * AgenticDB GUI 逐页审计 —— 找出「和 Navicat 差在哪」的事实依据。
 * 不猜测，只记录实测结果。
 */
import { chromium } from "playwright";

const URL = "http://localhost:5173";
const out = {};

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1500, height: 950 } });
const consoleErrs = [];
page.on("pageerror", (e) => consoleErrs.push(String(e).slice(0, 200)));
page.on("console", (m) => m.type() === "error" && consoleErrs.push(m.text().slice(0, 200)));

await page.goto(URL, { waitUntil: "domcontentloaded" });
await page.waitForSelector(".tree button.node", { timeout: 30000 });
await page.waitForTimeout(1500);

const text = () => page.evaluate(() => document.body.innerText);
const count = (sel) => page.locator(sel).count();

// ── 1. 表格数据页：逐项核对 Navicat 必备能力 ──────────────────────
await page.locator(".tree button.node", { hasText: "orders" }).first().click();
await page.waitForSelector(".grid tbody tr", { timeout: 15000 });
await page.waitForTimeout(800);

const toolbarBtns = await page.locator(".toolbar button").allInnerTexts();
out["数据页-工具栏按钮"] = toolbarBtns;

// 列头有没有筛选入口（Navicat 灵魂功能）
const headerHasFilter = await page.locator(".grid thead th .filter, .grid thead th [class*=filter]").count();

// 点单元格会发生什么（Navicat: 原地编辑并保存）
const firstCell = page.locator(".grid tbody tr").first().locator("td").nth(1);
const cellTextBefore = await firstCell.innerText();
await firstCell.click();
await page.waitForTimeout(400);
const editInputAppeared = await page.locator(".grid input").count();
const cellTextAfterClick = await firstCell.innerText().catch(() => "(消失)");
await page.keyboard.press("Escape");
await page.waitForTimeout(200);

out["列头筛选入口"] = headerHasFilter > 0 ? "有" : "❌ 无";
out["单元格点击"] = { 编辑前: cellTextBefore, 点击后: cellTextAfterClick, 出现输入框: editInputAppeared };

// 是否有右键菜单 / 批量操作栏
out["行选中复选框"] = (await count(".grid tbody input[type=checkbox]")) > 0 ? "有" : "❌ 无";
out["多选批量工具栏"] = (await count("[class*=batch], [class*=bulk]")) > 0 ? "有" : "❌ 无";

// 是否有列可见性控制 / 拖拽排序
out["列显示控制"] = (await count("[class*=column-menu], [class*=col-toggle]")) > 0 ? "有" : "❌ 无";

// 状态栏信息完整度
out["数据页底部信息"] = (await page.locator(".grid-foot").innerText().catch(() => "")).replace(/\n/g, " | ");

// ── 2. 快捷键 / 命令面板 ────────────────────────────────────────
const bodyText = await text();
out["有命令面板(⌘K)"] = /命令面板|Ctrl\+K|⌘K/.test(bodyText) ? "文案提及" : "❌ 无入口";

// ── 3. 各工具页逐一打开，记录可用性 ─────────────────────────────
const tools = [
  ["诊断", "诊断"], ["能力矩阵", "能力"], ["数据传输", "传输"],
  ["数据工厂", "工厂"], ["变更管控", "变更"],
];
out["工具页"] = {};
for (const [label, needle] of tools) {
  try {
    const sec = page.locator(".tree .sec", { hasText: "工具" });
    if (await sec.count()) {
      const isOpen = await page.evaluate(() => true);
    }
    // 工具分组默认展开，直接找节点
    const node = page.locator(".tree button.node", { hasText: needle }).first();
    if (await node.count()) {
      await node.click();
      await page.waitForTimeout(1200);
      const t = await text();
      const lines = t.split("\n").filter((l) => l.trim().length > 2);
      out["工具页"][label] = {
        可打开: true,
        内容行数: lines.length,
        片段: lines.slice(0, 6).join(" / ").slice(0, 160),
      };
    } else {
      out["工具页"][label] = { 可打开: false };
    }
  } catch (e) {
    out["工具页"][label] = { 可打开: false, 错误: String(e).slice(0, 100) };
  }
}

// ── 4. 查询编辑器能力核对 ───────────────────────────────────────
await page.locator("button", { hasText: "查询" }).first().click();
await page.waitForTimeout(1000);
const editorArea = await page.locator(".editor").count();
out["查询页-编辑器"] = editorArea > 0 ? "textarea（无语法高亮）" : "❌ 无编辑器";

const qBtns = await page.locator(".editor-page__toolbar button").allInnerTexts().catch(() => []);
out["查询页-工具栏"] = qBtns;

// 高亮：检查是否有 .cm-* (CodeMirror) 或 .token (Prism)
out["SQL语法高亮"] =
  (await count(".cm-editor")) > 0
    ? "CodeMirror ✅"
    : (await count(".editor .token")) > 0
      ? "自研高亮"
      : "❌ 无高亮（纯 textarea）";

// ── 5. 全局能力盘点（有没有这些入口） ───────────────────────────
const all = await text();
out["全局能力盘点"] = {
  新建查询: /＋ ?查询|新建查询/.test(all),
  新建连接: /新建连接|连接向导/.test(all),
  保存连接: /保存连接|收藏/.test(all),
  导出: /导出/.test(all),
  导入: /导入/.test(all),
  备份: /备份|还原/.test(all),
  事务: /事务|BEGIN|COMMIT/.test(all),
  ER图: /ER|关系图|模型/.test(all),
  片段: /片段|snippet/i.test(all),
  对象浏览器: /存储过程|函数|触发器/.test(all),
  定时任务: /定时|计划任务|调度/.test(all),
  SSH隧道: /SSH|隧道/.test(all),
  缩放: /缩放/.test(all),
  专注模式: /专注/.test(all),
};

// ── 6. 侧栏能力 ────────────────────────────────────────────────
out["侧栏"] = {
  右键菜单: (await count(".context-menu")) > 0 ? "有" : "❌ 无",
  收藏星标: /★|☆/.test(all) ? "有" : "❌ 无",
  折叠态按钮: (await count(".collapsed .ci")) > 0 ? "有" : "❌ 无",
};

out["控制台错误"] = consoleErrs.length ? consoleErrs : "无";

console.log(JSON.stringify(out, null, 2));
await browser.close();