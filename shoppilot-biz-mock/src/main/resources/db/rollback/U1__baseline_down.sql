-- V1 基线的回滚脚本（round18 票 42 / ADR 0041）
--
-- 本文件**不被任何工具自动执行**。Flyway 社区版没有 undo 能力（`undo` 是 Teams 版功能），
-- 放在 db/migration/ 之外的目录正是为了不被 Flyway 当成迁移扫描到。
--
-- 回滚口径（两条路径，按场景选）：
--   1. 整库重放（本仓的常规路径）：H2 是内存库、每次起栈本来就是干净世界，因此
--      「回滚」在本仓等价于把库丢掉重来 —— 设 flyway.clean-disabled=false 后执行
--      `flyway clean` 再 `migrate`，或直接重启进程。
--   2. 单版回退（保留数据时）：手工执行下面这条，再让 flyway_schema_history 删掉 V1 那一行。
--
-- 本文件是「回滚脚本存在且可执行」的约定载体，不是自动门禁；不要因为它存在就宣称有 undo。

drop table if exists coupons;
drop table if exists customers;
drop table if exists feedback;
drop table if exists logistics;
drop table if exists order_addresses;
drop table if exists orders;
drop table if exists refunds;
drop table if exists tenants;
drop table if exists tickets;
