<script setup lang="ts">
/**
 * 知识飞轮（round44 票 190 / ADR 0078）。
 *
 * <p>这条视图是 round43 反向沉淀链的**可视化面**：点踩 → 复核 → 附稿 → 发布 → 重跑 ingest。
 * 之前五个口都在后端开好了（票 186），前端一个都没调——能力在，产品面看不见。
 *
 * <p>三条口径写死在画面上，不靠注释：
 * 1. **发布不等于生效**。发布只把修订行置成 PUBLISHED，进检索语料要重跑 ingest（全量重建 + 推进纪元）。
 *    所以发布成功给的是「还需重跑 ingest」，不是「已上线」。
 * 2. **稿件由人写**。复核只是「确认要改」，系统不生成、不润色正文（ADR 0039 红线）。
 * 3. **操作人只在审计日志里**。`RevisionView` 不含 actor，页面只标得出本次会话里当前账号做的动作。
 *
 * <p>状态仍在 `App.vue`：表单草稿与队列都是根上的 ref，这一层只画与发事件。
 */
import { computed } from 'vue'
import { countLabel } from 'frontend-shared'
import type { FeedbackItem, KnowledgeRevision, RevisionDraft } from '../api'
import { formatTime } from '../lib/format'
import {
  DOMAINS,
  INTENT_LABEL,
  POLICY_INTENTS,
  REVISION_STATUS_LABEL,
  SCOPES,
} from '../lib/ui'

const draft = defineModel<RevisionDraft>('draft', { required: true })

const props = defineProps<{
  feedback: FeedbackItem[]
  feedbackLoading: boolean
  feedbackError: string
  /** 本次会话里复核完成的反馈——队列刷新后它们就出队了，不留一份就断在「没法附稿」。 */
  reviewed: FeedbackItem[]
  /** 正在附稿的那条反馈 id；空串表示表单收起。 */
  draftFeedbackId: string
  revisions: KnowledgeRevision[]
  revisionsLoading: boolean
  revisionsError: string
  actionNote: string
  actionError: string
  /** 与根上的动作锁同一个 ref：`kb:<动作>:<id>`。 */
  inFlight: string
}>()

const emit = defineEmits<{
  refresh: []
  review: [feedbackId: string]
  pickFeedback: [feedbackId: string]
  cancelDraft: []
  file: []
  publish: [revisionId: string]
  reject: [revisionId: string]
}>()

const drafts = computed(() => props.revisions.filter((r) => r.status === 'DRAFT'))
const published = computed(() => props.revisions.filter((r) => r.status === 'PUBLISHED'))

/** 五步各自的一条计数；「进入语料」那步没有数字可报，它等的是重跑 ingest。 */
const steps = computed(() => [
  { key: 'feedback', label: '点踩反馈', value: countLabel(props.feedback.length), hint: '待复核' },
  { key: 'review', label: '人工复核', value: countLabel(props.reviewed.length), hint: '本次会话' },
  { key: 'draft', label: '附修订稿', value: countLabel(drafts.value.length), hint: '待发布' },
  { key: 'publish', label: '发布', value: countLabel(published.value.length), hint: '已发布' },
  { key: 'ingest', label: '进入语料', value: 'ingest', hint: '需重跑' },
])

/** 提交前只拦「必填没填」，不替后端判合法（400 的原文照显）。 */
const missing = computed(() => {
  if (!props.draftFeedbackId) return ''
  if (!draft.value.title.trim()) return '标题必填'
  if (!draft.value.body.trim()) return '正文必填：这一稿会原样进入语料，系统不改写'
  return ''
})

const canFile = computed(() => Boolean(props.draftFeedbackId) && !missing.value && !props.inFlight)

function reviewing(id: string): boolean {
  return props.inFlight === `kb:review:${id}`
}

function filing(): boolean {
  return props.inFlight === 'kb:file'
}

function toggling(id: string, verb: 'publish' | 'reject'): boolean {
  return props.inFlight === `kb:${verb}:${id}`
}

function statusOf(revision: KnowledgeRevision): string {
  return REVISION_STATUS_LABEL[revision.status] ?? revision.status
}

function intentOf(value: string): string {
  return INTENT_LABEL[value] ?? value
}

function scopeOf(value: string): string {
  return value === 'PLATFORM' ? '平台通用' : '本店专用'
}
</script>

