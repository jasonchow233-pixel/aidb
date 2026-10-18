/**
 * ⭐ 编辑交互专项：A1-7 撤销/重做 + A1-8 类型化编辑器 + NULL/空串严格区分
 *
 * ⭐ 三条最容易出事的线：
 *
 *   1. **NULL 与空串**（`#10505`）
 *      把 NULL 改成空串必须产生一条真实的待保存改动。
 *      之前用 `String(a ?? "") === String(b ?? "")` 判「有没有变」，
 *      null 和 "" 都被归成 "" → 改动被**静默丢弃**：不报错、不提示，
 *      用户以为改好了，实际什么都没发生。
 *
 *   2. **撤销的粒度与净变化**
 *      · 粒度 = 一次「进入编辑 → 提交」，⌘Z 一次退回到进入编辑前的原值
 *      · 撤销回原值后，这一格必须从「待保存」里消失 ——
 *        否则会生成一条改了等于没改的 UPDATE
 *
 *   3. **类型化编辑器**
 *      枚举列给下拉、布尔列给复选框、JSON 列写坏了就地报错。
 *      控件类型只依据后端元数据，不按列名猜。
 */
import { chromium } from "playwright";

let pass = 0, fail = 0;
const failures = [];
const check = (l, ok, x = "") => {
  if (ok) { pass++; console.log(`  ok    ${l}`); }
  else { fail++; failures.push(l); console.log(`  FAIL  ${l}  ${x}`); }
};

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
const errs = [];
page.on("pageerror", (e) => errs.push(String(e).slice(0, 200)));
// ⭐ 不用全局 dialog 处理器：Playwright 一次 dialog 只能 accept 一次，
//    再 accept 会抛 "already handled" 把测试自己打断。
//    改成每次单独 page.once。

/** ⭐ 点「保存」并抓下确认框里展示的 SQL，返回 { sql, dismissed } */
async function saveAndCaptureSql() {
  let captured = "";
  const handler = async (d) => { captured = d.message(); await d.dismiss(); };
  page.once("dialog", handler);
  await page.locator(".toolbar button", { hasText: "保存" }).first().click();
  await page.waitForTimeout(1200);
  return captured;
}

/** ⭐ 点「保存」并**确认**，真正落库 */
async function saveAndConfirm() {
  page.once("dialog", async (d) => d.accept());
  await page.locator(".toolbar button", { hasText: "保存" }).first().click();
  await page.waitForTimeout(1800);
}

/** ⭐ 失败时要说清「卡在哪一步」——
 *    否则一个 30 秒的超时只留下一个 locator，不知道是哪个环节没出现。 */
let step = "(未开始)";
function at(name) { step = name; }

/** 表头文本 → 列下标 */
const colIdx = (name) =>
  page.evaluate((n) =>
    [...document.querySelectorAll(".grid thead th")].findIndex((t) =>
      t.innerText.trim().startsWith(n)), name);

/** 点第 r 行的第 name 列，并等编辑器真的出现。
 *
 *  ⚠ 必须等：保存后表格会整表重取，刷新途中点下去的行可能正好被替换掉，
 *    看起来像「点不开编辑器」，其实是点早了。重试一次即可自愈。 */
async function clickCell(r, name) {
  const i = await colIdx(name);
  const hit = () => page.evaluate(([ri, ci]) => {
    const tr = [...document.querySelectorAll(".grid tbody tr")]
      .filter((x) => !x.classList.contains("win-spacer"))[ri];
    if (!tr) return false;
    tr.querySelectorAll("td")[ci].click();
    return true;
  }, [r, i]);
  for (let attempt = 0; attempt < 3; attempt++) {
    if (!(await hit())) { await page.waitForTimeout(500); continue; }
    await page.waitForTimeout(400);
    if (await page.locator(".grid .cell-editor").count()) return;
  }
  throw new Error(`点不开第 ${r} 行的 ${name} 列`);
}

