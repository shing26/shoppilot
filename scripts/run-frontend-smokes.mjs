// 前端三条活体冒烟的唯一入口（round48 票 205 / ADR 0083）。
//
// 三条冒烟仍是既有的 verify-buyer / verify-workspace / verify-console：
// 它们各自带断言与退出码，本脚本只负责顺序执行、汇总结果，不重写任何断言。
//
// 用法（PowerShell）：
//   $env:NODE_PATH="<repo>\.tools\node_modules"
//   $env:SHOPPILOT_IDENTITY_DEMO_PASSWORD="..."   # 三条都需要（ADR 0056：口令不进仓库）
//   node scripts/run-frontend-smokes.mjs
//
// 前置：活体栈已起（网关 8082 + biz-mock 8091 + ticket 8092 + Redis），
// 买家/工作台/调试台用的是同一个网关。**它不进 ci-subset**：CI 没有活体栈与演示口令。
// 退出码：0 全过；1 至少一条冒烟有断言失败；2 至少一条因缺少前置（口令/浏览器）没有跑起来。
import { spawn } from 'node:child_process'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))

const SMOKES = [
  { name: 'buyer', script: 'verify-buyer.mjs' },
  { name: 'workspace', script: 'verify-workspace.mjs' },
  { name: 'console', script: 'verify-console.mjs' },
]

const results = []
for (const smoke of SMOKES) {
  console.log(`\n=== ${smoke.name} ===`)
  const code = await new Promise((resolve) => {
    const child = spawn(process.execPath, [join(HERE, smoke.script)], { stdio: 'inherit' })
    child.on('error', (error) => {
      console.error(`${smoke.script} 启动失败：${error.message}`)
      resolve(1)
    })
    child.on('exit', (exitCode, signal) => resolve(signal ? 1 : (exitCode ?? 1)))
  })
  results.push({ name: smoke.name, code })
}

const failed = results.filter((result) => result.code !== 0)
console.log('\n前端冒烟汇总：')
for (const result of results) {
  console.log(`  ${result.code === 0 ? 'PASS' : 'FAIL'}  ${result.name}`)
}

if (failed.some((result) => result.code === 2)) process.exit(2)
process.exit(failed.length === 0 ? 0 : 1)
