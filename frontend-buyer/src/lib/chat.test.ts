/**
 * 对话存档与回答收尾（round48 票 205 / ADR 0083）。
 *
 * <p>覆盖 round46/round47 定下来的三条现场约定：存档按店铺归属、刷新时在流式中的回答
 * 按中断收尾、一次回答的 499 / 失败 / 成功三种收尾文案互不串。
 */
import { describe, expect, it } from 'vitest'
import { beginAnswer, CHAT_LIMIT, restoreChat, serializeChat, settleAnswer } from './chat'
import type { ChatMessage } from './format'

const stored = (overrides: Record<string, unknown> = {}) =>
  JSON.stringify({
    tenantId: 'T001',
    conversation: 'buyer-1',
    messages: [
      { role: 'user', text: '查订单' },
      { role: 'assistant', text: '好的', conversationId: 'buyer-1' },
    ],
    ...overrides,
  })

describe('restoreChat', () => {
  it('没有存档时返回空对话，且不要求删键', () => {
    expect(restoreChat(null, 'T001')).toEqual({
      chat: { conversation: '', messages: [] },
      discard: false,
    })
  })

  it('店铺不一致整份丢弃（换店不串对话）', () => {
    const restored = restoreChat(stored({ tenantId: 'T002' }), 'T001')
    expect(restored.discard).toBe(true)
    expect(restored.chat.messages).toEqual([])
  })

  it('没有店铺码时同样丢弃', () => {
    expect(restoreChat(stored(), '').discard).toBe(true)
  })

  it('坏 JSON 或消息不是数组时丢弃而不是抛', () => {
    expect(restoreChat('{oops', 'T001').discard).toBe(true)
    expect(restoreChat(stored({ messages: {} }), 'T001').discard).toBe(true)
  })

  it('只保留 user/assistant 两种角色', () => {
    const raw = stored({
      messages: [
        { role: 'user', text: 'a' },
        { role: 'system', text: '不该出现' },
        { role: 'assistant', text: 'b' },
      ],
    })
    expect(restoreChat(raw, 'T001').chat.messages.map((message) => message.text)).toEqual(['a', 'b'])
  })

  it('刷新时在流式中的回答按中断收尾，保住已经收到的文字', () => {
    const raw = stored({ messages: [{ role: 'assistant', text: '已经收到的半句', pending: true }] })
    const [message] = restoreChat(raw, 'T001').chat.messages
    expect(message).toMatchObject({ pending: false, stopped: true, text: '已经收到的半句' })
    expect(message.note).toBe('刷新时中断，可以重试；问题已经留在上面。')
  })

  it('流式中且一个字都没有时补一句中断说明', () => {
    const raw = stored({ messages: [{ role: 'assistant', text: '', pending: true }] })
    expect(restoreChat(raw, 'T001').chat.messages[0].text).toBe('刷新时中断了这次回答。')
  })

  it('未完成的反馈票退回未评状态', () => {
    const raw = stored({ messages: [{ role: 'assistant', text: '答', feedbackState: 'sending' }] })
    expect(restoreChat(raw, 'T001').chat.messages[0].feedbackState).toBeUndefined()
  })
})

describe('serializeChat', () => {
  it('只留最近 CHAT_LIMIT 条，并带上店铺归属', () => {
    const messages: ChatMessage[] = Array.from({ length: CHAT_LIMIT + 5 }, (_, index) => ({
      role: 'user',
      text: `m${index}`,
    }))
    const parsed = JSON.parse(serializeChat('T001', 'buyer-9', messages))
    expect(parsed.tenantId).toBe('T001')
    expect(parsed.conversation).toBe('buyer-9')
    expect(parsed.messages).toHaveLength(CHAT_LIMIT)
    expect(parsed.messages[0].text).toBe('m5')
  })
})

