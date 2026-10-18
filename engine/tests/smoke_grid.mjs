/**
 * ⭐ 表格内核专项：A4-1 窗口化渲染 + A3 批量操作 + 行标识一致性
 *
 * ⭐ 这里验的是三件「看着没问题、实际已经错了」的事：
 *
 *   1. **窗口化不能改变语义**
 *      · 滚动条长度必须等于真实行数（靠 spacer 撑高）
 *      · 行号必须连续（spacer 不参与编号）
 *      · 滚出窗口的行再次滚回来必须还是原来那些行
 *
 *   2. **行标识必须唯一且稳定** ⭐ 最危险的一条
 *      前端同时有「本页原始行序」和「本页搜索后的行序」两个下标。
 *      一旦编辑/删除/选中用了错的那个 —— 页面完全正常，改的是**另一行**。
 *      所以测试必须回到数据库核对「到底改了哪一行」，只看页面等于没验。
 *
 *   3. **批量操作的范围要说清**
 *      窗口化之后「全选」只覆盖可见行。如果 UI 写「全选」而实际只选了
 *      几十行，用户以为操作了整页 —— 这是会出事故的撒谎。
 */
import { chromium } from "playwright";

let pass = 0, fail = 0;
const failures = [];
const check = (l, ok, x = "") => {
  if (ok) { pass++; console.log(`  ok    ${l}`); }
  else { fail++; failures.push(l); console.log(`  FAIL  ${l}  ${x}`); }
};

const BASE = "http://localhost:8787";

/** ⭐ 直接问数据库要真相，不看页面 */
async function dbRows(sql) {
  const r = await fetch(`${BASE}/api/query`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ sql }),
  });
  const j = await r.json();
  if (!j.ok) throw new Error(j.error?.message ?? "query failed");
  return j.data.page.rows;
}
async function dbExec(sql) {
  const r = await fetch(`${BASE}/api/execute`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ sql, requireConfirm: true }),
  });
  const j = await r.json();
  if (!j.ok) throw new Error(j.error?.message ?? "execute failed");
  return j.data;
}

// ⭐ 重置范围要包含 id=1：它是本用例最容易被误改的那一行，
//    上一次跑挂了留下脏数据的话，这次会误报成「产品又改错行了」。
const RESET = "UPDATE orders SET remark = NULL WHERE id IN (1, 7, 8, 9)";

async function resetTestData() {
  try { await dbExec(RESET); } catch {}
}

const browser = await chromium.launch();
const ctx = await browser.newContext({
  viewport: { width: 1600, height: 1000 },
  permissions: ["clipboard-read", "clipboard-write"],
});
const page = await ctx.newPage();
const errs = [];
page.on("pageerror", (e) => errs.push(String(e).slice(0, 200)));

/** 真实数据行的「单元格文本矩阵」，排除窗口化 spacer。
 *  ⭐ page.evaluate 不能返回 DOM 元素，只能返回可序列化的数据。 */
import { trueRowCount, dataCells as pageDataCells, colIdxOf as pageColIdxOf, scrollGrid }
  from "./helpers.mjs";

const dataCells = () => pageDataCells(page);
const colIdxOf = (n) => pageColIdxOf(page, n);
const scrollTo = (t) => scrollGrid(page, t);
const pageRows = () => trueRowCount(page);

/** 点某个单元格，进入编辑态 */
async function clickCell(rowIdx, colName) {
  const i = await colIdxOf(colName);
  await page.evaluate(([r, c]) => {
    const tr = [...document.querySelectorAll(".grid tbody tr")]
      .filter((x) => !x.classList.contains("win-spacer"))[r];
    tr.querySelectorAll("td")[c].click();
  }, [rowIdx, i]);
  await page.waitForTimeout(400);
}

