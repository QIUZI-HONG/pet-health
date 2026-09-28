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
| `SessionGate` | 四态的统一入口：加载中 / 服务异常 / 未登录 / 正常内容（页面用它包内容，别自己写 if 链） |
| `HealthScoreCard` | 首页健康评分卡：五维 + 趋势 + 免责声明 |
| `CheckInCard` | 今日健康任务：行内录入、一键「全部正常」「和昨天一样」、撤销单项 |
| `PhotoUploader` | 档案照片上传（预签名直传，JPEG/PNG，单张 ≤10MB，最多 9 张） |
| `ComplianceCard` | 账号与条款：三份合规文档入口 + 数据导出 + 注销（二次确认） |
| `states/StateLoading` | 加载态 |
| `states/StateEmpty` | 空态 |
| `states/StateError` | 错误态（带重试） |
| `states/StateForbidden` | 无权限 / 未登录态（带去登录） |

> 从 `HealthScoreCard` 起的四个组件是**只服务一个页面**的（首页 / 档案页 / 我的页），按上面的规矩本该
> 留在页面里；把它们放进来是因为各自都带着一段不长但容易写错的行为（评分分档、打卡的幂等交互、
> 上传流程、注销确认），塞进页面会让 `views/` 涨到几千行。**再加同类组件前先重新掂量这条**。

按钮、卡片、表单字段这些**没有做成组件，是 `styles/app.css` 里的基类**（`.ph-button` / `.ph-card` /
`.ph-field`）：它们只差样式、没有行为，做成 Vue 组件只是多一层包装。四态的公共骨架也在那里
（`.ph-state*`），组件里只留各自的差异。

## 四态组件怎么用

拿数据的页面按这个顺序判断，四种状态就是「数据还没来 / 没有数据 / 拿失败 / 没资格拿」：

**页面不要再手写这串 if——用 `SessionGate`**（它把顺序固定住，顺序错了就是 bug）：

```vue
<SessionGate forbidden-description="登录后查看你的记录。">
  <StateLoading v-if="loading" />
  <StateError v-else-if="error" :message="error" @retry="reload" />
  <StateEmpty v-else-if="items.length === 0" title="还没有记录" />
  <template v-else> …正常内容… </template>
</SessionGate>
```

⚠️ **顺序不能反**：`服务异常` 必须排在 `未登录` 前面。反过来写，后端一抖就会骗用户「请登录」，
而且会话会被清掉（这个 bug 真出现过，见 `stores/session.ts` 的注释与 `session-failure.spec.ts`）。
