// 坐席工作台真实用户走查：登录 → 队列 → 结单 → 退款 → 知识飞轮 → 其它视图。
import { createRequire } from 'node:module'
import fs from 'node:fs'

const { chromium } = createRequire(import.meta.url)('playwright')
const BASE = process.env.SHOPPILOT_BASE || 'http://127.0.0.1:8082'
const SHOTS = new URL('./shots/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
fs.mkdirSync(SHOTS, { recursive: true })
const log = []
const note = (stage, text) => { log.push({ stage, text }); console.log(`[${stage}] ${text}`) }

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
const consoleErrors = []
const pageErrors = []
const failed = []
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()) })
page.on('pageerror', (e) => pageErrors.push(String(e)))
page.on('response', (r) => { if (r.status() >= 400) failed.push(`HTTP ${r.status()} ${r.request().method()} ${new URL(r.url()).pathname}`) })
const shot = (n) => page.screenshot({ path: `${SHOTS}${n}.png` }).catch(() => {})

await page.goto(`${BASE}/workspace/`, { waitUntil: 'load' })
await page.waitForTimeout(1500)
note('登录', `登录卡可见=${await page.locator('.login-card').isVisible().catch(() => false)}`)
await shot('20-ws-login')

await page.locator('.login-card button:has-text("一键体验坐席")').click()
await page.waitForTimeout(2200)
note('登录', `身份=${JSON.stringify((await page.locator('.identity').innerText().catch(() => '')).replace(/\s+/g, ' '))}`)
note('登录', `侧栏=${JSON.stringify((await page.locator('.side-nav').innerText().catch(() => '')).replace(/\s+/g, ' '))}`)
await shot('21-ws-queue')

// KPI 与筛选
const kpis = await page.locator('.kpi').allInnerTexts().catch(() => [])
note('队列', `KPI ${JSON.stringify(kpis.map((k) => k.replace(/\s+/g, ' ')))}`)
const cards = await page.locator('.ticket-card').count()
note('队列', `工单卡 ${cards} 张`)
const firstCard = (await page.locator('.ticket-card').first().innerText().catch(() => '')).replace(/\s+/g, ' ')
note('队列', `第一张 ${JSON.stringify(firstCard)}`)

// 筛选到「已超时」，看详情是否还停在别的单上
await page.locator('.kpi.danger').click().catch(() => {})
await page.waitForTimeout(400)
note('筛选', `已超时档：卡片=${await page.locator('.ticket-card').count()} 详情=${await page.locator('.detail-pane .detail-head h2').innerText().catch(() => '(无详情)')}`)
await page.locator('.kpi').first().click().catch(() => {})
await page.waitForTimeout(400)

// 选第一张 → 领取
if (cards > 0) {
  await page.locator('.ticket-card').first().click()
  await page.waitForTimeout(300)
  const detailId = await page.locator('.detail-head h2').innerText().catch(() => '')
  const lifecycle = (await page.locator('.detail-pane').innerText().catch(() => '')).replace(/\s+/g, ' ')
  note('详情', `${detailId} — ${JSON.stringify(lifecycle.slice(0, 420))}`)
  const claimDisabled = await page.locator('.detail-actions button:has-text("领取")').isDisabled().catch(() => null)
  const resolveDisabled = await page.locator('.detail-actions button:has-text("处理完成")').isDisabled().catch(() => null)
  note('详情', `未领取时：领取 disabled=${claimDisabled}，处理完成 disabled=${resolveDisabled}`)

  // 先点「处理完成」而不领取——看看产品允不允许跳过领取
  await page.locator('.detail-actions button:has-text("处理完成")').click({ noWaitAfter: true }).catch(() => {})
  await page.waitForTimeout(400)
  const dialogShown = await page.locator('.dialog').isVisible().catch(() => false)
  note('结单', `未领取直接点处理完成 → 弹框=${dialogShown}`)
  if (dialogShown) {
    await page.locator('.dialog button:has-text("确认结单")').click()
    await page.waitForTimeout(300)
    note('结单', `空结论提交 → 错误=${JSON.stringify(await page.locator('.dialog .err').innerText().catch(() => '(无)'))}`)
    await shot('22-ws-resolve-dialog')
    // 弹框焦点
    note('结单', `弹框内焦点=${await page.evaluate(() => `${document.activeElement?.tagName}.${(document.activeElement?.className || '').toString().split(' ')[0]}`)}`)
    // Esc
    await page.keyboard.press('Escape')
    await page.waitForTimeout(300)
    note('结单', `Esc 后弹框仍在=${await page.locator('.dialog').isVisible().catch(() => false)}`)
    await page.locator('.dialog button:has-text("取消")').click().catch(() => {})
  }

  // 领取
  await page.locator('.detail-actions button:has-text("领取")').click({ noWaitAfter: true }).catch(() => {})
  await page.waitForTimeout(1800)
  note('领取', `详情=${JSON.stringify((await page.locator('.detail-pane').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 260))}`)
  await shot('23-ws-claimed')

  // 结单（填结论）
  await page.locator('.detail-actions button:has-text("处理完成")').click({ noWaitAfter: true }).catch(() => {})
  await page.waitForTimeout(400)
  if (await page.locator('.dialog').isVisible().catch(() => false)) {
    await page.fill('.dialog textarea', '走查写入：已核实，按实付金额退款，3 个工作日到账。')
    await page.locator('.dialog button:has-text("确认结单")').click()
    await page.waitForTimeout(1800)
    note('结单', `提交后 note=${JSON.stringify(await page.locator('.note').first().innerText().catch(() => '(无)'))}`)
    note('结单', `详情状态=${JSON.stringify((await page.locator('.detail-pane').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 220))}`)
    await shot('24-ws-resolved')
  }
}