/** 第 r 行第 name 列的显示文本 */
async function cellText(r, name) {
  const i = await colIdx(name);
  return page.evaluate(([ri, ci]) => {
    const tr = [...document.querySelectorAll(".grid tbody tr")]
      .filter((x) => !x.classList.contains("win-spacer"))[ri];
    return tr.querySelectorAll("td")[ci].innerText.trim();
  }, [r, i]);
}

const pending = async () => {
  const t = await page.locator(".toolbar").innerText();
  return Number(t.match(/●\s*(\d+)\s*处未保存/)?.[1] ?? 0);
};

/** ⭐ 清空编辑缓冲。
 *  ⚠ 各小节必须互不污染：上一节留下的待保存改动会让下一节的
 *    「待保存数应该是 1」变成 2，失败信息指向撤销/重做，真因是前一段没清场。 */
async function clearBuffer() {
  const disc = page.locator(".toolbar button", { hasText: "放弃" });
  if (await disc.count()) {
    page.once("dialog", (d) => d.accept());
    await disc.click();
    await page.waitForTimeout(900);
  }
}

async function setCell(row, name, value) {
  await clickCell(row, name);
  const inp = page.locator(".grid .cell-editor").first();
  await inp.fill(value);
  await inp.press("Enter");
  await page.waitForTimeout(500);
}

/** ⭐ 直接问数据库要真相，不看页面 */
const BASE = "http://localhost:8787";
async function dbQuery(sql) {
  const r = await fetch(`${BASE}/api/query`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ sql }),
  });
  const j = await r.json();
  if (!j.ok) throw new Error(j.error?.message ?? "query failed");
  return j.data.page.rows;
}
async function dbExec(sql) {
  const r = await fetch(`${BASE}/api/execute`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ sql, requireConfirm: true }),
  });
  const j = await r.json();
  if (!j.ok) throw new Error(j.error?.message ?? "execute failed");
  return j.data;
}

/** ⭐ 把 orders 前 6 行的 remark 清成 NULL。
 *  ⚠ 不能假设「remark 本来就是 NULL」—— 库里本来就有几条带备注，
 *    照着错误的假设写断言，失败时报的是「撤销坏了」，真因是数据前提错了。 */
const RESET = "UPDATE orders SET remark = NULL WHERE id <= 6";
await dbExec(RESET);

