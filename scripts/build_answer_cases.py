#!/usr/bin/env python3
"""生成答案级评测集（round48 票 202 / ADR 0083）。

案例的唯一来源是这份脚本：`python scripts/build_answer_cases.py` 写
`eval/answer-cases.jsonl`，`--check` 只校验不落盘（CI 用）。

为什么案例写在脚本里而不是直接手写 JSONL：政策题的事实必须能在被引用的条款里找到出处。
生成时逐条做「required 至少一个备选出现在条款原文」的**接地校验**，写错字或换错条款会当场红，
而不是等到录制完才发现模型答不出来。动作题的事实来自固定回执的状态映射，不做接地校验。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from answer_cases import CASES_PATH, clause

POLICY = "policy"
ACTION = "action"

RET = "POLICY_RETURN"
SHP = "POLICY_SHIPPING"
PRM = "POLICY_PROMO"
FRS = "POLICY_FRESH"
ORD = "ACTION_ORDER"
LOG = "ACTION_LOGISTICS"
RFD = "ACTION_REFUND"
ADR = "ACTION_ADDRESS"


def policy(case_id, intent, query, doc, section, required, forbidden):
    return {"id": case_id, "kind": POLICY, "intent": intent, "query": query,
            "clauses": [{"doc": doc, "section": section}],
            "required": required, "forbidden": forbidden}


def action(case_id, intent, query, tool, arguments, result, required, forbidden):
    call_id = "call_" + case_id.lower().replace("-", "_")
    return {"id": case_id, "kind": ACTION, "intent": intent, "query": query,
            "tool": {"id": call_id, "name": tool, "arguments": arguments},
            "toolResult": result, "required": required, "forbidden": forbidden}


def envelope(tool, status, payload=None, message=None):
    return {"tool": tool, "status": status, "payload": payload,
            "message": message, "allowedActions": []}


ADDR_OLD = {"receiverName": "张伟", "receiverPhone": "138****1234", "province": "浙江省",
            "city": "杭州市", "district": "西湖区", "detailAddress": "文三路100号"}
ADDR_NEW = {"receiverName": "张伟", "receiverPhone": "138****1234", "province": "浙江省",
            "city": "杭州市", "district": "余杭区", "detailAddress": "文一西路969号"}


def order_payload(order_no, status, amount_fen, refund_review=None):
    return {"orderNo": order_no, "amountFen": amount_fen, "status": status,
            "categoryName": "服饰鞋包", "serviceFlags": ["运费险"], "address": ADDR_OLD,
            "createdAt": "2026-10-28T02:00:00Z", "paidAt": "2026-10-28T02:01:00Z",
            "shippedAt": "2026-10-29T03:00:00Z" if status != "PAID" else None,
            "refundReview": refund_review}


def logistics_payload(order_no="90002", last_description="运输中，已到达杭州转运中心",
                      last_at="2026-10-31T05:00:00Z"):
    return {"orderNo": order_no, "cpCode": "ZTO", "cpName": "中通快递",
            "trackingNo": "ZT1234567890",
            "nodes": [{"seq": 1, "nodeCode": "PICKED_UP", "description": "快件已揽收",
                       "occurredAt": "2026-10-30T01:00:00Z"},
                      {"seq": 2, "nodeCode": "IN_TRANSIT", "description": last_description,
                       "occurredAt": last_at}]}


def refund_payload(order_no, refund_id, status="PENDING_REVIEW", reason="七天无理由"):
    return {"refundId": refund_id, "orderNo": order_no, "amountFen": 25900,
            "status": status, "createdAt": "2026-11-01T02:00:00Z", "reason": reason}


CASES = [
    # ── 政策 · 退货（10）────────────────────────────────────────────────
    policy("ANS-POL-RET-01", RET, "七天无理由从哪天开始算？",
           "return-01-7day-basic", "七天无理由退货的适用起点",
           [["签收"], ["次日", "第二天"]], ["发货日", "下单日"]),
    policy("ANS-POL-RET-02", RET, "七天内没把货寄回去会怎样？",
           "return-01-7day-basic", "七天无理由退货的适用起点",
           [["寄回"], ["关闭"]], ["自动退款", "订单作废"]),
    policy("ANS-POL-RET-03", RET, "包装拆开了还能七天无理由退吗？",
           "return-01-7day-basic", "商品完好标准与影响二次销售",
           [["拆封", "拆开"], ["完好"]], ["一律拒绝", "不能退"]),
    policy("ANS-POL-RET-04", RET, "退主商品时赠品要一起寄回吗？",
           "return-01-7day-basic", "赠品与套装的处理方式",
           [["赠品"], ["一并退回", "一起退回"]], ["不用退回", "可以留下"]),
    policy("ANS-POL-RET-05", RET, "内衣拆封后还能七天无理由退吗？",
           "return-02-7day-exclusions", "除外商品的第一类：拆封与定制",
           [["不支持", "不适用"], ["拆封"]], ["可以七天无理由", "享受七天无理由"]),
    policy("ANS-POL-RET-06", RET, "生鲜不适用无理由，那坏了还能退吗？",
           "return-02-7day-exclusions", "除外不等于不能退",
           [["质量问题"], ["商家承担"]], ["没有任何售后", "质量也不退"]),
    policy("ANS-POL-RET-07", RET, "七天无理由退货的运费谁承担？",
           "return-03-shipping-cost", "无理由退货的运费承担",
           [["买家"], ["运费"]], ["平台承担", "由平台补贴"]),
    policy("ANS-POL-RET-08", RET, "质量问题退货的运费谁出？",
           "return-03-shipping-cost", "质量问题的运费承担",
           [["商家"], ["承担"]], ["买家承担", "买家垫付"]),
    policy("ANS-POL-RET-09", RET, "退款一般多久能到账？",
           "return-04-refund-timeline", "退款到账时限",
           [["原支付", "原路"], ["工作日"]], ["当天一定到账", "两小时内到账"]),
    policy("ANS-POL-RET-10", RET, "商家一直不处理我的退货申请怎么办？",
           "return-04-refund-timeline", "退款审核时限",
           [["三十六小时", "36小时"], ["默认同意", "自动同意"]], ["无限期等待", "必须商家同意"]),

    # ── 政策 · 物流（10）────────────────────────────────────────────────
    policy("ANS-POL-SHP-01", SHP, "下单时能指定快递公司发顺丰吗？",
           "shipping-01-carrier-scope", "默认承运与可选承运",
           [["不能指定", "不可以指定"], ["备注"]], ["保证能发顺丰", "指定顺丰成功"]),
    policy("ANS-POL-SHP-02", SHP, "西藏偏远地区能发货吗？",
           "shipping-01-carrier-scope", "配送范围与到付限制",
           [["补运费", "协商"], ["不发"]], ["全国包邮", "一定送达"]),
    policy("ANS-POL-SHP-03", SHP, "付款后商家多久必须发货？",
           "shipping-02-deadline", "标准发货时限",
           [["四十八小时", "48小时"], ["揽收"]], ["二十四小时内发货", "当天发货"]),
    policy("ANS-POL-SHP-04", SHP, "商家延迟发货怎么赔付？",
           "shipping-02-deadline", "延迟发货的赔付标准",
           [["百分之五", "5%"], ["红包"]], ["百分之十", "现金赔付"]),
    policy("ANS-POL-SHP-05", SHP, "偏远地区可以加收运费吗？",
           "shipping-03-remote", "偏远地区的运费加收",
           [["公示"], ["加收", "不发货"]], ["下单后追收", "口头告知就行"]),
    policy("ANS-POL-SHP-06", SHP, "偏远地区物流走得慢，算商家延迟发货吗？",
           "shipping-03-remote", "中转与时效延长",
           [["不计入", "不算"], ["发货时效"]], ["商家违约", "计入发货时效"]),
    policy("ANS-POL-SHP-07", SHP, "一个订单拆成两个包裹，退货时限怎么算？",
           "shipping-04-split-package", "多运单的售后时限起算",
           [["分别"], ["签收"]], ["合并计算", "按第一个包裹"]),
    policy("ANS-POL-SHP-08", SHP, "还没发货可以改收货地址吗？",
           "shipping-05-address-change", "可改址的时间窗口",
           [["发货前", "待发货"], ["修改"]], ["发货后也能改", "已发货也能改"]),
    policy("ANS-POL-SHP-09", SHP, "已经发货了还能改地址吗？",
           "shipping-05-address-change", "在途改址的可行性与费用",
           [["承运方", "在途"], ["费用", "改址费"]], ["平台自助", "免费改址"]),
    policy("ANS-POL-SHP-10", SHP, "快递擅自放代收点算我签收吗？",
           "shipping-06-inspection", "签收的法律效力与例外",
           [["同意", "明确同意"], ["实际取得", "实际取件", "实际拿到"]], ["投放即签收", "未经同意也算"]),

    # ── 政策 · 优惠（10）────────────────────────────────────────────────
    policy("ANS-POL-PRM-01", PRM, "跨店满减的门槛按什么金额算？",
           "promo-01-cross-shop", "满减门槛的认定金额",
           [["标价"], ["不含运费"]], ["门槛包含运费", "运费计入门槛"]),
    policy("ANS-POL-PRM-02", PRM, "凑单后只退一件，为什么退款比标价少？",
           "promo-01-cross-shop", "上不封顶与分摊规则",
           [["分摊"], ["实付"]], ["标价全退", "可以补差"]),
    policy("ANS-POL-PRM-03", PRM, "预售的定金膨胀是什么意思？",
           "promo-02-deposit-inflation", "定金膨胀的口径",
           [["定金"], ["抵扣", "尾款"]], ["膨胀金额提现", "返现到账"]),
    policy("ANS-POL-PRM-04", PRM, "忘付尾款了，定金能退吗？",
           "promo-02-deposit-inflation", "定金不退与例外",
           [["不予退还", "不退还", "定金不退"], ["例外"]], ["任何情况都退", "忘记尾款也退"]),
    policy("ANS-POL-PRM-05", PRM, "店铺券和平台券可以叠加使用吗？",
           "promo-03-coupon-stack", "可叠加与不可叠加的组合",
           [["互斥", "只能使用其中一张"]], ["两张都能用", "同时抵扣"]),
    policy("ANS-POL-PRM-06", PRM, "退款成功后优惠券会退回来吗？",
           "promo-03-coupon-stack", "券的使用门槛与有效期",
           [["已发货"], ["不返还"]], ["自动返还", "退款必退券"]),
    policy("ANS-POL-PRM-07", PRM, "秒杀商品能用优惠券吗？",
           "promo-04-flash-limit", "秒杀的库存与价格生效",
           [["不支持", "不参与"]], ["可以用券", "参与满减"]),
    policy("ANS-POL-PRM-08", PRM, "限购是按收货地址还是账号判断？",
           "promo-04-flash-limit", "限购的判定维度",
           [["账号"], ["商品"]], ["看收货地址", "看手机号"]),
    policy("ANS-POL-PRM-09", PRM, "价保期从什么时候开始算？",
           "promo-05-price-protection", "价保的适用范围与期限",
           [["签收"], ["次日"]], ["付款当天起算", "下单时起算"]),
    policy("ANS-POL-PRM-10", PRM, "会员日的折上九折能和店铺券叠加吗？",
           "promo-08-shop-t001-member", "本店会员日的优惠内容",
           [["互斥"], ["平台券"]], ["能和店铺券同享", "两种券都能用"]),

    # ── 政策 · 生鲜（10）────────────────────────────────────────────────
    policy("ANS-POL-FRS-01", FRS, "冷藏生鲜的理赔时限是多久？",
           "fresh-01-claim-window", "受理时限的起算点",
           [["二十四小时", "24小时"]], ["七天都可以", "随时可申请"]),
    policy("ANS-POL-FRS-02", FRS, "生鲜理赔要拍什么样的照片？",
           "fresh-01-claim-window", "举证材料的具体要求",
           [["面单", "订单号"], ["开箱", "视频"]], ["不需要举证", "只拍商品特写"]),
    policy("ANS-POL-FRS-03", FRS, "生鲜坏了一部分，按什么比例赔？",
           "fresh-02-claim-scope", "按问题比例分级赔付",
           [["比例"], ["实付"]], ["按标价全额", "一律按标价赔"]),
    policy("ANS-POL-FRS-04", FRS, "生鲜坏了能要求赔误工费吗？",
           "fresh-02-claim-scope", "赔付上限与不承担的项目",
           [["实付金额"], ["不承担", "不包含误工费"]], ["可以赔误工费", "支持精神赔偿"]),
    policy("ANS-POL-FRS-05", FRS, "生鲜外包装破损了谁负责？",
           "fresh-03-packaging-loss", "外包装完好与破损的责任分界",
           [["外包装"], ["商家", "承运方"]], ["买家承担", "买家自行举证"]),
    policy("ANS-POL-FRS-06", FRS, "冰袋全化了算冷链断链吗？",
           "fresh-04-cold-chain", "冰袋状态的判定口径",
           [["化水", "断链"], ["全额退款"]], ["属于正常损耗", "不能退款"]),
    policy("ANS-POL-FRS-07", FRS, "水果少了几两算缺重吗？",
           "fresh-05-weight-shortage", "合理误差与赔付比例",
           [["百分之三", "3%"], ["不认定", "合理误差", "不算缺重"]], ["缺一克就赔", "按标价全退"]),
    policy("ANS-POL-FRS-08", FRS, "活蟹到货就死了，多久内要申请？",
           "fresh-06-seafood-dead", "活鲜死亡的时限与判定",
           [["六小时", "6小时"], ["死亡"]], ["四十八小时", "七天内"]),
    policy("ANS-POL-FRS-09", FRS, "水果不够甜能申请生鲜理赔吗？",
           "fresh-07-exclusions", "商家不承担责任的情形",
           [["自然差异", "个人预期"], ["不承担", "不予受理", "不在生鲜理赔范围"]],
           ["按质量问题赔", "应当赔付"]),
    policy("ANS-POL-FRS-10", FRS, "死蟹是按整单退还是按只赔？",
           "fresh-08-shop-t002-dead-crab", "按只折算与订单核验",
           [["只数", "按只"], ["实付"]], ["整单退款", "全额退整单"]),

    # ── 动作 · 订单（5）─────────────────────────────────────────────────
    action("ANS-ACT-ORD-01", ORD, "90001 现在是什么状态？",
           "queryOrderDetail", {"orderNo": "90001"},
           envelope("queryOrderDetail", "OK", order_payload("90001", "SHIPPED", 25900)),
           [["90001"], ["已发货", "运输中"]], ["已签收", "90002"]),
    action("ANS-ACT-ORD-02", ORD, "订单 90003 到哪一步了？",
           "queryOrderDetail", {"orderNo": "90003"},
           envelope("queryOrderDetail", "OK", order_payload("90003", "DELIVERED", 39900)),
           [["90003"], ["已签收", "已送达"]], ["运输中", "未发货"]),
    action("ANS-ACT-ORD-03", ORD, "90001 这单多少钱？",
           "queryOrderDetail", {"orderNo": "90001"},
           envelope("queryOrderDetail", "OK", order_payload("90001", "SHIPPED", 25900)),
           [["259"]], ["199", "90002"]),
    action("ANS-ACT-ORD-04", ORD, "90001 有退款在审核吗？",
           "queryOrderDetail", {"orderNo": "90001"},
           envelope("queryOrderDetail", "OK",
                    order_payload("90001", "SHIPPED", 25900, "PENDING_REVIEW")),
           [["审核中", "待审核", "正在审核"]], ["已退款", "已到账"]),
    action("ANS-ACT-ORD-05", ORD, "帮我查一下 90004",
           "queryOrderDetail", {"orderNo": "90004"},
           envelope("queryOrderDetail", "NOT_FOUND", None, "订单不存在或不属于当前买家"),
           [["未找到", "查不到", "查询不到", "不存在"]], ["已发货", "状态为已签收"]),

    # ── 动作 · 物流（5）─────────────────────────────────────────────────
    action("ANS-ACT-LOG-01", LOG, "90002 到哪了？",
           "queryLogistics", {"orderNo": "90002"}, envelope("queryLogistics", "OK", logistics_payload()),
           [["中通"], ["运输中", "在途"]], ["顺丰", "已签收"]),
    action("ANS-ACT-LOG-02", LOG, "90002 的快递单号是多少？",
           "queryLogistics", {"orderNo": "90002"}, envelope("queryLogistics", "OK", logistics_payload()),
           [["ZT1234567890"]], ["SF", "YT"]),
    action("ANS-ACT-LOG-03", LOG, "90002 什么时候揽收的？",
           "queryLogistics", {"orderNo": "90002"}, envelope("queryLogistics", "OK", logistics_payload()),
           [["揽收"], ["10月30", "10-30", "30日"]], ["未发货"]),
    action("ANS-ACT-LOG-04", LOG, "90002 是不是已经签收了？",
           "queryLogistics", {"orderNo": "90002"}, envelope("queryLogistics", "OK", logistics_payload()),
           [["运输中", "尚未签收", "没有签收"]], ["已签收", "已送达"]),
    action("ANS-ACT-LOG-05", LOG, "90002 的物流怎么三天没更新？",
           "queryLogistics", {"orderNo": "90002"},
           envelope("queryLogistics", "OK", logistics_payload(
               last_description="快件已到达杭州转运中心，等待下一站中转", last_at="2026-10-30T05:00:00Z")),
           [["查询", "核实"], ["承运方", "快递公司", "中通"]], ["保证", "一定"]),

    # ── 动作 · 退款（5）─────────────────────────────────────────────────
    action("ANS-ACT-RFD-01", RFD, "帮我把 90001 退款",
           "applyRefund", {"orderNo": "90001"},
           envelope("applyRefund", "PENDING_APPROVAL",
                    refund_payload("90001", "RF-90001-01")),
           [["90001"], ["提交", "受理", "审核"]], ["已到账", "退款成功", "已退款"]),
    action("ANS-ACT-RFD-02", RFD, "这笔退款什么时候到账？",
           "applyRefund", {"orderNo": "90001"},
           envelope("applyRefund", "PENDING_APPROVAL",
                    refund_payload("90001", "RF-90001-01")),
           [["审核"], ["到账"]], ["今天到账", "立即到账"]),
    action("ANS-ACT-RFD-03", RFD, "90003 也帮我申请退款",
           "applyRefund", {"orderNo": "90003"},
           envelope("applyRefund", "PENDING_APPROVAL",
                    refund_payload("90003", "RF-90003-01")),
           [["90003"], ["审核", "提交"]], ["已到账", "90001"]),
    action("ANS-ACT-RFD-04", RFD, "再帮我提交一次 90001 的退款",
           "applyRefund", {"orderNo": "90001"},
           envelope("applyRefund", "STATE_NOT_ALLOWED", None, "已有退款申请在审核中，不能重复提交"),
           [["审核中"], ["不能重复", "无需重复", "无法重复"]], ["已到账", "重复提交成功"]),
    action("ANS-ACT-RFD-05", RFD, "90001 这笔退款金额是多少？",
           "applyRefund", {"orderNo": "90001"},
           envelope("applyRefund", "PENDING_APPROVAL",
                    refund_payload("90001", "RF-90001-01")),
           [["259"]], ["已到账", "三倍"]),

    # ── 动作 · 改址（5）─────────────────────────────────────────────────
    action("ANS-ACT-ADR-01", ADR, "把 90001 的地址改成杭州市余杭区文一西路 969 号",
           "modifyDeliveryAddress", {"orderNo": "90001", "address": "浙江省杭州市余杭区文一西路969号"},
           envelope("modifyDeliveryAddress", "OK",
                    {"orderNo": "90001", "before": ADDR_OLD, "after": ADDR_NEW, "addressVersion": 2}),
           [["90001"], ["余杭", "文一西路"]], ["修改失败", "已发货"]),
    action("ANS-ACT-ADR-02", ADR, "90003 也帮我改一下地址",
           "modifyDeliveryAddress", {"orderNo": "90003", "address": "江苏省南京市鼓楼区中山路1号"},
           envelope("modifyDeliveryAddress", "STATE_NOT_ALLOWED", None,
                    "订单已发货，不支持修改收货地址"),
           [["已发货"], ["不能修改", "不支持", "无法修改"]], ["已修改成功", "已经改好"]),
    action("ANS-ACT-ADR-03", ADR, "90001 的地址改好了吗？",
           "modifyDeliveryAddress", {"orderNo": "90001", "address": "浙江省杭州市余杭区文一西路969号"},
           envelope("modifyDeliveryAddress", "OK",
                    {"orderNo": "90001", "before": ADDR_OLD, "after": ADDR_NEW, "addressVersion": 2}),
           [["90001"], ["已修改", "修改成功", "已更新", "改好"]], ["没有修改", "修改失败"]),
    action("ANS-ACT-ADR-04", ADR, "把 99999 的地址改到上海",
           "modifyDeliveryAddress", {"orderNo": "99999", "address": "上海市浦东新区世纪大道100号"},
           envelope("modifyDeliveryAddress", "NOT_FOUND", None, "订单不存在或不属于当前买家"),
           [["未找到", "查不到", "查询不到", "不存在"]], ["已修改", "已发货"]),
    action("ANS-ACT-ADR-05", ADR, "改完地址后旧地址还会收到货吗？",
           "modifyDeliveryAddress", {"orderNo": "90001", "address": "浙江省杭州市余杭区文一西路969号"},
           envelope("modifyDeliveryAddress", "OK",
                    {"orderNo": "90001", "before": ADDR_OLD, "after": ADDR_NEW, "addressVersion": 2}),
           [["新地址", "余杭", "文一西路"]], ["两个地址都", "旧地址也能收"]),
]


def validate(cases: list[dict]) -> list[str]:
    errors: list[str] = []
    ids = [case["id"] for case in cases]
    if len(ids) != len(set(ids)):
        errors.append("案例 id 有重复")
    policy_cases = [case for case in cases if case["kind"] == POLICY]
    action_cases = [case for case in cases if case["kind"] == ACTION]
    if (len(cases), len(policy_cases), len(action_cases)) != (60, 40, 20):
        errors.append(f"数量不对：总 {len(cases)} / 政策 {len(policy_cases)} / 动作 {len(action_cases)}")
    for case in cases:
        required = case.get("required") or []
        forbidden = case.get("forbidden") or []
        if not required:
            errors.append(f"{case['id']}: 没有 required")
        if not forbidden:
            errors.append(f"{case['id']}: 没有 forbidden")
        required_terms = {term for fact in required for term in
                          ([fact] if isinstance(fact, str) else fact)}
        forbidden_terms = {term for fact in forbidden for term in
                           ([fact] if isinstance(fact, str) else fact)}
        overlap = required_terms & forbidden_terms
        if overlap:
            errors.append(f"{case['id']}: required 与 forbidden 冲突 {sorted(overlap)}")
        if case["kind"] == POLICY:
            texts = []
            for ref in case["clauses"]:
                try:
                    texts.append(clause(ref["doc"], ref["section"])["text"])
                except Exception as failure:  # noqa: BLE001 —— 写错条款要当场红
                    errors.append(f"{case['id']}: {failure}")
            haystack = "\n".join(texts)
            for fact in required:
                terms = [fact] if isinstance(fact, str) else fact
                if not any(term in haystack for term in terms):
                    errors.append(f"{case['id']}: required {terms} 在引用条款里找不到出处")
            for fact in forbidden:
                terms = [fact] if isinstance(fact, str) else fact
                grounded = [term for term in terms if term in haystack]
                if grounded:
                    errors.append(f"{case['id']}: forbidden {grounded} 出现在条款原文里，会误伤正确引用")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="生成答案级评测集")
    parser.add_argument("--check", action="store_true", help="只校验，不写文件")
    args = parser.parse_args()

    errors = validate(CASES)
    if errors:
        print("FAIL 答案案例校验：", file=sys.stderr)
        for problem in errors:
            print("  - " + problem, file=sys.stderr)
        return 1

    payload = "".join(json.dumps(case, ensure_ascii=False) + "\n" for case in CASES)
    current = CASES_PATH.read_text(encoding="utf-8") if CASES_PATH.exists() else ""
    if args.check:
        if current != payload:
            print(f"FAIL {CASES_PATH} 与生成器不一致；运行 python scripts/build_answer_cases.py 重写",
                  file=sys.stderr)
            return 1
        print(f"PASS 答案案例：{len(CASES)} 条（政策 40 / 动作 20），与生成器一致")
        return 0
    CASES_PATH.write_text(payload, encoding="utf-8", newline="\n")
    print(f"已写 {CASES_PATH}：{len(CASES)} 条（政策 40 / 动作 20）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
