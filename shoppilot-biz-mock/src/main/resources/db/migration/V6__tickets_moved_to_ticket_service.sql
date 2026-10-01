-- round23 票 72：tickets 与 routing_rules 随数据搬到 shoppilot-ticket（所有者裁定 B）。
--
-- V3/V4 是**已应用的历史迁移**，checksum 不能动，所以它们建的那两张表留在这里，
-- 由本迁移删掉：留着两张永远空、谁也不再查的表，比删掉更糟——它会让下一个人
-- 以为工单还在业务侧。
--
-- 工单数据本身没丢：它现在在工单服务自己的库里（那边 V1 重建了同样的结构）。
-- biz-mock 侧留下的只有两个**指向工单号**的列（refunds.ticket_id / feedback.ticket_id），
-- 它们是字符串引用，不是外键，跨库也照样能用。
--
-- 这是一次真实的搬迁，所以它登记为本仓第一条「数据随服务走」的迁移：
-- 前提是内存库（CONTEXT.md 的可查工单条：可查作用域本来就在进程生命周期内）。
drop table if exists tickets;
drop table if exists routing_rules;
