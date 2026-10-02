// 验收式全功能演示视频：每个可操作功能都真实执行（会写入演示库，属预期）
import { chromium } from 'playwright-core';
import fs from 'node:fs';

const OUTDIR = '/tmp/docxwork/vids2';
fs.mkdirSync(OUTDIR, { recursive: true });
const browser = await chromium.launch({
  executablePath: process.env.HOME + '/.cache/ms-playwright/chromium-1243/chrome-linux64/chrome',
  args: ['--no-sandbox'],
});
const context = await browser.newContext({
  viewport: { width: 1280, height: 800 },
  recordVideo: { dir: OUTDIR, size: { width: 1280, height: 800 } },
});
const page = await context.newPage();
let T0 = Date.now();
const P = (ms) => page.waitForTimeout(ms);
const steps = [];
const caps = [];
const CAPS = {
  'C端 登录': 'C 端：手机号 + 密码登录；游客也可先浏览服务',
  'C端 首页浏览': '首页 = 健康驾驶舱：评分 / 需关注 / 今日任务 / 券与邀请',
  'C端 打卡：体重': '打卡：点任务行 → 填值 → 提交；连续打卡影响评分与奖励',
  'C端 服务页': '服务页：平台统一目录 + 附近门店（区间价）',
  'C端 进店': '进店看资质与报价；价格只能在平台区间内',
  'C端 点预约进下单页': '预约下单：选项目 → 选时段 → 用券',
  'C端 选明天的时段': '时段按门店营业时间切格；已占用的不可选',
  'C端 提交订单（自动匹配最优券）': '提交即占时段、锁券；到店付（平台不经手资金）',
  'C端 AI 管家 真问答': 'AI 管家：风险分级 + 知识库引用 + 免责声明（红线必就医）',
  'C端 转人工咨询': 'AI 兜不住 → 转人工，平台人工 2 小时内回复',
  'C端 我的订单（看新单）': '订单状态随履约推进：预约 → 履约中 → 完成',
  'C端 券包': '券包：面额 / 门槛 / 有效期 / 适用门店一目了然',
  'C端 积分': '积分只兑换券；来源：打卡 / 邀请 / 任务',
  '服务者 登录': '服务者后台：独立登录域（账号与 C 端同一批）',
  '服务者 概览（看今日订单）': '今日概览：5 分钟看完当天经营',
  '服务者 订单管理：接单': '接单后进入履约；到店核销',
  '服务者 核销管理：核销订单': '核销 = 先查后核（订单号 / 手机号 / 6 位码定位）',
  '服务者 券管理：贡献并承诺额度': '券池承诺额度：我店愿意接多少张；承诺 ≠ 发放',
  '服务者 服务标准：越界价校验 + 改价': '改价 = 重新审核；越界价当场拦截（与后端同文案）',
  '服务者 设置：营业时间保存': '营业时间决定可约时段；保存即生效',
  '服务者 考核/看板/结算': '考核 = 拉新 40% + 券 40% + 过程 20%；结算是对账视图',
  '运营 登录': '运营后台：名单制准入（fail-closed）',
  '运营 入驻审核：看材料并【审核通过】': '入驻审核：通过 = 门店转正常 + 申请人成为管理员',
  '运营 上架审核：通过并上架': '上架审核：价格区间是现取的；通过并上架',
  '运营 用户管理：查询 id=1': '用户管理：按 id 查积分 / 权益 / 邀请',
  '运营 标准目录/券池/补贴窗口': '目录 / 券池 / 补贴：平台侧配置，服务者只能承诺',
  '运营 AI 运营（知识条目）': 'AI 运营：提示词 / 红线 / 知识条目复核——改完即生效',
  '运营 积分邀请/考核规则配置': '积分与考核规则：运营可配（数值待业务确认）',
  '运营 数据看板（含订单与履约）': '数据看板：订单与履约口径 = 门店应收合计',
};
async function caption(text) {
  // 把讲解条画进页面：位置固定、不挡点击，随录制逐帧同步
  await page.evaluate((t) => {
    let bar = document.getElementById('__demo_caption');
    if (!bar) {
      bar = document.createElement('div');
      bar.id = '__demo_caption';
      bar.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:2147483647;' +
        'background:rgba(8,28,48,0.86);color:#FFFFFF;font:17px/2.1 "Microsoft YaHei","Noto Sans SC",sans-serif;' +
        'text-align:center;padding:4px 28px;pointer-events:none;letter-spacing:0.3px';
      document.body.appendChild(bar);
    }
    bar.textContent = t;
  }, text);
}
async function step(label, fn) {
  if (CAPS[label]) { caps.push({ t: T0 ? (Date.now() - T0) / 1000 : 0, text: CAPS[label] }); await caption(CAPS[label]); }
  try { await fn(); console.log('✓', label); steps.push('✓ ' + label); }
  catch (e) { console.log('✗', label, '|', String(e).slice(0, 90)); steps.push('✗ ' + label); }
}
const first = (selOrLoc) => selOrLoc.first();

