#!/usr/bin/env python3
"""用共享密钥签一个 HS256 演示令牌（round23 票 76 / ADR 0029）。

**为什么需要它**：网关的 `/auth/mock-token` 端点带 `@Conditional(MockIdentityCondition)`，
**只在回环绑定上注册**——而容器内为了端口发布必须绑 `0.0.0.0`，于是那个端点返回「接口不存在」。
这是 ADR 0029 的设计，不是缺陷：非回环 = 生产姿态，生产姿态下没有「随便领一个身份」的入口。

所以容器档的演示身份由**持有密钥的操作者自己签**——这正是真实 IdP 干的事，
不削弱任何防线：密钥本来就在你手里，脚本只是替你做 HMAC 计算。

用法（纯 stdlib，不需要装任何东西）：
    SHOPPILOT_JWT_SECRET=<与网关同一个密钥> \\
    python scripts/mint-demo-token.py --tenant T001 --customer C001

    # 直接拿它打一次链路：
    TOKEN=$(SHOPPILOT_JWT_SECRET=... python scripts/mint-demo-token.py --tenant T001 --customer C001)
    curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8082/api/v1/support/ops/tickets
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time

MIN_SECRET_BYTES = 32  # 与 JwtService 的启动校验同口径：不足 32 字节网关自己就拒绝启动


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def mint(secret: str, tenant: str, customer: str, ttl_seconds: int) -> str:
    if len(secret.encode("utf-8")) < MIN_SECRET_BYTES:
        # 不签：签出来的东西网关一定拒，那不如在这里就说清为什么
        raise SystemExit(
            f"SHOPPILOT_JWT_SECRET 至少 {MIN_SECRET_BYTES} 字节（现在 {len(secret.encode('utf-8'))}）——"
            "与 JwtService 的启动校验同口径，签了网关也会拒"
        )
    now = int(time.time())
    # claim 形状逐项对齐 JwtService.issue：sub=cid、tid、cid、iat、exp
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {
        "sub": customer,
        "tid": tenant,
        "cid": customer,
        "iat": now,
        "exp": now + ttl_seconds,
    }
    signing_input = (
        b64url(json.dumps(header, separators=(",", ":")).encode("utf-8"))
        + "."
        + b64url(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    )
    signature = hmac.new(secret.encode("utf-8"), signing_input.encode("utf-8"), hashlib.sha256).digest()
    return f"{signing_input}.{b64url(signature)}"


def main() -> int:
    parser = argparse.ArgumentParser(description="签一个 HS256 演示令牌（容器档用）")
    parser.add_argument("--tenant", default="T001")
    parser.add_argument("--customer", default="C001")
    parser.add_argument("--ttl", type=int, default=1800, help="有效期秒数（默认 1800，与网关同一档）")
    parser.add_argument("--check", action="store_true", help="自检：验一遍签名与 claim，不签发")
    args = parser.parse_args()

    secret = os.environ.get("SHOPPILOT_JWT_SECRET", "")
    if not secret:
        print("缺 SHOPPILOT_JWT_SECRET：它必须与网关的 SHOPPILOT_JWT_SECRET 同值", file=sys.stderr)
        return 2

    if args.check:
        token = mint(secret, args.tenant, args.customer, args.ttl)
        payload_segment = token.split(".")[1]
        padding = "=" * (-len(payload_segment) % 4)
        claims = json.loads(base64.urlsafe_b64decode(payload_segment + padding))
        assert claims["tid"] == args.tenant and claims["cid"] == args.customer, claims
        assert claims["exp"] > time.time(), "刚签出来的令牌必须是未过期的"
        print(f"自检通过：claim 形状与 JwtService.issue 一致（{claims['tid']}/{claims['cid']}）")
        return 0

    print(mint(secret, args.tenant, args.customer, args.ttl))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
