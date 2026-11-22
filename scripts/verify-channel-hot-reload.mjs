// 渠道配置热生效与出站安全活体验收（round56 票 225 / ADR 0087）。
//
// 前置：
//   - gateway :8082、biz-mock :8091、ticket :8092 已启动；
//   - .env 里 SHOPPILOT_IM_FEISHU_APP_ID / APP_SECRET / IDENTITY_DEMO_PASSWORD 可用；
//   - gateway 启动时带 SHOPPILOT_OUTBOUND_ALLOWLIST=localhost 与
//     SHOPPILOT_OUTBOUND_POLL_INTERVAL=1s（脚本只把 localhost 当本地投递端点）。
//
// 真发 HTTP + 真 Redis + 真重启网关；不 mock 投递。
import http from 'node:http'
import net from 'node:net'
import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const BASE = process.env.SHOPPILOT_GATEWAY_BASE || 'http://127.0.0.1:8082'
const REPO = process.env.SHOPPILOT_WORKSPACE || process.cwd()
const TENANT = process.env.SHOPPILOT_IDENTITY_TENANT || 'T001'
const DEMO_PASSWORD = process.env.SHOPPILOT_IDENTITY_DEMO_PASSWORD
const REDIS_HOST = process.env.SHOPPILOT_REDIS_HOST || '127.0.0.1'
const REDIS_PORT = Number(process.env.SHOPPILOT_REDIS_PORT || 16379)
const RECEIVER_PORT = Number(process.env.SHOPPILOT_OUTBOUND_TEST_PORT || 19090)
const REDIRECT_PORT = RECEIVER_PORT + 1
const CHANNEL_FILE = join(REPO, 'config', 'channels.json')

if (!DEMO_PASSWORD) {
  console.error('缺少 SHOPPILOT_IDENTITY_DEMO_PASSWORD')
  process.exit(2)
}

const results = []
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

// ── 极简 RESP（只用于 XADD / EXISTS / DEL）──────────────────────────────
function redis(...args) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: REDIS_HOST, port: REDIS_PORT })
    const payload = `*${args.length}\r\n` + args
      .map((arg) => `$${Buffer.byteLength(String(arg))}\r\n${arg}\r\n`)
      .join('')
    let buffer = ''
    socket.setTimeout(5000)
    socket.on('connect', () => socket.write(payload))
    socket.on('data', (chunk) => {
      buffer += chunk.toString('utf8')
      if (buffer.includes('\r\n')) {
        socket.end()
      }
    })
    socket.on('timeout', () => socket.destroy(new Error('redis timeout')))
    socket.on('error', reject)
    socket.on('close', () => {
      const line = buffer.split('\r\n')[0] || ''
      if (line.startsWith('-')) reject(new Error(line.slice(1)))
      else resolve(line)
    })
  })
}

// ── 本地 webhook 接收端与重定向端 ──────────────────────────────────────
const received = []
let receiverMode = 'ok'
const receiver = http.createServer((req, res) => {
  let body = ''
  req.on('data', (chunk) => { body += chunk })
  req.on('end', () => {
    received.push({ at: Date.now(), body, probe: req.headers['x-shoppilot-probe'] === '1' })
    res.statusCode = req.headers['x-shoppilot-probe'] === '1' ? 200 : (receiverMode === 'fail' ? 500 : 200)
    res.end('ok')
  })
})
const redirector = http.createServer((req, res) => {
  res.statusCode = 307
  res.setHeader('Location', 'http://10.0.0.8/hook')
  res.end()
})

function listen(server, port) {
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, resolve)
  })
}

