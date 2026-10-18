/**
 * ⭐ 结构页专项（D1-1）：外键 tab / 约束 tab
 *
 * ⭐ 验的是「数据是真的，不是摆设」：
 *   1. 外键 tab 显示**真实外键**（来自后端 PRAGMA foreign_key_list），
 *      不是那个恒为空的 `foreignKeys: []` 死字段。
 *   2. 约束 tab 汇总 主键 / 唯一 / 非空 / 检查，其中 CHECK 从建表 SQL 真实解析。
 *   3. 没有外键的表（tenants）显示空态，而不是默默渲染空表或报错。
 *
 * ⭐ 为什么这些能断言：演示库是确定性种子，外键关系是固定的：
 *   · orders.user_id → users(id)、orders.tenant_id → tenants(id)
 *   · reviews.rating 有 CHECK (rating BETWEEN 1 AND 5)
 *   · tags.code / coupons.code 有 UNIQUE
 *   · tenants 没有任何外键（被引用方）
 */
import { chromium } from "playwright";

let pass = 0, fail = 0;
const failures = [];
const check = (l, ok, x = "") => {
  if (ok) { pass++; console.log(`  ok    ${l}`); }
  else { fail++; failures.push(l); console.log(`  FAIL  ${l}  ${x}`); }
};

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
// ⭐ 外网头像图在沙箱无外网，加载失败是环境噪声 —— 路由成合法 PNG。
await page.route(/^https?:\/\/(?!localhost|127\.0\.0\.1)/, (route) =>
  route.fulfill({
    contentType: "image/png",
    body: Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==", "base64"),
  }));

async function openStructure(name) {
  // ⭐ 精确匹配表名：hasText 是子串匹配，"tags" 会误中 "product_tags"。
  //   这里锁定标签 span（`.truncate:not(.comment)`）用 :text-is 做全等比较。
  const node = page
    .locator(`.tree button.node:has(.truncate:not(.comment):text-is("${name}"))`)
    .first();
  await node.click({ button: "right" });
  await page.waitForSelector("[data-test=tree-ctx-menu]", { timeout: 5000 });
  await page.locator("[data-test=ctx-tree-structure]").click();
  await page.waitForSelector(".page:visible .tabs__item", { timeout: 8000 });
  await page.waitForTimeout(700);
}
async function clickTab(label) {
  await page.locator(".page:visible .tabs__item:visible", { hasText: label }).first().click();
  await page.waitForTimeout(300);
}
// ⭐ 当前可见结构页的网格行数（其它 tab 是 v-else-if，不渲染）
async function visibleRows() {
  return page.locator(".scroll.pad:visible .grid tbody tr").count();
}
async function visiblePanelText() {
  return (await page.locator(".scroll.pad:visible").innerText()).replace(/\s+/g, " ");
}

// 启动
await page.goto(PAGE, { waitUntil: "domcontentloaded" });
await page.waitForSelector(".tree button.node", { timeout: 30000 });
await page.waitForTimeout(1800);

console.log("\n═══ 1. 结构页 tab 结构（D1-1：列/索引/外键/约束/DDL/诊断）═══");
await openStructure("orders");
const tabCount = await page.locator(".page:visible .tabs__item").count();
check("结构页含 6 个 tab（新增外键/约束）", tabCount === 6, String(tabCount));
const tabLabels = await page.locator(".page:visible .tabs__item").allInnerTexts();
check("含「外键」「约束」两个新 tab",
  tabLabels.includes("外键") && tabLabels.includes("约束"), JSON.stringify(tabLabels));

console.log("\n═══ 2. 外键 tab：真实外键（orders）═══");
await clickTab("外键");
check("外键 tab 有行", (await visibleRows()) >= 2, String(await visibleRows()));
const fkText = await visiblePanelText();
check("外键指向 users", fkText.includes("users"), fkText.slice(0, 120));
check("外键指向 tenants", fkText.includes("tenants"), fkText.slice(0, 120));

console.log("\n═══ 3. 约束 tab：CHECK / 主键 / 非空（reviews）═══");
await openStructure("reviews");
await clickTab("约束");
const revText = await visiblePanelText();
check("约束含 CHECK(rating BETWEEN 1 AND 5)（从建表 SQL 真实解析）",
  /CHECK/.test(revText) && revText.includes("rating BETWEEN 1 AND 5"), revText.slice(0, 200));
check("约束含 PRIMARY KEY", revText.includes("PRIMARY KEY"), revText.slice(0, 200));
check("约束含 NOT NULL", revText.includes("NOT NULL"), revText.slice(0, 200));

console.log("\n═══ 4. 外键空态：无外键的表（tenants）═══");
await openStructure("tenants");
await clickTab("外键");
check("tenants 无外键 → 0 行", (await visibleRows()) === 0, String(await visibleRows()));
check("显示空态（该表没有外键）",
  (await page.locator(".scroll.pad:visible .empty").count()) >= 1);

console.log("\n═══ 5. 约束 tab：UNIQUE（tags）═══");
await openStructure("tags");
await clickTab("约束");
const tagText = await visiblePanelText();
check("约束含 UNIQUE（tags.name）", tagText.includes("UNIQUE"), tagText.slice(0, 200));

console.log("\n═══ 6. 控制台 ═══");
check("无未捕获异常（外网图片噪声已路由）", errs.length === 0, errs.slice(0, 2).join(" | "));

console.log(`\n通过 ${pass} / 失败 ${fail}`);
if (failures.length) console.log("失败项：\n - " + failures.join("\n - "));
await browser.close();
process.exit(fail ? 1 : 0);
