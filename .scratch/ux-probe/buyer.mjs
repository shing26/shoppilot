// 买家端真实用户走查（round44 之后的产品面复盘，非门禁）。
// 目的：不看断言绿不绿，看「一个人打开页面会不会卡住、会不会被骗、会不会丢东西」。
import { createRequire } from 'node:module'
import fs from 'node:fs'

const { chromium } = createRequire(import.meta.url)('playwright')
const BASE = process.env.SHOPPILOT_BASE || 'http://127.0.0.1:8082'
const SHOTS = new URL('./shots/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
fs.mkdirSync(SHOTS, { recursive: true })

const log = []
const note = (stage, text) => {
  log.push({ stage, text })
  console.log(`[${stage}] ${text}`)
}

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })

const consoleErrors = []
const pageErrors = []
const failedRequests = []
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()) })
page.on('pageerror', (e) => pageErrors.push(String(e)))
page.on('requestfailed', (r) => failedRequests.push(`${r.method()} ${r.url()} — ${r.failure()?.errorText}`))
page.on('response', (r) => { if (r.status() >= 400) failedRequests.push(`HTTP ${r.status()} ${r.request().method()} ${r.url()}`) })

const shot = async (name) => page.screenshot({ path: `${SHOTS}${name}.png`, fullPage: false }).catch(() => {})

// ── 1. 首屏 ────────────────────────────────────────────────────────────────
const t0 = Date.now()
await page.goto(`${BASE}/buyer/?store=T001`, { waitUntil: 'load' })
await page.waitForTimeout(2500)
note('首屏', `load + 2.5s 用时 ${Date.now() - t0}ms；标题=${JSON.stringify(await page.title())}`)
note('首屏', `欢迎区可见=${await page.locator('.welcome').isVisible().catch(() => false)}；登录门可见=${await page.locator('.gate').isVisible().catch(() => false)}`)
note('首屏', `顶栏=${(await page.locator('.topbar').innerText().catch(() => '')).replace(/\s+/g, ' ')}`)
await shot('01-buyer-first-paint')

// ── 2. 首屏卖点是不是「能点」的 ─────────────────────────────────────────────
const welcomePts = await page.locator('.welcome-points span').allInnerTexts().catch(() => [])
note('首屏卖点', `三个卖点 ${JSON.stringify(welcomePts)} — 标签名 span，不是按钮`)
const chips = await page.locator('.quick .chip').allInnerTexts().catch(() => [])
note('快捷问题', JSON.stringify(chips))

// ── 3. 问一句 ───────────────────────────────────────────────────────────────
const askOne = async (q, label) => {
  const start = Date.now()
  await page.fill('.composer input', q)
  await page.locator('.composer button').last().click()
  // 等到不再 asking
  await page.waitForFunction(() => !document.querySelector('.composer .stop'), null, { timeout: 120000 }).catch(() => {})
  const elapsed = Date.now() - start
  const last = page.locator('.thread .msg').last()
  const text = (await last.innerText().catch(() => '')).replace(/\s+/g, ' ')
  note(label, `用时 ${elapsed}ms；气泡=${JSON.stringify(text.slice(0, 260))}`)
  const traceCount = await last.locator('.agent-run summary .agent-meta').innerText().catch(() => '(无轨迹)')
  note(label, `Agent 轨迹=${JSON.stringify(traceCount)}`)
  const cites = await last.locator('.cites').count()
  note(label, `依据面板=${cites}`)
  return { elapsed, text }
}

await askOne('你们店是卖什么的？', '问店铺')
await shot('02-buyer-answer-store')

const orderAnswer = await askOne('帮我查一下订单 90001 的物流', '问物流')
await shot('03-buyer-answer-logistics')

