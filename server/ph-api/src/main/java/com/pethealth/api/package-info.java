/**
 * 接口 DTO：**手写，与 contract/ 的 OpenAPI 对齐**（AGENTS.md 定的分工）。
 *
 * <p>生成物是**前端**的类型（`packages/shared/src/api/*.d.ts`，由 `pnpm gen:api` 从契约生成）；
 * 后端这两份是两份手写产物，改契约时两边都要跟——契约是唯一源头，谁都不许自己发明字段。
 *
 * <p>模块边界（ADR-0006）：只能经由对外接口或领域事件与其它模块通信，禁止 join 其它模块的表。
 */
package com.pethealth.api;
