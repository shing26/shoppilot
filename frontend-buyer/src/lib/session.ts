/**
 * 买家页登录态的读取与店铺归属校验（round49 票 210，从 `App.vue` 下沉）。
 *
 * <p>round45 票 191 只拦住了「地址栏有 `?store=` 但存档属于别家店」：
 * `if (STORE_CODE && stored.tenantId !== STORE_CODE)` 在店铺码为空时短路，直接打开 `/buyer/`
 * 会把上一个标签页留下的身份原样恢复——令牌是真的，只是店不对，店卡、快捷问题与助手回答
 * 会一起沿用上一家店。这里把「空店铺码一律丢弃」写成显式分支，`App.vue` 只负责按结果删键。
 *
 * <p>**有店铺码时行为与票 191 一致**：同租户恢复、异租户丢弃；坏 JSON 照旧丢弃并返回 null。
 */
import type { Session } from '../types'

export interface RestoredSession {
  /** 可用的登录态；null 表示没有存档或存档被丢弃。 */
  session: Session | null
  /** true = 存档不可用（缺店铺码 / 换店 / 坏 JSON），调用方应删掉这个键。 */
  discard: boolean
}

/**
 * 读回本标签页的登录态。
 *
 * <p>没有店铺码时不做任何恢复：此时页面会走「缺少店铺码」/游客态，留着旧令牌只会让
 * 下一次 API 调用带着上一家店的身份。存档本身为空时不算「丢弃」——没有键可删。
 */
export function restoreSession(raw: string | null, storeCode: string): RestoredSession {
  if (!raw) return { session: null, discard: false }
  const code = storeCode.trim()
  if (!code) return { session: null, discard: true }
  try {
    const stored = JSON.parse(raw) as Session
    if (stored.tenantId !== code) return { session: null, discard: true }
    return { session: stored, discard: false }
  } catch (unreadable) {
    return { session: null, discard: true }
  }
}
