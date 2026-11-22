// 身份生命周期活体验收（round56 票 224 / ADR 0086）。
//
// 用法：
//   $env:NODE_PATH="<repo>\.tools\node_modules"
//   $env:SHOPPILOT_GATEWAY_BASE="http://127.0.0.1:8082"
//   $env:SHOPPILOT_IDENTITY_DEMO_PASSWORD="<演示口令>"
//   node scripts/verify-identity-lifecycle.mjs
//
// 真发 HTTP，覆盖：登录 → 刷新 → 旧 refresh 复用被拒 → 改密 → 旧 refresh/旧口令失效 →
// 新口令登录 → 连续失败锁定 → 窗口后恢复。脚本自己造账号，不依赖 buyer/agent 的既有状态。
import { createRequire } from 'node:module'

const BASE = process.env.SHOPPILOT_GATEWAY_BASE || 'http://127.0.0.1:8082'
const TENANT = process.env.SHOPPILOT_IDENTITY_TENANT || 'T001'
const DEMO_PASSWORD = process.env.SHOPPILOT_IDENTITY_DEMO_PASSWORD
const COOKIE_JAR = new Map()

if (!DEMO_PASSWORD) {
  console.error('缺少 SHOPPILOT_IDENTITY_DEMO_PASSWORD：演示账号口令不进仓库（ADR 0056）。')
  process.exit(2)
}

const results = []
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

/** 极简 Cookie jar：本脚本只关心 sp_rt，且只在同源回环里跑。 */
function storeCookies(response) {
  const raw = response.headers.getSetCookie?.() ?? []
  for (const line of raw) {
    const [pair] = line.split(';')
    const [name, ...rest] = pair.split('=')
    const value = rest.join('=')
    if (/Max-Age=0/i.test(line)) COOKIE_JAR.delete(name.trim())
    else COOKIE_JAR.set(name.trim(), value)
  }
}

function cookieHeader() {
  return [...COOKIE_JAR.entries()].map(([name, value]) => `${name}=${value}`).join('; ')
}

async function call(path, { method = 'POST', body, token, cookie = false, headers: extra = {} } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  if (cookie && COOKIE_JAR.size) headers.Cookie = cookieHeader()
  Object.assign(headers, extra)
  const response = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  storeCookies(response)
  const text = await response.text()
  let parsed = {}
  try {
    parsed = text ? JSON.parse(text) : {}
  } catch {
    parsed = { message: text }
  }
  return { status: response.status, body: parsed, headers: response.headers }
}

const stamp = Date.now().toString(36)
const username = `lifecycle-${stamp}`
const originalPassword = `Orig-${stamp}-pass`
const newPassword = `Next-${stamp}-pass`

// ── 0. 造一个独立买家账号 ───────────────────────────────────────────────
const registered = await call('/auth/register', {
  body: { tenantId: TENANT, username, password: originalPassword, displayName: '生命周期验收' },
})
check('注册独立验收账号', registered.status === 200 && Boolean(registered.body.token), `HTTP ${registered.status}`)
const accountId = registered.body.accountId

// ── 1. 登录 → 刷新 ─────────────────────────────────────────────────────
const loggedIn = await call('/auth/login', { body: { tenantId: TENANT, username, password: originalPassword } })
check(
  '登录返回 access token，refresh 只走 HttpOnly Cookie',
  loggedIn.status === 200 &&
    Boolean(loggedIn.body.token) &&
    !('refreshToken' in loggedIn.body) &&
    COOKIE_JAR.has('sp_rt'),
  `HTTP ${loggedIn.status}`,
)

const firstCookie = COOKIE_JAR.get('sp_rt')
const refreshed = await call('/auth/refresh', { cookie: true })
check(
  '刷新成功并轮换 Cookie',
  refreshed.status === 200 && Boolean(refreshed.body.token) && COOKIE_JAR.get('sp_rt') !== firstCookie,
  `HTTP ${refreshed.status}`,
)
const secondCookie = COOKIE_JAR.get('sp_rt')

// ── 2. 旧 refresh 复用 → 撤销整族 ─────────────────────────────────────
const reused = await call('/auth/refresh', {
  cookie: true,
  // 手动把旧票塞回去：浏览器已经换成新的，这里模拟攻击者拿着旧票重放
  headers: { Cookie: `sp_rt=${firstCookie}` },
})
check(
  '旧 refresh 复用被拒（refresh_reuse）',
  reused.status === 401 && reused.body.code === 'refresh_reuse',
  `HTTP ${reused.status} code=${reused.body.code || '-'}`,
)

// 新票也已被撤：复用检测撤销整族
const afterReuse = await call('/auth/refresh', { headers: { Cookie: `sp_rt=${secondCookie}` } })
check('复用后整族失效（新 refresh 也拒绝）', afterReuse.status === 401, `HTTP ${afterReuse.status}`)

// ── 2b. 停用与角色变更 ────────────────────────────────────────────────
// 用管理员账号改这个验收账号的角色；管理员账号由演示播种提供。
const adminLogin = await call('/auth/login', {
  body: { tenantId: TENANT, username: 'admin', password: DEMO_PASSWORD },
})
check('管理员账号可登录（用于角色/停用验收）', adminLogin.status === 200 && Boolean(adminLogin.body.token),
  `HTTP ${adminLogin.status}`)
