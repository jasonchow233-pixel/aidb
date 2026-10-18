#!/usr/bin/env node
/**
 * ⭐⭐ 查询编辑器专项：B1-1~B1-9
 *
 * ⭐ 为什么单开一层：查询页是盲测 T3（三表 JOIN + Ctrl+Click 跳转）唯一卡住的地方，
 *   也是本项目**唯一一个从「纯 textarea」变成「有内核的编辑器」**的页面。
 *   之前它是「能跑但没有编辑器」的状态：能执行、能报错，
 *   但没有高亮、没有跳转、没有多语句 —— 而这些都不影响「页面不报错」，
 *   所以前面九层门禁全都发现不了它缺功能。
 *
 * ⭐ 这里验的是四类「看着正常、实际已经错了」的事：
 *
 *   1. **高亮层与 textarea 必须逐字符对齐**
 *      字体度量差 1px，光标就整体偏移 —— 表现为「选中高亮的是 A，输入落在 B」。
 *      只看「有颜色了」发现不了，必须量两层的 computed style。
 *
 *   2. **高亮文本必须等于用户写的原文**
 *      高亮层是重新渲染的，漏一个字符用户看到的 SQL 就和实际执行的不一样。
 *      而用户是照着屏幕上的 SQL 思考的 —— 这比高亮不漂亮严重得多。
 *
 *   3. **Ctrl+Click 必须跳到用户点的那个符号**
 *      偏一个字符就跳错表；别名不解析就跳到一张叫 `o` 的表（不存在）。
 *
 *   4. **执行范围不能骗人**
 *      「执行选中」不能偷偷跑全部；多语句不能只显示一个结果。
 *
 * 用法：node engine/tests/smoke_query.mjs
 * 前置：后端 8787 + 前端 5173 在跑。
 */
import { chromium } from "playwright";

let pass = 0, fail = 0;
const failures = [];
const check = (l, ok, x = "") => {
  if (ok) { pass++; console.log(`  ok    ${l}`); }
  else { fail++; failures.push(l); console.log(`  FAIL  ${l}  ${x}`); }
};

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
const page = await ctx.newPage();
const errs = [];
page.on("pageerror", (e) => errs.push(String(e).slice(0, 200)));

/** ⭐ 把编辑器内容换成指定 SQL（⌘A 全选后输入，等高亮跟上） */
async function setSQL(text) {
  await page.locator(".sqled__ta").click();
  await page.keyboard.press("Meta+a");
  await page.keyboard.type(text);
  await page.waitForTimeout(700);
}

/** ⭐ 换一条查询页 tab（每个 tab 各自持有 SQL） */
async function newQueryTab() {
  await page.keyboard.press("Meta+t");
  await page.waitForTimeout(1000);
}

/**
 * ⭐ 在高亮层里找到某个词的像素位置并 ⌘Click 它。
 *
 * 用高亮层的 span 而不是自己算字符宽度 —— 那是本产品自己的排版结果，
 * 拿来当测试基准最不容易与实现漂移。
 */
async function cmdClickToken(word, occurrence = 0) {
  const box = await page.evaluate(([w, occ]) => {
    const spans = [...document.querySelectorAll(".sqled__hl .tk")].filter((e) => e.textContent === w);
    const s = spans[occ];
    if (!s) return null;
    const r = s.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
  }, [word, occurrence]);
  if (!box) return false;
  await page.keyboard.down("Meta");
  await page.mouse.click(box.x, box.y);
  await page.keyboard.up("Meta");
  await page.waitForTimeout(1400);
  return true;
}

/** 当前激活 tab 的标题（去掉 ✕） */
const activeTab = () =>
  page.locator(".tab--active").innerText().then((t) => t.replace("✕", "").trim()).catch(() => "");

