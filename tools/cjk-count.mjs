// 棘轮计数：复现 I18nAuditTest 里两条「只许降不许升」的尺子，一个数都不差。
//
// 为什么需要这个脚本（而不是直接 grep）：
//   `grep -hoP '"[^"]*[\x{4e00}-\x{9fff}][^"]*"'` 实测给 **1530**，而门禁给 **1504** ——
//   差的那 26 条在**注释**里。门禁是先剥注释再计数（`stripComments`），
//   grep 剥不了（注释里的引号、块注释跨行、字符串里的 `//` 都会骗过正则）。
//   基线数字对不上就是「记上去的」，所以这里用与门禁同构的状态机自己扫一遍。
//
// 用法：  node tools/cjk-count.mjs
// 输出：  汉字字面量总数 / 去重条数 / 有残留的文件数 / 按文件排行，
//         外加「已零汉字却仍留全角标点」的文件（对应第二条门禁）。
import fs from 'node:fs';
import path from 'node:path';

// 与 I18nAuditTest.CJK_LITERAL / FULLWIDTH_LITERAL 逐字对应
const HAN = '\u4e00-\u9fff';
const FULLWIDTH = '\u3000-\u303f\uff01-\uff60';
const hanPattern = new RegExp(`"[^"\n]*[${HAN}][^"\n]*"`, 'g');
const fullwidthPattern = new RegExp(`"[^"\n]*[${FULLWIDTH}][^"\n]*"`, 'g');

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name !== 'target') walk(p, out);
    } else if (p.split(path.sep).join('/').includes('src/main') && entry.name.endsWith('.java')) {
      out.push(p);
    }
  }
  return out;
}

