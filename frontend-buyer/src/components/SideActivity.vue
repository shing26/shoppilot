<script setup lang="ts">
/**
 * 侧栏「最近订单 / 最近工单」（round44 票 188 从 `App.vue` 拆出）。
 *
 * <p>只读展示 + 发事件：刷新、切换标签、点订单去问、点工单开抽屉都由 `App.vue` 执行——
 * 数据拉取与持久化状态不在这一层。
 */
import { computed } from 'vue'
import { countLabel } from 'frontend-shared'
import type { MyOrder, OrderDetail, OrderLogistics } from '../api'
import { PRIORITY_LABEL, STATUS_LABEL, type Ticket } from '../types'
import { ORDER_STATUS_LABEL, formatTime, slaText } from '../lib/format'
import OrderCard from './OrderCard.vue'

const sideTab = defineModel<'orders' | 'tickets'>('sideTab', { required: true })
const showAllOrders = defineModel<boolean>('showAllOrders', { required: true })
const showAllTickets = defineModel<boolean>('showAllTickets', { required: true })

const props = defineProps<{
  orders: MyOrder[]
  tickets: Ticket[]
  ordersLoading: boolean
  ticketsLoading: boolean
  orderError: string
  ticketError: string
  now: number
  /** 展开中的订单号；空串表示全部收起。详情数据由 `App.vue` 拉取后下传。 */
  expandedOrderNo: string
  orderDetail: OrderDetail | null
  orderLogistics: OrderLogistics | null
  orderDetailLoading: boolean
  orderDetailError: string
  /** 本次对话识别到的订单号；用于「本次涉及的订单」上下文卡。 */
  orderContextNo: string
  /** 会话号、买家与店铺用于侧栏底部的会话信息，不参与任何请求。 */
  conversationId: string
  buyerName: string
  storeLabel: string
}>()

const emit = defineEmits<{
  refreshOrders: []
  refreshTickets: []
  toggleOrder: [orderNo: string]
  askOrder: [order: MyOrder]
  openTicket: [ticket: Ticket]
}>()

// P0 侧栏：默认只露出 2 条，避免工单历史把聊天淹没。
const visibleOrders = computed(() => (showAllOrders.value ? props.orders : props.orders.slice(0, 2)))
const visibleTickets = computed(() => (showAllTickets.value ? props.tickets : props.tickets.slice(0, 2)))
const contextOrder = computed(() =>
  props.orderContextNo ? props.orders.find((order) => order.orderNo === props.orderContextNo) ?? null : null,
)

function pickTab(tab: 'orders' | 'tickets') {
  sideTab.value = tab
}

function toggleOrders() {
  showAllOrders.value = !showAllOrders.value
}

function toggleTickets() {
  showAllTickets.value = !showAllTickets.value
}
</script>

