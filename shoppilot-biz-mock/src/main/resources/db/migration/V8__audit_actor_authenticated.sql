-- round25 票 82：审计事件标注 actor 是否来自已验签的令牌（ADR 0058 第 4 条）
--
-- 自报身份退役之后，审计里仍然会有「不可信的 actor」——只有运维凭证在场时才会发生。
-- 但它必须能被查询面**挑出来**，否则「记了一个名字」和「记了一个有据的名字」在报表上长得一样。
--
-- 默认 false：老事件与任何忘了传这个标注的写入，一律按未认证解释。
-- 往严的一边倒，是为了让遗漏变成可查的噪声，而不是变成不可查的假账。
alter table audit_event add column actor_authenticated boolean not null default false;