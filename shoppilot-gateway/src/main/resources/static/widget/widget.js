(() => {
  const KEY = new URLSearchParams(location.search).get('key') || 'demo-widget-key';
  const VISITOR_KEY = 'shoppilot.widget.visitor';
  let visitorId = localStorage.getItem(VISITOR_KEY);
  if (!visitorId) {
    visitorId = 'v' + Math.random().toString(36).slice(2, 10);
    localStorage.setItem(VISITOR_KEY, visitorId);
  }
  const messages = document.getElementById('messages');
  const form = document.getElementById('form');
  const input = document.getElementById('input');
  const send = document.getElementById('send');
  let seen = 0;
  const api = (path, options = {}) => fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', 'X-Widget-Key': KEY, ...(options.headers || {}) },
  });
  function add(role, text) {
    const wrap = document.createElement('div');
    wrap.className = 'msg ' + (role === 'user' ? 'user' : 'agent');
    const meta = document.createElement('div');
    meta.className = 'meta';
    meta.textContent = role === 'user' ? '我' : '客服';
    const body = document.createElement('div');
    body.textContent = text;
    wrap.append(meta, body);
    messages.appendChild(wrap);
    messages.scrollTop = messages.scrollHeight;
  }
  async function refresh() {
    try {
      const response = await api('/api/v1/widget/chat/' + encodeURIComponent(visitorId));
      if (!response.ok) return;
      const data = await response.json();
      const list = data.messages || [];
      for (; seen < list.length; seen++) {
        add(list[seen].role === 'user' ? 'user' : 'agent', list[seen].text);
      }
    } catch { /* 离线时静默，下轮再试 */ }
  }
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const text = input.value.trim();
    if (!text) return;
    input.value = '';
    send.disabled = true;
    try {
      const response = await api('/api/v1/widget/chat', {
        method: 'POST',
        body: JSON.stringify({ visitorId, message: text }),
      });
      const data = await response.json();
      if (!response.ok) add('agent', data.error || '发送失败，请稍后再试');
      else await refresh();
    } catch {
      add('agent', '网络异常，请稍后再试');
    } finally {
      send.disabled = false;
      input.focus();
    }
  });
  refresh();
  setInterval(refresh, 2000);
})();
