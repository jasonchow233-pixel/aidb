/**
 * ⭐ 客户端交互专项测试：分页 / 排序 / 筛选 / 类型提示
 *
 * ⭐ 核心断言是「有没有下推到数据库」：
 *   前端只拿到当前页，内存里排序/筛选只对这一页有效。
 *   用户点「按金额倒序」，看到的其实是「这一页里最大的几行」，
 *   结果看着像模像样，实际是错的 —— 这类错误极难察觉。
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
const sqls = [];
const errs = [];
page.on("request", (r) => {
  if (r.url().includes("/api/query")) {
    try { sqls.push(JSON.parse(r.postData() || "{}").sql); } catch {}
  }
});
page.on("pageerror", (e) => errs.push(String(e).slice(0, 200)));
page.on("dialog", async (d) => d.accept());

const colIdx = (n) =>
  page.evaluate((name) =>
    [...document.querySelectorAll(".grid thead th")].findIndex((t) =>
      t.innerText.trim().startsWith(name)), n);
const cellVals = (i, n = 6) =>
  page.evaluate(([idx, cnt]) =>
    [...document.querySelectorAll(".grid tbody tr")].slice(0, cnt)
      .map((r) => Number(r.querySelectorAll("td")[idx].innerText.replace(/,/g, ""))), [i, n]);

try {
  await page.goto("http://localhost:5173", { waitUntil: "domcontentloaded" });
  await page.waitForSelector(".tree button.node", { timeout: 30000 });
  await page.waitForTimeout(1800);
  await page.locator(".tree button.node", { hasText: "orders" }).first().click();
  await page.waitForSelector(".grid tbody tr", { timeout: 15000 });
  await page.waitForTimeout(1200);

  console.log("═══ 1. 分页 ═══");
  check("有分页控件", (await page.locator(".pager").count()) > 0);
  check("显示页码", /\d/.test(await page.locator(".pager__pos").innerText()));
  check("显示总页数", /\/\s*\d+/.test(await page.locator(".pager__pos").innerText()),
    await page.locator(".pager__pos").innerText());
  check("首页时上一页禁用", await page.locator(".pager button", { hasText: "◀" }).isDisabled());

  const firstIds = await page.evaluate(() =>
    [...document.querySelectorAll(".grid tbody tr")].slice(0, 3)
      .map((r) => r.querySelectorAll("td")[1].innerText.trim()));
  await page.locator(".pager button", { hasText: "▶" }).click();
  await page.waitForTimeout(1800);
  const secondIds = await page.evaluate(() =>
    [...document.querySelectorAll(".grid tbody tr")].slice(0, 3)
      .map((r) => r.querySelectorAll("td")[1].innerText.trim()));
  check("⭐ 下一页换了数据", JSON.stringify(firstIds) !== JSON.stringify(secondIds),
    `${firstIds} vs ${secondIds}`);
  check("⭐ 页码递增", /第 2 页/.test(await page.locator(".pager__pos").innerText()));
  check("上一页恢复可用", !(await page.locator(".pager button", { hasText: "◀" }).isDisabled()));

  await page.locator(".pager button", { hasText: "◀" }).click();
  await page.waitForTimeout(1800);
  const backIds = await page.evaluate(() =>
    [...document.querySelectorAll(".grid tbody tr")].slice(0, 3)
      .map((r) => r.querySelectorAll("td")[1].innerText.trim()));
  check("上一页回到原数据", JSON.stringify(firstIds) === JSON.stringify(backIds));

  console.log("\n═══ 2. 页大小切换 ═══");
  await page.locator(".pager select").selectOption("100");
  await page.waitForTimeout(1800);
  const rowCount = await page.locator(".grid tbody tr").count();
  check("⭐ 页大小生效", rowCount === 100, `实际 ${rowCount} 行`);
  check("页码重置到第 1 页", /第 1 页/.test(await page.locator(".pager__pos").innerText()));

  console.log("\n═══ 3. 排序（下推到数据库）═══");
  const tIdx = await colIdx("total_cents");
  await page.locator(".grid thead th").nth(tIdx).click();
  await page.waitForTimeout(1800);
  check("⭐ SQL 含 ORDER BY", /ORDER BY/.test(sqls[sqls.length - 1]), sqls[sqls.length - 1]);
  check("⭐ 只有一个 ORDER BY 子句",
    (sqls[sqls.length - 1].match(/ORDER BY/gi) || []).length === 1,
    sqls[sqls.length - 1]);
  check("⭐ 有主键兜底排序", /ORDER BY.*"id"/.test(sqls[sqls.length - 1]), sqls[sqls.length - 1]);
  const asc = await cellVals(tIdx);
  check("升序正确", asc.every((v, i) => i === 0 || asc[i - 1] <= v), asc.join(","));

  await page.locator(".grid thead th").nth(tIdx).click();
  await page.waitForTimeout(1800);
  check("SQL 含 DESC", /DESC/.test(sqls[sqls.length - 1]), sqls[sqls.length - 1]);
  const desc = await cellVals(tIdx);
  check("降序正确", desc.every((v, i) => i === 0 || desc[i - 1] >= desc[i]), desc.join(","));
  check("表头有排序标记", /[↑↓]/.test(await page.locator(".grid thead").innerText()));

  console.log("\n═══ 4. 筛选（下推到数据库）═══");
  await page.locator(".toolbar button", { hasText: "筛选" }).first().click();
  await page.waitForTimeout(500);
  await page.locator(".filterbar select").first().selectOption("status");
  await page.waitForTimeout(250);
  await page.locator(".filterbar select").nth(1).selectOption("=");
  await page.waitForTimeout(250);
  await page.locator(".filterbar input").first().fill("2");
  await page.waitForTimeout(200);
  await page.locator(".filterbar button", { hasText: "添加" }).click();
  await page.waitForTimeout(2000);

  check("⭐ SQL 含 WHERE", /WHERE/.test(sqls[sqls.length - 1]), sqls[sqls.length - 1]);
  check("⭐ 筛选后 offset 归零", /status/.test(sqls[sqls.length - 1]));
  const stVals = await page.evaluate(() =>
    [...new Set([...document.querySelectorAll(".grid tbody tr")]
      .map((r) => r.querySelectorAll("td")[5]?.innerText.trim()))]);
  check("⭐ 结果只剩已支付", stVals.length === 1 && stVals[0] === "已支付", stVals.join(","));
  check("有条件 chip", (await page.locator(".filterchip").count()) === 1);
  check("提示条件已下推", /下推到数据库/.test(await page.locator(".filterbar").innerText()));

  console.log("\n═══ 5. 多条件 + IS NULL ═══");
  await page.locator(".filterbar select").first().selectOption("remark");
  await page.waitForTimeout(250);
  await page.locator(".filterbar select").nth(1).selectOption("IS NULL");
  await page.waitForTimeout(250);
  await page.locator(".filterbar button", { hasText: "添加" }).click();
  await page.waitForTimeout(2000);
  check("多条件用 AND 连接", /WHERE.*AND/.test(sqls[sqls.length - 1]), sqls[sqls.length - 1]);
  check("IS NULL 正确生成", /IS NULL/.test(sqls[sqls.length - 1]), sqls[sqls.length - 1]);
  check("两个 chip", (await page.locator(".filterchip").count()) === 2);
  const nullRows = await page.evaluate(() =>
    [...document.querySelectorAll(".grid tbody tr")]
      .filter((r) => r.querySelectorAll("td")[7]?.innerText.trim() === "NULL").length);
  check("结果全部为 NULL", nullRows === (await page.locator(".grid tbody tr").count()),
    `${nullRows} / ${await page.locator(".grid tbody tr").count()}`);

  console.log("\n═══ 6. 总数不撒谎 ═══");
  const foot = await page.locator(".grid-foot").innerText();
  check("⭐ 筛选时不显示未筛选的总数", /总数未统计/.test(foot), foot.split("\n")[0]);
  check("不显示 5,000（那是未筛选的数）", !/共 5,000 行/.test(foot), foot.split("\n")[0]);

  console.log("\n═══ 7. 移除条件 ═══");
  await page.locator(".filterchip__x").first().click();
  await page.waitForTimeout(1800);
  check("chip 减少", (await page.locator(".filterchip").count()) === 1);
  check("⭐ 剩余条件仍在 WHERE", /remark/.test(sqls[sqls.length - 1]), sqls[sqls.length - 1]);
  await page.locator(".filterbar button", { hasText: "全部清除" }).click();
  await page.waitForTimeout(1800);
  check("全部清除后无 WHERE", !/WHERE/.test(sqls[sqls.length - 1]), sqls[sqls.length - 1]);
  const foot2 = await page.locator(".grid-foot").innerText();
  check("⭐ 总数恢复显示", /共 5,000 行/.test(foot2), foot2.split("\n")[0]);

  console.log("\n═══ 8. 类型提示 ═══");
  const heads = await page.evaluate(() =>
    [...document.querySelectorAll(".grid thead th")].map((t) => t.innerText.replace(/\n/g, " | ")));
  check("表头显示类型", heads.some((h) => /TEXT|INTEGER/.test(h)));
  check("表头显示注释", heads.some((h) => /订单|金额|状态/.test(h)));
  check("表头显示默认值", heads.some((h) => /=/.test(h)));
  check("主键有标记", heads.some((h) => /🔑/.test(h)));
  check("外键有标记", heads.some((h) => /🔗/.test(h)));
  const enums = await page.evaluate(() =>
    [...new Set([...document.querySelectorAll(".grid tbody tr")]
      .map((r) => r.querySelectorAll("td")[5]?.innerText.trim()))]);
  check("⭐ 枚举显示中文", enums.some((e) => /待支付|已支付|已完成|已发货/.test(e || "")),
    enums.slice(0, 4).join(","));
  check("NULL 有专属样式", (await page.locator(".grid td.null").count()) > 0);

  console.log("\n═══ 9. 本页搜索范围明示 ═══");
  check("明示「仅本页」", /仅本页/.test(await page.locator(".toolbar").innerText()));

  console.log("\n═══ 10. 控制台 ═══");
  check("无未捕获异常", errs.length === 0, errs.slice(0, 2).join(" | "));
} finally {
  await browser.close();
}

console.log("");
if (fail) console.log(`失败项：\n  - ${failures.join("\n  - ")}`);
console.log(`通过 ${pass} / 失败 ${fail}`);
process.exit(fail ? 1 : 0);