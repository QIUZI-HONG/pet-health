/**
 * ESLint（flat config）——**只查正确性，不管格式**。
 *
 * 为什么只做这两件事：
 *
 * 1. **格式交给 .editorconfig 与既有习惯**：仓库里三个端的写法本来就一致（缩进、引号、分号），
 *    引入 Prettier 会把整个仓库重排一遍，那种 diff 除了噪音什么都看不出来（ADR-0015 的组件约定、
 *    `docs/conventions.md` 已经写清了更重要的那几条）；
 * 2. **规则集从「能稳定跑绿」的那一档起步**：`typescript-eslint` 的 recommended 里那些
 *    与 Vue SFC 打架的、以及纯风格的，都显式关掉（各条都写了理由）。
 *    一条常年红的 lint 等于没有 lint——那正是这个仓库至今没接 lint 的现状。
 *
 * 它补的是**类型检查与测试都看不见的那一类**：意外的 `any`、未使用的变量、
 * Vue 模板里写错的指令、`vue/no-mutating-props` 这类改了也能跑的写法。
 *
 * **还没做**：`@typescript-eslint/no-floating-promises`（漏 await）需要类型感知的 lint，
 * 跑得慢一倍——等这条流水线稳定后单开一刀。别把这一条当成已经覆盖了。
 */
import js from "@eslint/js";
import tseslint from "typescript-eslint";
import pluginVue from "eslint-plugin-vue";
import globals from "globals";

export default tseslint.config(
  {
    // 生成物、构建产物与依赖：契约生成的 .d.ts 是产物（改了要重新生成，不是手改）
    ignores: [
      "**/node_modules/**",
      "**/dist/**",
      "**/target/**",
      "packages/shared/src/api/*.d.ts",
      "deploy/**",
      "ai/**",
    ],
  },

  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs["flat/recommended"],

  {
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
      parserOptions: {
        // Vue SFC 里 `<script setup lang="ts">` 由 vue-eslint-parser 交给 ts 解析
        parser: tseslint.parser,
        extraFileExtensions: [".vue"],
      },
    },
    rules: {
      // —— 与 Vue SFC 的既有写法协调 ——
      // 组件名不强求多词（`CouponCard` / `CheckInCard` 都是单词+语义清楚，ADR-0015 的命名约定已经定了）
      "vue/multi-word-component-names": "off",
      // 属性换行、单双引号这些是格式，不是正确性
      "vue/max-attributes-per-line": "off",
      "vue/singleline-html-element-content-newline": "off",
      "vue/html-self-closing": "off",
      "vue/attributes-order": "off",
      "vue/html-indent": "off",
      "vue/html-closing-bracket-newline": "off",
      "vue/first-attribute-linebreak": "off",
      // `flat/recommended` 里还剩一批纯格式的（引号风格、等号两侧空格、插值两侧空格…）：
      // 它们与 .editorconfig 的既有习惯重叠，开着只会在每次小改动时报一堆与正确性无关的警告
      "vue/html-quotes": "off",
      "vue/v-bind-style": "off",
      "vue/v-on-style": "off",
      "vue/mustache-interpolation-spacing": "off",
      "vue/no-spaces-around-equal-signs-in-attribute": "off",
      "vue/multiline-html-element-content-newline": "off",
      "vue/attribute-hyphenation": "off",
      "vue/order-in-components": "off",
      "vue/this-in-template": "off",

      // —— TypeScript 口径 ——
      // 与 vue-tsc 的 noUnusedLocals 重叠但更快暴露；允许 `_` 前缀显式忽略
      "@typescript-eslint/no-unused-vars": [
        "error",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_", caughtErrors: "none" },
      ],
      // 类型检查已经覆盖了大部分，这里只拦「写了 any 就绕过了一切」的那类
      "@typescript-eslint/no-explicit-any": "warn",
      // 既有代码里 `!` 断言已在本轮清过一轮（D-37），剩下的记警告而不是阻断
      "@typescript-eslint/no-non-null-assertion": "warn",

      // —— 正确性（这几条是接 lint 的主要收益）——
      eqeqeq: ["error", "always", { null: "ignore" }],
      "no-console": ["warn", { allow: ["warn", "error"] }],
      "no-var": "error",
      "prefer-const": "error",
      // 未处理的 promise 是这类前端最常见的真 bug（漏 await）
      "no-async-promise-executor": "error",
      // **不开 `require-atomic-updates`**：它是为 Node 那种共享变量设计的，
      // 在 Vue 的 `ref().value = await ...` 模式下会把每一处都报成「可能的竞态」——
      // 实测 45 个 error 里 9 个是它、且全是误报。要抓「漏 await」应该用
      // `@typescript-eslint/no-floating-promises`，那需要类型感知的 lint（跑得慢一倍），
      // 等这条流水线稳定后再单开一刀。
    },
  },

  // 测试文件与配置文件：放宽「不许 any」之类
  {
    files: ["**/__tests__/**", "**/*.spec.ts", "**/vite.config.ts", "**/*.mjs", "**/scripts/**"],
    rules: {
      "@typescript-eslint/no-explicit-any": "off",
      "no-console": "off",
      // 测试里 `wrapper.findAll(...)!.trigger(...)` 是惯用写法：断言失败会当场报错，
      // 换成守卫反而把「这个元素不存在」变成一句更难读的报错
      "@typescript-eslint/no-non-null-assertion": "off",
    },
  },
);
