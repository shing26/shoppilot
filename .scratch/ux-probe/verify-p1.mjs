/**
 * round46 票 192-194 的七条 P1 活体验证（对 vite dev server + 真网关代理）。
 *
 * P1-1 游客能看到自己的订单与工单
 * P1-2 刷新后对话与会话 ID 还在；中断的回答标成「刷新时中断」
 * P1-4 输入法组合中的回车不发送
 * P1-5 三个弹层（买家工单抽屉 / 结单框 / 退款框）：焦点进入、Tab 圈内、Esc 关闭并回还焦点
 * P1-6 订单卡只有侧栏一层滚动
 * P1-7 退出登录中止流
 * P1-3 复核结掉复核单、刷新后仍可附稿、工单详情能跳到复核
 *
 * 用法：
 *   $env:NODE_PATH="D:\ShopPilot\.tools\node_modules"; node .scratch/ux-probe/verify-p1.mjs
 * 前置：frontend-buyer（5175）与 frontend-workspace（5174）的 dev server 都指向真网关 8082。
 */
import { createRequire } from 'node:module'
import { readFileSync } from 'node:fs'
const { chromium } = createRequire(import.meta.url)('playwright')

const BUYER = process.env.PROBE_BUYER ?? 'http://localhost:5175'
const WORKSPACE = process.env.PROBE_WORKSPACE ?? 'http://localhost:5174'
const SHOTS = '.scratch/ux-probe/shots'
/** 只跑指定段落（逗号分隔），用来单独复核某一项而不重跑整条链路。 */
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

// ── P1-1 / P1-6：游客能看到订单，且订单卡只有一层滚动 ────────────────────────
await section('guestAndScroll', async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  await page.goto(`${BUYER}/buyer/?store=T001`)
  await page.waitForFunction(STORE_READY, null, { timeout: 30000 })
  await page.waitForSelector('.order-card', { timeout: 30000 })

  const sidebar = await page.evaluate(() => {
    const list = document.querySelector('.order-list')
    const side = document.querySelector('.buyer-side')
    const notice = document.querySelector('.side-activity .notice')?.textContent?.trim() ?? null
    return {
      orderCards: document.querySelectorAll('.order-card').length,
      tabs: [...document.querySelectorAll('.side-tab')].map((tab) => tab.textContent.replace(/\s+/g, ' ').trim()),
      guestNotice: notice,
      listOverflowY: list ? getComputedStyle(list).overflowY : null,
      listMaxHeight: list ? getComputedStyle(list).maxHeight : null,
      sideOverflowY: side ? getComputedStyle(side).overflowY : null,
    }
  })

  const before = await page.evaluate(() => document.querySelector('.buyer-side').scrollTop)
  await page.locator('.order-card').first().click()
  await page.waitForSelector('.order-detail', { timeout: 20000 })
  await page.waitForFunction(() => {
    const detail = document.querySelector('.order-detail')
    return detail && (detail.querySelector('.progress') || detail.querySelector('.err'))
  }, null, { timeout: 20000 }).catch(() => {})
  const expanded = await page.evaluate(() => {
    const card = document.querySelector('.order-card.expanded')
    const detail = card?.querySelector('.order-detail')
    const side = document.querySelector('.buyer-side')
    return {
      expanded: !!card,
      hasProgress: !!detail?.querySelector('.progress'),
      detailScrolls: detail ? getComputedStyle(detail).overflowY : null,
      cardBottom: card ? Math.round(card.getBoundingClientRect().bottom) : null,
      sideBottom: side ? Math.round(side.getBoundingClientRect().bottom) : null,
    }
  })
  await page.screenshot({ path: `${SHOTS}/90-guest-orders.png` })

  // 工单页签：不应再有「游客模式不展示」的提示
  await page.locator('.side-tab', { hasText: '工单' }).click()
  await page.waitForTimeout(400)
  const ticketTab = await page.evaluate(() => ({
    notice: document.querySelector('.side-activity .notice')?.textContent?.trim() ?? null,
    empty: document.querySelector('.side-activity .empty')?.textContent?.trim() ?? null,
    cards: document.querySelectorAll('.ticket-card').length,
  }))

  await page.close()
  return { ...sidebar, scrollBefore: before, ...expanded, ticketTab }
})

