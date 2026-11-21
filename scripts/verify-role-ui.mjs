// 票 221：角色菜单与 403 面板的浏览器级验收。
//
// 用法：
//   $env:NODE_PATH="<repo>\.tools\node_modules"
//   node scripts/verify-role-ui.mjs
//
// 前置：网关 8082 已用包含票 221 代码的构建启动，且 demo-login 可用。
// 脚本不打印、不持久化任何 token 或口令。
import { createRequire } from 'node:module'

const { chromium } = createRequire(import.meta.url)('playwright')
const BASE = process.env.SHOPPILOT_WORKSPACE_BASE || 'http://127.0.0.1:8082'
const PAGE = `${BASE}/workspace/`

const results = []
const check = (name, ok, detail = '') => {
  results.push({ name, ok })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? `  — ${detail}` : ''}`)
}

const browser = await chromium.launch()
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })
const errors = []
page.on('pageerror', (error) => errors.push(String(error)))

try {
  await page.goto(PAGE, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: '一键体验坐席' }).click()
  await page.waitForSelector('.side-nav', { timeout: 10000 })

  const labels = await page.locator('.side-nav .nav-item').allInnerTexts()
  check('AGENT 侧栏只有五个入口', labels.length === 5, labels.join(' / '))
  check('AGENT 侧栏没有渠道连接', !labels.some((label) => label.includes('渠道连接')))
  check('AGENT 身份仍显示为坐席', (await page.locator('.nav-foot').innerText()).includes('坐席'))

  const sessionBefore = await page.evaluate(() => sessionStorage.getItem('shoppilot.session'))
  // 侧栏对 AGENT 不渲染「渠道连接」，这里从 SideNav 组件发出一次 select，
  // 模拟程序化跳转/旧深链进入无权限视图；断言的是根组件收到事件后的 403 呈现。
  const denied = await page.evaluate(() => {
    const root = document.querySelector('#app')?._vnode?.component
    const queue = [root?.subTree]
    while (queue.length) {
      const vnode = queue.shift()
      if (!vnode || typeof vnode !== 'object') continue
      const component = vnode.component
      if (component?.type?.__name === 'SideNav' || component?.type?.name === 'SideNav') {
        component.emit('select', 'channels')
        return true
      }
      if (Array.isArray(vnode.children)) queue.push(...vnode.children)
      if (component?.subTree) queue.push(component.subTree)
    }
    return false
  })
  check('能够模拟直接进入 channels 视图', denied)

  await page.waitForSelector('.access-denied', { timeout: 5000 }).catch(() => {})
  check('无权限视图显示 403 面板', await page.locator('.access-denied').isVisible().catch(() => false))
  const deniedText = await page.locator('.access-denied').innerText().catch(() => '')
  check('403 面板明确说明权限不足', deniedText.includes('403') && deniedText.includes('权限'))
  check('403 后没有退回登录页', (await page.locator('.login-card').count()) === 0)
  check(
    '403 后会话仍保留',
    (await page.evaluate(() => sessionStorage.getItem('shoppilot.session'))) === sessionBefore,
  )

  await page.getByRole('button', { name: '退出' }).click()
  await page.waitForSelector('.login-card', { timeout: 5000 })
  await page.getByRole('button', { name: '管理员体验' }).click()
  await page.waitForSelector('.side-nav', { timeout: 10000 })
  const adminLabels = await page.locator('.side-nav .nav-item').allInnerTexts()
  check('ADMIN 侧栏显示全部六个入口', adminLabels.length === 6, adminLabels.join(' / '))
  check('ADMIN 侧栏显示渠道连接', adminLabels.some((label) => label.includes('渠道连接')))
  check('浏览器无未捕获异常', errors.length === 0, errors.join(' | '))
} finally {
  await browser.close()
}

const failed = results.filter((result) => !result.ok)
console.log(`\nPASS ${results.length - failed.length} / FAIL ${failed.length}`)
process.exit(failed.length ? 1 : 0)