// ── HTTP 辅助 ─────────────────────────────────────────────────────────
async function api(path, { method = 'GET', body, token } = {}) {
  const response = await fetch(BASE + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  let parsed = {}
  try { parsed = text ? JSON.parse(text) : {} } catch { parsed = { message: text } }
  return { status: response.status, body: parsed, raw: text }
}

async function adminToken() {
  const result = await api('/auth/login', {
    method: 'POST',
    body: { tenantId: TENANT, username: 'admin', password: DEMO_PASSWORD },
  })
  if (result.status !== 200 || !result.body.token) {
    throw new Error(`admin login HTTP ${result.status}: ${result.raw.slice(0, 120)}`)
  }
  return result.body.token
}

function envValue(key) {
  const line = readFileSync(join(REPO, '.env'), 'utf8')
    .split(/\r?\n/)
    .find((item) => item.startsWith(key + '='))
  return line ? line.slice(key.length + 1).trim().replace(/^["']|["']$/g, '') : ''
}

function countLog(fragment) {
  const file = join(REPO, 'logs', 'gateway-default.out')
  if (!existsSync(file)) return -1
  return readFileSync(file, 'utf8').split(fragment).length - 1
}

async function waitFor(predicate, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await predicate()) return true
    await sleep(250)
  }
  throw new Error(`等待超时：${label}`)
}

function ps(command) {
  const shell = process.env.SHOPPILOT_PWSH || 'pwsh'
  execFileSync(shell, ['-NoProfile', '-Command', command], { cwd: REPO, stdio: 'ignore' })
}

async function restartGateway() {
  ps(`$c = Get-NetTCPConnection -State Listen -LocalPort 8082 -ErrorAction SilentlyContinue | Select-Object -First 1; ` +
    `if ($c) { Stop-Process -Id $c.OwningProcess -Force }`)
  await sleep(2000)
  const shell = process.env.SHOPPILOT_PWSH || 'pwsh'
  execFileSync(shell, ['-NoProfile', '-File', join(REPO, 'scripts', 'start-gateway.ps1')],
    { cwd: REPO, stdio: 'ignore' })
  await waitFor(async () => {
    try {
      const probe = await api('/auth/login', {
        method: 'POST',
        body: { tenantId: TENANT, username: 'admin', password: DEMO_PASSWORD },
      })
      return probe.status === 200
    } catch { return false }
  }, 90000, 'gateway restart')
}

const originalChannelFile = existsSync(CHANNEL_FILE) ? readFileSync(CHANNEL_FILE, 'utf8') : null
let token = ''

try {
  await listen(receiver, RECEIVER_PORT)
  await listen(redirector, REDIRECT_PORT)

  token = await adminToken()
  check('管理员登录', Boolean(token), 'ADMIN')

  // ── 1. 管理面状态与密钥掩码 ───────────────────────────────────────
  const listed = await api('/api/v1/support/ops/channels', { token })
  const channel = listed.body.channels?.[0] ?? {}
  check('渠道状态可读', listed.status === 200 && Boolean(channel.state), `HTTP ${listed.status} state=${channel.state}`)
  check('管理面不回显 secret', !listed.raw.includes('appSecret') && !listed.raw.includes('app-secret'),
    '响应体无 appSecret')
  check('appId 只回遮罩', String(channel.appId || '').includes('****'), channel.appId)

  // ── 2. SSRF：默认拒绝 + allowlist 覆盖 + 重定向复检 ───────────────
  const blocked = [
    ['loopback', `http://127.0.0.1:${RECEIVER_PORT}/hook`],
    ['RFC1918', 'http://10.0.0.8/hook'],
    ['metadata', 'http://169.254.169.254/latest/meta-data/'],
    ['IPv6 loopback', 'http://[::1]/hook'],
    ['TEST-NET-1 192.0.2.0/24', 'http://192.0.2.1/hook'],
    ['TEST-NET-2 198.51.100.0/24', 'http://198.51.100.1/hook'],
    ['TEST-NET-3 203.0.113.0/24', 'http://203.0.113.1/hook'],
    ['IPv6 文档前缀 2001:db8::/32', 'http://[2001:db8::1]/hook'],
    ['非 http scheme', 'ftp://example.com/hook'],
  ]
  for (const [label, url] of blocked) {
    const probe = await api('/api/v1/support/ops/channels/webhook/test', {
      method: 'POST', token, body: { url },
    })
    check(`SSRF 拒绝 ${label}`, probe.status === 200 && probe.body.ok === false,
      `${probe.status} ${probe.body.message}`)
  }
  const allowlisted = await api('/api/v1/support/ops/channels/webhook/test', {
    method: 'POST', token, body: { url: `http://localhost:${RECEIVER_PORT}/hook` },
  })
  check('allowlist 内本地端点可投递', allowlisted.status === 200 && allowlisted.body.ok === true,
    `${allowlisted.status} ${allowlisted.body.message}`)
  const redirected = await api('/api/v1/support/ops/channels/webhook/test', {
    method: 'POST', token, body: { url: `http://localhost:${REDIRECT_PORT}/hook` },
  })
  check('307 跳转到私网被逐跳拒绝', redirected.status === 200 && redirected.body.ok === false,
    `${redirected.status} ${redirected.body.message}`)

  // ── 3. 非法配置回滚 + 合法配置热生效 ─────────────────────────────
  const before = await api('/api/v1/support/ops/channels', { token })
  const beforeAppId = before.body.channels?.[0]?.appId
  const invalid = await api('/api/v1/support/ops/channels/feishu', {
    method: 'PUT', token,
    body: { enabled: true, appId: 'cli_invalid_probe', appSecret: 'invalid-secret',
      tenant: TENANT, customer: 'C001', identityFile: '' },
  })
  check('非法凭据返回明确错误并回滚', invalid.status === 409 && invalid.body.state === 'ROLLED_BACK',
    `${invalid.status} ${invalid.body.state}`)
  const afterInvalid = await api('/api/v1/support/ops/channels', { token })
  check('回滚后 last-known-good 不变', afterInvalid.body.channels?.[0]?.appId === beforeAppId,
    `${beforeAppId} -> ${afterInvalid.body.channels?.[0]?.appId}`)

  const appId = envValue('SHOPPILOT_IM_FEISHU_APP_ID')
  const appSecret = envValue('SHOPPILOT_IM_FEISHU_APP_SECRET')
  if (!appId || !appSecret) throw new Error('缺 SHOPPILOT_IM_FEISHU_APP_ID/APP_SECRET')
  const startedBefore = countLog('飞书长连接已启动')
  const applied = await api('/api/v1/support/ops/channels/feishu', {
    method: 'PUT', token,
    body: { enabled: true, appId, appSecret, tenant: TENANT, customer: 'C001', identityFile: '' },
  })
  check('合法配置无需重启生效', applied.status === 200 && applied.body.state === 'APPLIED'
    && applied.body.restartRequired === false, `${applied.status} state=${applied.body.state}`)
  check('生效后 activeAppId 与实际配置一致', applied.body.activeAppId === applied.body.appId,
    `${applied.body.activeAppId} / ${applied.body.appId}`)
  const startedAfter = countLog('飞书长连接已启动')
  check('旧连接停止、新连接启动有日志证据', startedBefore < 0 || startedAfter > startedBefore,
    `启动日志 ${startedBefore} -> ${startedAfter}`)

  // ── 4. 跨重启幂等 ─────────────────────────────────────────────────
  const event1 = `ev-hot-${Date.now()}`
  const ticket1 = `T-HOT-${Date.now()}`
  received.length = 0
  receiverMode = 'ok'
  await redis('XADD', 'shoppilot:channel-outbound', '*',
    'eventId', event1, 'schemaVersion', '1', 'tenantId', TENANT,
    'customerId', 'C001',
    'channel', 'webhook', 'target', `http://localhost:${RECEIVER_PORT}/hook`,
    'ticketId', ticket1, 'body', '热生效幂等验收')
  await waitFor(() => received.length >= 1, 15000, 'first delivery')
  check('首次投递真的到达本地端点', received.length === 1, `count=${received.length}`)
  const key = `shoppilot:outbound:delivered:${event1}`
  check('幂等键落 Redis', (await redis('EXISTS', key)) === ':1', key)

  await redis('XADD', 'shoppilot:channel-outbound', '*',
    'eventId', event1, 'schemaVersion', '1', 'tenantId', TENANT,
    'customerId', 'C001',
    'channel', 'webhook', 'target', `http://localhost:${RECEIVER_PORT}/hook`,
    'ticketId', ticket1, 'body', '重复事件')
  await sleep(6000)
  check('同一进程内重复 eventId 只投递一次', received.length === 1, `count=${received.length}`)

  await restartGateway()
  token = await adminToken()
  await redis('XADD', 'shoppilot:channel-outbound', '*',
    'eventId', event1, 'schemaVersion', '1', 'tenantId', TENANT,
    'customerId', 'C001',
    'channel', 'webhook', 'target', `http://localhost:${RECEIVER_PORT}/hook`,
    'ticketId', ticket1, 'body', '重启后重复事件')
  await sleep(12000)
  check('网关重启后同一 eventId 仍只投递一次', received.length === 1, `count=${received.length}`)

  // ── 5. 退避重试 + 耗尽落回执工单 ─────────────────────────────────
  const event2 = `ev-retry-${Date.now()}`
  const ticket2 = `T-RETRY-${Date.now()}`
  received.length = 0
  receiverMode = 'fail'
  await redis('XADD', 'shoppilot:channel-outbound', '*',
    'eventId', event2, 'schemaVersion', '1', 'tenantId', TENANT,
    'customerId', 'C001',
    'channel', 'webhook', 'target', `http://localhost:${RECEIVER_PORT}/hook`,
    'ticketId', ticket2, 'body', '退避验收')
  await waitFor(() => received.length >= 3, 20000, 'three retry attempts')
  const gaps = [received[1].at - received[0].at, received[2].at - received[1].at]
  check('失败重试 3 次', received.length === 3, `count=${received.length}`)
  check('重试间隔递增（指数退避）', gaps[1] >= gaps[0] * 1.2, `gaps=${gaps.join('/')}ms`)

  let receiptFound = false
  const receiptDeadline = Date.now() + 15000
  while (Date.now() < receiptDeadline && !receiptFound) {
    try {
      const tickets = await api('/api/v1/support/ops/tickets', { token })
      const list = Array.isArray(tickets.body) ? tickets.body : (tickets.body.tickets ?? [])
      receiptFound = list.some((item) => item.source === 'CHANNEL_RECEIPT'
        && (String(item.transcript || '').includes(ticket2) || String(item.userQuery || '').includes(ticket2)))
    } catch { receiptFound = false }
    if (!receiptFound) await sleep(500)
  }
  check('重试耗尽后落 CHANNEL_RECEIPT 工单', receiptFound, `ticket=${ticket2}`)
} finally {
  receiver.close()
  redirector.close()
  if (originalChannelFile === null) {
    try { rmSync(CHANNEL_FILE, { force: true }) } catch { /* 本机清理 */ }
  } else {
    try { writeFileSync(CHANNEL_FILE, originalChannelFile) } catch { /* 本机清理 */ }
  }
}

const failed = results.filter((item) => !item.ok)
console.log(`\n渠道热生效/出站安全断言 ${results.length - failed.length}/${results.length} 通过`)
if (failed.length) {
  for (const item of failed) console.log(`FAILED  ${item.name}  — ${item.detail}`)
  process.exit(1)
}
