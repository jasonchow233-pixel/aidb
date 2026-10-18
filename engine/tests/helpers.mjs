/**
 * ⭐ 表格测试的公共工具。
 *
 * ⭐ 为什么要这一层：窗口化之后「DOM 里有多少行」不再等于「这一页有多少行」。
 * 直接 `count('.grid tbody tr')` 拿到的是当前渲染了几十行，
 * 拿它断言「筛选后 0 行」「本页 100 行」全会假失败 ——
 * 而且报错信息（"期望 0，实际 43"）会把人引向完全错误的方向。
 *
 * 所以：
 *   · 行数一律从**底栏文案**读，那是产品自己告诉用户的信息
 *   · 要遍历数据行时必须排除 `.win-spacer` 占位行
 */

/** 读底栏里的「本页 N 行」；读不到返回 -1 */
export async function trueRowCount(page) {
  const t = await page.locator(".grid-foot").innerText();
  return Number(t.match(/本页\s+([\d,]+)\s*行/)?.[1].replace(/,/g, "") ?? -1);
}

/** 读底栏里的「共 N 行」；拿不到就返回 null（产品本来就没显示） */
export async function totalRows(page) {
  const t = await page.locator(".grid-foot").innerText();
  const m = t.match(/共\s+([\d,]+)\s*行/);
  return m ? Number(m[1].replace(/,/g, "")) : null;
}

/** ⭐ 当前真实数据行的「单元格文本矩阵」，排除窗口化 spacer。
 *
 *  ⚠ 必须排掉 spacer：它只有 1 个 td，取 td[5] 得到 undefined，
 *    undefined 混进 Set 会让「唯一值 === 1」误报成「筛选没生效」。
 */
export function dataCells(page) {
  return page.evaluate(() =>
    [...document.querySelectorAll(".grid tbody tr")]
      .filter((r) => !r.classList.contains("win-spacer"))
      .map((r) => [...r.querySelectorAll("td")].map((c) => c.innerText.trim())));
}

/** 当前渲染的 DOM 行数（窗口化后只含可视区） */
export async function domRowCount(page) {
  return page.locator(".grid tbody tr:not(.win-spacer)").count();
}

/** 打开一张表并等它加载完 */
export async function openTable(page, name, timeout = 20000) {
  await page.locator(".tree button.node", { hasText: name }).first().click();
  await page.waitForSelector(".grid tbody tr:not(.win-spacer)", { timeout });
  await page.waitForTimeout(900);
}

/**
 * ⭐ 把表格容器滚到指定位置（像素）。
 * 直接设 scrollTop 不会等一帧，返回前要自己等一下。
 */
export async function scrollGrid(page, top) {
  await page.evaluate((t) => { document.querySelector(".grid-wrap").scrollTop = t; }, top);
  await page.waitForTimeout(450);
}

/** ⭐ 表头文本 → 列下标。用它而不是硬编码 5、7，列序一改断言就假失败。 */
export function colIdxOf(page, name) {
  return page.evaluate((n) =>
    [...document.querySelectorAll(".grid thead th")].findIndex((t) =>
      t.innerText.trim().startsWith(n)), name);
}