<template>
  <section class="kb">
    <header class="kb-head">
      <div>
        <p class="eyebrow">知识飞轮</p>
        <h2>点踩 → 复核 → 附稿 → 发布 → 重跑 ingest</h2>
        <p class="lede">
          买家点踩的答案进复核队列；人工确认后附一版修订稿，发布只是把它标成「可入库」。
          <strong>发布不立即生效</strong>——要重跑 ingest 才会进入检索语料。
        </p>
      </div>
      <button class="btn ghost" type="button" :disabled="feedbackLoading || revisionsLoading" @click="emit('refresh')">
        {{ feedbackLoading || revisionsLoading ? '读取中…' : '刷新' }}
      </button>
    </header>

    <ol class="kb-flow" aria-label="知识飞轮五个环节">
      <li v-for="step in steps" :key="step.key" class="flow-step" :class="step.key">
        <span class="flow-label">{{ step.label }}</span>
        <strong class="flow-value">{{ step.value }}</strong>
        <span class="flow-hint">{{ step.hint }}</span>
      </li>
    </ol>

    <p v-if="actionError" class="err">{{ actionError }}</p>
    <p v-if="actionNote" class="note">{{ actionNote }}</p>

    <!-- ① 复核队列 -------------------------------------------------------------- -->
    <section class="panel review-panel">
      <header class="panel-head">
        <h3>待复核 <span class="count">{{ feedback.length }}</span></h3>
        <span class="fine">biz-mock 侧 PENDING 且答案为 DOWN 的反馈</span>
      </header>
      <p v-if="feedbackLoading" class="empty">正在读取复核队列…</p>
      <p v-else-if="feedbackError" class="err">{{ feedbackError }}</p>
      <p v-else-if="!feedback.length" class="empty">
        队列是空的：买家点踩且当次答案判为不达标，才会进来。
      </p>
      <ul v-else class="rows">
        <li v-for="item in feedback" :key="item.id" class="row review-row">
          <div class="row-top">
            <strong class="mono">{{ item.id }}</strong>
            <span class="pill warn">{{ item.verdict }}</span>
            <time>{{ formatTime(item.createdAt) }}</time>
          </div>
          <p class="row-reason">{{ item.reason || '（买家没有写明原因）' }}</p>
          <dl class="kv">
            <template v-if="item.ruleIds">
              <dt>命中条款</dt><dd class="mono">{{ item.ruleIds }}</dd>
            </template>
            <template v-if="item.signals">
              <dt>信号</dt><dd class="mono">{{ item.signals }}</dd>
            </template>
            <template v-if="item.ticketId">
              <dt>关联工单</dt><dd class="mono">{{ item.ticketId }}</dd>
            </template>
          </dl>
          <button class="btn" type="button" :disabled="!!inFlight" @click="emit('review', item.id)">
            {{ reviewing(item.id) ? '复核中…' : '标为已复核' }}
          </button>
        </li>
      </ul>
    </section>

    <!-- ② 已复核 → 附稿 --------------------------------------------------------- -->
    <section class="panel draft-panel">
      <header class="panel-head">
        <h3>已复核 · 可附稿 <span class="count">{{ reviewed.length }}</span></h3>
        <span class="fine">稿件由人写：系统不生成、不润色正文（ADR 0039）</span>
      </header>
      <p v-if="!reviewed.length" class="empty">
        本次会话还没有复核记录。上面复核一条，它就会落到这里——刷新队列不会把它弄丢。
      </p>
      <ul v-else class="rows">
        <li v-for="item in reviewed" :key="item.id" class="row">
          <div class="row-top">
            <strong class="mono">{{ item.id }}</strong>
            <span class="pill ok">已复核</span>
            <time>{{ formatTime(item.createdAt) }}</time>
          </div>
          <p class="row-reason">{{ item.reason || '（买家没有写明原因）' }}</p>
          <button
            v-if="draftFeedbackId !== item.id"
            class="btn ghost"
            type="button"
            :disabled="!!inFlight"
            @click="emit('pickFeedback', item.id)"
          >
            给这条附稿
          </button>
          <span v-else class="tag-active">正在附稿</span>
        </li>
      </ul>

      <form v-if="draftFeedbackId" class="draft-form" @submit.prevent="emit('file')">
        <header class="draft-head">
          <strong>附稿到 <code class="mono">{{ draftFeedbackId }}</code></strong>
          <button class="link" type="button" :disabled="!!inFlight" @click="emit('cancelDraft')">收起</button>
        </header>
        <label>
          标题
          <input v-model="draft.title" type="text" placeholder="例如：七天无理由退货的适用范围" />
        </label>
        <label>
          正文
          <textarea v-model="draft.body" rows="6" placeholder="写清适用条件、时限与例外。这一段会成为检索语料。"></textarea>
        </label>
        <div class="draft-grid">
          <label>
            政策意图
            <select v-model="draft.intent">
              <option v-for="intent in POLICY_INTENTS" :key="intent" :value="intent">{{ intentOf(intent) }}</option>
            </select>
          </label>
          <label>
            行业域
            <select v-model="draft.domain">
              <option v-for="domain in DOMAINS" :key="domain" :value="domain">{{ domain }}</option>
            </select>
          </label>
          <label>
            作用范围
            <select v-model="draft.scope">
              <option v-for="scope in SCOPES" :key="scope" :value="scope">{{ scopeOf(scope) }}</option>
            </select>
          </label>
        </div>
        <p v-if="missing" class="err">{{ missing }}</p>
        <button class="btn primary" type="submit" :disabled="!canFile">
          {{ filing() ? '提交中…' : '提交草稿' }}
        </button>
      </form>
    </section>

    <!-- ③ 修订与发布 ------------------------------------------------------------ -->
    <section class="panel revision-panel">
      <header class="panel-head">
        <h3>修订 <span class="count">{{ revisions.length }}</span></h3>
        <span class="fine">草稿可发布或驳回；发布后仍需 ingest</span>
      </header>
      <p v-if="revisionsLoading" class="empty">正在读取修订…</p>
      <p v-else-if="revisionsError" class="err">{{ revisionsError }}</p>
      <p v-else-if="!revisions.length" class="empty">本店还没有修订稿。</p>
      <ul v-else class="revision-list">
        <li v-for="revision in revisions" :key="revision.id" class="revision-card" :class="revision.status.toLowerCase()">
          <header class="rev-head">
            <strong>{{ revision.title }}</strong>
            <span class="pill" :class="revision.status.toLowerCase()">{{ statusOf(revision) }}</span>
          </header>
          <p class="rev-meta">
            <span class="mono">{{ revision.id }}</span>
            <span>来源反馈 <span class="mono">{{ revision.feedbackId }}</span></span>
            <span>{{ intentOf(revision.intent) }}</span>
            <span>{{ revision.domain }}</span>
            <span>{{ scopeOf(revision.scope) }}</span>
          </p>
          <details class="rev-body">
            <summary>查看正文</summary>
            <pre>{{ revision.body }}</pre>
          </details>
          <ol class="rev-path">
            <li class="done">
              <span class="dot"></span>附稿
              <time>{{ formatTime(revision.createdAt) }}</time>
            </li>
            <li v-if="revision.publishedAt" class="done">
              <span class="dot"></span>发布
              <time>{{ formatTime(revision.publishedAt) }}</time>
              <span class="who">当前账号</span>
            </li>
            <li v-else-if="revision.rejectedAt" class="stopped">
              <span class="dot"></span>驳回
              <time>{{ formatTime(revision.rejectedAt) }}</time>
            </li>
            <li v-else class="waiting">
              <span class="dot"></span>待发布
            </li>
          </ol>
          <div v-if="revision.status === 'DRAFT'" class="rev-actions">
            <button class="btn primary" type="button" :disabled="!!inFlight" @click="emit('publish', revision.id)">
              {{ toggling(revision.id, 'publish') ? '发布中…' : '发布' }}
            </button>
            <button class="btn ghost" type="button" :disabled="!!inFlight" @click="emit('reject', revision.id)">
              {{ toggling(revision.id, 'reject') ? '驳回中…' : '驳回' }}
            </button>
          </div>
          <p v-if="revision.status === 'PUBLISHED'" class="ingest-note">
            已发布，但<strong>不立即生效</strong>：需重跑 ingest 全量重建才会进入检索语料并推进纪元
            （<code class="mono">pwsh -File scripts/ingest.ps1</code>）。
          </p>
        </li>
      </ul>
    </section>
  </section>
