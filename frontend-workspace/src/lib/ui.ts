/**
 * 工作台的页面级枚举与表单形状（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>放这里的原因只有一个：这些类型要被根组件和某个子组件同时引用，
 * 而它们不是接口契约（契约在 `types.ts` / `api.ts`）。放在任一侧都会让另一侧
 * 反向 import 组件，所以独立成一个没有运行时内容的模块。
 */

/** 工作台的五个视图（ADR 0073）。 */
export type WorkspaceView = 'queue' | 'refunds' | 'quality' | 'channels' | 'conversations'

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
