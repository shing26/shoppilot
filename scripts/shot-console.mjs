// 抓两张调试台截图：主界面与工单抽屉。用于人工核对布局，不参与验收断言。
// 用法: $env:NODE_PATH="<repo>\.tools\node_modules"; node scripts/shot-console.mjs
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');

const BASE = process.env.SHOPPILOT_CONSOLE_BASE || 'http://127.0.0.1:8082';
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
await page.goto(BASE + '/console.html', { waitUntil: 'networkidle' });
await page.fill('#q', '生鲜坏了怎么赔');
await page.click('#btnSend');
await page.waitForSelector('.ev.done', { timeout: 90000 });
await page.waitForTimeout(300);
// 主图先拍：ops 令牌此时为空，截图里不会出现任何凭证（round37 安全审查 N5）。
await page.screenshot({ path: 'docs/console.png' });
// 工单抽屉是 ops 面，需要令牌；脚本用本机默认值（与 DevDefaultsPolicy 同源），
// 打开后立刻清空输入框再拍第二张，避免把令牌留在证据图里。
await page.fill('#opsToken', process.env.SHOPPILOT_OPS_TOKEN || 'dev-ops-token');
await page.waitForTimeout(500);
await page.click('#btnTickets');
await page.waitForSelector('#tickets .tk', { timeout: 15000 });
await page.fill('#opsToken', '');
await page.waitForTimeout(300);
await page.screenshot({ path: 'docs/console-tickets.png' });
await browser.close();
console.log('shots written to docs/console.png and docs/console-tickets.png');
