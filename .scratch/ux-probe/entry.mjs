// 入口页与调试台：真实用户能不能从首页走到产品深处。
import { createRequire } from 'node:module'
import fs from 'node:fs'

const { chromium } = createRequire(import.meta.url)('playwright')
const BASE = process.env.SHOPPILOT_BASE || 'http://127.0.0.1:8082'
const SHOTS = new URL('./shots/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
const log = []
const note = (stage, text) => { log.push({ stage, text }); console.log(`[${stage}] ${text}`) }

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
const consoleErrors = []
const pageErrors = []
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()) })
page.on('pageerror', (e) => pageErrors.push(String(e)))
const shot = (n) => page.screenshot({ path: `${SHOTS}${n}.png`, fullPage: true }).catch(() => {})

await page.goto(`${BASE}/`, { waitUntil: 'load' })
await page.waitForTimeout(1200)
note('首页', JSON.stringify((await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 700)))
const links = await page.locator('a').evaluateAll((els) => els.map((e) => ({ text: e.innerText.trim().replace(/\s+/g, ' '), href: e.getAttribute('href'), target: e.getAttribute('target') })))
note('首页链接', JSON.stringify(links))
await shot('50-landing')

// 从首页点第一家店
await page.locator('a[href="/buyer/?store=T001"]').first().click()
await page.waitForTimeout(2500)
note('首页→买家', `URL=${page.url()}；身份=${JSON.stringify((await page.locator('.topbar').innerText().catch(() => '')).replace(/\s+/g, ' '))}`)

// 打开 T004 酒店店，看看快捷问题与语义
await page.goto(`${BASE}/buyer/?store=T004`, { waitUntil: 'load' })
await page.waitForTimeout(2500)
note('T004', `店名=${JSON.stringify(await page.locator('.store-card').innerText().catch(() => '(无)'))}`)
note('T004', `快捷问题=${JSON.stringify(await page.locator('.quick .chip').allInnerTexts().catch(() => []))}`)
const t004Chips = await page.locator('.quick .chip').allInnerTexts().catch(() => [])
if (t004Chips.length) {
  await page.locator('.quick .chip').first().click()
  await page.waitForFunction(() => !document.querySelector('.composer .stop'), null, { timeout: 90000 }).catch(() => {})
  note('T004', `问「${t004Chips[0]}」→ ${JSON.stringify((await page.locator('.thread .msg').last().innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 260))}`)
}
await shot('51-buyer-t004')

// 调试台
await page.goto(`${BASE}/console.html`, { waitUntil: 'load' })
await page.waitForTimeout(3000)
const buttons = await page.locator('button').evaluateAll((els) => els.map((e) => `${e.innerText.trim().replace(/\s+/g, ' ')}${e.disabled ? '(disabled)' : ''}`))
note('调试台', `按钮 ${JSON.stringify(buttons)}`)
const fields = await page.locator('input').evaluateAll((els) => els.map((e) => ({ id: e.id, ph: e.placeholder, value: e.value, type: e.type })))
note('调试台', `输入框 ${JSON.stringify(fields)}`)
await shot('52-console')

console.log('\n=== console errors ===\n' + (consoleErrors.join('\n') || '(none)'))
console.log('=== page errors ===\n' + (pageErrors.join('\n') || '(none)'))
fs.writeFileSync(`${SHOTS}entry-log.json`, JSON.stringify({ log, consoleErrors, pageErrors }, null, 2))
await browser.close()