try {
  await page.goto("http://localhost:5173", { waitUntil: "domcontentloaded" });
  await page.waitForSelector(".tree button.node", { timeout: 30000 });
  await page.waitForTimeout(1800);
  await newQueryTab();

  // ── 1. 编辑器结构与高亮 ───────────────────────────────────
  console.log("═══ 1. B1-1 语法高亮 ═══");
  check("编辑器渲染出来了", (await page.locator(".sqled").count()) === 1);
  check("有行号条", (await page.locator(".sqled__ln").count()) > 1,
    `${await page.locator(".sqled__ln").count()} 行`);

  await setSQL("SELECT id, 'x' FROM orders WHERE n = 42 -- 尾注");
  const kinds = await page.evaluate(() =>
    [...new Set([...document.querySelectorAll(".sqled__hl .tk")]
      .map((e) => e.className.replace("tk tk--", "")))]);
  for (const k of ["keyword", "string", "number", "comment", "ident", "operator", "punct"]) {
    check(`⭐ 高亮覆盖 ${k}`, kinds.includes(k), kinds.join(","));
  }

  const colors = await page.evaluate(() => {
    const g = (sel) => {
      const e = document.querySelector(sel);
      return e ? getComputedStyle(e).color : "NONE";
    };
    return {
      keyword: g(".sqled__hl .tk--keyword"),
      string: g(".sqled__hl .tk--string"),
      number: g(".sqled__hl .tk--number"),
      base: g(".sqled__hl"),
    };
  });
  check("⭐ 关键字真的上了颜色（不是默认色）", colors.keyword !== colors.base, JSON.stringify(colors));
  check("⭐ 字符串用了与关键字不同的颜色", colors.string !== colors.keyword && colors.string !== "NONE",
    JSON.stringify(colors));

  // ── 2. ⭐ 高亮文本 === 用户写的原文 ─────────────────────────
  console.log("\n═══ 2. ⭐ 高亮层与原文一致 ═══");
  const fidelity = await page.evaluate(() => {
    const ta = document.querySelector(".sqled__ta");
    const pre = document.querySelector(".sqled__hl");
    return { value: ta.value, rendered: pre.innerText };
  });
  // highlightHtml 故意在末尾补一个换行（对齐 textarea 的空行）
  check("⭐ 高亮层渲染的文本 === 用户写的 SQL",
    fidelity.rendered.trimEnd() === fidelity.value,
    `\n原文: ${JSON.stringify(fidelity.value)}\n渲染: ${JSON.stringify(fidelity.rendered)}`);

  const special = "SELECT 'a;b', \"q u o\", 1 -- 含 ; 和 ' 的注释\nFROM t";
  await setSQL(special);
  const fidelity2 = await page.evaluate(() => ({
    value: document.querySelector(".sqled__ta").value,
    rendered: document.querySelector(".sqled__hl").innerText,
  }));
  check("⭐ 含引号/分号的 SQL 也不丢字符",
    fidelity2.rendered.trimEnd() === fidelity2.value,
    `\n原文: ${JSON.stringify(fidelity2.value)}\n渲染: ${JSON.stringify(fidelity2.rendered)}`);

  // ── 3. ⭐ 高亮层与 textarea 逐像素对齐 ─────────────────────
  console.log("\n═══ 3. ⭐ 高亮层与光标对齐 ═══");
  await setSQL("SELECT o.id, o.status\nFROM orders o\nJOIN users u ON u.id = o.user_id\nWHERE o.status = 2");
  const align = await page.evaluate(() => {
    const ta = document.querySelector(".sqled__ta");
    const pre = document.querySelector(".sqled__hl");
    const cs1 = getComputedStyle(ta), cs2 = getComputedStyle(pre);
    const keys = ["fontFamily", "fontSize", "lineHeight", "letterSpacing",
      "paddingTop", "paddingLeft", "tabSize", "whiteSpace", "wordBreak"];
    const tr = ta.getBoundingClientRect(), pr = pre.getBoundingClientRect();
    return {
      diff: keys.filter((k) => cs1[k] !== cs2[k]).map((k) => `${k}: ta=${cs1[k]} pre=${cs2[k]}`),
      dx: Math.abs(tr.left - pr.left), dy: Math.abs(tr.top - pr.top),
    };
  });
  check("⭐ 字体度量与换行策略完全一致（否则光标整体偏移）",
    align.diff.length === 0, align.diff.join(" | "));
  check("⭐ 两层左上角重合", align.dx < 1 && align.dy < 1, `dx=${align.dx} dy=${align.dy}`);

  // ── 4. B1-2 自动配对 ─────────────────────────────────────
  console.log("\n═══ 4. B1-2 自动配对 ═══");
  await setSQL("count");
  await page.keyboard.type("(");
  await page.waitForTimeout(250);
  check("打左括号自动补右括号", (await page.locator(".sqled__ta").inputValue()) === "count()",
    JSON.stringify(await page.locator(".sqled__ta").inputValue()));

  await page.keyboard.type(")");
  await page.waitForTimeout(250);
  check("⭐ 光标已在右括号上时跳过（不能变成 count())）",
    (await page.locator(".sqled__ta").inputValue()) === "count()",
    JSON.stringify(await page.locator(".sqled__ta").inputValue()));
  // ⭐ 跳过之后光标在 `)` **之后**（=7），这时再打 x 得到的是 `count()x`，
  //   这是正确的 —— 不是「打不进括号里」。
  //   验证「括号里能输入」要在**跳过之前**做：先 `(` 配对，此时光标在中间（=6）。
  await page.locator(".sqled__ta").click();
  await page.keyboard.press("Meta+a");
  await page.keyboard.type("count(");
  await page.waitForTimeout(300);
  await page.keyboard.type("x");
  await page.waitForTimeout(250);
  check("⭐ 配对后光标在括号中间（x 应落在括号里）",
    (await page.locator(".sqled__ta").inputValue()) === "count(x)",
    JSON.stringify(await page.locator(".sqled__ta").inputValue()));

  // ── 5. ⭐ Ctrl+Click 跳转（盲测 T3 的核心）─────────────────
  console.log("\n═══ 5. ⭐ B1-3 Ctrl+Click 符号跳转 ═══");
  await newQueryTab();
  await setSQL("SELECT id FROM orders LIMIT 1");
  check("能定位到表名 token", await cmdClickToken("orders"));
  check("⭐ ⌘Click 表名 → 打开该表的数据页", (await activeTab()) === "orders",
    `激活 tab=${await activeTab()}`);
  check("确实渲染出了数据网格", (await page.locator(".grid tbody tr").count()) > 0);

  // ⭐ 跳到**还没打开过**的表：之前 openStructure/openTable 依赖 state.tableDef，
  //   没打开过的表会静默失败
  await newQueryTab();
  await setSQL("SELECT id FROM products LIMIT 1");
  await cmdClickToken("products");
  check("⭐ 能跳到尚未打开过的表", (await activeTab()) === "products",
    `激活 tab=${await activeTab()}`);
  check("那张表的数据确实加载了", (await page.locator(".grid tbody tr").count()) > 0);

  // ⭐⭐ 别名必须解析成真实表名 —— 否则打开一张叫 `o` 的表（不存在）
  await newQueryTab();
  await setSQL("SELECT o.status, u.email\nFROM orders o\nJOIN users u ON u.id = o.user_id");
  check("能定位到列名 token", await cmdClickToken("status"));
  const colTab = await activeTab();
  check("⭐ ⌘Click 列名 → 打开**所属表**的结构页", colTab === "orders 结构",
    `实际=${colTab}（若显示「o 结构」说明别名没解析）`);
  check("结构页渲染出列清单（D1 升级为 6 tab）", (await page.locator(".tabs__item").count()) === 6,
    `${await page.locator(".tabs__item").count()} 个 tab`);

  // ⭐ 不可跳转的位置不该有反应（弹窗 = 噪音）
  await newQueryTab();
  await setSQL("SELECT 1");
  const before = await page.locator(".tab").count();
  const kwBox = await page.evaluate(() => {
    const s = document.querySelector(".sqled__hl .tk--keyword");
    const r = s.getBoundingClientRect();
    return { x: r.left + 2, y: r.top + r.height / 2 };
  });
  await page.keyboard.down("Meta");
  await page.mouse.click(kwBox.x, kwBox.y);
  await page.keyboard.up("Meta");
  await page.waitForTimeout(900);
  check("⭐ 关键字上 ⌘Click 不开新页（不制造噪音）",
    (await page.locator(".tab").count()) === before,
    `${before} → ${await page.locator(".tab").count()}`);

  // ── 6. B1-4 hover 提示 ───────────────────────────────────
  console.log("\n═══ 6. B1-4 hover 注释与枚举 ═══");
  await newQueryTab();
  await setSQL("SELECT status FROM orders");
  const hover = async (word) => {
    const box = await page.evaluate((w) => {
      const s = [...document.querySelectorAll(".sqled__hl .tk")].find((e) => e.textContent === w);
      if (!s) return null;
      const r = s.getBoundingClientRect();
      return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
    }, word);
    if (!box) return null;
    await page.mouse.move(box.x, box.y);
    await page.waitForTimeout(800);
    return page.locator(".sqled__tip").innerText().catch(() => null);
  };

  const tipTable = await hover("orders");
  check("⭐ hover 表名出提示", !!tipTable, String(tipTable));
  check("⭐ 表提示含注释", !!tipTable && /订单/.test(tipTable), String(tipTable));
  check("⭐ 表提示含行数", !!tipTable && /行/.test(tipTable), String(tipTable));
  check("⭐ hover 的 token 有下划线 affordance",
    (await page.locator(".sqled__hl .tk--mark").count()) > 0);

  const tipCol = await hover("status");
  check("⭐ hover 列名出提示", !!tipCol, String(tipCol));
  check("⭐ 列提示含枚举取值含义", !!tipCol && /待支付|已支付/.test(tipCol), String(tipCol));

  // ⭐ hover 关键字不该弹（那是噪音）
  const kwBox2 = await page.evaluate(() => {
    const s = document.querySelector(".sqled__hl .tk--keyword");
    const r = s.getBoundingClientRect();
    return { x: r.left + 2, y: r.top + r.height / 2 };
  });
  await page.mouse.move(kwBox2.x, kwBox2.y);
  await page.waitForTimeout(600);
  check("⭐ hover 关键字不弹提示", (await page.locator(".sqled__tip").count()) === 0,
    (await page.locator(".sqled__tip").innerText().catch(() => "")) || "");

  // ── 7. B1-6 多语句 + 选中执行 ────────────────────────────
  console.log("\n═══ 7. ⭐ B1-6 多语句执行 ═══");
  await setSQL("SELECT order_no FROM orders LIMIT 2; SELECT user_id FROM orders LIMIT 3;");
  check("识别出多条语句", (await page.locator("[data-test=stmt-count]").innerText()).includes("2"),
    await page.locator("[data-test=stmt-count]").innerText().catch(() => "NONE"));

  await page.locator("[data-test=run]").click();
  await page.waitForTimeout(3500);
  const tabs = await page.locator("[data-test=result-tab]").allInnerTexts();
  check("⭐ 每条语句一个结果页签", tabs.length === 2, JSON.stringify(tabs));
  check("⭐ 两个页签的行数各自独立（2 行 / 3 行）",
    tabs[0]?.includes("(2)") && tabs[1]?.includes("(3)"), JSON.stringify(tabs));

  await page.locator("[data-test=result-tab]").nth(1).click();
  await page.waitForTimeout(600);
  const rowsInTab2 = await page.locator("[data-test=result-grid] tbody tr").count();
  check("⭐ 切到第 2 个页签显示的是第 2 条语句的结果", rowsInTab2 === 3, `${rowsInTab2} 行`);

  // ⭐⭐ 执行选中：不能偷偷跑全部
  await setSQL("SELECT order_no FROM orders LIMIT 2;\nSELECT user_id FROM orders LIMIT 3;");
  // 选中第一条（光标停在第 1 行内即代表「光标所在那条」）
  await page.evaluate(() => {
    const ta = document.querySelector(".sqled__ta");
    ta.focus();
    ta.setSelectionRange(5, 5); // 第一条语句内
    ta.dispatchEvent(new Event("select", { bubbles: true }));
  });
  await page.waitForTimeout(300);
  await page.locator("[data-test=run-selection]").click();
  await page.waitForTimeout(3000);
  const selTabs = await page.locator("[data-test=result-tab]").allInnerTexts();
  check("⭐ 执行选中只跑了一条（不是全部）", selTabs.length === 1, JSON.stringify(selTabs));
  check("⭐ 跑的是光标所在那条（2 行）", selTabs[0]?.includes("(2)"), JSON.stringify(selTabs));

  // ── 8. B1-5 内联结果 + 布局切换 ──────────────────────────
  console.log("\n═══ 8. B1-5 结果内联与布局 ═══");
  const stackLayout = await page.evaluate(() =>
    document.querySelector(".query-body")?.className.includes("stack"));
  check("默认上下布局（结果在编辑器下方）", stackLayout === true, String(stackLayout));

  const stacked = await page.evaluate(() => {
    const ed = document.querySelector(".query-editor").getBoundingClientRect();
    const rs = document.querySelector(".result-area").getBoundingClientRect();
    return { editorBottom: ed.bottom, resultTop: rs.top };
  });
  check("⭐ 结果区在编辑器**下方**", stacked.resultTop >= stacked.editorBottom - 2,
    `编辑器底=${stacked.editorBottom} 结果顶=${stacked.resultTop}`);

  await setSQL("SELECT id FROM orders LIMIT 1");
  const editorWidth = await page.evaluate(() =>
    document.querySelector(".sqled__ta").getBoundingClientRect().width);
  const mainWidth = await page.evaluate(() =>
    document.querySelector(".app-main").getBoundingClientRect().width);
  check("⭐ 编辑器撑满容器（曾被 flex 收缩到 199px）",
    editorWidth > mainWidth * 0.9, `${Math.round(editorWidth)} / ${Math.round(mainWidth)}`);

  // ⚠ 按钮上写的是**当前**布局（「⇅ 上下」= 现在是上下，点一下变左右），
  //   所以点之前要先读，不能拿目标态去当选择器 —— 否则永远找不到元素。
  const layoutBtn = page.locator(".editor-page__toolbar button", { hasText: "⇅" });
  check("有布局切换入口", (await layoutBtn.count()) === 1,
    JSON.stringify(await page.locator(".editor-page__toolbar button").allInnerTexts()));
  await layoutBtn.click();
  await page.waitForTimeout(600);
  const splitLayout = await page.evaluate(() => {
    const ed = document.querySelector(".query-editor").getBoundingClientRect();
    const rs = document.querySelector(".result-area").getBoundingClientRect();
    return { cls: document.querySelector(".query-body")?.className, edRight: ed.right, rsLeft: rs.left };
  });
  check("可切换为左右分栏", splitLayout.cls.includes("split"), String(splitLayout.cls));
  check("⭐ 左右模式下结果在编辑器右侧",
    splitLayout.rsLeft >= splitLayout.edRight - 2,
    `编辑器右=${splitLayout.edRight} 结果左=${splitLayout.rsLeft}`);
  await page.locator(".editor-page__toolbar button", { hasText: "⇄" }).click();
  await page.waitForTimeout(500);
  check("能切回上下布局",
    (await page.evaluate(() => document.querySelector(".query-body")?.className)).includes("stack"));

  // ── 9. B1-7 格式化 ──────────────────────────────────────
  console.log("\n═══ 9. B1-7 格式化 ═══");
  await setSQL("select id,name from orders where status=2 and total_cents>100 order by id limit 5");
  await page.locator("[data-test=format]").click();
  await page.waitForTimeout(700);
  const fmt = await page.locator(".sqled__ta").inputValue();
  check("⭐ 关键字大写", /SELECT/.test(fmt) && !/select/.test(fmt), fmt);
  check("⭐ 子句各自成行", /^SELECT/m.test(fmt) && /^FROM/m.test(fmt) && /^WHERE/m.test(fmt), fmt);
  check("⭐ 选择列表换行对齐", /^ {2}\w/m.test(fmt), fmt);
  check("⭐ 格式化后语义不变（关键字集合一致）",
    (fmt.match(/SELECT|FROM|WHERE|ORDER BY|LIMIT/gi) || []).length === 5, fmt);

  await setSQL("select 'a  b' as x -- 注释    有   空格\nfrom t");
  await page.locator("[data-test=format]").click();
  await page.waitForTimeout(700);
  const fmt2 = await page.locator(".sqled__ta").inputValue();
  check("⭐ 格式化不折行字符串内容", fmt2.includes("'a  b'"), fmt2);
  check("⭐ 格式化不折行注释内容", fmt2.includes("-- 注释    有   空格"), fmt2);
  const cmtLine = fmt2.split("\n").findIndex((l) => l.includes("--"));
  check("⭐ 行注释后必须换行（否则后面的 SQL 会被注释掉）",
    fmt2.split("\n")[cmtLine + 1]?.includes("FROM"), fmt2);

  // ── 10. 错误定位与跳转（T4 的强项，必须不能退化）───────────
  console.log("\n═══ 10. ⭐ 错误定位与跳转 ═══");
  await setSQL("SELECT nope FROM orders");
  await page.locator("[data-test=run]").click();
  await page.waitForTimeout(2500);
  const errText = await page.locator("[data-test=sql-error]").innerText().catch(() => "");
  check("显示错误框", errText.length > 0);
  check("⭐ 文案说清缺的是哪一列", /nope/.test(errText), errText.replace(/\n/g, " "));
  check("⭐ 文案不把整条 SQL 再抄一遍", !/SELECT nope FROM orders/.test(errText),
    errText.replace(/\n/g, " "));
  check("⭐ 位置不是恒定的 (1,1)（曾恒为第 1 行第 1 列）",
    !/第 1 行, 第 1 列/.test(errText), errText.replace(/\n/g, " "));

  await page.locator("[data-test=jump-error]").click();
  await page.waitForTimeout(600);
  const sel = await page.evaluate(() => {
    const ta = document.querySelector(".sqled__ta");
    return { start: ta.selectionStart, end: ta.selectionEnd, text: ta.value.slice(ta.selectionStart, ta.selectionEnd) };
  });
  check("⭐ 「跳转到错误位置」真的选中出错的词", sel.text === "nope",
    `选中 ${JSON.stringify(sel.text)} @${sel.start}`);

  // 相似名可点击替换
  await setSQL("SELECT * FROM oderz");
  await page.locator("[data-test=run]").click();
  await page.waitForTimeout(2500);
  const hint = page.locator(".sql-error__hint").first();
  check("给相似表名建议", (await hint.count()) > 0);
  if (await hint.count()) {
    await hint.click();
    await page.waitForTimeout(500);
    check("⭐ 点相似名真的替换了文本",
      (await page.locator(".sqled__ta").inputValue()).includes("orders"),
      await page.locator(".sqled__ta").inputValue());
  }

  // ── 11. B1-9 执行计划 ───────────────────────────────────
  console.log("\n═══ 11. B1-9 执行计划 + 人话解读 ═══");
  await setSQL("SELECT id FROM orders WHERE status = 2");
  await page.locator("[data-test=explain]").click();
  await page.waitForTimeout(2500);
  const exText = await page.locator("[data-test=explain-panel]").innerText().catch(() => "");
  check("执行计划面板出现", exText.length > 0);
  check("⭐ 走索引的查询被判为「代价可接受」", /代价可接受/.test(exText),
    exText.replace(/\n/g, " ").slice(0, 160));
  // ⭐ status 上是**覆盖索引**，输出是 `USING COVERING INDEX`。
  //   规则只认 `USING INDEX` 的话，最该被表扬的情况反而只显示英文原文。
  check("⭐ 覆盖索引被翻译成人话（不是只抄原文）",
    /覆盖索引/.test(exText), exText.replace(/\n/g, " ").slice(0, 200));
  check("⭐ 同时保留原始计划步骤", /SEARCH|SCAN/.test(exText),
    exText.replace(/\n/g, " ").slice(0, 160));

  // ⭐ audit_logs：20 万行、无任何索引 → 必须被点名为全表扫描
  //   （⚠ 这张表**没有 id 列**，用 id 会先报「列不存在」，
  //     测的就不是执行计划而是报错路径了 —— 曾经就踩过）
  await setSQL("SELECT actor, action FROM audit_logs WHERE created_at > '2024-01-01'");
  await page.locator("[data-test=explain]").click();
  await page.waitForTimeout(2500);
  const exText2 = await page.locator("[data-test=explain-panel]").innerText().catch(() => "");
  check("⭐ 无索引的查询被判为「代价很高」", /代价很高/.test(exText2),
    exText2.replace(/\n/g, " ").slice(0, 160));
  check("⭐ 无索引的查询被点名为「全表扫描」",
    /全表扫描/.test(exText2), exText2.replace(/\n/g, " ").slice(0, 200));

  // ── 12. B1-8 大文件降级 ──────────────────────────────────
  console.log("\n═══ 12. B1-8 大文件降级 ═══");
  await page.evaluate(() => {
    const ta = document.querySelector(".sqled__ta");
    ta.value = Array.from({ length: 5200 }, (_, i) => `-- 第 ${i} 行注释`).join("\n");
    ta.dispatchEvent(new Event("input", { bubbles: true }));
  });
  await page.waitForTimeout(900);
  const foot = await page.locator(".sqled__foot").innerText().catch(() => "");
  check("⭐ 超大文件明示已关闭高亮（不装作还在解析）",
    /关闭语法高亮/.test(foot), foot.replace(/\n/g, " "));
  const bigTokens = await page.locator(".sqled__hl .tk").count();
  check("⭐ 降级后不再逐 token 渲染（否则 5000 行会卡死）", bigTokens === 0, `${bigTokens} 个 token`);
  const bigColor = await page.evaluate(() =>
    getComputedStyle(document.querySelector(".sqled__ta")).color);
  check("⭐ 降级后文字仍是正常色（不能是透明 —— 那就是白屏）",
    bigColor !== "rgba(0, 0, 0, 0)", bigColor);

  console.log("\n═══ 13. 控制台 ═══");
  check("无未捕获异常", errs.length === 0, errs.slice(0, 3).join(" | "));
} catch (e) {
  fail++;
  failures.push("测试执行中断: " + String(e).split("\n")[0]);
  console.log("  ERROR: " + String(e).split("\n")[0]);
} finally {
  await browser.close();
}

console.log("");
if (fail) console.log(`失败项：\n  - ${failures.join("\n  - ")}`);
console.log(`通过 ${pass} / 失败 ${fail}`);
process.exit(fail ? 1 : 0);
