# C 端组件

自研轻量组件（[ADR-0015](../../../docs/adr/0015-frontend-component-strategy.md)）：C 端不用 Element Plus，
因为它一眼就是「后台管理系统」，与视觉稿的卡片化观感差太远。

## 规矩

- **颜色、字号、圆角、间距一律走 `var(--ph-*)`**，不写死色值——`scripts/check-tokens.mjs` 在构建时扫，
  发现 `#RGB` / `rgb()` / `hsl()` 直接失败。
- **组件要么够通用，要么留在页面里。** 只为某一个页面服务的东西不进这个目录，免得长成半成品组件库。
- **不渲染用户输入的 HTML**（不用 `v-html`）：令牌放在 localStorage（ADR-0012），XSS 是唯一的现实威胁。

## 现有组件

| 组件 | 用途 |
| --- | --- |
| `AppSidebar` | 左侧主导航（5 个主页面） |
| `AppTopbar` | 顶栏：当前宠物、通知、账号 |
| `PetSwitcher` | 宠物切换下拉（多宠家庭） |
| `PhButton` | 按钮（主/次/文字三种，带禁用与加载态） |
| `PhCard` | 内容卡片（首页各区块的容器） |
| `states/StateLoading` | 加载态 |
| `states/StateEmpty` | 空态 |
| `states/StateError` | 错误态（带重试） |
| `states/StateForbidden` | 无权限 / 未登录态（带去登录） |

## 四态组件怎么用

拿数据的页面按这个顺序判断，四种状态就是「数据还没来 / 没有数据 / 拿失败 / 没资格拿」：

```vue
<StateLoading v-if="session.status === 'loading'" />
<StateForbidden v-else-if="!session.isLoggedIn" />
<StateError v-else-if="error" :message="error" @retry="reload" />
<StateEmpty v-else-if="items.length === 0" title="还没有记录" />
<template v-else> …正常内容… </template>
```