// ================= C 端 =================
await step('C端 登录', async () => {
  await page.goto('http://localhost:5173/login', { waitUntil: 'domcontentloaded' }); await P(1200);
  await page.locator('input[placeholder="13800138000"]').first().fill('13800138000'); await P(400);
  await page.locator('input[type=password]').first().fill('pet12345'); await P(400);
  await page.getByRole('button', { name: /登录|注册/ }).first().click(); await P(2000);
});
await step('C端 首页浏览', async () => {
  await page.mouse.wheel(0, 350); await P(1200); await page.mouse.wheel(0, 350); await P(1000);
});
await step('C端 打卡：体重', async () => {
  await page.locator('.ph-checkin__row').first().scrollIntoViewIfNeeded(); await P(600);
  await page.locator('.ph-checkin__row').first().click(); await P(1200);
  const save = page.locator('button.ph-button--primary').last();
  await save.click(); await P(1500);
});
await step('C端 服务页', async () => {
  await page.goto('http://localhost:5173/services', { waitUntil: 'domcontentloaded' }); await P(1500);
  await page.getByRole('button', { name: '医院' }).first().click(); await P(1200);
});
await step('C端 进店', async () => {
  await page.getByRole('link', { name: '查看门店与价格' }).first().click(); await P(2000);
});
await step('C端 点预约进下单页', async () => {
  await page.getByRole('link', { name: '预约' }).first().click(); await P(2200);
});
await step('C端 选明天的时段', async () => {
  const date = page.locator('.ph-order-form__date input').first();
  if (await date.count()) { await date.fill('2026-10-03'); await P(1500); }
  const slot = page.locator('.ph-order-form__slot:not([disabled])').first();
  if (await slot.count()) { await slot.click(); await P(900); }
});
await step('C端 提交订单（自动匹配最优券）', async () => {
  await page.locator('.ph-order-form__submit').first().scrollIntoViewIfNeeded(); await P(600);
  await page.locator('.ph-order-form__submit').first().click(); await P(2500);
});
await step('C端 AI 管家 真问答', async () => {
  await page.goto('http://localhost:5173/ai', { waitUntil: 'domcontentloaded' }); await P(1800);
  await page.locator('textarea').first().fill('我家狗狗今天吐了两次，精神一般，需要马上去医院吗？'); await P(600);
  await page.keyboard.press('Enter');
  for (let i = 0; i < 22; i++) { await P(1500); const t = await page.evaluate(() => document.body.innerText); if (t.includes('风险等级') && !t.includes('分析中')) break; }
  await P(800); await page.mouse.wheel(0, 280); await P(2200);
});
await step('C端 转人工咨询', async () => {
  const t = page.getByRole('button', { name: /转人工/ }).first();
  if (await t.count()) { await t.click(); await P(1500); }
});
await step('C端 我的订单（看新单）', async () => {
  await page.goto('http://localhost:5173/orders', { waitUntil: 'domcontentloaded' }); await P(1800);
  await page.mouse.wheel(0, 250); await P(1200);
});
await step('C端 券包', async () => {
  await page.goto('http://localhost:5173/coupons', { waitUntil: 'domcontentloaded' }); await P(1500);
});
await step('C端 积分', async () => {
  await page.goto('http://localhost:5173/points', { waitUntil: 'domcontentloaded' }); await P(1200);
});

