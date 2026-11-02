/**
 * 买家页的文案与轨迹整理（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>这里只放**没有状态**的东西：状态标签、时间文案、Agent 轨迹折叠。
 * 任何需要读写 ref 的逻辑都留在 `App.vue`——组件与工具模块都不持有页面状态。
 *
 * <p>时间格式与计数的实现下沉到 `frontend-shared`；本文件保留买家自己的兜底文案
 * （空时间显示空串，而不是坐席端的 `—`）。
 */
import { formatStamp } from 'frontend-shared'
import type { AgentRunStep } from '../api'
import { minutesLeft, type CitationDetail, type Ticket } from '../types'

/** 一条对话消息。流式回答**原地修改**这个对象，所以它必须按引用传递。 */
export interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  pending?: boolean
  note?: string
  citations?: CitationDetail[]
  /** 重试时复用原问题，不要求用户再打一遍。 */
  retryQuery?: string
  stopped?: boolean
  failed?: boolean
  /** Agent 运行轨迹：只显示面客可读的步骤，不展示内部 state detail。 */
  trace?: AgentRunStep[]
}

export const ORDER_STATUS_LABEL: Record<string, string> = {
  CREATED: '待付款',
  PAID: '待发货',
  SHIPPED: '运输中',
  DELIVERED: '已送达',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  REFUNDING: '退款中',
  REFUNDED: '已退款',
}

export function slaText(ticket: Ticket, now: number): string {
  if (ticket.escalatedAt) return '已超时（状态不变）'
  const left = minutesLeft(ticket.slaDeadline, now)
  if (left === null) return '—'
  return left >= 0 ? `剩 ${left} 分钟` : `超时 ${-left} 分钟`
}

/** 工单来源的产品化说法；未知来源原样显示，不吞掉信息。 */
export function channelLabel(channel: string | null | undefined): string {
  const labels: Record<string, string> = { web: '网页', app: 'App', miniapp: '小程序', webhook: 'Webhook', email: '邮件' }
  return channel ? (labels[channel] ?? channel) : '网页'
}

export function reasonLabel(reason: string | null): string {
  const labels: Record<string, string> = {
    TOOL_UNAVAILABLE: '系统暂时无法回答',
    USER_REQUESTED: '你要求转人工',
    LLM_TIMEOUT: '模型响应超时',
    LLM_UNAVAILABLE: '模型暂不可用',
    TOOL_ROUNDS_EXHAUSTED: '工具调用未完成',
    BUDGET_EXCEEDED: '今日模型额度用尽',
    REFUND_APPROVAL: '退款待审核',
    EMAIL_REPLY: '邮件回执',
    FEEDBACK_REVIEW: '反馈复核',
  }
  return reason ? (labels[reason] ?? reason) : '人工处理'
}

export const TOOL_STATUS_LABEL: Record<string, string> = {
  OK: '成功',
  NOT_FOUND: '未找到',
  STATE_NOT_ALLOWED: '当前状态不允许',
  PENDING_APPROVAL: '待人工审核',
  IDEMPOTENT_REPLAY: '已处理过',
  TIMEOUT: '响应超时',
  UNAVAILABLE: '暂不可用',
}

export function agentStepText(step: AgentRunStep): string {
  if (step.kind === 'tool_result') {
    return `业务系统返回${TOOL_STATUS_LABEL[step.status ?? ''] ?? (step.status || '结果')}`
  }
  if (step.kind === 'fallback') return '已转人工兜底，工单会出现在侧栏'
  return step.label
}

export function appendAgentStep(target: ChatMessage, step: AgentRunStep) {
  const trace = target.trace ?? []
  const previous = trace[trace.length - 1]
  // 状态机可能连续发同名状态；对用户来说那是同一件事，去重后再展示。
  if (previous && step.kind === 'state' && previous.kind === 'state' && previous.label === step.label) {
    return
  }
  target.trace = [...trace, { ...step, at: Date.now() }]
}

