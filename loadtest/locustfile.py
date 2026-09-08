"""大促混合流量压测（ticket 18、ADR 0011）。

只压同步端点：QPS 与 TP99 是这一路的指标。SSE 的 500 并发长连接 TTFT 与堆占用
由 scripts/run_sse_ttft.py 单独测——QPS 与长连接是两个维度，混测无意义。

两条流量曲线用环境变量切换，不改文件：
  SHOPPILOT_TRAFFIC_MODEL=l1  L1 主导：55% 文本重复热点 + 15% 口语改写 + 15% 业务办理 + 15% 长尾
  SHOPPILOT_TRAFFIC_MODEL=l2  L2 主导：15% 文本重复 + 55% 口语改写 + 15% 业务办理 + 15% 长尾

perf 模式下生成侧是 MockLLM（固定延迟），但 embedding 仍打真实 bge-m3——
否则 L2 那条曲线根本不存在（ADR 0011 明确否决了连向量化一起 mock 的做法）。
"""

import json
import os
import random
import time

from locust import HttpUser, between, task

TRAFFIC_MODEL = os.environ.get("SHOPPILOT_TRAFFIC_MODEL", "l1").strip().lower()
TENANTS = [("T001", "C001"), ("T002", "C200"), ("T003", "C300")]

# 文本重复热点：小池子，跑起来必然反复命中同一条 L1 key
HOT_EXACT = [
    "发什么快递",
    "七天无理由怎么算",
    "跨店满减怎么算",
    "生鲜坏了怎么赔",
    "多久发货",
    "可以指定顺丰吗",
    "优惠券能和满减一起用吗",
    "退款多久到账",
]
# 口语改写：语义等价但文本不同，是 L2 的目标流量
PARAPHRASE = [
    "你们家用的哪家物流",
    "无理由退换的七天从哪天开始算",
    "满300减50是几个店凑单的",
    "菜收到已经烂了要怎么理赔",
    "下单后几天能寄出",
    "我想发顺丰能不能备注",
    "店铺券和跨店优惠可以叠加不",
    "钱几天能退回我卡上",
    "快递一般选哪一家",
    "七天是从签收那天开始吗",
    "凑够300是不同店的一起算吗",
    "水果坏果能赔多少钱",
]
# 业务办理：跨进程打 biz-mock，测的是工具链与连接池
ACTIONS = [
    "90001 这单现在什么状态",
    "帮我查下订单 90002",
    "90002 的快递到哪了",
    "订单号 90003 麻烦看下进度",
    "90003 签收了吗",
    "查一下 90002 的物流轨迹",
]
# 冷门长尾：不进缓存，走混合检索
LONGTAIL = [
    "定制商品支持无理由退货吗",
    "赠品需要一起退回吗",
    "价保申请后差价退到哪里",
    "定金膨胀和跨店满减能同时享受吗",
    "冷链断链导致化冻怎么界定责任",
    "大件商品上门取退怎么预约",
    "拆封后影响二次销售还能退吗",
    "预售尾款没付定金会退吗",
    "港澳台地区能下单发货吗",
    "验货后拒收运费谁承担",
]

WEIGHTS = {
    "l1": [("hot", 55), ("para", 15), ("action", 15), ("long", 15)],
    "l2": [("hot", 15), ("para", 55), ("action", 15), ("long", 15)],
    # 任务书吞吐条件的原始口径："QPS >= 1200（80% 缓存命中）"。
    # l1 模型只有约 56% 可缓存流量，拿它的 QPS 去对 1200 的线是错配口径，
    # 所以单独构造 80% 热点重复 + 10% 业务办理 + 10% 长尾的模型来回答这个问题。
    "mix80": [("hot", 80), ("para", 0), ("action", 10), ("long", 10)],
    # 连接池饱和点探针（ticket 18）：全部流量走业务办理，直穿 biz-mock 的 DB 路径。
    # 用 l1 模型找池饱和点是找不到的——那里只有 15% 请求真打数据库。
    "biz": [("hot", 0), ("para", 0), ("action", 100), ("long", 0)],
}[TRAFFIC_MODEL]

POOL = {
    "hot": HOT_EXACT,
    "para": PARAPHRASE,
    "action": ACTIONS,
    "long": LONGTAIL,
}


def pick_kind():
    ticket = random.randint(1, sum(weight for _, weight in WEIGHTS))
    for kind, weight in WEIGHTS:
        if ticket <= weight:
            return kind
        ticket -= weight
    return WEIGHTS[-1][0]


class ShopPilotUser(HttpUser):
    # 不限速：本实验找的是拐点，压到系统顶不住为止
    wait_time = between(0, 0)
    host = os.environ.get("SHOPPILOT_BASE_URL", "http://127.0.0.1:8082")

    def on_start(self):
        self.tenant, self.customer = random.choice(TENANTS)
        self.token = None
        with self.client.post("/auth/mock-token",
                              json={"tenantId": self.tenant, "customerId": self.customer},
                              name="auth/mock-token", catch_response=True) as response:
            try:
                self.token = response.json()["token"]
                response.success()
            except (KeyError, ValueError) as bad:
                response.failure(f"mock-token 解析失败: {bad}")

    @task
    def chat(self):
        if not self.token:
            return
        kind = pick_kind()
        query = random.choice(POOL[kind])
        started = time.perf_counter()
        with self.client.post(
                "/api/v1/support/chat",
                json={"query": query, "idempotencyToken": f"perf-{int(time.time() * 1000)}-{random.randint(0, 999999)}"},
                headers={"Authorization": f"Bearer {self.token}",
                         "X-Conversation-Id": f"perf-{self.customer}-{int(started)}"},
                name=f"chat[{kind}]",
                catch_response=True,
                timeout=120,
        ) as response:
            if response.status_code != 200:
                response.failure(f"HTTP {response.status_code}")
                return
            try:
                payload = response.json()
            except ValueError:
                response.failure("响应不是 JSON")
                return
            if not payload.get("answer"):
                response.failure("空答案")
                return
            # 命中路径与未命中路径各记一条派生事件：缓存 TP99 < 30ms 这条 SLO 只能对着
            # "真命中"的样本报，混在 hot 这一路里会被未命中请求（含 mock 固定延迟）淹没。
            # 派生事件不计入头条吞吐，见 scripts/run_loadtest.py 的 DERIVED_COLUMNS。
            layer = payload.get("cacheLayer") or "NONE"
            self.environment.events.request.fire(
                request_type="CACHE",
                name="cache[hitpath]" if layer in ("L1", "L2") else "cache[misspath]",
                response_time=int((time.perf_counter() - started) * 1000), response_length=0,
                exception=None, context=self.environment.runner.user_count,
            )
            # 降级与转人工在功能上是对的，但在压测里必须单独计数，
            # 否则"错误率 0.1%"会把一整片转人工算成成功
            if payload.get("fallbackReason"):
                response.success()
                self.environment.events.request.fire(
                    request_type="FALLBACK", name=f"chat[{kind}].{payload['fallbackReason']}",
                    response_time=int((time.perf_counter() - started) * 1000), response_length=0,
                    exception=None, context=self.environment.runner.user_count,
                )
            else:
                response.success()
