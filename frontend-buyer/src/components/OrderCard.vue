<script setup lang="ts">
/**
 * 买家订单深度卡（round44 票 189）。
 *
 * <p>折叠时是一行订单摘要；展开后画四段状态推进条、收货信息与物流时间轴。数据由
 * `App.vue` 拉取后传入——这一层只决定怎么画，不发请求、不缓存。
 *
 * <p>手机号显示的是**服务端脱敏值**，这里不做任何还原或二次打码。
 */
import { computed, ref } from 'vue'
import type { LogisticsNode, MyOrder, OrderDetail, OrderLogistics } from '../api'
import { ORDER_STATUS_LABEL, formatTime } from '../lib/format'

const props = defineProps<{
  order: MyOrder
  expanded: boolean
  detail: OrderDetail | null
  logistics: OrderLogistics | null
  loading: boolean
  error: string
}>()

const emit = defineEmits<{ toggle: []; ask: [] }>()

interface ProgressStep {
  label: string
  at: string
  done: boolean
}

const steps = computed<ProgressStep[]>(() => {
  const detail = props.detail
  if (!detail) return []
  return [
    { label: '下单', at: formatTime(detail.createdAt), done: !!detail.createdAt },
    { label: '付款', at: formatTime(detail.paidAt), done: !!detail.paidAt },
    { label: '发货', at: formatTime(detail.shippedAt), done: !!detail.shippedAt },
    { label: '签收', at: formatTime(detail.deliveredAt), done: !!detail.deliveredAt },
  ]
})

const addressLine = computed(() => {
  const address = props.detail?.address
  if (!address) return ''
  return [address.province, address.city, address.district].filter(Boolean).join('') + ' ' + address.detailAddress
})

const nodes = computed<LogisticsNode[]>(() => props.logistics?.nodes ?? [])

const copyNote = ref('')

/** 运单号要能直接贴进快递查询——手选 12 位数字是没必要的摩擦。 */
async function copyTracking() {
  const trackingNo = props.logistics?.trackingNo
  if (!trackingNo) return
  try {
    await navigator.clipboard.writeText(trackingNo)
    copyNote.value = '已复制'
  } catch {
    copyNote.value = '复制失败，请手动选中'
  }
  window.setTimeout(() => {
    copyNote.value = ''
  }, 1600)
}
</script>

<template>
  <article
    class="order-card"
    :class="{ expanded }"
    role="button"
    tabindex="0"
    :aria-expanded="expanded"
    @click="emit('toggle')"
    @keyup.enter="emit('toggle')"
  >
    <div class="order-top">
      <strong class="mono">{{ order.orderNo }}</strong>
      <span class="pill">{{ ORDER_STATUS_LABEL[order.status] ?? order.status }}</span>
    </div>
    <div class="order-meta">
      <span>{{ order.categoryName }}</span>
      <strong>¥{{ (order.amountFen / 100).toFixed(2) }}</strong>
    </div>
    <p class="order-foot">
      {{ formatTime(order.createdAt) }} 下单 · {{ expanded ? '收起' : '查看详情与物流' }}
    </p>

    <div v-if="expanded" class="order-detail" @click.stop>
      <p v-if="error" class="err">{{ error }}</p>
      <p v-else-if="loading" class="empty">正在读取订单详情…</p>

      <template v-else-if="detail">
        <ol class="progress">
          <li v-for="step in steps" :key="step.label" :class="{ done: step.done }">
            <span class="dot" aria-hidden="true"></span>
            <span class="step-label">{{ step.label }}</span>
            <time v-if="step.at">{{ step.at }}</time>
          </li>
        </ol>

        <dl class="order-facts">
          <div><dt>金额</dt><dd>¥{{ (detail.amountFen / 100).toFixed(2) }}</dd></div>
          <div><dt>品类</dt><dd>{{ detail.categoryName }}</dd></div>
          <div><dt>地址版本</dt><dd>v{{ detail.addressVersion }}</dd></div>
        </dl>

        <ul v-if="detail.serviceFlags.length" class="flags">
          <li v-for="flag in detail.serviceFlags" :key="flag">{{ flag }}</li>
        </ul>

        <section class="block">
          <h3>收货信息</h3>
          <p class="address">
            {{ detail.address.receiverName }}
            <span class="mono masked">{{ detail.address.receiverPhone }}</span>
          </p>
          <p class="address-line">{{ addressLine }}</p>
          <p class="fine">号码在服务端脱敏后返回，本页从未收到完整号码。</p>
        </section>

        <section class="block">
          <h3>
            物流进展
            <span v-if="logistics?.cpName" class="carrier">{{ logistics.cpName }}</span>
          </h3>
          <p v-if="logistics?.trackingNo" class="tracking">
            运单号 <span class="mono">{{ logistics.trackingNo }}</span>
            <button class="link" type="button" @click.stop="copyTracking">复制</button>
            <span v-if="copyNote" class="fine">{{ copyNote }}</span>
          </p>

          <ol v-if="nodes.length" class="timeline">
            <li
              v-for="(node, index) in nodes"
              :key="node.seq"
              :class="{ latest: index === 0 }"
              :data-state="index === 0 ? 'current' : 'done'"
            >
              <span class="node-desc">{{ node.description }}</span>
              <time>{{ formatTime(node.occurredAt) }}</time>
            </li>
          </ol>
          <p v-else class="empty">还没有物流轨迹。订单发货后会在这里逐个出现节点。</p>
        </section>
      </template>
    </div>

    <button class="link order-ask" type="button" @click.stop="emit('ask')">问问这单 →</button>
  </article>