const adminToken = adminLogin.body.token

const reloginForRole = await call('/auth/login', {
  body: { tenantId: TENANT, username, password: originalPassword },
})
const roleRefresh = COOKIE_JAR.get('sp_rt')
const roleChanged = await call(`/auth/accounts/${encodeURIComponent(accountId)}/role`, {
  token: adminToken,
  body: { role: 'AGENT' },
})
check('管理员可把账号角色改为 AGENT', roleChanged.status === 200 && roleChanged.body.role === 'AGENT',
  `HTTP ${roleChanged.status}`)

const roleStaleRefresh = await call('/auth/refresh', { headers: { Cookie: `sp_rt=${roleRefresh}` } })
check('角色变更后旧 refresh 立即失效', roleStaleRefresh.status === 401, `HTTP ${roleStaleRefresh.status}`)

const meAfterRole = await call('/auth/me', { method: 'GET', token: reloginForRole.body.token })
check(
  '/auth/me 按服务端最新角色返回能力',
  meAfterRole.status === 200 && meAfterRole.body.role === 'AGENT' && meAfterRole.body.capabilities.includes('tickets'),
  `HTTP ${meAfterRole.status} role=${meAfterRole.body.role || '-'}`,
)

// ── 3. 改密 ───────────────────────────────────────────────────────────
const relogin = await call('/auth/login', { body: { tenantId: TENANT, username, password: originalPassword } })
check('改密前用原口令重新登录', relogin.status === 200 && Boolean(relogin.body.token), `HTTP ${relogin.status}`)
const access = relogin.body.token
const refreshBeforeChange = COOKIE_JAR.get('sp_rt')

const wrongCurrent = await call('/auth/password', {
  token: access,
  body: { currentPassword: 'definitely-wrong', newPassword },
})
check('错误当前口令被拒', wrongCurrent.status === 401, `HTTP ${wrongCurrent.status}`)

const changed = await call('/auth/password', {
  token: access,
  body: { currentPassword: originalPassword, newPassword },
})
check('正确当前口令改密成功', changed.status === 200 && changed.body.changed === true, `HTTP ${changed.status}`)

const staleRefresh = await call('/auth/refresh', { headers: { Cookie: `sp_rt=${refreshBeforeChange}` } })
check('改密后旧 refresh 立即失效', staleRefresh.status === 401, `HTTP ${staleRefresh.status}`)

const oldPasswordLogin = await call('/auth/login', { body: { tenantId: TENANT, username, password: originalPassword } })
check('旧口令登录失败', oldPasswordLogin.status === 401, `HTTP ${oldPasswordLogin.status}`)

const newPasswordLogin = await call('/auth/login', { body: { tenantId: TENANT, username, password: newPassword } })
check('新口令登录成功', newPasswordLogin.status === 200 && Boolean(newPasswordLogin.body.token), `HTTP ${newPasswordLogin.status}`)

// ── 3b. 停用账号 ─────────────────────────────────────────────────────
const disableRefresh = COOKIE_JAR.get('sp_rt')
const disabled = await call(`/auth/accounts/${encodeURIComponent(accountId)}/status`, {
  token: adminToken,
  body: { active: false },
})
check('管理员可停用账号', disabled.status === 200, `HTTP ${disabled.status}`)
const disabledRefresh = await call('/auth/refresh', { headers: { Cookie: `sp_rt=${disableRefresh}` } })
check('停用后 refresh 立即拒绝', disabledRefresh.status === 401, `HTTP ${disabledRefresh.status}`)
const disabledLogin = await call('/auth/login', { body: { tenantId: TENANT, username, password: newPassword } })
check('停用后登录被拒', disabledLogin.status === 401, `HTTP ${disabledLogin.status}`)

// ── 4. 连续失败锁定 → 窗口恢复 ─────────────────────────────────────────
for (let i = 0; i < 5; i++) {
  await call('/auth/login', { body: { tenantId: TENANT, username, password: `wrong-${i}-${stamp}` } })
}
const locked = await call('/auth/login', { body: { tenantId: TENANT, username, password: newPassword } })
check(
  '连续失败 5 次后锁定：正确口令也 429，且带 Retry-After',
  locked.status === 429 && Boolean(locked.headers.get('retry-after')),
  `HTTP ${locked.status} retry-after=${locked.headers.get('retry-after') || '-'}`,
)
check(
  '锁定文案不确认账号是否存在',
  !JSON.stringify(locked.body).includes(username),
  JSON.stringify(locked.body).slice(0, 80),
)

console.log('INFO  锁定窗口恢复需要等待 15 分钟；本轮不把等待写成 PASS。')
console.log('INFO  窗口恢复的机器验证在 IdentityLifecycleTest.lockoutBlocksCorrectPasswordUntilWindowEnds（拨动 locked_until）。')

const failed = results.filter((r) => !r.ok)
console.log(`\n身份生命周期断言 ${results.length - failed.length}/${results.length} 通过`)
process.exit(failed.length === 0 ? 0 : 1)
