-- round23 票 69：工单统一实体（ADR 0055）
--
-- tickets 从「降级工单」扩成「人工介入的统一工作项」：加来源、队列、SLA、坐席、payload 四格。
-- 刻意不新建第二张表——分流规则表（票 70）只该有一个分母，两张表就又回到「三套机制三张表」。
--
-- source 的回填口径：迁移前 tickets 里的每一行都是降级链路的终点（ADR 0009），
-- 所以历史行一律回填 DEGRADE。这是**回填**不是**分类判定**，不改变任何既有读数的分母。
alter table tickets add column source varchar(24);
update tickets set source = 'DEGRADE';
alter table tickets alter column source set not null;

-- 队列与坐席留给票 70/72：现在先允许为空，空值就是「尚未分派」，不是一个待补的默认队列。
alter table tickets add column queue varchar(32);
alter table tickets add column assignee varchar(32);
alter table tickets add column sla_deadline timestamp(6) with time zone;

-- payload：来源有上游记录时（反馈复核、退款审批）用 JSON 指回上游；自包含来源（降级、渠道回执）
-- 用行内列还原上下文，payload 留空——不为了「字段一律有值」而把已有列再抄一遍。
alter table tickets add column payload clob;

-- 退款审批工单的回指：payload 之外再给一列，让「这笔退款对应的工单是哪张」是可查的，
-- 而不是只能解析 JSON（审批是不可逆的资金动作，它的责任链不能建立在字符串解析上）。
alter table refunds add column ticket_id varchar(40);
