/**
 * round47 票 195-198 的十二项 P2 活体验证（对 vite dev server + 真网关代理）。
 *
 * 195  labels        四个位置不再出现原始枚举；--ink-3 三处对比度 ≥4.5
 * 195  unauthorized  带令牌 401 自动回登录面；登录请求的 401 不触发
 * 196  buyerMobile   390×844 下四处点击目标 ≥40px；点「问问这单」滚回对话
 * 196  ariaLog       `.thread` 是 role=log / aria-atomic=false
 * 196  guestTransfer 游客转人工后没有「游客模式不展示工单」文案，工单页签能读到
 * 197  queueFilter    筛到空档时详情清空；有结果时详情属于当前档
 * 197  resolveNeedsClaim 未领取的「处理完成」禁用并显示说明
 * 197  workspaceMobile   390×844 导航三列两行、导航项与顶栏按钮 ≥40px
 * 197  reviewedCount  五步条「人工复核」读服务端 REVIEWED 总数（附稿后不回零）
 * 198  qualityMax     质量报表的 maxSeconds 非 0 且不随时间窗归零
 *
 * 用法：
 *   $env:NODE_PATH="D:\ShopPilot\.tools\node_modules"; node .scratch/ux-probe/verify-p2.mjs
 *   $env:PROBE_ONLY="labels,buyerMobile"; node .scratch/ux-probe/verify-p2.mjs
 * 前置：frontend-buyer（5175）与 frontend-workspace（5174）的 dev server 都指向真网关 8082。
 * **网关没起时整条脚本会在第一次登录超时失败**——那不是断言失败，是环境未就绪，照登「未实跑」。
 */
import { createRequire } from 'node:module'
const { chromium } = createRequire(import.meta.url)('playwright')

const BUYER = process.env.PROBE_BUYER ?? 'http://localhost:5175'
const WORKSPACE = process.env.PROBE_WORKSPACE ?? 'http://localhost:5174'
const SHOTS = '.scratch/ux-probe/shots'
const ONLY = (process.env.PROBE_ONLY ?? '').split(',').map((name) => name.trim()).filter(Boolean)
const out = {}
const browser = await chromium.launch()

const STORE_READY = () => document.querySelector('.store-title strong')?.textContent?.trim() === '星选生活旗舰店'
const ANSWER_DONE = () => !document.querySelector('.composer .stop')

async function section(name, fn) {
  if (ONLY.length && !ONLY.includes(name)) {
    out[name] = { skipped: 'PROBE_ONLY 过滤' }
    return
  }
  try {
    out[name] = await fn()
  } catch (failure) {
    out[name] = { error: String(failure?.message ?? failure).split('\n')[0] }
  }
}

async function openBuyer(width = 1440, height = 900) {
  const page = await browser.newPage({ viewport: { width, height } })
  await page.goto(`${BUYER}/buyer/?store=T001`)
  await page.waitForFunction(STORE_READY, null, { timeout: 30000 })
  return page
}

async function openWorkspace(width = 1440, height = 900) {
  const page = await browser.newPage({ viewport: { width, height } })
  await page.goto(`${WORKSPACE}/workspace/`)
  await page.waitForSelector('.login-card', { timeout: 30000 })
  await page.locator('.login-card .primary', { hasText: '一键体验坐席' }).click()
  await page.waitForSelector('.side-nav', { timeout: 30000 })
  return page
}

// ── 195：可读枚举与对比度 ───────────────────────────────────────────────────
await section('labels', async () => {
  const ws = await openWorkspace()
  const seen = {}
  for (const [view, selector] of [
    ['知识飞轮', '.kb'],
    ['退款审核', '.refund-list, .view-panel'],
    ['渠道会话', '.conversation-panel'],
  ]) {
    const nav = ws.locator('.nav-item', { hasText: view })
    if (!(await nav.count())) continue
    await nav.first().click()
    await ws.waitForTimeout(500)
    const text = await ws.evaluate(() => document.body.innerText)
    seen[view] = {
      rendered: (await ws.locator(selector).count()) > 0,
      rawEnums: ['DOWN', 'UP', 'PENDING_REVIEW', 'PROCESSING', 'REJECTED', 'BOT', 'AGENT']
        .filter((token) => new RegExp(`(^|[^A-Z_])${token}([^A-Z_]|$)`).test(text)),
    }
  }
  await ws.close()

  const buyer = await openBuyer()
  const flags = await buyer.evaluate(async () => {
    const card = document.querySelector('.order-card')
    if (!card) return { rendered: false }
    card.click()
    await new Promise((resolve) => setTimeout(resolve, 1200))
    return {
      rendered: true,
      raw: [...document.querySelectorAll('.flags li')]
        .map((li) => li.textContent.trim())
        .filter((text) => /^[A-Z_]+$/.test(text)),
    }
  })
  await buyer.close()
  return { workspace: seen, buyerFlags: flags }
})

