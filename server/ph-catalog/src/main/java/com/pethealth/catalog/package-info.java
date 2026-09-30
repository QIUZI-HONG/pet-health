/**
 * 标准服务目录：分类 / 项目 / 价格区间 / 目录外服务提案的入库口
 *
 * <p>模块边界（ADR-0006）：只能经由对外接口或领域事件与其它模块通信，禁止 join 其它模块的表。
 * 本模块对外的三个接口都在 {@code api} 包：
 *
 * <ul>
 *   <li>{@code CatalogQueryApi}——目录项摘要（ph-provider 拼视图用）；
 *   <li>{@code CatalogPricingApi}——价格区间查询与校验（**下单链路要复用**，交付文档 2.5）；
 *   <li>{@code CatalogItemApi}——目录外服务提案审核通过后建正式项目（只见于 ph-provider 的审核动作）。
 * </ul>
 *
 * <p>依赖方向是单向的：ph-provider → ph-catalog。反过来（catalog 需要服务者信息）目前没有需求，
 * 真出现时要靠领域事件或把该字段移出目录的视图，不能加反向依赖——Maven 依赖不允许成环。
 */
package com.pethealth.catalog;