// ── P1-2：刷新后对话与会话 ID 还在；待完成的回答标成中断 ──────────────────────
await section('chatPersistence', async () => {
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })
  await page.goto(`${BUYER}/buyer/?store=T001`)
  await page.waitForFunction(STORE_READY, null, { timeout: 30000 })
  await page.waitForSelector('.composer input', { timeout: 15000 })

  await page.fill('.composer input', '七天无理由怎么退')
  await page.locator('.composer .primary').click()
  await page.waitForFunction(() => document.querySelectorAll('.thread .msg').length >= 2, null, { timeout: 20000 })
  await page.waitForFunction(ANSWER_DONE, null, { timeout: 150000 })
  await page.waitForTimeout(400)

  const before = await page.evaluate(() => {
    const chat = JSON.parse(sessionStorage.getItem('shoppilot.buyer.chat') ?? 'null')
    return {
      messages: document.querySelectorAll('.thread .msg').length,
      conversation: chat?.conversation ?? null,
      stored: chat?.messages?.length ?? 0,
      conversationIds: [...new Set((chat?.messages ?? []).map((m) => m.conversationId).filter(Boolean))],
    }
  })

  await page.reload()
  await page.waitForFunction(STORE_READY, null, { timeout: 30000 })
  await page.waitForFunction((n) => document.querySelectorAll('.thread .msg').length >= n, before.messages, { timeout: 20000 })
  const after = await page.evaluate(() => {
    const chat = JSON.parse(sessionStorage.getItem('shoppilot.buyer.chat') ?? 'null')
    return {
      messages: document.querySelectorAll('.thread .msg').length,
      conversation: chat?.conversation ?? null,
      welcome: !!document.querySelector('.welcome'),
    }
  })

  // 中断态：把一条 pending 回答写进存档，刷新后应变成「刷新时中断」+ 可重试
  await page.evaluate((conversation) => {
    const chat = JSON.parse(sessionStorage.getItem('shoppilot.buyer.chat'))
    chat.messages.push({ role: 'assistant', text: '', pending: true, retryQuery: '中断测试', conversationId: conversation })
    sessionStorage.setItem('shoppilot.buyer.chat', JSON.stringify(chat))
  }, before.conversation)
  await page.reload()
  await page.waitForFunction(STORE_READY, null, { timeout: 30000 })
  await page.waitForTimeout(600)
  const interrupted = await page.evaluate(() => {
    const msgs = [...document.querySelectorAll('.thread .msg')]
    const last = msgs[msgs.length - 1]
    return {
      note: last?.querySelector('.fallback')?.textContent?.trim() ?? null,
      retry: !!last?.querySelector('.msg-actions button'),
      text: last?.querySelector('.bubble')?.textContent?.trim() ?? null,
    }
  })
  await page.screenshot({ path: `${SHOTS}/91-chat-restored.png` })
  await page.close()
  return { before, after, interrupted, persisted: before.conversation === after.conversation && after.messages >= before.messages }
})

