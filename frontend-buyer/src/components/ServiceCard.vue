<script setup lang="ts">
/** 侧栏「服务范围」（round44 票 188 从 `App.vue` 拆出）：纯静态说明。 */
</script>

<template>
  <section class="side-card service-card">
    <h2>服务范围</h2>
    <ul>
      <li><strong>订单与物流</strong><span>查状态、金额、轨迹</span></li>
      <li><strong>退换与理赔</strong><span>按店铺政策办理</span></li>
      <li><strong>人工接管</strong><span>答不了就转，给可追踪工单</span></li>
    </ul>
    <p class="side-fine">需要人工时不用重复描述，工单进度会在这里更新。</p>
  </section>
</template>

<style scoped>
.service-card ul { list-style: none; margin: 0; padding: 0; display: grid; gap: 9px; }
.service-card li { display: grid; gap: 1px; }
.service-card strong { font-size: 13px; }
.service-card li span { color: var(--ink-3); font-size: 12px; }
.side-fine { margin: 12px 0 0; color: var(--ink-3); font-size: 12px; line-height: 1.5; }
</style>
