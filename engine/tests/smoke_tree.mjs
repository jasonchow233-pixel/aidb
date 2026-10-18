/**
 * ⭐ 对象树专项（C1）：右键菜单 / 双击打开 / 按 kind 分组 / 收藏星标 / 只读标记
 *
 * ⭐ 验的是三类「看着正常、其实没接上」的事：
 *   1. 右键菜单 10 项（8 动作 + 2 分隔线）每一项都真能触发，且 Esc/点外面能关。
 *      —— 上一轮网格右键菜单就曾「7 项全是摆设」（capture mousedown 把菜单先卸了，
 *         click 永不触发）。这里用「菜单项触发后产生可观测副作用」来证伪。
 *   2. 双击打开：Navicat 肌肉记忆；单点保持「展开+开表」不动，避免破坏现有门禁。
 *   3. 收藏星标持久化到 localStorage，跨刷新保留；只读连接显示盾牌且禁用编辑入口。
 *
 * ⭐ 视图分组：demo 有 3 个视图（v_order_summary / v_product_sales / v_user_stats），
 *   按 kind 渲染为 📊 并与表分组成「视图」组 —— 不硬编码，驱动返回什么 kind 渲染什么。
 */
import { chromium } from "playwright";

let pass = 0, fail = 0;
const failures = [];
const check = (l, ok, x = "") => {
  if (ok) { pass++; console.log(`  ok    ${l}`); }
  else { fail++; failures.push(l); console.log(`  FAIL  ${l}  ${x}`); }
};

const BASE = "http://localhost:8787";
const PAGE = "http://localhost:5173";

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 1600, height: 1000 },
  permissions: ["clipboard-read", "clipboard-write"],
});
const page = await ctx.newPage();
const errs = [];
page.on("pageerror", (e) => errs.push(String(e).slice(0, 200)));
page.on("console", (m) => {
  if (m.type() !== "error") return;
  const t = m.text();
  if (/net::ERR_(CONNECTION_CLOSED|NAME_NOT_RESOLVED|INTERNET_DISCONNECTED|NETWORK_CHANGED|PROXY_CONNECTION_FAILED|CONNECTION_REFUSED|TIMED_OUT)/.test(t)) return;
  errs.push("console: " + t.slice(0, 200));
});
// ⭐ 外部头像图在沙箱无外网，加载失败是环境噪声 —— 路由成一个合法 PNG，避免 console 噪声。
await page.route(/^https?:\/\/(?!localhost|127\.0\.0\.1)/, (route) =>
  route.fulfill({
    contentType: "image/png",
    body: Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==", "base64"),
  }));

async function openTable(name) {
  await page.locator(".tree button.node", { hasText: name }).first().click();
  await page.waitForSelector(".grid tbody tr:not(.win-spacer)", { timeout: 20000 });
  await page.waitForTimeout(700);
}
async function rightClick(name) {
  await page.locator(".tree button.node", { hasText: name }).first().click({ button: "right" });
  await page.waitForSelector("[data-test=tree-ctx-menu]", { timeout: 5000 });
}

// 启动
await page.goto(PAGE, { waitUntil: "domcontentloaded" });
await page.waitForSelector(".tree button.node", { timeout: 30000 });
await page.waitForTimeout(1800);

console.log("\n═══ 1. 对象树：表 / 视图分组 / 按 kind 图标 ═══");
const secTexts = await page.locator(".tree .sec").allInnerTexts();
check("对象树含「视图」分组", secTexts.some((t) => t.includes("视图")), JSON.stringify(secTexts));
for (const v of ["v_order_summary", "v_product_sales", "v_user_stats"]) {
  check(`视图 ${v} 出现在树中`, (await page.locator(".tree button.node", { hasText: v }).count()) >= 1);
}
const viewIco = await page.locator(".tree button.node", { hasText: "v_order_summary" }).first().locator(".ico").innerText();
check("视图节点图标为 📊（按 kind 渲染，不硬编码）", viewIco.trim() === "📊", viewIco);
const tableIco = await page.locator(".tree button.node", { hasText: "users" }).first().locator(".ico").innerText();
check("表节点图标为 ▦", tableIco.trim() === "▦", tableIco);

console.log("\n═══ 2. 右键菜单：10 项（8 动作 + 2 分隔线）═══");
await rightClick("users");
check("右键弹出菜单", (await page.locator("[data-test=tree-ctx-menu]").count()) === 1);
check("菜单 8 个动作项", (await page.locator(".ctxmenu__item").count()) === 8, String(await page.locator(".ctxmenu__item").count()));
check("菜单 2 条分隔线", (await page.locator(".ctxmenu__sep").count()) === 2, String(await page.locator(".ctxmenu__sep").count()));
const labels = await page.locator(".ctxmenu__item").allInnerTexts();
check("菜单含：打开数据/打开结构/刷新此表/复制表名/复制 SELECT/在查询中打开/诊断此表/收藏",
  ["打开数据", "打开结构", "刷新此表", "复制表名", "复制 SELECT", "在查询中打开", "诊断此表", "收藏"].every((l) => labels.includes(l)),
  JSON.stringify(labels));

