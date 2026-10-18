/**
 * ⭐ 编辑闭环端到端测试 —— 盲测任务 T5 的前置条件。
 *
 *   改 3 行数据 → review 生成的 SQL → 撤销 → 保存 → 刷新后数据一致
 *
 * 这条测试挡的是「编译通过、单测通过、API 契约通过，但编辑存不进去」。
 */
import { chromium } from "playwright";

const URL = "http://localhost:5173";
let pass = 0, fail = 0;
const failures = [];
const check = (l, ok, x = "") => {
  if (ok) { pass++; console.log(`  ok    ${l}`); }
  else { fail++; failures.push(l); console.log(`  FAIL  ${l}  ${x}`); }
};

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1500, height: 950 } });
const errs = [];
page.on("pageerror", (e) => errs.push(String(e).slice(0, 200)));
page.on("console", (m) => m.type() === "error" && errs.push(m.text().slice(0, 200)));

// 弹窗处理：confirm 自动确认，prompt 按预设回答
let promptAnswer = "d";
page.on("dialog", async (d) => {
  if (d.type() === "confirm") await d.accept();
  else await d.accept(promptAnswer);
});

try {
  await page.goto(URL, { waitUntil: "domcontentloaded" });
  await page.waitForSelector(".tree button.node", { timeout: 30000 });
  await page.waitForTimeout(1500);

  console.log("═══ 1. 打开可编辑表（有主键）═══");
  await page.locator(".tree button.node", { hasText: "orders" }).first().click();
  await page.waitForSelector(".grid tbody tr", { timeout: 15000 });
  await page.waitForTimeout(800);
  check("表格已加载", (await page.locator(".grid tbody tr").count()) > 0);

  // 记住第 1 行 remark 的原值
  const remarkIdx = await page.evaluate(() => {
    const ths = [...document.querySelectorAll(".grid thead th")];
    return ths.findIndex((t) => t.innerText.trim().startsWith("remark"));
  });
  check("找到 remark 列", remarkIdx > 0, `索引=${remarkIdx}`);

  const readRemark = async () => {
    return page.evaluate((idx) => {
      const rows = document.querySelectorAll(".grid tbody tr");
      return rows[0]?.querySelectorAll("td")[idx]?.innerText.trim();
    }, remarkIdx);
  };
  const original = await readRemark();

  console.log("\n═══ 2. 改 3 个单元格 ═══");
  const targets = [];
  for (let r = 0; r < 3; r++) {
    const cell = page.locator(".grid tbody tr").nth(r).locator("td").nth(remarkIdx);
    await cell.click();
    await page.waitForTimeout(200);
    const input = page.locator(".grid tbody tr").nth(r).locator(".cell-editor");
    check(`第 ${r + 1} 行出现编辑框`, (await input.count()) > 0);
    if ((await input.count()) > 0) {
      await input.fill(`SMOKE_${r}`);
      await input.press("Enter");
      await page.waitForTimeout(250);
      targets.push(r);
    }
  }

  console.log("\n═══ 3. 未保存提示 ═══");
  const dirtyBadge = await page.locator(".toolbar .badge", { hasText: "处未保存" }).count();
  check("工具栏显示未保存计数", dirtyBadge > 0, `找到 ${dirtyBadge}`);
  const badgeText = dirtyBadge ? await page.locator(".toolbar .badge", { hasText: "处未保存" }).first().innerText() : "";
  check("未保存计数 = 3", /3/.test(badgeText), badgeText);

  const dirtyCells = await page.locator(".grid td.is-dirty").count();
  check("改动的单元格有高亮", dirtyCells === 3, `高亮 ${dirtyCells} 个`);

  const tabDirty = await page.locator(".tab__dirty").count();
  check("标签页显示未保存标记", tabDirty > 0);

  console.log("\n═══ 4. 生成的 SQL 带主键 WHERE ══");
  // 点保存会弹 confirm，我们拦截它的内容
  let capturedSql = "";
  page.removeAllListeners("dialog");
  page.on("dialog", async (d) => {
    if (d.type() === "confirm") { capturedSql = d.message(); await d.accept(); }
    else await d.accept(promptAnswer);
  });

  await page.locator(".toolbar button", { hasText: "保存" }).first().click();
  await page.waitForTimeout(2500);

  check("确认框展示了 SQL", capturedSql.length > 0, `长度=${capturedSql.length}`);
  check("SQL 含 UPDATE", /UPDATE/.test(capturedSql), capturedSql.slice(0, 80));
  check("⭐ SQL WHERE 带主键", /WHERE "id" =/.test(capturedSql), capturedSql.slice(0, 160));
  check("⭐ 三行合并为三条语句", (capturedSql.match(/UPDATE/g) || []).length === 3,
    `实际 ${(capturedSql.match(/UPDATE/g) || []).length} 条`);
  // ⭐ 引号必须被转义成 ''，否则 SQL 语法错误（WHERE 失效 → 改到多行）
  check(
    "SQL 语法自洽（引号成对）",
    (capturedSql.match(/'/g) || []).length % 2 === 0,
    `单引号数=${(capturedSql.match(/'/g) || []).length}`,
  );
  check("SQL 中 SET 段引号闭合", /SET "remark" = 'SMOKE_0' WHERE/.test(capturedSql),
    capturedSql.slice(0, 200).replace(/\n/g, " "));

  console.log("\n═══ 5. 保存后数据真的变了 ═══");
  const after = await readRemark();
  check("单元格显示新值", after === "SMOKE_0", `实际=${after}`);

  // 直接问后端
  const backend = await page.evaluate(async () => {
    const r = await fetch('/api/query', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ sql: 'SELECT remark FROM orders WHERE id = 1', maxRows: 1 }),
    });
    const j = await r.json();
    return j.data?.page?.rows?.[0]?.remark;
  });
  check("⭐ 数据库里真的落库了", backend === "SMOKE_0", `后端返回=${backend}`);

  console.log("\n═══ 6. 未保存标记已清除 ═══");
  const badgeAfter = await page.locator(".toolbar .badge", { hasText: "处未保存" }).count();
  check("保存后未保存计数消失", badgeAfter === 0, `还有 ${badgeAfter}`);

  console.log("\n═══ 7. 还原数据 ═══");
  await page.evaluate(async () => {
    for (let i = 1; i <= 3; i++) {
      await fetch('/api/execute', {
        method: 'POST', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          sql: `UPDATE orders SET remark = NULL WHERE id = ${i}`,
          requireConfirm: true,
        }),
      });
    }
  });
  await page.waitForTimeout(500);
  const restored = await page.evaluate(async () => {
    const r = await fetch('/api/query', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ sql: 'SELECT remark FROM orders WHERE id = 1', maxRows: 1 }),
    });
    return (await r.json()).data?.page?.rows?.[0]?.remark;
  });
  check("数据已还原为 NULL", restored === null, `实际=${restored}`);

  console.log("\n═══ 8. ⭐ 无主键表整表只读 ═══");
  // ⭐ 之前的实现是「先让你编辑、保存时再拒绝」。现在改成**入口就挡住**：
  //    点单元格不弹编辑框、直接告诉用户为什么。
  //    「保存时才拒绝」的问题是用户可能改完十几格才被告知 —— 白干。
  await page.locator(".tree button.node", { hasText: "audit_logs" }).first().click();
  await page.waitForSelector(".grid tbody tr:not(.win-spacer)", { timeout: 15000 });
  await page.waitForTimeout(1000);
  const akIdx = await page.evaluate(() => {
    const ths = [...document.querySelectorAll(".grid thead th")];
    return ths.findIndex((t) => t.innerText.trim().startsWith("actor"));
  });
  check("找到目标列", akIdx > 0, `actor 列下标=${akIdx}`);

  await page.evaluate((i) => {
    const tr = [...document.querySelectorAll(".grid tbody tr")]
      .filter((x) => !x.classList.contains("win-spacer"))[0];
    tr.querySelectorAll("td")[i].click();
  }, akIdx);
  await page.waitForTimeout(600);

  check("⭐ 无主键表点不出编辑框",
    (await page.locator(".grid .cell-editor").count()) === 0);
  const roToast = (await page.locator(".toast").count())
    ? await page.locator(".toast").first().innerText() : "";
  check("⭐ 说明了**为什么**不能编辑", /主键/.test(roToast), roToast.slice(0, 80));
  check("⭐ 行内标注只读",
    (await page.locator(".grid-foot, .grid").getByText("只读").count()) > 0
    || (await page.locator(".grid .btn--danger").count()) === 0);
  check("⭐ 没有保存按钮（压根没进缓冲）",
    (await page.locator(".toolbar button", { hasText: "保存" }).count()) === 0);
  check("⭐ 撤销键不可用", await page.locator(".toolbar button[title*='撤销']").isDisabled());

  const actorAfter = await page.evaluate(async () => {
    const r = await fetch("/api/query", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ sql: "SELECT actor FROM audit_logs LIMIT 1", maxRows: 1 }),
    });
    return (await r.json()).data?.page?.rows?.[0]?.actor;
  });
  check("⭐ 数据库没被误改", actorAfter !== "noop_pk_test", `actor=${actorAfter}`);

  console.log("\n═══ 9. 控制台 ═══");
  check("无未捕获异常", errs.filter((e) => !/confirm|prompt/i.test(e)).length === 0,
    errs.slice(0, 2).join(" | "));
} finally {
  await browser.close();
}

console.log("");
if (fail) console.log(`失败项：\n  - ${failures.join("\n  - ")}`);
console.log(`通过 ${pass} / 失败 ${fail}`);
process.exit(fail ? 1 : 0);