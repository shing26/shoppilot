/**
 * 对话存档与一次回答的状态收尾（round48 票 205 / ADR 0083，从 `App.vue` 下沉）。
 *
 * <p>这两块是买家页最容易回归、又最不需要 DOM 的逻辑：存档要按店铺归属归一化，
 * 流式回答的 499 / 失败 / 成功三种收尾各自写不同文案与标记。把它们留在根组件里时，
 * 只能靠 Playwright 活体脚本事后验；下沉后 Vitest 可以直接喂输入断言输出。
 *
 * <p>**行为逐字照搬**：文案、标记与分支顺序都取自收口前的 `App.vue`，
 * 包括「刷新时还在流式中的回答按中断收尾」这条 round46 的约定。
 */
import type { CitationDetail } from '../types'
import type { ChatMessage } from './format'

/** 存档上限：只留最近 100 条，避免长会话把 sessionStorage 配额吃满。 */
export const CHAT_LIMIT = 100

export interface StoredChat {
  conversation: string
  messages: ChatMessage[]
}

export interface RestoredChat {
  chat: StoredChat
  /** true = 存档不可用（坏 JSON / 换店 / 形状不对），调用方应删掉这个键。 */
  discard: boolean
}

const EMPTY: StoredChat = { conversation: '', messages: [] }

/**
 * 读回本标签页的对话。
 *
 * <p>存档跟着**店铺**走：`tenantId` 与当前会话不一致就整份丢弃，换店不会看到上一家的对话。
 * 刷新时还在流式中的那条回答按「中断」收尾——页面已经重载，那条流再也回不来了，
 * 假装它还在等只会让界面永远转圈。
 */
export function restoreChat(raw: string | null, tenantId: string): RestoredChat {
  if (!raw) return { chat: { ...EMPTY, messages: [] }, discard: false }
  try {
    const stored = JSON.parse(raw) as {
      tenantId?: string
      conversation?: string
      messages?: ChatMessage[]
    }
    if (!tenantId || stored.tenantId !== tenantId || !Array.isArray(stored.messages)) {
      return { chat: { ...EMPTY, messages: [] }, discard: true }
    }
    const messages = stored.messages.filter(
      (message) => message?.role === 'user' || message?.role === 'assistant',
    )
    for (const message of messages) {
      if (typeof message.text !== 'string') message.text = ''
      if (message.feedbackState === 'sending') message.feedbackState = undefined
      if (message.pending) {
        message.pending = false
        message.stopped = true
        if (!message.text.trim()) message.text = '刷新时中断了这次回答。'
        message.note = '刷新时中断，可以重试；问题已经留在上面。'
      }
    }
    return { chat: { conversation: stored.conversation ?? '', messages }, discard: false }
  } catch (unreadable) {
    return { chat: { ...EMPTY, messages: [] }, discard: true }
  }
}

/** 写回本标签页的对话；调用方负责吞掉配额不足 / 隐私模式的异常。 */
export function serializeChat(tenantId: string, conversation: string, messages: ChatMessage[]): string {
  return JSON.stringify({
    tenantId,
    conversation,
    messages: messages.slice(-CHAT_LIMIT),
  })
}

/** 一次流式回答开始前的重置。重试复用同一个消息对象，上一票作废。 */
export function beginAnswer(target: ChatMessage) {
  target.pending = true
  target.stopped = false
  target.failed = false
  target.note = undefined
  target.citations = undefined
  target.text = ''
  target.trace = []
  target.feedback = undefined
  target.feedbackState = undefined
  target.feedbackError = undefined
}

export interface AnswerOutcome {
  status: number
  /** 非 200 时由调用方用 `describe()` 算好的展示文案。 */
  failureText?: string
  body?: {
    ticketId: string | null
    citationDetails?: CitationDetail[]
    fallbackReason: string | null
  }
}

/**
 * 一次回答的收尾。
 *
 * @return 需要刷新工单列表时返回工单号；其余情况返回 null（与「没有工单」不可混用：
 *         调用方只按它决定要不要再拉一次列表）
 */
export function settleAnswer(target: ChatMessage, outcome: AnswerOutcome): string | null {
  if (outcome.status === 499) {
    target.stopped = true
    if (!target.text.trim()) target.text = '已停止生成。'
    target.note = '已停止生成，可以重试；不用重新描述问题。'
    return null
  }
  if (outcome.status !== 200) {
    target.failed = true
    target.text = outcome.failureText ?? ''
    target.note = '这次没有连上，可以重试；问题已经留在上面。'
    return null
  }
  const body = outcome.body
  if (!body) return null
  target.citations = body.citationDetails ?? []
  if (!target.text.trim()) {
    target.text = '这次没有收到完整回答，可以重试，或直接转人工。'
  }
  if (body.fallbackReason) {
    const ticketInText = !!body.ticketId && target.text.includes(body.ticketId)
    target.note = body.ticketId
      ? (ticketInText
        ? '已转人工，进度在下方「我的工单」里，不用再重复描述。'
        : `已转人工：${body.ticketId}。进度在下方「我的工单」里，不用再重复描述。`)
      : '这次没能直接答出来，已转人工处理，不用再重复描述。'
  }
  return body.ticketId
}
