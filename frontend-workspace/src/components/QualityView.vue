<script setup lang="ts">
/**
 * 编排质量视图（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>只读旁路报表（ADR 0073）：这里没有任何干预动作，所以组件也只发一个 `refresh`。
 */
import type { QualityReport } from '../api'
import { formatTime, ratioText, secondsText } from '../lib/format'

defineProps<{
  quality: QualityReport | null
  qualityLoading: boolean
  qualityError: string
  blockedReasonEntries: Array<[string, number]>
  auditActionEntries: Array<[string, number]>
  handoffContextLossCount: number
}>()

const emit = defineEmits<{ refresh: [] }>()
</script>

<template>
  <section class="view-panel quality-panel">
    <div class="pane-head">
      <h2>编排质量</h2>
      <button class="ghost" type="button" :disabled="qualityLoading" @click="emit('refresh')">
        {{ qualityLoading ? '读取中…' : '刷新报表' }}
      </button>
    </div>
    <p class="hint">只读旁路报表：Handoff、工具风险与摩擦模式来自本进程累计指标；坐席动作来自审计流最近 15 分钟。这里不做任何干预动作。</p>

    <p v-if="qualityError" class="err">{{ qualityError }}</p>
    <p v-if="qualityLoading" class="empty">正在读取编排质量报表…</p>

    <template v-else-if="quality">
      <div class="quality-kpis">
        <article class="quality-kpi">
          <span>请求 / 降级</span>
          <strong>{{ quality.requests.total }} / {{ quality.requests.fallbacks }}</strong>
          <small>降级率 {{ ratioText(quality.requests.fallbackRatio) }}</small>
        </article>
        <article class="quality-kpi">
          <span>Handoff 允许 / 拒绝</span>
          <strong>{{ quality.handoff.allowed }} / {{ quality.handoff.blocked }}</strong>
          <small>拒绝率 {{ ratioText(quality.handoff.blockedRatio) }}</small>
        </article>
        <article class="quality-kpi">
          <span>决策耗时</span>
          <strong>{{ secondsText(quality.handoff.decisionSeconds.meanSeconds) }}</strong>
          <small>最大 {{ secondsText(quality.handoff.decisionSeconds.maxSeconds) }}</small>
        </article>
        <article class="quality-kpi danger-kpi">
          <span>上下文丢失</span>
          <strong>{{ handoffContextLossCount }}</strong>
          <small>Handoff 后重复追问同一槽位</small>
        </article>
      </div>

      <div class="quality-grid">
        <section class="quality-block">
          <h3>Blocked 原因分布</h3>
          <ul v-if="blockedReasonEntries.length" class="distribution">
            <li v-for="[reason, count] in blockedReasonEntries" :key="reason">
              <span class="mono">{{ reason }}</span><strong>{{ count }}</strong>
            </li>
          </ul>
          <p v-else class="empty">还没有被拒绝的 Handoff</p>
        </section>

        <section class="quality-block">
          <h3>摩擦模式</h3>
          <ul class="distribution">
            <li><span>工具成功后仍降级</span><strong>{{ quality.friction.toolSuccessFallbacks }}</strong></li>
            <li><span>重复追问槽位</span><strong>{{ quality.friction.repeatedSlotAsks }}</strong></li>
            <li><span>Handoff 上下文丢失</span><strong>{{ handoffContextLossCount }}</strong></li>
          </ul>
          <p v-if="quality.friction.repeatedSlotAsksByPhase.length" class="fine">
            已按 same_role / cross_handoff 分相统计；只有 cross_handoff + sameSlot 会进入告警。
          </p>
        </section>

        <section class="quality-block">
          <h3>工具风险面</h3>
          <table v-if="quality.toolRisk.rows.length">
            <thead><tr><th>工具</th><th>风险</th><th>HITL</th><th>角色</th><th>次数</th></tr></thead>
            <tbody>
              <tr v-for="(row, index) in quality.toolRisk.rows" :key="index">
                <td class="mono">{{ String(row.tool ?? '—') }}</td>
                <td>{{ String(row.risk ?? '—') }}</td>
                <td>{{ String(row.hitl ?? '—') }}</td>
                <td>{{ String(row.role ?? '—') }}</td>
                <td>{{ row.count }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else class="empty">还没有工具尝试</p>
        </section>

        <section class="quality-block">
          <h3>最近坐席动作</h3>
          <p v-if="!quality.audit.available" class="empty">审计流暂时读不到；指标部分仍可读。</p>
          <ul v-else-if="auditActionEntries.length" class="distribution">
            <li v-for="[action, count] in auditActionEntries" :key="action">
              <span class="mono">{{ action }}</span><strong>{{ count }}</strong>
            </li>
          </ul>
          <p v-else class="empty">最近 {{ quality.audit.windowMinutes ?? 15 }} 分钟没有坐席留痕动作</p>
        </section>
      </div>
      <p class="fine">数据时间：{{ formatTime(quality.generatedAt) }} · 口径：{{ quality.scope }}</p>
    </template>
    <p v-else class="empty">点击“刷新报表”读取编排质量数据。</p>
  </section>
</template>

<style scoped>
.quality-kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.quality-kpi {
  background: var(--panel); border: 1px solid var(--line); border-radius: 14px;
  padding: 14px; display: grid; gap: 4px;
}
.quality-kpi span { color: var(--ink-3); font-size: 12px; }
.quality-kpi strong { font-size: 24px; line-height: 1.15; }
.quality-kpi small { color: var(--ink-3); font-size: 12px; }
.danger-kpi strong { color: var(--rose); }
.quality-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.quality-block { background: var(--panel); border: 1px solid var(--line); border-radius: 14px; padding: 14px; }
.quality-block h3 { margin: 0 0 10px; font-size: 13px; }
.distribution { list-style: none; margin: 0; padding: 0; display: grid; gap: 7px; }
.distribution li {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 7px 8px; background: var(--bg); border-radius: 8px; font-size: 12px;
}
.distribution span { color: var(--ink-2); overflow-wrap: anywhere; }
.distribution strong { color: var(--ink); }

@media (max-width: 960px) {
  .quality-kpis, .quality-grid { grid-template-columns: 1fr; }
}

@media (max-width: 640px) {
  .quality-block { overflow-x: auto; }
}
</style>
