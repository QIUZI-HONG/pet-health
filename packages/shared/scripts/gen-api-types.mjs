#!/usr/bin/env node
/**
 * 从 `contract/*.yaml` 生成前端的 TS 类型。
 *
 * 契约是唯一源头（ADR-0005）：改完契约跑这个，**不要手写接口类型**。
 *
 *   pnpm --filter @pet-health/shared gen:api          生成
 *   pnpm --filter @pet-health/shared gen:api:check    CI 用：生成物与契约不同步则失败
 *
 * 每个契约文件产出一份 .d.ts，文件名一一对应。跨文件 `$ref: './common.yaml#/...'`
 * 由生成器自动解析并内联，所以各端契约可以放心引用公共组件。
 */
import { execFileSync } from "node:child_process";
import { mkdirSync, readdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "../../..");
const contractDir = join(repoRoot, "contract");
const outDir = resolve(here, "../src/api");
const check = process.argv.includes("--check");

mkdirSync(outDir, { recursive: true });

const files = readdirSync(contractDir)
  .filter((f) => f.endsWith(".yaml"))
  .sort();

let failed = 0;
for (const f of files) {
  const out = join(outDir, f.replace(/\.yaml$/, ".d.ts"));
  const args = [join(contractDir, f), "-o", out];
  if (check) args.push("--check");
  try {
    execFileSync("openapi-typescript", args, { stdio: "pipe" });
    console.log(`  ✓ ${f} → ${out.slice(repoRoot.length + 1)}`);
  } catch (e) {
    failed++;
    console.error(`  ✗ ${f}${check ? "（生成物与契约不同步，先跑 gen:api）" : ""}`);
    const detail = `${e.stdout ?? ""}${e.stderr ?? ""}`.trim();
    if (detail) console.error(detail.split("\n").slice(0, 8).join("\n"));
  }
}

if (failed) {
  console.error(`\n${failed} 个契约文件处理失败。`);
  process.exit(1);
}
console.log(check ? "\n生成物与契约一致。" : "\n生成完成。");
