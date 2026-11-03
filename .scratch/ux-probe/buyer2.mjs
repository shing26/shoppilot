// 买家端（登录态）走查：深度卡、工单抽屉、键盘与窄屏。
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
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()) })
page.on('pageerror', (e) => pageErrors.push(String(e)))
const shot = (n) => page.screenshot({ path: `${SHOTS}${n}.png` }).catch(() => {})

await page.goto(`${BASE}/buyer/?store=T001&guest=0`, { waitUntil: 'load' })
await page.waitForTimeout(1500)
note('登录门', `gate=${await page.locator('.gate').isVisible().catch(() => false)} 提示=${JSON.stringify((await page.locator('.gate').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 200))}`)
await shot('10-buyer-gate')

await page.locator('.gate button:has-text("一键体验买家")').click()
await page.waitForTimeout(2500)
note('体验登录', `顶栏=${(await page.locator('.topbar').innerText().catch(() => '')).replace(/\s+/g, ' ')}`)
note('体验登录', `欢迎区=${await page.locator('.welcome').isVisible().catch(() => false)}`)
await page.waitForTimeout(1500)

// 侧栏订单
await page.locator('.side-tab', { hasText: '订单' }).click().catch(() => {})
await page.waitForTimeout(1200)
const orders = await page.locator('.order-card').count()
note('订单', `订单卡 ${orders} 张`)
const orderTexts = await page.locator('.order-card').allInnerTexts().catch(() => [])
note('订单', JSON.stringify(orderTexts.map((t) => t.replace(/\s+/g, ' '))))

if (orders > 0) {
  const t = Date.now()
  await page.locator('.order-card').first().click()
  await page.waitForSelector('.order-detail', { timeout: 8000 }).catch(() => {})
  note('订单深度卡', `展开耗时 ${Date.now() - t}ms`)
  const detail = await page.locator('.order-detail').innerText().catch(() => '')
  note('订单深度卡', JSON.stringify(detail.replace(/\s+/g, ' ').slice(0, 700)))
  const progress = await page.locator('.progress li').evaluateAll((els) => els.map((e) => e.innerText.replace(/\s+/g, ' '))).catch(() => [])
  note('订单深度卡', `推进条=${JSON.stringify(progress)}`)
  const nodes = await page.locator('.timeline li').allInnerTexts().catch(() => [])
  note('订单深度卡', `物流节点 ${nodes.length} 条 ${JSON.stringify(nodes.map((n) => n.replace(/\s+/g, ' ')))}`)
  await shot('11-buyer-order-expanded')

  // 复制运单号
  const copyBtn = page.locator('.tracking button:has-text("复制")')
  if (await copyBtn.count()) {
    await copyBtn.click().catch(() => {})
    await page.waitForTimeout(300)
    note('订单深度卡', `复制反馈=${JSON.stringify(await page.locator('.tracking .fine').innerText().catch(() => '(无)'))}`)
  } else {
    note('订单深度卡', '没有运单号可复制（未发货单）')
  }

  // 展开第二张单（切换）
  if (orders > 1) {
    await page.locator('.order-card').nth(1).click()
    await page.waitForTimeout(1500)
    const second = await page.locator('.order-detail').innerText().catch(() => '')
    note('订单深度卡', `切到第二张=${JSON.stringify(second.replace(/\s+/g, ' ').slice(0, 150))}`)
    note('订单深度卡', `同时展开张数=${await page.locator('.order-detail').count()}`)
  }
}

// 转人工 → 工单抽屉
await page.fill('.composer input', '我不想跟机器说了，转人工')
await page.locator('.composer button').last().click()
await page.waitForFunction(() => !document.querySelector('.composer .stop'), null, { timeout: 60000 }).catch(() => {})
await page.waitForTimeout(1500)
await page.locator('.side-tab', { hasText: '工单' }).click().catch(() => {})
await page.waitForTimeout(800)
const tickets = await page.locator('.ticket-card').count()
note('工单', `工单卡 ${tickets} 张 ${JSON.stringify((await page.locator('.ticket-card').allInnerTexts().catch(() => [])).map((t) => t.replace(/\s+/g, ' ')))}`)
await shot('12-buyer-tickets')

