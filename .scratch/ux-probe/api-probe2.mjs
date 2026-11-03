import fs from 'node:fs'
const BASE = 'http://127.0.0.1:8082'
const login = await (await fetch(`${BASE}/auth/demo-login`, { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({tenantId:'T001', role:'agent'}) })).json()
const H = { Authorization: `Bearer ${login.token}` }
const r = await fetch(`${BASE}/api/v1/support/ops/tickets`, { headers: H })
const all = await r.json()
console.log('tickets', r.status, Array.isArray(all) ? all.map(t => `${t.id}|${t.source}|${t.status}|${t.queue}|${(t.userQuery||'').slice(0,34)}`) : all)
const log = JSON.parse(fs.readFileSync('.scratch/ux-probe/shots/workspace-log.json','utf8'))
console.log('\nKB lines:')
for (const l of log.log) if (l.stage === '知识') console.log(' -', l.text)