</template>

<style scoped>
.kb { display: grid; gap: 14px; }
.kb-head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 16px;
  background: var(--panel); border: 1px solid var(--line); border-radius: 16px;
  padding: 18px 20px; box-shadow: 0 8px 28px rgba(20, 22, 26, .05);
}
.kb-head h2 { margin: 2px 0 8px; font-size: 19px; line-height: 1.35; }
.kb-head .lede { margin: 0; max-width: 620px; color: var(--ink-2); font-size: 13px; line-height: 1.7; }
.kb-head .lede strong { color: var(--ink); }

.kb-flow {
  list-style: none; margin: 0; padding: 0;
  display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px;
}
.flow-step {
  position: relative;
  display: grid; gap: 2px; padding: 12px 14px;
  background: var(--panel); border: 1px solid var(--line); border-radius: 12px;
}
.flow-step + .flow-step::before {
  content: '›'; position: absolute; left: -9px; top: 50%; transform: translateY(-50%);
  color: var(--ink-3); font-size: 15px;
}
.flow-step.ingest { background: var(--teal-wash); border-color: var(--teal-line-2); }
.flow-label { color: var(--ink-3); font-size: 12px; }
.flow-value { font-size: 20px; line-height: 1.2; }
.flow-step.ingest .flow-value { font-size: 15px; color: var(--teal); }
.flow-hint { color: var(--ink-3); font-size: 11px; }