if (tickets > 0) {
  const focusBefore = await page.evaluate(() => document.activeElement?.tagName)
  await page.locator('.ticket-card').first().click()
  await page.waitForTimeout(800)
  note('工单抽屉', `打开后可见=${await page.locator('.drawer').isVisible().catch(() => false)}；打开前焦点=${focusBefore}；打开后焦点=${await page.evaluate(() => `${document.activeElement?.tagName}.${(document.activeElement?.className || '').toString().split(' ')[0]}`)}`)
  note('工单抽屉', JSON.stringify((await page.locator('.drawer').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 400)))
  await shot('13-buyer-ticket-drawer')
  // ESC 是否能关
  await page.keyboard.press('Escape')
  await page.waitForTimeout(400)
  note('工单抽屉', `按 Esc 后仍打开=${await page.locator('.drawer').isVisible().catch(() => false)}`)
  // Tab 是否会跑出抽屉
  const trail = []
  for (let i = 0; i < 6; i += 1) {
    await page.keyboard.press('Tab')
    trail.push(await page.evaluate(() => {
      const el = document.activeElement
      const inside = !!(el && el.closest && el.closest('.drawer'))
      return `${inside ? 'IN' : 'OUT'}:${el?.tagName.toLowerCase()}|${(el?.textContent || '').trim().slice(0, 14)}`
    }))
  }
  note('工单抽屉', `Tab ${JSON.stringify(trail)}`)
  await page.locator('.drawer button:has-text("关闭")').click().catch(() => {})
  await page.waitForTimeout(300)
}

// 长文本溢出：粘一段超长问题
const longQ = '这是一段很长的买家提问'.repeat(30)
await page.fill('.composer input', longQ)
const inputValue = await page.inputValue('.composer input')
note('长输入', `输入框接受 ${inputValue.length} 字（未截断=${inputValue.length === longQ.length}）`)
await page.locator('.composer button').last().click()
await page.waitForFunction(() => !document.querySelector('.composer .stop'), null, { timeout: 90000 }).catch(() => {})
await page.waitForTimeout(500)
const userBubble = await page.locator('.thread .msg.user .bubble').last().evaluate((el) => ({
  scrollW: el.scrollWidth, clientW: el.clientWidth,
})).catch(() => null)
note('长输入', `用户气泡尺寸 ${JSON.stringify(userBubble)}`)
await shot('14-buyer-long-input')

// 停止按钮
await page.fill('.composer input', '介绍一下你们的售后政策，越详细越好')
await page.locator('.composer button').last().click()
await page.waitForTimeout(600)
const stopVisible = await page.locator('.composer .stop').isVisible().catch(() => false)
note('停止', `流式中出现停止按钮=${stopVisible}`)
if (stopVisible) {
  await page.locator('.composer .stop').click()
  await page.waitForTimeout(800)
  note('停止', `点停止后=${JSON.stringify((await page.locator('.thread .msg').last().innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 200))}`)
  note('停止', `重试按钮=${await page.locator('.thread .msg').last().locator('button:has-text("重试")').count()}`)
}
await shot('15-buyer-stop')

// 窄屏下的侧栏与抽屉
await page.setViewportSize({ width: 390, height: 844 })
await page.waitForTimeout(600)
await page.locator('.side-tab', { hasText: '工单' }).click().catch(() => {})
await page.waitForTimeout(400)
if (await page.locator('.ticket-card').count()) {
  await page.locator('.ticket-card').first().click()
  await page.waitForTimeout(600)
  const drawerBox = await page.locator('.drawer').boundingBox().catch(() => null)
  note('窄屏抽屉', JSON.stringify(drawerBox))
  await shot('16-buyer-mobile-drawer')
}
const narrow = await page.evaluate(() => ({ scrollW: document.documentElement.scrollWidth, innerW: window.innerWidth }))
note('窄屏', JSON.stringify(narrow))

console.log('\n=== console errors ===\n' + (consoleErrors.join('\n') || '(none)'))
console.log('=== page errors ===\n' + (pageErrors.join('\n') || '(none)'))
fs.writeFileSync(`${SHOTS}buyer2-log.json`, JSON.stringify({ log, consoleErrors, pageErrors }, null, 2))
await browser.close()