// ── P1-4 + P1-7：输入法回车不发送；退出中止 SSE ─────────────────────────────
await section('imeAndSignOut', async () => {
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })
  const failed = []
  page.on('requestfailed', (request) => failed.push({ url: request.url(), error: request.failure()?.errorText }))
  await page.goto(`${BUYER}/buyer/?store=T001`)
  await page.waitForFunction(STORE_READY, null, { timeout: 30000 })
  await page.waitForSelector('.composer input', { timeout: 15000 })

  const input = page.locator('.composer input')
  await input.fill('输入法组合测试')
  await input.evaluate((el) => {
    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: true, bubbles: true }))
  })
  await page.waitForTimeout(300)
  const afterComposing = await page.evaluate(() => ({
    messages: document.querySelectorAll('.thread .msg').length,
    value: document.querySelector('.composer input').value,
  }))

  await input.evaluate((el) => {
    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: false, bubbles: true }))
  })
  await page.waitForFunction(() => document.querySelectorAll('.thread .msg').length >= 2, null, { timeout: 15000 })
  await page.waitForFunction(() => !!document.querySelector('.composer .stop'), null, { timeout: 20000 })
  const afterPlain = await page.evaluate(() => document.querySelectorAll('.thread .msg').length)

  // 流式中退出：请求必须被中止，登录门出现，本地存档清空
  await page.locator('.topbar .link', { hasText: '退出' }).click()
  await page.waitForTimeout(1200)
  const signedOut = await page.evaluate(() => ({
    login: !!document.querySelector('.gate'),
    session: sessionStorage.getItem('shoppilot.buyer.session'),
    chat: sessionStorage.getItem('shoppilot.buyer.chat'),
  }))
  await page.screenshot({ path: `${SHOTS}/92-signed-out.png` })
  await page.close()
  return {
    afterComposing,
    afterPlain,
    composingHeld: afterComposing.messages === 0 && afterComposing.value === '输入法组合测试',
    plainSent: afterPlain > 0,
    signedOut,
    abortedStreams: failed.filter((row) => row.url.includes('/chat/stream')),
  }
})