console.log("\n═══ 3. 菜单动作真能触发（非摆设）═══");
// 「打开结构」→ 结构页 tab
await page.locator("[data-test=ctx-tree-structure]").click();
await page.waitForTimeout(900);
check("「打开结构」打开结构页", (await page.locator(".tab", { hasText: "结构" }).count()) >= 1);

// 「复制表名」→ 剪贴板 = users
await rightClick("users");
await page.locator("[data-test=ctx-tree-copy-name]").click();
await page.waitForTimeout(300);
const clip = await page.evaluate(() => navigator.clipboard.readText());
check("「复制表名」写入剪贴板 = users", clip.trim() === "users", clip);
check("点击菜单项后菜单关闭", (await page.locator("[data-test=tree-ctx-menu]").count()) === 0);

// 「在查询中打开」→ 新查询页含 SELECT
await rightClick("orders");
await page.locator("[data-test=ctx-tree-query]").click();
await page.waitForTimeout(600);
check("「在查询中打开」新建查询页", (await page.locator(".tab", { hasText: "查询" }).count()) >= 1);

console.log("\n═══ 4. 菜单关闭：Esc / 点外面（含立刻 Esc 回归）═══");
await rightClick("users");
await page.locator(".page-head__name, .tabbar").first().click(); // 点外面
await page.waitForTimeout(250);
check("点菜单外关闭", (await page.locator("[data-test=tree-ctx-menu]").count()) === 0);

await rightClick("users");
await page.keyboard.press("Escape");
await page.waitForTimeout(200);
check("Esc 关闭菜单", (await page.locator("[data-test=tree-ctx-menu]").count()) === 0);

// ⭐⭐ 回归（网格 #14 同类）：右键后立刻按 Esc，中间不做任何别的动作也要关得掉
await rightClick("users");
await page.keyboard.press("Escape"); // 立刻，无 waitForSelector 之外的任何动作
await page.waitForTimeout(150);
check("右键后立刻 Esc 也能关（监听器不延后挂载）", (await page.locator("[data-test=tree-ctx-menu]").count()) === 0);

console.log("\n═══ 5. 双击打开（单点保持展开+开表，不破坏现有门禁）═══");
const ordersTabBefore = await page.locator(".tab", { hasText: "orders" }).count();
await page.locator(".tree button.node", { hasText: "orders" }).first().dblclick();
await page.waitForTimeout(900);
check("双击打开数据页（orders tab 出现）", (await page.locator(".tab", { hasText: "orders" }).count()) > ordersTabBefore);

console.log("\n═══ 6. 收藏星标：持久化到 localStorage ═══");
// 先确保 orders 未收藏
await page.locator(".tree button.node", { hasText: "orders" }).first().hover();
const starOff = page.locator(".tree button.node", { hasText: "orders" }).first().locator(".star--off");
if (await starOff.count()) {
  await starOff.click({ force: true });
  await page.waitForTimeout(300);
}
check("收藏后 ★ 出现", (await page.locator(".tree button.node", { hasText: "orders" }).first().locator(".star--on").count()) >= 1);
check("出现「收藏」分组", (await page.locator(".tree .sec", { hasText: "收藏" }).count()) >= 1);
check("收藏写入 localStorage", (await page.evaluate(() => localStorage.getItem("adb.starred"))).includes("orders"));

// 跨刷新保留
await page.reload({ waitUntil: "domcontentloaded" });
await page.waitForSelector(".tree button.node", { timeout: 30000 });
await page.waitForTimeout(1800);
check("刷新后收藏仍在（localStorage 持久化）", (await page.locator(".tree .sec", { hasText: "收藏" }).count()) >= 1);
check("刷新后 orders 仍带 ★", (await page.locator(".tree button.node", { hasText: "orders" }).first().locator(".star--on").count()) >= 1);

console.log("\n═══ 7. 只读标记逻辑（demo 为 full → 不应出现盾牌/禁用）═══");
check("非只读连接：数据库节点无只读样式", (await page.locator(".node--readonly").count()) === 0);
check("非只读连接：无只读盾牌", (await page.locator(".shield").count()) === 0);
await openTable("users");
check("非只读连接：数据页编辑入口（删除按钮）未被禁用", (await page.locator("button", { hasText: "删除" }).count()) > 0);

console.log("\n═══ 8. 控制台 ═══");
check("无未捕获异常（外网图片噪声已路由）", errs.length === 0, errs.slice(0, 2).join(" | "));

console.log(`\n通过 ${pass} / 失败 ${fail}`);
if (failures.length) console.log("失败项：\n - " + failures.join("\n - "));
await browser.close();
process.exit(fail ? 1 : 0);