/** 状态机剥注释：与 Java 侧 stripComments 同语义（字符串里的 // 不算注释）。 */
function stripComments(src) {
  let out = '', inStr = false, inLine = false, inBlock = false;
  for (let i = 0; i < src.length; i++) {
    const c = src[i], n = i + 1 < src.length ? src[i + 1] : '';
    if (inLine) { if (c === '\n') { inLine = false; out += c; } continue; }
    if (inBlock) {
      if (c === '*' && n === '/') { inBlock = false; i++; }
      else if (c === '\n') out += c;
      continue;
    }
    if (inStr) {
      out += c;
      if (c === '\\') { if (n !== '') { out += n; i++; } }
      else if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') { inStr = true; out += c; }
    else if (c === '/' && n === '/') { inLine = true; i++; }
    else if (c === '/' && n === '*') { inBlock = true; i++; }
    else out += c;
  }
  return out;
}

const files = walk('ui');

/**
 * `node tools/cjk-count.mjs --reuse QueryPage.java` —— 抽某一页之前先问「哪些字面量
 * 资源包里已经有现成的键」。第五批定的规矩：**两侧都对得上**才许复用，
 * 所以这里同时打印 zh 与 en 的值，只匹配 zh 的那一半不算数。
 * （手工 grep 中文容易漏：值里带 `{0}`、带全角空格、或者 en 侧其实不一样。）
 */
const reuseAt = process.argv.indexOf('--reuse');
if (reuseAt >= 0) {
  const want = (process.argv[reuseAt + 1] || '').toLowerCase();
  const hit = files.filter(f => path.basename(f).toLowerCase().includes(want));
  if (hit.length === 0) {
    console.error(`没有文件名匹配「${want}」`);
    process.exit(1);
  }
  const bundleDir = 'ui/adb-ui-core/src/main/resources/io/agenticdb/ui';
  const readProps = p => {
    const map = new Map();
    for (const line of fs.readFileSync(p, 'utf8').split('\n')) {
      const m = line.match(/^\s*([^#!=\s][^=]*)=(.*)$/);
      if (m) map.set(m[1].trim(), m[2]);
    }
    return map;
  };
  const zh = readProps(`${bundleDir}/messages_zh.properties`);
  const en = readProps(`${bundleDir}/messages_en.properties`);
  for (const f of hit) {
    const stripped = stripComments(fs.readFileSync(f, 'utf8'));
    const lits = new Map();
    for (const m of stripped.matchAll(hanPattern)) {
      const v = m[0].slice(1, -1);
      lits.set(v, (lits.get(v) || 0) + 1);
    }
    const reusable = [];
    for (const [lit, n] of lits) {
      for (const [key, val] of zh) {
        if (val === lit) reusable.push([lit, n, key, en.get(key) ?? '(en 缺)']);
      }
    }
    reusable.sort((a, b) => b[1] - a[1]);
    console.log(`\n── ${path.basename(f)}：${lits.size} 条去重，其中 ${reusable.length} 处有 zh 完全相同的现成键 ──`);
    for (const [lit, n, key, enVal] of reusable) {
      console.log(`  ${String(n).padStart(2)}×  ${JSON.stringify(lit)}  →  ${key}  = ${JSON.stringify(enVal)}`);
    }
  }
  process.exit(0);
}

/**
 * `node tools/cjk-count.mjs --list QueryPage.java` —— 抽某一页之前的清单：
 * 按「出现次数 × 行号」列出该文件里所有含汉字/全角的字面量。
 * 之所以进这个脚本而不是每次现写：抽文案的每一批第一步都是这份清单，
 * 而现写的版本要用 shell 传正则，转义一次踩一次（本轮踩过）。
 */
const listAt = process.argv.indexOf('--list');
if (listAt >= 0) {
  const want = (process.argv[listAt + 1] || '').toLowerCase();
  const hit = files.filter(f => path.basename(f).toLowerCase().includes(want));
  if (hit.length === 0) {
    console.error(`没有文件名匹配「${want}」（在 ui/ 的 src/main 下）`);
    process.exit(1);
  }
  for (const f of hit) {
    const raw = fs.readFileSync(f, 'utf8');
    const stripped = stripComments(raw);
    const tally = new Map();
    for (const [re, kind] of [[hanPattern, 'han'], [fullwidthPattern, 'full']]) {
      for (const m of stripped.matchAll(re)) {
        const line = stripped.slice(0, m.index).split('\n').length;
        const key = m[0];
        if (!tally.has(key)) tally.set(key, { n: 0, kind, line });
        const rec = tally.get(key);
        rec.n += 1;
        if (kind === 'han' && rec.kind === 'full') rec.kind = 'han';
      }
    }
    const rows = [...tally.entries()].sort((a, b) => b[1].n - a[1].n || a[1].line - b[1].line);
    const total = rows.reduce((s, [, r]) => s + r.n, 0);
    console.log(`\n── ${path.basename(f)}：${total} 次出现 / ${rows.length} 条去重 ──`);
    for (const [lit, r] of rows) {
      console.log(`  ${String(r.n).padStart(2)}×  L${String(r.line).padStart(4)}  ${lit}`);
    }
  }
  process.exit(0);
}

let hanTotal = 0;
const unique = new Set();
const rows = [];
const cleanButFullwidth = [];
let cleanCount = 0;
for (const f of files) {
  const source = stripComments(fs.readFileSync(f, 'utf8'));
  const han = source.match(hanPattern) || [];
  const fullwidth = source.match(fullwidthPattern) || [];
  hanTotal += han.length;
  for (const x of han) unique.add(x);
  if (han.length > 0) rows.push([han.length, path.basename(f)]);
  else if (fullwidth.length > 0) cleanButFullwidth.push(`${path.basename(f)} = ${fullwidth.length}`);
  else cleanCount++;
}
rows.sort((a, b) => b[0] - a[0]);
console.log(`汉字字面量 TOTAL ${hanTotal} · UNIQUE ${unique.size}`
        + ` · 有残留 ${rows.length} 个文件 · 零汉字零全角 ${cleanCount} 个 · 共扫 ${files.length} 个`);
console.log(rows.map(r => String(r[0]).padStart(4) + '  ' + r[1]).join('\n'));
if (cleanButFullwidth.length) {
  console.log('\n⚠ 已零汉字却仍留全角标点（第二条门禁会红）：');
  console.log(cleanButFullwidth.join('\n'));
}