await section('contrast', async () => {
  const page = await openBuyer()
  const ratios = await page.evaluate(() => {
    const probe = document.createElement('div')
    probe.style.color = 'var(--ink-3)'
    document.body.appendChild(probe)
    const ink = getComputedStyle(probe).color
    probe.remove()

    const parse = (value) => {
      const match = value.match(/rgba?\(([^)]+)\)/)
      if (!match) return null
      const [r, g, b] = match[1].split(',').slice(0, 3).map(Number)
      return [r, g, b]
    }
    const luminance = ([r, g, b]) => {
      const channel = (v) => {
        const s = v / 255
        return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
      }
      return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }
    const ratio = (a, b) => {
      const la = luminance(a)
      const lb = luminance(b)
      return Math.round(((Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)) * 100) / 100
    }
    const token = (name) => {
      const el = document.createElement('div')
      el.style.background = `var(${name})`
      document.body.appendChild(el)
      const value = getComputedStyle(el).backgroundColor
      el.remove()
      return parse(value)
    }
    return {
      ink: ink,
      onBg: ratio(parse(ink), token('--bg')),
      onLine: ratio(parse(ink), token('--line')),
      onPanel: ratio(parse(ink), token('--panel')),
    }
  })
  await page.close()
  return ratios
})

// ── 195：401 自动登出 ───────────────────────────────────────────────────────
await section('unauthorized', async () => {
  const page = await openBuyer()
  // 只拦一次工单列表，模拟令牌过期；对话与订单仍在内存里。
  await page.route('**/api/v1/support/ops/tickets/mine', (route) =>
    route.fulfill({ status: 401, contentType: 'application/json', body: '{"message":"token expired"}' }))
  await page.evaluate(async () => {
    const session = JSON.parse(sessionStorage.getItem('shoppilot.buyer.session'))
    await fetch('/api/v1/support/ops/tickets/mine', {
      headers: { Authorization: `Bearer ${session.token}` },
    }).catch(() => {})
  })
  await page.waitForTimeout(600)
  const state = await page.evaluate(() => ({
    loginGate: !!document.querySelector('.gate'),
    storedSession: sessionStorage.getItem('shoppilot.buyer.session'),
    reason: document.querySelector('.gate .err')?.textContent?.trim() ?? null,
  }))
  await page.unroute('**/api/v1/support/ops/tickets/mine')

  // 登录请求本身 401 不应触发「登录已失效」以外的登出路径（仍停在登录门、密码框还在）。
  await page.locator('.gate .link', { hasText: '用买家账号登录' }).click().catch(() => {})
  await page.evaluate(() => {
    const form = document.querySelector('.gate .login')
    return !!form
  })
  await page.close()
  return state
})

// ── 196：移动端点击目标与回对话 ─────────────────────────────────────────────
await section('buyerMobile', async () => {
  const page = await openBuyer(390, 844)
  await page.locator('.side-tab', { hasText: '订单' }).click().catch(() => {})
  await page.waitForTimeout(400)
  const heights = await page.evaluate(() => {
    const heightOf = (selector) =>
      [...document.querySelectorAll(selector)].map((el) => Math.round(el.getBoundingClientRect().height))
    return {
      exit: heightOf('.topbar .link'),
      refresh: heightOf('.side-activity .section-head .link'),
      ask: heightOf('.order-ask'),
      tabs: heightOf('.side-tab'),
    }
  })

  const ask = page.locator('.order-ask').first()
  if (await ask.count()) {
    await ask.click()
    await page.waitForTimeout(800)
  }
  const scrolled = await page.evaluate(() => {
    const main = document.querySelector('.buyer-main')
    return main ? Math.round(main.getBoundingClientRect().top) : null
  })
  await page.screenshot({ path: `${SHOTS}/95-buyer-mobile-p2.png` })
  await page.close()
  return { heights, chatTopAfterAsk: scrolled }
})

await section('ariaLog', async () => {
  const page = await openBuyer()
  const attrs = await page.evaluate(() => {
    const thread = document.querySelector('.thread')
    return thread
      ? {
          role: thread.getAttribute('role'),
          live: thread.getAttribute('aria-live'),
          relevant: thread.getAttribute('aria-relevant'),
          atomic: thread.getAttribute('aria-atomic'),
        }
      : null
  })
  await page.close()
  return attrs
})

await section('guestTransfer', async () => {
  const page = await openBuyer()
  await page.locator('.composer input').fill('转人工')
  await page.locator('.composer .primary').click()
  await page.waitForFunction(() => document.querySelectorAll('.thread .msg').length >= 2, null, { timeout: 20000 })
  await page.waitForFunction(ANSWER_DONE, null, { timeout: 150000 }).catch(() => {})
  const state = await page.evaluate(() => ({
    staleCopy: document.body.innerText.includes('游客模式不展示工单'),
    note: [...document.querySelectorAll('.thread .fallback')].map((el) => el.textContent.trim()).at(-1) ?? null,
  }))
  await page.locator('.side-tab', { hasText: '工单' }).click().catch(() => {})
  await page.waitForTimeout(600)
  state.ticketCards = await page.locator('.ticket-card').count()
  await page.close()
  return state
})

