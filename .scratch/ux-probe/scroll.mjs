import { createRequire } from 'node:module'
const { chromium } = createRequire(import.meta.url)('playwright')
const BASE='http://127.0.0.1:8082'
const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 390, height: 844 } })
await page.goto(`${BASE}/buyer/?store=T001&guest=0`, { waitUntil: 'load' })
await page.waitForTimeout(1000)
await page.locator('.gate button:has-text("一键体验买家")').click()
await page.waitForTimeout(2000)

for (const q of ['你们店是卖什么的？','七天无理由怎么退','生鲜坏了怎么赔']) {
  await page.fill('.composer input', q)
  await page.locator('.composer button').last().click()
  await page.waitForFunction(() => !document.querySelector('.composer .stop'), null, { timeout: 90000 }).catch(()=>{})
  await page.waitForTimeout(400)
}

const st = await page.evaluate(() => {
  const t = document.querySelector('.thread')
  const last = document.querySelector('.thread .msg:last-child')
  const r = last?.getBoundingClientRect()
  return {
    scrollTop: Math.round(t.scrollTop), clientH: t.clientHeight, scrollH: t.scrollHeight,
    atBottom: t.scrollTop + t.clientHeight >= t.scrollHeight - 4,
    lastTop: r && Math.round(r.top), lastBottom: r && Math.round(r.bottom), viewH: window.innerHeight,
    lastVisible: r ? (r.top < window.innerHeight && r.bottom > 0) : null,
  }
})
console.log('多轮后线程滚动状态:', JSON.stringify(st))

// 移动端「问问这单」后视口在哪
await page.locator('.side-tab', { hasText: '订单' }).click()
await page.waitForTimeout(800)
const askBtn = page.locator('.order-card .order-ask').first()
if (await askBtn.count()) {
  const before = await page.evaluate(() => Math.round(window.scrollY))
  await askBtn.click()
  await page.waitForTimeout(1500)
  const after = await page.evaluate(() => Math.round(window.scrollY))
  const lastVisible = await page.evaluate(() => {
    const t = document.querySelector('.thread .msg:last-child')?.getBoundingClientRect()
    return t ? (t.top < window.innerHeight && t.bottom > 0) : null
  })
  console.log(`点「问问这单」前后 scrollY ${before} → ${after}；最新消息在视口内=${lastVisible}`)
}
await page.screenshot({ path: '.scratch/ux-probe/shots/70-mobile-ask-order.png', fullPage: false })
await browser.close()
