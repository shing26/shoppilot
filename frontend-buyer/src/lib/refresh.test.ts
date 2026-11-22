/**
 * 401 单飞刷新（round56 票 224 / ADR 0086）。
 *
 * <p>要钉的是三条：并发 401 只刷新一次；成功后只重试原请求一次；
 * 刷新失败只登出一次，不形成 401 循环。
 *
 * <p>测试放在买家工程而不是 `frontend-shared`：共享包没有自己的测试运行器，
 * 放在这里才会进入 `npm test` 与 CI；被测代码仍从共享入口导入。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { changePassword, request, setRefreshHandler, setUnauthorizedHandler } from 'frontend-shared'
import { streamAnswer } from '../api'

describe('request 401 refresh', () => {
  const responses: Array<{ status: number; body: string }> = []
  let calls: string[] = []

  beforeEach(() => {
    calls = []
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      calls.push(url)
      const next = responses.shift() ?? { status: 200, body: '{"ok":true}' }
      return new Response(next.body, { status: next.status })
    }))
  })

  afterEach(() => {
    setRefreshHandler(null)
    setUnauthorizedHandler(null)
    vi.unstubAllGlobals()
  })

  it('并发 401 只刷新一次，并各自重试一次', async () => {
    responses.push({ status: 401, body: '{}' }, { status: 401, body: '{}' },
      { status: 200, body: '{"ok":1}' }, { status: 200, body: '{"ok":2}' })
    const refresh = vi.fn(async () => {
      await new Promise((resolve) => setTimeout(resolve, 5))
      return 'new-token'
    })
    setRefreshHandler(refresh)
    const signOut = vi.fn()
    setUnauthorizedHandler(signOut)

    const [a, b] = await Promise.all([
      request<{ ok: number }>('/a', { token: 'old' }),
      request<{ ok: number }>('/b', { token: 'old' }),
    ])

    expect(refresh).toHaveBeenCalledTimes(1)
    expect(a.status).toBe(200)
    expect(b.status).toBe(200)
    expect(signOut).not.toHaveBeenCalled()
    expect(calls.filter((url) => url === '/a')).toHaveLength(2)
  })

  it('刷新失败只登出一次，且不重试原请求', async () => {
    responses.push({ status: 401, body: '{}' }, { status: 401, body: '{}' })
    setRefreshHandler(async () => null)
    const signOut = vi.fn()
    setUnauthorizedHandler(signOut)

    const [a, b] = await Promise.all([
      request('/a', { token: 'old' }),
      request('/b', { token: 'old' }),
    ])

    expect(a.status).toBe(401)
    expect(b.status).toBe(401)
    expect(signOut).toHaveBeenCalledTimes(1)
    expect(calls).toHaveLength(2)
  })

  it('重试仍 401 时直接登出，不再刷新第二次', async () => {
    responses.push({ status: 401, body: '{}' }, { status: 401, body: '{}' })
    const refresh = vi.fn(async () => 'new-token')
    setRefreshHandler(refresh)
    const signOut = vi.fn()
    setUnauthorizedHandler(signOut)

    const result = await request('/a', { token: 'old' })

    expect(result.status).toBe(401)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(signOut).toHaveBeenCalledTimes(1)
  })

  it('登录请求 allowRefresh=false：401 不触发刷新', async () => {
    responses.push({ status: 401, body: '{}' })
    const refresh = vi.fn(async () => 'new-token')
    setRefreshHandler(refresh)

    const result = await request('/auth/login', { method: 'POST', allowRefresh: false })

    expect(result.status).toBe(401)
    expect(refresh).not.toHaveBeenCalled()
  })

  it('SSE 401 先刷新一次，再用新令牌重发同一个请求', async () => {
    const seen: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      const auth = new Headers(init?.headers).get('Authorization') ?? ''
      seen.push(auth)
      if (seen.length === 1) return new Response('{"message":"expired"}', { status: 401 })
      return new Response('event: token\ndata: "好的"\n\nevent: done\ndata: {}\n\n', { status: 200 })
    }))
    const refresh = vi.fn(async () => 'new-token')
    setRefreshHandler(refresh)
    const signOut = vi.fn()
    setUnauthorizedHandler(signOut)
    let answer = ''

    const result = await streamAnswer('old-token', '在吗', 'conv-1', (chunk) => { answer += chunk })

    expect(result.status).toBe(200)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(seen).toEqual(['Bearer old-token', 'Bearer new-token'])
    expect(answer).toBe('好的')
    expect(signOut).not.toHaveBeenCalled()
  })

  it('SSE 刷新失败只登出一次，不形成 401 循环', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{"message":"expired"}', { status: 401 })))
    setRefreshHandler(async () => null)
    const signOut = vi.fn()
    setUnauthorizedHandler(signOut)

    const result = await streamAnswer('old-token', '在吗', 'conv-1', () => undefined)

    expect(result.status).toBe(401)
    expect(signOut).toHaveBeenCalledTimes(1)
  })

  it('改密当前口令错误返回 403：不刷新、不登出，保留弹窗错误', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{"message":"当前口令不对"}', { status: 403 })))
    const refresh = vi.fn(async () => 'new-token')
    setRefreshHandler(refresh)
    const signOut = vi.fn()
    setUnauthorizedHandler(signOut)

    const result = await changePassword('token', 'wrong', 'new-password')

    expect(result.status).toBe(403)
    expect(refresh).not.toHaveBeenCalled()
    expect(signOut).not.toHaveBeenCalled()
  })
})
