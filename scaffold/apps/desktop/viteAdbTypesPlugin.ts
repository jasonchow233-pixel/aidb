/**
 * apps/desktop/viteAdbTypesPlugin.ts
 *
 * 源: declarations/connections/*.yaml + declarations/transfer-families/*.yaml
 * 产出: src/types/generated/{connections,transferFamilies}.ts
 *
 * ⭐ 设计来源: DBX `apps/desktop/viteConnectionTypesPlugin.ts`
 *    配套还有三条保险（见 generate-types.ts 与 package.json）：
 *      1. pretypecheck / pretest 钩子
 *      2. 生成物提交进 git
 *      3. pnpm check:types（CI 只读校验）
 */

import type { Plugin } from "vite";
import { generate } from "./scripts/generate-types";

export const DECLARATIONS_DIR = "../../declarations";
export const GENERATED_DIR = "src/types/generated";

export function adbTypesPlugin(): Plugin {
  return {
    name: "adb-types",

    // ① 构建开始时无条件生成（build 和 serve 都是）
    buildStart() {
      const { errors } = generate();
      if (errors.length) {
        // ⛔ 描述符有问题时立刻失败，不等到运行时
        this.error(`[adb-types] declarations 校验失败:\n${errors.join("\n")}`);
      }
    },

    // ② 声明文件变更时触发重建（含新增/删除文件）
    configureServer(server) {
      const watchDirs = [
        `${DECLARATIONS_DIR}/connections`,
        `${DECLARATIONS_DIR}/transfer-families`,
      ];
      for (const dir of watchDirs) {
        server.watcher.add(new URL(dir, import.meta.url).pathname);
      }
      server.watcher.on("add", (f) => { if (isDeclaration(f)) regen(); });
      server.watcher.on("change", (f) => { if (isDeclaration(f)) regen(); });
      server.watcher.on("unlink", (f) => { if (isDeclaration(f)) regen(); });

      function regen() {
        const { errors, changed } = generate();
        if (errors.length) {
          server.config.logger.error(`[adb-types] ${errors.join("\n")}`);
          return;
        }
        if (changed.length) {
          server.config.logger.info(
            `\x1b[36m[adb-types]\x1b[0m 重新生成: ${changed.join(", ")}`,
          );
        }
      }
    },
  };
}

function isDeclaration(file: string): boolean {
  return (
    file.includes("/declarations/connections/") ||
    file.includes("/declarations/transfer-families/")
  ) &&
    (file.endsWith(".yaml") || file.endsWith(".yml"));
}
