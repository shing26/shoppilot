// round44 的视觉证据：三入口 × 桌面宽 / 390px。
//
// 用法: $env:NODE_PATH="<repo>\.tools\node_modules"; node scripts/capture-r44-screens.mjs
//   环境变量：
//     SHOPPILOT_GATEWAY_BASE  默认 http://127.0.0.1:8082
//
// 产物落 `docs/r44-*.png`，由 `docs/EVIDENCE.md` 登记。
// **截图证不了「好不好看」**：它只证「这一刻页面长这样」。结论里必须把
// 「脚本实测的结构」与「所有者主观确认的观感」分开写。
import { createRequire } from 'node:module';
const { chromium } = createRequire(import.meta.url)('playwright');

const BASE = process.env.SHOPPILOT_GATEWAY_BASE || 'http://127.0.0.1:8082';
const OUT = new URL('../docs/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');

const DESKTOP = { width: 1440, height: 900 };
const MOBILE = { width: 390, height: 844 };

const browser = await chromium.launch();
const shots = [];

async function shoot(name, path, { viewport, prepare, fullPage = false }) {
  const page = await browser.newPage({ viewport });
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  // `networkidle` 在本机等不到：/actuator/health/deps 在 ES/Qdrant 不可达时挂 40 s
  // （实测 40.04 s 后 503），页面永远不会「网络空闲」。截图要的是**渲染完成**，
  // 用 `load` + 下面那个固定停顿；`prepare` 里各自等自己的元素。
  await page.goto(BASE + path, { waitUntil: 'load' });
  if (prepare) await prepare(page);
  await page.waitForTimeout(350);
  const file = `${OUT}${name}.png`;
  await page.screenshot({ path: file, fullPage });
  shots.push({ name, path, viewport: `${viewport.width}x${viewport.height}`, errors });
  await page.close();
  console.log(`saved ${name}.png  ${viewport.width}x${viewport.height}${errors.length ? '  ⚠ ' + errors.join(' | ') : ''}`);
}

// 登录态的准备动作有三处共用：登录门默认收起口令表单，一键体验按钮是唯一稳定的入口。
const enterBuyer = async (page) => {
  await page.locator('.gate button:has-text("一键体验买家")').click();
  await page.waitForSelector('.buyer-layout', { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(600);
};

const enterWorkspace = async (page) => {
  await page.locator('.login-card button:has-text("一键体验坐席")').click();
  await page.waitForSelector('.console', { timeout: 15000 }).catch(() => {});
  await page.waitForSelector('.ticket-card', { timeout: 15000 }).catch(() => {});
};

// ① 演示门户（视觉基准）
await shoot('r44-index-desktop', '/', { viewport: DESKTOP });
await shoot('r44-index-mobile', '/', { viewport: MOBILE });

// ② 调试台（本票只改样式）
await shoot('r44-console-desktop', '/console.html', { viewport: DESKTOP });
await shoot('r44-console-mobile', '/console.html', { viewport: MOBILE });

// ③ 买家中心
await shoot('r44-buyer-desktop', '/buyer/?store=T001&guest=0', { viewport: DESKTOP, prepare: enterBuyer });
await shoot('r44-buyer-mobile', '/buyer/?store=T001&guest=0', { viewport: MOBILE, prepare: enterBuyer });

// ④ 坐席工作台：队列（票 189 的深度卡在工作台侧是生命周期与退款决策路径）
await shoot('r44-workspace-queue-desktop', '/workspace/', { viewport: DESKTOP, prepare: enterWorkspace });
await shoot('r44-workspace-queue-mobile', '/workspace/', { viewport: MOBILE, prepare: enterWorkspace });

// ⑤ 知识飞轮（票 190 的新面）
await shoot('r44-kb-desktop', '/workspace/', {
  viewport: DESKTOP,
  fullPage: true,
  prepare: async (page) => {
    await enterWorkspace(page);
    await page.locator('.side-nav button:has-text("知识飞轮")').click();
    await page.waitForSelector('.kb', { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(800);
  },
});
await shoot('r44-kb-mobile', '/workspace/', {
  viewport: MOBILE,
  fullPage: true,
  prepare: async (page) => {
    await enterWorkspace(page);
    await page.locator('.side-nav button:has-text("知识飞轮")').click();
    await page.waitForSelector('.kb', { timeout: 15000 }).catch(() => {});
    await page.waitForTimeout(800);
  },
});

await browser.close();

const noisy = shots.filter((shot) => shot.errors.length);
console.log(`\n共 ${shots.length} 张，落 ${OUT}`);
if (noisy.length) {
  console.log('有页面异常的截图：');
  for (const shot of noisy) console.log(`  ${shot.name}: ${shot.errors.join(' | ')}`);
  process.exit(1);
}
