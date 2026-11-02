<script setup lang="ts">
/**
 * 对话流（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>`messages` 是**按引用**传进来的：流式回答在根组件里原地改对象
 * （`text += chunk`、`trace = [...]`），任何拷贝都会让文字停在某一帧。
 */
import type { ChatMessage } from '../lib/format'
import {
  agentDuration,
  agentPhases,
  agentPlanSteps,
  agentStepText,
  agentStepTone,
  groupCitations,
} from '../lib/format'

defineProps<{
  messages: ChatMessage[]
  asking: boolean
}>()

const emit = defineEmits<{ retry: [index: number] }>()
</script>

<template>
  <section class="thread" aria-live="polite">
    <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
      <div class="msg-who">{{ m.role === 'user' ? '我' : '客服' }}</div>
      <div class="bubble" :class="{ streaming: m.pending }">
        <span v-if="m.pending && !m.text" class="typing">正在回答</span>
        <template v-else>{{ m.text }}</template>
      </div>
      <p v-if="m.note" class="fallback">{{ m.note }}</p>
      <details v-if="m.trace?.length" class="agent-run" :open="!!m.pending">
        <summary>
          <span class="agent-dot" :class="{ running: m.pending }"></span>
          <span class="agent-title">{{ m.pending ? 'Agent 正在执行' : 'Agent 执行记录' }}</span>
          <span class="agent-meta">{{ m.trace.length }} 步<template v-if="agentDuration(m.trace)"> · {{ agentDuration(m.trace) }}</template></span>
          <span class="agent-chevron">›</span>
        </summary>
        <div class="agent-body">
          <ol v-if="agentPlanSteps(m.trace).length" class="agent-plan">
            <li v-for="(item, pi) in agentPlanSteps(m.trace)" :key="pi">
              <span class="plan-dot"></span><span>{{ item }}</span>
            </li>
          </ol>
          <section v-for="phase in agentPhases(m.trace)" :key="phase.key" class="agent-phase">
            <header>
              <span class="phase-title">{{ phase.title }}</span>
              <span class="phase-hint">{{ phase.hint }}</span>
            </header>
            <ol>
              <li v-for="(step, si) in phase.steps" :key="si" :class="[step.kind, agentStepTone(step)]">
                <span class="step-dot"></span>
                <span>{{ agentStepText(step) }}</span>
              </li>
            </ol>
          </section>
          <details class="agent-raw">
            <summary>查看原始步骤</summary>
            <ol>
              <li v-for="(step, si) in m.trace" :key="si" :class="step.kind">{{ agentStepText(step) }}</li>
            </ol>
          </details>
        </div>
      </details>
      <div v-if="m.stopped || m.failed" class="msg-actions">
        <button class="link" type="button" :disabled="asking" @click="emit('retry', i)">重试</button>
        <span class="fine">问题已经留在上面，不用重新输入。</span>
      </div>
      <details v-if="m.citations?.length" class="cites">
        <summary>
          <span class="cites-title">依据 {{ m.citations.length }} 条知识</span>
          <span class="cites-meta">{{ groupCitations(m.citations).length }} 份文档 · 展开</span>
        </summary>
        <div class="cite-body">
          <section v-for="group in groupCitations(m.citations)" :key="group.doc || '未标注'" class="cite-group">
            <h4>{{ group.doc || '未标注文档' }}</h4>
            <ul>
              <li v-for="(c, j) in group.items" :key="c.ruleId || j">
                <span class="cite-h">{{ c.heading || '（未标注小节）' }}</span>
                <span v-if="c.ruleId" class="cite-rule mono">{{ c.ruleId }}</span>
              </li>
            </ul>
          </section>
          <p class="cite-foot">只列本次回答引用到的条款，不代表整份文档都已生效。</p>
        </div>
      </details>
    </div>
  </section>
</template>

