/**
 * 登录态读取与店铺归属校验（round49 票 210）。
 *
 * <p>钉住票 191 的残余缺口：地址栏没有店铺码时，旧会话必须丢弃而不是沿用。
 * `discard` 标志与「本来就没有存档」分开断言——两者都返回 null，但只有一个需要删键。
 */
import { describe, expect, it } from 'vitest'
import { restoreSession } from './session'
import type { Session } from '../types'

const session: Session = {
  token: 'token-1',
  accountId: 'A-1',
  username: 'buyer',
  role: 'BUYER',
  tenantId: 'T001',
  customerId: 'C-1',
  displayName: '买家',
}

const stored = (overrides: Partial<Session> = {}) => JSON.stringify({ ...session, ...overrides })

describe('restoreSession', () => {
  it('没有店铺码时丢弃旧会话（不沿用上一家店的身份）', () => {
    expect(restoreSession(stored(), '')).toEqual({ session: null, discard: true })
  })

  it('店铺码只有空白时同样丢弃', () => {
    expect(restoreSession(stored(), '   ')).toEqual({ session: null, discard: true })
  })

  it('同租户时恢复存档', () => {
    expect(restoreSession(stored(), 'T001')).toEqual({ session, discard: false })
  })

  it('异租户时丢弃（票 191 行为不变）', () => {
    expect(restoreSession(stored({ tenantId: 'T002' }), 'T001')).toEqual({ session: null, discard: true })
  })

  it('坏 JSON 丢弃而不是抛', () => {
    expect(restoreSession('{oops', 'T001')).toEqual({ session: null, discard: true })
  })

  it('没有存档时返回 null 但不要求删键（与「丢弃」区分）', () => {
    expect(restoreSession(null, 'T001')).toEqual({ session: null, discard: false })
    expect(restoreSession(null, '')).toEqual({ session: null, discard: false })
  })

  it('可解析但不是会话对象时丢弃', () => {
    expect(restoreSession('null', 'T001')).toEqual({ session: null, discard: true })
    expect(restoreSession('{}', 'T001')).toEqual({ session: null, discard: true })
  })
})