// ── 4. 订单深度卡 ───────────────────────────────────────────────────────────
await page.locator('.side-tab', { hasText: '订单' }).click().catch(() => {})
await page.waitForTimeout(800)
const orderCards = await page.locator('.order-card').count()
note('订单卡', `订单卡 ${orderCards} 张`)
if (orderCards > 0) {
  await page.locator('.order-card').first().click()
  await page.waitForTimeout(1800)
  const detail = (await page.locator('.order-detail').innerText().catch(() => '')).replace(/\s+/g, ' ')
  note('订单卡', `展开内容=${JSON.stringify(detail.slice(0, 500))}`)
  await shot('04-buyer-order-detail')
  const steps = await page.locator('.progress li').evaluateAll((els) =>
    els.map((e) => ({ label: e.className, text: e.innerText.replace(/\s+/g, ' ') }))).catch(() => [])
  note('订单卡', `状态推进条 ${JSON.stringify(steps)}`)
}

// ── 5. 转人工 ───────────────────────────────────────────────────────────────
await askOne('我不想跟机器说了，转人工', '转人工')
await page.waitForTimeout(1200)
await page.locator('.side-tab', { hasText: '工单' }).click().catch(() => {})
await page.waitForTimeout(600)
const ticketCards = await page.locator('.ticket-card').count()
note('工单', `工单卡 ${ticketCards} 张`)
await shot('05-buyer-ticket-side')

// ── 6. 刷新页面：会话与对话是否还在 ─────────────────────────────────────────
const beforeRefreshText = (await page.locator('.thread').innerText().catch(() => '')).replace(/\s+/g, ' ')
await page.reload({ waitUntil: 'load' })
await page.waitForTimeout(2200)
const afterRefreshText = (await page.locator('.thread').innerText().catch(() => '')).replace(/\s+/g, ' ')
note('刷新', `刷新前对话 ${beforeRefreshText.length} 字，刷新后 ${afterRefreshText.length} 字`)
note('刷新', `刷新后欢迎区回来了=${await page.locator('.welcome').isVisible().catch(() => false)}`)
await shot('06-buyer-after-refresh')

// ── 7. 键盘可达性 ───────────────────────────────────────────────────────────
const focusTrail = []
for (let i = 0; i < 14; i += 1) {
  await page.keyboard.press('Tab')
  focusTrail.push(await page.evaluate(() => {
    const el = document.activeElement
    if (!el) return 'null'
    return `${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''}.${(el.className || '').toString().split(' ').filter(Boolean)[0] ?? ''}|${(el.textContent || el.getAttribute('aria-label') || '').trim().slice(0, 22)}`
  }))
}
note('键盘', `Tab 序列 ${JSON.stringify(focusTrail)}`)

// ── 8. 窄屏 ─────────────────────────────────────────────────────────────────
await page.setViewportSize({ width: 390, height: 844 })
await page.waitForTimeout(600)
const narrow = await page.evaluate(() => ({
  scrollW: document.documentElement.scrollWidth,
  innerW: window.innerWidth,
  bodyScrollH: document.documentElement.scrollHeight,
}))
note('窄屏', `390px 溢出读数 ${JSON.stringify(narrow)}`)
const overflowing = await page.evaluate(() => {
  const out = []
  for (const el of document.querySelectorAll('body *')) {
    const r = el.getBoundingClientRect()
    if (r.width > 0 && r.right > window.innerWidth + 2) {
      out.push(`${el.tagName.toLowerCase()}.${(el.className || '').toString().split(' ')[0]} right=${Math.round(r.right)}`)
    }
  }
  return out.slice(0, 12)
})
note('窄屏', `右溢元素 ${JSON.stringify(overflowing)}`)
await shot('07-buyer-mobile')

console.log('\n=== console errors ===')
console.log(consoleErrors.join('\n') || '(none)')
console.log('\n=== page errors ===')
console.log(pageErrors.join('\n') || '(none)')
console.log('\n=== failed requests ===')
console.log([...new Set(failedRequests)].join('\n') || '(none)')

fs.writeFileSync(`${SHOTS}buyer-log.json`, JSON.stringify({ log, consoleErrors, pageErrors, failedRequests }, null, 2))
await browser.close()
