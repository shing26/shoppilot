-- round46 票 194（ADR 0081）：复核闭环。
--
-- 点踩时开的复核工单号要落回本行，复核完成时才能顺手结掉它——此前这条回指只存在于
-- 工单 payload 里，业务侧手上没有号（复核与工单是两套账：review_status 变 REVIEWED，
-- 工单却一直 OPEN）。
--
-- 可空：UP 不开复核单；开单失败（工单服务不可达）也允许为空——真源仍然是 review_status，
-- 工单只是它的工作项（见 FeedbackService#openReviewWorkItem 的原注释）。
alter table feedback add column review_ticket_id varchar(40);
