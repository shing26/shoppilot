import { createRequire } from 'node:module'
const { chromium } = createRequire(import.meta.url)('playwright')
const BASE='http://127.0.0.1:8082'
const SHOTS = new URL('./shots/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')
const browser = await chromium.launch()

// 场景 A：直接打开 T004（新上下文）
const a = await browser.newContext()
const p1 = await a.newPage()
await p1.goto(`${BASE}/buyer/?store=T004`, { waitUntil: 'load' })
await p1.waitForTimeout(2500)
console.log('A 直开 T004 店卡=', JSON.stringify((await p1.locator('.store-card').innerText().catch(()=>'')).replace(/\s+/g,' ').slice(0,60)))
await p1.screenshot({ path: `${SHOTS}60-t004-direct.png` })
await a.close()

// 场景 B：同一标签页 首页 → T001 → 首页 → T004
const b = await browser.newContext()
const p2 = await b.newPage()
await p2.goto(`${BASE}/buyer/?store=T001`, { waitUntil: 'load' })
await p2.waitForTimeout(2000)
await p2.goto(`${BASE}/buyer/?store=T004`, { waitUntil: 'load' })
await p2.waitForTimeout(2500)
console.log('B T001→T004 店卡=', JSON.stringify((await p2.locator('.store-card').innerText().catch(()=>'')).replace(/\s+/g,' ').slice(0,60)))
console.log('B 快捷问题=', JSON.stringify(await p2.locator('.quick .chip').allInnerTexts().catch(()=>[])))
console.log('B 侧栏订单=', await p2.locator('.order-card').count(), '工单=', await p2.locator('.ticket-card').count())
await p2.screenshot({ path: `${SHOTS}61-t004-after-t001.png` })
// 用这个「T004 页面」问一句酒店问题
await p2.locator('.quick .chip').first().click()
await p2.waitForFunction(() => !document.querySelector('.composer .stop'), null, { timeout: 90000 }).catch(()=>{})
console.log('B 回答=', JSON.stringify((await p2.locator('.thread .msg').last().innerText().catch(()=>'')).replace(/\s+/g,' ').slice(0,180)))
await p2.screenshot({ path: `${SHOTS}62-t004-wrong-answer.png` })
await b.close()
await browser.close()