try {
  // 用一张干净的小表：改坏了不影响别的用例，也便于逐行核对
  await page.goto("http://localhost:5173", { waitUntil: "domcontentloaded" });
  await page.waitForSelector(".tree button.node", { timeout: 30000 });
  await page.waitForTimeout(1800);
  await page.locator(".tree button.node", { hasText: "orders" }).first().click();
  await page.waitForSelector(".grid tbody tr:not(.win-spacer)", { timeout: 15000 });
  await page.waitForTimeout(1200);
  check("⭐ 前提：前 6 行 remark 均为 NULL",
    (await dbQuery("SELECT COUNT(*) c FROM orders WHERE remark IS NOT NULL AND id <= 6"))[0].c === 0);

  console.log("═══ 1. ⭐ NULL → 空串 必须产生真实改动 ═══");
  // 第 1 行的 remark 在库里是 NULL
  check("初始为 NULL", (await cellText(0, "remark")) === "NULL", await cellText(0, "remark"));
  await setCell(0, "remark", "");
  check("⭐ 显示从 NULL 变成空", (await cellText(0, "remark")) === "", `"${await cellText(0, "remark")}"`);
  check("⭐ 计入了待保存（没被静默丢弃）", (await pending()) === 1, `${await pending()}`);

  // ⭐ SQL 从**保存确认框**里读，不要用 `import('/src/lib/store.ts')`：
  //    动态 import 可能拿到另一个模块实例（另一份空 state），
  //    于是 buildSaveSql 返回空，断言会误报成「SQL 不对」。
  // ⭐ 列名在 SQL 里是带双引号的 `"remark"`，正则要按实际写法来。
  const sqlNow = await saveAndCaptureSql();
  check("⭐ 生成的 SQL 是 remark = ''（不是 NULL）",
    /"remark"\s*=\s*''/.test(sqlNow) && !/"remark"\s*=\s*NULL/.test(sqlNow),
    sqlNow.replace(/\n/g, " ").slice(0, 160));
  await clearBuffer();

  console.log("\n═══ 2. ⭐ 设 NULL / 设空串是两个独立动作 ═══");
  // ⭐ 必须先真的落一个非空值，再「设 NULL」，否则那是净变化 0、
  //    保存按钮压根不该出现（见 2.6）。这里走完整闭环：改 → 存 → 再改。
  await setCell(1, "remark", "临时值");
  check("先写入非空值", (await cellText(1, "remark")) === "临时值");
  await saveAndConfirm();
  check("⭐ 非空值已落库",
    (await dbQuery("SELECT remark FROM orders WHERE id = 2"))[0].remark === "临时值");

  await clickCell(1, "remark");
  const nullBtn = page.locator(".cell-edit-ops button", { hasText: "设 NULL" });
  const emptyBtn = page.locator(".cell-edit-ops button", { hasText: "设空串" });
  check("⭐ 有显式「设 NULL」按钮", (await nullBtn.count()) === 1);
  check("⭐ 有显式「设空串」按钮（独立于设 NULL）", (await emptyBtn.count()) === 1);
  await nullBtn.click();
  await page.waitForTimeout(500);
  check("⭐ 点「设 NULL」后显示 NULL", (await cellText(1, "remark")) === "NULL",
    await cellText(1, "remark"));

  const sqlNull = await saveAndCaptureSql();
  check("⭐ SQL 是 remark = NULL", /"remark"\s*=\s*NULL/.test(sqlNull),
    sqlNull.replace(/\n/g, " ").slice(0, 160));
  check("⭐ SET 段带了主键 WHERE", /WHERE\s+"id"\s*=\s*2/.test(sqlNull),
    sqlNull.replace(/\n/g, " ").slice(0, 200));
  // 确认框是「取消」，所以不该落库
  check("⭐ 点了取消就没落库",
    (await dbQuery("SELECT remark FROM orders WHERE id = 2"))[0].remark === "临时值",
    `remark=${JSON.stringify((await dbQuery("SELECT remark FROM orders WHERE id = 2"))[0].remark)}`);

  console.log("\n═══ 2.5 设空串也是独立动作 ═══");
  await clickCell(1, "remark");
  await page.locator(".cell-edit-ops button", { hasText: "设空串" }).click();
  await page.waitForTimeout(500);
  check("⭐ 点「设空串」后单元格为空（不是 NULL）", (await cellText(1, "remark")) === "",
    `"${await cellText(1, "remark")}"`);
  const sqlEmpty = await saveAndCaptureSql();
  check("⭐ SQL 是 ''（不是 NULL）",
    /"remark"\s*=\s*''/.test(sqlEmpty) && !/"remark"\s*=\s*NULL/.test(sqlEmpty),
    sqlEmpty.replace(/\n/g, " ").slice(0, 160));
  at("2.5 保存空串");
  await saveAndConfirm();

  console.log("\n═══ 2.6 净变化为 0 时不该留下待保存 ═══");
  // 库里此刻是 ''，界面显示 ''
  at("2.6 点开单元格");
  await clickCell(1, "remark");
  at("2.6 检查按钮可用态");
  check("⭐ 值已经是空串 → 「设空串」置灰（不给用户按一个没用的按钮）",
    await page.locator(".cell-edit-ops button", { hasText: "设空串" }).isDisabled());
  check("⭐ 值不是 NULL → 「设 NULL」可用",
    !(await page.locator(".cell-edit-ops button", { hasText: "设 NULL" }).isDisabled()));
  // '' → NULL 相对**库里的当前值**是真实变化，理应被记下来
  at("2.6 设 NULL");
  await page.locator(".cell-edit-ops button", { hasText: "设 NULL" }).click();
  await page.waitForTimeout(500);
  check("改 ''→NULL 记为待保存", (await pending()) === 1, `${await pending()}`);

  // 再改回 '' = 净变化 0
  at("2.6 再点开单元格");
  await clickCell(1, "remark");
  at("2.6 设回空串");
  await page.locator(".cell-edit-ops button", { hasText: "设空串" }).click();
  await page.waitForTimeout(500);
  check("⭐ 改回原值后待保存归零（不生成改了等于没改的 UPDATE）",
    (await pending()) === 0, `${await pending()}`);
  check("⭐ 保存按钮随之消失",
    (await page.locator(".toolbar button", { hasText: "保存" }).count()) === 0);
  check("⭐ 撤销栈也跟着空（无净变化就没有可撤销的）",
    await page.locator(".toolbar button[title*='撤销']").isDisabled());
  // 收尾：把库里那行还原
  at("2.6 还原数据库");
  await dbExec("UPDATE orders SET remark = NULL WHERE id = 2");
  at("2.6 重新加载表格");
  // ⭐ 用工具栏的「刷新」而不是改 select：对已经是当前值的 select 再选一次
  //   不会触发 change，界面会停在旧数据上，下一节的断言就会验一份没人看过的快照。
  const refreshBtn = page.locator(".page-head button", { hasText: "刷新" });
  if (await refreshBtn.count()) {
    await refreshBtn.first().click({ timeout: 10000 });
    await page.waitForTimeout(1800);
  } else {
    await page.reload({ waitUntil: "domcontentloaded" });
    await page.waitForSelector(".tree button.node", { timeout: 30000 });
    await page.waitForTimeout(1500);
    await page.locator(".tree button.node", { hasText: "orders" }).first().click();
    await page.waitForSelector(".grid tbody tr:not(.win-spacer)", { timeout: 15000 });
    await page.waitForTimeout(1200);
  }
  check("还原后界面回到 NULL", (await cellText(1, "remark")) === "NULL",
    await cellText(1, "remark"));

  console.log("\n═══ 3. ⭐ 撤销 ═══");
  await setCell(1, "remark", "待撤销的值");
  check("撤销键可用", !(await page.locator(".toolbar button[title*='撤销']").isDisabled()));
  await page.locator(".toolbar button[title*='撤销']").click();
  await page.waitForTimeout(1200);
  check("⭐ 撤销后回到进入编辑前的值", (await cellText(1, "remark")) === "NULL",
    await cellText(1, "remark"));
  check("⭐ 撤销后待保存归零", (await pending()) === 0, `${await pending()}`);

  console.log("\n═══ 4. ⭐ 多次编辑只占一步 ═══");
  await setCell(3, "remark", "第一版");
  await setCell(3, "remark", "第二版");
  await setCell(3, "remark", "第三版");
  check("改过三次", (await cellText(3, "remark")) === "第三版");
  check("⭐ 同一格多次编辑只记 1 处待保存", (await pending()) === 1, `${await pending()}`);
  await page.locator(".toolbar button[title*='撤销']").click();
  await page.waitForTimeout(1200);
  check("⭐ 一次 ⌘Z 直接回到原值（不是回到第二版）",
    (await cellText(3, "remark")) === "NULL", await cellText(3, "remark"));
  check("⭐ 待保存随之归零", (await pending()) === 0, `${await pending()}`);
  await clearBuffer();

  console.log("\n═══ 5. ⭐ 重做 ═══");
  await setCell(4, "remark", "可重做的值");
  check("已产生改动", (await pending()) === 1, `${await pending()}`);
  await page.locator(".toolbar button[title*='撤销']").click();
  await page.waitForTimeout(1200);
  check("撤销后回到 NULL", (await cellText(4, "remark")) === "NULL", await cellText(4, "remark"));
  check("撤销后待保存归零", (await pending()) === 0, `${await pending()}`);
  check("重做键变可用", !(await page.locator(".toolbar button[title*='重做']").isDisabled()));
  await page.locator(".toolbar button[title*='重做']").click();
  await page.waitForTimeout(1200);
  check("⭐ 重做恢复了改动", (await cellText(4, "remark")) === "可重做的值",
    await cellText(4, "remark"));
  check("⭐ 重做后待保存数仍是 1（没重复记账）", (await pending()) === 1, `${await pending()}`);
  const redoSql = await saveAndCaptureSql();
  check("⭐ 重做后的 SQL 仍带主键 WHERE", /WHERE\s+"id"/.test(redoSql),
    redoSql.replace(/\n/g, " ").slice(0, 160));
  await clearBuffer();

  console.log("\n═══ 6. ⭐ 新改动要清空重做栈 ═══");
  // ⚠ 必须先造出一个「撤销过、重做可用」的状态；
  //   紧跟 clearBuffer 之后撤销键是灰的，点它只会等 30 秒超时。
  at("6 造一次改动");
  await setCell(5, "remark", "第一次");
  at("6 撤销它");
  await page.locator(".toolbar button[title*='撤销']").click();
  await page.waitForTimeout(1200);
  check("撤销后重做键又可用", !(await page.locator(".toolbar button[title*='重做']").isDisabled()));
  at("6 再改一次别的");
  await setCell(5, "remark", "新值");
  check("⭐ 新改动后重做键变灰（标准编辑器行为）",
    await page.locator(".toolbar button[title*='重做']").isDisabled());

  console.log("\n═══ 6.5 放弃要连带清掉撤销栈 ═══");
  page.once("dialog", (d) => d.accept());
  await page.locator(".toolbar button", { hasText: "放弃" }).click();
  await page.waitForTimeout(1200);
  check("放弃后待保存归零", (await pending()) === 0);
  check("⭐ 放弃后撤销键变灰（不能把放弃的改动变回来）",
    await page.locator(".toolbar button[title*='撤销']").isDisabled());
  check("单元格已回到 NULL", (await cellText(5, "remark")) === "NULL", await cellText(5, "remark"));

  console.log("\n═══ 7. ⭐ 类型化编辑器：枚举列 ═══");
  await clickCell(0, "status");
  check("⭐ 枚举列给的是下拉而不是文本框",
    (await page.locator(".grid .cell-editor").first().evaluate(
      (el) => el.tagName.toLowerCase())) === "select");
  const optTexts = await page.locator(".grid .cell-editor option").allInnerTexts();
  check("⭐ 选项来自 enumValues（含中文含义 + 原值）",
    optTexts.length >= 3 && optTexts.some((o) => /已支付/.test(o)) && optTexts.some((o) => /\d/.test(o)),
    optTexts.join(" | "));
  check("⭐ 选一项就提交", (await page.locator(".grid .cell-editor").count()) === 1);
  // ⭐ 必须挑一个与当前值**不同**的选项：commitEdit 对「值没变」的记录
  //    本来就会跳过（sameValue），选到当前值等于什么都没做。
  //    ⚠ 不能写死 index：演示库重新物化后各行 status 会变，
  //      写死就会随机挂（曾经 row1 的 status 恰好等于 index 1 的值）。
  const curLabel = (await page.locator(".grid tbody tr:not(.win-spacer)")
    .first().locator('td[data-col="status"]').innerText()).trim();
  const pickIdx = optTexts.findIndex((t) => !t.startsWith(curLabel));
  check("找到与当前值不同的枚举选项（否则这次编辑等于没改）",
    pickIdx >= 0, `当前=${curLabel} 选项=${optTexts.join(" | ")}`);
  await page.locator(".grid .cell-editor").first().selectOption({ index: pickIdx });
  await page.waitForTimeout(600);
  check("枚举值已进待保存", (await pending()) === 1, `${await pending()}`);
  const enumSql = await saveAndCaptureSql();
  check("⭐ 生成的是数字枚举值而不是字符串",
    /"status"\s*=\s*\d+/.test(enumSql) && !/"status"\s*=\s*'\d+'/.test(enumSql),
    enumSql.replace(/\n/g, " ").slice(0, 160));
  await page.locator(".toolbar button[title*='撤销']").click();
  await page.waitForTimeout(1000);

  console.log("\n═══ 8. ⭐ 类型化编辑器：JSON 列 ═══");
  await clearBuffer();
  // ⭐ 列的 category 由后端给：TEXT + 中文注释「JSON 扩展信息」→ category=json。
  //    控件选择只认这个字段，不看列名、不看注释。
  await page.locator(".tree button.node", { hasText: "users" }).first().click();
  await page.waitForSelector(".grid tbody tr:not(.win-spacer)", { timeout: 15000 });
  await page.waitForTimeout(1200);
  const profCol = await colIdx("profile");
  check("找到 JSON 类列（users.profile）", profCol >= 0, `列下标=${profCol}`);

  await clickCell(0, "profile");
  const inp = page.locator(".grid .cell-editor").first();
  check("⭐ JSON 列给的是文本框", (await inp.count()) === 1);
  await inp.fill("{ 坏掉的 json ");
  await inp.press("Enter");
  await page.waitForTimeout(500);
  check("⭐ JSON 写坏了就地报错", (await page.locator(".cell-edit-err").count()) === 1);
  check("⭐ 出错时不退出编辑态（还停在框里）",
    (await page.locator(".grid .cell-editor").count()) === 1);
  check("⭐ 坏值没进待保存", (await pending()) === 0, `${await pending()}`);

  await inp.fill('{"theme":"light"}');
  await inp.press("Enter");
  await page.waitForTimeout(600);
  check("⭐ 改成合法 JSON 后接受", (await pending()) === 1, `${await pending()}`);
  check("⭐ 报错提示已消失", (await page.locator(".cell-edit-err").count()) === 0);

  const jsonSql = await saveAndCaptureSql();
  check("⭐ JSON 序列化成内容而不是 '[object Object]'",
    /"profile"\s*=\s*'\{"theme":"light"\}'/.test(jsonSql)
    && !/\[object Object\]/.test(jsonSql),
    jsonSql.replace(/\n/g, " ").slice(0, 200));

  console.log("\n═══ 8.5 数值列不被写成字符串 ═══");
  await clearBuffer();
  await setCell(0, "balance_cents", "8888");
  const numSql = await saveAndCaptureSql();
  check("⭐ 数值列不加引号", /"balance_cents"\s*=\s*8888/.test(numSql)
    && !/"balance_cents"\s*=\s*'8888'/.test(numSql),
    numSql.replace(/\n/g, " ").slice(0, 200));

  console.log("\n═══ 9. 清理 ═══");
  const disc = page.locator(".toolbar button", { hasText: "放弃" });
  if (await disc.count()) {
    page.once("dialog", (d) => d.accept());
    await disc.click();
    await page.waitForTimeout(1000);
  }
  check("缓冲已清空", (await pending()) === 0, `${await pending()}`);

  // ⭐ 兜底：确认这轮测试没在库里留下任何东西
  check("⭐ 数据库没被这轮测试留下脏数据",
    (await dbQuery("SELECT COUNT(*) c FROM orders WHERE remark IS NOT NULL AND id <= 6"))[0].c === 0);

  console.log("\n═══ 10. 控制台 ═══");
  check("无未捕获异常", errs.length === 0, errs.slice(0, 2).join(" | "));
} catch (e) {
  console.log("  ERROR: " + String(e).split("\n")[0]);
  fail++;
  failures.push(`测试执行中断 @ ${step}: ` + String(e).split("\n")[0]);
} finally {
  // ⭐ 兜底还原：这一轮不管中途怎么挂，都不能把脏数据留给下一个人
  try { await dbExec(RESET); } catch {}
  await browser.close();
}

console.log("");
if (fail) console.log(`失败项：\n  - ${failures.join("\n  - ")}`);
console.log(`通过 ${pass} / 失败 ${fail}`);
process.exit(fail ? 1 : 0);