<style scoped>
.thread { display: flex; flex-direction: column; gap: 14px; padding: 8px 4px 4px 0; flex: 1; min-height: 0; overflow-y: auto; }
.msg { max-width: 88%; }
.msg.user { align-self: flex-end; text-align: right; }
.msg-who { font-size: 12px; color: var(--ink-3); margin-bottom: 3px; }
.bubble {
  border: 1px solid var(--line); border-radius: 14px; padding: 9px 13px;
  white-space: pre-wrap; overflow-wrap: anywhere; background: var(--panel); text-align: left;
}
.msg.user .bubble { background: var(--teal-soft-2); border-color: var(--teal-line-2); }
.bubble.streaming::after { content: '▍'; color: var(--teal); margin-left: 2px; animation: blink 1s steps(2, start) infinite; }
@keyframes blink { 50% { opacity: 0; } }
.typing { color: var(--ink-3); }
.typing::after { content: '…'; }
.agent-run {
  margin: 8px 0 0; text-align: left; border: 1px solid var(--line);
  border-radius: 12px; background: var(--panel); font-size: 12px; overflow: hidden;
}
.agent-run summary {
  cursor: pointer; color: var(--ink-2); display: flex; align-items: center; gap: 8px;
  user-select: none; list-style: none; padding: 10px 12px;
}
.agent-run summary::-webkit-details-marker { display: none; }
.agent-run[open] summary { border-bottom: 1px solid var(--line); }
.agent-dot {
  width: 8px; height: 8px; border-radius: 50%; background: var(--teal);
  box-shadow: 0 0 0 3px var(--teal-soft); flex: none;
}
.agent-dot.running { animation: pulse 1.2s ease-in-out infinite; }
@keyframes pulse { 50% { box-shadow: 0 0 0 6px rgba(15, 118, 110, .12); } }
.agent-title { color: var(--ink); font-weight: 600; }
.agent-meta { color: var(--ink-3); font-size: 11px; }
.agent-chevron { margin-left: auto; color: var(--ink-3); transition: transform .15s ease; }
.agent-run[open] .agent-chevron { transform: rotate(90deg); }
.agent-body { padding: 10px 12px 12px; display: grid; gap: 12px; }
.agent-plan {
  list-style: none; margin: 0; padding: 8px 10px; display: grid; gap: 5px;
  background: var(--teal-wash); border: 1px solid var(--teal-line-2); border-radius: 9px;
}
.agent-plan li { display: flex; align-items: flex-start; gap: 7px; color: var(--ink-2); line-height: 1.45; }
.plan-dot {
  width: 5px; height: 5px; border-radius: 50%; background: var(--teal); flex: none; margin-top: 6px;
}
.agent-phase header { display: flex; align-items: baseline; gap: 8px; margin: 0 0 5px; }
.phase-title { color: var(--ink); font-weight: 600; font-size: 12px; }
.phase-hint { color: var(--ink-3); font-size: 11px; }
.agent-phase ol { list-style: none; margin: 0; padding: 0 0 0 3px; display: grid; gap: 4px; }
.agent-phase li { display: flex; align-items: flex-start; gap: 8px; color: var(--ink-2); line-height: 1.45; }
.step-dot {
  width: 5px; height: 5px; border-radius: 50%; background: var(--teal-line); flex: none; margin-top: 6px;
}
.agent-phase li.ok .step-dot { background: var(--green); }
.agent-phase li.warn .step-dot { background: var(--amber); }
.agent-phase li.danger .step-dot { background: var(--rose); }
.agent-raw { border-top: 1px dashed var(--line); padding-top: 8px; }
.agent-raw summary { cursor: pointer; color: var(--ink-3); font-size: 11px; padding: 0; border: none; }
.agent-raw ol { list-style: none; margin: 6px 0 0; padding: 0 0 0 12px; display: grid; gap: 4px; border-left: 1px dashed var(--teal-line-2); }
.agent-raw li { position: relative; color: var(--ink-3); line-height: 1.45; }
.agent-raw li::before {
  content: ''; position: absolute; left: -16px; top: 6px; width: 4px; height: 4px;
  border-radius: 50%; background: var(--teal-line);
}
.msg-actions { display: flex; align-items: center; gap: 10px; margin-top: 6px; text-align: left; }
.msg-actions .fine { margin: 0; }
.fallback {
  color: var(--amber-ink); background: var(--amber-soft); border: 1px solid var(--amber-line);
  border-radius: 10px; padding: 8px 10px; font-size: 13px; margin: 8px 0 0;
  text-align: left;
}
.cites {
  margin: 8px 0 0; text-align: left; font-size: 13px;
  border: 1px solid var(--line); border-radius: 12px; background: var(--teal-wash);
  overflow: hidden;
}
.cites summary {
  cursor: pointer; list-style: none; padding: 9px 12px;
  display: flex; align-items: baseline; gap: 8px; color: var(--ink-2);
}
.cites summary::-webkit-details-marker { display: none; }
.cites-title { font-weight: 600; color: var(--ink); }
.cites-meta { color: var(--ink-3); font-size: 12px; margin-left: auto; }
.cites[open] summary { border-bottom: 1px solid var(--teal-line-2); }
.cite-body { padding: 10px 12px 12px; display: grid; gap: 10px; }
.cite-group h4 {
  margin: 0 0 4px; font-size: 12px; font-weight: 600; color: var(--ink-3);
  font-family: var(--mono); overflow-wrap: anywhere;
}
.cite-group ul { margin: 0; padding-left: 16px; display: grid; gap: 3px; }
.cite-group li { color: var(--ink-2); }
.cite-rule { color: var(--ink-3); margin-left: 6px; font-size: 11px; }
.cite-foot { margin: 0; color: var(--ink-3); font-size: 11px; }

@media (max-width: 960px) {
  .thread { max-height: 62vh; }
}

@media (max-width: 640px) {
  .msg { max-width: 96%; }
}
</style>
