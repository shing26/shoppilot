/* ShopPilot workspace service worker：Web Push 展示与点击回会话（票 181）。 */
self.addEventListener('install', (event) => {
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(self.clients.claim())
})

self.addEventListener('push', (event) => {
  let payload = {}
  try {
    payload = event.data ? event.data.json() : {}
  } catch {
    payload = { title: '新的渠道消息', body: event.data ? event.data.text() : '买家发来一条新消息' }
  }
  const title = payload.title || '新的渠道消息'
  const conversationId = payload.conversationId || ''
  event.waitUntil(self.registration.showNotification(title, {
    body: payload.body || '买家发来一条新消息',
    tag: conversationId || 'shoppilot-channel',
    data: { conversationId },
  }))
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const conversationId = (event.notification.data && event.notification.data.conversationId) || ''
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    for (const client of windows) {
      await client.focus()
      client.postMessage({ type: 'channel-notification-click', conversationId })
      return
    }
    const suffix = conversationId ? '?conversation=' + encodeURIComponent(conversationId) : ''
    await self.clients.openWindow('/workspace/' + suffix)
  })())
})