describe('beginAnswer / settleAnswer', () => {
  it('开始新回答时清掉上一票与上一段流式状态', () => {
    const target: ChatMessage = {
      role: 'assistant',
      text: '旧答案',
      pending: false,
      stopped: true,
      failed: true,
      note: '旧的',
      feedback: 'DOWN',
      feedbackState: 'done',
      feedbackError: '旧的错',
      citations: [{ ruleId: 'R-1', heading: '标题', doc: 'x' }],
      trace: [{ kind: 'state', label: '旧步骤' }],
    }
    beginAnswer(target)
    expect(target).toMatchObject({ pending: true, text: '', trace: [] })
    expect(target.stopped).toBe(false)
    expect(target.failed).toBe(false)
    expect(target.note).toBeUndefined()
    expect(target.citations).toBeUndefined()
    expect(target.feedback).toBeUndefined()
    expect(target.feedbackState).toBeUndefined()
    expect(target.feedbackError).toBeUndefined()
  })

  it('499：标记已停止，空回答给一句兜底', () => {
    const target: ChatMessage = { role: 'assistant', text: '' }
    expect(settleAnswer(target, { status: 499 })).toBeNull()
    expect(target.stopped).toBe(true)
    expect(target.text).toBe('已停止生成。')
    expect(target.note).toBe('已停止生成，可以重试；不用重新描述问题。')
  })

  it('499 时已经收到的半句保留', () => {
    const target: ChatMessage = { role: 'assistant', text: '已经生成的一半' }
    settleAnswer(target, { status: 499 })
    expect(target.text).toBe('已经生成的一半')
  })

  it('非 200：标记失败并展示调用方给的文案', () => {
    const target: ChatMessage = { role: 'assistant', text: '' }
    expect(settleAnswer(target, { status: 0, failureText: '网关不可达' })).toBeNull()
    expect(target.failed).toBe(true)
    expect(target.text).toBe('网关不可达')
    expect(target.note).toBe('这次没有连上，可以重试；问题已经留在上面。')
  })

  it('200 且回答为空：给重试/转人工兜底', () => {
    const target: ChatMessage = { role: 'assistant', text: '' }
    settleAnswer(target, { status: 200, body: { ticketId: null, citationDetails: [], fallbackReason: null } })
    expect(target.text).toBe('这次没有收到完整回答，可以重试，或直接转人工。')
    expect(target.note).toBeUndefined()
  })

  it('200 + 降级：文案里已经有工单号时不重复贴', () => {
    const target: ChatMessage = { role: 'assistant', text: '已转人工：T-9，稍后处理' }
    const ticketId = settleAnswer(target, {
      status: 200,
      body: { ticketId: 'T-9', citationDetails: [], fallbackReason: 'TOOL_UNAVAILABLE' },
    })
    expect(ticketId).toBe('T-9')
    expect(target.note).toBe('已转人工，进度在下方「我的工单」里，不用再重复描述。')
  })

  it('200 + 降级：文案里没有工单号时贴出来', () => {
    const target: ChatMessage = { role: 'assistant', text: '这次答不了' }
    settleAnswer(target, {
      status: 200,
      body: { ticketId: 'T-9', citationDetails: [], fallbackReason: 'TOOL_UNAVAILABLE' },
    })
    expect(target.note).toBe('已转人工：T-9。进度在下方「我的工单」里，不用再重复描述。')
  })

  it('200 + 降级且没有工单：只说明转人工', () => {
    const target: ChatMessage = { role: 'assistant', text: '这次答不了' }
    settleAnswer(target, {
      status: 200,
      body: { ticketId: null, citationDetails: [], fallbackReason: 'BUDGET_EXCEEDED' },
    })
    expect(target.note).toBe('这次没能直接答出来，已转人工处理，不用再重复描述。')
  })

  it('200：引用详情写回消息对象', () => {
    const detail = { ruleId: 'R-1', heading: '七天无理由', doc: '退货政策' }
    const target: ChatMessage = { role: 'assistant', text: '答' }
    settleAnswer(target, {
      status: 200,
      body: { ticketId: null, citationDetails: [detail], fallbackReason: null },
    })
    expect(target.citations).toEqual([detail])
  })
})
