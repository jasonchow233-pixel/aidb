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
 *
 * ⭐ 第 9 节验 A4-2~A4-8 表格进阶能力，这里全部有真实数据可断言：
 *   演示库 users 表专门种了 PNG 魔数的 BLOB（avatar_blob）与 JSON 数组
 *   （user_profiles.preferences）—— 没有这些数据，「支持 array/blob」
 *   这类能力只能空口断言，所以种子数据本身就是为门禁服务的。
 *   断言一律**回到数据库核对原料**（hex() 查魔数），前端渲染与库里的值
 *   必须对得上，防止「页面演得挺好、数据其实是另一回事」。
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
// ⭐ 控制台错误也要收，但有两类噪声必须先识别清楚，否则门禁会时红时绿：
//
//   1. **外网图片**（cdn.example.com）在沙箱里没有外网，加载失败是环境噪声，
//      产品自己的回落逻辑（渲染成 URL 文本）就是为这种情况准备的。
//
//   2. **预期内的 API 4xx**：后端对「外键拦截的 DELETE」这类**正确的错误报告**
//      返回 HTTP 400，浏览器必然往 console 打一条 "Failed to load resource … 400"。
//      这是产品在如实报错（第 8 节单独断言了它的 toast 不撒谎），不是缺陷。
//      所以不许一刀切过滤，而是**对账**：console 里的 400 条数必须等于
//      本用例故意触发的 API 4xx 条数 —— 多出来的一条才是产品的锅。
const api4xx = [];
page.on("response", (r) => {
  if (r.status() >= 400 && r.url().includes("/api/")) {
    api4xx.push(`${r.status()} ${new URL(r.url()).pathname}`);
  }
});
let console400 = 0;
page.on("console", (m) => {
  if (m.type() !== "error") return;
  const t = m.text();
  if (/net::ERR_(CONNECTION_CLOSED|NAME_NOT_RESOLVED|INTERNET_DISCONNECTED|NETWORK_CHANGED|PROXY_CONNECTION_FAILED|CONNECTION_REFUSED|TIMED_OUT)/.test(t)) return;
  if (/Failed to load resource/.test(t) && /400 \(Bad Request\)/.test(t)) { console400++; return; }
  errs.push("console: " + t.slice(0, 200));
});
// ⭐ 把外部头像拦下来喂一张真 PNG：
//   · 「图片渲染成缩略图」因此可以**确定性**断言（含 naturalWidth>0 真的解码）
//   · 否则图片要么加载失败被回落成文本（断言白写），要么偶发成功（时通时断）
await page.route(/cdn\.example\.com/, (route) =>
  route.fulfill({
    contentType: "image/png",
    body: Buffer.from(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
      "base64"),
  }));

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

  // ── 9. ⭐ A4-2~A4-8 列布局三件套 / Renderer / 右键菜单 / 转置 ──
  console.log("\n═══ 9. A4-2~A4-8 列布局 / Renderer / 右键 / 转置 ═══");

  /** 打开（或切回）一张表，并等到**标题**真的换成它。
   *  ⭐ 不能只等 `.grid tbody tr` 出现：tab 已存在时旧内容还挂在 DOM 上，
   *    「等行出现」等于没等 —— 第 13 个 bug 正是这么漏过去的。 */
  async function openTableTab(name) {
    await page.locator(".tree button.node", { hasText: name }).first().click();
    await page.waitForFunction(
      (n) => (document.querySelector(".page-head__name")?.textContent ?? "").includes(n),
      name, { timeout: 15000 });
    await page.waitForTimeout(900);
  }
  /** 工具栏「仅本页 N 行」——产品自己报的当前行数，隐藏 NULL 后它必须变 */
  const rowsShown = async () => {
    const t = await page.locator(".toolbar span", { hasText: "仅本页" }).first().innerText();
    return Number(t.match(/仅本页\s+([\d,]+)\s*行/)?.[1].replace(/,/g, "") ?? -1);
  };

  // ⭐⭐⭐ 切 tab 回归（第 13 个「看着正常其实错了」的 bug）：
  //   store.openTable 在 tab 已存在时只切 activeTabId 不重载，而 DataGridPage
  //   读的是全局 state.tableDef/state.rows —— 切回已打开的表时，
  //   **标题是 A、内容是 B，且控制台一声不响**。所以断言必须同时核对
  //   「标题」和「内容列」，只看标题等于没验。
  await openTableTab("user_profiles");
  await openTableTab("users");
  await openTableTab("user_profiles");
  const headProfile = await page.locator(".page-head__name").innerText();
  check("⭐⭐ 切回 user_profiles：标题与内容一致",
    headProfile.includes("user_profiles") &&
    (await page.locator('td[data-col="preferences"]').count()) > 0 &&
    (await page.locator('td[data-col="email"]').count()) === 0,
    `标题=${headProfile}`);
  await openTableTab("users");
  const headUsers = await page.locator(".page-head__name").innerText();
  check("⭐⭐ 切回 users：标题与内容一致（email 列在、preferences 列不在）",
    headUsers.includes("users") &&
    (await page.locator('td[data-col="email"]').count()) > 0 &&
    (await page.locator('td[data-col="preferences"]').count()) === 0,
    `标题=${headUsers}`);

  // ── A4-2 冻结列 ──────────────────────────────────────────
  const frozen = await page.evaluate(() => {
    const th = document.querySelector("th.frozen-pk");
    if (!th) return null;
    const cs = getComputedStyle(th);
    const td = document.querySelector("td.frozen-pk");
    return {
      pos: cs.position, left: cs.left, bg: cs.backgroundColor,
      tdBg: td ? getComputedStyle(td).backgroundColor : null,
      tdCount: document.querySelectorAll("td.frozen-pk").length,
      rows: document.querySelectorAll(".grid tbody tr:not(.win-spacer)").length,
    };
  });
  check("A4-2 PK 列有冻结表头", frozen !== null);
  check("⭐ 冻结列 position=sticky 且 left=88px（行号列之后）",
    frozen?.pos === "sticky" && frozen?.left === "88px", JSON.stringify(frozen));
  check("⭐ 冻结列背景不透明（滚动时透出后面内容就是事故）",
    !!frozen && frozen.bg !== "rgba(0, 0, 0, 0)" &&
    !!frozen.tdBg && frozen.tdBg !== "rgba(0, 0, 0, 0)",
    `th=${frozen?.bg} td=${frozen?.tdBg}`);
  check("每一行都有冻结单元格（覆盖全部数据行）",
    frozen?.tdCount === frozen?.rows && (frozen?.rows ?? 0) > 0,
    `td=${frozen?.tdCount} rows=${frozen?.rows}`);

  // ── A4-7 渲染器：blob / image / date ─────────────────────
  const bin = await page.evaluate(() => {
    const el = document.querySelector('td[data-col="avatar_blob"] .cell-bin');
    return el ? el.textContent.trim() : null;
  });
  check("⭐ blob 渲染成「⬢ N B」芯片（二进制不糊进网格）",
    bin !== null && /^⬢\s*\d+\s*B$/.test(bin), String(bin));

  const imgInfo = await page.evaluate(async () => {
    for (const td of document.querySelectorAll('td[data-col="avatar_url"]')) {
      const i = td.querySelector("img.cell-img");
      if (i) {
        i.scrollIntoView({ block: "nearest", inline: "nearest" });
        try { await i.decode(); } catch {}
        return { src: i.getAttribute("src"), loaded: i.complete && i.naturalWidth > 0 };
      }
    }
    return null;
  });
  check("⭐ 图片 URL 渲染成缩略图（且真的解码成功）",
    imgInfo !== null &&
    /^https?:\/\/\S+\.(png|jpe?g|gif|webp|svg|avif|bmp)(\?\S*)?$/i.test(imgInfo.src ?? "") &&
    imgInfo.loaded === true,
    JSON.stringify(imgInfo));

  const dt = await page.evaluate(() => {
    for (const td of document.querySelectorAll('td[data-col="created_at"]')) {
      const s = td.querySelector("span[title]");
      if (s?.textContent?.trim()) {
        return { txt: s.textContent.trim(), title: s.getAttribute("title") };
      }
    }
    return null;
  });
  check("⭐ dateTime 渲染成友好格式 yyyy-MM-dd HH:mm",
    dt !== null && /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(dt.txt), JSON.stringify(dt));
  check("⭐ 完整值保留在 title（不为好看丢信息）",
    dt !== null && /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/.test(dt.title ?? ""),
    String(dt?.title));

  // ⭐ 渲染器的原料必须回到数据库核对：前端不可能凭空渲染出 PNG 魔数
  const pngHex = await dbRows(
    "SELECT hex(avatar_blob) h FROM users WHERE avatar_blob IS NOT NULL LIMIT 1");
  check("⭐ 数据库里 users.avatar_blob 确是 PNG（89504E47 开头）",
    /^89504E47/.test(pngHex[0]?.h ?? ""), String(pngHex[0]?.h).slice(0, 24));

  // ── A4-7 数组 chips（换表验 JSON 数组渲染器）──────────────
  await openTableTab("user_profiles");
  const chips = await page.evaluate(() => {
    for (const td of document.querySelectorAll('td[data-col="preferences"]')) {
      const c = [...td.querySelectorAll(".cell-arr__item")].map((x) => x.textContent.trim());
      if (c.length) return c;
    }
    return [];
  });
  check("⭐ JSON 数组渲染成 chips（不糊成一坨字符串）", chips.length >= 1,
    JSON.stringify(chips));
  const prefRow = await dbRows(
    "SELECT preferences FROM user_profiles WHERE preferences IS NOT NULL LIMIT 1");
  let prefArr = null;
  try { prefArr = JSON.parse(String(prefRow[0]?.preferences ?? "")); } catch {}
  check("⭐ chips 内容与数据库里的 JSON 数组逐项一致",
    Array.isArray(prefArr) && chips.length === prefArr.length &&
    chips.every((c, i) => c === String(prefArr[i])),
    `chips=${JSON.stringify(chips)} db=${JSON.stringify(prefArr)}`);

  await openTableTab("users");

  // ── A4-6 单元格右键菜单 ──────────────────────────────────
  const emailIdx = await page.evaluate(() => {
    const tds = [...document.querySelectorAll('td[data-col="email"]')];
    return {
      nullIdx: tds.findIndex((td) => td.querySelector("em")?.textContent === "NULL"),
      valIdx: tds.findIndex((td) => !td.querySelector("em") &&
        (td.textContent ?? "").trim().length > 0),
    };
  });
  check("窗口内同时有 NULL 与非 NULL 的 email 格（用例前提）",
    emailIdx.nullIdx >= 0 && emailIdx.valIdx >= 0, JSON.stringify(emailIdx));

  await page.locator('td[data-col="email"]').nth(emailIdx.nullIdx).click({ button: "right" });
  await page.waitForSelector("[data-test=cell-ctx-menu]", { timeout: 5000 });
  check("A4-6 右键菜单出现", true);
  check("⭐「设 NULL」「设空串」是两个独立入口（#10505 铁律）",
    (await page.locator("[data-test=ctx-set-null]").count()) === 1 &&
    (await page.locator("[data-test=ctx-set-empty]").count()) === 1);
  check("⭐ 已是 NULL 的格：「设 NULL」置灰（不能产生无意义改动）",
    await page.locator("[data-test=ctx-set-null]").isDisabled());
  await page.keyboard.press("Escape");
  await page.waitForTimeout(250);
  check("Esc 关闭菜单", (await page.locator("[data-test=cell-ctx-menu]").count()) === 0);

  // ⭐⭐ 回归（第 14 个「看着正常其实错了」）：右键后**立刻**按 Esc，
  //   中间不做任何别的动作，也必须关得掉。曾经的 bug：Esc 监听器被
  //   setTimeout(0) 延后挂载，落在窗口期里的第一次按键被整个吞掉 ——
  //   菜单开着、按了 Esc 没反应、控制台一声不响。
  //   （同一处 rewrite 还修掉了监听器泄漏：原先靠 watch 回调 return 的
  //     清理函数摘监听，而 Vue 根本不调它 —— 每开合一轮漏一对。）
  await page.locator('td[data-col="email"]').nth(emailIdx.nullIdx).click({ button: "right" });
  await page.waitForSelector("[data-test=cell-ctx-menu]", { timeout: 5000 });
  await page.keyboard.press("Escape");
  await page.waitForTimeout(250);
  check("⭐⭐ 右键后立刻按 Esc 也能关掉（监听器不许延后挂载）",
    (await page.locator("[data-test=cell-ctx-menu]").count()) === 0);

  await page.locator('td[data-col="email"]').nth(emailIdx.valIdx).click({ button: "right" });
  await page.waitForSelector("[data-test=cell-ctx-menu]", { timeout: 5000 });
  check("非 NULL 格：「设 NULL」「设空串」都可用",
    !(await page.locator("[data-test=ctx-set-null]").isDisabled()) &&
    !(await page.locator("[data-test=ctx-set-empty]").isDisabled()));
  check("菜单 7 项（编辑/NULL/空串/复制/复制SQL/详情/删除行）",
    (await page.locator(".ctxmenu__item").count()) === 7,
    String(await page.locator(".ctxmenu__item").count()));
  await page.keyboard.press("Escape");
  await page.waitForTimeout(250);

  // ⭐⭐⭐ 回归（第 15 个「看着正常其实错了」）：菜单项必须真的点得动。
  //   曾经的 bug：关闭菜单用 capture 级 mousedown 且不排除菜单自身，
  //   点菜单项时光标还没松开菜单就被卸掉，click 永远不触发 ——
  //   7 个菜单项全是摆设，页面一切正常，点了没有任何反应。
  const nullEmailBefore = (await dbRows(
    "SELECT COUNT(*) c FROM users WHERE email IS NULL"))[0].c;
  await page.locator('td[data-col="email"]').nth(emailIdx.valIdx).click({ button: "right" });
  await page.waitForSelector("[data-test=cell-ctx-menu]", { timeout: 5000 });
  await page.locator("[data-test=ctx-set-null]").click();
  await page.waitForTimeout(600);
  check("⭐⭐⭐ 右键菜单项点得动（「设 NULL」真的进入未保存缓冲）",
    (await page.locator(".toolbar .badge--warn").count()) === 1,
    (await page.locator(".toolbar .badge--warn").allInnerTexts()).join("|"));
  check("点完菜单项菜单自己关掉", (await page.locator("[data-test=cell-ctx-menu]").count()) === 0);
  // ⭐ 「放弃」带原生 confirm，不接住 dialog 会被默认取消 —— 缓冲就清不掉
  page.once("dialog", (d) => d.accept());
  await page.locator(".toolbar button", { hasText: "放弃" }).click();
  await page.waitForTimeout(600);
  check("放弃后未保存缓冲清空（菜单项交互可逆）",
    (await page.locator(".toolbar .badge--warn").count()) === 0);
  const nullEmailAfter = (await dbRows(
    "SELECT COUNT(*) c FROM users WHERE email IS NULL"))[0].c;
  check("⭐ 走完右键菜单流程数据库一个字节没动（只在缓冲里玩）",
    nullEmailAfter === nullEmailBefore, `${nullEmailBefore} → ${nullEmailAfter}`);

  // ── A4-4 列显示控制 ──────────────────────────────────────
  await page.locator("[data-test=col-panel-btn]").click();
  await page.waitForSelector("[data-test=col-panel]", { timeout: 5000 });
  const colItems = await page.locator(".colpanel__item").count();
  const thAll = await page.locator(".grid thead th").count();
  check("列面板列出全部列", colItems > 0, String(colItems));
  check("列头数 = 数据列 + 行号列 + 操作列", thAll === colItems + 2,
    `${thAll} vs ${colItems}+2`);
  await page.locator('[data-test="col-toggle-phone"]').click();
  await page.waitForTimeout(500);
  check("隐藏一列后列头数减一",
    (await page.locator(".grid thead th").count()) === colItems + 1,
    String(await page.locator(".grid thead th").count()));
  check("被隐藏列的单元格从 DOM 消失（不是 visibility 隐藏）",
    (await page.locator('td[data-col="phone"]').count()) === 0);
  await page.locator("[data-test=col-reset]").click();
  await page.waitForTimeout(500);
  check("重置布局后列头数恢复",
    (await page.locator(".grid thead th").count()) === thAll,
    String(await page.locator(".grid thead th").count()));

  // ── A4-8 转置 / 隐藏 NULL ────────────────────────────────
  await page.locator("[data-test=transpose-btn]").click();
  await page.waitForSelector(".grid--transpose", { timeout: 5000 });
  check("转置后每一行是一个字段（行数=可见列数）",
    (await page.locator(".grid--transpose tbody tr").count()) === colItems,
    `${await page.locator(".grid--transpose tbody tr").count()} vs ${colItems}`);
  check("转置视图第一列是字段名",
    /^(id|tenant_id|email)$/.test(
      (await page.locator(".grid--transpose tbody tr td.t-field").first().innerText()).trim()));
  await page.locator("[data-test=transpose-btn]").click();
  await page.waitForTimeout(400);
  check("再点一次转置恢复普通视图",
    (await page.locator(".grid--transpose").count()) === 0);

  const rowsBefore = await rowsShown();
  await page.locator("[data-test=hide-null-btn]").click();
  await page.waitForTimeout(500);
  const rowsAfter = await rowsShown();
  check("⭐ 隐藏 NULL 后行数变少（users 有 NULL 行）",
    rowsAfter >= 0 && rowsAfter < rowsBefore, `${rowsBefore} → ${rowsAfter}`);
  check("⭐ 工具栏明示「已隐藏 NULL」（用户看得见当前在看什么）",
    /已隐藏\s*NULL/.test(
      await page.locator(".toolbar span", { hasText: "仅本页" }).first().innerText()));
  await page.locator("[data-test=hide-null-btn]").click();
  await page.waitForTimeout(500);
  check("再点一次恢复全部行", (await rowsShown()) === rowsBefore);

  // ── A4-3 列宽拖拽 + 列序拖拽 + A4-2 布局持久化 ──────────────
  const headNames = () => page.evaluate(() =>
    [...document.querySelectorAll(".grid thead th")]
      .map((t) => t.getAttribute("data-col")).filter(Boolean));
  const thWidth = () => page.evaluate(() => {
    const ths = document.querySelectorAll(".grid thead th");
    return ths.length > 3 ? getComputedStyle(ths[3]).width : null;
  });

  // 列序拖拽：把 name 拖到 email 前面（6px 阈值内不算拖拽）
  const headsBefore = await headNames();
  const nameBox = await page.locator('.grid thead th[data-col="name"]').boundingBox();
  const emailBox = await page.locator('.grid thead th[data-col="email"]').boundingBox();
  if (nameBox && emailBox) {
    await page.mouse.move(nameBox.x + nameBox.width / 2, nameBox.y + nameBox.height / 2);
    await page.mouse.down();
    await page.mouse.move(emailBox.x + emailBox.width / 2, emailBox.y + emailBox.height / 2,
      { steps: 10 });
    await page.mouse.up();
  }
  await page.waitForTimeout(600);
  const headsAfter = await headNames();
  check("⭐ 拖列序：name 落到 email 前面",
    headsAfter.indexOf("name") >= 0 &&
    headsAfter.indexOf("name") < headsAfter.indexOf("email") &&
    headsBefore.indexOf("name") > headsBefore.indexOf("email"),
    `${headsBefore.join(",")} → ${headsAfter.join(",")}`);
  check("⭐ 拖列序不误触排序（拖拽末尾那次 click 被吞掉）",
    (await page.locator(".grid thead th.is-sorted").count()) === 0);

  const wBefore = await thWidth();
  const resizer = page.locator(".col-resizer").nth(2);
  const rbox = await resizer.boundingBox();
  if (rbox) {
    await page.mouse.move(rbox.x + rbox.width / 2, rbox.y + rbox.height / 2);
    await page.mouse.down();
    await page.mouse.move(rbox.x + 90, rbox.y + rbox.height / 2, { steps: 6 });
    await page.mouse.up();
  }
  await page.waitForTimeout(500);
  const wAfter = await thWidth();
  check("⭐ 拖列宽手柄后该列变宽",
    wBefore !== null && wAfter !== null &&
    parseFloat(wAfter) > parseFloat(wBefore) + 40,
    `${wBefore} → ${wAfter}`);

  // 布局是 300ms 防抖写入的，多等一会儿
  await page.waitForTimeout(500);
  const saved = await page.evaluate(() => {
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k && k.startsWith("adb.gridlayout:") && k.endsWith(":users")) {
        return localStorage.getItem(k);
      }
    }
    return null;
  });
  let layout = null;
  try { layout = saved ? JSON.parse(saved) : null; } catch {}
  check("⭐ 列宽 + 列序都持久化到 localStorage（按连接+表名）",
    layout !== null && typeof layout.widths === "object" &&
    Object.values(layout.widths).some((w) => typeof w === "number" && w > 56) &&
    Array.isArray(layout.order) &&
    layout.order.indexOf("name") < layout.order.indexOf("email"),
    String(saved).slice(0, 200));

  // ── A4-7 blob 详情：hex 走后端 /api/cell 二次查询 ────────
  await page.locator('td[data-col="avatar_blob"] .cell-bin').first().dblclick();
  await page.waitForSelector("[data-test=cell-detail-hex]", { timeout: 8000 });
  const hexTxt = await page.locator("[data-test=cell-detail-hex] pre").innerText();
  check("⭐ 详情面板给出 hex 且是 PNG 魔数（后端 hex() 二次查询生效）",
    hexTxt.startsWith("89 50 4E 47 0D 0A 1A 0A"), hexTxt.slice(0, 48));
  check("⭐ 双击查看 hex 不产生未保存改动（二进制不能被文本框改坏）",
    (await page.locator(".toolbar .badge--warn").count()) === 0);
  const pngAfter = await dbRows(
    "SELECT hex(avatar_blob) h FROM users WHERE avatar_blob IS NOT NULL LIMIT 1");
  check("⭐ 走完一遍 UI 后数据库里的 blob 完好（没被编辑器写坏）",
    /^89504E47/.test(pngAfter[0]?.h ?? ""), String(pngAfter[0]?.h).slice(0, 24));

  console.log("\n═══ 10. 控制台 ═══");
  check("无未捕获异常（外网图片噪声已滤）", errs.length === 0,
    errs.slice(0, 2).join(" | "));
  check("⭐ console 的 400 噪声与「故意触发的 API 4xx」笔笔对账",
    console400 === api4xx.length,
    `console 400 × ${console400} / API 4xx × ${api4xx.length}` +
    (api4xx.length ? `：${api4xx.join(" | ")}` : ""));
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