// ── P1-3：点踩 → 复核 → 复核单 RESOLVED → 刷新仍可附稿 → 去复核入口 ─────────
await section('reviewLoop', async () => {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  let feedbackAck = null
  page.on('response', async (response) => {
    if (response.url().includes('/api/v1/support/chat/feedback')) {
      feedbackAck = { status: response.status(), body: await response.text().catch(() => '') }
    }
  })
  await page.goto(`${BUYER}/buyer/?store=T001`)
  await page.waitForFunction(STORE_READY, null, { timeout: 30000 })
  await page.locator('.quick .chip').first().click()
  await page.waitForFunction(() => document.querySelectorAll('.thread .msg').length >= 2, null, { timeout: 20000 })
  await page.waitForFunction(ANSWER_DONE, null, { timeout: 150000 })
  await page.waitForTimeout(400)
  await page.locator('.rate-btn', { hasText: '没用' }).last().click()
  await page.waitForSelector('.rate-reason input', { timeout: 5000 })
  await page.fill('.rate-reason input', 'round46 活体：复核闭环')
  await page.locator('.rate-submit').click()
  await page.waitForFunction(() => document.querySelector('.rate-done')?.textContent?.includes('已记下'), null, { timeout: 20000 })
  await page.waitForTimeout(400)
  const ack = feedbackAck ? JSON.parse(feedbackAck.body) : null
  if (!ack?.feedbackId) throw new Error('点踩没有拿到 feedbackId，复核队列无法定位')
  await page.close()

  const ws = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  await ws.goto(`${WORKSPACE}/workspace/`)
  await ws.waitForSelector('.login-card', { timeout: 30000 })
  await ws.locator('.login-card .primary', { hasText: '一键体验坐席' }).click()
  await ws.waitForSelector('.side-nav', { timeout: 30000 })
  await ws.locator('.nav-item', { hasText: '知识飞轮' }).click()
  await ws.waitForSelector('.kb', { timeout: 20000 })
  await ws.waitForFunction((id) => [...document.querySelectorAll('.review-row .mono')].some((el) => el.textContent.trim() === id), ack?.feedbackId, { timeout: 30000 })

  const row = ws.locator('.review-row', { has: ws.locator('.mono', { hasText: ack.feedbackId }) })
  await row.locator('button').click()
  await ws.waitForFunction((id) => [...document.querySelectorAll('.draft-panel .row .mono')].some((el) => el.textContent.trim() === id), ack.feedbackId, { timeout: 20000 })
  const afterReview = await ws.evaluate((id) => ({
    stillPending: [...document.querySelectorAll('.review-row .mono')].some((el) => el.textContent.trim() === id),
    reviewed: [...document.querySelectorAll('.draft-panel .row .mono')].some((el) => el.textContent.trim() === id),
    note: document.querySelector('.note')?.textContent?.trim() ?? null,
  }), ack.feedbackId)

  // 复核单状态：从页面里带令牌直接读工单队列
  const ticket = await ws.evaluate(async (feedbackId) => {
    const session = JSON.parse(sessionStorage.getItem('shoppilot.session'))
    const response = await fetch('/api/v1/support/ops/tickets', { headers: { Authorization: `Bearer ${session.token}` } })
    if (!response.ok) return { status: response.status }
    const rows = await response.json()
    const match = rows.find((row) => String(row.payload ?? '').includes(feedbackId))
    return match ? { id: match.id, status: match.status, source: match.source, payload: match.payload } : null
  }, ack.feedbackId)

  // 刷新后仍在「已复核 · 可附稿」
  await ws.reload()
  await ws.waitForSelector('.side-nav', { timeout: 30000 })
  await ws.locator('.nav-item', { hasText: '知识飞轮' }).click()
  await ws.waitForSelector('.draft-panel', { timeout: 20000 })
  await ws.waitForFunction((id) => [...document.querySelectorAll('.draft-panel .row .mono')].some((el) => el.textContent.trim() === id), ack.feedbackId, { timeout: 20000 })
  const survivedRefresh = await ws.evaluate((id) => ({
    reviewed: [...document.querySelectorAll('.draft-panel .row .mono')].some((el) => el.textContent.trim() === id),
    pending: [...document.querySelectorAll('.review-row .mono')].some((el) => el.textContent.trim() === id),
  }), ack.feedbackId)
  await ws.screenshot({ path: `${SHOTS}/93-knowledge-reviewed.png`, fullPage: true })

  // 工单队列：复核单 → 去复核这条反馈
  await ws.locator('.nav-item', { hasText: '工单队列' }).click()
  await ws.waitForSelector('.ticket-card', { timeout: 20000 })
  // 队列卡片用 shortId（长号只留尾部），所以按尾 12 位匹配。
  const cardText = ticket?.id ? ticket.id.slice(-12) : '__missing__'
  const card = ws.locator('.ticket-card', { hasText: cardText })
  await card.first().click()
  await ws.waitForSelector('.detail-pane .detail-actions', { timeout: 10000 })
  const jump = ws.locator('.detail-actions button', { hasText: '去复核这条反馈' })
  const jumpVisible = await jump.count()
  if (jumpVisible) {
    await jump.click()
    await ws.waitForSelector('.kb', { timeout: 10000 })
  }
  const jumpNote = await ws.evaluate(() => document.querySelector('.kb .note')?.textContent?.trim() ?? null)

  await ws.close()
  return { ack, ticket: ticket ? { id: ticket.id, status: ticket.status, source: ticket.source } : null, afterReview, survivedRefresh, jumpVisible: !!jumpVisible, jumpNote }
})

