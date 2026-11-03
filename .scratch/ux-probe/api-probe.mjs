const BASE = 'http://127.0.0.1:8082'
const login = await (await fetch(`${BASE}/auth/demo-login`, { method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({tenantId:'T001', role:'agent'}) })).json()
const H = { Authorization: `Bearer ${login.token}` }
for (const q of ['ALL','ESCALATION','FEEDBACK_REVIEW']) {
  const r = await fetch(`${BASE}/api/v1/support/ops/tickets?queue=${encodeURIComponent(q)}`, { headers: H })
  const body = await r.json().catch(() => null)
  console.log(q, r.status, Array.isArray(body) ? body.map(t => `${t.id}|${t.source}|${t.status}|${t.queue}|${(t.userQuery||'').slice(0,30)}`) : body)
}
const fq = await (await fetch(`${BASE}/api/v1/support/ops/feedback/review-queue`, { headers: H })).json().catch(()=>null)
console.log('review-queue', JSON.stringify(fq))
const rev = await (await fetch(`${BASE}/api/v1/support/ops/knowledge/revisions`, { headers: H })).json().catch(()=>null)
console.log('revisions', JSON.stringify(rev?.map?.(r => ({id:r.id,status:r.status,feedbackId:r.feedbackId})) ?? rev))
