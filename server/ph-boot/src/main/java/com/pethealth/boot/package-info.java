/**
 * 启动模块：{@code PetHealthApplication} + 数据库迁移脚本 + 端到端接口测试。
 *
 * <p>它自己不写业务代码（除了启动类），存在的理由有三个：
 *
 * <ul>
 *   <li><b>唯一可启动的入口</b>：其它 12 个模块都是库，只有这里打得出可运行的 jar，
 *       组件扫描与 Mapper 扫描都从 {@code PetHealthApplication} 所在包出发；</li>
 *   <li><b>迁移脚本的家</b>：{@code resources/db/migration}（Flyway 正向）与
 *       {@code resources/db/undo}（手工回滚，U&lt;版本号&gt; 与 V 一一对应，ADR-0011）；
 *       放这里是因为迁移属于**部署形态**而不是某个领域模块；</li>
 *   <li><b>接口与契约测试的家</b>：{@code src/test} 下的用例跑在 Testcontainers 起的
 *       真实 MySQL/Redis 上，测的是「HTTP → 领域 → 数据库」整条链（ADR-0014）——
 *       只有这里能同时看到全部模块。</li>
 * </ul>
 */
package com.pethealth.boot;
