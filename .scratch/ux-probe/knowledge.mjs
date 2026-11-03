// 知识飞轮有数据时的走查 + REVIEW 队列工单的入口检查。
import { createRequire } from 'node:module'
import fs from 'node:fs'

const { chromium } = createRequire(import.meta.url)('playwright')
const BASE = process.env.SHOPPILOT_BASE || 'http://127.0.0.1:8082'
const SHOTS = new URL('./shots/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
const log = []
const note = (stage, text) => { log.push({ stage, text }); console.log(`[${stage}] ${text}`) }

// 先由「买家」点一次踩（API 层，因为买家页根本没有点踩入口——这本身就是要证明的事）
const buyer = await (await fetch(`${BASE}/auth/mock-token`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ tenantId: 'T001', customerId: 'C001' }),
})).json()
const conv = `ux-probe-${Date.now()}`
const fb = await (await fetch(`${BASE}/api/v1/support/chat/feedback`, {
  method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${buyer.token}` },
  body: JSON.stringify({ conversationId: conv, verdict: 'DOWN', reason: '走查：答案没写清退货起算点' }),
})).json()
note('造数据', `feedback=${JSON.stringify(fb)}`)

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
const consoleErrors = []
const pageErrors = []
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()) })
page.on('pageerror', (e) => pageErrors.push(String(e)))
const shot = (n) => page.screenshot({ path: `${SHOTS}${n}.png` }).catch(() => {})

await page.goto(`${BASE}/workspace/`, { waitUntil: 'load' })
await page.waitForTimeout(1200)
await page.locator('.login-card button:has-text("一键体验坐席")').click()
await page.waitForTimeout(2200)

// 先看 REVIEW 队列里的复核工单长什么样
await page.locator('.toolbar select').selectOption('REVIEW')
await page.waitForTimeout(1200)
const reviewCards = await page.locator('.ticket-card').count()
note('复核工单', `REVIEW 队列 ${reviewCards} 张 ${JSON.stringify((await page.locator('.ticket-card').allInnerTexts().catch(() => [])).map((t) => t.replace(/\s+/g, ' ')))}`)
if (reviewCards) {
  await page.locator('.ticket-card').first().click()
  await page.waitForTimeout(500)
  note('复核工单', `详情=${JSON.stringify((await page.locator('.detail-pane').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 500))}`)
  const actions = await page.locator('.detail-actions button').allInnerTexts().catch(() => [])
  note('复核工单', `可用动作=${JSON.stringify(actions)} — 没有「去复核」入口`)
  await shot('31-ws-review-ticket')
}

// 回全部队列，进知识飞轮
await page.locator('.toolbar select').selectOption('ALL')
await page.waitForTimeout(1000)
await page.locator('.nav-item:has-text("知识飞轮")').click()
await page.waitForTimeout(1500)
note('知识', `待复核=${await page.locator('.review-row').count()}`)
const rowText = (await page.locator('.review-row').first().innerText().catch(() => '')).replace(/\s+/g, ' ')
note('知识', `行内容=${JSON.stringify(rowText)}`)
await shot('32-ws-kb-with-data')

await page.locator('.review-row button:has-text("标为已复核")').first().click()
await page.waitForTimeout(1400)
note('知识', `复核后表单可见=${await page.locator('.draft-form').isVisible().catch(() => false)}`)
note('知识', `刚打开表单就显示的提示=${JSON.stringify((await page.locator('.draft-form .err').allInnerTexts().catch(() => [])).join(' | '))}`)
note('知识', `提交按钮 disabled=${await page.locator('.draft-form button[type="submit"]').isDisabled().catch(() => null)}`)
await shot('33-ws-kb-draft-open')

// 只填标题、不填正文
await page.fill('.draft-form input[type="text"]', '退货起算点')
await page.waitForTimeout(200)
note('知识', `仅填标题时提示=${JSON.stringify((await page.locator('.draft-form .err').allInnerTexts().catch(() => [])).join(' | '))}`)

// 刷新页面：已复核的那条还在不在
await page.reload({ waitUntil: 'load' })
await page.waitForTimeout(2000)
await page.locator('.nav-item:has-text("知识飞轮")').click()
await page.waitForTimeout(1500)
note('刷新', `刷新后：待复核=${await page.locator('.review-row').count()} 已复核=${await page.locator('.draft-panel .row').count()} 修订=${await page.locator('.revision-card').count()}`)
await shot('34-ws-kb-after-refresh')

console.log('\n=== console errors ===\n' + (consoleErrors.join('\n') || '(none)'))
console.log('=== page errors ===\n' + (pageErrors.join('\n') || '(none)'))
fs.writeFileSync(`${SHOTS}kb-log.json`, JSON.stringify({ log, consoleErrors, pageErrors, feedback: fb }, null, 2))
await browser.close()
