/**
 * 工作台的页面级枚举与表单形状（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>放这里的原因只有一个：这些类型要被根组件和某个子组件同时引用，
 * 而它们不是接口契约（契约在 `types.ts` / `api.ts`）。放在任一侧都会让另一侧
 * 反向 import 组件，所以独立成一个没有运行时内容的模块。
 */

/** 工作台的六个视图（ADR 0073；round44 票 190 增「知识」）。 */
export type WorkspaceView = 'queue' | 'refunds' | 'quality' | 'channels' | 'conversations' | 'knowledge'

/** 队列头部的状态筛选；与后端的状态无关，是纯前端过滤。 */
export type StatusFilter = 'ALL' | 'OPEN' | 'ASSIGNED' | 'OVERDUE'

/** 飞书连接表单。密钥字段留空表示沿用当前值，所以不预填。 */
export interface ChannelForm {
  enabled: boolean
  appId: string
  appSecret: string
  tenant: string
  customer: string
  identityFile: string
}

/**
 * 附稿下拉的选项（round44 票 190）。
 *
 * <p>与 `KnowledgeRevisionService.POLICY_INTENTS` / `SCOPES` 同源，但**不是**前端复刻的校验器：
 * 这里只把可选值列出来（下拉框本来就只能选这些），不判「这版稿子合不合法」——
 * 那件事由后端 `validateDraft` 回答，400 的原文直接显示在页面上。
 */
export const POLICY_INTENTS = [
  'POLICY_RETURN',
  'POLICY_SHIPPING',
  'POLICY_PROMO',
  'POLICY_FRESH',
  'POLICY_HOTEL',
] as const

/** 作用域：本店专用 / 平台通用。 */
export const SCOPES = ['SHOP', 'PLATFORM'] as const

/** 行业域（`Domain` 枚举里可写入的两个；`UNKNOWN` 是未登记租户的兜底，不作为稿件选项）。 */
export const DOMAINS = ['ECOMMERCE', 'HOTEL'] as const

/** 意图与状态的可读名。未知值原样透出，不吞。 */
export const INTENT_LABEL: Record<string, string> = {
  POLICY_RETURN: '退货政策',
  POLICY_SHIPPING: '物流政策',
  POLICY_PROMO: '促销政策',
  POLICY_FRESH: '生鲜理赔',
  POLICY_HOTEL: '酒店政策',
}

export const REVISION_STATUS_LABEL: Record<string, string> = {
  DRAFT: '待发布',
  PUBLISHED: '已发布',
  REJECTED: '已驳回',
}
