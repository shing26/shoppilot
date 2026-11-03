const BASE = 'http://127.0.0.1:8082'
const login = await (await fetch(`${BASE}/auth/demo-login`, { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({tenantId:'T001', role:'agent'}) })).json()
const H = { Authorization: `Bearer ${login.token}` }
const all = await (await fetch(`${BASE}/api/v1/support/ops/tickets`, { headers: H })).json()
const t = all.find(x => x.id === 'T1793659863897-5')
console.log('复核工单状态:', JSON.stringify({id:t?.id, status:t?.status, source:t?.source, queue:t?.queue, assignee:t?.assignee}))
const rq = await (await fetch(`${BASE}/api/v1/support/ops/feedback/review-queue`, { headers: H })).json()
console.log('pending 复核队列:', JSON.stringify(rq))
const rev = await (await fetch(`${BASE}/api/v1/support/ops/knowledge/revisions`, { headers: H })).json()
console.log('修订稿:', JSON.stringify(rev.map(r => ({id:r.id,status:r.status,feedbackId:r.feedbackId,title:r.title}))))
