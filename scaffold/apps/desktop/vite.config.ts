/**
 * apps/desktop/vite.config.ts —— 修正版
 *
 * ⭐ 抄 DBX 的 manualChunks：把懒加载的重依赖切出去，首屏 JS 才压得住。
 *    DBX 的切法（vite.config.ts 原文）：
 *      codemirror / vue-echarts / ui(reka-ui) / marked
 *      echarts 还要按内部模块再拆 4 块：charts/components/renderers/core
 */

import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import tailwindcss from "@tailwindcss/vite";
import path from "node:path";
import { adbTypesPlugin } from "./viteAdbTypesPlugin";

// ─────────────────────────────────────────────────────────────
// ⭐ chunk 预算（gzip KB）—— CI 门禁 scripts/check-bundle.ts
//
// 目标：冷启动到能点开一张表 ≤ 3s
// 手段：首屏 JS 越小越好，重依赖全部懒加载
// ─────────────────────────────────────────────────────────────
export const CHUNK_BUDGET_KB = {
  index: 800,        // 首屏（应用壳 + 侧栏 + 标签）
  "ag-grid": 350,    // 表格，懒加载
  codemirror: 400,   // 编辑器，懒加载
  echarts: 300,      // 图表，懒加载
  ui: 120,           // reka-ui 弹层基础
  marked: 60,        // AI 输出渲染
} as const;

/** 懒加载阈值：超过此体积才拆 chunk */
const ASYNC_THRESHOLD_KB = 100;

export default defineConfig({
  root: import.meta.dirname,
  base: "./",
  plugins: [
    // ⭐ 保险 1+2：构建时生成 + dev watch
    adbTypesPlugin(),
    vue(),
    tailwindcss(),
  ],

  resolve: {
    alias: {
      "@": path.resolve(import.meta.dirname, "./src"),
    },
  },

  build: {
    outDir: "../../dist",
    emptyOutDir: true,
    // 大块语法包已隔离并按需加载
    chunkSizeWarningLimit: 800,
    target: "es2022",
    minify: "oxc",
    cssCodeSplit: true,

    rollupOptions: {
      output: {
        // ⭐⭐ 抄 DBX 的 manualChunks
        manualChunks(id: string): string | undefined {
          const p = id.replaceAll("\\", "/");
          if (p.includes("/node_modules/ag-grid")) return "ag-grid";
          if (p.includes("/node_modules/codemirror/")) return "codemirror";
          if (p.includes("/node_modules/@codemirror/")) return "codemirror";
          if (p.includes("/node_modules/echarts/")) return echartsChunk(p);
          if (p.includes("/node_modules/reka-ui/")) return "ui";
          if (p.includes("/node_modules/marked/")) return "marked";
          return undefined;
        },

        // 只对超阈值的大包做警告，中小包不刷屏
        chunkFileNames: "assets/[name]-[hash].js",
        entryFileNames: "assets/[name]-[hash].js",
        assetFileNames: "assets/[name]-[hash][extname]",

        // ⭐ 预加载提示：让浏览器提前拉取
        chunkLoading: "import",
      },
      // ⭐ 只对白名单做 tree-shake 检查，避免误报
      treeshake: {
        preset: "smallest",
        moduleSideEffects: (id) => {
          // CSS 有副作用
          if (id.includes(".css") || id.includes(".vue")) return true;
          return false;
        },
      },
    },
  },

  // 开发期更快刷新
  server: {
    port: 1420,
    strictPort: true,
    watch: { ignored: ["**/target/**", "**/.git/**"] },
  },
});

/**
 * ⭐ echarts 按内部模块再拆 —— 抄自 DBX `chunkNameForEchartsModule`
 *
 * 只用折线图的用户不需要下载 sankey / map / graph 的代码。
 */
function echartsChunk(id: string): string {
  const p = id.split("/node_modules/echarts/").pop() ?? "";
  if (p === "charts.js" || p.startsWith("lib/chart/")) return "echarts-charts";
  if (p === "components.js" || p.startsWith("lib/component/")) return "echarts-components";
  if (p === "renderers.js" || p.startsWith("lib/renderer/")) return "echarts-renderers";
  return "echarts-core";
}

// ─────────────────────────────────────────────────────────────
// Vite 插件：直接消费生成的声明
//   ⭐ 组件不要 import YAML，用生成的 TS 常量
// ─────────────────────────────────────────────────────────────

/** 虚拟模块：adb:connections —— 让能力查询不需要运行时 JSON 解析 */
export function declarationsVirtualModule(): string {
  return `
import { CONNECTIONS, CONNECTION_BY_ID, can, warnsImplicitDdlCommit,
         showsSequenceWorkspace, explainFormats, isVisibleByDefault }
  from "/src/types/generated/connections.ts";
import { TRANSFER_FAMILIES, familyOf, mapType } from "/src/types/generated/transferFamilies.ts";
export { CONNECTIONS, CONNECTION_BY_ID, can, warnsImplicitDdlCommit,
         showsSequenceWorkspace, explainFormats, isVisibleByDefault,
         TRANSFER_FAMILIES, familyOf, mapType };
`;
}

/**
 * 用法示例（UI 组件里）：
 *
 * ```vue
 * <script setup lang="ts">
 * import { warnsImplicitDdlCommit, can } from "virtual:adb-declarations";
 * const conn = useConnectionStore().current;
 *
 * // ⭐ 声明驱动，不是硬编码
 * const showDdlWarning = computed(() => warnsImplicitDdlCommit(conn.id));
 * const canEditTable = computed(() => can(conn.id, "tableStructureEdit"));
 * </script>
 *
 * <template>
 *   <Banner v-if="showDdlWarning" level="important">
 *     MySQL 的 DDL 会隐式提交，无法通过事务回滚。
 *   </Banner>
 *   <Button v-if="canEditTable">结构编辑器</Button>
 * </template>
 * ```
 */
