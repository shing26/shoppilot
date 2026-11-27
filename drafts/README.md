# drafts（已迁出）

退款内核与 Shopify 适配器已迁到独立 reactor [`kernel/`](../kernel/README.md)，
版本 `0.1.0-SNAPSHOT`。

- 构建：`.\mvnw.cmd -f kernel/pom.xml -B -ntp verify`
- 转正契约：`plans/232-commerce-kernel-promotion.md`
- ADR：`docs/adr/0088-refund-approval-ledger.md`、
  `0089-refund-settlement-read-model.md`、
  `0090-buyer-term-and-store-scoped-customer.md`

本目录不再保留可运行副本，避免两个事实源。