<template>
  <section class="side-card side-activity">
    <div class="side-tabs" role="tablist" aria-label="订单与工单">
      <button
        class="side-tab"
        :class="{ active: sideTab === 'orders' }"
        type="button"
        role="tab"
        :aria-selected="sideTab === 'orders'"
        @click="pickTab('orders')"
      >
        订单<span class="tab-count">{{ countLabel(orders.length) }}</span>
      </button>
      <button
        class="side-tab"
        :class="{ active: sideTab === 'tickets' }"
        type="button"
        role="tab"
        :aria-selected="sideTab === 'tickets'"
        @click="pickTab('tickets')"
      >
        工单<span class="tab-count">{{ countLabel(tickets.length) }}</span>
      </button>
    </div>

    <template v-if="sideTab === 'orders'">
      <div class="section-head">
        <h2>本次涉及的订单</h2>
        <span class="section-note">来自对话上下文</span>
      </div>
      <div v-if="contextOrder" class="context-order">
        <div class="context-top">
          <strong class="mono">{{ contextOrder.orderNo }}</strong>
          <span class="pill">{{ ORDER_STATUS_LABEL[contextOrder.status] ?? contextOrder.status }}</span>
        </div>
        <p class="context-meta">
          {{ contextOrder.categoryName }}
          <span>¥{{ (contextOrder.amountFen / 100).toFixed(2) }}</span>
        </p>
        <button class="link context-link" type="button" @click="emit('toggleOrder', contextOrder.orderNo)">
          查看订单详情与物流 →
        </button>
      </div>
      <p v-else class="context-empty">
        本次对话还没有关联订单。展开任一订单后提问，客服会带上这单的上下文。
      </p>

      <div class="section-head orders-head">
        <h2>全部订单</h2>
        <button class="link" type="button" @click="emit('refreshOrders')">刷新</button>
      </div>
      <p v-if="orderError" class="err">{{ orderError }}</p>
      <p v-else-if="ordersLoading" class="empty">正在读取订单…</p>
      <div v-else-if="orders.length" class="order-list">
        <OrderCard
          v-for="order in visibleOrders"
          :key="order.orderNo"
          :order="order"
          :expanded="expandedOrderNo === order.orderNo"
          :detail="expandedOrderNo === order.orderNo ? orderDetail : null"
          :logistics="expandedOrderNo === order.orderNo ? orderLogistics : null"
          :loading="expandedOrderNo === order.orderNo && orderDetailLoading"
          :error="expandedOrderNo === order.orderNo ? orderDetailError : ''"
          @toggle="emit('toggleOrder', order.orderNo)"
          @ask="emit('askOrder', order)"
        />
      </div>
      <p v-else class="empty">你还没有订单</p>
      <button v-if="orders.length > 2" class="link side-more" type="button" @click="toggleOrders">
        {{ showAllOrders ? '收起' : '查看全部订单' }}
      </button>
    </template>

    <template v-else>
      <div class="section-head">
        <h2>售后进度</h2>
        <button class="link" type="button" @click="emit('refreshTickets')">刷新</button>
      </div>
      <p v-if="ticketError" class="err">{{ ticketError }}</p>
      <p v-else-if="ticketsLoading" class="empty">正在读取…</p>
      <div v-else-if="tickets.length" class="ticket-list">
        <article
          v-for="t in visibleTickets"
          :key="t.id"
          class="ticket-card clickable"
          role="button"
          tabindex="0"
          @click="emit('openTicket', t)"
          @keyup.enter="emit('openTicket', t)"
        >
          <div class="ticket-top">
            <strong class="mono">{{ t.id }}</strong>
            <span class="pill">{{ STATUS_LABEL[t.status] ?? t.status }}</span>
          </div>
          <p class="ticket-query">{{ t.userQuery }}</p>
          <div class="ticket-timeline">
            <span class="step done">已提交 <time>{{ formatTime(t.createdAt) }}</time></span>
            <span v-if="t.escalatedAt" class="step warn">已超时升级 <time>{{ formatTime(t.escalatedAt) }}</time></span>
            <span class="step" :class="{ done: t.status === 'RESOLVED' }">
              {{ STATUS_LABEL[t.status] ?? t.status }}<template v-if="t.status !== 'RESOLVED'"> · {{ slaText(t, now) }}</template>
            </span>
          </div>
          <div class="ticket-meta">
            <span>{{ PRIORITY_LABEL[t.priority ?? 'normal'] ?? t.priority }}</span>
            <span>{{ t.assignee ?? '待分配' }}</span>
            <span class="ticket-open">查看进度 →</span>
          </div>
        </article>
      </div>
      <p v-else class="empty">你还没有需要人工处理的工单</p>
      <button v-if="tickets.length > 2" class="link side-more" type="button" @click="toggleTickets">
        {{ showAllTickets ? '收起' : '查看全部工单' }}
      </button>
    </template>

    <section class="session-info">
      <h2>会话信息</h2>
      <dl>
        <div><dt>会话号</dt><dd class="mono">{{ conversationId || '提问后生成' }}</dd></div>
        <div><dt>买家</dt><dd>{{ buyerName || '未登录' }}</dd></div>
        <div><dt>所属店铺</dt><dd>{{ storeLabel }}</dd></div>
        <div><dt>答复依据</dt><dd>每次回答附命中条款</dd></div>
      </dl>
    </section>
  </section>
</template>