// ── P1-5（买家）：工单抽屉的 Esc / 焦点陷阱 / 焦点回还 ──────────────────────
// 放在 reviewLoop 之后：那一节点踩会为 C001 开一张复核单，抽屉才有单可点。
await section('ticketDrawerKeyboard', async () => {
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })
  await page.goto(`${BUYER}/buyer/?store=T001`)
  await page.waitForFunction(STORE_READY, null, { timeout: 30000 })
  await page.locator('.side-tab', { hasText: '工单' }).click()
  await page.waitForSelector('.ticket-card', { timeout: 20000 })
  const cards = await page.locator('.ticket-card').count()
  await page.locator('.ticket-card').first().click()
  await page.waitForSelector('.drawer', { timeout: 10000 })
  await page.waitForTimeout(300)

  const opened = await page.evaluate(() => ({
    inside: !!document.querySelector('.drawer')?.contains(document.activeElement),
  }))

  // Tab 次数 > 可聚焦元素数：焦点必须仍在层内
  let escaped = false
  for (let i = 0; i < 12; i += 1) {
    await page.keyboard.press('Tab')
    const inside = await page.evaluate(() => !!document.querySelector('.drawer')?.contains(document.activeElement))
    if (!inside) escaped = true
  }
  await page.keyboard.press('Escape')
  await page.waitForTimeout(300)
  const closed = await page.evaluate(() => ({
    drawer: !!document.querySelector('.drawer'),
    focusReturned: !!document.activeElement?.closest('.ticket-card'),
  }))

  // 遮罩点击仍可关闭
  await page.locator('.ticket-card').first().click()
  await page.waitForSelector('.drawer', { timeout: 10000 })
  await page.locator('.drawer-mask').click({ position: { x: 8, y: 8 } })
  await page.waitForTimeout(300)
  const maskClosed = await page.evaluate(() => !document.querySelector('.drawer'))

  await page.close()
  return { cards, opened, tabEscaped: escaped, closed, maskClosed }
})

