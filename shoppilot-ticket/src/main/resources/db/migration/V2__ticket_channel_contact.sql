-- round26 票 86：工单带渠道与投递目标（ADR 0059 第 2 条）
--
-- 结果回流要送回「买家当初问的那个渠道」，所以工单得记着两件事：
-- ① 他从哪个渠道来（channel），② 那个渠道的回我地址是什么（contact）。
--
-- 在此之前这两件事只以 transcript 里一句「【回执渠道】{contact}」的**人类可读文本**存在。
-- 拿字符串当投递目标，等于让一次文案改动决定邮件发不发——那是不能接受的耦合。
--
-- 两列都可空：复核单、退款审批单这类工单没有渠道，也没有投递目标。
--
-- **刻意不加索引。** 第一版这里写了 `idx_ticket_channel (tenant_id, channel)`，
-- 它当场把 `TicketQueryPlanTest.unboundedListStillHasNoTenantTimeIndex` 判红：
-- H2 拿它去满足无上界的 `where tenant_id = ?`，于是那条「无上界就是扫表」的既有守卫失效。
-- 而这条索引**根本没有消费者**——回流是按工单号取单，不是按渠道扫全表。
-- 同一个道理 round18 已经吃过一次：给没有查询要用的查询面加索引，等于花写入成本换一次计划变化。
alter table tickets add column channel varchar(16);
alter table tickets add column contact varchar(255);