// ================= 服务者后台 =================
await step('服务者 登录', async () => {
  await page.goto('http://localhost:5174/login', { waitUntil: 'domcontentloaded' }); await P(1200);
  await page.locator('input[type=tel]').fill('13800138000'); await P(400);
  await page.locator('input[type=password]').fill('pet12345'); await P(400);
  await page.getByRole('button', { name: '登录' }).click(); await P(2200);
});
await step('服务者 概览（看今日订单）', async () => { await P(1500); await page.mouse.wheel(0, 250); await P(1500); });
await step('服务者 订单管理：接单', async () => {
  await page.goto('http://localhost:5174/b/orders', { waitUntil: 'domcontentloaded' }); await P(1800);
  const btn = page.locator('tbody tr').first().locator('button').first();
  if (await btn.count()) { await btn.click(); await P(1500); }
});
await step('服务者 核销管理：核销订单', async () => {
  await page.goto('http://localhost:5174/b/redeem', { waitUntil: 'domcontentloaded' }); await P(1700);
  const code = await page.evaluate(async () => {
    const t = localStorage.getItem('ph.provider.access_token');
    const grab = async (status) => {
      const url = '/api/v1/provider/orders?page=1&page_size=5' + (status === null ? '' : '&status=' + status);
      const r = await fetch(url, { headers: { Authorization: 'Bearer ' + t } });
      return ((await r.json()).data || {}).list || [];
    };
    let list = await grab(1);
    if (!list.length) list = await grab(0);
    if (!list.length) list = await grab(null);
    list = list.slice().sort((a, b) => b.id - a.id);
    const target = list.find((o) => o.status !== 2) || list[0];
    if (!target) return null;
    const r2 = await fetch('/api/v1/provider/orders/' + target.id, { headers: { Authorization: 'Bearer ' + t } });
    return ((await r2.json()).data || {}).redeem_code || null;
  });
  console.log('   核销码:', code);
  const inp = page.locator('input').first();
  if (code && await inp.count()) { await inp.fill(String(code)); await P(700);
    await page.getByRole('button', { name: '查询订单' }).first().click(); await P(1600);
    const row = page.locator('tbody tr').first().getByRole('button', { name: '核销' }).first();
    if (await row.count()) { await row.click(); await P(2000); }
  }
});
await step('服务者 券管理：贡献并承诺额度', async () => {
  await page.goto('http://localhost:5174/b/coupons', { waitUntil: 'domcontentloaded' }); await P(1700);
  const edit = page.getByRole('button', { name: '调整额度' }).first();
  if (await edit.count()) { await edit.click(); await P(1200); }
  const amount = page.locator('label').filter({ hasText: '承诺可核销额度' }).locator('input').first();
  if (await amount.count()) { await amount.fill('6'); await P(700); }
  const ok = page.getByRole('button', { name: '保存额度' }).first();
  if (await ok.count()) { await ok.click(); await P(2000); }
});
await step('服务者 服务标准：越界价校验 + 改价', async () => {
  await page.goto('http://localhost:5174/b/standard', { waitUntil: 'domcontentloaded' }); await P(1800);
  await page.getByRole('button', { name: '我的服务项' }).first().click(); await P(1500);
  const edit = page.locator('button.ph-table__action').filter({ hasText: '改价' }).first();
  if (await edit.count()) { await edit.click(); await P(1000);
    const price = page.locator('.ph-card input').last();
    await price.scrollIntoViewIfNeeded();
    await price.fill('1.00'); await P(1300);   // 越界：应显示「价格须在…之间」
    await price.fill('84.00'); await P(900);
    const card = page.locator('.ph-card').filter({ hasText: '改价：' }).first();
    const ok = card.locator('button.ph-button--primary').first();
    if (await ok.count()) { await ok.click(); await P(2000); }
    console.log('   改价卡片按钮:', JSON.stringify(await card.locator('button').allInnerTexts().catch(()=>[]))); }
});
await step('服务者 设置：营业时间保存', async () => {
  await page.goto('http://localhost:5174/b/settings', { waitUntil: 'domcontentloaded' }); await P(1800);
  const boxes = page.locator('input[type=checkbox]');
  const n = await boxes.count();
  for (let i = 0; i < Math.min(n, 5); i++) { await boxes.nth(i).check().catch(() => {}); await P(150); }
  const save = page.locator('button').filter({ hasText: /保存营业时间/ }).first();
  if (await save.count()) { await save.scrollIntoViewIfNeeded(); await save.click(); await P(1800); }
});
await step('服务者 考核/看板/结算', async () => {
  for (const u of ['/b/assess', '/b/stats', '/b/settle']) {
    await page.goto('http://localhost:5174' + u, { waitUntil: 'domcontentloaded' }); await P(1400);
    await page.mouse.wheel(0, 260); await P(1100);
  }
});