.panel {
  background: var(--panel); border: 1px solid var(--line); border-radius: 16px;
  padding: 16px 18px 18px; box-shadow: 0 8px 28px rgba(20, 22, 26, .04);
}
.panel-head {
  display: flex; align-items: baseline; justify-content: space-between; gap: 12px;
  flex-wrap: wrap; margin-bottom: 10px;
}
.panel-head h3 { margin: 0; font-size: 15px; }
.panel-head .count {
  margin-left: 6px; padding: 0 7px; border-radius: 999px;
  background: var(--teal-soft-2); color: var(--teal); font-size: 12px;
}
.rows { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
.row {
  border: 1px solid var(--line); border-radius: 12px; padding: 12px;
  display: grid; gap: 8px; justify-items: start;
}
.row-top { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; width: 100%; }
.row-top strong { font-size: 13px; }
.row-top time { margin-left: auto; color: var(--ink-3); font-size: 12px; }
.row-reason { margin: 0; color: var(--ink); font-size: 13px; overflow-wrap: anywhere; }
.kv { margin: 0; display: grid; grid-template-columns: auto 1fr; gap: 2px 10px; font-size: 12px; }
.kv dt { color: var(--ink-3); }
.kv dd { margin: 0; color: var(--ink-2); overflow-wrap: anywhere; }
.tag-active { color: var(--teal); font-size: 12px; }

.draft-form {
  margin-top: 12px; padding: 14px; border-radius: 12px;
  background: var(--teal-wash); border: 1px solid var(--teal-line-2);
  display: grid; gap: 10px;
}
.draft-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; font-size: 13px; }
.draft-form label { display: grid; gap: 4px; font-size: 12px; color: var(--ink-2); }
.draft-form input, .draft-form textarea, .draft-form select { width: 100%; box-sizing: border-box; }
.draft-form textarea { resize: vertical; font-family: inherit; }
.draft-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }

.revision-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
.revision-card {
  border: 1px solid var(--line); border-left: 3px solid var(--teal-line);
  border-radius: 12px; padding: 12px 14px; display: grid; gap: 8px;
}
.revision-card.draft { border-left-color: var(--amber); }
.revision-card.published { border-left-color: var(--green); }
.revision-card.rejected { border-left-color: var(--rose); }
.rev-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.rev-head strong { font-size: 14px; overflow-wrap: anywhere; }
.rev-meta {
  display: flex; flex-wrap: wrap; gap: 4px 12px; margin: 0;
  color: var(--ink-3); font-size: 12px;
}
.rev-body summary { cursor: pointer; color: var(--ink-3); font-size: 12px; }
.rev-body pre {
  margin: 8px 0 0; padding: 10px; border-radius: 10px; background: var(--bg);
  white-space: pre-wrap; overflow-wrap: anywhere; font-family: inherit;
  font-size: 13px; line-height: 1.7; color: var(--ink-2);
}
.rev-path { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: 6px 16px; font-size: 12px; }
.rev-path li { display: flex; align-items: center; gap: 6px; color: var(--ink-3); }
.rev-path .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--line); }
.rev-path .done { color: var(--ink-2); }
.rev-path .done .dot { background: var(--teal); }
.rev-path .stopped .dot { background: var(--rose); }
.rev-path .waiting .dot { background: var(--amber); }
.rev-path .who { color: var(--teal); font-size: 11px; }
.rev-actions { display: flex; gap: 8px; }
.ingest-note {
  margin: 0; padding: 8px 10px; border-radius: 10px;
  background: var(--amber-soft); border: 1px solid var(--amber-line);
  color: var(--amber-ink); font-size: 12px; line-height: 1.6;
}

@media (max-width: 960px) {
  .kb-flow { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .flow-step + .flow-step::before { content: none; }
  .draft-grid { grid-template-columns: 1fr; }
}

@media (max-width: 640px) {
  .kb-head { flex-direction: column; }
  .kb-flow { grid-template-columns: 1fr; }
  .panel { padding: 14px; }
}
</style>
