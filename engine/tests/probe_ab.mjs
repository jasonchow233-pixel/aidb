/**
 * ⭐ 窗口化 A/B 基线对比。
 *
 * 手法：把浏览器视口调到足够高，`viewportH` 变大 → `win` 覆盖全部行，
 * 等价于「不做窗口化」。于是同一份数据能同时量出两条曲线。
 */
import { chromium } from "playwright";

async function run(height, label) {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1600, height } });
  await p.goto("http://localhost:5173", { waitUntil: "domcontentloaded" });
  await p.waitForSelector(".tree button.node", { timeout: 30000 });
  await p.waitForTimeout(1800);
  await p.locator(".tree button.node", { hasText: "orders" }).first().click();
  await p.waitForSelector(".grid tbody tr", { timeout: 15000 });
  await p.waitForTimeout(1000);
  await p.locator(".pager select").selectOption(PAGE);
  await p.waitForTimeout(3000);

  const dom = await p.evaluate(() => ({
    totalNodes: document.querySelectorAll("*").length,
    gridNodes: document.querySelectorAll(".grid *").length,
    domRows: [...document.querySelectorAll(".grid tbody tr")]
      .filter((r) => !r.classList.contains("win-spacer")).length,
  }));
  const frames = await p.evaluate(async () => {
    const el = document.querySelector(".grid-wrap");
    const times = [];
    let last = performance.now();
    let raf;
    const tick = () => { const n = performance.now(); times.push(n - last); last = n; raf = requestAnimationFrame(tick); };
    raf = requestAnimationFrame(tick);
    for (let i = 0; i < 50; i++) { el.scrollTop += 30; await new Promise((r) => setTimeout(r, 16)); }
    cancelAnimationFrame(raf);
    times.sort((a, b) => a - b);
    return {
      p50: +times[Math.floor(times.length * 0.5)].toFixed(1),
      p95: +times[Math.floor(times.length * 0.95)].toFixed(1),
      max: +times[times.length - 1].toFixed(1),
    };
  });
  console.log(`${label.padEnd(22)} DOM ${String(dom.totalNodes).padStart(6)} 个 · 表格节点 ${String(dom.gridNodes).padStart(6)} · 渲染 ${String(dom.domRows).padStart(3)} 行 · 帧 p50/p95/max ${frames.p50}/${frames.p95}/${frames.max} ms`);
  await b.close();
}

const PAGE = process.env.PAGE ?? "5000";

console.log(`同一份数据（orders，本页 ${PAGE} 行）两种渲染策略：\n`);
await run(1000, "窗口化（1600×1000）");
await run(170000, "全量渲染（视口拉满）");