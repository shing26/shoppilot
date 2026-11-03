const BASE='http://127.0.0.1:8082'
for (const t of ['T001','T002','T003','T004']) {
  const r = await fetch(`${BASE}/auth/mock-token`, {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({tenantId:t,customerId:'C001'})})
  const b = await r.json().catch(()=>null)
  const claims = b?.token ? JSON.parse(Buffer.from(b.token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/'),'base64').toString()) : null
  const sp = b?.token ? await (await fetch(`${BASE}/api/v1/support/store-profile`,{headers:{Authorization:`Bearer ${b.token}`}})).json().catch(()=>null) : null
  const orders = b?.token ? await (await fetch(`${BASE}/api/v1/support/orders/mine`,{headers:{Authorization:`Bearer ${b.token}`}})).json().catch(()=>null) : null
  console.log(t, r.status, 'claims=', JSON.stringify(claims), 'profile=', JSON.stringify(sp), 'orders=', Array.isArray(orders)?orders.length:orders)
}