// ================= 运营后台 =================
await step('运营 登录', async () => {
  await page.goto('http://localhost:5175/login', { waitUntil: 'domcontentloaded' }); await P(1200);
  await page.locator('input[type=tel]').fill('13800138000'); await P(400);
  await page.locator('input[type=password]').fill('pet12345'); await P(400);
  await page.getByRole('button', { name: /登录/ }).first().click(); await P(2200);
});
await step('运营 入驻审核：看材料并【审核通过】', async () => {
  await page.goto('http://localhost:5175/admin/providers', { waitUntil: 'domcontentloaded' }); await P(2000);
  const row = page.locator('tbody tr').first();
  if (await row.count()) {
    await row.getByRole('button', { name: /审核/ }).first().click(); await P(1800);
    await page.mouse.wheel(0, 350); await P(1500);
    const ok = page.getByRole('button', { name: /审核通过/ }).first();
    if (await ok.count()) { await ok.click(); await P(2200); }
  }
});
await step('运营 上架审核：通过并上架', async () => {
  await page.getByRole('button', { name: '服务上架审核' }).first().click(); await P(2000);
  const ok = page.getByRole('button', { name: /通过并上架/ }).first();
  if (await ok.count()) { await ok.click(); await P(2200); }
});
await step('运营 用户管理：查询 id=1', async () => {
  await page.goto('http://localhost:5175/admin/users', { waitUntil: 'domcontentloaded' }); await P(1600);
  await page.locator('input[placeholder="如：1001"]').first().fill('1'); await P(500);
  await page.getByRole('button', { name: '查询' }).first().click(); await P(1800);
  await page.mouse.wheel(0, 300); await P(1200);
});
await step('运营 标准目录/券池/补贴窗口', async () => {
  for (const u of ['/admin/catalog', '/admin/coupon-pool', '/admin/subsidy']) {
    await page.goto('http://localhost:5175' + u, { waitUntil: 'domcontentloaded' }); await P(1500);
    await page.mouse.wheel(0, 260); await P(1000);
  }
});
await step('运营 AI 运营（知识条目）', async () => {
  await page.goto('http://localhost:5175/admin/ai-ops', { waitUntil: 'domcontentloaded' }); await P(1600);
  await page.mouse.wheel(0, 300); await P(1200);
});
await step('运营 积分邀请/考核规则配置', async () => {
  for (const u of ['/admin/points-invite', '/admin/assess-rules']) {
    await page.goto('http://localhost:5175' + u, { waitUntil: 'domcontentloaded' }); await P(1500);
    await page.mouse.wheel(0, 240); await P(1000);
  }
});
await step('运营 数据看板（含订单与履约）', async () => {
  await page.goto('http://localhost:5175/admin/stats', { waitUntil: 'domcontentloaded' }); await P(1800);
  await page.mouse.wheel(0, 400); await P(1600);
});

const video = page.video();
await page.close();
await context.close();                    // 录制在这里落盘
await video.saveAs('/tmp/docxwork/vids2/acceptance-tour.webm');  // 必须在 browser.close 之前
await browser.close();
const target = '/tmp/docxwork/vids2/acceptance-tour.webm';
console.log('视频:', target, (fs.statSync(target).size / 1024 / 1024).toFixed(1), 'MB');
fs.writeFileSync('/tmp/docxwork/captions.json', JSON.stringify(caps));
console.log('步骤结果:\n' + steps.join('\n'));