</template>

<style scoped>
.order-card {
  border: 1px solid var(--line); border-radius: 10px; padding: 9px 10px;
  cursor: pointer; transition: border-color var(--dur-1) var(--ease), box-shadow var(--dur-1) var(--ease);
}
.order-card:hover { border-color: var(--teal-line); box-shadow: 0 3px 12px rgba(15, 118, 110, .07); }
.order-card.expanded { border-color: var(--teal-line); background: var(--panel-active); }
.order-card:focus-visible { outline: 2px solid var(--teal); outline-offset: 2px; }
.order-top { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.order-top strong { font-size: 12px; overflow-wrap: anywhere; }
.order-meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-top: 5px; font-size: 12px; color: var(--ink-2); }
.order-meta strong { color: var(--ink); font-size: 13px; }
.order-foot { margin: 5px 0 0; color: var(--ink-3); font-size: 11px; }
.order-ask { margin-top: 8px; font-size: 12px; }

.order-detail { margin-top: 10px; padding-top: 10px; border-top: 1px dashed var(--teal-line-2); cursor: default; }
.progress { list-style: none; display: flex; margin: 0 0 12px; padding: 0; }
.progress li { flex: 1; position: relative; display: grid; gap: 2px; padding-top: 12px; color: var(--ink-3); font-size: 11px; }
.progress li::before {
  content: ''; position: absolute; top: 4px; left: 0; right: 0; height: 2px; background: var(--line);
}
.progress li:first-child::before { left: 50%; }
.progress li:last-child::before { right: 50%; }
.progress li .dot {
  position: absolute; top: 1px; left: 50%; transform: translateX(-50%);
  width: 8px; height: 8px; border-radius: 50%; background: var(--line); border: 2px solid var(--panel);
}
.progress li.done { color: var(--ink); }
.progress li.done::before { background: var(--teal-line); }
.progress li.done .dot { background: var(--teal); }
.progress li .step-label { text-align: center; }
.progress li time { text-align: center; font-size: 10px; color: var(--ink-3); }

.order-facts { display: flex; gap: 14px; margin: 0 0 10px; }
.order-facts div { display: grid; gap: 1px; }
.order-facts dt { color: var(--ink-3); font-size: 11px; }
.order-facts dd { margin: 0; font-size: 12px; }

.flags { list-style: none; display: flex; flex-wrap: wrap; gap: 5px; margin: 0 0 10px; padding: 0; }
.flags li {
  font-size: 11px; color: var(--teal); background: var(--teal-soft);
  border: 1px solid var(--teal-line); border-radius: var(--radius-full); padding: 1px 8px;
}

.block { border-top: 1px solid var(--line-2); padding-top: 9px; margin-top: 9px; }
.block h3 { font-size: 12px; margin: 0 0 6px; display: flex; align-items: center; gap: 6px; }
.carrier { color: var(--ink-3); font-weight: 400; font-size: 11px; }
.address { margin: 0; font-size: 12px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.masked { color: var(--ink-2); }
.address-line { margin: 3px 0 0; font-size: 12px; color: var(--ink-2); overflow-wrap: anywhere; }
.tracking { margin: 0 0 8px; font-size: 12px; color: var(--ink-2); display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.tracking .link { font-size: 12px; }

.timeline { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
.timeline li {
  position: relative; padding-left: 14px; display: grid; gap: 1px; color: var(--ink-3); font-size: 12px;
}
.timeline li::before {
  content: ''; position: absolute; left: 0; top: 5px; width: 6px; height: 6px;
  border-radius: 50%; background: var(--teal-line);
}
.timeline li.latest { color: var(--ink); }
.timeline li.latest::before { background: var(--teal); box-shadow: 0 0 0 3px var(--teal-soft); }
.timeline li:not(:last-child)::after {
  content: ''; position: absolute; left: 2px; top: 14px; bottom: -8px; width: 1px; background: var(--line);
}
.timeline time { font-size: 11px; color: var(--ink-3); }
.node-desc { overflow-wrap: anywhere; }
</style>
