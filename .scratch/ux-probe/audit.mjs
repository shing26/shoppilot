import { createRequire } from 'node:module'
const { chromium } = createRequire(import.meta.url)('playwright')
const BASE='http://127.0.0.1:8082'
const browser = await chromium.launch()

const lum = (rgb) => { const [r,g,b]=rgb.map(v=>{v/=255;return v<=0.03928?v/12.92:((v+0.055)/1.055)**2.4}); return 0.2126*r+0.7152*g+0.0722*b }
const parse = (s) => { const m = s.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)/); return m?[+m[1],+m[2],+m[3]]:null }
const ratio = (a,b) => { const la=lum(a), lb=lum(b); return ((Math.max(la,lb)+0.05)/(Math.min(la,lb)+0.05)) }

const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
await page.goto(`${BASE}/buyer/?store=T001&guest=0`, { waitUntil: 'load' })
await page.waitForTimeout(1200)
await page.locator('.gate button:has-text("一键体验买家")').click()
await page.waitForTimeout(2200)

const contrast = await page.evaluate(() => {
  const out = []
  const seen = new Set()
  for (const el of document.querySelectorAll('body *')) {
    if (!el.childNodes.length) continue
    const hasText = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim())
    if (!hasText) continue
    const cs = getComputedStyle(el)
    const bg = (() => { let p = el; while (p) { const c = getComputedStyle(p).backgroundColor; if (c && !/rgba\(0, 0, 0, 0\)/.test(c)) return c; p = p.parentElement } return 'rgb(255,255,255)' })()
    const key = `${cs.color}|${bg}|${cs.fontSize}`
    if (seen.has(key)) continue
    seen.add(key)
    out.push({ cls: (el.className || '').toString().split(' ')[0] || el.tagName.toLowerCase(), color: cs.color, bg, size: cs.fontSize, weight: cs.fontWeight, sample: el.textContent.trim().slice(0, 18) })
  }
  return out.slice(0, 60)
})
const rows = contrast.map((r) => {
  const f = r.color.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?/)
  const b = r.bg.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?/)
  if (!f || !b) return null
  const fg = [+f[1],+f[2],+f[3]], bg=[+b[1],+b[2],+b[3]]
  return { ...r, ratio: Math.round(ratio(fg,bg)*100)/100 }
}).filter(Boolean).sort((a,b)=>a.ratio-b.ratio)
console.log('最低对比度若干：')
for (const r of rows.slice(0, 12)) console.log(` ${r.ratio}:1  ${r.size}  ${r.cls}  "${r.sample}"  fg=${r.color} bg=${r.bg}`)

// 移动端点击目标
await page.setViewportSize({ width: 390, height: 844 })
await page.waitForTimeout(500)
const taps = await page.evaluate(() => {
  const out = []
  for (const el of document.querySelectorAll('button, a[href], input, select, textarea')) {
    const r = el.getBoundingClientRect()
    if (r.width === 0 || r.height === 0) continue
    if (r.height < 40 || r.width < 40) out.push({ tag: el.tagName.toLowerCase(), txt: (el.innerText || el.placeholder || '').trim().slice(0, 16), w: Math.round(r.width), h: Math.round(r.height), cls: (el.className||'').toString().split(' ')[0] })
  }
  return out.slice(0, 20)
})
console.log('\n移动端小于 40px 的点击目标：', JSON.stringify(taps, null, 1))
await browser.close()
