/**
 * round45 票 191 三项 P0 的活体验证（对 vite dev server + 真网关代理）。
 *
 * 1) 同标签页换店不再沿用上一家的会话；
 * 2) 回答下面能点「没用」，POST 到 /chat/feedback 并在页面上留下回执；
 * 3) 新回答把线程滚到底。
 */
import { createRequire } from 'node:module'
const { chromium } = createRequire(import.meta.url)('playwright')

const BASE = process.env.PROBE_BASE ?? 'http://127.0.0.1:5175'
const out = {}
const browser = await chromium.launch()

// ── A. 跨店会话 ────────────────────────────────────────────────────────────
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const snap = () => page.evaluate(() => {
  const raw = sessionStorage.getItem('shoppilot.buyer.session')
  return {
    search: location.search,
    store: document.querySelector('.store-title strong')?.textContent?.trim() ?? null,
    sessionTenant: raw ? JSON.parse(raw).tenantId : null,
    chips: [...document.querySelectorAll('.quick .chip')].map((b) => b.textContent.trim()),
  }
})
await page.goto(`${BASE}/buyer/?store=T001`)
await page.waitForFunction(() => document.querySelector('.store-title strong')?.textContent?.trim() === '星选生活旗舰店', null, { timeout: 25000 })
out.storeBefore = await snap()

await page.goto(`${BASE}/buyer/?store=T004`)
await page.waitForFunction(() => document.querySelector('.store-title strong')?.textContent?.trim() === '云栖酒店', null, { timeout: 25000 })
await page.waitForTimeout(400)
out.storeAfter = await snap()

// ── B. 点踩入口 ────────────────────────────────────────────────────────────
let feedbackCall = null
page.on('response', async (res) => {
  if (res.url().includes('/api/v1/support/chat/feedback')) {
    feedbackCall = { status: res.status(), body: await res.text().catch(() => '') }
  }
})
await page.locator('.quick .chip').first().click()
await page.waitForFunction(() => document.querySelectorAll('.thread .msg').length >= 2, null, { timeout: 150000 })
await page.waitForFunction(() => !document.querySelector('.composer .stop'), null, { timeout: 150000 })
await page.waitForTimeout(400)
out.rateButtons = await page.locator('.rate-btn').allTextContents()

await page.locator('.rate-btn', { hasText: '没用' }).last().click()
await page.waitForSelector('.rate-reason input', { timeout: 5000 })
await page.fill('.rate-reason input', '回答没有给出退款时限')
await page.locator('.rate-submit').click()
await page.waitForFunction(() => document.querySelector('.rate-done')?.textContent?.includes('已记下'), null, { timeout: 20000 })
await page.waitForFunction(() => !document.querySelector('.rate-submit'), null, { timeout: 5000 }).catch(() => {})
await page.waitForTimeout(300)
out.feedbackReceipt = await page.evaluate(() => document.querySelector('.rate-done')?.textContent?.trim())
out.feedbackCall = feedbackCall
out.rateButtonsAfter = await page.locator('.rate-btn').count()
await page.screenshot({ path: '.scratch/ux-probe/shots/80-rate-down.png' })

// ── C. 自动滚动 ────────────────────────────────────────────────────────────
const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } })
await mobile.goto(`${BASE}/buyer/?store=T001`)
await mobile.waitForFunction(() => document.querySelector('.store-title strong')?.textContent?.trim() === '星选生活旗舰店', null, { timeout: 25000 })
const questions = ['你们店是卖什么的？', '七天无理由怎么退', '生鲜坏了怎么赔']
for (let i = 0; i < questions.length; i += 1) {
  await mobile.fill('.composer input', questions[i])
  await mobile.locator('.composer .primary').click()
  await mobile.waitForFunction((n) => document.querySelectorAll('.thread .msg').length >= n, 2 * (i + 1), { timeout: 150000 })
  await mobile.waitForFunction(() => !document.querySelector('.composer .stop'), null, { timeout: 150000 })
  await mobile.waitForTimeout(250)
}
out.scroll = await mobile.evaluate(() => {
  const thread = document.querySelector('.thread')
  const last = thread.querySelector('.msg:last-child').getBoundingClientRect()
  return {
    scrollTop: Math.round(thread.scrollTop),
    clientHeight: thread.clientHeight,
    scrollHeight: thread.scrollHeight,
    atBottom: thread.scrollTop + thread.clientHeight >= thread.scrollHeight - 4,
    lastVisible: last.top < window.innerHeight && last.bottom > 0,
  }
})
await mobile.screenshot({ path: '.scratch/ux-probe/shots/81-mobile-scroll.png' })

await browser.close()
console.log(JSON.stringify(out, null, 2))