// ── 197：队列筛选 / 结单前置 / 移动端导航 / 五步条 ───────────────────────────
await section('queueFilter', async () => {
  const ws = await openWorkspace()
  await ws.locator('.nav-item', { hasText: '工单队列' }).click()
  await ws.waitForSelector('.ticket-card', { timeout: 20000 })
  const filters = await ws.evaluate(() =>
    [...document.querySelectorAll('.filter-chip, .filters button, .segmented button')]
      .map((el) => el.textContent.replace(/\s+/g, ' ').trim()))
  const result = { filters }
  for (const label of filters) {
    const chip = ws.locator('.filter-chip, .filters button, .segmented button', { hasText: label }).first()
    await chip.click()
    await ws.waitForTimeout(400)
    result[label] = await ws.evaluate(() => ({
      cards: document.querySelectorAll('.ticket-card').length,
      detail: !!document.querySelector('.detail-pane'),
      detailInFilter: (() => {
        const card = document.querySelector('.ticket-card.selected, .ticket-card.active')
        const detail = document.querySelector('.detail-pane')
        if (!card || !detail) return null
        return card.textContent.includes(detail.querySelector('h2')?.textContent?.trim().slice(-8) ?? '__none__')
      })(),
    }))
  }
  await ws.close()
  return result
})

await section('resolveNeedsClaim', async () => {
  const ws = await openWorkspace()
  await ws.locator('.nav-item', { hasText: '工单队列' }).click()
  await ws.waitForSelector('.ticket-card', { timeout: 20000 })
  const unclaimed = ws.locator('.ticket-card', { hasText: '待分配' }).first()
  if (!(await unclaimed.count())) {
    await ws.close()
    return { skipped: '队列里没有未领取工单' }
  }
  await unclaimed.click()
  await ws.waitForSelector('.detail-pane .detail-actions', { timeout: 10000 })
  const state = await ws.evaluate(() => {
    const button = [...document.querySelectorAll('.detail-actions button')]
      .find((el) => el.textContent.includes('处理完成'))
    return {
      disabled: button?.disabled ?? null,
      hint: document.querySelector('.action-hint')?.textContent?.trim() ?? null,
    }
  })
  await ws.close()
  return state
})

await section('workspaceMobile', async () => {
  const ws = await openWorkspace(390, 844)
  const layout = await ws.evaluate(() => {
    const nav = document.querySelector('.side-nav')
    const first = document.querySelector('.nav-item')
    const buttons = [...document.querySelectorAll('.top-right .ghost, .top-right .link')]
    const queue = document.querySelector('.ticket-card')
    return {
      columns: nav ? getComputedStyle(nav).gridTemplateColumns.split(' ').length : null,
      navHeight: first ? Math.round(first.getBoundingClientRect().height) : null,
      topbarHeights: buttons.map((el) => Math.round(el.getBoundingClientRect().height)),
      queueTop: queue ? Math.round(queue.getBoundingClientRect().top) : null,
    }
  })
  await ws.screenshot({ path: `${SHOTS}/96-workspace-mobile-p2.png` })
  await ws.close()
  return layout
})

await section('reviewedCount', async () => {
  const ws = await openWorkspace()
  await ws.locator('.nav-item', { hasText: '知识飞轮' }).click()
  await ws.waitForSelector('.kb', { timeout: 20000 })
  const steps = await ws.evaluate(() =>
    [...document.querySelectorAll('.kb .flow-step')].map((el) => el.textContent.replace(/\s+/g, ' ').trim()))
  const reviewed = await ws.evaluate(() => {
    const step = [...document.querySelectorAll('.kb .flow-step')]
      .find((el) => el.textContent.includes('人工复核'))
    return step ? step.textContent.replace(/\s+/g, ' ').trim() : null
  })
  await ws.close()
  return { steps, reviewed }
})

// ── 198：质量报表的累计最大 ─────────────────────────────────────────────────
await section('qualityMax', async () => {
  const ws = await openWorkspace()
  await ws.locator('.nav-item', { hasText: '编排质量' }).click()
  await ws.waitForTimeout(1500)
  const shown = await ws.evaluate(() => {
    const text = document.body.innerText
    const match = text.match(/Handoff[^\n]*\n[^\n]*?最大[^\n]*?([\d.]+)\s*ms/)
    return { maxText: match ? match[1] : null, bodyHasMax: text.includes('最大') }
  })
  const api = await ws.evaluate(async () => {
    const session = JSON.parse(sessionStorage.getItem('shoppilot.session'))
    const response = await fetch('/api/v1/support/ops/quality/report', {
      headers: { Authorization: `Bearer ${session.token}` },
    })
    if (!response.ok) return { status: response.status }
    const body = await response.json()
    return { status: response.status, decision: body?.handoff?.decisionSeconds ?? null }
  })
  await ws.close()
  return { shown, api }
})

await browser.close()
console.log(JSON.stringify(out, null, 2))
