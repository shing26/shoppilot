-- 版本占位（PostgreSQL 侧，round43 票 185 / ADR 0078）。
--
-- H2 侧 V10 的 knowledge_revisions 在 PG 侧按真实 DDL 建出（不是占位空迁移）。
-- body 是有界 varchar（服务层限 20000 字），不走 @Lob/oid：ingest 拉取口走 native 投影，
-- LOB 在投影里要专门解引用（round28 的矩阵是按实体加载调的），有界文本不值得背 LOB 的账。
-- 列名避 H2 关键字：knowledge_domain / revision_scope。
create table knowledge_revisions (
    id varchar(40) not null,
    tenant_id varchar(32) not null,
    feedback_id varchar(40) not null,
    title varchar(200) not null,
    body varchar(20000) not null,
    intent varchar(32) not null,
    knowledge_domain varchar(16) not null,
    revision_scope varchar(16) not null,
    status varchar(16) not null,
    created_by varchar(64),
    actor_authenticated boolean not null default false,
    created_at timestamp(6) with time zone not null,
    published_at timestamp(6) with time zone,
    rejected_at timestamp(6) with time zone,
    primary key (id)
);
create index idx_knowledge_revision_status on knowledge_revisions (status);