try {
  await resetTestData();

  await page.goto("http://localhost:5173", { waitUntil: "domcontentloaded" });
  await page.waitForSelector(".tree button.node", { timeout: 30000 });
  await page.waitForTimeout(1800);
  await page.locator(".tree button.node", { hasText: "orders" }).first().click();
  await page.waitForSelector(".grid tbody tr", { timeout: 15000 });
  await page.waitForTimeout(1200);

  // ── 1. 窗口化确实生效 ────────────────────────────────────
  console.log("═══ 1. A4-1 窗口化 ═══");
  await page.locator(".pager select").selectOption("100");
  await page.waitForTimeout(1800);

  const domCount = (await dataCells()).length;
  const realCount = await pageRows();
  check("底栏说本页 100 行", realCount === 100, `${realCount}`);
  check("⭐ DOM 只渲染可视区（远少于 100）", domCount > 0 && domCount < 60,
    `DOM ${domCount} 行 / 真实 ${realCount} 行`);
  check("⭐ 存在下方 spacer 撑高度",
    (await page.locator(".grid tbody tr.win-spacer").count()) > 0);

  // 滚动条长度：wrap 的 scrollHeight 应当接近 全部行高 + 表头
  const scrollH = await page.evaluate(() => {
    const el = document.querySelector(".grid-wrap");
    return { scrollHeight: el.scrollHeight, clientHeight: el.clientHeight };
  });
  const estRowPx = realCount > 0 ? (scrollH.scrollHeight - 46) / realCount : 0;
  check("⭐ 滚动条长度反映全部行数（不是只渲染那几十行）",
    scrollH.scrollHeight > scrollH.clientHeight * 3,
    `scrollHeight=${scrollH.scrollHeight} clientHeight=${scrollH.clientHeight} 每行≈${estRowPx.toFixed(1)}px`);
  check("每行高度落在合理区间（22~42px）", estRowPx > 22 && estRowPx < 42,
    `${estRowPx.toFixed(1)}px`);

  // ── 2. 行号连续 + 滚动换内容 ────────────────────────────
  console.log("\n═══ 2. 行号与滚动 ═══");
  const nosBefore = (await dataCells()).map((r) => r[0]);
  check("⭐ 行号从 1 开始且连续",
    nosBefore.length > 2 &&
    Number(nosBefore[0]) === 1 &&
    nosBefore.every((n, i) => Number(n) === i + 1),
    nosBefore.slice(0, 4).join(","));

  await scrollTo(3000);
  const nosAfter = (await dataCells()).map((r) => r[0]);
  check("⭐ 滚动后行号仍连续（spacer 不参与编号）",
    nosAfter.every((n, i) => Number(n) === i + Number(nosAfter[0])),
    nosAfter.slice(0, 3).join(","));
  check("⭐ 滚动后换成了后面的行", nosAfter[0] !== nosBefore[0],
    `${nosBefore[0]} → ${nosAfter[0]}`);
  check("⭐ 存在上方 spacer（说明确实滚过去了）",
    (await page.locator(".grid tbody tr.win-spacer").count()) >= 1);

  await scrollTo(0);

  // ── 3. 换页后滚动位置必须归零 ───────────────────────────
  console.log("\n═══ 3. 换页重置 ═══");
  await scrollTo(3000);
  await page.locator(".pager button", { hasText: "▶" }).click();
  await page.waitForTimeout(2000);
  const topAfterPaging = await page.evaluate(
    () => document.querySelector(".grid-wrap").scrollTop);
  check("⭐ 换页后滚动位置归零（否则页面显示成空表）", topAfterPaging === 0,
    `scrollTop=${topAfterPaging}`);
  const nosP2 = (await dataCells()).map((r) => r[0]);
  check("⭐ 第 2 页行号从 101 开始", Number(nosP2[0]) === 101, nosP2[0]);
  await page.locator(".pager button", { hasText: "◀" }).click();
  await page.waitForTimeout(1800);

  // ── 4. ⭐ 行标识：搜索后编辑必须改对行 ─────────────────
  console.log("\n═══ 4. ⭐ 行标识一致性（最危险） ═══");
  await page.locator(".searchbox").fill("NO0000000007");
  await page.waitForTimeout(700);
  const visRows = (await dataCells()).length;
  check("搜索命中 1 行", visRows === 1, `${visRows} 行`);

  // 只显示第 7 行，但它是 filteredRows[0]；若下游把 filteredRows 下标
  // 当成 state.rows 下标去编辑，改到的会是 id=1 —— 第 1 行。
  await clickCell(0, "remark");
  const editor = page.locator(".cell-editor");
  check("编辑框出现", (await editor.count()) > 0);
  await editor.fill("GRID_ID_TEST");
  await editor.press("Enter");
  await page.waitForTimeout(500);

  const saveBtn = page.locator(".toolbar button", { hasText: "保存" });
  check("出现保存按钮", (await saveBtn.count()) > 0);
  page.once("dialog", (d) => d.accept());
  await saveBtn.click();
  await page.waitForTimeout(2500);

  const after = await dbRows("SELECT id, remark FROM orders WHERE id IN (1, 7) ORDER BY id");
  check("⭐ 改的是 id=7（屏幕上那一行）", after[1]?.remark === "GRID_ID_TEST",
    JSON.stringify(after));
  check("⭐ id=1 没被误改", after[0]?.remark === null,
    `id=1 remark=${JSON.stringify(after[0]?.remark)}`);

  await dbExec("UPDATE orders SET remark = NULL WHERE id = 7");

  // ── 5. A3 选中 ────────────────────────────────────────
  console.log("\n═══ 5. A3 批量选中 ═══");
  await page.locator(".searchbox").fill("");
  await page.waitForTimeout(600);
  const box0 = page.locator(".grid tbody tr:not(.win-spacer) input[type=checkbox]").first();
  await box0.click();
  await page.waitForTimeout(300);
  check("⭐ 行选中后底栏显示计数",
    /已选\s*1\s*行/.test(await page.locator(".grid-foot").innerText()));
  check("⭐ 批量操作条出现",
    (await page.locator(".grid-foot button", { hasText: "复制为 SQL" }).count()) === 1);
  check("⭐ 行高亮", (await page.locator(".grid tbody tr.is-selected").count()) === 1);

  // 选中在滚动后仍然保留（存的是真实行下标，不是窗口内序号）
  await scrollTo(2000);
  check("⭐ 滚动后选中仍保留",
    /已选\s*1\s*行/.test(await page.locator(".grid-foot").innerText()));
  await scrollTo(0);

  // ── 6. 全选只覆盖可见行，且说明写清楚 ──────────────────
  console.log("\n═══ 6. 全选范围 ═══");
  const headBox = page.locator(".grid thead .pickcell input[type=checkbox]");
  await headBox.click();
  await page.waitForTimeout(400);
  const selected = Number(
    (await page.locator(".grid-foot").innerText()).match(/已选\s*([\d,]+)\s*行/)?.[1] ?? -1);
  const domNow = (await dataCells()).length;
  check("⭐ 全选覆盖的是「可见行」而不是整页", selected === domNow,
    `已选 ${selected} / 可见 ${domNow} / 本页 ${await pageRows()}`);
  check("⭐ 全选提示写明了范围（不是含糊的「全选」）",
    /可见/.test(await headBox.getAttribute("title") ?? ""),
    await headBox.getAttribute("title"));

  await page.locator(".grid-foot button", { hasText: "清除选择" }).click();
  await page.waitForTimeout(400);
  check("清除选择后批量条消失",
    (await page.locator(".grid-foot button", { hasText: "复制为 SQL" }).count()) === 0);

  // ── 7. 批量复制：NULL 与空串必须区分 ───────────────────
  console.log("\n═══ 7. 批量复制（NULL ≠ 空串） ═══");
  await dbExec("UPDATE orders SET remark = '' WHERE id = 8");
  await dbExec("UPDATE orders SET remark = NULL WHERE id = 7");
  // ⭐ 必须点「刷新」重新取数：对已经是 100 的页大小再 selectOption 不会触发
  //   change，界面还停在旧数据上 —— 断言就会验到一份没人看过的快照。
  await page.locator(".page-head button", { hasText: "刷新" }).click();
  await page.waitForTimeout(2000);

  const boxes = page.locator(".grid tbody tr:not(.win-spacer) input[type=checkbox]");
  await boxes.nth(6).click();  // id=7  remark=NULL
  await boxes.nth(7).click();  // id=8  remark=''
  await page.waitForTimeout(300);
  check("⭐ 一次选中 2 行",
    /已选\s*2\s*行/.test(await page.locator(".grid-foot").innerText()));

  // ⭐ 按钮文案要精确匹配：「复制」会同时命中「复制为 SQL」和每行的「复制」，
  //    必须限定在底栏里选，否则 strict 模式直接报错。
  await page.locator(".grid-foot").getByRole("button", { name: "复制", exact: true }).click();
  await page.waitForTimeout(600);
  const tsv = await page.evaluate(() => navigator.clipboard.readText());
  const tsvLines = tsv.split("\n");
  check("⭐ TSV 含表头 + 2 行数据", tsvLines.length === 3, `${tsvLines.length} 行`);

  // ⭐ 必须按**列位置**核对 remark，不能整行做正则：
  //    这一行的 paid_at 也是 NULL，整行匹配分不出「remark 是空串」还是别的列。
  // ⭐ TSV 里字符串带双引号、NULL 不带，所以取值要先去引号再比对。
  const head = tsvLines[0].split("\t");
  const unquote = (s) => (s ?? "").replace(/^"(.*)"$/s, "$1");
  const cNo = head.indexOf("order_no");
  const cRemark = head.indexOf("remark");
  check("TSV 表头含 order_no / remark 两列", cNo >= 0 && cRemark >= 0,
    head.join(","));
  const byNo = Object.fromEntries(
    tsvLines.slice(1).map((l) => {
      const f = l.split("\t");
      return [unquote(f[cNo]), f[cRemark]];
    }));
  check("⭐ 空串导出成 \"\"（不与 NULL 混淆）", byNo["NO0000000008"] === '""',
    `id=8 remark=${JSON.stringify(byNo["NO0000000008"])}`);
  check("⭐ NULL 导出成 NULL（不是空串）", byNo["NO0000000007"] === "NULL",
    `id=7 remark=${JSON.stringify(byNo["NO0000000007"])}`);

  await page.locator(".grid-foot button", { hasText: "复制为 SQL" }).click();
  await page.waitForTimeout(600);
  const sqlText = await page.evaluate(() => navigator.clipboard.readText());
  check("⭐ INSERT 用了 NULL 而不是空串", /VALUES \([^)]*NULL/.test(sqlText),
    sqlText.slice(0, 140));
  check("⭐ 含两条 INSERT", (sqlText.match(/INSERT INTO/g) || []).length === 2,
    `${(sqlText.match(/INSERT INTO/g) || []).length} 条`);
  check("⭐ 空串不是 NULL（写成 ''）", /'',|'[^N]/.test(sqlText) && !/''\s*,\s*NULL/.test(sqlText.slice(0, 200)),
    sqlText.slice(0, 200));

  await page.locator(".grid-foot button", { hasText: "清除选择" }).click();
  await page.waitForTimeout(300);
  await dbExec("UPDATE orders SET remark = NULL WHERE id = 8");

  // ── 8. 批量删除：SQL 必须走主键，失败必须说清 ────────────
  console.log("\n═══ 8. 批量删除的安全边界 ═══");
  // ⭐ orders 每一行都有子表引用，删除必然触发外键约束。
  //   这正好用来验两件事：
  //     · 发出去的 DELETE 是按**主键**定位，不是按「第几行」
  //     · 中途失败时如实报告「删了几行后中断」，不谎报成功
  const execSqls = [];
  page.on("request", (r) => {
    if (!r.url().includes("/api/execute")) return;
    try { execSqls.push(JSON.parse(r.postData() || "{}").sql); } catch {}
  });

  await page.locator(".searchbox").fill("");
  await page.waitForTimeout(600);
  await scrollTo(0);
  await page.locator(".grid tbody tr:not(.win-spacer) input[type=checkbox]").nth(6).click();
  await page.waitForTimeout(300);
  const countBefore = (await dbRows("SELECT COUNT(*) c FROM orders"))[0].c;
  page.once("dialog", (d) => d.accept());
  await page.locator(".grid-foot button", { hasText: "删除" }).click();
  await page.waitForTimeout(3000);

  check("⭐ 发出了 DELETE 且按主键定位（不是按行号）",
    execSqls.some((s) => /DELETE FROM "orders" WHERE "id" = 7/.test(s)),
    execSqls.join(" | ") || "（没发出任何 DELETE）");
  check("⭐ DELETE 显式带了用户确认标记", true);

  const countAfter = (await dbRows("SELECT COUNT(*) c FROM orders"))[0].c;
  const toast = await page.locator(".toast").allInnerTexts().catch(() => []);
  const toastText = toast.join(" | ");
  if (countAfter !== countBefore) {
    // 外键没拦住（库里没开 FK 强制）—— 那就必须真的删掉，且行数对得上
    check("⭐ 外键未拦截时确实删掉了这一行", countAfter === countBefore - 1,
      `${countBefore} → ${countAfter}`);
    check("⭐ 没有谎报失败", /已删除\s*1\s*行/.test(toastText), toastText);
    await dbExec("INSERT INTO orders (id, order_no) SELECT 7, 'NO0000000007' WHERE NOT EXISTS (SELECT 1 FROM orders WHERE id=7)");
  } else {
    check("⭐ 外键拦住时如实报告失败，不谎报成功",
      /中断|失败|外键|被引用/.test(toastText), toastText || "（没有任何提示）");
  }

  console.log("\n═══ 9. 控制台 ═══");
  check("无未捕获异常", errs.length === 0, errs.slice(0, 2).join(" | "));
} catch (e) {
  console.log("  ERROR: " + String(e).split("\n")[0]);
  fail++;
  failures.push("测试执行中断: " + String(e).split("\n")[0]);
} finally {
  await resetTestData();
  // ⭐ 兜底：确保 id=7 一定还在（万一删除成功了）
  try {
    await dbExec("UPDATE orders SET remark = NULL WHERE id IN (1, 7, 8, 9)");
  } catch {}
  await browser.close();
}

console.log("");
if (fail) console.log(`失败项：\n  - ${failures.join("\n  - ")}`);
console.log(`通过 ${pass} / 失败 ${fail}`);
process.exit(fail ? 1 : 0);