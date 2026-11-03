// 买家端交互边角：IME 回车、退出时仍在生成、连点、Tab 顺序。
import { createRequire } from 'node:module'
import fs from 'node:fs'

const { chromium } = createRequire(import.meta.url)('playwright')
const BASE = process.env.SHOPPILOT_BASE || 'http://127.0.0.1:8082'
const SHOTS = new URL('./shots/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
const log = []
const note = (stage, text) => { log.push({ stage, text }); console.log(`[${stage}] ${text}`) }

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
const requests = []
page.on('request', (r) => { if (r.url().includes('/chat/')) requests.push(`${r.method()} ${new URL(r.url()).pathname}`) })
page.on('requestfinished', (r) => { if (r.url().includes('/chat/')) requests.push(`DONE ${new URL(r.url()).pathname}`) })
const shot = (n) => page.screenshot({ path: `${SHOTS}${n}.png` }).catch(() => {})

await page.goto(`${BASE}/buyer/?store=T001&guest=0`, { waitUntil: 'load' })
await page.waitForTimeout(1200)
await page.locator('.gate button:has-text("一键体验买家")').click()
await page.waitForTimeout(2000)

// 1) 中文输入法候选确认时按回车——事件带 isComposing
await page.fill('.composer input', '我想问一下')
const beforeMsgs = await page.locator('.thread .msg').count()
await page.evaluate(() => {
  const input = document.querySelector('.composer input')
  input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', isComposing: true, bubbles: true }))
})
await page.waitForTimeout(700)
const afterMsgs = await page.locator('.thread .msg').count()
note('IME 回车', `isComposing=true 的回车是否触发发送：消息数 ${beforeMsgs} → ${afterMsgs}（相等=没拦）`)
if (afterMsgs > beforeMsgs) {
  note('IME 回车', `已把「我想问一下」当成问题发出去：${JSON.stringify((await page.locator('.thread .msg.user .bubble').last().innerText().catch(() => '')).trim())}`)
  await page.waitForFunction(() => !document.querySelector('.composer .stop'), null, { timeout: 60000 }).catch(() => {})
}

// 2) 连点两次发送
await page.fill('.composer input', '连点测试')
const reqBefore = requests.length
await page.locator('.composer button').last().click()
await page.locator('.composer button').last().click({ force: true }).catch(() => {})
await page.waitForTimeout(1500)
note('连点', `1.5s 内 /chat 请求 ${requests.slice(reqBefore).join(' , ') || '(无)'}`)
await page.waitForFunction(() => !document.querySelector('.composer .stop'), null, { timeout: 60000 }).catch(() => {})

// 3) 生成中退出登录：流是否被中止
await page.fill('.composer input', '请详细说明生鲜理赔流程和需要的凭证')
requests.length = 0
await page.locator('.composer button').last().click()
await page.waitForTimeout(900)
const streaming = await page.locator('.composer .stop').isVisible().catch(() => false)
note('退出中', `点击退出前仍在流式=${streaming}`)
await page.locator('.topbar button:has-text("退出")').click()
await page.waitForTimeout(1200)
note('退出中', `退出后页面=${JSON.stringify((await page.locator('.gate').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 60))}`)
await page.waitForTimeout(6000)
note('退出中', `退出后 7s 的 /chat 请求轨迹=${JSON.stringify(requests)}`)
await shot('40-buyer-signout-midstream')

// 4) 重新登录后是否残留半截消息
await page.locator('.gate button:has-text("一键体验买家")').click()
await page.waitForTimeout(2500)
note('重登', `重登后线程消息数=${await page.locator('.thread .msg').count()}；欢迎区=${await page.locator('.welcome').isVisible().catch(() => false)}`)

// 5) 顶栏「退出」的 Tab 位置与无障碍名称
const topbarButtons = await page.locator('.topbar button').evaluateAll((els) => els.map((e) => ({ text: e.innerText.trim(), label: e.getAttribute('aria-label') }))).catch(() => [])
note('无障碍', `顶栏按钮 ${JSON.stringify(topbarButtons)}`)
const iconOnly = await page.evaluate(() => {
  const out = []
  for (const el of document.querySelectorAll('button, a[href]')) {
    const name = (el.innerText || el.getAttribute('aria-label') || el.getAttribute('title') || '').trim()
    if (!name) out.push(el.outerHTML.slice(0, 90))
  }
  return out
})
note('无障碍', `无可读名称的可聚焦元素 ${JSON.stringify(iconOnly)}`)

fs.writeFileSync(`${SHOTS}interaction-log.json`, JSON.stringify({ log, requests }, null, 2))
await browser.close()