<style scoped>
.side-tabs {
  display: flex; gap: 20px; padding: 0; margin: 0 0 14px;
  border-bottom: 1px solid var(--line);
}
.side-tab {
  border: none; background: transparent; color: var(--ink-3);
  border-radius: 0; padding: 8px 2px 9px; cursor: pointer; font-size: 13px;
  display: flex; align-items: center; justify-content: center; gap: 6px;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
}
.side-tab.active {
  color: var(--ink);
  border-bottom-color: var(--teal);
  font-weight: 600;
}
.tab-count {
  min-width: 18px; padding: 0 5px; border-radius: var(--radius-full); background: var(--panel-2);
  border: 1px solid var(--line);
  color: var(--ink-3); font-size: 11px; line-height: 18px; text-align: center;
}
.side-tab.active .tab-count { background: var(--teal-soft); border-color: var(--teal-line); color: var(--teal-ink); }
.side-activity .section-head { margin-bottom: 8px; }
.side-activity .section-head h2 { font-size: 12px; }
.section-note { color: var(--ink-3); font-size: 10px; margin-left: auto; }
.context-order {
  border: 1px solid var(--teal-line);
  background: var(--teal-wash);
  border-radius: var(--radius-md);
  padding: 10px 11px;
  margin-bottom: 16px;
}
.context-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.context-top strong { font-size: 12px; overflow-wrap: anywhere; }
.context-meta {
  display: flex; justify-content: space-between; gap: 8px;
  margin: 4px 0 6px; color: var(--ink-2); font-size: 12px;
}
.context-meta span { color: var(--ink); font-weight: 600; }
.context-link { font-size: 12px; }
.context-empty {
  margin: 0 0 16px;
  padding: 10px 11px;
  border: 1px dashed var(--line);
  border-radius: var(--radius-md);
  color: var(--ink-3);
  font-size: 11px;
  line-height: 1.6;
}
.orders-head { padding-top: 2px; }
.side-more { display: block; margin: 8px auto 0; font-size: 12px; }
/* 只留侧栏这一层滚动（round46 票 192）：卡片内部的第二个滚动区会把展开的深度卡裁掉。 */
.order-list { display: grid; gap: 8px; }
.ticket-list { display: grid; gap: 10px; }
.ticket-card {
  box-sizing: border-box;
  background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius-md); padding: 12px;
  border-left: 3px solid var(--line);
}
.ticket-card.clickable { cursor: pointer; transition: border-color 0.15s ease, background 0.15s ease; }
.ticket-card.clickable:hover { border-color: var(--teal-line); border-left-color: var(--teal); background: var(--panel-2); }
.ticket-card.clickable:focus-visible { outline: 2px solid var(--teal); outline-offset: 2px; }
.ticket-open { margin-left: auto; color: var(--teal); white-space: nowrap; }
.ticket-top { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.ticket-top strong { font-size: 13px; overflow-wrap: anywhere; }
.ticket-query { margin: 6px 0 8px; color: var(--ink); overflow-wrap: anywhere; }
.ticket-timeline { display: flex; gap: 12px; flex-wrap: wrap; margin: 0 0 8px; color: var(--ink-3); font-size: 12px; }
.step { position: relative; padding-left: 10px; }
.step::before { content: ''; position: absolute; left: 0; top: 6px; width: 5px; height: 5px; border-radius: 50%; background: var(--line); }
.step.done::before { background: var(--teal); }
.step.warn { color: var(--amber-ink); }
.step.warn::before { background: var(--amber); }
.step time { margin-left: 4px; }
.ticket-meta { display: flex; gap: 14px; flex-wrap: wrap; color: var(--ink-3); font-size: 12px; }
.session-info {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}
.session-info h2 { margin: 0 0 8px; font-size: 12px; }
.session-info dl { margin: 0; display: grid; gap: 6px; }
.session-info div { display: grid; grid-template-columns: 58px minmax(0, 1fr); gap: 8px; align-items: baseline; }
.session-info dt { color: var(--ink-3); font-size: 11px; }
.session-info dd {
  margin: 0; color: var(--ink-2); font-size: 11px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

/* 触屏目标 ≥40px（round47 票 196）；桌面密度不变。 */
@media (max-width: 640px) {
  .side-tab { min-height: 40px; }
  .section-head .link {
    display: inline-flex; align-items: center; justify-content: center;
    min-height: 40px; min-width: 44px; padding: 0 8px;
  }
}
</style>