export interface AgentPhase {
  key: string
  title: string
  hint: string
  steps: AgentRunStep[]
}

/** 把内部 trace 折成三段人话：理解 → 调用 → 回复。 */
export function agentPhases(trace: AgentRunStep[]): AgentPhase[] {
  const phases: AgentPhase[] = [
    { key: 'understand', title: '理解需求', hint: '识别意图与政策', steps: [] },
    { key: 'act', title: '调用系统', hint: '查订单 / 物流 / 办理', steps: [] },
    { key: 'reply', title: '生成回复', hint: '汇总结果与引用', steps: [] },
  ]
  const isActStep = (step: AgentRunStep) =>
    step.kind === 'tool' || step.kind === 'tool_result' || step.kind === 'fallback'
    || (step.kind === 'state' && (step.label.includes('调用业务系统') || step.label.includes('调用工具')))
  const actIndexes = trace
    .map((step, index) => ({ step, index }))
    .filter(({ step }) => isActStep(step))
    .map(({ index }) => index)
  const firstAct = actIndexes.length ? actIndexes[0] : -1
  const lastAct = actIndexes.length ? actIndexes[actIndexes.length - 1] : -1
  trace.forEach((step, index) => {
    if (step.kind === 'plan') return
    let phase: AgentPhase
    if (isActStep(step)) {
      phase = phases[1]
    } else if (firstAct >= 0 && index > lastAct) {
      phase = phases[2]
    } else if (firstAct < 0 && (step.label.includes('生成回复') || step.label.includes('写入缓存'))) {
      phase = phases[2]
    } else {
      phase = phases[0]
    }
    phase.steps.push(step)
  })
  return phases.filter((phase) => phase.steps.length)
}

/** plan 事件把步骤串成一句“计划：A → B”，这里拆成 checklist。 */
export function agentPlanSteps(trace: AgentRunStep[]): string[] {
  const plan = trace.find((step) => step.kind === 'plan')
  if (!plan) return []
  return plan.label
    .replace(/^计划[:：]\s*/, '')
    .split('→')
    .map((item) => item.trim())
    .filter(Boolean)
}

export function agentDuration(trace: AgentRunStep[]): string {
  const times = trace.map((step) => step.at).filter((value): value is number => typeof value === 'number')
  if (times.length < 2) return ''
  const elapsed = Math.max(...times) - Math.min(...times)
  if (elapsed < 1000) return `${elapsed}ms`
  return `${(elapsed / 1000).toFixed(1)}s`
}

export function agentStepTone(step: AgentRunStep): string {
  if (step.kind === 'fallback') return 'danger'
  if (step.kind === 'tool_result') {
    if (step.status === 'OK' || step.status === 'PENDING_APPROVAL') return 'ok'
    if (step.status === 'TIMEOUT' || step.status === 'UNAVAILABLE') return 'danger'
    return 'warn'
  }
  return 'neutral'
}

/** 工单时间线用的短时间：同城演示只显示月-日 时:分，不引入日期库。 */
export function formatTime(value: string | null | undefined): string {
  return formatStamp(value) ?? ''
}

/** 一组同文档的依据。`doc` 为空时归到空串组，渲染层给一句兜底文案。 */
export interface CitationGroup {
  doc: string
  items: CitationDetail[]
}

/**
 * 依据按文档分组（round44 票 190）。
 *
 * <p>检索结果本来就是「同一份政策里的若干小节」，平铺成一列会让人数不出「依据了几份」。
 * 组内保持后端给的顺序（相关度序），组间按首次出现排——重排过一次就等于替检索器重排。
 */
export function groupCitations(citations: CitationDetail[] | undefined): CitationGroup[] {
  const groups = new Map<string, CitationDetail[]>()
  for (const citation of citations ?? []) {
    const doc = citation.doc ?? ''
    const bucket = groups.get(doc)
    if (bucket) bucket.push(citation)
    else groups.set(doc, [citation])
  }
  return [...groups].map(([doc, items]) => ({ doc, items }))
}