// 退款视图
await page.locator('.nav-item:has-text("退款审核")').click()
await page.waitForTimeout(1200)
note('退款', `卡片 ${await page.locator('.refund-card').count()}；正文=${JSON.stringify((await page.locator('.view-panel').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 500))}`)
await shot('25-ws-refunds')

// 知识飞轮
await page.locator('.nav-item:has-text("知识飞轮")').click()
await page.waitForTimeout(1500)
const kbText = (await page.locator('.kb').innerText().catch(() => '')).replace(/\s+/g, ' ')
note('知识', `首屏=${JSON.stringify(kbText.slice(0, 900))}`)
note('知识', `五步=${JSON.stringify(await page.locator('.kb-flow .flow-step').allInnerTexts().catch(() => []))}`)
note('知识', `待复核=${await page.locator('.review-row').count()} 已复核=${await page.locator('.draft-panel .row').count()} 修订=${await page.locator('.revision-card').count()}`)
await shot('26-ws-knowledge')

// 复核第一条（如果有）
if (await page.locator('.review-row').count()) {
  const rowId = await page.locator('.review-row .mono').first().innerText().catch(() => '')
  await page.locator('.review-row button:has-text("标为已复核")').first().click()
  await page.waitForTimeout(1200)
  note('知识', `复核 ${rowId} 后：表单可见=${await page.locator('.draft-form').isVisible().catch(() => false)}；笔记=${JSON.stringify(await page.locator('.note').first().innerText().catch(() => '(无)'))}`)
  const missing = await page.locator('.draft-form .err').innerText().catch(() => '(未提示)')
  note('知识', `空表单提交前提示=${JSON.stringify(missing)}`)
  await page.fill('.draft-form input[type="text"]', '走查：七天无理由起算点')
  await page.fill('.draft-form textarea', '签收次日 0 点起算，满 7 天当日 24 点截止。')
  await shot('27-ws-draft-form')
  await page.locator('.draft-form button[type="submit"]').click()
  await page.waitForTimeout(1500)
  note('知识', `附稿后：DRAFT 卡=${await page.locator('.revision-card.draft').count()}；笔记=${JSON.stringify(await page.locator('.note').first().innerText().catch(() => '(无)'))}`)
  if (await page.locator('.revision-card.draft').count()) {
    await page.locator('.revision-card.draft button:has-text("发布")').first().click()
    await page.waitForTimeout(1500)
    note('知识', `发布后：卡片类=${await page.locator('.revision-card').first().getAttribute('class').catch(() => '')}；提示=${JSON.stringify(await page.locator('.ingest-note').first().innerText().catch(() => '(无)'))}`)
  }
}
await shot('28-ws-knowledge-after')

// 渠道会话 / 渠道连接 / 编排质量
for (const [label, cls] of [['渠道会话', '.conversation-panel'], ['渠道连接', '.channel-panel'], ['编排质量', '.quality-panel']]) {
  await page.locator(`.nav-item:has-text("${label}")`).click()
  await page.waitForTimeout(1400)
  const text = (await page.locator('.work-area').innerText().catch(() => '')).replace(/\s+/g, ' ')
  note(label, JSON.stringify(text.slice(0, 600)))
}
await shot('29-ws-quality')

// 刷新：视图是否保留
await page.reload({ waitUntil: 'load' })
await page.waitForTimeout(2200)
note('刷新', `刷新后视图=${await page.locator('.nav-item.active').innerText().catch(() => '(无)')}`)

// 窄屏
await page.setViewportSize({ width: 390, height: 844 })
await page.waitForTimeout(700)
const narrow = await page.evaluate(() => ({ scrollW: document.documentElement.scrollWidth, innerW: window.innerWidth }))
note('窄屏', JSON.stringify(narrow))
const overflowing = await page.evaluate(() => {
  const out = []
  for (const el of document.querySelectorAll('body *')) {
    const r = el.getBoundingClientRect()
    if (r.width > 0 && r.right > window.innerWidth + 2) out.push(`${el.tagName.toLowerCase()}.${(el.className || '').toString().split(' ')[0]}|r=${Math.round(r.right)}`)
  }
  return out.slice(0, 10)
})
note('窄屏', `右溢 ${JSON.stringify(overflowing)}`)
await shot('30-ws-mobile')

console.log('\n=== console errors ===\n' + (consoleErrors.join('\n') || '(none)'))
console.log('=== page errors ===\n' + (pageErrors.join('\n') || '(none)'))
console.log('=== failed ===\n' + ([...new Set(failed)].join('\n') || '(none)'))
fs.writeFileSync(`${SHOTS}workspace-log.json`, JSON.stringify({ log, consoleErrors, pageErrors, failed }, null, 2))
await browser.close()
