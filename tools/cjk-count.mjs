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