// ── P1-5（工作台）：结单框与退款框的焦点陷阱与 Esc ──────────────────────────
await section('workspaceDialogs', async () => {
  // 现造一张 OPEN 工单与一笔待审退款，让这一段不依赖库里的历史数据。
  const token = readEnv('SHOPPILOT_INTERNAL_TOKEN')
  if (!token) throw new Error('.env 里没有 SHOPPILOT_INTERNAL_TOKEN，无法准备结单框')
  const headers = { 'Content-Type': 'application/json', 'X-Internal-Token': token, 'X-Tenant-Id': 'T001', 'X-Customer-Id': 'C001' }
  const created = await fetch('http://127.0.0.1:8092/api/tickets', {
    method: 'POST',
    headers,
    body: JSON.stringify({
      source: 'DEGRADE',
      customerId: 'C001',
      reason: 'HANDOFF',
      userQuery: 'round46 活体：弹层键盘检查',
      transcript: 'live probe',
      priority: 'normal',
    }),
  })
  if (!created.ok) throw new Error(`建单失败 HTTP ${created.status}`)
  const ticket = await created.json()

  const orders = await (await fetch('http://127.0.0.1:8091/api/orders/mine', { headers })).json()
  const refundable = orders.find(
    (order) => ['PAID', 'SHIPPED', 'DELIVERED'].includes(order.status) && !order.refundReview,
  )
  let refundStatus = 'skipped: 没有可退订单'
  if (refundable) {
    const applied = await fetch('http://127.0.0.1:8091/api/tools/applyRefund', {
      method: 'POST',
      headers: { ...headers, 'Idempotency-Token': `probe-refund-${Date.now()}` },
      body: JSON.stringify({ orderNo: refundable.orderNo, reason: 'round46 活体：退款弹层键盘检查' }),
    })
    refundStatus = `HTTP ${applied.status}`
  }

  const ws = await browser.newPage({ viewport: { width: 1440, height: 900 } })
  await ws.goto(`${WORKSPACE}/workspace/`)
  await ws.waitForSelector('.login-card', { timeout: 30000 })
  await ws.locator('.login-card .primary', { hasText: '一键体验坐席' }).click()
  await ws.waitForSelector('.side-nav', { timeout: 30000 })

  // 结单框：点「处理完成」→ 焦点进入层内 → Tab/Shift+Tab 不跑出去 → Esc 关闭并回还焦点
  await ws.locator('.nav-item', { hasText: '工单队列' }).click()
  await ws.waitForSelector('.ticket-card', { timeout: 20000 })
  const card = ws.locator('.ticket-card', { hasText: ticket.id.slice(-12) }).first()
  await card.click()
  await ws.waitForSelector('.detail-pane .detail-actions', { timeout: 10000 })
  const resolveButton = ws.locator('.detail-actions button', { hasText: '处理完成' })
  await resolveButton.click()
  await ws.waitForSelector('.dialog', { timeout: 10000 })
  await ws.waitForTimeout(300)

  const resolveOpened = await ws.evaluate(() => ({
    inside: !!document.querySelector('.dialog')?.contains(document.activeElement),
    onNote: document.activeElement?.tagName === 'TEXTAREA',
    title: document.querySelector('.dialog h2')?.textContent?.trim() ?? null,
  }))
  let resolveEscaped = false
  for (let i = 0; i < 6; i += 1) {
    await ws.keyboard.press('Tab')
    const inside = await ws.evaluate(() => !!document.querySelector('.dialog')?.contains(document.activeElement))
    if (!inside) resolveEscaped = true
  }
  for (let i = 0; i < 6; i += 1) {
    await ws.keyboard.press('Shift+Tab')
    const inside = await ws.evaluate(() => !!document.querySelector('.dialog')?.contains(document.activeElement))
    if (!inside) resolveEscaped = true
  }
  await ws.keyboard.press('Escape')
  await ws.waitForTimeout(300)
  const resolveClosed = await ws.evaluate(() => ({
    dialog: !!document.querySelector('.dialog'),
    focusReturned: document.activeElement?.textContent?.includes('处理完成') ?? false,
  }))

  // 退款框：待审退款 → 放行 → 初始焦点在备注框（不是确认放行）→ Esc 关闭
  await ws.locator('.nav-item', { hasText: '工单队列' }).click()
  await ws.locator('.kpi.refund').click()
  await ws.waitForSelector('.refund-card', { timeout: 20000 })
  const approve = ws.locator('.refund-card .primary').first()
  await approve.click()
  await ws.waitForSelector('.dialog', { timeout: 10000 })
  await ws.waitForTimeout(300)
  const refundOpened = await ws.evaluate(() => ({
    inside: !!document.querySelector('.dialog')?.contains(document.activeElement),
    onNote: document.activeElement?.tagName === 'TEXTAREA',
    title: document.querySelector('.dialog h2')?.textContent?.trim() ?? null,
    text: document.querySelector('.dialog')?.textContent?.replace(/\s+/g, ' ').trim().slice(0, 80) ?? null,
  }))
  let refundEscaped = false
  for (let i = 0; i < 5; i += 1) {
    await ws.keyboard.press('Tab')
    const inside = await ws.evaluate(() => !!document.querySelector('.dialog')?.contains(document.activeElement))
    if (!inside) refundEscaped = true
  }
  await ws.keyboard.press('Escape')
  await ws.waitForTimeout(300)
  const refundClosed = await ws.evaluate(() => ({
    dialog: !!document.querySelector('.dialog'),
    focusReturned: document.activeElement?.textContent?.includes('放行') ?? false,
  }))
  await ws.screenshot({ path: `${SHOTS}/94-workspace-dialogs.png` })

  await ws.close()
  return {
    setup: { ticket: ticket.id, refundFor: refundable?.orderNo ?? null, refundStatus },
    resolve: { opened: resolveOpened, tabEscaped: resolveEscaped, closed: resolveClosed },
    refund: { opened: refundOpened, tabEscaped: refundEscaped, closed: refundClosed },
  }
})

await browser.close()
console.log(JSON.stringify(out, null, 2))

/** 从仓库根的 `.env` 取内部调用凭证；探针只在本机跑，不回显它。 */
function readEnv(name) {
  if (process.env[name]) return process.env[name]
  try {
    const text = readFileSync('.env', 'utf8')
    const match = text.match(new RegExp(`^${name}=(.*)$`, 'm'))
    return match ? match[1].trim() : ''
  } catch {
    return ''
  }
}
